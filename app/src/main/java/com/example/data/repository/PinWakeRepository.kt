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

    suspend fun getAllAlarmsOnce(): List<GeoAlarmEntity> = geoAlarmDao.getAllAlarmsOnce()

    suspend fun getActiveAlarmsOnce(): List<GeoAlarmEntity> = geoAlarmDao.getActiveAlarmsOnce()

    suspend fun getAlarmById(id: Long): GeoAlarmEntity? = geoAlarmDao.getAlarmById(id)

    suspend fun getFavoriteByCategory(category: FavoriteCategory): GeoAlarmEntity? =
        geoAlarmDao.getFavoriteByCategory(category)

    suspend fun saveAlarm(alarm: GeoAlarmEntity): Long {
        val now = System.currentTimeMillis()
        val sanitizedTitle = NominatimPlaceDto.stripUnsupportedGlyphs(alarm.title)
            .ifBlank { alarm.title.trim() }
        val sanitizedAddress = NominatimPlaceDto.stripUnsupportedGlyphs(alarm.address)
            .ifBlank { sanitizedTitle }
        val cleanAlarm = alarm.copy(title = sanitizedTitle, address = sanitizedAddress)
        return if (cleanAlarm.id == 0L) {
            geoAlarmDao.insertAlarm(cleanAlarm.copy(createdAt = now, updatedAt = now))
        } else {
            geoAlarmDao.updateAlarm(cleanAlarm.copy(updatedAt = now))
            cleanAlarm.id
        }
    }

    suspend fun saveQuickAssignedLocation(
        category: FavoriteCategory,
        title: String,
        address: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        isActive: Boolean,
        ringtoneUri: String? = null,
        ringtoneTitle: String? = null
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
        val cleanTitle = NominatimPlaceDto.stripUnsupportedGlyphs(title).ifBlank { title.trim() }
        val cleanAddress = NominatimPlaceDto.stripUnsupportedGlyphs(address).ifBlank { cleanTitle }
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
                ringtoneUri = ringtoneUri ?: existing.ringtoneUri,
                ringtoneTitle = ringtoneTitle ?: existing.ringtoneTitle,
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
                    ringtoneUri = ringtoneUri,
                    ringtoneTitle = ringtoneTitle,
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
            alarm.radiusMeters > 200 ->
                (alarm.radiusMeters - 300).coerceAtLeast(200)
            else -> 200
        }
        // Only grant a short cooldown if user is already at the exact inner core (<= 220m) or radius didn't shrink below distance
        val stillInsideNewRadius = currentDistanceMeters != null && currentDistanceMeters <= shrunkRadius
        val snoozeCooldownMs = if (stillInsideNewRadius || shrunkRadius >= alarm.radiusMeters) {
            System.currentTimeMillis() + 180_000L
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
        tripHistoryDao.insertTripAndPrune(
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

    suspend fun replayTrip(trip: TripHistoryEntity): GeoAlarmEntity {
        val now = System.currentTimeMillis()
        val existing = if (trip.alarmId > 0L) geoAlarmDao.getAlarmById(trip.alarmId) else null
        return if (existing != null) {
            val reactivated = existing.copy(
                isActive = true,
                lastDistanceMeters = null,
                snoozedUntilMs = 0L,
                updatedAt = now
            )
            geoAlarmDao.updateAlarm(reactivated)
            reactivated
        } else {
            val cleanTitle = NominatimPlaceDto.stripUnsupportedGlyphs(trip.alarmTitle)
                .ifBlank { trip.alarmTitle.trim() }
            val cleanAddress = NominatimPlaceDto.stripUnsupportedGlyphs(trip.address)
                .ifBlank { cleanTitle }
            val created = GeoAlarmEntity(
                title = cleanTitle.take(120),
                address = cleanAddress.take(240),
                latitude = trip.latitude,
                longitude = trip.longitude,
                radiusMeters = trip.radiusMeters.coerceIn(200, 5000),
                isActive = true,
                isFavorite = false,
                favoriteCategory = FavoriteCategory.NONE,
                vibrateEnabled = true,
                lastDistanceMeters = null,
                snoozedUntilMs = 0L,
                createdAt = now,
                updatedAt = now
            )
            val newId = geoAlarmDao.insertAlarm(created)
            created.copy(id = newId)
        }
    }

    suspend fun importBackupAlarms(imported: List<GeoAlarmEntity>): Int {
        if (imported.isEmpty()) return 0
        val existingList = geoAlarmDao.getAllAlarmsOnce().toMutableList()
        val toUpdate = mutableListOf<GeoAlarmEntity>()
        val toInsert = mutableListOf<GeoAlarmEntity>()
        val now = System.currentTimeMillis()

        for (item in imported.take(500)) {
            val cleanTitle = NominatimPlaceDto.stripUnsupportedGlyphs(item.title)
                .ifBlank { item.title.trim() }
                .take(120)
            if (cleanTitle.isBlank()) continue
            val cleanAddress = NominatimPlaceDto.stripUnsupportedGlyphs(item.address)
                .ifBlank { cleanTitle }
                .take(240)
            val isCanonical = item.favoriteCategory == FavoriteCategory.HOME ||
                item.favoriteCategory == FavoriteCategory.WORK ||
                item.favoriteCategory == FavoriteCategory.STATION

            val matchByCategory = if (isCanonical && item.isFavorite) {
                existingList.firstOrNull { it.isFavorite && it.favoriteCategory == item.favoriteCategory }
            } else {
                null
            }
            val matchByLocation = matchByCategory ?: existingList.firstOrNull { existing ->
                kotlin.math.abs(existing.latitude - item.latitude) < 0.0001 &&
                    kotlin.math.abs(existing.longitude - item.longitude) < 0.0001 &&
                    existing.title.equals(cleanTitle, ignoreCase = true)
            }

            if (matchByLocation != null) {
                val updated = matchByLocation.copy(
                    title = cleanTitle,
                    address = cleanAddress,
                    latitude = item.latitude,
                    longitude = item.longitude,
                    radiusMeters = item.radiusMeters.coerceIn(200, 5000),
                    isActive = item.isActive,
                    isFavorite = item.isFavorite || matchByLocation.isFavorite,
                    favoriteCategory = if (item.favoriteCategory != FavoriteCategory.NONE) {
                        item.favoriteCategory
                    } else {
                        matchByLocation.favoriteCategory
                    },
                    vibrateEnabled = item.vibrateEnabled,
                    ringtoneUri = item.ringtoneUri ?: matchByLocation.ringtoneUri,
                    ringtoneTitle = item.ringtoneTitle ?: matchByLocation.ringtoneTitle,
                    lastDistanceMeters = null,
                    snoozedUntilMs = 0L,
                    updatedAt = now
                )
                toUpdate.add(updated)
                val idx = existingList.indexOfFirst { it.id == matchByLocation.id }
                if (idx >= 0) existingList[idx] = updated
            } else {
                val toInsertItem = item.copy(
                    id = 0L,
                    title = cleanTitle,
                    address = cleanAddress,
                    radiusMeters = item.radiusMeters.coerceIn(200, 5000),
                    lastDistanceMeters = null,
                    snoozedUntilMs = 0L,
                    updatedAt = now
                )
                toInsert.add(toInsertItem)
                existingList.add(toInsertItem)
            }
        }

        if (toUpdate.isNotEmpty() || toInsert.isNotEmpty()) {
            geoAlarmDao.applyBatchImport(toUpdate, toInsert)
        }
        return toUpdate.size + toInsert.size
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
