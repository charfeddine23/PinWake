package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NominatimAddressDto(
    val road: String? = null,
    val neighbourhood: String? = null,
    val suburb: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val state: String? = null,
    val country: String? = null,
    val railway: String? = null,
    val station: String? = null,
    val amenity: String? = null
)

@JsonClass(generateAdapter = true)
data class NominatimPlaceDto(
    @Json(name = "place_id") val placeId: Long = 0L,
    @Json(name = "display_name") val displayName: String = "",
    val name: String? = null,
    val lat: String = "0.0",
    val lon: String = "0.0",
    val type: String? = null,
    val category: String? = null,
    val address: NominatimAddressDto? = null
) {
    fun latitudeDouble(): Double = lat.toDoubleOrNull() ?: 0.0
    fun longitudeDouble(): Double = lon.toDoubleOrNull() ?: 0.0

    fun shortTitle(): String {
        val cleanName = stripUnsupportedGlyphs(name)
        if (cleanName.isNotBlank()) return cleanName
        val addr = address
        val candidate = listOf(
            addr?.station,
            addr?.railway,
            addr?.amenity,
            addr?.road,
            addr?.neighbourhood,
            addr?.suburb,
            addr?.city,
            addr?.town,
            addr?.village
        ).firstNotNullOfOrNull { raw ->
            stripUnsupportedGlyphs(raw).takeIf { it.isNotBlank() }
        }
        if (!candidate.isNullOrBlank()) return candidate
        val cleanDisplay = stripUnsupportedGlyphs(displayName)
        return cleanDisplay.substringBefore(",").trim().ifEmpty { cleanDisplay }
    }

    fun compactAddress(): String {
        val cleanDisplay = stripUnsupportedGlyphs(displayName)
        val parts = cleanDisplay.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size <= 3) parts.joinToString(", ") else parts.take(3).joinToString(", ")
    }

    companion object {
        // Strips Tifinagh Unicode block (U+2D30..U+2D7F) and cleans leftover separator artifacts
        private val TIFINAGH_REGEX = Regex("[\u2D30-\u2D7F]+")
        private val ORPHAN_SEP_REGEX = Regex("\\s*[/|·•-]+\\s*(?=[,|/]|$)|(^|\\s)[/|]+\\s*")
        private val MULTI_SPACE_REGEX = Regex("\\s{2,}")

        fun stripUnsupportedGlyphs(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw
                .replace(TIFINAGH_REGEX, "")
                .replace(ORPHAN_SEP_REGEX, " ")
                .replace(MULTI_SPACE_REGEX, " ")
                .trim(' ', ',', '/', '-', '·')
        }
    }
}
