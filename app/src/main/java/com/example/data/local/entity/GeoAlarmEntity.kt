package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class FavoriteCategory {
    NONE,
    HOME,
    WORK,
    STATION,
    CUSTOM
}

@Entity(tableName = "geo_alarms")
data class GeoAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 500,
    val isActive: Boolean = false,
    val isFavorite: Boolean = false,
    val favoriteCategory: FavoriteCategory = FavoriteCategory.NONE,
    val vibrateEnabled: Boolean = true,
    val ringtoneUri: String? = null,
    val ringtoneTitle: String? = null,
    val lastDistanceMeters: Float? = null,
    val snoozedUntilMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
