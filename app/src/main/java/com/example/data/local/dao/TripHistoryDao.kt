package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.local.entity.TripHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TripHistoryDao {

    @Query("SELECT * FROM trip_history ORDER BY triggeredAt DESC LIMIT 20")
    fun observeRecentTrips(): Flow<List<TripHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripHistoryEntity): Long

    @Query(
        "DELETE FROM trip_history WHERE id NOT IN " +
            "(SELECT id FROM trip_history ORDER BY triggeredAt DESC LIMIT :keepCount)"
    )
    suspend fun pruneOldTrips(keepCount: Int = 50)

    @Transaction
    suspend fun insertTripAndPrune(trip: TripHistoryEntity, keepCount: Int = 50): Long {
        val insertedId = insertTrip(trip)
        pruneOldTrips(keepCount)
        return insertedId
    }

    @Query("DELETE FROM trip_history")
    suspend fun clearHistory()
}
