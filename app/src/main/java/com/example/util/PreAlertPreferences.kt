package com.example.util

import android.content.Context

object PreAlertPreferences {

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_PRE_ALERT_METERS = "pre_alert_distance_meters"

    const val PRE_ALERT_OFF = 0
    const val PRE_ALERT_300M = 300
    const val PRE_ALERT_500M = 500
    const val PRE_ALERT_1000M = 1000
    const val DEFAULT_PRE_ALERT_METERS = PRE_ALERT_300M

    val SUPPORTED_DISTANCES = setOf(
        PRE_ALERT_OFF,
        PRE_ALERT_300M,
        PRE_ALERT_500M,
        PRE_ALERT_1000M
    )

    fun getPreAlertDistanceMeters(context: Context): Int {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PRE_ALERT_METERS, DEFAULT_PRE_ALERT_METERS)
        return normalizeDistance(raw)
    }

    fun setPreAlertDistanceMeters(context: Context, meters: Int) {
        val normalized = normalizeDistance(meters)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PRE_ALERT_METERS, normalized)
            .apply()
    }

    fun normalizeDistance(meters: Int): Int {
        return if (meters in SUPPORTED_DISTANCES) meters else DEFAULT_PRE_ALERT_METERS
    }
}
