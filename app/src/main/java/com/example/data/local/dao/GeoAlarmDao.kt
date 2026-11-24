package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GeoAlarmDao {

    @Query("SELECT * FROM geo_alarms ORDER BY isActive DESC, updatedAt DESC")
    fun observeAllAlarms(): Flow<List<GeoAlarmEntity>>

    @Query("SELECT * FROM geo_alarms WHERE isActive = 1 ORDER BY updatedAt DESC")
    fun observeActiveAlarms(): Flow<List<GeoAlarmEntity>>

    @Query("SELECT * FROM geo_alarms WHERE isFavorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<GeoAlarmEntity>>

    @Query("SELECT * FROM geo_alarms ORDER BY isActive DESC, updatedAt DESC")
    suspend fun getAllAlarmsOnce(): List<GeoAlarmEntity>

    @Query("SELECT * FROM geo_alarms WHERE isActive = 1")
    suspend fun getActiveAlarmsOnce(): List<GeoAlarmEntity>

    @Query("SELECT * FROM geo_alarms WHERE id = :id LIMIT 1")
    suspend fun getAlarmById(id: Long): GeoAlarmEntity?

    @Query("SELECT * FROM geo_alarms WHERE favoriteCategory = :category AND isFavorite = 1 LIMIT 1")
    suspend fun getFavoriteByCategory(category: FavoriteCategory): GeoAlarmEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: GeoAlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: GeoAlarmEntity)

    @Query("UPDATE geo_alarms SET isActive = :active, lastDistanceMeters = :lastDistance, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setAlarmActive(
        id: Long,
        active: Boolean,
        lastDistance: Float? = null,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE geo_alarms SET radiusMeters = :radiusMeters, isActive = 1, snoozedUntilMs = :snoozedUntilMs, updatedAt = :updatedAt WHERE id = :id")
    suspend fun applySnooze(
        id: Long,
        radiusMeters: Int,
        snoozedUntilMs: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE geo_alarms SET lastDistanceMeters = :distanceMeters WHERE id = :id")
    suspend fun updateLastDistance(
        id: Long,
        distanceMeters: Float?
    )

    @Query("UPDATE geo_alarms SET isActive = 0, lastDistanceMeters = NULL, updatedAt = :updatedAt WHERE isActive = 1")
    suspend fun deactivateAll(updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM geo_alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)
}
