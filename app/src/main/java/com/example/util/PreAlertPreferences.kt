package com.example.util

import android.content.Context

object PreAlertPreferences {

    private const val PREFS_NAME = "pin_wake_prefs"
    private const val KEY_PRE_ALERT_METERS = "pre_alert_distance_meters"
    private const val KEY_FORCE_GPS_KEEP_ON = "force_gps_keep_on"
    private const val KEY_FORCE_SILENT_OVERRIDE = "force_silent_override"
    private const val KEY_NAV_ROUTE_LINE_ENABLED = "nav_route_line_enabled"

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

    fun isForcedGpsKeepOnEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_GPS_KEEP_ON, true)
    }

    fun setForcedGpsKeepOnEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_GPS_KEEP_ON, enabled)
            .apply()
    }

    fun isForceSilentOverrideEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FORCE_SILENT_OVERRIDE, true)
    }

    fun setForceSilentOverrideEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FORCE_SILENT_OVERRIDE, enabled)
            .apply()
    }

    fun isNavRouteLineEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_NAV_ROUTE_LINE_ENABLED, false)
    }

    fun setNavRouteLineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NAV_ROUTE_LINE_ENABLED, enabled)
            .apply()
    }

    fun normalizeDistance(meters: Int): Int {
        return if (meters in SUPPORTED_DISTANCES) meters else DEFAULT_PRE_ALERT_METERS
    }
}
