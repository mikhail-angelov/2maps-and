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
