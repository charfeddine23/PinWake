package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.PollingTier
import com.example.util.ApkUpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Pin Wake", appName)
    }

    @Test
    fun `adaptive polling tier scales with distance`() {
        assertEquals(PollingTier.ECO_30S, PollingTier.forDistance(6500f))
        assertEquals(PollingTier.BALANCED_10S, PollingTier.forDistance(2500f))
        assertEquals(PollingTier.PRECISION_2S, PollingTier.forDistance(800f))
    }

    @Test
    fun `semantic version comparison detects newer github release`() {
        assertTrue(ApkUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(ApkUpdateManager.isNewerVersion("v1.1.0", "1.0.9"))
        assertFalse(ApkUpdateManager.isNewerVersion("v1.0.1", "1.0.1"))
        assertFalse(ApkUpdateManager.isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun `stripUnsupportedGlyphs removes Tifinagh blocks cleanly`() {
        val raw = "Alger / ⴷⵣⴰⵢⵔ Centre, الجزائر"
        val cleaned = com.example.data.remote.dto.NominatimPlaceDto.stripUnsupportedGlyphs(raw)
        assertFalse(cleaned.any { it in '\u2D30'..'\u2D7F' })
        assertTrue(cleaned.contains("Alger"))
        assertTrue(cleaned.contains("الجزائر"))
    }

    @Test
    fun `openFreeMapStyles defaults to google dark and persists 3d buildings toggle`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            com.example.util.OpenFreeMapStyle.GOOGLE_DARK,
            com.example.util.OpenFreeMapStyles.getSavedStyle(context)
        )
        assertTrue(com.example.util.OpenFreeMapStyles.is3dBuildingsEnabled(context))

        com.example.util.OpenFreeMapStyles.saveStyle(
            context,
            com.example.util.OpenFreeMapStyle.GOOGLE_LIGHT
        )
        com.example.util.OpenFreeMapStyles.set3dBuildingsEnabled(context, false)

        assertEquals(
            com.example.util.OpenFreeMapStyle.GOOGLE_LIGHT,
            com.example.util.OpenFreeMapStyles.getSavedStyle(context)
        )
        assertFalse(com.example.util.OpenFreeMapStyles.is3dBuildingsEnabled(context))
    }

    @Test
    fun `saved home and work favorites persist even when inactive`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val dao = db.geoAlarmDao()
            val homeId = dao.insertAlarm(
                com.example.data.local.entity.GeoAlarmEntity(
                    title = "Home",
                    address = "Algiers",
                    latitude = 36.7538,
                    longitude = 3.0588,
                    radiusMeters = 500,
                    isActive = false,
                    isFavorite = true,
                    favoriteCategory = com.example.data.local.entity.FavoriteCategory.HOME
                )
            )
            val workId = dao.insertAlarm(
                com.example.data.local.entity.GeoAlarmEntity(
                    title = "Work",
                    address = "Hydra",
                    latitude = 36.7450,
                    longitude = 3.0350,
                    radiusMeters = 400,
                    isActive = false,
                    isFavorite = true,
                    favoriteCategory = com.example.data.local.entity.FavoriteCategory.WORK
                )
            )
            assertTrue(homeId > 0L && workId > 0L)
            val savedHome = dao.getFavoriteByCategory(com.example.data.local.entity.FavoriteCategory.HOME)
            val savedWork = dao.getFavoriteByCategory(com.example.data.local.entity.FavoriteCategory.WORK)
            assertEquals(36.7538, savedHome?.latitude ?: 0.0, 0.0001)
            assertEquals(36.7450, savedWork?.latitude ?: 0.0, 0.0001)
        } finally {
            db.close()
        }
    }

    @Test
    fun `saveQuickAssignedLocation replaces canonical Home without duplicates and supports custom places`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val repo = com.example.data.repository.PinWakeRepository(
                geoAlarmDao = db.geoAlarmDao(),
                tripHistoryDao = db.tripHistoryDao(),
                nominatimApi = com.example.data.remote.NominatimApiService.create(),
                trackingStateManager = com.example.data.repository.TrackingStateManager()
            )

            val firstHomeId = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.HOME,
                title = "Home",
                address = "Old Address",
                latitude = 36.7500,
                longitude = 3.0500,
                radiusMeters = 500,
                isActive = false
            )
            val replacedHomeId = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.HOME,
                title = "New Home",
                address = "Updated Address",
                latitude = 36.7600,
                longitude = 3.0600,
                radiusMeters = 650,
                isActive = true
            )
            val customId = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.CUSTOM,
                title = "Gym",
                address = "City Center",
                latitude = 36.7700,
                longitude = 3.0700,
                radiusMeters = 350,
                isActive = false
            )

            assertEquals(firstHomeId, replacedHomeId)
            assertTrue(customId != replacedHomeId)

            val home = repo.getFavoriteByCategory(com.example.data.local.entity.FavoriteCategory.HOME)
            assertEquals("New Home", home?.title)
            assertEquals(36.7600, home?.latitude ?: 0.0, 0.0001)
            assertEquals(650, home?.radiusMeters)
            assertTrue(home?.isActive == true)

            val custom = repo.getAlarmById(customId)
            assertEquals("Gym", custom?.title)
            assertTrue(custom?.isFavorite == true)
            assertEquals(
                com.example.data.local.entity.FavoriteCategory.CUSTOM,
                custom?.favoriteCategory
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `languageManager setLanguage persists and toggles between en and ar`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.example.util.LanguageManager.setLanguage(context, "ar")
        assertEquals("ar", com.example.util.LanguageManager.getCurrentLanguageCode(context))

        com.example.util.LanguageManager.setLanguage(context, "en")
        assertEquals("en", com.example.util.LanguageManager.getCurrentLanguageCode(context))
    }

    @Test
    fun `saveQuickAssignedLocation supports standard wake pin category NONE`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val repo = com.example.data.repository.PinWakeRepository(
                geoAlarmDao = db.geoAlarmDao(),
                tripHistoryDao = db.tripHistoryDao(),
                nominatimApi = com.example.data.remote.NominatimApiService.create(),
                trackingStateManager = com.example.data.repository.TrackingStateManager()
            )
            val pinId = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.NONE,
                title = "Central Square",
                address = "Downtown",
                latitude = 36.7525,
                longitude = 3.0420,
                radiusMeters = 450,
                isActive = true
            )
            val saved = repo.getAlarmById(pinId)
            assertEquals(
                com.example.data.local.entity.FavoriteCategory.NONE,
                saved?.favoriteCategory
            )
            assertFalse(saved?.isFavorite ?: true)
            assertTrue(saved?.isActive == true)
        } finally {
            db.close()
        }
    }
}
