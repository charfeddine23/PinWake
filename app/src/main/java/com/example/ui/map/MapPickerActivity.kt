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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import com.example.util.MapStyleHelper
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.example.util.SafeMapLocationEngine
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
import org.maplibre.geojson.FeatureCollection
import java.util.Locale

class MapPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMapPickerBinding
    private val viewModel: MapPickerViewModel by viewModels {
        MapPickerViewModel.Factory((application as PinWakeApplication).container.repository)
    }

    private lateinit var searchAdapter: SearchResultAdapter
    private var mapLibreMap: MapLibreMap? = null
    private lateinit var locationManager: LocationManager
    private var activeSingleFixListener: LocationListener? = null

    private var userEditedTitle = false
    private var isProgrammaticTitleUpdate = false
    private var isSyncing3dSwitch = false
    private var isPinLifted = false
    private var suppressNextIdleGeocode = false
    private var pendingCameraTarget: Triple<Double, Double, Double>? = null
    private var currentLat: Double = DEFAULT_LAT
    private var currentLon: Double = DEFAULT_LON
    private val safeLocationEngine by lazy { SafeMapLocationEngine(this) }

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
        if (!isRobolectric()) {
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
            if (isDestroyed || isFinishing) return@initialize
            if (existing != null) {
                userEditedTitle = true
                suppressNextIdleGeocode = true
                setTitleProgrammatically(existing.title)
                binding.sliderRadius.value = existing.radiusMeters.toFloat().coerceIn(200f, 5000f)
                binding.tvRadiusValue.text = DistanceFormatter.formatRadiusSliderValue(this, existing.radiusMeters)
                binding.btnSaveAlarm.setText(R.string.map_update_alarm)
                selectCategoryChip(existing.favoriteCategory)
                moveCameraTo(
                    lat = existing.latitude,
                    lon = existing.longitude,
                    zoom = 16.0,
                    animate = false,
                    triggerReverseGeocode = false
                )
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
            val targetTopMargin = searchTopMargin + sysBars.top
            if (searchParams.topMargin != targetTopMargin) {
                searchParams.topMargin = targetTopMargin
                binding.searchBarContainer.layoutParams = searchParams
            }

            val targetBottomPad = sheetBottomPad + sysBars.bottom
            if (binding.bottomSheet.paddingBottom != targetBottomPad) {
                binding.bottomSheet.updatePadding(bottom = targetBottomPad)
            }

            val targetBlurHeight = (94f * resources.displayMetrics.density).toInt() + sysBars.top
            val blurParams = binding.topLiquidGlassBlur.layoutParams
            if (blurParams.height != targetBlurHeight) {
                blurParams.height = targetBlurHeight
                binding.topLiquidGlassBlur.layoutParams = blurParams
            }
            insets
        }
    }

    private fun setupMap() {
        val initialStyle = OpenFreeMapStyles.getSavedStyle(this)
        val is3dEnabled = OpenFreeMapStyles.is3dBuildingsEnabled(this)
        syncLayerMenuUi(initialStyle, is3dEnabled)

        if (isRobolectric()) return

        binding.mapView.getMapAsync { map ->
            if (isDestroyed || isFinishing) return@getMapAsync
            mapLibreMap = map
            MapStyleHelper.configureHighPrecisionMap(
                context = this,
                mapView = binding.mapView,
                map = map,
                allowGestures = true
            )

            val initialTarget = pendingCameraTarget
            pendingCameraTarget = null
            val startLat = initialTarget?.first ?: DEFAULT_LAT
            val startLon = initialTarget?.second ?: DEFAULT_LON
            val startZoom = initialTarget?.third ?: 15.0
            currentLat = startLat
            currentLon = startLon

            val startTilt = if (OpenFreeMapStyles.is3dBuildingsEnabled(this) && startZoom >= 15.0) 32.0 else 0.0
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(startLat, startLon))
                .zoom(startZoom)
                .tilt(startTilt)
                .build()

            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    suppressNextIdleGeocode = false
                    liftPinUp()
                }
            }

            map.addOnCameraMoveListener {
                val target = map.cameraPosition.target ?: return@addOnCameraMoveListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
            }

            map.addOnCameraIdleListener {
                dropPinDown()
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
                if (suppressNextIdleGeocode) {
                    suppressNextIdleGeocode = false
                } else {
                    viewModel.onMapCenterSettled(
                        target.latitude,
                        target.longitude,
                        LanguageManager.getCurrentLanguageCode(this@MapPickerActivity)
                    )
                }
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
        val existingStyle = map.style
        if (existingStyle != null && existingStyle.isFullyLoaded) {
            applyRuntimeStyleAndOverlays(existingStyle, styleOption)
            return
        }

        map.setStyle(Style.Builder().fromUri(styleOption.styleUrl)) { loadedStyle ->
            if (isDestroyed || isFinishing) return@setStyle
            applyRuntimeStyleAndOverlays(loadedStyle, styleOption)
        }
    }

    private fun applyRuntimeStyleAndOverlays(style: Style, styleOption: OpenFreeMapStyle) {
        val enable3d = OpenFreeMapStyles.is3dBuildingsEnabled(this)
        MapStyleHelper.applyGoogleNavigationStyling(
            style = style,
            mode = styleOption,
            enable3dBuildings = enable3d
        )

        val circleFeature = MapStyleHelper.createGeodesicCircleFeature(
            lat = currentLat,
            lon = currentLon,
            radiusMeters = viewModel.uiState.value.radiusMeters
        )
        MapStyleHelper.ensureGeofenceCircleLayers(
            style = style,
            sourceId = GEOFENCE_SOURCE_ID,
            fillLayerId = GEOFENCE_FILL_LAYER_ID,
            lineLayerId = GEOFENCE_LINE_LAYER_ID,
            features = FeatureCollection.fromFeature(circleFeature)
        )

        MapStyleHelper.ensureSavedPlaceMarkersLayer(
            context = this,
            style = style,
            sourceId = SAVED_PLACES_SOURCE_ID,
            haloLayerId = SAVED_PLACES_HALO_LAYER_ID,
            symbolLayerId = SAVED_PLACES_SYMBOL_LAYER_ID,
            favorites = viewModel.favoriteAlarms.value,
            isDark = styleOption.isDark
        )

        if (hasLocationPermission()) {
            enableLocationComponent(style)
        }
    }

    private fun updateCircleOverlay(lat: Double, lon: Double, radiusMeters: Int) {
        val style = mapLibreMap?.style ?: return
        if (!style.isFullyLoaded) return
        val feature = MapStyleHelper.createGeodesicCircleFeature(lat, lon, radiusMeters)
        MapStyleHelper.ensureGeofenceCircleLayers(
            style = style,
            sourceId = GEOFENCE_SOURCE_ID,
            fillLayerId = GEOFENCE_FILL_LAYER_ID,
            lineLayerId = GEOFENCE_LINE_LAYER_ID,
            features = FeatureCollection.fromFeature(feature)
        )
    }

    private fun liftPinUp() {
        if (isPinLifted) return
        isPinLifted = true
        val liftPx = -18f * resources.displayMetrics.density

        binding.ivCrosshair.animate().cancel()
        binding.ivCrosshair.animate()
            .translationY(liftPx)
            .scaleX(1.1f)
            .scaleY(1.1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        binding.viewPinShadow.animate().cancel()
        binding.viewPinShadow.animate()
            .scaleX(0.75f)
            .scaleY(0.75f)
            .alpha(0.55f)
            .setDuration(180L)
            .start()
    }

    private fun dropPinDown() {
        if (!isPinLifted) return
        isPinLifted = false

        binding.ivCrosshair.animate().cancel()
        binding.ivCrosshair.animate()
            .translationY(0f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(300L)
            .setInterpolator(OvershootInterpolator(1.7f))
            .start()

        binding.viewPinShadow.animate().cancel()
        binding.viewPinShadow.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(240L)
            .start()
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
            suppressNextIdleGeocode = true
            moveCameraTo(
                lat = place.latitudeDouble(),
                lon = place.longitudeDouble(),
                zoom = 16.0,
                animate = true,
                triggerReverseGeocode = false
            )
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

        binding.optMapGoogleDark.setOnClickListener {
            applyMapStyle(OpenFreeMapStyle.GOOGLE_DARK)
        }
        binding.optMapGoogleLight.setOnClickListener {
            applyMapStyle(OpenFreeMapStyle.GOOGLE_LIGHT)
        }

        binding.rowToggle3dBuildings.setOnClickListener {
            binding.switch3dBuildings.toggle()
        }

        binding.switch3dBuildings.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncing3dSwitch) return@setOnCheckedChangeListener
            OpenFreeMapStyles.set3dBuildingsEnabled(this, isChecked)
            val savedStyle = OpenFreeMapStyles.getSavedStyle(this)
            mapLibreMap?.style?.let { style ->
                if (style.isFullyLoaded) {
                    MapStyleHelper.applyGoogleNavigationStyling(style, savedStyle, isChecked)
                }
            }
            mapLibreMap?.let { map ->
                val cam = map.cameraPosition
                val target = cam.target ?: return@let
                val nextZoom = if (isChecked && cam.zoom < 15.2) 15.8 else cam.zoom
                val nextTilt = if (isChecked) 40.0 else 0.0
                MapStyleHelper.animateCameraSmooth(
                    map = map,
                    lat = target.latitude,
                    lon = target.longitude,
                    zoom = nextZoom,
                    tilt = nextTilt,
                    durationMs = 800
                )
            }
        }

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
                launch {
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
                launch {
                    viewModel.favoriteAlarms.collect { favorites ->
                        val style = mapLibreMap?.style ?: return@collect
                        if (style.isFullyLoaded) {
                            val isDark = OpenFreeMapStyles.getSavedStyle(this@MapPickerActivity).isDark
                            MapStyleHelper.ensureSavedPlaceMarkersLayer(
                                context = this@MapPickerActivity,
                                style = style,
                                sourceId = SAVED_PLACES_SOURCE_ID,
                                haloLayerId = SAVED_PLACES_HALO_LAYER_ID,
                                symbolLayerId = SAVED_PLACES_SYMBOL_LAYER_ID,
                                favorites = favorites,
                                isDark = isDark
                            )
                        }
                    }
                }
            }
        }
    }

    private fun applyMapStyle(style: OpenFreeMapStyle) {
        OpenFreeMapStyles.saveStyle(this, style)
        syncLayerMenuUi(style, OpenFreeMapStyles.is3dBuildingsEnabled(this))
        binding.cardMapStyleMenu.visibility = View.GONE
        loadMapStyle(style)
    }

    private fun syncLayerMenuUi(active: OpenFreeMapStyle, enable3d: Boolean) {
        val options = listOf(
            binding.optMapGoogleDark to OpenFreeMapStyle.GOOGLE_DARK,
            binding.optMapGoogleLight to OpenFreeMapStyle.GOOGLE_LIGHT
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
        isSyncing3dSwitch = true
        binding.switch3dBuildings.isChecked = enable3d
        isSyncing3dSwitch = false
    }

    private fun moveCameraTo(
        lat: Double,
        lon: Double,
        zoom: Double? = null,
        animate: Boolean = true,
        triggerReverseGeocode: Boolean = true
    ) {
        currentLat = lat
        currentLon = lon
        val map = mapLibreMap
        if (map == null) {
            pendingCameraTarget = Triple(lat, lon, zoom ?: 15.8)
            updateCircleOverlay(lat, lon, viewModel.uiState.value.radiusMeters)
            if (triggerReverseGeocode) {
                viewModel.onMapCenterSettled(lat, lon, LanguageManager.getCurrentLanguageCode(this))
            }
            return
        }

        val targetZoom = zoom ?: map.cameraPosition.zoom
        val tilt = if (OpenFreeMapStyles.is3dBuildingsEnabled(this) && targetZoom >= 15.0) 32.0 else 0.0
        if (animate) {
            MapStyleHelper.animateCameraSmooth(
                map = map,
                lat = lat,
                lon = lon,
                zoom = targetZoom,
                tilt = tilt,
                durationMs = 800
            )
        } else {
            val position = CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(targetZoom)
                .tilt(tilt)
                .build()
            map.moveCamera(CameraUpdateFactory.newCameraPosition(position))
        }
        updateCircleOverlay(lat, lon, viewModel.uiState.value.radiusMeters)
        if (triggerReverseGeocode) {
            viewModel.onMapCenterSettled(lat, lon, LanguageManager.getCurrentLanguageCode(this))
        }
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
                .useDefaultLocationEngine(false)
                .locationEngine(safeLocationEngine)
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
            moveCameraTo(best.latitude, best.longitude, 16.0, animate = true)
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

        activeSingleFixListener?.let { old ->
            runCatching { locationManager.removeUpdates(old) }
        }

        val singleFixListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { locationManager.removeUpdates(this) }
                activeSingleFixListener = null
                if (!isDestroyed && !isFinishing) {
                    moveCameraTo(location.latitude, location.longitude, 16.0, animate = true)
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeSingleFixListener = singleFixListener

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

    private fun isRobolectric(): Boolean {
        return "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)
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
        activeSingleFixListener?.let { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }
        activeSingleFixListener = null
        runCatching {
            mapLibreMap?.locationComponent?.takeIf { it.isLocationComponentActivated }?.isLocationComponentEnabled = false
        }
        binding.ivCrosshair.animate().cancel()
        binding.viewPinShadow.animate().cancel()
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
        private const val SAVED_PLACES_SOURCE_ID = "pinwake-saved-places-source"
        private const val SAVED_PLACES_HALO_LAYER_ID = "pinwake-saved-places-halo"
        private const val SAVED_PLACES_SYMBOL_LAYER_ID = "pinwake-saved-places-symbol"
    }
}
