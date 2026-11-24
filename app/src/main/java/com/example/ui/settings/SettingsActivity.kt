package com.example.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
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
import com.example.BuildConfig
import com.example.PinWakeApplication
import com.example.R
import com.example.data.remote.GitHubReleaseApiService
import com.example.databinding.ActivitySettingsBinding
import com.example.service.PinWakeService
import com.example.ui.dashboard.DashboardViewModel
import com.example.ui.dashboard.UpdateCheckEvent
import com.example.ui.dashboard.UpdateUiState
import com.example.util.ApkUpdateManager
import com.example.util.LanguageManager
import com.example.util.OpenFreeMapStyle
import com.example.util.OpenFreeMapStyles
import com.example.util.PermissionHelper
import com.example.util.PreAlertPreferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val apkUpdateManager: ApkUpdateManager by lazy {
        (application as PinWakeApplication).container.apkUpdateManager
    }

    private val viewModel: DashboardViewModel by viewModels {
        val container = (application as PinWakeApplication).container
        DashboardViewModel.Factory(container.repository, container.apkUpdateManager)
    }

    private var pendingInstallApk: File? = null
    private var pendingExportJson: String? = null
    private var isSyncing3dSwitch = false

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissionsUi()
        syncTrackingServiceIfPossible()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        refreshPermissionsUi()
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startActivity(PermissionHelper.buildAppSettingsIntent(this))
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissionsUi()
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

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            writeBackupToUri(uri, json)
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            readAndRestoreBackupFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        setupControls()
        syncLanguageUi()
        syncMapStyleUi()
        syncPreAlertUi()
        refreshPermissionsUi()
        binding.tvSettingsCurrentVersion.text =
            getString(R.string.settings_current_version, BuildConfig.VERSION_NAME)
        observeViewModel()

        if (intent.getBooleanExtra(EXTRA_SCROLL_TO_UPDATES, false)) {
            binding.scrollSettings.post {
                binding.scrollSettings.smoothScrollTo(0, binding.cardSettingsUpdateAvailable.top)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionsUi()
        syncLanguageUi()
        syncMapStyleUi()
        syncTrackingServiceIfPossible()
    }

    private fun applyInsets() {
        val density = resources.displayMetrics.density
        val baseTopBarPad = binding.settingsTopBar.paddingTop
        val baseScrollBottom = (32f * density).toInt()
        var currentBottomInset = 0
        var currentTopInset = 0

        fun updateScrollTopClearance() {
            val headerH = binding.settingsTopBar.height
            val minHeader = (72f * density).toInt() + currentTopInset
            val targetTop = maxOf(headerH, minHeader) + (6f * density).toInt()
            if (binding.scrollSettings.paddingTop != targetTop) {
                binding.scrollSettings.updatePadding(top = targetTop)
            }
        }

        binding.settingsTopBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateScrollTopClearance()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootSettings) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            currentTopInset = sysBars.top
            currentBottomInset = sysBars.bottom

            binding.settingsTopBar.updatePadding(top = baseTopBarPad + sysBars.top)
            binding.scrollSettings.updatePadding(bottom = baseScrollBottom + currentBottomInset)
            updateScrollTopClearance()
            insets
        }
    }

    private fun setupControls() {
        binding.btnSettingsBack.setOnClickListener { finish() }

        listOf(
            binding.btnSettingsBack,
            binding.optLangEnglish,
            binding.optLangArabic,
            binding.optSettingsMapDark,
            binding.optSettingsMapLight,
            binding.optPreAlertOff,
            binding.optPreAlert300,
            binding.optPreAlert500,
            binding.optPreAlert1000,
            binding.btnOpenAndroidAppSettings,
            binding.btnSettingsCheckUpdates,
            binding.btnDownloadUpdate,
            binding.btnOpenGithubRelease,
            binding.btnSettingsClearHistory,
            binding.btnExportBackupJson,
            binding.btnImportBackupJson
        ).forEach { attachLuxuryPressFeedback(it) }

        // Language
        binding.optLangEnglish.setOnClickListener {
            if (LanguageManager.getCurrentLanguageCode(this) != "en") {
                LanguageManager.setLanguage(this, "en")
            }
        }
        binding.optLangArabic.setOnClickListener {
            if (LanguageManager.getCurrentLanguageCode(this) != "ar") {
                LanguageManager.setLanguage(this, "ar")
            }
        }

        // Map Appearance & 3D Buildings
        binding.optSettingsMapDark.setOnClickListener {
            OpenFreeMapStyles.saveStyle(this, OpenFreeMapStyle.GOOGLE_DARK)
            syncMapStyleUi()
        }
        binding.optSettingsMapLight.setOnClickListener {
            OpenFreeMapStyles.saveStyle(this, OpenFreeMapStyle.GOOGLE_LIGHT)
            syncMapStyleUi()
        }
        binding.rowSettings3dBuildings.setOnClickListener {
            binding.switchSettings3dBuildings.toggle()
        }
        binding.switchSettings3dBuildings.setOnCheckedChangeListener { _, isChecked ->
            if (isSyncing3dSwitch) return@setOnCheckedChangeListener
            OpenFreeMapStyles.set3dBuildingsEnabled(this, isChecked)
        }

        // Configurable Early Arrival Warning (Pre-Alert)
        binding.optPreAlertOff.setOnClickListener { updatePreAlertMeters(PreAlertPreferences.PRE_ALERT_OFF) }
        binding.optPreAlert300.setOnClickListener { updatePreAlertMeters(PreAlertPreferences.PRE_ALERT_300M) }
        binding.optPreAlert500.setOnClickListener { updatePreAlertMeters(PreAlertPreferences.PRE_ALERT_500M) }
        binding.optPreAlert1000.setOnClickListener { updatePreAlertMeters(PreAlertPreferences.PRE_ALERT_1000M) }

        // Background Permissions
        binding.btnPermFineLocation.setOnClickListener { requestFineLocation() }
        binding.btnPermBackgroundLocation.setOnClickListener { requestBackgroundLocation() }
        binding.btnPermNotifications.setOnClickListener { requestNotifications() }
        binding.btnPermFullScreen.setOnClickListener {
            runCatching { startActivity(PermissionHelper.buildFullScreenIntentSettings(this)) }
        }
        binding.btnPermBattery.setOnClickListener {
            runCatching { startActivity(PermissionHelper.buildBatteryOptimizationIntent(this)) }
        }
        binding.btnOpenAndroidAppSettings.setOnClickListener {
            runCatching { startActivity(PermissionHelper.buildAppSettingsIntent(this)) }
        }

        // App Updates
        binding.btnSettingsCheckUpdates.setOnClickListener {
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
                ?.takeIf { ApkUpdateManager.isTrustedGitHubHttpsUrl(it) }
                ?: GitHubReleaseApiService.REPO_RELEASES_URL
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }

        // Trip History Data
        binding.btnSettingsClearHistory.setOnClickListener {
            viewModel.clearHistory()
            Toast.makeText(this, R.string.settings_history_cleared_toast, Toast.LENGTH_SHORT).show()
        }

        // Local JSON Backup & Restore
        binding.btnExportBackupJson.setOnClickListener {
            val preAlert = PreAlertPreferences.getPreAlertDistanceMeters(this)
            viewModel.exportBackupJson(preAlert) { count, json ->
                if (count == 0) {
                    Toast.makeText(this, R.string.backup_export_empty, Toast.LENGTH_SHORT).show()
                    return@exportBackupJson
                }
                pendingExportJson = json
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportBackupLauncher.launch("pinwake-backup-$stamp.json")
            }
        }
        binding.btnImportBackupJson.setOnClickListener {
            importBackupLauncher.launch(arrayOf("application/json", "text/*"))
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        val count = state.recentTrips.size
                        if (count == 0) {
                            binding.tvSettingsHistoryCount.setText(R.string.settings_history_empty)
                            binding.btnSettingsClearHistory.isEnabled = false
                            binding.btnSettingsClearHistory.alpha = 0.45f
                        } else {
                            binding.tvSettingsHistoryCount.text =
                                getString(R.string.settings_history_count, count)
                            binding.btnSettingsClearHistory.isEnabled = true
                            binding.btnSettingsClearHistory.alpha = 1f
                        }
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
                                    this@SettingsActivity,
                                    getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                                    Toast.LENGTH_SHORT
                                ).show()
                                viewModel.consumeUpdateEvent()
                            }
                            is UpdateCheckEvent.Failed -> {
                                Toast.makeText(
                                    this@SettingsActivity,
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

    private fun syncLanguageUi() {
        val isArabic = LanguageManager.getCurrentLanguageCode(this) == "ar"
        applySelectionPillStyle(binding.optLangEnglish, selected = !isArabic)
        applySelectionPillStyle(binding.optLangArabic, selected = isArabic)
    }

    private fun syncMapStyleUi() {
        val style = OpenFreeMapStyles.getSavedStyle(this)
        val enable3d = OpenFreeMapStyles.is3dBuildingsEnabled(this)

        applySelectionPillStyle(
            binding.optSettingsMapDark,
            selected = style == OpenFreeMapStyle.GOOGLE_DARK
        )
        applySelectionPillStyle(
            binding.optSettingsMapLight,
            selected = style == OpenFreeMapStyle.GOOGLE_LIGHT
        )

        isSyncing3dSwitch = true
        binding.switchSettings3dBuildings.isChecked = enable3d
        isSyncing3dSwitch = false
    }

    private fun updatePreAlertMeters(meters: Int) {
        PreAlertPreferences.setPreAlertDistanceMeters(this, meters)
        syncPreAlertUi()
    }

    private fun syncPreAlertUi() {
        val selected = PreAlertPreferences.getPreAlertDistanceMeters(this)
        applySelectionPillStyle(binding.optPreAlertOff, selected == PreAlertPreferences.PRE_ALERT_OFF)
        applySelectionPillStyle(binding.optPreAlert300, selected == PreAlertPreferences.PRE_ALERT_300M)
        applySelectionPillStyle(binding.optPreAlert500, selected == PreAlertPreferences.PRE_ALERT_500M)
        applySelectionPillStyle(binding.optPreAlert1000, selected == PreAlertPreferences.PRE_ALERT_1000M)
    }

    private fun writeBackupToUri(uri: Uri, json: String) {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                        writer.write(json)
                    } ?: error("Cannot open output stream")
                }.isSuccess
            }
            if (success) {
                val count = viewModel.uiState.value.alarms.size
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.backup_export_success, count),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.backup_import_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun readAndRestoreBackupFromUri(uri: Uri) {
        lifecycleScope.launch {
            val readOutcome = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        val limit = com.example.util.BackupManager.MAX_BACKUP_JSON_CHARS
                        val buf = CharArray(limit + 1)
                        var total = 0
                        while (total <= limit) {
                            val read = reader.read(buf, total, buf.size - total)
                            if (read == -1) break
                            total += read
                        }
                        if (total > limit) {
                            null to true
                        } else {
                            String(buf, 0, total) to false
                        }
                    } ?: (null to false)
                }.getOrElse { null to false }
            }
            val (rawJson, tooLarge) = readOutcome
            if (tooLarge) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.backup_file_too_large,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            if (rawJson.isNullOrBlank()) {
                Toast.makeText(
                    this@SettingsActivity,
                    R.string.backup_import_failed,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            viewModel.importBackupJson(rawJson) { result ->
                result.onSuccess { (count, restoredPreAlert) ->
                    if (restoredPreAlert != null) {
                        PreAlertPreferences.setPreAlertDistanceMeters(
                            this@SettingsActivity,
                            restoredPreAlert
                        )
                        syncPreAlertUi()
                    }
                    syncTrackingServiceIfPossible()
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.backup_import_success, count),
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.backup_import_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun applySelectionPillStyle(view: TextView, selected: Boolean) {
        if (selected) {
            view.setBackgroundResource(R.drawable.bg_luxury_glass_pill_active)
            view.setTextColor(ContextCompat.getColor(this, R.color.accent_emerald))
        } else {
            view.setBackgroundResource(R.drawable.bg_luxury_glass_pill)
            view.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    private fun refreshPermissionsUi() {
        val status = PermissionHelper.checkStatus(this)
        val missingCount = listOf(
            status.hasFineLocation,
            status.hasBackgroundLocation,
            status.hasNotifications,
            status.hasFullScreenIntent,
            status.isBatteryUnrestricted
        ).count { !it }

        if (missingCount == 0) {
            binding.tvSettingsPermSummaryBadge.setText(R.string.settings_perm_all_ready)
            binding.tvSettingsPermSummaryBadge.setBackgroundResource(R.drawable.bg_badge_emerald)
            binding.tvSettingsPermSummaryBadge.setTextColor(
                ContextCompat.getColor(this, R.color.accent_emerald)
            )
        } else {
            binding.tvSettingsPermSummaryBadge.text =
                getString(R.string.settings_perm_needed, missingCount)
            binding.tvSettingsPermSummaryBadge.setBackgroundResource(R.drawable.bg_badge_cyan)
            binding.tvSettingsPermSummaryBadge.setTextColor(
                ContextCompat.getColor(this, R.color.accent_cyan_glow)
            )
        }

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

    private fun renderUpdateState(update: UpdateUiState) {
        val info = update.availableUpdate
        if (info == null) {
            binding.cardSettingsUpdateAvailable.visibility = View.GONE
            return
        }

        binding.cardSettingsUpdateAvailable.visibility = View.VISIBLE
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
            binding.tvUpdateProgress.visibility =
                if (update.errorMessage != null) View.VISIBLE else View.GONE
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
        if (PermissionHelper.hasTrackingLocation(this) && viewModel.uiState.value.activeCount > 0) {
            PinWakeService.startOrSync(this)
        }
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
                        .setDuration(200L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
            }
            false
        }
    }

    companion object {
        const val EXTRA_SCROLL_TO_UPDATES = "extra_scroll_to_updates"
    }
}
