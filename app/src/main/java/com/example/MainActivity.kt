package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.remote.GitHubReleaseApiService
import com.example.data.repository.PollingTier
import com.example.databinding.ActivityMainBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.AlarmAdapter
import com.example.ui.dashboard.DashboardUiState
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.TripHistoryAdapter
import com.example.ui.dashboard.UpdateCheckEvent
import com.example.ui.dashboard.UpdateUiState
import com.example.ui.map.MapPickerActivity
import com.example.util.ApkUpdateManager
import com.example.util.DistanceFormatter
import com.example.util.LanguageManager
import com.example.util.MapStyleHelper
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val apkUpdateManager: ApkUpdateManager by lazy {
        (application as PinWakeApplication).container.apkUpdateManager
    }

    private val viewModel: DashboardViewModel by viewModels {
        val container = (application as PinWakeApplication).container
        DashboardViewModel.Factory(container.repository, container.apkUpdateManager)
    }

    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var historyAdapter: TripHistoryAdapter
    private lateinit var sheetBehavior: BottomSheetBehavior<NestedScrollView>
    private var pendingInstallApk: File? = null
    private var heroMap: MapLibreMap? = null
    private var loadedHeroStyle: OpenFreeMapStyle? = null
    private var loadedHero3dState: Boolean? = null
    private var lastHeroGeofenceKey: String? = null
    private var lastHeroSavedPlacesKey: String? = null
    private var lastHeroCameraKey: String? = null
    private var sysTopInset: Int = 0
    private var sysBottomInset: Int = 0
    private var computedHalfSlideOffset: Float = 0.65f
    private var lastSheetState: Int = BottomSheetBehavior.STATE_HALF_EXPANDED
    private var activeSingleFixListener: LocationListener? = null
    private var pendingCenterOnLocation: Boolean = false

    private val restoreSheetBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionCard()
        syncTrackingServiceIfPossible()
        heroMap?.style?.let { style ->
            if (style.isFullyLoaded) enableHeroLocationPuck(style)
        }
        if (pendingCenterOnLocation) {
            pendingCenterOnLocation = false
            if (hasFineOrCoarseLocation()) {
                centerHeroOnCurrentLocation()
            }
        } else {
            lastHeroCameraKey = null
            updateHeroMapViewport(viewModel.uiState.value)
        }
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissionCard()
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(PermissionHelper.buildAppSettingsIntent(this))
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionCard()
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pending = pendingInstallApk
        if (pending != null && apkUpdateManager.canRequestPackageInstalls()) {
            pendingInstallApk = null
            launchApkInstaller(pending)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isRobolectric()) {
            MapLibre.getInstance(this)
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.dashboardMapView.onCreate(savedInstanceState)
        binding.tvAppVersionPill.text =
            getString(R.string.update_pill_version, BuildConfig.VERSION_NAME)

        onBackPressedDispatcher.addCallback(this, restoreSheetBackCallback)
        setupBottomSheet(savedInstanceState == null)
        applyEdgeToEdgeInsets()
        setupHeroMap()
        setupRecyclerViews()
        setupClicks()
        observeDashboard()
    }

    private fun setupBottomSheet(initialHalfExpanded: Boolean) {
        sheetBehavior = BottomSheetBehavior.from(binding.scrollDashboard).apply {
            isFitToContents = false
            isHideable = false
            skipCollapsed = false
            isDraggable = true
            if (initialHalfExpanded) {
                state = BottomSheetBehavior.STATE_HALF_EXPANDED
                lastSheetState = BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                lastSheetState = state
            }
        }

        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_SETTLING ||
                    newState == BottomSheetBehavior.STATE_DRAGGING
                ) {
                    return
                }
                val previous = lastSheetState
                lastSheetState = newState
                restoreSheetBackCallback.isEnabled = newState != BottomSheetBehavior.STATE_HALF_EXPANDED
                syncModeForSheetState(
                    state = newState,
                    animateCamera = previous != newState
                )
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                updateChromeForSlideOffset(slideOffset)
            }
        })

        binding.rootCoordinator.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateBottomSheetGeometry()
        }
    }

    override fun onStart() {
        super.onStart()
        binding.dashboardMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.dashboardMapView.onResume()
        refreshPermissionCard()
        syncTrackingServiceIfPossible()

        val savedStyle = OpenFreeMapStyles.getSavedStyle(this)
        val saved3d = OpenFreeMapStyles.is3dBuildingsEnabled(this)
        val map = heroMap
        val currentStyle = map?.style
        if (map != null && currentStyle != null && currentStyle.isFullyLoaded &&
            (loadedHeroStyle != savedStyle || loadedHero3dState != saved3d)
        ) {
            loadedHeroStyle = savedStyle
            loadedHero3dState = saved3d
            MapStyleHelper.applyGoogleNavigationStyling(
                style = currentStyle,
                mode = savedStyle,
                enable3dBuildings = saved3d
            )
            updateHeroMapViewport(viewModel.uiState.value, forceRefresh = true)
        } else {
            updateHeroMapViewport(viewModel.uiState.value)
        }
    }

    override fun onPause() {
        binding.dashboardMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.dashboardMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.dashboardMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.dashboardMapView.onLowMemory()
    }

    override fun onDestroy() {
        activeSingleFixListener?.let { listener ->
            runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        }
        activeSingleFixListener = null
        binding.dashboardMapView.onDestroy()
        super.onDestroy()
    }

    private fun applyEdgeToEdgeInsets() {
        val density = resources.displayMetrics.density
        val baseTopPad = binding.topFloatingBar.paddingTop
        val baseScrollBottom = binding.scrollDashboard.paddingBottom
        val fabBaseMargin = (20f * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sysTopInset = sysBars.top
            sysBottomInset = sysBars.bottom

            val targetTopPad = baseTopPad + sysBars.top
            if (binding.topFloatingBar.paddingTop != targetTopPad) {
                binding.topFloatingBar.updatePadding(top = targetTopPad)
            }

            val targetScrollBottom = baseScrollBottom + sysBars.bottom
            if (binding.scrollDashboard.paddingBottom != targetScrollBottom) {
                binding.scrollDashboard.updatePadding(bottom = targetScrollBottom)
            }

            val fabParams = binding.fabPinAlarm.layoutParams as
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            val targetFabMargin = fabBaseMargin + sysBars.bottom
            if (fabParams.bottomMargin != targetFabMargin) {
                fabParams.bottomMargin = targetFabMargin
                binding.fabPinAlarm.layoutParams = fabParams
            }

            val targetBlurHeight = (94f * density).toInt() + sysBars.top
            val blurParams = binding.topLiquidGlassBlur.layoutParams
            if (blurParams.height != targetBlurHeight) {
                blurParams.height = targetBlurHeight
                binding.topLiquidGlassBlur.layoutParams = blurParams
            }
            binding.topLiquidGlassBlur.pivotY = 0f

            updateBottomSheetGeometry()
            insets
        }
    }

    private fun updateBottomSheetGeometry() {
        val parentHeight = binding.rootCoordinator.height
        if (parentHeight <= 0) return

        val density = resources.displayMetrics.density
        val desiredHeroTop = (244f * density).toInt() + sysTopInset
        val expandedOffsetPx = (78f * density).toInt() + sysTopInset

        val handleHeight = if (binding.layoutSheetHandle.height > 0) {
            binding.layoutSheetHandle.height
        } else {
            (30f * density).toInt()
        }
        val whereToHeight = if (binding.btnWhereTo.height > 0) {
            binding.btnWhereTo.height
        } else {
            (62f * density).toInt()
        }
        val topPadPx = binding.contentContainer.paddingTop
        val bottomGapPx = (16f * density).toInt() + sysBottomInset
        val targetPeekHeight = (topPadPx + handleHeight + whereToHeight + bottomGapPx)
            .coerceAtMost((parentHeight * 0.45f).toInt())

        val targetHalfRatio = (1f - desiredHeroTop.toFloat() / parentHeight.toFloat())
            .coerceIn(0.45f, 0.78f)

        if (sheetBehavior.peekHeight != targetPeekHeight) {
            sheetBehavior.peekHeight = targetPeekHeight
        }
        if (sheetBehavior.expandedOffset != expandedOffsetPx) {
            sheetBehavior.expandedOffset = expandedOffsetPx
        }
        if (kotlin.math.abs(sheetBehavior.halfExpandedRatio - targetHalfRatio) > 0.005f) {
            sheetBehavior.halfExpandedRatio = targetHalfRatio
        }

        val collapsedOffset = parentHeight - targetPeekHeight
        val halfOffset = (parentHeight * (1f - targetHalfRatio)).toInt()
        val range = (collapsedOffset - expandedOffsetPx).coerceAtLeast(1)
        computedHalfSlideOffset = ((collapsedOffset - halfOffset).toFloat() / range)
            .coerceIn(0.15f, 0.95f)

        val stackParams = binding.stackQuickFavMapButtons.layoutParams as
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        val targetStackBottom = targetPeekHeight + (14f * density).toInt()
        if (stackParams.bottomMargin != targetStackBottom) {
            stackParams.bottomMargin = targetStackBottom
            binding.stackQuickFavMapButtons.layoutParams = stackParams
        }

        val currentSlide = when (sheetBehavior.state) {
            BottomSheetBehavior.STATE_COLLAPSED -> 0f
            BottomSheetBehavior.STATE_EXPANDED -> 1f
            else -> computedHalfSlideOffset
        }
        updateChromeForSlideOffset(currentSlide)
    }

    private fun updateChromeForSlideOffset(slideOffset: Float) {
        val safeSlide = slideOffset.coerceIn(0f, 1f)
        val half = computedHalfSlideOffset.coerceAtLeast(0.15f)
        val collapseFactor = (safeSlide / half).coerceIn(0f, 1f)

        val bodyAlpha = ((collapseFactor - 0.12f) / 0.48f).coerceIn(0f, 1f)
        binding.sheetBodyContainer.alpha = bodyAlpha
        binding.sheetBodyContainer.visibility =
            if (bodyAlpha <= 0.01f) View.INVISIBLE else View.VISIBLE

        val topBarAlpha = ((collapseFactor - 0.18f) / 0.52f).coerceIn(0f, 1f)
        binding.topFloatingBar.alpha = topBarAlpha
        binding.topFloatingBar.visibility =
            if (topBarAlpha <= 0.02f) View.GONE else View.VISIBLE

        binding.topLiquidGlassBlur.alpha = 0.72f + 0.28f * collapseFactor
        binding.topLiquidGlassBlur.scaleY = 0.56f + 0.44f * collapseFactor
        binding.mapScrimBottom.alpha = collapseFactor

        val fabAlpha = ((collapseFactor - 0.28f) / 0.45f).coerceIn(0f, 1f)
        val fabScale = 0.72f + 0.28f * fabAlpha
        binding.fabPinAlarm.alpha = fabAlpha
        binding.fabPinAlarm.scaleX = fabScale
        binding.fabPinAlarm.scaleY = fabScale
        binding.fabPinAlarm.visibility =
            if (fabAlpha <= 0.02f) View.GONE else View.VISIBLE

        val density = resources.displayMetrics.density
        val measuredSheetTop = binding.scrollDashboard.top
        val sheetTop = if (measuredSheetTop > 0) {
            measuredSheetTop.toFloat()
        } else {
            (244f * density) + sysTopInset
        }
        val pillH = if (binding.pillLiveRadar.height > 0) {
            binding.pillLiveRadar.height.toFloat()
        } else {
            (28f * density)
        }
        val gap = 12f * density
        binding.pillLiveRadar.translationY =
            (sheetTop - pillH - gap).coerceAtLeast(sysTopInset + 12f * density)

        val aboveHalfRange = (1f - half).coerceAtLeast(0.1f)
        val upperFade = 1f - ((safeSlide - half) / (aboveHalfRange * 0.42f)).coerceIn(0f, 1f)
        val lowerFade = ((collapseFactor - 0.25f) / 0.50f).coerceIn(0f, 1f)
        val pillAlpha = (upperFade * lowerFade).coerceIn(0f, 1f)
        binding.pillLiveRadar.alpha = pillAlpha
        binding.pillLiveRadar.visibility =
            if (pillAlpha <= 0.02f) View.GONE else View.VISIBLE

        val fullMapQuickAlpha = ((0.38f - collapseFactor) / 0.38f).coerceIn(0f, 1f)
        val quickScale = 0.85f + 0.15f * fullMapQuickAlpha
        binding.stackQuickFavMapButtons.alpha = fullMapQuickAlpha
        binding.stackQuickFavMapButtons.scaleX = quickScale
        binding.stackQuickFavMapButtons.scaleY = quickScale
        binding.stackQuickFavMapButtons.visibility =
            if (fullMapQuickAlpha <= 0.02f) View.GONE else View.VISIBLE
    }

    private fun syncModeForSheetState(state: Int, animateCamera: Boolean) {
        val isFullMap = state == BottomSheetBehavior.STATE_COLLAPSED
        if (isFullMap) {
            binding.scrollDashboard.scrollTo(0, 0)
            binding.btnMapHeroTap.visibility = View.GONE
            setHeroMapGesturesEnabled(true)
        } else {
            binding.btnMapHeroTap.visibility =
                if (state == BottomSheetBehavior.STATE_HALF_EXPANDED) View.VISIBLE else View.GONE
            setHeroMapGesturesEnabled(false)
        }

        if (animateCamera) {
            lastHeroCameraKey = null
            updateHeroMapViewport(viewModel.uiState.value, animate = true)
        }
    }

    private fun enterFullMapMode() {
        binding.scrollDashboard.scrollTo(0, 0)
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    private fun setHeroMapGesturesEnabled(enabled: Boolean) {
        val map = heroMap ?: return
        map.uiSettings.apply {
            if (enabled) {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isQuickZoomGesturesEnabled = true
                isTiltGesturesEnabled = true
                isRotateGesturesEnabled = true
                isScaleVelocityAnimationEnabled = true
                isFlingVelocityAnimationEnabled = true
                isRotateVelocityAnimationEnabled = true
            } else {
                setAllGesturesEnabled(false)
            }
        }
    }

    private fun setupHeroMap() {
        if (isRobolectric()) return
        binding.dashboardMapView.getMapAsync { map ->
            if (isDestroyed || isFinishing) return@getMapAsync
            heroMap = map
            MapStyleHelper.configureHighPrecisionMap(
                context = this,
                mapView = binding.dashboardMapView,
                map = map,
                allowGestures = sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED
            )
            map.addOnMapClickListener { point ->
                if (sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
                    return@addOnMapClickListener false
                }
                val screenPt = map.projection.toScreenLocation(point)
                val features = map.queryRenderedFeatures(screenPt, HERO_SAVED_PLACES_SYMBOL_ID)
                val clickedId = features.firstOrNull()?.getNumberProperty("alarmId")?.toLong()
                if (clickedId != null && clickedId > 0L) {
                    openMapPicker(alarmId = clickedId)
                    true
                } else {
                    false
                }
            }
            applyHeroMapStyle(OpenFreeMapStyles.getSavedStyle(this))
        }
    }

    private fun applyHeroMapStyle(styleOption: OpenFreeMapStyle) {
        val map = heroMap ?: return
        val enable3d = OpenFreeMapStyles.is3dBuildingsEnabled(this)
        loadedHeroStyle = styleOption
        loadedHero3dState = enable3d

        map.setStyle(Style.Builder().fromUri(styleOption.styleUrl)) { loadedStyle ->
            if (isDestroyed || isFinishing) return@setStyle
            MapStyleHelper.applyGoogleNavigationStyling(
                style = loadedStyle,
                mode = styleOption,
                enable3dBuildings = enable3d
            )
            enableHeroLocationPuck(loadedStyle)
            updateHeroMapViewport(viewModel.uiState.value, forceRefresh = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableHeroLocationPuck(style: Style) {
        if (!hasFineOrCoarseLocation()) return
        val map = heroMap ?: return
        runCatching {
            val cyanAccent = ContextCompat.getColor(this, R.color.accent_cyan)
            val options = LocationComponentOptions.builder(this)
                .pulseEnabled(true)
                .pulseColor(cyanAccent)
                .foregroundTintColor(cyanAccent)
                .accuracyColor(ContextCompat.getColor(this, R.color.geofence_fill))
                .build()

            val activation = LocationComponentActivationOptions.builder(this, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(true)
                .build()

            map.locationComponent.apply {
                activateLocationComponent(activation)
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.NORMAL
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateHeroMapViewport(
        state: DashboardUiState,
        forceRefresh: Boolean = false,
        animate: Boolean = false
    ) {
        val map = heroMap ?: return
        val style = map.style
        val activeAlarms = state.alarms.filter { it.isActive }
        val savedFavorites = state.alarms.filter {
            it.isFavorite && it.favoriteCategory != FavoriteCategory.NONE
        }

        val geoKey = activeAlarms.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }
        val favKey = savedFavorites.joinToString("|") {
            "${it.id}:${it.favoriteCategory}:${it.title}:${it.latitude}:${it.longitude}"
        }
        val isDark = OpenFreeMapStyles.getSavedStyle(this).isDark

        if (style != null && style.isFullyLoaded) {
            if (forceRefresh || geoKey != lastHeroGeofenceKey) {
                lastHeroGeofenceKey = geoKey
                val features = activeAlarms.map { alarm ->
                    MapStyleHelper.createGeodesicCircleFeature(
                        lat = alarm.latitude,
                        lon = alarm.longitude,
                        radiusMeters = alarm.radiusMeters,
                        steps = 56
                    )
                }
                MapStyleHelper.ensureGeofenceCircleLayers(
                    style = style,
                    sourceId = HERO_GEOFENCE_SOURCE_ID,
                    fillLayerId = HERO_GEOFENCE_FILL_ID,
                    lineLayerId = HERO_GEOFENCE_LINE_ID,
                    features = FeatureCollection.fromFeatures(features)
                )
            }

            if (forceRefresh || favKey != lastHeroSavedPlacesKey) {
                lastHeroSavedPlacesKey = favKey
                MapStyleHelper.ensureSavedPlaceMarkersLayer(
                    context = this,
                    style = style,
                    sourceId = HERO_SAVED_PLACES_SOURCE_ID,
                    haloLayerId = HERO_SAVED_PLACES_HALO_ID,
                    symbolLayerId = HERO_SAVED_PLACES_SYMBOL_ID,
                    favorites = savedFavorites,
                    isDark = isDark
                )
            }
        }

        val targetAlarm = activeAlarms.minByOrNull { it.lastDistanceMeters ?: Float.MAX_VALUE }
            ?: state.alarms.firstOrNull()

        if (targetAlarm != null) {
            val modeTag = if (sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) "full" else "hero"
            val camKey = "alarm:$modeTag:${targetAlarm.id}:${targetAlarm.latitude}:${targetAlarm.longitude}"
            if (forceRefresh || camKey != lastHeroCameraKey) {
                lastHeroCameraKey = camKey
                moveHeroCameraWithSheetPadding(
                    map = map,
                    lat = targetAlarm.latitude,
                    lon = targetAlarm.longitude,
                    zoom = if (modeTag == "full") 14.2 else 14.5,
                    animate = animate
                )
            }
            return
        }

        if (hasFineOrCoarseLocation()) {
            val lm = getSystemService(LocationManager::class.java)
            val gps = runCatching { lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            val net = runCatching { lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
            val best = when {
                gps != null && net != null -> if (gps.time >= net.time) gps else net
                gps != null -> gps
                else -> net
            }
            if (best != null) {
                val modeTag = if (sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) "full" else "hero"
                val camKey = "loc:$modeTag:${best.latitude}:${best.longitude}"
                if (forceRefresh || camKey != lastHeroCameraKey) {
                    lastHeroCameraKey = camKey
                    moveHeroCameraWithSheetPadding(
                        map = map,
                        lat = best.latitude,
                        lon = best.longitude,
                        zoom = 14.8,
                        animate = animate
                    )
                }
                return
            }
        }

        if (forceRefresh || lastHeroCameraKey == null) {
            lastHeroCameraKey = "default"
            moveHeroCameraWithSheetPadding(
                map = map,
                lat = 48.8566,
                lon = 2.3522,
                zoom = 12.5,
                animate = animate
            )
        }
    }

    private fun moveHeroCameraWithSheetPadding(
        map: MapLibreMap,
        lat: Double,
        lon: Double,
        zoom: Double,
        animate: Boolean
    ) {
        val density = resources.displayMetrics.density
        val parentHeight = binding.rootCoordinator.height
        val isFullMap = sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED
        val topPad = if (isFullMap) {
            sysTopInset.toDouble()
        } else {
            sysTopInset.toDouble() + (52f * density)
        }
        val bottomPad = when {
            parentHeight <= 0 -> 0.0
            isFullMap -> sheetBehavior.peekHeight.toDouble()
            else -> {
                val visibleHero = (244f * density) + sysTopInset
                (parentHeight - visibleHero).coerceAtLeast(0f).toDouble()
            }
        }
        val tilt = if (OpenFreeMapStyles.is3dBuildingsEnabled(this)) 24.0 else 0.0
        val pos = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .tilt(tilt)
            .padding(0.0, topPad, 0.0, bottomPad)
            .build()
        val update = CameraUpdateFactory.newCameraPosition(pos)
        if (animate) {
            map.animateCamera(update, 650)
        } else {
            map.moveCamera(update)
        }
    }

    private fun setupRecyclerViews() {
        alarmAdapter = AlarmAdapter(
            onToggleActive = { alarm, active ->
                if (active && !PermissionHelper.checkStatus(this).hasFineLocation) {
                    requestFineLocation()
                }
                viewModel.setAlarmActive(alarm, active) { hasActive ->
                    if (hasActive && PermissionHelper.checkStatus(this).hasFineLocation) {
                        PinWakeService.startOrSync(this)
                    } else if (!hasActive) {
                        stopService(Intent(this, PinWakeService::class.java))
                    }
                }
            },
            onEditClick = { alarm ->
                openMapPicker(alarmId = alarm.id)
            },
            onDeleteClick = { alarm ->
                viewModel.deleteAlarm(alarm.id) { hasActive ->
                    if (!hasActive) {
                        stopService(Intent(this, PinWakeService::class.java))
                    }
                }
            }
        )

        binding.rvAlarms.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alarmAdapter
            itemAnimator = null
        }

        historyAdapter = TripHistoryAdapter()
        binding.rvTripHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
    }

    private fun setupClicks() {
        binding.fabPinAlarm.setOnClickListener { openMapPicker() }
        binding.btnWhereTo.setOnClickListener { openMapPicker() }
        setupMapHeroPullGesture()

        binding.layoutSheetHandle.setOnClickListener {
            if (sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                enterFullMapMode()
            }
        }

        binding.btnLanguageToggle.setOnClickListener {
            LanguageManager.toggleLanguage(this)
        }

        binding.btnCheckUpdates.setOnClickListener {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            viewModel.checkForUpdates(manual = true)
        }

        binding.btnDownloadUpdate.setOnClickListener {
            viewModel.startInAppDownload { apkFile ->
                installDownloadedApk(apkFile)
            }
        }

        binding.btnOpenGithubRelease.setOnClickListener {
            val url = viewModel.updateState.value.availableUpdate?.releasePageUrl
                ?: GitHubReleaseApiService.REPO_RELEASES_URL
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearHistory()
        }

        binding.btnPermFineLocation.setOnClickListener { requestFineLocation() }
        binding.btnPermBackgroundLocation.setOnClickListener { requestBackgroundLocation() }
        binding.btnPermNotifications.setOnClickListener { requestNotifications() }
        binding.btnPermFullScreen.setOnClickListener {
            runCatching { startActivity(PermissionHelper.buildFullScreenIntentSettings(this)) }
        }
        binding.btnPermBattery.setOnClickListener {
            runCatching { startActivity(PermissionHelper.buildBatteryOptimizationIntent(this)) }
        }

        binding.btnMyLocationFullMap.setOnClickListener {
            if (hasFineOrCoarseLocation()) {
                centerHeroOnCurrentLocation()
            } else {
                pendingCenterOnLocation = true
                requestFineLocation()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerHeroOnCurrentLocation() {
        if (!hasFineOrCoarseLocation()) return
        val map = heroMap ?: return
        val lm = getSystemService(LocationManager::class.java) ?: return

        map.style?.let { style ->
            if (style.isFullyLoaded) enableHeroLocationPuck(style)
        }

        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val best = when {
            gps != null && net != null -> if (gps.time >= net.time) gps else net
            gps != null -> gps
            else -> net
        }

        if (best != null) {
            lastHeroCameraKey = "myloc:${best.latitude}:${best.longitude}"
            moveHeroCameraWithSheetPadding(
                map = map,
                lat = best.latitude,
                lon = best.longitude,
                zoom = 16.0,
                animate = true
            )
            return
        }

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }

        if (provider == null) {
            Toast.makeText(this, R.string.map_location_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        activeSingleFixListener?.let { old ->
            runCatching { lm.removeUpdates(old) }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                activeSingleFixListener = null
                if (!isDestroyed && !isFinishing) {
                    lastHeroCameraKey = "myloc:${location.latitude}:${location.longitude}"
                    moveHeroCameraWithSheetPadding(
                        map = map,
                        lat = location.latitude,
                        lon = location.longitude,
                        zoom = 16.0,
                        animate = true
                    )
                }
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeSingleFixListener = listener

        runCatching {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMapHeroPullGesture() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var pulledDown = false

        binding.btnMapHeroTap.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    pulledDown = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!pulledDown) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (dy > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.1f) {
                            pulledDown = true
                            enterFullMapMode()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!pulledDown) {
                        val dx = kotlin.math.abs(event.rawX - downX)
                        val dy = kotlin.math.abs(event.rawY - downY)
                        if (dx <= touchSlop && dy <= touchSlop) {
                            view.performClick()
                            openMapPicker()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pulledDown = false
                    true
                }
                else -> false
            }
        }
    }

    private fun observeDashboard() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.updateState.collect { update ->
                        renderUpdateState(update)
                    }
                }
                launch {
                    viewModel.updateEvent.collect { event ->
                        when (event) {
                            is UpdateCheckEvent.UpToDate -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                                    Toast.LENGTH_SHORT
                                ).show()
                                viewModel.consumeUpdateEvent()
                            }
                            is UpdateCheckEvent.Failed -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.update_check_failed,
                                    Toast.LENGTH_SHORT
                                ).show()
                                viewModel.consumeUpdateEvent()
                            }
                            is UpdateCheckEvent.UpdateFound -> {
                                viewModel.consumeUpdateEvent()
                            }
                            null -> Unit
                        }
                    }
                }
            }
        }
    }

    private fun renderUpdateState(update: UpdateUiState) {
        val info = update.availableUpdate
        if (info == null) {
            binding.cardUpdateAvailable.visibility = View.GONE
            return
        }

        binding.cardUpdateAvailable.visibility = View.VISIBLE
        binding.tvUpdateTagMono.text = info.tagName
        binding.tvUpdateTitle.text = getString(R.string.update_card_title, info.versionName)

        val sizeMb = info.apkAsset.size / (1024.0 * 1024.0)
        binding.tvUpdateSubtitle.text = getString(R.string.update_card_subtitle_size, sizeMb)

        if (info.releaseNotes.isNotBlank()) {
            binding.tvUpdateNotes.visibility = View.VISIBLE
            binding.tvUpdateNotes.text = info.releaseNotes
        } else {
            binding.tvUpdateNotes.visibility = View.GONE
        }

        if (update.isDownloading) {
            binding.progressUpdateDownload.visibility = View.VISIBLE
            binding.progressUpdateDownload.isIndeterminate = update.downloadPercent == 0
            binding.progressUpdateDownload.setProgressCompat(update.downloadPercent, true)
            binding.tvUpdateProgress.visibility = View.VISIBLE
            binding.tvUpdateProgress.text = getString(
                R.string.update_downloading,
                update.downloadPercent,
                update.downloadedMb,
                update.totalMb
            )
            binding.btnDownloadUpdate.isEnabled = false
            binding.btnDownloadUpdate.alpha = 0.65f
        } else {
            binding.progressUpdateDownload.visibility = View.GONE
            binding.tvUpdateProgress.visibility = if (update.errorMessage != null) View.VISIBLE else View.GONE
            if (update.errorMessage != null) {
                binding.tvUpdateProgress.text = update.errorMessage
            }
            binding.btnDownloadUpdate.isEnabled = true
            binding.btnDownloadUpdate.alpha = 1f
            binding.btnDownloadUpdate.setText(
                if (update.downloadedApkFile != null) R.string.update_btn_install
                else R.string.update_btn_download
            )
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        if (!apkUpdateManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.update_btn_install)
                .setMessage(R.string.update_install_perm_msg)
                .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                    installPermissionLauncher.launch(apkUpdateManager.buildInstallPermissionIntent())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launchApkInstaller(apkFile)
    }

    private fun launchApkInstaller(apkFile: File) {
        runCatching {
            startActivity(apkUpdateManager.buildInstallApkIntent(apkFile))
        }.onFailure {
            Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderState(state: DashboardUiState) {
        alarmAdapter.submitList(state.alarms)
        binding.emptyAlarmsCard.visibility = if (state.alarms.isEmpty()) View.VISIBLE else View.GONE
        binding.tvActiveAlarmCount.text = getString(R.string.status_active_count, state.activeCount)

        historyAdapter.submitList(state.recentTrips)
        binding.sectionHistory.visibility =
            if (state.recentTrips.isEmpty()) View.GONE else View.VISIBLE

        renderTelemetryBanner(state)
        updateHeroMapViewport(state)
        renderQuickFavorite(
            card = binding.cardFavHome,
            statusView = binding.tvFavHomeStatus,
            favorite = state.homeFavorite,
            category = FavoriteCategory.HOME
        )
        renderQuickFavorite(
            card = binding.cardFavWork,
            statusView = binding.tvFavWorkStatus,
            favorite = state.workFavorite,
            category = FavoriteCategory.WORK
        )
        renderQuickFavorite(
            card = binding.cardFavStation,
            statusView = binding.tvFavStationStatus,
            favorite = state.stationFavorite,
            category = FavoriteCategory.STATION
        )
        renderFullMapQuickButton(
            button = binding.btnQuickWorkFullMap,
            labelView = binding.tvQuickWorkFullMap,
            favorite = state.workFavorite,
            category = FavoriteCategory.WORK
        )
        renderFullMapQuickButton(
            button = binding.btnQuickHomeFullMap,
            labelView = binding.tvQuickHomeFullMap,
            favorite = state.homeFavorite,
            category = FavoriteCategory.HOME
        )
    }

    private fun renderTelemetryBanner(state: DashboardUiState) {
        val telemetry = state.telemetry
        if (telemetry.isTracking && state.activeCount > 0) {
            binding.cardLiveTelemetry.setBackgroundResource(R.drawable.bg_liquid_glass_card_active)
            binding.viewTrackingDot.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.viewHeroRadarDot.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.tvHeroRadarText.text = getString(R.string.hero_live_radar)
            binding.tvHeroRadarText.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            binding.tvTrackingTarget.text =
                telemetry.closestAlarmTitle ?: getString(R.string.app_name)
            binding.tvTrackingSubtitle.text =
                DistanceFormatter.formatDistanceBadge(this, telemetry.closestDistanceMeters)

            val tierText = when (telemetry.pollingTier) {
                PollingTier.STANDBY -> getString(R.string.status_standby)
                PollingTier.ECO_30S -> getString(R.string.status_tracking_eco)
                PollingTier.BALANCED_10S -> getString(R.string.status_tracking_balanced)
                PollingTier.PRECISION_2S -> getString(R.string.status_tracking_precision)
            }
            binding.tvPollingTierBadge.text = tierText
            binding.tvPollingTierBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.tvPollingTierBadge.setTextColor(
                ContextCompat.getColor(this, R.color.accent_emerald)
            )
        } else {
            binding.cardLiveTelemetry.setBackgroundResource(R.drawable.bg_liquid_glass_card)
            binding.viewTrackingDot.setBackgroundResource(R.drawable.bg_badge_cyan)
            binding.viewHeroRadarDot.setBackgroundResource(R.drawable.bg_badge_cyan)
            binding.tvHeroRadarText.setText(R.string.status_standby)
            binding.tvHeroRadarText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvTrackingTarget.setText(R.string.status_standby)
            binding.tvTrackingSubtitle.setText(R.string.app_tagline)
            binding.tvPollingTierBadge.setText(R.string.status_standby)
            binding.tvPollingTierBadge.setBackgroundResource(R.drawable.bg_badge_cyan)
            binding.tvPollingTierBadge.setTextColor(
                ContextCompat.getColor(this, R.color.accent_cyan_glow)
            )
        }
    }

    private fun renderQuickFavorite(
        card: View,
        statusView: TextView,
        favorite: GeoAlarmEntity?,
        category: FavoriteCategory
    ) {
        if (favorite == null) {
            card.setBackgroundResource(R.drawable.bg_liquid_glass_card)
            statusView.setText(R.string.fav_tap_to_pin)
            statusView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            card.setOnClickListener {
                openMapPicker(defaultCategory = category)
            }
            card.setOnLongClickListener(null)
        } else {
            if (favorite.isActive) {
                card.setBackgroundResource(R.drawable.bg_liquid_glass_card_active)
                statusView.setText(R.string.fav_armed)
                statusView.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            } else {
                card.setBackgroundResource(R.drawable.bg_liquid_glass_card)
                statusView.setText(R.string.fav_tap_to_arm)
                statusView.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan_glow))
            }
            card.setOnClickListener {
                val nextActive = !favorite.isActive
                if (nextActive && !PermissionHelper.checkStatus(this).hasFineLocation) {
                    requestFineLocation()
                }
                viewModel.setAlarmActive(favorite, nextActive) { hasActive ->
                    if (hasActive && PermissionHelper.checkStatus(this).hasFineLocation) {
                        PinWakeService.startOrSync(this)
                    } else if (!hasActive) {
                        stopService(Intent(this, PinWakeService::class.java))
                    }
                }
            }
            card.setOnLongClickListener {
                openMapPicker(alarmId = favorite.id)
                true
            }
        }
    }

    private fun renderFullMapQuickButton(
        button: View,
        labelView: TextView,
        favorite: GeoAlarmEntity?,
        category: FavoriteCategory
    ) {
        if (favorite == null) {
            button.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
            labelView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            button.setOnClickListener {
                openMapPicker(defaultCategory = category)
            }
            button.setOnLongClickListener(null)
        } else {
            if (favorite.isActive) {
                button.setBackgroundResource(R.drawable.bg_liquid_glass_pill_active)
                labelView.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            } else {
                button.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
                labelView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
            button.setOnClickListener {
                heroMap?.let { map ->
                    lastHeroCameraKey = "fav:${favorite.id}:${favorite.latitude}:${favorite.longitude}"
                    moveHeroCameraWithSheetPadding(
                        map = map,
                        lat = favorite.latitude,
                        lon = favorite.longitude,
                        zoom = 15.5,
                        animate = true
                    )
                }
                val nextActive = !favorite.isActive
                if (nextActive && !PermissionHelper.checkStatus(this).hasFineLocation) {
                    requestFineLocation()
                }
                viewModel.setAlarmActive(favorite, nextActive) { hasActive ->
                    if (hasActive && PermissionHelper.checkStatus(this).hasFineLocation) {
                        PinWakeService.startOrSync(this)
                    } else if (!hasActive) {
                        stopService(Intent(this, PinWakeService::class.java))
                    }
                }
            }
            button.setOnLongClickListener {
                openMapPicker(alarmId = favorite.id)
                true
            }
        }
    }

    private fun refreshPermissionCard() {
        val status = PermissionHelper.checkStatus(this)
        binding.cardPermissions.visibility = if (status.allGranted) View.GONE else View.VISIBLE
        if (status.allGranted) return

        updatePermissionPill(binding.btnPermFineLocation, status.hasFineLocation)
        updatePermissionPill(binding.btnPermBackgroundLocation, status.hasBackgroundLocation)
        updatePermissionPill(binding.btnPermNotifications, status.hasNotifications)
        updatePermissionPill(binding.btnPermFullScreen, status.hasFullScreenIntent)
        updatePermissionPill(binding.btnPermBattery, status.isBatteryUnrestricted)
    }

    private fun updatePermissionPill(button: TextView, granted: Boolean) {
        if (granted) {
            button.setText(R.string.perm_status_granted)
            button.setBackgroundResource(R.drawable.bg_badge_emerald)
            button.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
            button.isEnabled = false
        } else {
            button.setText(R.string.perm_status_grant)
            button.setBackgroundResource(R.drawable.bg_badge_cyan)
            button.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan_glow))
            button.isEnabled = true
        }
    }

    private fun requestFineLocation() {
        fineLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun requestBackgroundLocation() {
        val status = PermissionHelper.checkStatus(this)
        if (!status.hasFineLocation) {
            requestFineLocation()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.perm_background_location)
                .setMessage(R.string.perm_rationale_background)
                .setPositiveButton(R.string.perm_status_grant) { _, _ ->
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
                .setNegativeButton(R.string.perm_open_settings) { _, _ ->
                    startActivity(PermissionHelper.buildAppSettingsIntent(this))
                }
                .show()
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun syncTrackingServiceIfPossible() {
        val status = PermissionHelper.checkStatus(this)
        if (status.hasFineLocation && viewModel.uiState.value.activeCount > 0) {
            PinWakeService.startOrSync(this)
        }
    }

    private fun hasFineOrCoarseLocation(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isRobolectric(): Boolean {
        return "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)
    }

    private fun openMapPicker(
        alarmId: Long = -1L,
        defaultCategory: FavoriteCategory = FavoriteCategory.NONE
    ) {
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            if (alarmId > 0L) putExtra(MapPickerActivity.EXTRA_ALARM_ID, alarmId)
            putExtra(MapPickerActivity.EXTRA_DEFAULT_CATEGORY, defaultCategory.name)
        }
        startActivity(intent)
    }

    companion object {
        private const val HERO_GEOFENCE_SOURCE_ID = "pinwake-hero-geofence-source"
        private const val HERO_GEOFENCE_FILL_ID = "pinwake-hero-geofence-fill"
        private const val HERO_GEOFENCE_LINE_ID = "pinwake-hero-geofence-line"
        private const val HERO_SAVED_PLACES_SOURCE_ID = "pinwake-hero-saved-places-source"
        private const val HERO_SAVED_PLACES_HALO_ID = "pinwake-hero-saved-places-halo"
        private const val HERO_SAVED_PLACES_SYMBOL_ID = "pinwake-hero-saved-places-symbol"
    }
}
