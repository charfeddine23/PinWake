package com.example.util

import android.content.Context
import com.example.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class NavAssistMetrics(
    val distanceMeters: Float,
    val bearingDegrees: Float,
    val cardinalDirection: String,
    val etaSeconds: Long?,
    val isLiveSpeedEta: Boolean,
    val speedMetersPerSec: Float?
)

object NavigationAssistCalculator {

    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val MIN_MOVING_SPEED_MPS = 0.8f
    private const val MAX_REALISTIC_SPEED_MPS = 55.0f
    private const val FALLBACK_URBAN_SPEED_MPS = 8.33f // ~30 km/h urban baseline when stationary

    fun computeMetrics(
        currentLat: Double,
        currentLon: Double,
        currentSpeedMps: Float?,
        targetLat: Double,
        targetLon: Double
    ): NavAssistMetrics {
        val (distance, bearing) = computeDistanceAndBearing(
            startLat = currentLat,
            startLon = currentLon,
            endLat = targetLat,
            endLon = targetLon
        )
        val cardinal = cardinalFromBearing(bearing)
        val validLiveSpeed = currentSpeedMps
            ?.takeIf { !it.isNaN() && it >= MIN_MOVING_SPEED_MPS }
            ?.coerceAtMost(MAX_REALISTIC_SPEED_MPS)

        val (etaSec, isLive) = when {
            distance <= 15f -> 0L to (validLiveSpeed != null)
            validLiveSpeed != null -> (distance / validLiveSpeed).toLong().coerceAtLeast(1L) to true
            else -> (distance / FALLBACK_URBAN_SPEED_MPS).toLong().coerceAtLeast(1L) to false
        }

        return NavAssistMetrics(
            distanceMeters = distance,
            bearingDegrees = bearing,
            cardinalDirection = cardinal,
            etaSeconds = etaSec,
            isLiveSpeedEta = isLive,
            speedMetersPerSec = validLiveSpeed
        )
    }

    fun computeDistanceAndBearing(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ): Pair<Float, Float> {
        val lat1 = Math.toRadians(startLat)
        val lon1 = Math.toRadians(startLon)
        val lat2 = Math.toRadians(endLat)
        val lon2 = Math.toRadians(endLon)

        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1) * cos(lat2) * sin(dLon / 2.0) * sin(dLon / 2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        val distanceMeters = (EARTH_RADIUS_METERS * c).toFloat()

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val rawDeg = Math.toDegrees(atan2(y, x)).toFloat()
        val normalizedBearing = ((rawDeg % 360f) + 360f) % 360f

        return distanceMeters to normalizedBearing
    }

    fun cardinalFromBearing(bearingDegrees: Float): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val normalized = ((bearingDegrees % 360f) + 360f) % 360f
        val idx = (((normalized + 22.5f) / 45f).toInt()) % dirs.size
        return dirs[idx]
    }

    fun formatEtaCompact(
        context: Context,
        etaSeconds: Long?,
        isLiveSpeed: Boolean
    ): String {
        if (etaSeconds == null) {
            return context.getString(R.string.nav_assist_eta_unknown)
        }
        if (etaSeconds <= 30L) {
            return context.getString(R.string.nav_assist_eta_now)
        }
        val totalMinutes = ((etaSeconds + 29L) / 60L).toInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (hours > 0) {
            if (isLiveSpeed) {
                context.getString(R.string.nav_assist_eta_hr_min, hours, mins)
            } else {
                context.getString(R.string.nav_assist_eta_est_hr_min, hours, mins)
            }
        } else {
            if (isLiveSpeed) {
                context.getString(R.string.nav_assist_eta_min, totalMinutes)
            } else {
                context.getString(R.string.nav_assist_eta_est_min, totalMinutes)
            }
        }
    }

    fun formatDistanceAndBearing(
        context: Context,
        distanceMeters: Float,
        bearingDegrees: Float
    ): String {
        val distStr = DistanceFormatter.formatCompactDistance(distanceMeters)
        val cardinal = cardinalFromBearing(bearingDegrees)
        val roundedDeg = (bearingDegrees.roundToInt() % 360 + 360) % 360
        return context.getString(R.string.nav_assist_distance_bearing, distStr, cardinal, roundedDeg)
    }
}
