package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteGuidanceTest {
    private val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001), GeoPoint(0.002, 0.001))
    private val route = Route(points, points.last(), 334.0, 60.0,
        listOf(RouteManeuver(1, ManeuverType.LEFT), RouteManeuver(2, ManeuverType.ROUNDABOUT, 3)),
        listOf(RouteRoadSection(0, 1, 60), RouteRoadSection(1, 2, null), RouteRoadSection(2, 3, 30)))
    @Test fun distanceFallsAndPassedManeuverAdvances() {
        val guidance = RouteGuidance(route)
        assertEquals(ManeuverType.LEFT, guidance.at(0.0).upcomingManeuver!!.maneuver.type)
        assertEquals(61.2, guidance.at(50.0).upcomingManeuver!!.distanceMeters, 0.5)
        assertEquals(ManeuverType.ROUNDABOUT, guidance.at(130.0).upcomingManeuver!!.maneuver.type)
        assertEquals(3, guidance.at(130.0).upcomingManeuver!!.maneuver.exitNumber)
        assertEquals(ManeuverType.FINISH, guidance.at(250.0).upcomingManeuver!!.maneuver.type)
    }
    @Test fun limitClearsOnUnknownRoadAndChangesAtBoundary() {
        val guidance = RouteGuidance(route)
        assertEquals(60, guidance.at(50.0).maxSpeedKmh)
        assertNull(guidance.at(120.0).maxSpeedKmh)
        assertEquals(30, guidance.at(250.0).maxSpeedKmh)
    }
}
