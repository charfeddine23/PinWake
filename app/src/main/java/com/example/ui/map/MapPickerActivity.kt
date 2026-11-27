package com.example.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import com.example.util.GpsEnforcementHelper
import com.example.util.LanguageManager
import com.example.util.MapStyleHelper
import com.example.util.NavigationAssistCalculator
import com.example.util.NavigationAssistHelper
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.example.util.PreAlertPreferences
import com.example.util.SafeMapLocationEngine
import com.example.util.ShareTripHelper
import com.example.util.TransitionHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var navAssistLocationListener: LocationListener? = null
    private var navAssistIntervalMs: Long = -1L
    private var latestUserLocation: Location? = null
    private var isResumedForNavAssist: Boolean = false

    private var userEditedTitle = false
    private var isProgrammaticTitleUpdate = false
    private var isSyncing3dSwitch = false
    private var isSyncingVibrateSwitch = false
    private var isPinLifted = false
    private var lockedGeocodeCoord: Pair<Double, Double>? = null
    private var pendingCameraTarget: Triple<Double, Double, Double>? = null
    private var pendingSaveArgs: Pair<String, String>? = null
    private var currentLat: Double = DEFAULT_LAT
    private var currentLon: Double = DEFAULT_LON
    private var promptedGpsThisSession = false
    private val safeLocationEngine by lazy { SafeMapLocationEngine(this) }

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val pickedUri = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        }
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (pickedUri == null || pickedUri == defaultUri) {
            viewModel.updateRingtone(uri = null, title = null)
        } else {
            val resolvedTitle = runCatching {
                RingtoneManager.getRingtone(this, pickedUri)?.getTitle(this)
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: pickedUri.lastPathSegment
                ?: getString(R.string.ringtone_default_alarm)
            viewModel.updateRingtone(
                uri = pickedUri.toString(),
                title = resolvedTitle.take(80)
            )
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it }
        val pendingSave = pendingSaveArgs
        pendingSaveArgs = null
        if (pendingSave != null) {
            if (granted) {
                viewModel.saveAndActivateAlarm(pendingSave.first, pendingSave.second, activateNow = true) {
                    PinWakeService.startOrSync(this)
                    PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this) {
                        if (!isDestroyed && !isFinishing) TransitionHelper.finishSmooth(this)
                    }
                }
            } else {
                viewModel.saveAndActivateAlarm(pendingSave.first, pendingSave.second, activateNow = false) {
                    Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
                    TransitionHelper.finishSmooth(this)
                }
            }
            return@registerForActivityResult
        }
        if (granted) {
            mapLibreMap?.style?.let { style ->
                if (style.isFullyLoaded) enableLocationComponent(style)
            }
            centerOnCurrentLocation(silentIfUnavailable = false)
            startOrSyncNavAssistLocationUpdates()
            renderPickerNavAssist()
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.cardMapStyleMenu.visibility == View.VISIBLE -> {
                        TransitionHelper.animateLayoutChange(binding.mapRoot, 170L)
                        binding.cardMapStyleMenu.visibility = View.GONE
                    }
                    binding.cardSearchResults.visibility == View.VISIBLE -> {
                        TransitionHelper.animateLayoutChange(binding.mapRoot, 170L)
                        viewModel.clearSearchResults()
                        binding.cardSearchResults.visibility = View.GONE
                        binding.cardPickerNavAssist.visibility = View.VISIBLE
                    }
                    else -> TransitionHelper.finishSmooth(this@MapPickerActivity)
                }
            }
        })

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
        val hasSharedCoords = intent.hasExtra(EXTRA_SHARED_LAT) && intent.hasExtra(EXTRA_SHARED_LON)
        val sharedLat = intent.getDoubleExtra(EXTRA_SHARED_LAT, DEFAULT_LAT)
        val sharedLon = intent.getDoubleExtra(EXTRA_SHARED_LON, DEFAULT_LON)
        val sharedLabel = intent.getStringExtra(EXTRA_SHARED_LABEL)
        val sharedSearchQuery = intent.getStringExtra(EXTRA_SHARED_SEARCH_QUERY)

        val lang = LanguageManager.getCurrentLanguageCode(this)
        viewModel.initialize(alarmId, defaultCategory, lang) { existing ->
            if (isDestroyed || isFinishing) return@initialize
            when {
                existing != null -> {
                    userEditedTitle = true
                    lockedGeocodeCoord = existing.latitude to existing.longitude
                    setTitleProgrammatically(existing.title)
                    val snappedRadius = (((existing.radiusMeters.coerceIn(10, 5000) + 5) / 10) * 10)
                        .coerceIn(10, 5000)
                    if (snappedRadius != existing.radiusMeters) {
                        viewModel.updateRadius(snappedRadius)
                    }
                    binding.sliderRadius.value = snappedRadius.toFloat()
                    binding.tvRadiusValue.text = DistanceFormatter.formatRadiusSliderValue(this, snappedRadius)
                    binding.btnSaveAlarm.setText(R.string.map_update_alarm)
                    selectCategoryChip(existing.favoriteCategory)
                    moveCameraTo(
                        lat = existing.latitude,
                        lon = existing.longitude,
                        zoom = if (snappedRadius <= 100) 17.5 else 16.0,
                        animate = false,
                        triggerReverseGeocode = false
                    )
                    updateCircleOverlay(existing.latitude, existing.longitude, snappedRadius)
                }
                hasSharedCoords -> {
                    selectCategoryChip(defaultCategory)
                    if (!sharedLabel.isNullOrBlank()) {
                        userEditedTitle = true
                        setTitleProgrammatically(sharedLabel)
                    }
                    moveCameraTo(
                        lat = sharedLat,
                        lon = sharedLon,
                        zoom = 16.0,
                        animate = false,
                        triggerReverseGeocode = true
                    )
                    Toast.makeText(this, R.string.share_to_pin_loaded, Toast.LENGTH_SHORT).show()
                }
                !sharedSearchQuery.isNullOrBlank() -> {
                    selectCategoryChip(defaultCategory)
                    preFillCategoryTitle(defaultCategory)
                    binding.etSearchPlace.setText(sharedSearchQuery)
                    binding.etSearchPlace.setSelection(sharedSearchQuery.length)
                    Toast.makeText(this, R.string.share_to_pin_search, Toast.LENGTH_SHORT).show()
                }
                else -> {
                    selectCategoryChip(defaultCategory)
                    preFillCategoryTitle(defaultCategory)
                    centerOnCurrentLocation(silentIfUnavailable = true)
                }
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
                    lockedGeocodeCoord = null
                    liftPinUp()
                }
            }

            map.addOnCameraMoveListener {
                val target = map.cameraPosition.target ?: return@addOnCameraMoveListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
                renderPickerNavAssist()
            }

            map.addOnCameraIdleListener {
                dropPinDown()
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                currentLat = target.latitude
                currentLon = target.longitude
                updateCircleOverlay(target.latitude, target.longitude, viewModel.uiState.value.radiusMeters)
                viewModel.updateCoordinatesImmediate(target.latitude, target.longitude)
                renderPickerNavAssist()
                val locked = lockedGeocodeCoord
                val stillOnLockedSpot = locked != null &&
                    kotlin.math.abs(locked.first - target.latitude) < 0.0002 &&
                    kotlin.math.abs(locked.second - target.longitude) < 0.0002
                if (!stillOnLockedSpot) {
                    lockedGeocodeCoord = null
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

        val editingId = viewModel.uiState.value.existingAlarm?.id
        MapStyleHelper.ensureSavedPlaceMarkersLayer(
            context = this,
            style = style,
            sourceId = SAVED_PLACES_SOURCE_ID,
            haloLayerId = SAVED_PLACES_HALO_LAYER_ID,
            symbolLayerId = SAVED_PLACES_SYMBOL_LAYER_ID,
            favorites = viewModel.allMapPins.value.filter { it.id != editingId },
            isDark = styleOption.isDark
        )

        if (hasLocationPermission()) {
            enableLocationComponent(style)
        }
        renderPickerNavAssist()
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

        binding.layoutCrosshairContainer.animate().cancel()
        binding.layoutCrosshairContainer.animate()
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

        binding.layoutCrosshairContainer.animate().cancel()
        binding.layoutCrosshairContainer.animate()
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
            binding.cardPickerNavAssist.visibility = View.VISIBLE
            if (!userEditedTitle || binding.etAlarmTitle.text.isNullOrBlank()) {
                setTitleProgrammatically(place.shortTitle())
            }
            lockedGeocodeCoord = place.latitudeDouble() to place.longitudeDouble()
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
            binding.cardPickerNavAssist.visibility = View.VISIBLE
        }
    }

    private fun setupControls() {
        binding.btnMapBack.setOnClickListener { TransitionHelper.finishSmooth(this) }

        listOf(
            binding.btnMapBack,
            binding.fabMapStyle,
            binding.fabMyLocation,
            binding.btnPickerToggleRoute,
            binding.btnPickerNavigate,
            binding.btnSharePin,
            binding.rowAlarmRingtone,
            binding.btnSaveAlarm
        ).forEach { TransitionHelper.attachLuxuryPressFeedback(it) }

        binding.fabMapStyle.setOnClickListener {
            TransitionHelper.animateLayoutChange(binding.mapRoot, 180L)
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
                if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                    GpsEnforcementHelper.showEnableGpsDialog(
                        context = this,
                        mandatoryForActiveTracking = false
                    )
                    centerOnCurrentLocation(silentIfUnavailable = true)
                } else {
                    centerOnCurrentLocation(silentIfUnavailable = false)
                }
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
            syncCrosshairPinType(cat)
            if (!userEditedTitle) {
                preFillCategoryTitle(cat)
            }
        }

        binding.btnSharePin.setOnClickListener {
            val state = viewModel.uiState.value
            val label = binding.etAlarmTitle.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: state.suggestedTitle.ifBlank { state.resolvedAddress }
            val shareText = ShareTripHelper.buildDestinationShareText(
                context = this,
                title = label,
                latitude = currentLat,
                longitude = currentLon,
                radiusMeters = state.radiusMeters
            )
            ShareTripHelper.launchShareSheet(
                context = this,
                text = shareText,
                chooserTitle = getString(R.string.share_chooser_title)
            )
        }

        binding.rowAlarmRingtone.setOnClickListener {
            launchRingtonePicker()
        }

        binding.rowAlarmVibrate.setOnClickListener {
            binding.switchAlarmVibrate.toggle()
        }

        binding.switchAlarmVibrate.setOnCheckedChangeListener { _, isChecked ->
            if (!isSyncingVibrateSwitch) {
                viewModel.updateVibrateEnabled(isChecked)
            }
        }

        binding.btnPickerToggleRoute.setOnClickListener {
            val nextEnabled = !PreAlertPreferences.isNavRouteLineEnabled(this)
            PreAlertPreferences.setNavRouteLineEnabled(this, nextEnabled)
            startOrSyncNavAssistLocationUpdates()
            renderPickerNavAssist()
        }

        binding.btnPickerNavigate.setOnClickListener {
            val state = viewModel.uiState.value
            val label = binding.etAlarmTitle.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: state.suggestedTitle.ifBlank { state.resolvedAddress }
            NavigationAssistHelper.launchTurnByTurnNavigation(
                context = this,
                latitude = currentLat,
                longitude = currentLon,
                label = label,
                alarmId = state.existingAlarm?.id ?: -1L,
                radiusMeters = state.radiusMeters
            )
        }

        binding.btnSaveAlarm.setOnClickListener {
            val state = viewModel.uiState.value
            val isOffline = GpsEnforcementHelper.isOffline(this)
            val enteredTitle = binding.etAlarmTitle.text?.toString()?.trim().orEmpty()
            val finalTitle = enteredTitle.ifBlank {
                state.suggestedTitle.ifBlank {
                    if (isOffline) getString(R.string.offline_gps_pin_title) else ""
                }
            }
            if (finalTitle.isBlank()) {
                binding.tilAlarmTitle.error = getString(R.string.map_error_empty_title)
                return@setOnClickListener
            }
            binding.tilAlarmTitle.error = null

            val fallbackAddress = if (isOffline) {
                getString(R.string.offline_gps_pin_address, state.latitude, state.longitude)
            } else {
                getString(R.string.map_unknown_address, state.latitude, state.longitude)
            }

            if (PermissionHelper.hasTrackingLocation(this)) {
                viewModel.saveAndActivateAlarm(finalTitle, fallbackAddress, activateNow = true) {
                    PinWakeService.startOrSync(this)
                    if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                        GpsEnforcementHelper.showEnableGpsDialog(
                            context = this,
                            mandatoryForActiveTracking = true,
                            onOpenSettings = { TransitionHelper.finishSmooth(this) },
                            onDismissed = { TransitionHelper.finishSmooth(this) }
                        )
                    } else {
                        PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this) {
                            if (!isDestroyed && !isFinishing) TransitionHelper.finishSmooth(this)
                        }
                    }
                }
            } else {
                pendingSaveArgs = finalTitle to fallbackAddress
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun launchRingtonePicker() {
        val currentUriString = viewModel.uiState.value.ringtoneUri
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val existingUri = currentUriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: defaultUri
        val pickerIntent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.map_ringtone_label))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, defaultUri)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
        }
        runCatching { ringtonePickerLauncher.launch(pickerIntent) }
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
                            GpsEnforcementHelper.isOffline(this@MapPickerActivity) ->
                                getString(R.string.offline_gps_pin_address, state.latitude, state.longitude)
                            else -> getString(R.string.map_unknown_address, state.latitude, state.longitude)
                        }

                        binding.tvSelectedRingtone.text =
                            state.ringtoneTitle?.takeIf { it.isNotBlank() }
                                ?: getString(R.string.ringtone_default_alarm)

                        if (binding.switchAlarmVibrate.isChecked != state.vibrateEnabled) {
                            isSyncingVibrateSwitch = true
                            binding.switchAlarmVibrate.isChecked = state.vibrateEnabled
                            isSyncingVibrateSwitch = false
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
                        val hasSearchResults = state.searchResults.isNotEmpty()
                        val wasShowingSearch = binding.cardSearchResults.visibility == View.VISIBLE
                        if (wasShowingSearch != hasSearchResults) {
                            TransitionHelper.animateLayoutChange(binding.mapRoot, 180L)
                        }
                        binding.cardSearchResults.visibility =
                            if (hasSearchResults) View.VISIBLE else View.GONE
                        binding.cardPickerNavAssist.visibility =
                            if (hasSearchResults) View.GONE else View.VISIBLE

                        if (state.searchError) {
                            Toast.makeText(
                                this@MapPickerActivity,
                                R.string.map_search_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                            viewModel.consumeSearchError()
                        }
                    }
                }
                launch {
                    combine(
                        viewModel.allMapPins,
                        viewModel.uiState.map { it.existingAlarm?.id }.distinctUntilChanged()
                    ) { pins, editingId ->
                        pins.filter { it.id != editingId }
                    }.collect { visiblePins ->
                        val style = mapLibreMap?.style ?: return@collect
                        if (style.isFullyLoaded) {
                            val isDark = OpenFreeMapStyles.getSavedStyle(this@MapPickerActivity).isDark
                            MapStyleHelper.ensureSavedPlaceMarkersLayer(
                                context = this@MapPickerActivity,
                                style = style,
                                sourceId = SAVED_PLACES_SOURCE_ID,
                                haloLayerId = SAVED_PLACES_HALO_LAYER_ID,
                                symbolLayerId = SAVED_PLACES_SYMBOL_LAYER_ID,
                                favorites = visiblePins,
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
        renderPickerNavAssist()
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
        val best = GpsEnforcementHelper.getBestLocation(this)

        if (best != null) {
            latestUserLocation = best
            moveCameraTo(best.latitude, best.longitude, 16.0, animate = true)
            return
        }

        val enabledProviders = buildList {
            if (runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (runCatching { locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.PASSIVE_PROVIDER)
            }
            if (runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (enabledProviders.isEmpty()) {
            if (!silentIfUnavailable) {
                GpsEnforcementHelper.showEnableGpsDialog(
                    context = this,
                    mandatoryForActiveTracking = false
                )
            }
            return
        }

        if (!silentIfUnavailable && GpsEnforcementHelper.isOffline(this)) {
            Toast.makeText(this, R.string.offline_gps_waiting_satellite, Toast.LENGTH_SHORT).show()
        }

        activeSingleFixListener?.let { old ->
            runCatching { locationManager.removeUpdates(old) }
        }

        val singleFixListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { locationManager.removeUpdates(this) }
                activeSingleFixListener = null
                latestUserLocation = location
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

        enabledProviders.forEach { providerName ->
            runCatching {
                locationManager.requestLocationUpdates(
                    providerName,
                    0L,
                    0f,
                    singleFixListener,
                    Looper.getMainLooper()
                )
            }
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
        syncCrosshairPinType(category)
    }

    private fun syncCrosshairPinType(category: FavoriteCategory) {
        val (iconRes, tintColorRes) = when (category) {
            FavoriteCategory.HOME -> R.drawable.ic_home to R.color.accent_emerald
            FavoriteCategory.WORK -> R.drawable.ic_work to R.color.accent_amber
            FavoriteCategory.STATION -> R.drawable.ic_train to R.color.accent_cyan_glow
            FavoriteCategory.CUSTOM -> R.drawable.ic_star to R.color.accent_cyan_glow
            FavoriteCategory.NONE -> R.drawable.ic_alarm_bell to R.color.accent_cyan
        }
        binding.ivCrosshair.setColorFilter(ContextCompat.getColor(this, tintColorRes))
        binding.ivCrosshairCategoryIcon.setImageResource(iconRes)
        binding.ivCrosshairCategoryIcon.animate().cancel()
        binding.ivCrosshairCategoryIcon.scaleX = 0.82f
        binding.ivCrosshairCategoryIcon.scaleY = 0.82f
        binding.ivCrosshairCategoryIcon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator(1.8f))
            .start()
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
        isResumedForNavAssist = true
        binding.mapView.onResume()
        if (hasLocationPermission() && latestUserLocation == null) {
            latestUserLocation = GpsEnforcementHelper.getBestLocation(this)
        }
        startOrSyncNavAssistLocationUpdates()
        renderPickerNavAssist()
        if (!promptedGpsThisSession &&
            hasLocationPermission() &&
            !GpsEnforcementHelper.isGpsProviderEnabled(this)
        ) {
            promptedGpsThisSession = true
            GpsEnforcementHelper.showEnableGpsDialog(
                context = this,
                mandatoryForActiveTracking = false
            )
        }
    }

    override fun onPause() {
        isResumedForNavAssist = false
        stopNavAssistLocationUpdates()
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
        isResumedForNavAssist = false
        stopNavAssistLocationUpdates()
        activeSingleFixListener?.let { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }
        activeSingleFixListener = null
        runCatching {
            mapLibreMap?.locationComponent?.takeIf { it.isLocationComponentActivated }?.isLocationComponentEnabled = false
        }
        binding.layoutCrosshairContainer.animate().cancel()
        binding.viewPinShadow.animate().cancel()
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startOrSyncNavAssistLocationUpdates() {
        if (!isResumedForNavAssist || !hasLocationPermission()) {
            stopNavAssistLocationUpdates()
            return
        }
        val routeEnabled = PreAlertPreferences.isNavRouteLineEnabled(this)
        val batterySaver = PreAlertPreferences.isBatterySaverEnabled(this)
        val desiredIntervalMs = when {
            batterySaver && routeEnabled -> 8_000L
            batterySaver -> 20_000L
            routeEnabled -> 4_000L
            else -> 10_000L
        }
        if (navAssistLocationListener != null && navAssistIntervalMs == desiredIntervalMs) {
            return
        }

        stopNavAssistLocationUpdates()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                latestUserLocation = location
                if (!isDestroyed && !isFinishing) {
                    renderPickerNavAssist()
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        var registeredAny = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        desiredIntervalMs,
                        10f,
                        listener,
                        Looper.getMainLooper()
                    )
                    registeredAny = true
                }
            }
        }
        if (registeredAny) {
            navAssistLocationListener = listener
            navAssistIntervalMs = desiredIntervalMs
        }
    }

    private fun stopNavAssistLocationUpdates() {
        navAssistLocationListener?.let { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }
        navAssistLocationListener = null
        navAssistIntervalMs = -1L
    }

    private fun renderPickerNavAssist() {
        val routeEnabled = PreAlertPreferences.isNavRouteLineEnabled(this)
        if (routeEnabled) {
            binding.btnPickerToggleRoute.setBackgroundResource(R.drawable.bg_luxury_glass_pill_cyan)
            val cyan = ContextCompat.getColor(this, R.color.accent_cyan_glow)
            binding.ivPickerToggleRoute.setColorFilter(cyan)
            binding.tvPickerToggleRoute.setTextColor(cyan)
            binding.tvPickerToggleRoute.setText(R.string.nav_assist_route_on)
        } else {
            binding.btnPickerToggleRoute.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            val secondary = ContextCompat.getColor(this, R.color.text_secondary)
            binding.ivPickerToggleRoute.setColorFilter(secondary)
            binding.tvPickerToggleRoute.setTextColor(secondary)
            binding.tvPickerToggleRoute.setText(R.string.nav_assist_route_off)
        }

        val userLoc = latestUserLocation
            ?: (if (hasLocationPermission()) GpsEnforcementHelper.getBestLocation(this) else null)

        if (userLoc != null) {
            val metrics = NavigationAssistCalculator.computeMetrics(
                currentLat = userLoc.latitude,
                currentLon = userLoc.longitude,
                currentSpeedMps = if (userLoc.hasSpeed()) userLoc.speed else null,
                targetLat = currentLat,
                targetLon = currentLon
            )
            binding.tvPickerNavMetrics.text = NavigationAssistCalculator.formatDistanceAndBearing(
                context = this,
                distanceMeters = metrics.distanceMeters,
                bearingDegrees = metrics.bearingDegrees
            )
            binding.tvPickerNavEta.text = NavigationAssistCalculator.formatEtaCompact(
                context = this,
                etaSeconds = metrics.etaSeconds,
                isLiveSpeed = metrics.isLiveSpeedEta
            )
        } else {
            binding.tvPickerNavMetrics.text = getString(R.string.nav_assist_waiting_gps)
            binding.tvPickerNavEta.text = getString(R.string.nav_assist_eta_unknown)
        }

        val style = mapLibreMap?.style
        if (style != null && style.isFullyLoaded) {
            MapStyleHelper.ensureNavigationRouteLineLayer(
                style = style,
                sourceId = PICKER_NAV_ROUTE_SOURCE_ID,
                lineLayerId = PICKER_NAV_ROUTE_LINE_ID,
                fromLat = userLoc?.latitude,
                fromLon = userLoc?.longitude,
                toLat = currentLat,
                toLon = currentLon,
                visible = routeEnabled,
                belowLayerId = SAVED_PLACES_HALO_LAYER_ID
            )
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_DEFAULT_CATEGORY = "extra_default_category"
        const val EXTRA_SHARED_LAT = "extra_shared_lat"
        const val EXTRA_SHARED_LON = "extra_shared_lon"
        const val EXTRA_SHARED_LABEL = "extra_shared_label"
        const val EXTRA_SHARED_SEARCH_QUERY = "extra_shared_search_query"

        private const val DEFAULT_LAT = 48.8566
        private const val DEFAULT_LON = 2.3522

        private const val GEOFENCE_SOURCE_ID = "pinwake-geofence-source"
        private const val GEOFENCE_FILL_LAYER_ID = "pinwake-geofence-fill"
        private const val GEOFENCE_LINE_LAYER_ID = "pinwake-geofence-line"
        private const val SAVED_PLACES_SOURCE_ID = "pinwake-saved-places-source"
        private const val SAVED_PLACES_HALO_LAYER_ID = "pinwake-saved-places-halo"
        private const val SAVED_PLACES_SYMBOL_LAYER_ID = "pinwake-saved-places-symbol"
        private const val PICKER_NAV_ROUTE_SOURCE_ID = "pinwake-picker-nav-route-source"
        private const val PICKER_NAV_ROUTE_LINE_ID = "pinwake-picker-nav-route-line"
    }
}
