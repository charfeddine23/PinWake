package com.example.util

import com.example.data.local.entity.FavoriteCategory
import com.example.data.local.entity.GeoAlarmEntity
import com.example.data.remote.dto.NominatimPlaceDto
import org.json.JSONArray
import org.json.JSONObject

data class BackupPayload(
    val schemaVersion: Int,
    val exportedAt: Long,
    val preAlertMeters: Int?,
    val alarms: List<GeoAlarmEntity>
)

object BackupManager {

    private const val SCHEMA_VERSION = 1
    const val MAX_BACKUP_JSON_CHARS = 2 * 1024 * 1024
    private const val MAX_IMPORT_ALARMS = 500
    private const val MAX_TITLE_LENGTH = 120
    private const val MAX_ADDRESS_LENGTH = 240

    fun serializeToJson(
        alarms: List<GeoAlarmEntity>,
        preAlertMeters: Int,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("app", "PinWake")
            put("exportedAt", exportedAt)
            put("preAlertMeters", preAlertMeters)

            val array = JSONArray()
            alarms.forEach { alarm ->
                array.put(
                    JSONObject().apply {
                        put("title", alarm.title)
                        put("address", alarm.address)
                        put("latitude", alarm.latitude)
                        put("longitude", alarm.longitude)
                        put("radiusMeters", alarm.radiusMeters.coerceIn(200, 5000))
                        put("isActive", alarm.isActive)
                        put("isFavorite", alarm.isFavorite)
                        put("favoriteCategory", alarm.favoriteCategory.name)
                        put("vibrateEnabled", alarm.vibrateEnabled)
                        alarm.ringtoneUri?.takeIf { isValidRingtoneUri(it) }?.let { put("ringtoneUri", it) }
                        alarm.ringtoneTitle?.takeIf { it.isNotBlank() }?.let { put("ringtoneTitle", it) }
                        put("createdAt", alarm.createdAt)
                        put("updatedAt", alarm.updatedAt)
                    }
                )
            }
            put("alarms", array)
        }
        return root.toString(2)
    }

    private fun isValidRingtoneUri(raw: String): Boolean {
        if (raw.length > 512) return false
        return raw.startsWith("content://") || raw.startsWith("android.resource://")
    }

    fun parseFromJson(rawJson: String): Result<BackupPayload> = runCatching {
        require(rawJson.length <= MAX_BACKUP_JSON_CHARS) { "Backup JSON exceeds size limit" }
        val root = JSONObject(rawJson)
        val version = root.optInt("schemaVersion", 1)
        val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())
        val preAlertMeters = if (root.has("preAlertMeters")) {
            PreAlertPreferences.normalizeDistance(root.optInt("preAlertMeters"))
        } else {
            null
        }

        val alarmsArray = root.optJSONArray("alarms") ?: JSONArray()
        val itemLimit = minOf(alarmsArray.length(), MAX_IMPORT_ALARMS)
        val parsed = ArrayList<GeoAlarmEntity>(itemLimit)
        val now = System.currentTimeMillis()

        for (i in 0 until itemLimit) {
            val obj = alarmsArray.optJSONObject(i) ?: continue
            val lat = obj.optDouble("latitude", Double.NaN)
            val lon = obj.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                continue
            }

            val rawTitle = NominatimPlaceDto.stripUnsupportedGlyphs(obj.optString("title", ""))
                .take(MAX_TITLE_LENGTH)
                .trim()
            if (rawTitle.isBlank()) continue
            val rawAddress = NominatimPlaceDto.stripUnsupportedGlyphs(obj.optString("address", ""))
                .take(MAX_ADDRESS_LENGTH)
                .trim()

            val categoryName = obj.optString("favoriteCategory", FavoriteCategory.NONE.name)
            val category = runCatching {
                FavoriteCategory.valueOf(categoryName)
            }.getOrDefault(FavoriteCategory.NONE)

            val isFavorite = obj.optBoolean("isFavorite", category != FavoriteCategory.NONE) ||
                category != FavoriteCategory.NONE

            val rawRingtoneUri = obj.optString("ringtoneUri", "").trim()
                .takeIf { it.isNotBlank() && isValidRingtoneUri(it) }
            val rawRingtoneTitle = NominatimPlaceDto.stripUnsupportedGlyphs(
                obj.optString("ringtoneTitle", "")
            ).take(80).trim().takeIf { it.isNotBlank() }

            parsed.add(
                GeoAlarmEntity(
                    id = 0L,
                    title = rawTitle,
                    address = rawAddress.ifBlank { rawTitle },
                    latitude = lat,
                    longitude = lon,
                    radiusMeters = obj.optInt("radiusMeters", 500).coerceIn(200, 5000),
                    isActive = obj.optBoolean("isActive", false),
                    isFavorite = isFavorite,
                    favoriteCategory = category,
                    vibrateEnabled = obj.optBoolean("vibrateEnabled", true),
                    ringtoneUri = rawRingtoneUri,
                    ringtoneTitle = rawRingtoneTitle,
                    lastDistanceMeters = null,
                    snoozedUntilMs = 0L,
                    createdAt = obj.optLong("createdAt", now),
                    updatedAt = obj.optLong("updatedAt", now)
                )
            )
        }

        BackupPayload(
            schemaVersion = version,
            exportedAt = exportedAt,
            preAlertMeters = preAlertMeters,
            alarms = parsed
        )
    }
}
