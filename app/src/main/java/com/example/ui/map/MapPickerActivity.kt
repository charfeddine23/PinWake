package com.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
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
import com.example.util.DistanceFormatter
import com.example.util.LanguageManager
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding
    private val viewModel: MapPickerViewModel by viewModels {
        MapPickerViewModel.Factory((application as PinWakeApplication).container.repository)
    }

    private lateinit var searchAdapter: SearchResultAdapter
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var locationManager: LocationManager

    private var userEditedTitle = false
    private var isProgrammaticTitleUpdate = false
    private var pendingCameraTarget: Triple<Double, Double, Double>? = null
    private var currentLat: Double = DEFAULT_LAT
    private var currentLon: Double = DEFAULT_LON

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        if (granted) {
            mapLibreMap?.style?.let { style ->
                if (style.isFullyLoaded) enableLocationComponent(style)
            }
            centerOnCurrentLocation(silentIfUnavailable = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!"robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) {
            MapLibre.getInstance(this)
        }
        enableEdgeToEdge()
        binding = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(savedInstanceState)
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
                binding.tvRadiusValue.text = DistanceFormatter.formatRadiusSliderValue(this, existing.radiusMeters)
                binding.btnSaveAlarm.setText(R.string.map_update_alarm)
                selectCategoryChip(existing.favoriteCategory)
                moveCameraTo(existing.latitude, existing.longitude, 15.5, animate = false)
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
        val initialStyle = OpenFreeMapStyles.getSavedStyle(this)
        highlightSelectedMapStyle(initialStyle)

        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            map.uiSettings.apply {
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = false
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }

            val initialTarget = pendingCameraTarget
            pendingCameraTarget = null
            val startLat = initialTarget?.first ?: DEFAULT_LAT
            val startLon = initialTarget?.second ?: DEFAULT_LON
            val startZoom = initialTarget?.third ?: 14.5
            currentLat = startLat
            currentLon = startLon

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(startLat, startLon))
                .zoom(startZoom)
                .build()

            map.addOnCameraMoveListener {
                val target = map.cameraPosition.target ?: return@addOnCameraMoveListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
            }

            map.addOnCameraIdleListener {
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
                viewModel.onMapCenterSettled(
                    target.latitude,
                    target.longitude,
                    LanguageManager.getCurrentLanguageCode(this@MapPickerActivity)
                )
            }

            map.addOnMapClickListener {
                if (binding.cardMapStyleMenu.visibility == View.VISIBLE) {
                    binding.cardMapStyleMenu.visibility = View.GONE
                    true
                } else {
                    false
                }
            }

            loadMapStyle(initialStyle)
        }
    }

    private fun loadMapStyle(styleOption: OpenFreeMapStyle) {
        val map = mapLibreMap ?: return
        map.setStyle(Style.Builder().fromUri(styleOption.styleUrl)) { loadedStyle ->
            if (styleOption == OpenFreeMapStyle.DARK) {
                loadedStyle.getLayerAs<BackgroundLayer>("background")?.setProperties(
                    PropertyFactory.backgroundColor("#0B0F19")
                )
            }
            ensureGeofenceLayers(
                style = loadedStyle,
                lat = currentLat,
                lon = currentLon,
                radiusMeters = viewModel.uiState.value.radiusMeters
            )
            if (hasLocationPermission()) {
                enableLocationComponent(loadedStyle)
            }
        }
    }

    private fun ensureGeofenceLayers(style: Style, lat: Double, lon: Double, radiusMeters: Int) {
        val circleFeature = createGeodesicCircleFeature(lat, lon, radiusMeters)
        val existingSource = style.getSourceAs<GeoJsonSource>(GEOFENCE_SOURCE_ID)
        if (existingSource == null) {
            val source = GeoJsonSource(GEOFENCE_SOURCE_ID, circleFeature)
            style.addSource(source)
        } else {
            existingSource.setGeoJson(circleFeature)
        }

        if (style.getLayer(GEOFENCE_FILL_LAYER_ID) == null) {
            val fillLayer = FillLayer(GEOFENCE_FILL_LAYER_ID, GEOFENCE_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.fillColor("#22D3EE"),
                    PropertyFactory.fillOpacity(0.18f)
                )
            }
            style.addLayer(fillLayer)
        }

        if (style.getLayer(GEOFENCE_LINE_LAYER_ID) == null) {
            val lineLayer = LineLayer(GEOFENCE_LINE_LAYER_ID, GEOFENCE_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.lineColor("#00E5FF"),
                    PropertyFactory.lineWidth(2.5f),
                    PropertyFactory.lineOpacity(0.95f)
                )
            }
            style.addLayer(lineLayer)
        }
    }

    private fun updateCircleOverlay(lat: Double, lon: Double, radiusMeters: Int) {
        val style = mapLibreMap?.style ?: return
        if (!style.isFullyLoaded) return
        val source = style.getSourceAs<GeoJsonSource>(GEOFENCE_SOURCE_ID) ?: return
        source.setGeoJson(createGeodesicCircleFeature(lat, lon, radiusMeters))
    }

    private fun createGeodesicCircleFeature(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        steps: Int = 72
    ): Feature {
        val earthRadiusMeters = 6371008.8
        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val angularDistance = radiusMeters.toDouble() / earthRadiusMeters

        val ring = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val bearing = 2.0 * Math.PI * (i % steps) / steps
            val pointLat = asin(
                sin(latRad) * cos(angularDistance) +
                    cos(latRad) * sin(angularDistance) * cos(bearing)
            )
            val pointLon = lonRad + atan2(
                sin(bearing) * sin(angularDistance) * cos(latRad),
                cos(angularDistance) - sin(latRad) * sin(pointLat)
            )
            ring.add(Point.fromLngLat(Math.toDegrees(pointLon), Math.toDegrees(pointLat)))
        }
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
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

        binding.fabMapStyle.setOnClickListener {
            binding.cardMapStyleMenu.visibility =
                if (binding.cardMapStyleMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        binding.optMapDark.setOnClickListener { applyMapStyle(OpenFreeMapStyle.DARK) }
        binding.optMapFiord.setOnClickListener { applyMapStyle(OpenFreeMapStyle.FIORD) }
        binding.optMapLiberty.setOnClickListener { applyMapStyle(OpenFreeMapStyle.LIBERTY) }
        binding.optMapPositron.setOnClickListener { applyMapStyle(OpenFreeMapStyle.POSITRON) }

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
            updateCircleOverlay(currentLat, currentLon, radius)
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
                viewModel.uiState.value.suggestedTitle.ifBlank { "" }
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

    private fun applyMapStyle(style: OpenFreeMapStyle) {
        OpenFreeMapStyles.saveStyle(this, style)
        highlightSelectedMapStyle(style)
        binding.cardMapStyleMenu.visibility = View.GONE
        loadMapStyle(style)
    }

    private fun highlightSelectedMapStyle(active: OpenFreeMapStyle) {
        val options = listOf(
            binding.optMapDark to OpenFreeMapStyle.DARK,
            binding.optMapFiord to OpenFreeMapStyle.FIORD,
            binding.optMapLiberty to OpenFreeMapStyle.LIBERTY,
            binding.optMapPositron to OpenFreeMapStyle.POSITRON
        )
        options.forEach { (view, style) ->
            if (style == active) {
                view.setBackgroundResource(R.drawable.bg_badge_cyan)
                view.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan_glow))
            } else {
                view.background = null
                view.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
    }

    private fun moveCameraTo(lat: Double, lon: Double, zoom: Double? = null, animate: Boolean = true) {
        currentLat = lat
        currentLon = lon
        val map = mapLibreMap
        if (map == null) {
            pendingCameraTarget = Triple(lat, lon, zoom ?: 15.5)
            updateCircleOverlay(lat, lon, viewModel.uiState.value.radiusMeters)
            viewModel.onMapCenterSettled(lat, lon, LanguageManager.getCurrentLanguageCode(this))
            return
        }

        val targetZoom = zoom ?: map.cameraPosition.zoom
        val position = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(targetZoom)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(position)
        if (animate) {
            map.animateCamera(update, 450)
        } else {
            map.moveCamera(update)
        }
        updateCircleOverlay(lat, lon, viewModel.uiState.value.radiusMeters)
        viewModel.onMapCenterSettled(lat, lon, LanguageManager.getCurrentLanguageCode(this))
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(style: Style) {
        if (!hasLocationPermission()) return
        val map = mapLibreMap ?: return
        runCatching {
            val cyanAccent = ContextCompat.getColor(this, R.color.accent_cyan)
            val options = LocationComponentOptions.builder(this)
                .pulseEnabled(true)
                .pulseColor(cyanAccent)
                .foregroundTintColor(cyanAccent)
                .accuracyColor(ContextCompat.getColor(this, R.color.geofence_fill))
                .build()

            val activationOptions = LocationComponentActivationOptions.builder(this, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(true)
                .build()

            map.locationComponent.apply {
                activateLocationComponent(activationOptions)
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.NORMAL
            }
        }
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

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroy() {
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_DEFAULT_CATEGORY = "extra_default_category"

        private const val DEFAULT_LAT = 48.8566
        private const val DEFAULT_LON = 2.3522

        private const val GEOFENCE_SOURCE_ID = "pinwake-geofence-source"
        private const val GEOFENCE_FILL_LAYER_ID = "pinwake-geofence-fill"
        private const val GEOFENCE_LINE_LAYER_ID = "pinwake-geofence-line"
    }
}
