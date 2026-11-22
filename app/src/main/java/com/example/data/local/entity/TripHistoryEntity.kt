package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TripOutcome {
    ARRIVED,
    SNOOZED,
    CANCELLED
}

@Entity(tableName = "trip_history")
data class TripHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val alarmId: Long,
    val alarmTitle: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val outcome: TripOutcome,
    val triggeredAt: Long = System.currentTimeMillis()
)
