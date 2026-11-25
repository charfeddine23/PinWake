package com.example.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PollingTier(val intervalMs: Long, val minDistanceMeters: Float) {
    STANDBY(intervalMs = 0L, minDistanceMeters = 0f),
    ECO_30S(intervalMs = 30_000L, minDistanceMeters = 50f),
    BALANCED_10S(intervalMs = 10_000L, minDistanceMeters = 15f),
    PRECISION_2S(intervalMs = 1_200L, minDistanceMeters = 0f);

    companion object {
        fun forDistance(distanceMeters: Float): PollingTier {
            return when {
                distanceMeters > 5_000f -> ECO_30S
                distanceMeters > 1_000f -> BALANCED_10S
                else -> PRECISION_2S
            }
        }
    }
}

data class TrackingTelemetry(
    val isTracking: Boolean = false,
    val pollingTier: PollingTier = PollingTier.STANDBY,
    val activeAlarmCount: Int = 0,
    val closestAlarmId: Long? = null,
    val closestAlarmTitle: String? = null,
    val closestDistanceMeters: Float? = null,
    val lastFixAccuracyMeters: Float? = null,
    val lastLatitude: Double? = null,
    val lastLongitude: Double? = null,
    val lastUpdatedAtMs: Long = 0L,
    val isGpsEnabled: Boolean = true,
    val isGpsDisabledWarning: Boolean = false,
    val isOfflineMode: Boolean = false,
    val locationProvider: String? = null
)

class TrackingStateManager {
    private val _telemetry = MutableStateFlow(TrackingTelemetry())
    val telemetry: StateFlow<TrackingTelemetry> = _telemetry.asStateFlow()

    fun update(transform: (TrackingTelemetry) -> TrackingTelemetry) {
        _telemetry.value = transform(_telemetry.value)
    }

    fun resetToStandby() {
        _telemetry.value = TrackingTelemetry(
            isTracking = false,
            pollingTier = PollingTier.STANDBY
        )
    }
}
