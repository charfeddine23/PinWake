package com.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.PinWakeApplication
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.databinding.ActivityMapPickerBinding
import com.example.service.PinWakeService
import com.example.util.DarkMapTileSource
import com.example.util.DistanceFormatter
import com.example.util.LanguageManager
import com.example.util.PermissionHelper
import kotlinx.coroutines.launch
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding
    private val viewModel: MapPickerViewModel by viewModels {
        MapPickerViewModel.Factory((application as PinWakeApplication).container.repository)
    }

    private lateinit var searchAdapter: SearchResultAdapter
    private lateinit var geofencePolygon: Polygon
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private lateinit var locationManager: LocationManager

    private var userEditedTitle = false
    private var isProgrammaticTitleUpdate = false

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            enableMyLocationOverlay()
            centerOnCurrentLocation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(LocationManager::class.java)
        applyInsets()
        setupMap()
        setupSearch()
        setupControls()
        observeUiState()

        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        val categoryName = intent.getStringExtra(EXTRA_DEFAULT_CATEGORY)
        val defaultCategory = runCatching {
            FavoriteCategory.valueOf(categoryName ?: FavoriteCategory.NONE.name)
        }.getOrDefault(FavoriteCategory.NONE)

        val lang = LanguageManager.getCurrentLanguageCode(this)
        viewModel.initialize(alarmId, defaultCategory, lang) { existing ->
            if (existing != null) {
                userEditedTitle = true
                setTitleProgrammatically(existing.title)
                binding.sliderRadius.value = existing.radiusMeters.toFloat().coerceIn(200f, 5000f)
                binding.btnSaveAlarm.setText(R.string.map_update_alarm)
                selectCategoryChip(existing.favoriteCategory)
                moveCameraTo(existing.latitude, existing.longitude, 15.5)
            } else {
                selectCategoryChip(defaultCategory)
                preFillCategoryTitle(defaultCategory)
                centerOnCurrentLocation(silentIfUnavailable = true)
            }
        }
    }

    private fun applyInsets() {
        val searchTopMargin = (12f * resources.displayMetrics.density).toInt()
        val sheetBottomPad = binding.bottomSheet.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.mapRoot) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val searchParams = binding.searchBarContainer.layoutParams as
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            searchParams.topMargin = searchTopMargin + sysBars.top
            binding.searchBarContainer.layoutParams = searchParams

            binding.bottomSheet.updatePadding(bottom = sheetBottomPad + sysBars.bottom)
            insets
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(DarkMapTileSource.CARTO_DARK_MATTER)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(14.5)
            controller.setCenter(GeoPoint(48.8566, 2.3522))
        }

        geofencePolygon = Polygon(binding.mapView).apply {
            fillPaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.geofence_fill)
            outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.geofence_stroke)
            outlinePaint.strokeWidth = 3f * resources.displayMetrics.density
        }
        binding.mapView.overlays.add(geofencePolygon)

        if (hasLocationPermission()) {
            enableMyLocationOverlay()
        }

        binding.mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val center = binding.mapView.mapCenter
                updateCircleOverlay(center.latitude, center.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(center.latitude, center.longitude)
                viewModel.onMapCenterSettled(
                    center.latitude,
                    center.longitude,
                    LanguageManager.getCurrentLanguageCode(this@MapPickerActivity)
                )
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                val center = binding.mapView.mapCenter
                updateCircleOverlay(center.latitude, center.longitude, viewModel.uiState.value.radiusMeters)
                return false
            }
        })

        updateCircleOverlay(48.8566, 2.3522, 500)
    }

    private fun setupSearch() {
        searchAdapter = SearchResultAdapter { place ->
            hideKeyboard()
            viewModel.selectSearchResult(place)
            binding.etSearchPlace.setText(place.shortTitle())
            binding.cardSearchResults.visibility = View.GONE
            if (!userEditedTitle || binding.etAlarmTitle.text.isNullOrBlank()) {
                setTitleProgrammatically(place.shortTitle())
            }
            moveCameraTo(place.latitudeDouble(), place.longitudeDouble(), 15.5)
        }

        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@MapPickerActivity)
            adapter = searchAdapter
        }

        binding.etSearchPlace.doAfterTextChanged { editable ->
            val query = editable?.toString() ?: ""
            binding.btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            viewModel.onSearchQueryChanged(
                query,
                LanguageManager.getCurrentLanguageCode(this)
            )
        }

        binding.etSearchPlace.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearchPlace.setText("")
            viewModel.clearSearchResults()
            binding.cardSearchResults.visibility = View.GONE
        }
    }

    private fun setupControls() {
        binding.btnMapBack.setOnClickListener { finish() }

        binding.fabMyLocation.setOnClickListener {
            if (hasLocationPermission()) {
                centerOnCurrentLocation(silentIfUnavailable = false)
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }

        binding.etAlarmTitle.doAfterTextChanged {
            if (!isProgrammaticTitleUpdate) {
                userEditedTitle = !it.isNullOrBlank()
            }
        }

        binding.sliderRadius.addOnChangeListener { _, value, _ ->
            val radius = value.toInt()
            viewModel.updateRadius(radius)
            binding.tvRadiusValue.text = DistanceFormatter.formatRadiusSliderValue(this, radius)
            val center = binding.mapView.mapCenter
            updateCircleOverlay(center.latitude, center.longitude, radius)
        }
        binding.tvRadiusValue.text = DistanceFormatter.formatRadiusSliderValue(this, 500)

        binding.chipGroupCategory.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipNone
            val cat = when (checkedId) {
                R.id.chipHome -> FavoriteCategory.HOME
                R.id.chipWork -> FavoriteCategory.WORK
                R.id.chipStation -> FavoriteCategory.STATION
                R.id.chipCustom -> FavoriteCategory.CUSTOM
                else -> FavoriteCategory.NONE
            }
            viewModel.updateCategory(cat)
            if (!userEditedTitle) {
                preFillCategoryTitle(cat)
            }
        }

        binding.btnSaveAlarm.setOnClickListener {
            val enteredTitle = binding.etAlarmTitle.text?.toString()?.trim().orEmpty()
            val finalTitle = enteredTitle.ifBlank {
                viewModel.uiState.value.suggestedTitle.ifBlank {
                    ""
                }
            }
            if (finalTitle.isBlank()) {
                binding.tilAlarmTitle.error = getString(R.string.map_error_empty_title)
                return@setOnClickListener
            }
            binding.tilAlarmTitle.error = null

            val state = viewModel.uiState.value
            val fallbackAddress = getString(
                R.string.map_unknown_address,
                state.latitude,
                state.longitude
            )

            viewModel.saveAndActivateAlarm(finalTitle, fallbackAddress) {
                if (PermissionHelper.checkStatus(this).hasFineLocation) {
                    PinWakeService.startOrSync(this)
                }
                finish()
            }
        }
    }

    private fun observeUiState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.tvCoordinatesMono.text = String.format(
                        Locale.US,
                        "%.5f, %.5f",
                        state.latitude,
                        state.longitude
                    )

                    binding.tvResolvedAddress.text = when {
                        state.isResolvingAddress -> getString(R.string.map_resolving_address)
                        state.resolvedAddress.isNotBlank() -> state.resolvedAddress
                        else -> getString(R.string.map_unknown_address, state.latitude, state.longitude)
                    }

                    if (!userEditedTitle &&
                        state.suggestedTitle.isNotBlank() &&
                        binding.etAlarmTitle.text.isNullOrBlank()
                    ) {
                        setTitleProgrammatically(state.suggestedTitle)
                    }

                    binding.progressSearch.visibility =
                        if (state.isSearching) View.VISIBLE else View.GONE

                    searchAdapter.submitList(state.searchResults)
                    binding.cardSearchResults.visibility =
                        if (state.searchResults.isNotEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun updateCircleOverlay(lat: Double, lon: Double, radiusMeters: Int) {
        val center = GeoPoint(lat, lon)
        geofencePolygon.points = Polygon.pointsAsCircle(center, radiusMeters.toDouble())
        binding.mapView.invalidate()
    }

    private fun moveCameraTo(lat: Double, lon: Double, zoom: Double? = null) {
        val target = GeoPoint(lat, lon)
        zoom?.let { binding.mapView.controller.setZoom(it) }
        binding.mapView.controller.animateTo(target)
        updateCircleOverlay(lat, lon, viewModel.uiState.value.radiusMeters)
        viewModel.onMapCenterSettled(lat, lon, LanguageManager.getCurrentLanguageCode(this))
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocationOverlay() {
        if (myLocationOverlay != null || !hasLocationPermission()) return
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
            enableMyLocation()
        }
        binding.mapView.overlays.add(myLocationOverlay)
    }

    @SuppressLint("MissingPermission")
    private fun centerOnCurrentLocation(silentIfUnavailable: Boolean = false) {
        if (!hasLocationPermission()) return
        val gps = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()
        val net = runCatching {
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()

        val best = when {
            gps != null && net != null -> if (gps.time >= net.time) gps else net
            gps != null -> gps
            else -> net
        }

        if (best != null) {
            moveCameraTo(best.latitude, best.longitude, 15.5)
            return
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            if (!silentIfUnavailable) {
                Toast.makeText(this, R.string.map_location_unavailable, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val singleFixListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                locationManager.removeUpdates(this)
                moveCameraTo(location.latitude, location.longitude, 15.5)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        runCatching {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                singleFixListener,
                Looper.getMainLooper()
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun selectCategoryChip(category: FavoriteCategory) {
        val chipId = when (category) {
            FavoriteCategory.HOME -> R.id.chipHome
            FavoriteCategory.WORK -> R.id.chipWork
            FavoriteCategory.STATION -> R.id.chipStation
            FavoriteCategory.CUSTOM -> R.id.chipCustom
            FavoriteCategory.NONE -> R.id.chipNone
        }
        binding.chipGroupCategory.check(chipId)
    }

    private fun preFillCategoryTitle(category: FavoriteCategory) {
        val title = when (category) {
            FavoriteCategory.HOME -> getString(R.string.fav_home)
            FavoriteCategory.WORK -> getString(R.string.fav_work)
            FavoriteCategory.STATION -> getString(R.string.fav_station)
            else -> return
        }
        setTitleProgrammatically(title)
    }

    private fun setTitleProgrammatically(text: String) {
        isProgrammaticTitleUpdate = true
        binding.etAlarmTitle.setText(text)
        binding.etAlarmTitle.setSelection(text.length)
        isProgrammaticTitleUpdate = false
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etSearchPlace.windowToken, 0)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        myLocationOverlay?.disableMyLocation()
        binding.mapView.onPause()
        super.onPause()
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_DEFAULT_CATEGORY = "extra_default_category"
    }
}
