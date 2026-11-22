package com.example.util

import android.content.Context
import com.example.R

object DistanceFormatter {
    fun formatDistanceBadge(context: Context, meters: Float?): String {
        if (meters == null) return context.getString(R.string.badge_waiting_gps)
        return if (meters >= 1000f) {
            context.getString(R.string.badge_distance_km, meters / 1000f)
        } else {
            context.getString(R.string.badge_distance_m, meters.toInt())
        }
    }

    fun formatCompactDistance(meters: Float): String {
        return if (meters >= 1000f) {
            String.format(java.util.Locale.US, "%.1f km", meters / 1000f)
        } else {
            String.format(java.util.Locale.US, "%d m", meters.toInt())
        }
    }

    fun formatRadiusBadge(context: Context, radiusMeters: Int): String {
        return if (radiusMeters >= 1000 && radiusMeters % 100 == 0) {
            context.getString(R.string.badge_radius_km, radiusMeters / 1000f)
        } else {
            context.getString(R.string.badge_radius_m, radiusMeters)
        }
    }

    fun formatRadiusSliderValue(context: Context, radiusMeters: Int): String {
        return if (radiusMeters >= 1000) {
            context.getString(R.string.map_radius_value_km, radiusMeters / 1000f)
        } else {
            context.getString(R.string.map_radius_value_m, radiusMeters)
        }
    }
}
