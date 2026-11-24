package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.local.dao.GeoAlarmDao
import com.example.data.local.dao.TripHistoryDao
import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.local.entity.TripHistoryEntity
import com.example.data.local.entity.TripOutcome

class PinWakeConverters {
    @TypeConverter
    fun fromFavoriteCategory(value: FavoriteCategory): String = value.name

    @TypeConverter
    fun toFavoriteCategory(value: String): FavoriteCategory =
        runCatching { FavoriteCategory.valueOf(value) }.getOrDefault(FavoriteCategory.NONE)

    @TypeConverter
    fun fromTripOutcome(value: TripOutcome): String = value.name

    @TypeConverter
    fun toTripOutcome(value: String): TripOutcome =
        runCatching { TripOutcome.valueOf(value) }.getOrDefault(TripOutcome.ARRIVED)
}

@Database(
    entities = [GeoAlarmEntity::class, TripHistoryEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(PinWakeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun geoAlarmDao(): GeoAlarmDao
    abstract fun tripHistoryDao(): TripHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pin_wake_database.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
