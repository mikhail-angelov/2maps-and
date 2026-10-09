package com.bconf.a2maps_and.navigation

class RouteGuidance(private val route: Route) {
    private val cumulative = DoubleArray(route.points.size)
    init {
        for (i in 1 until cumulative.size) cumulative[i] = cumulative[i - 1] + distanceMeters(route.points[i - 1], route.points[i])
    }

    fun at(alongMeters: Double): RouteGuidanceInfo {
        val along = alongMeters.coerceIn(0.0, cumulative.last())
        val next = route.maneuvers.firstOrNull { cumulative[it.pointIndex] + 10 >= along }
            ?: RouteManeuver(route.points.lastIndex, ManeuverType.FINISH)
        val upcoming = UpcomingManeuver(next, (cumulative[next.pointIndex] - along).coerceAtLeast(0.0))
        val road = route.roadSections.firstOrNull { along >= cumulative[it.fromPointIndex] && along < cumulative[it.toPointIndex] }
            ?: route.roadSections.lastOrNull()?.takeIf { along >= cumulative.last() }
        return RouteGuidanceInfo(upcoming, road?.maxSpeedKmh)
    }
}
