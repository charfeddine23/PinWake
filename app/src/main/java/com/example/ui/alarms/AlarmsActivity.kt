package com.example.ui.alarms

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.PinWakeApplication
import com.example.R
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.databinding.ActivityAlarmsBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.AlarmAdapter
import com.example.ui.dashboard.DashboardUiState
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.map.MapPickerActivity
import com.example.util.GpsEnforcementHelper
import com.example.util.NavigationAssistHelper
import com.example.util.PermissionHelper
import com.example.util.ShareTripHelper
import com.example.util.TransitionHelper
import kotlinx.coroutines.launch

class AlarmsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmsBinding

    private val viewModel: DashboardViewModel by viewModels {
        val container = (application as PinWakeApplication).container
        DashboardViewModel.Factory(container.repository, container.apkUpdateManager)
    }

    private lateinit var alarmAdapter: AlarmAdapter
    private var pendingArmAlarm: GeoAlarmEntity? = null
    private var isGpsDialogShowing = false

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingArmAlarm
        pendingArmAlarm = null
        if (PermissionHelper.hasTrackingLocation(this)) {
            if (pending != null) {
                viewModel.setAlarmActive(pending, true) { hasActive ->
                    if (hasActive) {
                        PinWakeService.startOrSync(this)
                        if (!GpsEnforcementHelper.isGpsProviderEnabled(this)) {
                            showGpsEnablePromptIfNeeded(mandatory = true)
                        } else {
                            PermissionHelper.promptMandatoryBackgroundReadinessIfNeeded(this)
                        }
                    }
                }
            }
        } else if (pending != null) {
            Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
            alarmAdapter.notifyDataSetChanged()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAlarmsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TransitionHelper.finishSmooth(this@AlarmsActivity)
            }
        })

        applyInsets()
        setupRecyclerView()
        setupControls()
        observeUiState()
    }

    private fun applyInsets() {
        val density = resources.displayMetrics.density
        val baseTopBarPad = binding.alarmsTopBar.paddingTop
        val baseListBottom = (104f * density).toInt()
        val baseFabBottom = (20f * density).toInt()
        var currentTopInset = 0
        var currentBottomInset = 0

        fun updateListPadding() {
            val headerH = binding.alarmsTopBar.height
            val minHeader = (76f * density).toInt() + currentTopInset
            val targetTop = maxOf(headerH, minHeader) + (10f * density).toInt()
            val targetBottom = baseListBottom + currentBottomInset
            if (binding.rvAlarmsPage.paddingTop != targetTop ||
                binding.rvAlarmsPage.paddingBottom != targetBottom
            ) {
                binding.rvAlarmsPage.updatePadding(
                    top = targetTop,
                    bottom = targetBottom
                )
            }
        }

        binding.alarmsTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateListPadding()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootAlarms) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            currentTopInset = sysBars.top
            currentBottomInset = sysBars.bottom

            binding.alarmsTopBar.updatePadding(top = baseTopBarPad + sysBars.top)

            val fabParams = binding.fabAlarmsPageNew.layoutParams as FrameLayout.LayoutParams
            val targetFabMargin = baseFabBottom + sysBars.bottom
            if (fabParams.bottomMargin != targetFabMargin) {
                fabParams.bottomMargin = targetFabMargin
                binding.fabAlarmsPageNew.layoutParams = fabParams
            }

            updateListPadding()
            insets
        }
    }

    private fun setupRecyclerView() {
        alarmAdapter = AlarmAdapter(
            onToggleActive = { alarm, active ->
                if (active && !PermissionHelper.hasTrackingLocation(this)) {
                    pendingArmAlarm = alarm
                    alarmAdapter.notifyDataSetChanged()
                    fineLocationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
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
            onNavigateClick = { alarm ->
                NavigationAssistHelper.launchTurnByTurnNavigation(
                    context = this,
                    latitude = alarm.latitude,
                    longitude = alarm.longitude,
                    label = alarm.title,
                    alarmId = alarm.id,
                    radiusMeters = alarm.radiusMeters
                )
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

        binding.rvAlarmsPage.apply {
            layoutManager = LinearLayoutManager(this@AlarmsActivity)
            adapter = alarmAdapter
            itemAnimator = DefaultItemAnimator().apply {
                supportsChangeAnimations = false
                addDuration = 180L
                removeDuration = 180L
                moveDuration = 180L
            }
        }
    }

    private fun setupControls() {
        binding.btnAlarmsBack.setOnClickListener { TransitionHelper.finishSmooth(this) }
        binding.fabAlarmsPageNew.setOnClickListener { openMapPicker() }
        TransitionHelper.attachLuxuryPressFeedback(binding.btnAlarmsBack)
        TransitionHelper.attachLuxuryPressFeedback(binding.fabAlarmsPageNew)
    }

    private fun observeUiState() {
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
        binding.tvAlarmsPageActiveCount.text =
            getString(R.string.status_active_count, state.activeCount)

        if (state.activeCount > 0) {
            binding.tvAlarmsPageActiveCount.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.tvAlarmsPageActiveCount.setTextColor(
                ContextCompat.getColor(this, R.color.accent_emerald)
            )
        } else {
            binding.tvAlarmsPageActiveCount.setBackgroundResource(R.drawable.bg_liquid_glass_pill)
            binding.tvAlarmsPageActiveCount.setTextColor(
                ContextCompat.getColor(this, R.color.text_secondary)
            )
        }
    }

    private fun openMapPicker(alarmId: Long = -1L) {
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            if (alarmId > 0L) putExtra(MapPickerActivity.EXTRA_ALARM_ID, alarmId)
            putExtra(MapPickerActivity.EXTRA_DEFAULT_CATEGORY, FavoriteCategory.NONE.name)
        }
        TransitionHelper.startActivitySmooth(this, intent)
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
}
