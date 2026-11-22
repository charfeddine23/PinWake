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
        if (!name.isNullOrBlank()) return name
        val addr = address
        val candidate = addr?.station
            ?: addr?.railway
            ?: addr?.amenity
            ?: addr?.road
            ?: addr?.neighbourhood
            ?: addr?.suburb
            ?: addr?.city
            ?: addr?.town
            ?: addr?.village
        if (!candidate.isNullOrBlank()) return candidate
        return displayName.substringBefore(",").trim().ifEmpty { displayName }
    }

    fun compactAddress(): String {
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size <= 3) displayName else parts.take(3).joinToString(", ")
    }
}
