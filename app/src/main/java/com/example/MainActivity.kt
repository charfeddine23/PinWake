package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
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
import com.example.data.repository.PollingTier
import com.example.databinding.ActivityMainBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.AlarmAdapter
import com.example.ui.dashboard.DashboardUiState
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.TripHistoryAdapter
import com.example.ui.map.MapPickerActivity
import com.example.util.DistanceFormatter
import com.example.util.LanguageManager
import com.example.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory((application as PinWakeApplication).container.repository)
    }

    private lateinit var alarmAdapter: AlarmAdapter
    private lateinit var historyAdapter: TripHistoryAdapter

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionCard()
        syncTrackingServiceIfPossible()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()
        setupRecyclerViews()
        setupClicks()
        observeDashboard()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionCard()
        syncTrackingServiceIfPossible()
    }

    private fun applyEdgeToEdgeInsets() {
        val baseTop = binding.contentContainer.paddingTop
        val baseBottom = binding.scrollDashboard.paddingBottom
        val fabBaseMargin = (20f * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootCoordinator) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentContainer.updatePadding(top = baseTop + sysBars.top)
            binding.scrollDashboard.updatePadding(bottom = baseBottom + sysBars.bottom)

            val fabParams = binding.fabPinAlarm.layoutParams as
                androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
            fabParams.bottomMargin = fabBaseMargin + sysBars.bottom
            binding.fabPinAlarm.layoutParams = fabParams
            insets
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
        binding.fabPinAlarm.setOnClickListener {
            openMapPicker()
        }

        binding.btnLanguageToggle.setOnClickListener {
            LanguageManager.toggleLanguage(this)
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
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
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
}
