package com.example.ui.nav

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
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.PinWakeApplication
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.databinding.ActivityInAppNavigationBinding
import com.example.service.PinWakeService
import com.example.util.DistanceFormatter
import com.example.util.GpsEnforcementHelper
import com.example.util.InAppNavigationEngine
import com.example.util.ManeuverIconType
import com.example.util.MapStyleHelper
import com.example.util.NavProgressState
import com.example.util.NavRoute
import com.example.util.NavigationAssistCalculator
import com.example.util.NavigationVoiceCoach
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.example.util.SafeMapLocationEngine
import com.example.util.TransitionHelper
import com.example.util.TravelProfile
import kotlinx.coroutines.Job
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
import org.maplibre.geojson.Point
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

class InAppNavigationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInAppNavigationBinding
    private val repository by lazy { (application as PinWakeApplication).container.repository }
    private val safeLocationEngine by lazy { SafeMapLocationEngine(this) }
    private lateinit var locationManager: LocationManager
    private lateinit var voiceCoach: NavigationVoiceCoach

    private var navMap: MapLibreMap? = null
    private var locationListener: LocationListener? = null
    private var routeFetchJob: Job? = null

    private var destLat: Double = 0.0
    private var destLon: Double = 0.0
    private var destLabel: String = ""
    private var alarmId: Long = -1L
    private var radiusMeters: Int = 500
    private var linkedAlarm: GeoAlarmEntity? = null

    private var travelProfile: TravelProfile = TravelProfile.DRIVING
    private var currentRoute: NavRoute? = null
    private var latestUserLocation: Location? = null
    private var isFetchingRoute = false
    private var lastRouteFetchMs = 0L
    private var isCameraFollowing = true
    private var sysTopInset = 0
    private var sysBottomInset = 0

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasLocationPermission()) {
            navMap?.style?.let { style ->
                if (style.isFullyLoaded) enableLocationPuck(style)
            }
            startLiveLocationUpdates()
            GpsEnforcementHelper.getBestLocation(this)?.let { loc ->
                latestUserLocation = loc
                requestRoute(loc.latitude, loc.longitude, force = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isRobolectric()) {
            MapLibre.getInstance(this)
        }
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityInAppNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navMapView.onCreate(savedInstanceState)
        locationManager = getSystemService(LocationManager::class.java)
        voiceCoach = NavigationVoiceCoach(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TransitionHelper.finishSmooth(this@InAppNavigationActivity)
            }
        })

        destLat = intent.getDoubleExtra(EXTRA_DEST_LAT, 0.0)
        destLon = intent.getDoubleExtra(EXTRA_DEST_LON, 0.0)
        destLabel = intent.getStringExtra(EXTRA_DEST_LABEL).orEmpty().trim()
        alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        radiusMeters = intent.getIntExtra(EXTRA_RADIUS_METERS, 500).coerceIn(5, 5000)

        binding.tvNavDestTitle.text = destLabel.ifBlank {
            getString(R.string.map_unknown_address, destLat, destLon)
        }

        applyInsets()
        setupControls()
        loadLinkedAlarmIfAny()
        setupMap()

        if (hasLocationPermission()) {
            latestUserLocation = GpsEnforcementHelper.getBestLocation(this)
        } else if (!isRobolectric()) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
        latestUserLocation?.let { loc ->
            requestRoute(loc.latitude, loc.longitude, force = true)
        } ?: run {
            val fallbackRoute = InAppNavigationEngine.buildDirectFallbackRoute(
                fromLat = destLat,
                fromLon = destLon,
                toLat = destLat,
                toLon = destLon,
                profile = travelProfile
            )
            currentRoute = fallbackRoute
            renderWaitingGpsState()
        }
    }

    private fun applyInsets() {
        val density = resources.displayMetrics.density
        val baseTopMargin = (12f * density).toInt()
        val baseBottomPad = binding.cardNavBottomHud.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.navRoot) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            sysTopInset = sysBars.top
            sysBottomInset = sysBars.bottom

            val hudParams = binding.cardManeuverHud.layoutParams as
                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            val targetTop = baseTopMargin + sysBars.top
            if (hudParams.topMargin != targetTop) {
                hudParams.topMargin = targetTop
                binding.cardManeuverHud.layoutParams = hudParams
            }

            val targetBottomPad = baseBottomPad + sysBars.bottom
            if (binding.cardNavBottomHud.paddingBottom != targetBottomPad) {
                binding.cardNavBottomHud.updatePadding(bottom = targetBottomPad)
            }

            val blurParams = binding.navTopLiquidBlur.layoutParams
            val targetBlurHeight = (98f * density).toInt() + sysBars.top
            if (blurParams.height != targetBlurHeight) {
                blurParams.height = targetBlurHeight
                binding.navTopLiquidBlur.layoutParams = blurParams
            }
            insets
        }
    }

    private fun setupControls() {
        binding.btnNavBack.setOnClickListener { TransitionHelper.finishSmooth(this) }
        binding.btnEndNavigation.setOnClickListener { TransitionHelper.finishSmooth(this) }

        listOf(
            binding.btnNavBack,
            binding.btnEndNavigation,
            binding.btnToggleProfile,
            binding.btnToggleVoice,
            binding.btnRecenterNav,
            binding.btnNavWakeAlarmToggle
        ).forEach { TransitionHelper.attachLuxuryPressFeedback(it) }

        binding.btnToggleVoice.setOnClickListener {
            voiceCoach.isMuted = !voiceCoach.isMuted
            renderVoiceToggle()
        }

        binding.btnToggleProfile.setOnClickListener {
            travelProfile = if (travelProfile == TravelProfile.DRIVING) {
                TravelProfile.WALKING
            } else {
                TravelProfile.DRIVING
            }
            renderProfileToggle()
            val loc = latestUserLocation
            if (loc != null) {
                requestRoute(loc.latitude, loc.longitude, force = true)
            }
        }

        binding.btnRecenterNav.setOnClickListener {
            isCameraFollowing = true
            renderRecenterButton()
            val loc = latestUserLocation
            if (loc != null) {
                val bearing = if (loc.hasBearing() && loc.speed >= 0.8f) {
                    loc.bearing
                } else {
                    NavigationAssistCalculator.computeDistanceAndBearing(
                        loc.latitude, loc.longitude, destLat, destLon
                    ).second
                }
                moveFollowCamera(loc.latitude, loc.longitude, bearing, animate = true)
            } else {
                moveFollowCamera(destLat, destLon, 0f, animate = true)
            }
        }

        binding.btnNavWakeAlarmToggle.setOnClickListener {
            toggleLinkedWakeAlarm()
        }

        renderVoiceToggle()
        renderProfileToggle()
        renderRecenterButton()
        renderWakeStatusBadge()
    }

    private fun loadLinkedAlarmIfAny() {
        if (alarmId <= 0L) {
            renderWakeStatusBadge()
            return
        }
        lifecycleScope.launch {
            linkedAlarm = repository.getAlarmById(alarmId)
            linkedAlarm?.let { alarm ->
                radiusMeters = alarm.radiusMeters.coerceIn(5, 5000)
                if (destLabel.isBlank()) {
                    destLabel = alarm.title
                    binding.tvNavDestTitle.text = destLabel
                }
                updateMapOverlays()
            }
            renderWakeStatusBadge()
        }
    }

    private fun toggleLinkedWakeAlarm() {
        val alarm = linkedAlarm ?: return
        if (!alarm.isActive && !PermissionHelper.hasTrackingLocation(this)) {
            Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
            return
        }
        val nextActive = !alarm.isActive
        lifecycleScope.launch {
            repository.setAlarmActive(alarm.id, nextActive, null)
            linkedAlarm = repository.getAlarmById(alarm.id)
            val hasAnyActive = repository.getActiveAlarmsOnce().isNotEmpty()
            if (hasAnyActive && PermissionHelper.hasTrackingLocation(this@InAppNavigationActivity)) {
                PinWakeService.startOrSync(this@InAppNavigationActivity)
            } else if (!hasAnyActive) {
                PinWakeService.stopTracking(this@InAppNavigationActivity)
            }
            renderWakeStatusBadge()
        }
    }

    private fun setupMap() {
        if (isRobolectric()) return
        val styleOption = OpenFreeMapStyles.getSavedStyle(this)
        val enable3d = OpenFreeMapStyles.is3dBuildingsEnabled(this)

        binding.navMapView.getMapAsync { map ->
            if (isDestroyed || isFinishing) return@getMapAsync
            navMap = map
            MapStyleHelper.configureHighPrecisionMap(
                context = this,
                mapView = binding.navMapView,
                map = map,
                allowGestures = true
            )

            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && isCameraFollowing) {
                    isCameraFollowing = false
                    renderRecenterButton()
                }
            }

            val startLat = latestUserLocation?.latitude ?: destLat
            val startLon = latestUserLocation?.longitude ?: destLon
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(startLat, startLon))
                .zoom(16.6)
                .tilt(if (enable3d) 48.0 else 36.0)
                .build()

            map.setStyle(Style.Builder().fromUri(styleOption.styleUrl)) { style ->
                if (isDestroyed || isFinishing) return@setStyle
                MapStyleHelper.applyGoogleNavigationStyling(
                    style = style,
                    mode = styleOption,
                    enable3dBuildings = enable3d
                )
                enableLocationPuck(style)
                updateMapOverlays()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationPuck(style: Style) {
        if (!hasLocationPermission()) return
        val map = navMap ?: return
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
                .useDefaultLocationEngine(false)
                .locationEngine(safeLocationEngine)
                .build()

            map.locationComponent.apply {
                activateLocationComponent(activation)
                isLocationComponentEnabled = true
                cameraMode = CameraMode.NONE
                renderMode = RenderMode.COMPASS
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLiveLocationUpdates() {
        if (!hasLocationPermission()) return
        if (locationListener != null) return

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onNewLocation(location)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        var registered = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                if (locationManager.isProviderEnabled(provider)) {
                    locationManager.requestLocationUpdates(
                        provider,
                        1_000L,
                        2f,
                        listener,
                        Looper.getMainLooper()
                    )
                    registered = true
                }
            }
        }
        if (registered) {
            locationListener = listener
        }
        GpsEnforcementHelper.getBestLocation(this)?.let { onNewLocation(it) }
    }

    private fun stopLiveLocationUpdates() {
        locationListener?.let { listener ->
            runCatching { locationManager.removeUpdates(listener) }
        }
        locationListener = null
    }

    private fun onNewLocation(location: Location) {
        if (isDestroyed || isFinishing) return
        latestUserLocation = location

        val route = currentRoute
        if (route == null) {
            requestRoute(location.latitude, location.longitude, force = true)
            return
        }

        val speedMps = if (location.hasSpeed()) location.speed else null
        val progress = InAppNavigationEngine.computeProgress(
            route = route,
            currentLat = location.latitude,
            currentLon = location.longitude,
            currentSpeedMps = speedMps,
            geofenceRadiusMeters = radiusMeters
        )

        renderProgress(route, progress, speedMps)
        updateMapOverlays(progress)

        if (isCameraFollowing) {
            val camBearing = if (location.hasBearing() && (speedMps ?: 0f) >= 0.8f) {
                location.bearing
            } else {
                progress.activeBearingDegrees
            }
            moveFollowCamera(
                lat = location.latitude,
                lon = location.longitude,
                bearing = camBearing,
                animate = true
            )
        }

        if (progress.shouldReroute && !isFetchingRoute) {
            val now = System.currentTimeMillis()
            if (now - lastRouteFetchMs >= REROUTE_COOLDOWN_MS) {
                binding.layoutNextStepStrip.visibility = View.VISIBLE
                binding.tvNextStepInstruction.text = getString(R.string.nav_rerouting)
                requestRoute(location.latitude, location.longitude, force = false)
            }
        }
    }

    private fun requestRoute(fromLat: Double, fromLon: Double, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (isFetchingRoute || now - lastRouteFetchMs < REROUTE_COOLDOWN_MS)) return

        routeFetchJob?.cancel()
        isFetchingRoute = true
        lastRouteFetchMs = now
        binding.progressRouteFetch.visibility = View.VISIBLE

        routeFetchJob = lifecycleScope.launch {
            val fetched = repository.fetchNavigationRoute(
                fromLat = fromLat,
                fromLon = fromLon,
                toLat = destLat,
                toLon = destLon,
                profile = travelProfile
            )
            if (isDestroyed || isFinishing) return@launch
            isFetchingRoute = false
            binding.progressRouteFetch.visibility = View.GONE
            currentRoute = fetched
            voiceCoach.resetForNewRoute()

            val loc = latestUserLocation
            if (loc != null) {
                val speed = if (loc.hasSpeed()) loc.speed else null
                val progress = InAppNavigationEngine.computeProgress(
                    route = fetched,
                    currentLat = loc.latitude,
                    currentLon = loc.longitude,
                    currentSpeedMps = speed,
                    geofenceRadiusMeters = radiusMeters
                )
                renderProgress(fetched, progress, speed)
                updateMapOverlays(progress)
                if (isCameraFollowing) {
                    moveFollowCamera(loc.latitude, loc.longitude, progress.activeBearingDegrees, animate = true)
                }
            } else {
                updateMapOverlays()
            }
        }
    }

    private fun renderProgress(
        route: NavRoute,
        progress: NavProgressState,
        speedMps: Float?
    ) {
        val instruction = InAppNavigationEngine.formatInstruction(
            context = this,
            step = progress.upcomingStep,
            destinationTitle = destLabel
        )
        binding.ivManeuverIcon.setImageResource(iconResFor(progress.upcomingStep.iconType))
        binding.tvManeuverDistance.text = if (progress.hasArrived) {
            getString(R.string.nav_arrived_status)
        } else {
            DistanceFormatter.formatCompactDistance(progress.distanceToNextTurnMeters)
        }
        binding.tvManeuverInstruction.text = instruction

        if (route.isOfflineDirectFallback) {
            binding.tvNavRouteModeBadge.setBackgroundResource(R.drawable.bg_badge_amber)
            binding.tvNavRouteModeBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            binding.tvNavRouteModeBadge.setText(R.string.nav_mode_offline_direct)
        } else {
            binding.tvNavRouteModeBadge.setBackgroundResource(R.drawable.bg_badge_cyan)
            binding.tvNavRouteModeBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_cyan_glow))
            binding.tvNavRouteModeBadge.setText(R.string.nav_mode_road)
        }

        val nextStep = progress.followingStep
        val showNextStep = nextStep != null && !progress.hasArrived
        val wasNextStepVisible = binding.layoutNextStepStrip.visibility == View.VISIBLE
        if (wasNextStepVisible != showNextStep) {
            TransitionHelper.animateLayoutChange(binding.cardManeuverHud, 180L)
        }
        if (showNextStep && nextStep != null) {
            binding.layoutNextStepStrip.visibility = View.VISIBLE
            binding.ivNextStepIcon.setImageResource(iconResFor(nextStep.iconType))
            val nextText = InAppNavigationEngine.formatInstruction(this, nextStep, destLabel)
            binding.tvNextStepInstruction.text = getString(R.string.nav_then_prefix, nextText)
        } else {
            binding.layoutNextStepStrip.visibility = View.GONE
        }

        binding.tvNavEtaDuration.text = NavigationAssistCalculator.formatEtaCompact(
            context = this,
            etaSeconds = progress.remainingDurationSeconds,
            isLiveSpeed = progress.isLiveSpeedEta
        )
        binding.tvNavRemainingDist.text = DistanceFormatter.formatCompactDistance(
            progress.remainingDistanceMeters
        )

        val arrivalMs = System.currentTimeMillis() + progress.remainingDurationSeconds * 1000L
        val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(arrivalMs))
        binding.tvNavArrivalClock.text = getString(R.string.nav_arrive_clock, clock)

        val kmh = ((speedMps ?: 0f).coerceAtLeast(0f) * 3.6f).roundToInt()
        binding.tvNavSpeed.text = getString(R.string.nav_speed_kmh, kmh)

        voiceCoach.maybeAnnounceProgress(
            step = progress.upcomingStep,
            distanceToTurnMeters = progress.distanceToNextTurnMeters,
            hasArrived = progress.hasArrived,
            spokenInstruction = instruction
        )
    }

    private fun renderWaitingGpsState() {
        binding.tvManeuverDistance.text = "--"
        binding.tvManeuverInstruction.setText(R.string.nav_assist_waiting_gps)
        binding.tvNavEtaDuration.setText(R.string.nav_assist_eta_unknown)
        binding.tvNavRemainingDist.text = ""
        binding.tvNavArrivalClock.text = ""
        binding.tvNavSpeed.text = getString(R.string.nav_speed_kmh, 0)
    }

    private fun updateMapOverlays(progress: NavProgressState? = null) {
        val style = navMap?.style ?: return
        if (!style.isFullyLoaded) return

        val circleFeature = MapStyleHelper.createGeodesicCircleFeature(
            lat = destLat,
            lon = destLon,
            radiusMeters = radiusMeters
        )
        MapStyleHelper.ensureGeofenceCircleLayers(
            style = style,
            sourceId = NAV_GEOFENCE_SOURCE_ID,
            fillLayerId = NAV_GEOFENCE_FILL_ID,
            lineLayerId = NAV_GEOFENCE_LINE_ID,
            features = FeatureCollection.fromFeature(circleFeature)
        )

        val route = currentRoute
        val points = (progress?.remainingRoutePoints ?: route?.points).orEmpty().map {
            Point.fromLngLat(it.longitude, it.latitude)
        }
        MapStyleHelper.ensureNavigationPolylineLayers(
            style = style,
            sourceId = NAV_ROUTE_SOURCE_ID,
            casingLayerId = NAV_ROUTE_CASING_ID,
            lineLayerId = NAV_ROUTE_LINE_ID,
            points = points,
            isDashed = route?.isOfflineDirectFallback ?: true,
            visible = points.size >= 2,
            belowLayerId = NAV_DEST_HALO_ID
        )

        val destPin = linkedAlarm ?: GeoAlarmEntity(
            id = 1L,
            title = destLabel.ifBlank { getString(R.string.nav_title) },
            address = "",
            latitude = destLat,
            longitude = destLon,
            radiusMeters = radiusMeters,
            isActive = true,
            favoriteCategory = FavoriteCategory.NONE
        )
        MapStyleHelper.ensureSavedPlaceMarkersLayer(
            context = this,
            style = style,
            sourceId = NAV_DEST_SOURCE_ID,
            haloLayerId = NAV_DEST_HALO_ID,
            symbolLayerId = NAV_DEST_SYMBOL_ID,
            favorites = listOf(destPin),
            isDark = OpenFreeMapStyles.getSavedStyle(this).isDark
        )
    }

    private fun moveFollowCamera(lat: Double, lon: Double, bearing: Float, animate: Boolean) {
        val map = navMap ?: return
        val density = resources.displayMetrics.density
        val topPad = sysTopInset.toDouble() + (132f * density)
        val bottomPad = sysBottomInset.toDouble() + (168f * density)
        val tilt = if (OpenFreeMapStyles.is3dBuildingsEnabled(this)) 48.0 else 36.0
        val pos = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(17.2)
            .bearing(bearing.toDouble())
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

    private fun renderVoiceToggle() {
        if (voiceCoach.isMuted) {
            binding.btnToggleVoice.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            binding.btnToggleVoice.setImageResource(R.drawable.ic_volume_off)
            binding.btnToggleVoice.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.btnToggleVoice.setBackgroundResource(R.drawable.bg_luxury_glass_pill_cyan)
            binding.btnToggleVoice.setImageResource(R.drawable.ic_volume_on)
            binding.btnToggleVoice.setColorFilter(ContextCompat.getColor(this, R.color.accent_cyan_glow))
        }
    }

    private fun renderProfileToggle() {
        if (travelProfile == TravelProfile.DRIVING) {
            binding.ivNavProfileIcon.setImageResource(R.drawable.ic_directions_car)
            binding.tvNavProfileLabel.setText(R.string.nav_profile_driving)
        } else {
            binding.ivNavProfileIcon.setImageResource(R.drawable.ic_directions_walk)
            binding.tvNavProfileLabel.setText(R.string.nav_profile_walking)
        }
    }

    private fun renderRecenterButton() {
        val showLabel = !isCameraFollowing
        val wasLabelVisible = binding.tvRecenterLabel.visibility == View.VISIBLE
        if (wasLabelVisible != showLabel) {
            TransitionHelper.animateLayoutChange(binding.btnRecenterNav, 160L)
        }
        if (isCameraFollowing) {
            binding.btnRecenterNav.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            binding.tvRecenterLabel.visibility = View.GONE
            binding.ivRecenterNav.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.btnRecenterNav.setBackgroundResource(R.drawable.bg_luxury_glass_pill_cyan)
            binding.tvRecenterLabel.visibility = View.VISIBLE
            binding.ivRecenterNav.setColorFilter(ContextCompat.getColor(this, R.color.accent_cyan_glow))
        }
    }

    private fun renderWakeStatusBadge() {
        val alarm = linkedAlarm
        val isArmed = alarm?.isActive == true
        val effectiveRadius = alarm?.radiusMeters ?: radiusMeters
        binding.btnNavWakeAlarmToggle.isClickable = alarm != null
        binding.btnNavWakeAlarmToggle.isFocusable = alarm != null
        if (isArmed) {
            binding.btnNavWakeAlarmToggle.setBackgroundResource(R.drawable.bg_luxury_glass_pill_active)
            val emerald = ContextCompat.getColor(this, R.color.accent_emerald)
            binding.ivNavWakeBell.setColorFilter(emerald)
            binding.tvNavWakeStatus.setTextColor(emerald)
            binding.tvNavWakeStatus.text = getString(R.string.nav_alarm_armed_radius, effectiveRadius)
        } else if (alarm != null) {
            binding.btnNavWakeAlarmToggle.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            val secondary = ContextCompat.getColor(this, R.color.text_secondary)
            binding.ivNavWakeBell.setColorFilter(secondary)
            binding.tvNavWakeStatus.setTextColor(secondary)
            binding.tvNavWakeStatus.text = getString(R.string.nav_alarm_off_radius, effectiveRadius)
        } else {
            binding.btnNavWakeAlarmToggle.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            val cyan = ContextCompat.getColor(this, R.color.accent_cyan_glow)
            binding.ivNavWakeBell.setColorFilter(cyan)
            binding.tvNavWakeStatus.setTextColor(cyan)
            binding.tvNavWakeStatus.text = DistanceFormatter.formatRadiusBadge(this, effectiveRadius)
        }
    }

    private fun iconResFor(iconType: ManeuverIconType): Int = when (iconType) {
        ManeuverIconType.STRAIGHT -> R.drawable.ic_turn_straight
        ManeuverIconType.SLIGHT_LEFT -> R.drawable.ic_turn_slight_left
        ManeuverIconType.LEFT -> R.drawable.ic_turn_left
        ManeuverIconType.SHARP_LEFT -> R.drawable.ic_turn_sharp_left
        ManeuverIconType.SLIGHT_RIGHT -> R.drawable.ic_turn_slight_right
        ManeuverIconType.RIGHT -> R.drawable.ic_turn_right
        ManeuverIconType.SHARP_RIGHT -> R.drawable.ic_turn_sharp_right
        ManeuverIconType.UTURN -> R.drawable.ic_turn_uturn
        ManeuverIconType.ROUNDABOUT -> R.drawable.ic_roundabout
        ManeuverIconType.ARRIVE -> R.drawable.ic_flag_destination
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

    private fun isRobolectric(): Boolean =
        "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)

    override fun onStart() {
        super.onStart()
        binding.navMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.navMapView.onResume()
        startLiveLocationUpdates()
        if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
            GpsEnforcementHelper.showEnableGpsDialog(
                context = this,
                mandatoryForActiveTracking = linkedAlarm?.isActive == true
            )
        }
    }

    override fun onPause() {
        stopLiveLocationUpdates()
        binding.navMapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.navMapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.navMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.navMapView.onLowMemory()
    }

    override fun onDestroy() {
        routeFetchJob?.cancel()
        stopLiveLocationUpdates()
        voiceCoach.shutdown()
        runCatching {
            navMap?.locationComponent?.takeIf { it.isLocationComponentActivated }?.isLocationComponentEnabled = false
        }
        binding.navMapView.onDestroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DEST_LAT = "extra_dest_lat"
        const val EXTRA_DEST_LON = "extra_dest_lon"
        const val EXTRA_DEST_LABEL = "extra_dest_label"
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_RADIUS_METERS = "extra_radius_meters"

        private const val REROUTE_COOLDOWN_MS = 12_000L
        private const val NAV_GEOFENCE_SOURCE_ID = "pinwake-nav-geofence-source"
        private const val NAV_GEOFENCE_FILL_ID = "pinwake-nav-geofence-fill"
        private const val NAV_GEOFENCE_LINE_ID = "pinwake-nav-geofence-line"
        private const val NAV_ROUTE_SOURCE_ID = "pinwake-nav-route-source"
        private const val NAV_ROUTE_CASING_ID = "pinwake-nav-route-casing"
        private const val NAV_ROUTE_LINE_ID = "pinwake-nav-route-line"
        private const val NAV_DEST_SOURCE_ID = "pinwake-nav-dest-source"
        private const val NAV_DEST_HALO_ID = "pinwake-nav-dest-halo"
        private const val NAV_DEST_SYMBOL_ID = "pinwake-nav-dest-symbol"
    }
}
