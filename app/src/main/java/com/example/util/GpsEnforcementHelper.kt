package com.example.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.example.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object GpsEnforcementHelper {

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return runCatching { LocationManagerCompat.isLocationEnabled(lm) }.getOrDefault(false)
    }

    fun isGpsProviderEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        if (!isLocationEnabled(context)) return false
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }

    fun isOffline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(network) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun buildLocationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    }

    /**
     * Queries all hardware and cached providers (prioritizing offline hardware GPS_PROVIDER).
     */
    @SuppressLint("MissingPermission")
    fun getBestLocation(context: Context): Location? {
        if (!PermissionHelper.hasTrackingLocation(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null

        val gps = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val net = runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val passive = runCatching { lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER) }.getOrNull()

        val candidates = listOfNotNull(gps, net, passive)
        if (candidates.isEmpty()) return null

        // Prefer freshest fix; break close ties in favor of direct satellite GPS
        val newest = candidates.maxByOrNull { it.time }!!
        return if (gps != null && newest.time - gps.time <= 15_000L) {
            gps
        } else {
            newest
        }
    }

    fun showEnableGpsDialog(
        context: Context,
        mandatoryForActiveTracking: Boolean = false,
        onOpenSettings: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null
    ) {
        val titleRes = if (mandatoryForActiveTracking) {
            R.string.gps_mandatory_dialog_title
        } else {
            R.string.gps_enable_dialog_title
        }
        val msgRes = if (mandatoryForActiveTracking) {
            R.string.gps_mandatory_dialog_message
        } else {
            R.string.gps_enable_dialog_message
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(msgRes)
            .setCancelable(!mandatoryForActiveTracking)
            .setPositiveButton(R.string.gps_turn_on_now) { _, _ ->
                onOpenSettings?.invoke()
                runCatching {
                    context.startActivity(buildLocationSettingsIntent())
                }
            }
            .apply {
                if (!mandatoryForActiveTracking) {
                    setNegativeButton(android.R.string.cancel) { _, _ ->
                        onDismissed?.invoke()
                    }
                } else {
                    setNeutralButton(android.R.string.cancel) { _, _ ->
                        onDismissed?.invoke()
                    }
                }
            }
            .show()
    }
}
