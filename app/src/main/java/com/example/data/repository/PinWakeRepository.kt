package com.example.data.repository

import com.example.data.local.dao.GeoAlarmDao
import com.example.data.local.dao.TripHistoryDao
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.local.entity.TripOutcome
import com.example.data.remote.NominatimApiService
import com.example.data.remote.dto.NominatimPlaceDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class PinWakeRepository(
    private val geoAlarmDao: GeoAlarmDao,
    private val tripHistoryDao: TripHistoryDao,
    private val nominatimApi: NominatimApiService,
    val trackingStateManager: TrackingStateManager
) {
    val allAlarms: Flow<List<GeoAlarmEntity>> = geoAlarmDao.observeAllAlarms()
    val activeAlarms: Flow<List<GeoAlarmEntity>> = geoAlarmDao.observeActiveAlarms()
    val favoriteAlarms: Flow<List<GeoAlarmEntity>> = geoAlarmDao.observeFavorites()
    val recentTrips: Flow<List<TripHistoryEntity>> = tripHistoryDao.observeRecentTrips()

    suspend fun getActiveAlarmsOnce(): List<GeoAlarmEntity> = geoAlarmDao.getActiveAlarmsOnce()

    suspend fun getAlarmById(id: Long): GeoAlarmEntity? = geoAlarmDao.getAlarmById(id)

    suspend fun getFavoriteByCategory(category: FavoriteCategory): GeoAlarmEntity? =
        geoAlarmDao.getFavoriteByCategory(category)

    suspend fun saveAlarm(alarm: GeoAlarmEntity): Long {
        val now = System.currentTimeMillis()
        return if (alarm.id == 0L) {
            geoAlarmDao.insertAlarm(alarm.copy(createdAt = now, updatedAt = now))
        } else {
            geoAlarmDao.updateAlarm(alarm.copy(updatedAt = now))
            alarm.id
        }
    }

    suspend fun saveQuickAssignedLocation(
        category: FavoriteCategory,
        title: String,
        address: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        isActive: Boolean
    ): Long {
        val now = System.currentTimeMillis()
        val isCanonicalSlot = category == FavoriteCategory.HOME ||
            category == FavoriteCategory.WORK ||
            category == FavoriteCategory.STATION
        val existing = if (isCanonicalSlot) {
            geoAlarmDao.getFavoriteByCategory(category)
        } else {
            null
        }
        val cleanTitle = title.trim()
        val cleanAddress = address.trim()
        return if (existing != null) {
            val updated = existing.copy(
                title = cleanTitle,
                address = cleanAddress,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters.coerceIn(200, 5000),
                isActive = isActive,
                isFavorite = true,
                favoriteCategory = category,
                lastDistanceMeters = null,
                snoozedUntilMs = 0L,
                updatedAt = now
            )
            geoAlarmDao.updateAlarm(updated)
            existing.id
        } else {
            geoAlarmDao.insertAlarm(
                GeoAlarmEntity(
                    title = cleanTitle,
                    address = cleanAddress,
                    latitude = latitude,
                    longitude = longitude,
                    radiusMeters = radiusMeters.coerceIn(200, 5000),
                    isActive = isActive,
                    isFavorite = category != FavoriteCategory.NONE,
                    favoriteCategory = category,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }
    }

    suspend fun setAlarmActive(id: Long, active: Boolean, lastDistanceMeters: Float? = null) {
        geoAlarmDao.setAlarmActive(
            id = id,
            active = active,
            lastDistance = if (active) lastDistanceMeters else null,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun applySnooze500m(alarm: GeoAlarmEntity, currentDistanceMeters: Float?) {
        val shrunkRadius = when {
            currentDistanceMeters != null && currentDistanceMeters > 700f ->
                (currentDistanceMeters - 500f).toInt().coerceIn(200, 4500)
            alarm.radiusMeters > 700 ->
                (alarm.radiusMeters - 500).coerceAtLeast(200)
            else -> alarm.radiusMeters
        }
        // Grant a 90-second cooldown when already inside tight <=700m zone so user isn't instantly re-rung
        val snoozeCooldownMs = if (alarm.radiusMeters <= 700 && (currentDistanceMeters == null || currentDistanceMeters <= 700f)) {
            System.currentTimeMillis() + 90_000L
        } else {
            0L
        }
        geoAlarmDao.applySnooze(
            id = alarm.id,
            radiusMeters = shrunkRadius,
            snoozedUntilMs = snoozeCooldownMs
        )
        recordTripOutcome(alarm, TripOutcome.SNOOZED)
    }

    suspend fun updateLastDistance(id: Long, distanceMeters: Float?) {
        geoAlarmDao.updateLastDistance(id, distanceMeters)
    }

    suspend fun deactivateAllAlarms() {
        geoAlarmDao.deactivateAll()
        trackingStateManager.resetToStandby()
    }

    suspend fun deleteAlarm(id: Long) {
        geoAlarmDao.deleteAlarmById(id)
    }

    suspend fun recordTripOutcome(alarm: GeoAlarmEntity, outcome: TripOutcome) {
        tripHistoryDao.insertTrip(
            TripHistoryEntity(
                alarmId = alarm.id,
                alarmTitle = alarm.title,
                address = alarm.address,
                latitude = alarm.latitude,
                longitude = alarm.longitude,
                radiusMeters = alarm.radiusMeters,
                outcome = outcome,
                triggeredAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearHistory() {
        tripHistoryDao.clearHistory()
    }

    suspend fun searchPlaces(query: String, languageCode: String): Result<List<NominatimPlaceDto>> =
        withContext(Dispatchers.IO) {
            runCatching {
                nominatimApi.searchPlaces(
                    query = query.trim(),
                    acceptLanguage = languageCode
                )
            }
        }

    suspend fun reverseGeocode(
        latitude: Double,
        longitude: Double,
        languageCode: String
    ): Result<NominatimPlaceDto> = withContext(Dispatchers.IO) {
        runCatching {
            nominatimApi.reverseGeocode(
                latitude = latitude,
                longitude = longitude,
                acceptLanguage = languageCode
            )
        }
    }
}
