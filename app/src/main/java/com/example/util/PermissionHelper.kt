package com.example.util

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

data class PermissionStatus(
    val hasFineLocation: Boolean,
    val hasBackgroundLocation: Boolean,
    val hasNotifications: Boolean,
    val hasFullScreenIntent: Boolean,
    val isBatteryUnrestricted: Boolean,
    val hasDndBypassAccess: Boolean
) {
    val allGranted: Boolean
        get() = hasFineLocation &&
            hasBackgroundLocation &&
            hasNotifications &&
            hasFullScreenIntent &&
            isBatteryUnrestricted &&
            hasDndBypassAccess
}

object PermissionHelper {

    private var promptedBgReadinessThisSession = false

    fun hasTrackingLocation(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun checkStatus(context: Context): PermissionStatus {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            notificationManager?.canUseFullScreenIntent() ?: true
        } else {
            true
        }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        val dndBypass = notificationManager?.isNotificationPolicyAccessGranted ?: false

        return PermissionStatus(
            hasFineLocation = fine,
            hasBackgroundLocation = background,
            hasNotifications = notifications,
            hasFullScreenIntent = fullScreen,
            isBatteryUnrestricted = batteryUnrestricted,
            hasDndBypassAccess = dndBypass
        )
    }

    fun needsMandatoryBackgroundReadiness(context: Context): Boolean {
        val status = checkStatus(context)
        return status.hasFineLocation && (!status.hasBackgroundLocation || !status.isBatteryUnrestricted)
    }

    fun promptMandatoryBackgroundReadinessIfNeeded(
        activity: AppCompatActivity,
        onComplete: (() -> Unit)? = null
    ) {
        if (activity.isDestroyed || activity.isFinishing) {
            onComplete?.invoke()
            return
        }
        val status = checkStatus(activity)
        if (!status.hasFineLocation ||
            (status.hasBackgroundLocation && status.isBatteryUnrestricted) ||
            promptedBgReadinessThisSession
        ) {
            onComplete?.invoke()
            return
        }
        promptedBgReadinessThisSession = true

        val messageRes = if (!status.hasBackgroundLocation) {
            R.string.bg_readiness_dialog_message_location
        } else {
            R.string.bg_readiness_dialog_message_battery
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.bg_readiness_dialog_title)
            .setMessage(messageRes)
            .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                val targetIntent = if (!status.hasBackgroundLocation) {
                    buildAppSettingsIntent(activity)
                } else {
                    buildBatteryOptimizationIntent(activity)
                }
                runCatching { activity.startActivity(targetIntent) }
                onComplete?.invoke()
            }
            .setNegativeButton(R.string.update_btn_maybe_later) { _, _ ->
                onComplete?.invoke()
            }
            .setOnCancelListener {
                onComplete?.invoke()
            }
            .show()
    }

    fun buildAppSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun buildFullScreenIntentSettings(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        } else {
            buildAppSettingsIntent(context)
        }
    }

    fun buildBatteryOptimizationIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }

    fun buildNotificationPolicySettingsIntent(): Intent {
        return Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }
}
