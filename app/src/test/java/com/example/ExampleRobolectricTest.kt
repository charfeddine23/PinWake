package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.PollingTier
import com.example.util.ApkUpdateManager
import kotlinx.coroutines.flow.first
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

    @Test
    fun `mapLinkParser extracts coordinates and labels across geo and map urls`() {
        val geo = com.example.util.MapLinkParser.parse("geo:0,0?q=36.7538,3.0588(Central+Station)")
        assertEquals(36.7538, geo?.latitude ?: 0.0, 0.0001)
        assertEquals(3.0588, geo?.longitude ?: 0.0, 0.0001)
        assertEquals("Central Station", geo?.label)

        val gmaps = com.example.util.MapLinkParser.parse(
            "https://www.google.com/maps/place/Algiers+Port/@36.7650,3.0620,16z/data=!3d36.7642!4d3.0631"
        )
        assertEquals(36.7642, gmaps?.latitude ?: 0.0, 0.0001)
        assertEquals(3.0631, gmaps?.longitude ?: 0.0, 0.0001)
        assertEquals("Algiers Port", gmaps?.label)

        val osm = com.example.util.MapLinkParser.parse(
            "https://www.openstreetmap.org/?mlat=48.8566&mlon=2.3522#map=16/48.8566/2.3522"
        )
        assertEquals(48.8566, osm?.latitude ?: 0.0, 0.0001)
        assertEquals(2.3522, osm?.longitude ?: 0.0, 0.0001)

        val raw = com.example.util.MapLinkParser.parse("Meet at 36.7400, 3.0300 please")
        assertEquals(36.7400, raw?.latitude ?: 0.0, 0.0001)
        assertEquals(3.0300, raw?.longitude ?: 0.0, 0.0001)

        val tifinaghLabel = com.example.util.MapLinkParser.parse("geo:0,0?q=36.75,3.05(Alger+%E2%B4%B7%E2%B5%A3)")
        assertFalse(tifinaghLabel?.label?.any { it in '\u2D30'..'\u2D7F' } == true)
    }

    @Test
    fun `preAlertPreferences persists and normalizes supported early warning distances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(
            com.example.util.PreAlertPreferences.PRE_ALERT_300M,
            com.example.util.PreAlertPreferences.getPreAlertDistanceMeters(context)
        )

        com.example.util.PreAlertPreferences.setPreAlertDistanceMeters(context, 1000)
        assertEquals(1000, com.example.util.PreAlertPreferences.getPreAlertDistanceMeters(context))

        com.example.util.PreAlertPreferences.setPreAlertDistanceMeters(context, 0)
        assertEquals(0, com.example.util.PreAlertPreferences.getPreAlertDistanceMeters(context))

        com.example.util.PreAlertPreferences.setPreAlertDistanceMeters(context, 999)
        assertEquals(
            com.example.util.PreAlertPreferences.DEFAULT_PRE_ALERT_METERS,
            com.example.util.PreAlertPreferences.getPreAlertDistanceMeters(context)
        )
    }

    @Test
    fun `backupManager exports and restores alarms and favorites without duplicates`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sampleAlarms = listOf(
            com.example.data.local.entity.GeoAlarmEntity(
                title = "Home",
                address = "Algiers / ⴷⵣⴰⵢⵔ",
                latitude = 36.7538,
                longitude = 3.0588,
                radiusMeters = 500,
                isActive = true,
                isFavorite = true,
                favoriteCategory = com.example.data.local.entity.FavoriteCategory.HOME
            ),
            com.example.data.local.entity.GeoAlarmEntity(
                title = "Train Hub",
                address = "Central",
                latitude = 36.7600,
                longitude = 3.0650,
                radiusMeters = 350,
                isActive = false,
                isFavorite = true,
                favoriteCategory = com.example.data.local.entity.FavoriteCategory.STATION
            )
        )

        val json = com.example.util.BackupManager.serializeToJson(sampleAlarms, preAlertMeters = 500)
        val parsed = com.example.util.BackupManager.parseFromJson(json).getOrThrow()
        assertEquals(500, parsed.preAlertMeters)
        assertEquals(2, parsed.alarms.size)
        assertFalse(parsed.alarms[0].address.any { it in '\u2D30'..'\u2D7F' })

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
            assertEquals(2, repo.importBackupAlarms(parsed.alarms))
            assertEquals(2, repo.importBackupAlarms(parsed.alarms))
            assertEquals(2, repo.getAllAlarmsOnce().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `replayTrip reactivates existing alarm or recreates snapshot if deleted`() = kotlinx.coroutines.runBlocking {
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
            val tripForDeletedAlarm = com.example.data.local.entity.TripHistoryEntity(
                alarmId = 999L,
                alarmTitle = "Old Station",
                address = "Main Concourse",
                latitude = 36.7580,
                longitude = 3.0590,
                radiusMeters = 450,
                outcome = com.example.data.local.entity.TripOutcome.ARRIVED,
                triggeredAt = System.currentTimeMillis() - 60_000L
            )
            val rearmed = repo.replayTrip(tripForDeletedAlarm)
            assertTrue(rearmed.isActive)
            assertEquals("Old Station", rearmed.title)
            assertEquals(450, rearmed.radiusMeters)
            assertEquals(1, repo.getActiveAlarmsOnce().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `apkUpdateManager findExistingDownloadedApk validates versioned filename and size`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = ApkUpdateManager(
            context = context,
            gitHubApi = com.example.data.remote.GitHubReleaseApiService.create()
        )
        val updateInfo = com.example.util.AppUpdateInfo(
            versionName = "9.9.9",
            tagName = "v9.9.9",
            releaseNotes = "Release notes",
            releasePageUrl = "https://example.com",
            apkAsset = com.example.data.remote.dto.GitHubAssetDto(
                name = "PinWake-v9.9.9.apk",
                browserDownloadUrl = "https://example.com/PinWake-v9.9.9.apk",
                size = 5L
            )
        )
        val updatesDir = java.io.File(context.cacheDir, "updates").apply { mkdirs() }
        val apkFile = java.io.File(updatesDir, "PinWake-v9.9.9.apk")
        try {
            apkFile.writeText("123")
            assertEquals(null, manager.findExistingDownloadedApk(updateInfo))

            apkFile.writeText("12345")
            assertEquals(apkFile, manager.findExistingDownloadedApk(updateInfo))
        } finally {
            apkFile.delete()
        }
    }

    @Test
    fun `apkUpdateManager rejects untrusted or non-https release urls`() {
        assertTrue(ApkUpdateManager.isTrustedGitHubHttpsUrl("https://github.com/user/repo/releases/download/v1.0/app.apk"))
        assertTrue(ApkUpdateManager.isTrustedGitHubHttpsUrl("https://objects.githubusercontent.com/github-production-release/123"))
        assertTrue(ApkUpdateManager.isTrustedGitHubHttpsUrl("https://release-assets.githubusercontent.com/asset"))
        assertFalse(ApkUpdateManager.isTrustedGitHubHttpsUrl("http://github.com/user/repo/releases/download/v1.0/app.apk"))
        assertFalse(ApkUpdateManager.isTrustedGitHubHttpsUrl("https://evil-github.com/app.apk"))
        assertFalse(ApkUpdateManager.isTrustedGitHubHttpsUrl("https://github.com.evil.org/app.apk"))
        assertFalse(ApkUpdateManager.isTrustedGitHubHttpsUrl("file:///sdcard/app.apk"))
        assertFalse(ApkUpdateManager.isTrustedGitHubHttpsUrl(null))
    }

    @Test
    fun `backupManager enforces size limit and truncates oversized fields`() {
        val oversized = "{\"alarms\":[]}".padEnd(com.example.util.BackupManager.MAX_BACKUP_JSON_CHARS + 10, ' ')
        assertTrue(com.example.util.BackupManager.parseFromJson(oversized).isFailure)

        val longTitle = "A".repeat(200)
        val longAddress = "B".repeat(400)
        val json = """
            {
              "schemaVersion": 1,
              "alarms": [
                {
                  "title": "$longTitle",
                  "address": "$longAddress",
                  "latitude": 36.75,
                  "longitude": 3.05,
                  "radiusMeters": 99999
                }
              ]
            }
        """.trimIndent()
        val parsed = com.example.util.BackupManager.parseFromJson(json).getOrThrow()
        assertEquals(1, parsed.alarms.size)
        assertEquals(120, parsed.alarms[0].title.length)
        assertEquals(240, parsed.alarms[0].address.length)
        assertEquals(5000, parsed.alarms[0].radiusMeters)
    }

    @Test
    fun `mapLinkParser handles 2-decimal coords and avoids mid-token numbers`() {
        val shortCoords = com.example.util.MapLinkParser.parse("Pin at 36.75, 3.05")
        assertEquals(36.75, shortCoords?.latitude ?: 0.0, 0.0001)
        assertEquals(3.05, shortCoords?.longitude ?: 0.0, 0.0001)

        val invalidMidToken = com.example.util.MapLinkParser.parse("id12.34,56.78test")
        assertEquals(null, invalidMidToken)
    }

    @Test
    fun `applySnooze500m shrinks default 500m radius and logs snoozed trip`() = kotlinx.coroutines.runBlocking {
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
            val id = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.NONE,
                title = "Stop",
                address = "Main St",
                latitude = 36.75,
                longitude = 3.05,
                radiusMeters = 500,
                isActive = true
            )
            val alarm = repo.getAlarmById(id)!!
            repo.applySnooze500m(alarm, currentDistanceMeters = 450f)

            val updated = repo.getAlarmById(id)!!
            assertEquals(200, updated.radiusMeters)
            assertEquals(0L, updated.snoozedUntilMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun `tripHistoryDao insertTripAndPrune caps stored rows`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.AppDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val dao = db.tripHistoryDao()
            for (i in 1..10) {
                dao.insertTripAndPrune(
                    com.example.data.local.entity.TripHistoryEntity(
                        alarmId = 1L,
                        alarmTitle = "Stop $i",
                        address = "Addr $i",
                        latitude = 36.75,
                        longitude = 3.05,
                        radiusMeters = 500,
                        outcome = com.example.data.local.entity.TripOutcome.ARRIVED,
                        triggeredAt = i * 1000L
                    ),
                    keepCount = 5
                )
            }
            val remaining = dao.observeRecentTrips().first()
            assertEquals(5, remaining.size)
            assertEquals("Stop 10", remaining.first().alarmTitle)
            assertEquals("Stop 6", remaining.last().alarmTitle)
        } finally {
            db.close()
        }
    }

    @Test
    fun `custom ringtone persists and backupManager validates ringtone uri schemes`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val validUri = "content://media/internal/audio/media/42"
        val alarm = com.example.data.local.entity.GeoAlarmEntity(
            title = "Morning Commute",
            address = "Downtown",
            latitude = 36.7538,
            longitude = 3.0588,
            radiusMeters = 500,
            isActive = true,
            ringtoneUri = validUri,
            ringtoneTitle = "Helios"
        )

        val json = com.example.util.BackupManager.serializeToJson(listOf(alarm), preAlertMeters = 300)
        val parsed = com.example.util.BackupManager.parseFromJson(json).getOrThrow()
        assertEquals(validUri, parsed.alarms[0].ringtoneUri)
        assertEquals("Helios", parsed.alarms[0].ringtoneTitle)

        // Unsafe file:// URI or oversized URI must be stripped during import parsing
        val unsafeJson = """
            {
              "schemaVersion": 1,
              "alarms": [
                {
                  "title": "Unsafe",
                  "address": "Test",
                  "latitude": 36.75,
                  "longitude": 3.05,
                  "ringtoneUri": "file:///sdcard/evil.mp3",
                  "ringtoneTitle": "Evil"
                }
              ]
            }
        """.trimIndent()
        val unsafeParsed = com.example.util.BackupManager.parseFromJson(unsafeJson).getOrThrow()
        assertEquals(null, unsafeParsed.alarms[0].ringtoneUri)
        assertEquals("Evil", unsafeParsed.alarms[0].ringtoneTitle)

        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.AppDatabase::class.java
        ).allowMainThreadQueries().build()
        try {
            val id = db.geoAlarmDao().insertAlarm(alarm)
            val saved = db.geoAlarmDao().getAlarmById(id)
            assertEquals(validUri, saved?.ringtoneUri)
            assertEquals("Helios", saved?.ringtoneTitle)
        } finally {
            db.close()
        }
    }

    @Test
    fun `forced gps keep-on and silent override preferences default true and persist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(com.example.util.PreAlertPreferences.isForcedGpsKeepOnEnabled(context))
        assertTrue(com.example.util.PreAlertPreferences.isForceSilentOverrideEnabled(context))

        com.example.util.PreAlertPreferences.setForcedGpsKeepOnEnabled(context, false)
        com.example.util.PreAlertPreferences.setForceSilentOverrideEnabled(context, false)

        assertFalse(com.example.util.PreAlertPreferences.isForcedGpsKeepOnEnabled(context))
        assertFalse(com.example.util.PreAlertPreferences.isForceSilentOverrideEnabled(context))

        com.example.util.PreAlertPreferences.setForcedGpsKeepOnEnabled(context, true)
        com.example.util.PreAlertPreferences.setForceSilentOverrideEnabled(context, true)

        assertTrue(com.example.util.PreAlertPreferences.isForcedGpsKeepOnEnabled(context))
        assertTrue(com.example.util.PreAlertPreferences.isForceSilentOverrideEnabled(context))
    }

    @Test
    fun `migration 1 to 2 adds ringtone columns to geo_alarms cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration-test-${System.nanoTime()}.db"
        val sqliteHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE IF NOT EXISTS geo_alarms (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                title TEXT NOT NULL,
                                address TEXT NOT NULL,
                                latitude REAL NOT NULL,
                                longitude REAL NOT NULL,
                                radiusMeters INTEGER NOT NULL,
                                isActive INTEGER NOT NULL,
                                isFavorite INTEGER NOT NULL,
                                favoriteCategory TEXT NOT NULL,
                                vibrateEnabled INTEGER NOT NULL,
                                lastDistanceMeters REAL,
                                snoozedUntilMs INTEGER NOT NULL,
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        try {
            val db = sqliteHelper.writableDatabase
            com.example.data.local.AppDatabase.MIGRATION_1_2.migrate(db)
            db.execSQL(
                "INSERT INTO geo_alarms (title, address, latitude, longitude, radiusMeters, isActive, isFavorite, favoriteCategory, vibrateEnabled, snoozedUntilMs, createdAt, updatedAt, ringtoneUri, ringtoneTitle) " +
                    "VALUES ('Work', 'Center', 36.75, 3.05, 500, 1, 1, 'WORK', 1, 0, 1000, 1000, 'content://ring/1', 'Chime')"
            )
            db.query("SELECT ringtoneUri, ringtoneTitle FROM geo_alarms WHERE title='Work'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("content://ring/1", cursor.getString(0))
                assertEquals("Chime", cursor.getString(1))
            }
        } finally {
            sqliteHelper.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun `geofence radius allows 10m precision minimum across repository snooze replay and backup`() = kotlinx.coroutines.runBlocking {
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
            val id10 = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.NONE,
                title = "Doorstep",
                address = "Gate A",
                latitude = 36.75,
                longitude = 3.05,
                radiusMeters = 10,
                isActive = true
            )
            assertEquals(10, repo.getAlarmById(id10)?.radiusMeters)

            val idClamped = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.NONE,
                title = "SubMin",
                address = "Gate B",
                latitude = 36.76,
                longitude = 3.06,
                radiusMeters = 3,
                isActive = true
            )
            assertEquals(10, repo.getAlarmById(idClamped)?.radiusMeters)

            val id20 = repo.saveQuickAssignedLocation(
                category = com.example.data.local.entity.FavoriteCategory.NONE,
                title = "Tight Stop",
                address = "Gate C",
                latitude = 36.77,
                longitude = 3.07,
                radiusMeters = 20,
                isActive = true
            )
            val alarm20 = repo.getAlarmById(id20)!!
            repo.applySnooze500m(alarm20, currentDistanceMeters = 15f)
            assertEquals(10, repo.getAlarmById(id20)?.radiusMeters)

            val json = """
                {
                  "schemaVersion": 1,
                  "alarms": [
                    {"title": "Exact10", "address": "A", "latitude": 36.75, "longitude": 3.05, "radiusMeters": 10},
                    {"title": "Clamp10", "address": "B", "latitude": 36.76, "longitude": 3.06, "radiusMeters": 2}
                  ]
                }
            """.trimIndent()
            val parsed = com.example.util.BackupManager.parseFromJson(json).getOrThrow()
            assertEquals(10, parsed.alarms[0].radiusMeters)
            assertEquals(10, parsed.alarms[1].radiusMeters)
        } finally {
            db.close()
        }
    }
}
