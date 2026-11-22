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
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val hasFineLocation: Boolean,
    val hasBackgroundLocation: Boolean,
    val hasNotifications: Boolean,
    val hasFullScreenIntent: Boolean,
    val isBatteryUnrestricted: Boolean
) {
    val allGranted: Boolean
        get() = hasFineLocation &&
            hasBackgroundLocation &&
            hasNotifications &&
            hasFullScreenIntent &&
            isBatteryUnrestricted
}

object PermissionHelper {

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

        val fullScreen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryUnrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

        return PermissionStatus(
            hasFineLocation = fine,
            hasBackgroundLocation = background,
            hasNotifications = notifications,
            hasFullScreenIntent = fullScreen,
            isBatteryUnrestricted = batteryUnrestricted
        )
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
}
