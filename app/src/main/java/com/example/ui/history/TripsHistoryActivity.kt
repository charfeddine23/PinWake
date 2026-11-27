package com.example.ui.history

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.PinWakeApplication
import com.example.R
import com.example.data.local.entity.GeoAlarmEntity
import com.example.databinding.ActivityTripsHistoryBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.DashboardUiState
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.TripHistoryAdapter
import com.example.util.GpsEnforcementHelper
import com.example.util.PermissionHelper
import com.example.util.TransitionHelper
import kotlinx.coroutines.launch

class TripsHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTripsHistoryBinding

    private val viewModel: DashboardViewModel by viewModels {
        val container = (application as PinWakeApplication).container
        DashboardViewModel.Factory(container.repository, container.apkUpdateManager)
    }

    private lateinit var historyAdapter: TripHistoryAdapter
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
                        Toast.makeText(
                            this,
                            getString(R.string.trip_rearmed_toast, pending.title),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else if (pending != null) {
            Toast.makeText(this, R.string.perm_location_needed_to_arm, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTripsHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                TransitionHelper.finishSmooth(this@TripsHistoryActivity)
            }
        })

        applyInsets()
        setupRecyclerView()
        setupControls()
        observeUiState()
    }

    private fun applyInsets() {
        val density = resources.displayMetrics.density
        val baseTopBarPad = binding.historyTopBar.paddingTop
        val baseListBottom = (32f * density).toInt()
        var currentTopInset = 0
        var currentBottomInset = 0

        fun updateListPadding() {
            val headerH = binding.historyTopBar.height
            val minHeader = (76f * density).toInt() + currentTopInset
            val targetTop = maxOf(headerH, minHeader) + (10f * density).toInt()
            val targetBottom = baseListBottom + currentBottomInset
            if (binding.rvTripsHistoryPage.paddingTop != targetTop ||
                binding.rvTripsHistoryPage.paddingBottom != targetBottom
            ) {
                binding.rvTripsHistoryPage.updatePadding(
                    top = targetTop,
                    bottom = targetBottom
                )
            }
        }

        binding.historyTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateListPadding()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootTripsHistory) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            currentTopInset = sysBars.top
            currentBottomInset = sysBars.bottom

            binding.historyTopBar.updatePadding(top = baseTopBarPad + sysBars.top)
            updateListPadding()
            insets
        }
    }

    private fun setupRecyclerView() {
        historyAdapter = TripHistoryAdapter { trip ->
            val hasPerm = PermissionHelper.hasTrackingLocation(this)
            viewModel.replayTrip(trip) { rearmedAlarm, hasActive ->
                if (isDestroyed || isFinishing) return@replayTrip
                if (!hasPerm) {
                    viewModel.setAlarmActive(rearmedAlarm, false) {
                        pendingArmAlarm = rearmedAlarm
                        fineLocationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
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

        binding.rvTripsHistoryPage.apply {
            layoutManager = LinearLayoutManager(this@TripsHistoryActivity)
            adapter = historyAdapter
        }
    }

    private fun setupControls() {
        binding.btnHistoryBack.setOnClickListener { TransitionHelper.finishSmooth(this) }
        binding.btnHistoryClearAll.setOnClickListener {
            viewModel.clearHistory()
            Toast.makeText(this, R.string.settings_history_cleared_toast, Toast.LENGTH_SHORT).show()
        }
        TransitionHelper.attachLuxuryPressFeedback(binding.btnHistoryBack)
        TransitionHelper.attachLuxuryPressFeedback(binding.btnHistoryClearAll)
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
        historyAdapter.submitList(state.recentTrips)
        val isEmpty = state.recentTrips.isEmpty()
        binding.emptyHistoryCard.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.btnHistoryClearAll.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
