package com.example.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OsrmRouteResponseDto(
    val code: String? = null,
    val routes: List<OsrmRouteDto>? = null
)

@JsonClass(generateAdapter = true)
data class OsrmRouteDto(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val geometry: OsrmGeometryDto? = null,
    val legs: List<OsrmLegDto>? = null
)

@JsonClass(generateAdapter = true)
data class OsrmGeometryDto(
    val type: String? = null,
    val coordinates: List<List<Double>>? = null
)

@JsonClass(generateAdapter = true)
data class OsrmLegDto(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val steps: List<OsrmStepDto>? = null
)

@JsonClass(generateAdapter = true)
data class OsrmStepDto(
    val distance: Double = 0.0,
    val duration: Double = 0.0,
    val name: String? = null,
    val mode: String? = null,
    val geometry: OsrmGeometryDto? = null,
    val maneuver: OsrmManeuverDto? = null
)

@JsonClass(generateAdapter = true)
data class OsrmManeuverDto(
    val type: String? = null,
    val modifier: String? = null,
    @Json(name = "bearing_before") val bearingBefore: Double? = null,
    @Json(name = "bearing_after") val bearingAfter: Double? = null,
    val location: List<Double>? = null,
    val exit: Int? = null
)
