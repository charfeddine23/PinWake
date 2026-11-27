package com.example.util

import android.content.Context
import com.example.R
import com.example.data.remote.dto.NominatimPlaceDto
import com.example.data.remote.dto.OsrmRouteResponseDto
import com.example.data.remote.dto.OsrmStepDto
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

enum class TravelProfile(
    val osrmProfile: String,
    val fallbackSpeedMps: Float
) {
    DRIVING("driving", 8.33f),
    WALKING("walking", 1.38f)
}

enum class ManeuverIconType {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    UTURN,
    ROUNDABOUT,
    ARRIVE
}

data class NavPoint(
    val latitude: Double,
    val longitude: Double
)

data class NavStep(
    val index: Int,
    val type: String,
    val modifier: String?,
    val streetName: String,
    val exitNumber: Int?,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverPoint: NavPoint,
    val bearingAfter: Float,
    val iconType: ManeuverIconType
)

data class NavRoute(
    val profile: TravelProfile,
    val points: List<NavPoint>,
    val steps: List<NavStep>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val isOfflineDirectFallback: Boolean
)

data class NavProgressState(
    val upcomingStep: NavStep,
    val followingStep: NavStep?,
    val distanceToNextTurnMeters: Float,
    val remainingDistanceMeters: Float,
    val remainingDurationSeconds: Long,
    val isLiveSpeedEta: Boolean,
    val offRouteDistanceMeters: Float,
    val shouldReroute: Boolean,
    val hasArrived: Boolean,
    val snappedUserPoint: NavPoint,
    val remainingRoutePoints: List<NavPoint>,
    val activeBearingDegrees: Float
)

object InAppNavigationEngine {

    private const val OFF_ROUTE_THRESHOLD_METERS = 65f
    private const val STEP_ADVANCE_TOLERANCE_METERS = 12.0
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    fun formatCoordinatesParam(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): String {
        return String.format(
            Locale.US,
            "%.6f,%.6f;%.6f,%.6f",
            fromLon,
            fromLat,
            toLon,
            toLat
        )
    }

    fun fromOsrmResponse(
        response: OsrmRouteResponseDto,
        profile: TravelProfile
    ): NavRoute? {
        if (!response.code.equals("Ok", ignoreCase = true)) return null
        val route = response.routes?.firstOrNull() ?: return null
        val rawCoords = route.geometry?.coordinates ?: return null
        val points = rawCoords.mapNotNull { pair ->
            if (pair.size >= 2) NavPoint(latitude = pair[1], longitude = pair[0]) else null
        }
        if (points.size < 2) return null

        val rawSteps = route.legs?.flatMap { it.steps.orEmpty() }.orEmpty()
        val steps = if (rawSteps.isNotEmpty()) {
            rawSteps.mapIndexed { index, dto -> mapStepDto(index, dto, points) }
        } else {
            val first = points.first()
            val last = points.last()
            val (dist, bearing) = NavigationAssistCalculator.computeDistanceAndBearing(
                first.latitude,
                first.longitude,
                last.latitude,
                last.longitude
            )
            listOf(
                NavStep(
                    index = 0,
                    type = "arrive",
                    modifier = null,
                    streetName = "",
                    exitNumber = null,
                    distanceMeters = dist.toDouble(),
                    durationSeconds = route.duration,
                    maneuverPoint = last,
                    bearingAfter = bearing,
                    iconType = ManeuverIconType.ARRIVE
                )
            )
        }

        return NavRoute(
            profile = profile,
            points = points,
            steps = steps,
            totalDistanceMeters = route.distance.coerceAtLeast(computePolylineLength(points)),
            totalDurationSeconds = route.duration.coerceAtLeast(1.0),
            isOfflineDirectFallback = false
        )
    }

    fun buildDirectFallbackRoute(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        profile: TravelProfile
    ): NavRoute {
        val (distMeters, bearing) = NavigationAssistCalculator.computeDistanceAndBearing(
            startLat = fromLat,
            startLon = fromLon,
            endLat = toLat,
            endLon = toLon
        )
        val durationSec = (distMeters / profile.fallbackSpeedMps).toDouble().coerceAtLeast(1.0)
        val start = NavPoint(fromLat, fromLon)
        val end = NavPoint(toLat, toLon)
        return NavRoute(
            profile = profile,
            points = listOf(start, end),
            steps = listOf(
                NavStep(
                    index = 0,
                    type = "arrive",
                    modifier = "straight",
                    streetName = "",
                    exitNumber = null,
                    distanceMeters = distMeters.toDouble(),
                    durationSeconds = durationSec,
                    maneuverPoint = end,
                    bearingAfter = bearing,
                    iconType = ManeuverIconType.ARRIVE
                )
            ),
            totalDistanceMeters = distMeters.toDouble(),
            totalDurationSeconds = durationSec,
            isOfflineDirectFallback = true
        )
    }

    fun computeProgress(
        route: NavRoute,
        currentLat: Double,
        currentLon: Double,
        currentSpeedMps: Float?,
        geofenceRadiusMeters: Int
    ): NavProgressState {
        val points = route.points
        if (points.size < 2) {
            val fallbackPoint = points.firstOrNull() ?: NavPoint(currentLat, currentLon)
            val step = route.steps.first()
            return NavProgressState(
                upcomingStep = step,
                followingStep = null,
                distanceToNextTurnMeters = 0f,
                remainingDistanceMeters = 0f,
                remainingDurationSeconds = 0L,
                isLiveSpeedEta = false,
                offRouteDistanceMeters = 0f,
                shouldReroute = false,
                hasArrived = true,
                snappedUserPoint = fallbackPoint,
                remainingRoutePoints = points,
                activeBearingDegrees = step.bearingAfter
            )
        }

        val cumDistances = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumDistances[i] = cumDistances[i - 1] + distanceBetween(points[i - 1], points[i])
        }
        val totalGeomMeters = cumDistances.last().coerceAtLeast(1.0)

        val projection = projectPointOntoPolyline(
            lat = currentLat,
            lon = currentLon,
            points = points,
            cumDistances = cumDistances
        )
        val remainingGeomMeters = (totalGeomMeters - projection.traveledMeters).coerceAtLeast(0.0)

        val remainingPolyline = buildList {
            add(projection.snappedPoint)
            for (idx in (projection.segmentIndex + 1) until points.size) {
                add(points[idx])
            }
        }

        val steps = route.steps
        val stepOffsets = computeStepOffsets(steps, points, cumDistances, totalGeomMeters)

        var currentStepIdx = 0
        for (i in steps.indices) {
            if (stepOffsets[i] <= projection.traveledMeters + STEP_ADVANCE_TOLERS(route)) {
                currentStepIdx = i
            } else {
                break
            }
        }

        val upcomingIdx = when {
            steps.size == 1 -> 0
            currentStepIdx < steps.lastIndex -> currentStepIdx + 1
            else -> steps.lastIndex
        }
        val upcomingStep = steps[upcomingIdx]
        val followingStep = steps.getOrNull(upcomingIdx + 1)
        val distToTurn = if (steps.size == 1) {
            remainingGeomMeters.toFloat()
        } else {
            (stepOffsets[upcomingIdx] - projection.traveledMeters).coerceAtLeast(0.0).toFloat()
        }

        val validSpeed = currentSpeedMps
            ?.takeIf { !it.isNaN() && it >= 0.8f }
            ?.coerceAtMost(55f)
        val ratioRemaining = (remainingGeomMeters / totalGeomMeters).coerceIn(0.0, 1.0)
        val modelEtaSec = (route.totalDurationSeconds * ratioRemaining).toLong().coerceAtLeast(0L)
        val (etaSec, isLiveEta) = when {
            remainingGeomMeters <= 20.0 -> 0L to (validSpeed != null)
            validSpeed != null -> {
                val speedEta = (remainingGeomMeters / validSpeed).toLong().coerceAtLeast(1L)
                speedEta to true
            }
            else -> modelEtaSec.coerceAtLeast(1L) to false
        }

        val dest = points.last()
        val (directToDest, segBearing) = NavigationAssistCalculator.computeDistanceAndBearing(
            startLat = currentLat,
            startLon = currentLon,
            endLat = dest.latitude,
            endLon = dest.longitude
        )
        val nextVertex = points[(projection.segmentIndex + 1).coerceAtMost(points.lastIndex)]
        val (_, pathBearing) = NavigationAssistCalculator.computeDistanceAndBearing(
            startLat = projection.snappedPoint.latitude,
            startLon = projection.snappedPoint.longitude,
            endLat = nextVertex.latitude,
            endLon = nextVertex.longitude
        )

        val arrivalThreshold = max(25f, geofenceRadiusMeters * 0.25f).coerceAtMost(60f)
        val hasArrived = remainingGeomMeters <= arrivalThreshold || directToDest <= 25f
        val shouldReroute = !route.isOfflineDirectFallback &&
            !hasArrived &&
            projection.perpDistanceMeters > OFF_ROUTE_THRESHOLD_METERS

        return NavProgressState(
            upcomingStep = upcomingStep,
            followingStep = followingStep,
            distanceToNextTurnMeters = distToTurn,
            remainingDistanceMeters = remainingGeomMeters.toFloat(),
            remainingDurationSeconds = etaSec,
            isLiveSpeedEta = isLiveEta,
            offRouteDistanceMeters = projection.perpDistanceMeters,
            shouldReroute = shouldReroute,
            hasArrived = hasArrived,
            snappedUserPoint = projection.snappedPoint,
            remainingRoutePoints = remainingPolyline,
            activeBearingDegrees = if (remainingGeomMeters > 8.0) pathBearing else segBearing
        )
    }

    private fun STEP_ADVANCE_TOLERS(route: NavRoute): Double =
        if (route.profile == TravelProfile.WALKING) 8.0 else STEP_ADVANCE_TOLERANCE_METERS

    fun resolveManeuverIconType(type: String?, modifier: String?): ManeuverIconType {
        val normType = type?.lowercase(Locale.US).orEmpty()
        val normMod = modifier?.lowercase(Locale.US).orEmpty()
        return when (normType) {
            "arrive" -> ManeuverIconType.ARRIVE
            "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" ->
                ManeuverIconType.ROUNDABOUT
            else -> when (normMod) {
                "uturn" -> ManeuverIconType.UTURN
                "sharp left" -> ManeuverIconType.SHARP_LEFT
                "left" -> ManeuverIconType.LEFT
                "slight left" -> ManeuverIconType.SLIGHT_LEFT
                "sharp right" -> ManeuverIconType.SHARP_RIGHT
                "right" -> ManeuverIconType.RIGHT
                "slight right" -> ManeuverIconType.SLIGHT_RIGHT
                else -> ManeuverIconType.STRAIGHT
            }
        }
    }

    fun formatInstruction(
        context: Context,
        step: NavStep,
        destinationTitle: String
    ): String {
        val cleanStreet = NominatimPlaceDto.stripUnsupportedGlyphs(step.streetName)
            .ifBlank { step.streetName.trim() }
        val streetDisplay = cleanStreet.take(48)

        if (step.iconType == ManeuverIconType.ARRIVE) {
            return if (destinationTitle.isNotBlank()) {
                context.getString(R.string.nav_maneuver_arrive_named, destinationTitle)
            } else {
                context.getString(R.string.nav_maneuver_arrive)
            }
        }

        if (step.iconType == ManeuverIconType.ROUNDABOUT) {
            val exit = step.exitNumber
            return when {
                exit != null && streetDisplay.isNotBlank() ->
                    context.getString(R.string.nav_maneuver_roundabout_exit_onto, exit, streetDisplay)
                exit != null ->
                    context.getString(R.string.nav_maneuver_roundabout_exit, exit)
                streetDisplay.isNotBlank() ->
                    context.getString(R.string.nav_maneuver_roundabout_onto, streetDisplay)
                else ->
                    context.getString(R.string.nav_maneuver_roundabout)
            }
        }

        val actionRes = when (step.iconType) {
            ManeuverIconType.LEFT -> R.string.nav_action_turn_left
            ManeuverIconType.SLIGHT_LEFT -> R.string.nav_action_slight_left
            ManeuverIconType.SHARP_LEFT -> R.string.nav_action_sharp_left
            ManeuverIconType.RIGHT -> R.string.nav_action_turn_right
            ManeuverIconType.SLIGHT_RIGHT -> R.string.nav_action_slight_right
            ManeuverIconType.SHARP_RIGHT -> R.string.nav_action_sharp_right
            ManeuverIconType.UTURN -> R.string.nav_action_uturn
            ManeuverIconType.STRAIGHT -> R.string.nav_action_continue
            ManeuverIconType.ROUNDABOUT -> R.string.nav_maneuver_roundabout
            ManeuverIconType.ARRIVE -> R.string.nav_maneuver_arrive
        }
        val actionText = context.getString(actionRes)
        return if (streetDisplay.isNotBlank()) {
            context.getString(R.string.nav_maneuver_onto_format, actionText, streetDisplay)
        } else {
            actionText
        }
    }

    private fun mapStepDto(
        index: Int,
        dto: OsrmStepDto,
        routePoints: List<NavPoint>
    ): NavStep {
        val loc = dto.maneuver?.location
        val maneuverPoint = if (loc != null && loc.size >= 2) {
            NavPoint(latitude = loc[1], longitude = loc[0])
        } else {
            routePoints.first()
        }
        val type = dto.maneuver?.type.orEmpty()
        val modifier = dto.maneuver?.modifier
        return NavStep(
            index = index,
            type = type,
            modifier = modifier,
            streetName = dto.name.orEmpty(),
            exitNumber = dto.maneuver?.exit,
            distanceMeters = dto.distance.coerceAtLeast(0.0),
            durationSeconds = dto.duration.coerceAtLeast(0.0),
            maneuverPoint = maneuverPoint,
            bearingAfter = (dto.maneuver?.bearingAfter ?: 0.0).toFloat(),
            iconType = resolveManeuverIconType(type, modifier)
        )
    }

    private fun computeStepOffsets(
        steps: List<NavStep>,
        points: List<NavPoint>,
        cumDistances: DoubleArray,
        totalGeomMeters: Double
    ): DoubleArray {
        val offsets = DoubleArray(steps.size)
        if (steps.isEmpty()) return offsets

        offsets[0] = 0.0
        var prevOffset = 0.0
        for (i in 1 until steps.size) {
            val step = steps[i]
            val projected = projectPointOntoPolyline(
                lat = step.maneuverPoint.latitude,
                lon = step.maneuverPoint.longitude,
                points = points,
                cumDistances = cumDistances
            )
            val monotonic = if (projected.traveledMeters >= prevOffset - 5.0 &&
                projected.perpDistanceMeters <= 120f
            ) {
                projected.traveledMeters.coerceAtLeast(prevOffset)
            } else {
                (prevOffset + steps[i - 1].distanceMeters).coerceAtMost(totalGeomMeters)
            }
            offsets[i] = monotonic
            prevOffset = monotonic
        }
        if (steps.last().iconType == ManeuverIconType.ARRIVE) {
            offsets[steps.lastIndex] = totalGeomMeters
        }
        return offsets
    }

    private fun computePolylineLength(points: List<NavPoint>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += distanceBetween(points[i - 1], points[i])
        }
        return total
    }

    private fun distanceBetween(a: NavPoint, b: NavPoint): Double {
        return NavigationAssistCalculator.computeDistanceAndBearing(
            startLat = a.latitude,
            startLon = a.longitude,
            endLat = b.latitude,
            endLon = b.longitude
        ).first.toDouble()
    }

    private data class PolylineProjection(
        val segmentIndex: Int,
        val snappedPoint: NavPoint,
        val traveledMeters: Double,
        val perpDistanceMeters: Float
    )

    private fun projectPointOntoPolyline(
        lat: Double,
        lon: Double,
        points: List<NavPoint>,
        cumDistances: DoubleArray
    ): PolylineProjection {
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        var bestDist = Double.MAX_VALUE
        var bestSegIdx = 0
        var bestSnapped = points.first()
        var bestTraveled = 0.0

        for (i in 0 until points.lastIndex) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.longitude - lon) * METERS_PER_DEGREE_LAT * cosLat
            val ay = (a.latitude - lat) * METERS_PER_DEGREE_LAT
            val bx = (b.longitude - lon) * METERS_PER_DEGREE_LAT * cosLat
            val by = (b.latitude - lat) * METERS_PER_DEGREE_LAT

            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 1e-6) {
                0.0
            } else {
                ((-(ax * dx + ay * dy)) / lenSq).coerceIn(0.0, 1.0)
            }

            val px = ax + t * dx
            val py = ay + t * dy
            val dist = hypot(px, py)
            if (dist < bestDist) {
                bestDist = dist
                bestSegIdx = i
                val snapLat = a.latitude + t * (b.latitude - a.latitude)
                val snapLon = a.longitude + t * (b.longitude - a.longitude)
                bestSnapped = NavPoint(snapLat, snapLon)
                val segLen = cumDistances[i + 1] - cumDistances[i]
                bestTraveled = cumDistances[i] + t * segLen
            }
        }

        return PolylineProjection(
            segmentIndex = bestSegIdx,
            snappedPoint = bestSnapped,
            traveledMeters = bestTraveled,
            perpDistanceMeters = bestDist.toFloat()
        )
    }
}
