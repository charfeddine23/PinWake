package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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
    private var pendingInstallApk: File? = null
    private var heroMap: MapLibreMap? = null
    private var loadedHeroStyle: OpenFreeMapStyle? = null
    private var loadedHero3dState: Boolean? = null
    private var lastHeroGeofenceKey: String? = null
    private var lastHeroCameraKey: String? = null

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionCard()
        syncTrackingServiceIfPossible()
        heroMap?.style?.let { style ->
            if (style.isFullyLoaded) enableHeroLocationPuck(style)
        }
        lastHeroCameraKey = null
        updateHeroMapViewport(viewModel.uiState.value)
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

        applyEdgeToEdgeInsets()
        setupHeroMap()
        setupRecyclerViews()
        setupClicks()
        observeDashboard()
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
        binding.dashboardMapView.onDestroy()
        super.onDestroy()
    }

    private fun applyEdgeToEdgeInsets() {
        val density = resources.displayMetrics.density
        val baseTopPad = binding.topFloatingBar.paddingTop
        val baseScrollBottom = binding.scrollDashboard.paddingBottom
        val fabBaseMargin = (20f * density).toInt()
        val baseHeroHeight = (268f * density).toInt()
        val baseMinHeroHeight = (84f * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            val targetTopPad = baseTopPad + sysBars.top
            if (binding.topFloatingBar.paddingTop != targetTopPad) {
                binding.topFloatingBar.updatePadding(top = targetTopPad)
            }

            val targetHeroHeight = baseHeroHeight + sysBars.top
            val targetMinHeroHeight = baseMinHeroHeight + sysBars.top
            val collapseParams = binding.collapsingDashboard.layoutParams
            if (collapseParams.height != targetHeroHeight ||
                binding.collapsingDashboard.minimumHeight != targetMinHeroHeight
            ) {
                collapseParams.height = targetHeroHeight
                binding.collapsingDashboard.layoutParams = collapseParams
                binding.collapsingDashboard.minimumHeight = targetMinHeroHeight
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
            insets
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
                allowGestures = false
            )
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
    private fun updateHeroMapViewport(state: DashboardUiState, forceRefresh: Boolean = false) {
        val map = heroMap ?: return
        val style = map.style
        val activeAlarms = state.alarms.filter { it.isActive }

        val geoKey = activeAlarms.joinToString("|") {
            "${it.id}:${it.latitude}:${it.longitude}:${it.radiusMeters}"
        }
        if (style != null && style.isFullyLoaded && (forceRefresh || geoKey != lastHeroGeofenceKey)) {
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

        val targetAlarm = activeAlarms.minByOrNull { it.lastDistanceMeters ?: Float.MAX_VALUE }
            ?: state.alarms.firstOrNull()

        if (targetAlarm != null) {
            val camKey = "alarm:${targetAlarm.id}:${targetAlarm.latitude}:${targetAlarm.longitude}"
            if (forceRefresh || camKey != lastHeroCameraKey) {
                lastHeroCameraKey = camKey
                val pos = CameraPosition.Builder()
                    .target(LatLng(targetAlarm.latitude, targetAlarm.longitude))
                    .zoom(14.5)
                    .tilt(if (OpenFreeMapStyles.is3dBuildingsEnabled(this)) 24.0 else 0.0)
                    .build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
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
                val camKey = "loc:${best.latitude}:${best.longitude}"
                if (forceRefresh || camKey != lastHeroCameraKey) {
                    lastHeroCameraKey = camKey
                    val pos = CameraPosition.Builder()
                        .target(LatLng(best.latitude, best.longitude))
                        .zoom(14.8)
                        .tilt(if (OpenFreeMapStyles.is3dBuildingsEnabled(this)) 24.0 else 0.0)
                        .build()
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                }
                return
            }
        }

        if (forceRefresh || lastHeroCameraKey == null) {
            lastHeroCameraKey = "default"
            val defaultPos = CameraPosition.Builder()
                .target(LatLng(48.8566, 2.3522))
                .zoom(12.5)
                .build()
            map.moveCamera(CameraUpdateFactory.newCameraPosition(defaultPos))
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
        binding.btnMapHeroTap.setOnClickListener { openMapPicker() }

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
    }
}
