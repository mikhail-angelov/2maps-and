package com.bconf.a2maps_and.navigation

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    fun isValid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
}

internal fun longitudeDelta(degrees: Double): Double = ((degrees + 540.0) % 360.0) - 180.0

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.latitude - a.latitude)
    val lon = Math.toRadians(longitudeDelta(b.longitude - a.longitude))
    val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
    return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Initial great-circle bearing from [a] to [b], 0..360 clockwise from north. */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val lon = Math.toRadians(longitudeDelta(b.longitude - a.longitude))
    val y = sin(lon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(lon)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/** Distance from the first point to every vertex. */
fun cumulativeMeters(points: List<GeoPoint>): DoubleArray {
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + distanceMeters(points[i - 1], points[i])
    return cumulative
}

/** Segment index and the fraction within it at [along] metres of a [cumulativeMeters] array. */
fun segmentAt(cumulative: DoubleArray, along: Double): Pair<Int, Double> {
    if (cumulative.size < 2) return 0 to 0.0
    val target = along.coerceIn(0.0, cumulative.last())
    val index = cumulative.binarySearch(target).let { if (it >= 0) it else -it - 2 }.coerceIn(0, cumulative.size - 2)
    val length = cumulative[index + 1] - cumulative[index]
    return index to if (length > 0) (target - cumulative[index]) / length else 0.0
}

/** Point [along] metres from the start of [points]; [cumulative] is their [cumulativeMeters]. */
fun pointAlong(points: List<GeoPoint>, cumulative: DoubleArray, along: Double): GeoPoint {
    if (points.size < 2) return points.first()
    val (index, t) = segmentAt(cumulative, along)
    return interpolate(points[index], points[index + 1], t)
}

fun interpolate(a: GeoPoint, b: GeoPoint, t: Double) =
    GeoPoint(a.latitude + (b.latitude - a.latitude) * t, a.longitude + longitudeDelta(b.longitude - a.longitude) * t)

data class Route(val points: List<GeoPoint>, val destination: GeoPoint,
                 val distanceMeters: Double, val estimatedDurationSeconds: Double?,
                 val maneuvers: List<RouteManeuver> = emptyList(),
                 val roadSections: List<RouteRoadSection> = emptyList())

enum class ManeuverType { STRAIGHT, LEFT, SLIGHT_LEFT, SHARP_LEFT, RIGHT, SLIGHT_RIGHT,
    SHARP_RIGHT, KEEP_LEFT, KEEP_RIGHT, U_TURN, ROUNDABOUT, REJOIN, CONTINUE, EXIT_LEFT, EXIT_RIGHT, FINISH }
data class RouteManeuver(val pointIndex: Int, val type: ManeuverType, val exitNumber: Int = 0)
data class RouteRoadSection(val fromPointIndex: Int, val toPointIndex: Int, val maxSpeedKmh: Int?)
data class UpcomingManeuver(val maneuver: RouteManeuver, val distanceMeters: Double)
data class RouteGuidanceInfo(val upcomingManeuver: UpcomingManeuver?, val maxSpeedKmh: Int?)

data class GpsFix(val point: GeoPoint, val accuracyMeters: Double, val elapsedMillis: Long,
                  val bearingDegrees: Double? = null, val speedMetersPerSecond: Double = 0.0) {
    fun isGood(now: Long) = point.isValid() && elapsedMillis > 0 && now - elapsedMillis in 0..15000 &&
        accuracyMeters.isFinite() && accuracyMeters in 0.0..50.0

    /** Direction of travel when GPS reports one while moving faster than walking pace, as for the position arrow. */
    val courseDegrees get() = bearingDegrees?.takeIf { it.isFinite() && speedMetersPerSecond.isFinite() && speedMetersPerSecond > 3 }
}

enum class RoutingFailure { UNAVAILABLE, SETUP, INVALID_RESPONSE, TOO_LARGE, CONNECTION, TIMEOUT }
class RoutingException(val kind: RoutingFailure, message: String) : Exception(message)

enum class NavigationPhase { IDLE, WAITING_POSITION, CALCULATING, SELECTING, ACTIVE, ERROR, ARRIVED }
data class NavigationState(
    val phase: NavigationPhase = NavigationPhase.IDLE,
    val destination: GeoPoint? = null,
    val route: Route? = null,
    val remainingMeters: Double = 0.0,
    val remainingSeconds: Double? = null,
    val isRerouting: Boolean = false,
    val waitingForGps: Boolean = false,
    val failure: RoutingFailure? = null,
    val message: String? = null,
    val upcomingManeuver: UpcomingManeuver? = null,
    val maxSpeedKmh: Int? = null,
    /** Route choices offered before driving; [route] is the selected one. */
    val options: List<Route> = emptyList(),
    val selectedOption: Int = 0,
    val searchingAlternatives: Boolean = false,
    /** Distance along the route geometry confirmed by an accurate on-route fix; null until the first one. */
    val progressMeters: Double? = null,
    /** Wall-clock arrival estimate; frozen while the position is stale. */
    val arrivalAtMillis: Long? = null,
    /** Current GPS speed while guiding; null when unknown. */
    val speedKmh: Int? = null,
    /** Where to draw the car while guiding on the route; null means the raw GPS position. */
    val snappedPosition: GeoPoint? = null,
) {
    /** GPS was lost after the route progress had been confirmed. */
    val positionStale get() = waitingForGps && progressMeters != null
    val needsGps get() = phase == NavigationPhase.WAITING_POSITION || phase == NavigationPhase.CALCULATING || phase == NavigationPhase.ACTIVE || (phase == NavigationPhase.ERROR && route != null)
}

// Injection seam for controller tests; production uses only the local Binder provider.
fun interface RoutingEngine {
    /** [alternative] 0 is the main route; 1..3 are BRouter alternatives for the same request. */
    suspend fun route(origin: GeoPoint, destination: GeoPoint, alternative: Int): Route
}
