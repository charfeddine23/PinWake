package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.TripHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripHistoryDao {

    @Query("SELECT * FROM trip_history ORDER BY triggeredAt DESC LIMIT 20")
    fun observeRecentTrips(): Flow<List<TripHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripHistoryEntity): Long

    @Query("DELETE FROM trip_history")
    suspend fun clearHistory()
}
