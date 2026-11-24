package com.example.util

import com.example.data.remote.dto.NominatimPlaceDto
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ParsedMapDestination(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null
)

object MapLinkParser {

    private val GEO_URI_REGEX = Regex(
        """geo:([-+]?\d+(?:\.\d+)?),([-+]?\d+(?:\.\d+)?)(?:\?([^#\s]+))?""",
        RegexOption.IGNORE_CASE
    )
    private val BANG_3D_4D_REGEX = Regex("""!3d([-+]?\d+(?:\.\d+)?)!4d([-+]?\d+(?:\.\d+)?)""")
    private val AT_COORDS_REGEX = Regex("""@([-+]?\d+(?:\.\d+)?),([-+]?\d+(?:\.\d+)?)""")
    private val OSM_MLAT_REGEX = Regex("""[?&]mlat=([-+]?\d+(?:\.\d+)?)&mlon=([-+]?\d+(?:\.\d+)?)""")
    private val OSM_HASH_REGEX = Regex("""#map=\d+/([-+]?\d+(?:\.\d+)?)/([-+]?\d+(?:\.\d+)?)""")
    private val QUERY_COORDS_REGEX = Regex(
        """[?&](?:q|query|ll|center|destination)=([-+]?\d+(?:\.\d+)?)[,\s]+([-+]?\d+(?:\.\d+)?)(?:\(([^)]+)\))?""",
        RegexOption.IGNORE_CASE
    )
    private val PLAIN_COORDS_REGEX = Regex(
        """([-+]?\d{1,2}\.\d{3,}),\s*([-+]?\d{1,3}\.\d{3,})"""
    )

    fun parse(rawInput: String?): ParsedMapDestination? {
        if (rawInput.isNullOrBlank()) return null
        val text = rawInput.trim()

        // 1. geo: URI (e.g. geo:36.7538,3.0588 or geo:0,0?q=36.7538,3.0588(Central Station))
        GEO_URI_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            val query = match.groupValues.getOrNull(3)

            if (!query.isNullOrBlank()) {
                val decodedQuery = decodeUrlPart(query)
                val qParamMatch = Regex(
                    """q=([-+]?\d+(?:\.\d+)?)[,\s]+([-+]?\d+(?:\.\d+)?)(?:\(([^)]+)\))?""",
                    RegexOption.IGNORE_CASE
                ).find(decodedQuery)
                if (qParamMatch != null) {
                    val qLat = qParamMatch.groupValues[1].toDoubleOrNull()
                    val qLon = qParamMatch.groupValues[2].toDoubleOrNull()
                    val qLabel = cleanLabel(qParamMatch.groupValues.getOrNull(3))
                    if (isValidLatLon(qLat, qLon) && !(qLat == 0.0 && qLon == 0.0)) {
                        return ParsedMapDestination(qLat!!, qLon!!, qLabel)
                    }
                }
            }
            if (isValidLatLon(lat, lon) && !(lat == 0.0 && lon == 0.0)) {
                return ParsedMapDestination(lat!!, lon!!, null)
            }
        }

        // 2. Google Maps !3dLAT!4dLON pin coordinates (prefer exact pin over camera @center)
        BANG_3D_4D_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, extractPlaceSlug(text))
            }
        }

        // 3. OpenStreetMap ?mlat=..&mlon=..
        OSM_MLAT_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, null)
            }
        }

        // 4. Google Maps / Apple Maps query parameters (?q=lat,lon or ?ll=lat,lon)
        QUERY_COORDS_REGEX.find(decodeUrlPart(text))?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            val label = cleanLabel(match.groupValues.getOrNull(3))
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, label)
            }
        }

        // 5. Google Maps @lat,lon camera coordinate
        AT_COORDS_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, extractPlaceSlug(text))
            }
        }

        // 6. OpenStreetMap #map=zoom/lat/lon
        OSM_HASH_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, null)
            }
        }

        // 7. Plain coordinate string anywhere in shared text
        PLAIN_COORDS_REGEX.find(text)?.let { match ->
            val lat = match.groupValues[1].toDoubleOrNull()
            val lon = match.groupValues[2].toDoubleOrNull()
            if (isValidLatLon(lat, lon)) {
                return ParsedMapDestination(lat!!, lon!!, null)
            }
        }

        return null
    }

    private fun extractPlaceSlug(url: String): String? {
        val placeMatch = Regex("""/maps/place/([^/@?]+)""").find(url) ?: return null
        val decoded = decodeUrlPart(placeMatch.groupValues[1]).replace('+', ' ')
        return cleanLabel(decoded)
    }

    private fun cleanLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = NominatimPlaceDto.stripUnsupportedGlyphs(
            decodeUrlPart(raw).replace('+', ' ')
        )
        return cleaned.takeIf { it.isNotBlank() }
    }

    private fun decodeUrlPart(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun isValidLatLon(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        if (lat.isNaN() || lon.isNaN()) return false
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }
}
