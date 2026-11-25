package com.example

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
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
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.repository.PollingTier
import com.example.databinding.ActivityMainBinding
import com.example.databinding.DialogInstallUpdateBinding
import com.example.databinding.DialogQuickAssignPinBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.AlarmAdapter
import com.example.ui.dashboard.DashboardUiState
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.TripHistoryAdapter
import com.example.ui.dashboard.UpdateUiState
import com.example.ui.map.MapPickerActivity
import com.example.ui.settings.SettingsActivity
import com.example.util.ApkUpdateManager
import com.example.util.AppUpdateInfo
import com.example.util.DistanceFormatter
import com.example.util.GpsEnforcementHelper
import com.example.util.LanguageManager
import com.example.util.MapLinkParser
import com.example.util.MapStyleHelper
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.example.util.PreAlertPreferences
import com.example.util.SafeMapLocationEngine
import com.example.util.ShareTripHelper
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
import java.util.Locale
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val viewModel: DashboardViewModel by viewModels {
        val container = (application as PinWakeApplication).container
        DashboardViewModel.Factory(container.repository, container.apkUpdateManager)
    }

    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var historyAdapter: TripHistoryAdapter
    private lateinit var sheetBehavior: BottomSheetBehavior<NestedScrollView>
    private var heroMap: MapLibreMap? = null
    private var loadedHeroStyle: OpenFreeMapStyle? = null
    private var loadedHero3dState: Boolean? = null
    private var lastHeroGeofenceKey: String? = null
    private var lastHeroSavedPlacesKey: String? = null
    private var lastHeroCameraKey: String? = null
    private var focusedPlaceLockId: Long? = null
    private var sysTopInset: Int = 0
    private var sysBottomInset: Int = 0
    private var computedHalfSlideOffset: Float = 0.65f
    private var lastSheetState: Int = BottomSheetBehavior.STATE_HALF_EXPANDED
    private var activeSingleFixListener: LocationListener? = null
    private var pendingCenterOnLocation: Boolean = false
    private var pendingArmAlarm: GeoAlarmEntity? = null
    private val mapHoldHandler = Handler(Looper.getMainLooper())
    private var pendingMapHoldRunnable: Runnable? = null
    private var quickAssignDialog: BottomSheetDialog? = null
    private var installUpdateDialog: BottomSheetDialog? = null
    private var brandArrowPulseAnimator: ObjectAnimator? = null
    private var trackingDotPulseAnimator: ObjectAnimator? = null
    private var heroRadarDotPulseAnimator: ObjectAnimator? = null
    private var pendingInstallApk: File? = null
    private var promptedGpsThisSession = false
    private var isGpsDialogShowing = false
    private var providersReceiverRegistered = false

    private val providersChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                renderTelemetryBanner(viewModel.uiState.value)
            }
        }
    }

    private val apkUpdateManager: ApkUpdateManager by lazy {
        (application as PinWakeApplication).container.apkUpdateManager
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

    private val restoreSheetBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private val safeLocationEngine by lazy { SafeMapLocationEngine(this) }

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshSettingsAttentionDot()
        val pendingToArm = pendingArmAlarm
        pendingArmAlarm = null
        if (hasFineOrCoarseLocation()) {
            if (pendingToArm != null) {
                viewModel.setAlarmActive(pendingToArm, true) { hasActive ->
                    if (hasActive) {
                        PinWakeService.startOrSync(this)
                        if (GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                            PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                        }
                    }
                }
            } else {
                syncTrackingServiceIfPossible()
            }
            heroMap?.style?.let { style ->
                if (style.isFullyLoaded) enableHeroLocationPuck(style, forceReapplyStyle = false)
            }
            if (pendingCenterOnLocation) {
                pendingCenterOnLocation = false
                centerHeroOnCurrentLocation()
            } else {
                lastHeroCameraKey = null
                updateHeroMapViewport(viewModel.uiState.value)
            }
        } else {
            pendingCenterOnLocation = false
            if (pendingToArm != null) {
                Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
                alarmAdapter.notifyDataSetChanged()
            }
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

        onBackPressedDispatcher.addCallback(this, restoreSheetBackCallback)
        binding.tvTopVersionBadge.text =
            getString(R.string.update_pill_version, BuildConfig.VERSION_NAME)
        setupBottomSheet(savedInstanceState == null)
        applyEdgeToEdgeInsets()
        setupHeroMap()
        setupRecyclerViews()
        setupClicks()
        observeDashboard()

        if (!providersReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                providersChangedReceiver,
                IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            providersReceiverRegistered = true
        }

        if (savedInstanceState == null) {
            handleIncomingShareIntent(intent)
            maybeHandlePromptEnableGpsIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
        maybeHandlePromptEnableGpsIntent(intent)
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
                    if (binding.scrollDashboard.layerType != View.LAYER_TYPE_HARDWARE) {
                        binding.scrollDashboard.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    }
                    return
                }
                if (binding.scrollDashboard.layerType != View.LAYER_TYPE_NONE) {
                    binding.scrollDashboard.setLayerType(View.LAYER_TYPE_NONE, null)
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
        refreshSettingsAttentionDot()
        syncTrackingServiceIfPossible()
        renderTelemetryBanner(viewModel.uiState.value)
        checkGpsOnResume()

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
        cancelPendingMapHold()
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
        cancelPendingMapHold()
        stopBrandArrowPulse()
        stopTelemetryDotPulse()
        quickAssignDialog?.dismiss()
        quickAssignDialog = null
        installUpdateDialog?.dismiss()
        installUpdateDialog = null
        activeSingleFixListener?.let { listener ->
            runCatching { getSystemService(LocationManager::class.java)?.removeUpdates(listener) }
        }
        activeSingleFixListener = null
        runCatching {
            heroMap?.locationComponent?.takeIf { it.isLocationComponentActivated }?.isLocationComponentEnabled = false
        }
        if (providersReceiverRegistered) {
            runCatching { unregisterReceiver(providersChangedReceiver) }
            providersReceiverRegistered = false
        }
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

        val targetSheetHeight = (parentHeight - expandedOffsetPx).coerceAtLeast(targetPeekHeight)
        val scrollParams = binding.scrollDashboard.layoutParams
        if (scrollParams.height != targetSheetHeight) {
            scrollParams.height = targetSheetHeight
            binding.scrollDashboard.layoutParams = scrollParams
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
        val bodyVis = if (bodyAlpha <= 0.01f) View.INVISIBLE else View.VISIBLE
        if (binding.sheetBodyContainer.visibility != bodyVis) {
            binding.sheetBodyContainer.visibility = bodyVis
        }

        // Top command bar & liquid glass canopy stay visible in ALL modes (including Full Map Mode)
        if (binding.topFloatingBar.visibility != View.VISIBLE) {
            binding.topFloatingBar.visibility = View.VISIBLE
        }
        binding.topFloatingBar.alpha = 1f
        binding.topFloatingBar.translationY = 0f
        binding.topLiquidGlassBlur.alpha = 1f
        binding.topLiquidGlassBlur.scaleY = 1f
        binding.mapScrimBottom.alpha = collapseFactor

        // Zero-relayout FAB transition using INVISIBLE + clickable flags
        val fabAlpha = ((collapseFactor - 0.28f) / 0.45f).coerceIn(0f, 1f)
        val fabScale = 0.78f + 0.22f * fabAlpha
        binding.fabPinAlarm.alpha = fabAlpha
        binding.fabPinAlarm.scaleX = fabScale
        binding.fabPinAlarm.scaleY = fabScale
        val fabInteractive = fabAlpha > 0.02f
        val fabVis = if (fabInteractive) View.VISIBLE else View.INVISIBLE
        if (binding.fabPinAlarm.visibility != fabVis) {
            binding.fabPinAlarm.visibility = fabVis
        }
        if (binding.fabPinAlarm.isClickable != fabInteractive) {
            binding.fabPinAlarm.isClickable = fabInteractive
            binding.fabPinAlarm.isFocusable = fabInteractive
        }

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
        val barBottom = binding.topFloatingBar.bottom.toFloat()
            .coerceAtLeast(sysTopInset + 68f * density)
        val minRadarTop = barBottom + 10f * density
        val halfRadarY = (sheetTop - pillH - gap).coerceAtLeast(minRadarTop)
        binding.pillLiveRadar.translationY =
            minRadarTop + (halfRadarY - minRadarTop) * collapseFactor

        val aboveHalfRange = (1f - half).coerceAtLeast(0.1f)
        val upperFade = 1f - ((safeSlide - half) / (aboveHalfRange * 0.28f)).coerceIn(0f, 1f)
        binding.pillLiveRadar.alpha = upperFade
        val pillVis = if (upperFade <= 0.02f) View.INVISIBLE else View.VISIBLE
        if (binding.pillLiveRadar.visibility != pillVis) {
            binding.pillLiveRadar.visibility = pillVis
        }

        // Dynamic staggered slide-in for the Full Map quick dock (My Location -> Work -> Home)
        val fullMapQuickAlpha = ((0.42f - collapseFactor) / 0.42f).coerceIn(0f, 1f)
        val dockInteractive = fullMapQuickAlpha > 0.02f
        val dockVis = if (dockInteractive) View.VISIBLE else View.INVISIBLE
        if (binding.stackQuickFavMapButtons.visibility != dockVis) {
            binding.stackQuickFavMapButtons.visibility = dockVis
        }
        binding.stackQuickFavMapButtons.alpha = if (dockInteractive) 1f else 0f

        val tHome = ((fullMapQuickAlpha - 0.00f) / 0.72f).coerceIn(0f, 1f)
        val tWork = ((fullMapQuickAlpha - 0.14f) / 0.72f).coerceIn(0f, 1f)
        val tLoc = ((fullMapQuickAlpha - 0.28f) / 0.72f).coerceIn(0f, 1f)

        applyStaggeredDockItem(binding.btnQuickHomeFullMap, tHome, 16f * density, dockInteractive)
        applyStaggeredDockItem(binding.btnQuickWorkFullMap, tWork, 22f * density, dockInteractive)
        applyStaggeredDockItem(binding.btnMyLocationFullMap, tLoc, 28f * density, dockInteractive)
    }

    private fun applyStaggeredDockItem(
        view: View,
        progress: Float,
        offsetPx: Float,
        interactive: Boolean
    ) {
        val scale = 0.86f + 0.14f * progress
        view.alpha = progress
        view.scaleX = scale
        view.scaleY = scale
        view.translationY = (1f - progress) * offsetPx
        if (view.isClickable != interactive) {
            view.isClickable = interactive
            view.isFocusable = interactive
        }
    }

    private fun syncModeForSheetState(state: Int, animateCamera: Boolean) {
        val isFullMap = state == BottomSheetBehavior.STATE_COLLAPSED
        if (isFullMap) {
            binding.scrollDashboard.scrollTo(0, 0)
            binding.btnMapHeroTap.visibility = View.GONE
            setHeroMapGesturesEnabled(true)
        } else {
            focusedPlaceLockId = null
            cancelPendingMapHold()
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
                val features = map.queryRenderedFeatures(
                    screenPt,
                    HERO_SAVED_PLACES_SYMBOL_ID,
                    HERO_SAVED_PLACES_HALO_ID,
                    "$HERO_SAVED_PLACES_SYMBOL_ID-labels"
                )
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
            enableHeroLocationPuck(loadedStyle, forceReapplyStyle = true)
            updateHeroMapViewport(viewModel.uiState.value, forceRefresh = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableHeroLocationPuck(style: Style, forceReapplyStyle: Boolean = false) {
        if (!hasFineOrCoarseLocation()) return
        val map = heroMap ?: return
        runCatching {
            val locationComponent = map.locationComponent
            if (!forceReapplyStyle &&
                locationComponent.isLocationComponentActivated &&
                locationComponent.isLocationComponentEnabled
            ) {
                return@runCatching
            }
            val cyanAccent = ContextCompat.getColor(this, R.color.accent_cyan)
            val options = LocationComponentOptions.builder(this)
                .pulseEnabled(true)
                .pulseColor(cyanAccent)
                .foregroundTintColor(cyanAccent)
                .accuracyColor(ContextCompat.getColor(this, R.color.geofence_fill))
                .build()

            val activation = LocationComponentActivationOptions.builder(this, style)
                .locationComponentOptions(options)
                .useDefaultLocationEngine(false)
                .locationEngine(safeLocationEngine)
                .build()

            locationComponent.apply {
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
        val pinnedPlaces = state.alarms

        val geoKey = activeAlarms.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }
        val favKey = pinnedPlaces.joinToString("|") {
            "${it.id}:${it.favoriteCategory}:${it.isActive}:${it.title}:${it.latitude}:${it.longitude}"
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
                    favorites = pinnedPlaces,
                    isDark = isDark
                )
            }
        }

        val isFullMap = sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED
        val lockedPlace = if (isFullMap && focusedPlaceLockId != null) {
            state.alarms.firstOrNull { it.id == focusedPlaceLockId }
        } else {
            null
        }
        val targetAlarm = lockedPlace
            ?: activeAlarms.minByOrNull { it.lastDistanceMeters ?: Float.MAX_VALUE }
            ?: state.alarms.firstOrNull()

        if (targetAlarm != null) {
            val modeTag = if (isFullMap) "full" else "hero"
            val camKey = "alarm:$modeTag:${targetAlarm.id}:${targetAlarm.latitude}:${targetAlarm.longitude}"
            if (forceRefresh || camKey != lastHeroCameraKey) {
                lastHeroCameraKey = camKey
                moveHeroCameraWithSheetPadding(
                    map = map,
                    lat = targetAlarm.latitude,
                    lon = targetAlarm.longitude,
                    zoom = if (lockedPlace != null) 16.0 else if (modeTag == "full") 14.5 else 14.5,
                    animate = animate
                )
            }
            return
        }

        if (hasFineOrCoarseLocation()) {
            val best = GpsEnforcementHelper.getBestLocation(this)
            if (best != null) {
                val modeTag = if (isFullMap) "full" else "hero"
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
        val topPad = sysTopInset.toDouble() + (56f * density)
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
                if (active && !PermissionHelper.hasTrackingLocation(this)) {
                    pendingArmAlarm = alarm
                    alarmAdapter.notifyDataSetChanged()
                    requestFineLocation()
                    return@AlarmAdapter
                }
                viewModel.setAlarmActive(alarm, active) { hasActive ->
                    if (hasActive && PermissionHelper.hasTrackingLocation(this)) {
                        PinWakeService.startOrSync(this)
                        if (active && !GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                            showGpsEnablePromptIfNeeded(mandatory = true)
                        } else if (active) {
                            PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                        }
                    } else if (!hasActive) {
                        PinWakeService.stopTracking(this)
                    }
                }
            },
            onEditClick = { alarm ->
                openMapPicker(alarmId = alarm.id)
            },
            onShareClick = { alarm ->
                ShareTripHelper.launchShareSheet(
                    context = this,
                    text = ShareTripHelper.buildAlarmShareText(this, alarm),
                    chooserTitle = getString(R.string.share_chooser_title)
                )
            },
            onDeleteClick = { alarm ->
                viewModel.deleteAlarm(alarm.id) { hasActive ->
                    if (!hasActive) {
                        PinWakeService.stopTracking(this)
                    }
                }
            }
        )

        binding.rvAlarms.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = alarmAdapter
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                addDuration = 180L
                removeDuration = 180L
                moveDuration = 180L
            }
        }

        historyAdapter = TripHistoryAdapter { trip ->
            val hasPerm = PermissionHelper.hasTrackingLocation(this)
            viewModel.replayTrip(trip) { rearmedAlarm, hasActive ->
                if (isDestroyed || isFinishing) return@replayTrip
                focusedPlaceLockId = rearmedAlarm.id
                lastHeroCameraKey = null
                if (!hasPerm) {
                    viewModel.setAlarmActive(rearmedAlarm, false) {
                        pendingArmAlarm = rearmedAlarm
                        requestFineLocation()
                    }
                    return@replayTrip
                }
                if (hasActive) {
                    PinWakeService.startOrSync(this)
                    if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                        showGpsEnablePromptIfNeeded(mandatory = true)
                    } else {
                        PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                    }
                }
                Toast.makeText(
                    this,
                    getString(R.string.trip_rearmed_toast, rearmedAlarm.title),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.rvTripHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = historyAdapter
        }
    }

    private fun setupClicks() {
        binding.fabPinAlarm.setOnClickListener { openMapPicker() }
        binding.btnWhereTo.setOnClickListener { openMapPicker() }
        setupMapHeroPullGesture()
        attachMapOneSecondHoldDetector()

        listOf(
            binding.btnWhereTo,
            binding.fabPinAlarm,
            binding.btnMyLocationFullMap,
            binding.btnQuickWorkFullMap,
            binding.btnQuickHomeFullMap,
            binding.btnSettings,
            binding.pillBrandHeader
        ).forEach { attachLuxuryPressFeedback(it) }

        binding.layoutSheetHandle.setOnClickListener {
            if (sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED) {
                sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                enterFullMapMode()
            }
        }

        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        binding.btnClearHistory.setOnClickListener {
            viewModel.clearHistory()
        }

        binding.cardGpsDisabledBanner.setOnClickListener {
            runCatching { startActivity(GpsEnforcementHelper.buildLocationSettingsIntent()) }
        }
        binding.btnEnableGpsBanner.setOnClickListener {
            runCatching { startActivity(GpsEnforcementHelper.buildLocationSettingsIntent()) }
        }

        binding.btnMyLocationFullMap.setOnClickListener {
            focusedPlaceLockId = null
            renderFullMapQuickDockStates(viewModel.uiState.value)
            if (hasFineOrCoarseLocation()) {
                if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                    showGpsEnablePromptIfNeeded(mandatory = false)
                    centerHeroOnCurrentLocation(silentIfUnavailable = true)
                } else {
                    centerHeroOnCurrentLocation(silentIfUnavailable = false)
                }
            } else {
                pendingCenterOnLocation = true
                requestFineLocation()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachLuxuryPressFeedback(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .setDuration(85L)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
            }
            false
        }
    }

    private fun cancelPendingMapHold() {
        pendingMapHoldRunnable?.let { mapHoldHandler.removeCallbacks(it) }
        pendingMapHoldRunnable = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMapOneSecondHoldDetector() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var holdArmed = false

        binding.dashboardMapView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingMapHold()
                    val canArm = sheetBehavior.state == BottomSheetBehavior.STATE_COLLAPSED &&
                        event.pointerCount == 1 &&
                        heroMap != null &&
                        quickAssignDialog?.isShowing != true
                    if (canArm) {
                        downX = event.x
                        downY = event.y
                        holdArmed = true
                        val runnable = Runnable {
                            holdArmed = false
                            pendingMapHoldRunnable = null
                            if (isDestroyed || isFinishing) return@Runnable
                            if (sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) return@Runnable
                            val map = heroMap ?: return@Runnable
                            val latLng = map.projection.fromScreenLocation(PointF(downX, downY))
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            val targetZoom = maxOf(map.cameraPosition.zoom, 15.5)
                            moveHeroCameraWithSheetPadding(
                                map = map,
                                lat = latLng.latitude,
                                lon = latLng.longitude,
                                zoom = targetZoom,
                                animate = true
                            )
                            showQuickAssignDialog(latLng.latitude, latLng.longitude)
                        }
                        pendingMapHoldRunnable = runnable
                        mapHoldHandler.postDelayed(runnable, MAP_HOLD_DURATION_MS)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (holdArmed) {
                        val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                        if (event.pointerCount > 1 || moved > touchSlop) {
                            holdArmed = false
                            cancelPendingMapHold()
                        }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    holdArmed = false
                    cancelPendingMapHold()
                }
            }
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun centerHeroOnCurrentLocation(silentIfUnavailable: Boolean = false) {
        if (!hasFineOrCoarseLocation()) return
        val map = heroMap ?: return
        val lm = getSystemService(LocationManager::class.java) ?: return

        map.style?.let { style ->
            if (style.isFullyLoaded) enableHeroLocationPuck(style)
        }

        val best = GpsEnforcementHelper.getBestLocation(this)

        if (best != null) {
            runCatching {
                map.locationComponent.takeIf { it.isLocationComponentActivated }?.forceLocationUpdate(best)
            }
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

        val enabledProviders = buildList {
            if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.PASSIVE_PROVIDER)
            }
            if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }

        if (enabledProviders.isEmpty()) {
            if (!silentIfUnavailable) {
                showGpsEnablePromptIfNeeded(mandatory = false)
            }
            return
        }

        if (!silentIfUnavailable && GpsEnforcementHelper.isOffline(this)) {
            Toast.makeText(this, R.string.offline_gps_waiting_satellite, Toast.LENGTH_SHORT).show()
        }

        activeSingleFixListener?.let { old ->
            runCatching { lm.removeUpdates(old) }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                activeSingleFixListener = null
                if (!isDestroyed && !isFinishing) {
                    runCatching {
                        map.locationComponent.takeIf { it.isLocationComponentActivated }
                            ?.forceLocationUpdate(location)
                    }
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

        enabledProviders.forEach { providerName ->
            runCatching {
                lm.requestLocationUpdates(providerName, 0L, 0f, listener, Looper.getMainLooper())
            }
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
                        renderBrandUpdateIndicator(update)
                        refreshSettingsAttentionDot()
                    }
                }
            }
        }
    }

    private fun renderState(state: DashboardUiState) {
        alarmAdapter.submitList(state.alarms)
        binding.emptyAlarmsCard.visibility = if (state.alarms.isEmpty()) View.VISIBLE else View.GONE
        binding.tvActiveAlarmCount.text = getString(R.string.status_active_count, state.activeCount)
        if (state.activeCount > 0) {
            binding.tvActiveAlarmCount.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.tvActiveAlarmCount.setTextColor(
                ContextCompat.getColor(this, R.color.accent_emerald)
            )
        } else {
            binding.tvActiveAlarmCount.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
            binding.tvActiveAlarmCount.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }

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
        renderFullMapQuickDockStates(state)
    }

    private fun renderFullMapQuickDockStates(state: DashboardUiState) {
        renderFullMapQuickButton(
            button = binding.btnQuickWorkFullMap,
            labelView = binding.tvQuickWorkFullMap,
            dotView = binding.dotQuickWorkFullMap,
            favorite = state.workFavorite,
            category = FavoriteCategory.WORK
        )
        renderFullMapQuickButton(
            button = binding.btnQuickHomeFullMap,
            labelView = binding.tvQuickHomeFullMap,
            dotView = binding.dotQuickHomeFullMap,
            favorite = state.homeFavorite,
            category = FavoriteCategory.HOME
        )
    }

    private fun renderTelemetryBanner(state: DashboardUiState) {
        val gpsEnabled = GpsEnforcementHelper.isGpsProviderEnabled(this)
        val offline = GpsEnforcementHelper.isOffline(this)
        binding.cardGpsDisabledBanner.visibility = if (gpsEnabled) View.GONE else View.VISIBLE

        val telemetry = state.telemetry
        if (telemetry.isTracking && state.activeCount > 0) {
            binding.tvOfflineGpsChip.visibility =
                if (offline || telemetry.isOfflineMode) View.VISIBLE else View.GONE
            binding.tvBgLockedChip.visibility = View.VISIBLE
            binding.cardLiveTelemetry.setBackgroundResource(R.drawable.bg_liquid_glass_card_active)
            binding.viewTrackingDot.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.viewHeroRadarDot.setBackgroundResource(R.drawable.bg_badge_emerald)
            startTelemetryDotPulse()
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
            val targetAlarm = state.alarms.firstOrNull { it.id == telemetry.closestAlarmId }
                ?: state.alarms.firstOrNull { it.isActive }
            if (targetAlarm != null) {
                binding.btnShareTripStatus.visibility = View.VISIBLE
                binding.btnShareTripStatus.setOnClickListener {
                    val shareText = ShareTripHelper.buildDestinationShareText(
                        context = this,
                        title = targetAlarm.title,
                        latitude = targetAlarm.latitude,
                        longitude = targetAlarm.longitude,
                        radiusMeters = targetAlarm.radiusMeters,
                        distanceMeters = telemetry.closestDistanceMeters
                    )
                    ShareTripHelper.launchShareSheet(
                        context = this,
                        text = shareText,
                        chooserTitle = getString(R.string.share_chooser_title)
                    )
                }
            } else {
                binding.btnShareTripStatus.visibility = View.GONE
            }
        } else {
            stopTelemetryDotPulse()
            binding.tvOfflineGpsChip.visibility =
                if (offline && state.activeCount > 0) View.VISIBLE else View.GONE
            binding.tvBgLockedChip.visibility = View.GONE
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
            binding.btnShareTripStatus.visibility = View.GONE
        }
    }

    private fun startTelemetryDotPulse() {
        if (trackingDotPulseAnimator?.isRunning != true) {
            trackingDotPulseAnimator = ObjectAnimator.ofFloat(
                binding.viewTrackingDot,
                View.ALPHA,
                1f,
                0.35f
            ).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                duration = 900L
                start()
            }
        }
        if (heroRadarDotPulseAnimator?.isRunning != true) {
            heroRadarDotPulseAnimator = ObjectAnimator.ofFloat(
                binding.viewHeroRadarDot,
                View.ALPHA,
                1f,
                0.35f
            ).apply {
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                duration = 900L
                start()
            }
        }
    }

    private fun stopTelemetryDotPulse() {
        trackingDotPulseAnimator?.cancel()
        trackingDotPulseAnimator = null
        heroRadarDotPulseAnimator?.cancel()
        heroRadarDotPulseAnimator = null
        binding.viewTrackingDot.alpha = 1f
        binding.viewHeroRadarDot.alpha = 1f
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
                if (nextActive && !PermissionHelper.hasTrackingLocation(this)) {
                    pendingArmAlarm = favorite
                    requestFineLocation()
                    return@setOnClickListener
                }
                viewModel.setAlarmActive(favorite, nextActive) { hasActive ->
                    if (hasActive && PermissionHelper.hasTrackingLocation(this)) {
                        PinWakeService.startOrSync(this)
                        if (nextActive && !GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                            showGpsEnablePromptIfNeeded(mandatory = true)
                        } else if (nextActive) {
                            PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                        }
                    } else if (!hasActive) {
                        PinWakeService.stopTracking(this)
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
        dotView: View,
        favorite: GeoAlarmEntity?,
        category: FavoriteCategory
    ) {
        if (favorite == null) {
            button.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            labelView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            dotView.setBackgroundResource(
                if (category == FavoriteCategory.HOME) R.drawable.bg_badge_emerald
                else R.drawable.bg_badge_cyan
            )
            dotView.alpha = 0.35f
            button.setOnClickListener {
                openMapPicker(defaultCategory = category)
            }
            button.setOnLongClickListener(null)
        } else {
            val isHighlighted = favorite.isActive || favorite.id == focusedPlaceLockId
            if (isHighlighted) {
                button.setBackgroundResource(R.drawable.bg_luxury_glass_pill_active)
                labelView.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
                dotView.setBackgroundResource(R.drawable.bg_badge_emerald)
                dotView.alpha = 1f
            } else {
                button.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
                labelView.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                dotView.setBackgroundResource(
                    if (category == FavoriteCategory.HOME) R.drawable.bg_badge_emerald
                    else R.drawable.bg_badge_cyan
                )
                dotView.alpha = 0.9f
            }

            // Directly fly camera to Home / Work in Full Map Mode without toggling/disarming
            button.setOnClickListener {
                focusedPlaceLockId = favorite.id
                lastHeroCameraKey =
                    "alarm:full:${favorite.id}:${favorite.latitude}:${favorite.longitude}"
                heroMap?.let { map ->
                    moveHeroCameraWithSheetPadding(
                        map = map,
                        lat = favorite.latitude,
                        lon = favorite.longitude,
                        zoom = 16.0,
                        animate = true
                    )
                }
                renderFullMapQuickDockStates(viewModel.uiState.value)
            }

            button.setOnLongClickListener {
                openMapPicker(alarmId = favorite.id)
                true
            }
        }
    }

    private fun showQuickAssignDialog(latitude: Double, longitude: Double) {
        quickAssignDialog?.dismiss()

        val dialogBinding = DialogQuickAssignPinBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener {
            if (quickAssignDialog === dialog) {
                quickAssignDialog = null
            }
        }
        quickAssignDialog = dialog

        dialogBinding.tvQuickAssignCoords.text =
            String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
        dialogBinding.tvQuickAssignAddress.text = getString(R.string.map_resolving_address)

        var selectedCategory = FavoriteCategory.HOME
        var userEditedTitle = false
        var isProgrammaticTitleUpdate = false
        var resolvedSuggestion = ""
        var resolvedAddressText = ""

        fun setProgrammaticTitle(value: String) {
            isProgrammaticTitleUpdate = true
            dialogBinding.etQuickAssignTitle.setText(value)
            dialogBinding.etQuickAssignTitle.setSelection(value.length)
            isProgrammaticTitleUpdate = false
        }

        fun applyCategoryTitleIfUntouched() {
            if (userEditedTitle) return
            when (selectedCategory) {
                FavoriteCategory.HOME -> setProgrammaticTitle(getString(R.string.fav_home))
                FavoriteCategory.WORK -> setProgrammaticTitle(getString(R.string.fav_work))
                FavoriteCategory.STATION -> setProgrammaticTitle(
                    resolvedSuggestion.ifBlank { getString(R.string.fav_station) }
                )
                FavoriteCategory.CUSTOM,
                FavoriteCategory.NONE -> if (resolvedSuggestion.isNotBlank()) {
                    setProgrammaticTitle(resolvedSuggestion)
                }
            }
        }

        dialogBinding.etQuickAssignTitle.doAfterTextChanged {
            if (!isProgrammaticTitleUpdate) {
                userEditedTitle = !it.isNullOrBlank()
            }
        }

        fun syncQuickAssignIcon() {
            val (iconRes, tintColorRes) = when (selectedCategory) {
                FavoriteCategory.HOME -> R.drawable.ic_home to R.color.accent_emerald
                FavoriteCategory.WORK -> R.drawable.ic_work to R.color.accent_amber
                FavoriteCategory.STATION -> R.drawable.ic_train to R.color.accent_cyan_glow
                FavoriteCategory.CUSTOM -> R.drawable.ic_star to R.color.accent_cyan_glow
                FavoriteCategory.NONE -> R.drawable.ic_alarm_bell to R.color.accent_cyan
            }
            dialogBinding.ivQuickAssignIcon.setImageResource(iconRes)
            dialogBinding.ivQuickAssignIcon.setColorFilter(
                ContextCompat.getColor(this, tintColorRes)
            )
        }

        dialogBinding.chipAssignHome.isChecked = true
        applyCategoryTitleIfUntouched()
        syncQuickAssignIcon()

        dialogBinding.chipGroupQuickAssign.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedCategory = when (checkedId) {
                R.id.chipAssignHome -> FavoriteCategory.HOME
                R.id.chipAssignWork -> FavoriteCategory.WORK
                R.id.chipAssignStation -> FavoriteCategory.STATION
                R.id.chipAssignPin -> FavoriteCategory.NONE
                else -> FavoriteCategory.CUSTOM
            }
            applyCategoryTitleIfUntouched()
            syncQuickAssignIcon()
        }

        dialogBinding.tvQuickAssignRadius.text = DistanceFormatter.formatRadiusSliderValue(
            this,
            dialogBinding.sliderQuickAssignRadius.value.toInt()
        )
        dialogBinding.sliderQuickAssignRadius.addOnChangeListener { _, value, _ ->
            dialogBinding.tvQuickAssignRadius.text =
                DistanceFormatter.formatRadiusSliderValue(this, value.toInt())
        }

        dialogBinding.rowQuickAssignAlarmToggle.setOnClickListener {
            dialogBinding.switchQuickAssignActive.isChecked =
                !dialogBinding.switchQuickAssignActive.isChecked
        }

        attachLuxuryPressFeedback(dialogBinding.btnQuickAssignConfirm)

        val lang = LanguageManager.getCurrentLanguageCode(this)
        viewModel.reverseGeocodeCoordinate(latitude, longitude, lang) { suggestedTitle, resolvedAddress ->
            if (isDestroyed || isFinishing || !dialog.isShowing) return@reverseGeocodeCoordinate
            resolvedSuggestion = suggestedTitle
            resolvedAddressText = resolvedAddress.ifBlank {
                getString(R.string.map_unknown_address, latitude, longitude)
            }
            dialogBinding.tvQuickAssignAddress.text = resolvedAddressText
            if (!userEditedTitle &&
                (selectedCategory == FavoriteCategory.STATION || selectedCategory == FavoriteCategory.CUSTOM)
            ) {
                applyCategoryTitleIfUntouched()
            }
        }

        dialogBinding.btnQuickAssignConfirm.setOnClickListener {
            val rawTitle = dialogBinding.etQuickAssignTitle.text?.toString()?.trim().orEmpty()
            val finalTitle = rawTitle.ifBlank {
                when (selectedCategory) {
                    FavoriteCategory.HOME -> getString(R.string.fav_home)
                    FavoriteCategory.WORK -> getString(R.string.fav_work)
                    FavoriteCategory.STATION -> getString(R.string.fav_station)
                    FavoriteCategory.CUSTOM,
                    FavoriteCategory.NONE -> resolvedSuggestion.ifBlank { getString(R.string.fav_custom) }
                }
            }
            val finalAddress = resolvedAddressText.ifBlank {
                getString(R.string.map_unknown_address, latitude, longitude)
            }
            val radiusMeters = dialogBinding.sliderQuickAssignRadius.value.toInt()
            val canTrack = PermissionHelper.hasTrackingLocation(this)
            val activateAlarm = dialogBinding.switchQuickAssignActive.isChecked && canTrack

            if (dialogBinding.switchQuickAssignActive.isChecked && !canTrack) {
                Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
                requestFineLocation()
            }

            viewModel.saveQuickAssignedLocation(
                category = selectedCategory,
                title = finalTitle,
                address = finalAddress,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
                isActive = activateAlarm
            ) { savedId, hasAnyActive ->
                if (isDestroyed || isFinishing) return@saveQuickAssignedLocation
                focusedPlaceLockId = savedId
                lastHeroCameraKey = null
                if (hasAnyActive && PermissionHelper.hasTrackingLocation(this)) {
                    PinWakeService.startOrSync(this)
                    if (activateAlarm && GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                        PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                    }
                } else if (!hasAnyActive) {
                    PinWakeService.stopTracking(this)
                }
                Toast.makeText(
                    this,
                    getString(R.string.quick_assign_saved_toast, finalTitle),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun renderBrandUpdateIndicator(update: UpdateUiState) {
        val readyFile = update.downloadedApkFile?.takeIf { it.exists() }
        when {
            update.isDownloading -> {
                binding.ivBrandLogo.visibility = View.GONE
                binding.layoutBrandUpdateSlot.visibility = View.VISIBLE
                binding.progressBrandUpdate.visibility = View.VISIBLE
                binding.ivBrandUpdateArrow.visibility = View.VISIBLE
                binding.ivBrandUpdateArrow.setColorFilter(
                    ContextCompat.getColor(this, R.color.accent_cyan_glow)
                )
                binding.ivBrandUpdateArrow.contentDescription =
                    getString(R.string.cd_update_downloading_indicator)
                binding.pillBrandHeader.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
                binding.pillBrandHeader.isClickable = false
                binding.pillBrandHeader.isFocusable = false
                binding.pillBrandHeader.setOnClickListener(null)
                startBrandArrowPulse()
            }
            readyFile != null -> {
                stopBrandArrowPulse()
                binding.ivBrandLogo.visibility = View.GONE
                binding.layoutBrandUpdateSlot.visibility = View.VISIBLE
                binding.progressBrandUpdate.visibility = View.GONE
                binding.ivBrandUpdateArrow.visibility = View.VISIBLE
                binding.ivBrandUpdateArrow.alpha = 1f
                binding.ivBrandUpdateArrow.setColorFilter(
                    ContextCompat.getColor(this, R.color.accent_emerald)
                )
                binding.ivBrandUpdateArrow.contentDescription =
                    getString(R.string.cd_update_ready_arrow)
                binding.pillBrandHeader.setBackgroundResource(R.drawable.bg_luxury_glass_pill_active)
                binding.pillBrandHeader.isClickable = true
                binding.pillBrandHeader.isFocusable = true
                binding.pillBrandHeader.setOnClickListener {
                    showInstallUpdateDialog(readyFile, update.availableUpdate)
                }
            }
            else -> {
                stopBrandArrowPulse()
                binding.ivBrandLogo.visibility = View.VISIBLE
                binding.layoutBrandUpdateSlot.visibility = View.GONE
                binding.progressBrandUpdate.visibility = View.GONE
                binding.pillBrandHeader.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
                binding.pillBrandHeader.isClickable = false
                binding.pillBrandHeader.isFocusable = false
                binding.pillBrandHeader.setOnClickListener(null)
            }
        }
    }

    private fun startBrandArrowPulse() {
        if (brandArrowPulseAnimator?.isRunning == true) return
        stopBrandArrowPulse()
        brandArrowPulseAnimator = ObjectAnimator.ofFloat(
            binding.ivBrandUpdateArrow,
            View.ALPHA,
            1f,
            0.35f
        ).apply {
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            duration = 700L
            start()
        }
    }

    private fun stopBrandArrowPulse() {
        brandArrowPulseAnimator?.cancel()
        brandArrowPulseAnimator = null
        binding.ivBrandUpdateArrow.alpha = 1f
    }

    private fun showInstallUpdateDialog(apkFile: File, updateInfo: AppUpdateInfo?) {
        installUpdateDialog?.dismiss()

        val dialogBinding = DialogInstallUpdateBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.setOnDismissListener {
            if (installUpdateDialog === dialog) {
                installUpdateDialog = null
            }
        }
        installUpdateDialog = dialog

        val versionName = updateInfo?.versionName ?: BuildConfig.VERSION_NAME
        dialogBinding.tvInstallPromptVersion.text =
            getString(R.string.update_pill_version, versionName)
        dialogBinding.tvInstallPromptMessage.text =
            getString(R.string.update_install_prompt_message, versionName)

        val notes = updateInfo?.releaseNotes?.trim().orEmpty()
        if (notes.isNotBlank()) {
            dialogBinding.tvInstallPromptNotes.visibility = View.VISIBLE
            dialogBinding.tvInstallPromptNotes.text = notes
        } else {
            dialogBinding.tvInstallPromptNotes.visibility = View.GONE
        }

        attachLuxuryPressFeedback(dialogBinding.btnInstallLater)
        attachLuxuryPressFeedback(dialogBinding.btnInstallNow)

        dialogBinding.btnInstallLater.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.btnInstallNow.setOnClickListener {
            dialog.dismiss()
            installDownloadedApk(apkFile)
        }

        dialog.show()
    }

    private fun installDownloadedApk(apkFile: File) {
        if (!apkUpdateManager.verifyApkSignatureMatchesCurrentApp(apkFile)) {
            Toast.makeText(this, R.string.update_signature_invalid, Toast.LENGTH_LONG).show()
            return
        }
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
        if (!apkUpdateManager.verifyApkSignatureMatchesCurrentApp(apkFile)) {
            Toast.makeText(this, R.string.update_signature_invalid, Toast.LENGTH_LONG).show()
            return
        }
        runCatching {
            startActivity(apkUpdateManager.buildInstallApkIntent(apkFile))
        }.onFailure {
            Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSettingsAttentionDot() {
        val missingPerms = !PermissionHelper.checkStatus(this).allGranted
        binding.dotSettingsAttention.visibility =
            if (missingPerms) View.VISIBLE else View.GONE
    }

    private fun openSettings(scrollToUpdates: Boolean = false) {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra(SettingsActivity.EXTRA_SCROLL_TO_UPDATES, scrollToUpdates)
        }
        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.pinwake_activity_enter,
            R.anim.pinwake_activity_exit
        )
        startActivity(intent, options.toBundle())
    }

    private fun requestFineLocation() {
        fineLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun syncTrackingServiceIfPossible() {
        if (PermissionHelper.hasTrackingLocation(this) && viewModel.uiState.value.activeCount > 0) {
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

    private fun handleIncomingShareIntent(incoming: Intent?) {
        if (incoming == null) return
        val action = incoming.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_SEND) return

        val rawText = when (action) {
            Intent.ACTION_VIEW -> incoming.dataString
            Intent.ACTION_SEND -> incoming.getStringExtra(Intent.EXTRA_TEXT)
                ?: incoming.getStringExtra(Intent.EXTRA_SUBJECT)
            else -> null
        }?.trim().orEmpty()

        if (rawText.isBlank()) return

        val parsed = MapLinkParser.parse(rawText)
        val pickerIntent = Intent(this, MapPickerActivity::class.java).apply {
            putExtra(MapPickerActivity.EXTRA_DEFAULT_CATEGORY, FavoriteCategory.NONE.name)
            if (parsed != null) {
                putExtra(MapPickerActivity.EXTRA_SHARED_LAT, parsed.latitude)
                putExtra(MapPickerActivity.EXTRA_SHARED_LON, parsed.longitude)
                parsed.label?.let { putExtra(MapPickerActivity.EXTRA_SHARED_LABEL, it) }
            } else {
                putExtra(MapPickerActivity.EXTRA_SHARED_SEARCH_QUERY, rawText.take(120))
            }
        }
        startActivity(pickerIntent)
    }

    private fun openMapPicker(
        alarmId: Long = -1L,
        defaultCategory: FavoriteCategory = FavoriteCategory.NONE
    ) {
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            if (alarmId > 0L) putExtra(MapPickerActivity.EXTRA_ALARM_ID, alarmId)
            putExtra(MapPickerActivity.EXTRA_DEFAULT_CATEGORY, defaultCategory.name)
        }
        val options = ActivityOptions.makeCustomAnimation(
            this,
            R.anim.pinwake_activity_enter,
            R.anim.pinwake_activity_exit
        )
        startActivity(intent, options.toBundle())
    }

    private fun checkGpsOnResume() {
        if (GpsEnforcementHelper.isGpsProviderEnabled(this)) {
            promptedGpsThisSession = false
            return
        }
        val hasActive = viewModel.uiState.value.activeCount > 0
        val mandatory = hasActive && PreAlertPreferences.isForcedGpsKeepOnEnabled(this)
        if (mandatory) {
            showGpsEnablePromptIfNeeded(mandatory = true)
        } else if (!promptedGpsThisSession) {
            promptedGpsThisSession = true
            showGpsEnablePromptIfNeeded(mandatory = false)
        }
    }

    private fun maybeHandlePromptEnableGpsIntent(incoming: Intent?) {
        if (incoming?.getBooleanExtra(EXTRA_PROMPT_ENABLE_GPS, false) == true &&
            !GpsEnforcementHelper.isGpsProviderEnabled(this)
        ) {
            showGpsEnablePromptIfNeeded(mandatory = true)
        }
    }

    private fun showGpsEnablePromptIfNeeded(mandatory: Boolean) {
        if (isDestroyed || isFinishing || isGpsDialogShowing) return
        if (GpsEnforcementHelper.isGpsProviderEnabled(this)) return
        isGpsDialogShowing = true
        GpsEnforcementHelper.showEnableGpsDialog(
            context = this,
            mandatoryForActiveTracking = mandatory,
            onOpenSettings = { isGpsDialogShowing = false },
            onDismissed = { isGpsDialogShowing = false }
        )
    }

    companion object {
        const val EXTRA_PROMPT_ENABLE_GPS = "extra_prompt_enable_gps"

        private const val MAP_HOLD_DURATION_MS = 1000L
        private const val HERO_GEOFENCE_SOURCE_ID = "pinwake-hero-geofence-source"
        private const val HERO_GEOFENCE_FILL_ID = "pinwake-hero-geofence-fill"
        private const val HERO_GEOFENCE_LINE_ID = "pinwake-hero-geofence-line"
        private const val HERO_SAVED_PLACES_SOURCE_ID = "pinwake-hero-saved-places-source"
        private const val HERO_SAVED_PLACES_HALO_ID = "pinwake-hero-saved-places-halo"
        private const val HERO_SAVED_PLACES_SYMBOL_ID = "pinwake-hero-saved-places-symbol"
    }
}
