package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class RerouteSelectorTest {
    private val here = GeoPoint(56.31, 44.01)
    private val west = GeoPoint(56.31, 44.00)
    private val east = GeoPoint(56.31, 44.02)
    private val target = GeoPoint(56.32, 44.01)
    private val back = Route(listOf(here, west, target), target, 2000.0, 200.0)
    private val forward = Route(listOf(here, east, target), target, 2100.0, 210.0)
    private val eastCourse = 90.0

    @Test fun `candidate continuing the course wins over one heading back`() {
        assertEquals(1, RerouteSelector.select(listOf(back, forward), eastCourse))
        assertTrue(RerouteSelector.canImprove(back, eastCourse))
    }

    @Test fun `similar candidates keep the main route`() {
        val alsoForward = Route(listOf(here, GeoPoint(56.3105, 44.02), target), target, 2050.0, 205.0)
        assertEquals(0, RerouteSelector.select(listOf(forward, alsoForward), eastCourse))
        assertFalse(RerouteSelector.canImprove(forward, eastCourse))
    }

    @Test fun `missing course keeps the main route`() {
        assertEquals(0, RerouteSelector.select(listOf(back, forward), null))
        assertFalse(RerouteSelector.canImprove(back, null))
        assertNull(GpsFix(here, 5.0, 1000, bearingDegrees = 90.0, speedMetersPerSecond = 1.0).courseDegrees)
        assertNull(GpsFix(here, 5.0, 1000, bearingDegrees = null, speedMetersPerSecond = 15.0).courseDegrees)
    }

    @Test fun `immediate U-turn is penalised even when the line looks straight`() {
        val uTurn = forward.copy(maneuvers = listOf(RouteManeuver(0, ManeuverType.U_TURN)))
        assertEquals(1, RerouteSelector.select(listOf(uTurn, forward), eastCourse))
    }

    @Test fun `point along the route lies on its segment`() {
        val points = listOf(here, east, target)
        val cumulative = cumulativeMeters(points)
        val middle = pointAlong(points, cumulative, cumulative[1] / 2)
        assertEquals(here.latitude, middle.latitude, 1e-9)
        assertEquals((here.longitude + east.longitude) / 2, middle.longitude, 1e-9)
        assertEquals(0.0, distanceMeters(target, pointAlong(points, cumulative, 1e9)), 1e-6)
        assertEquals(0.0, distanceMeters(here, pointAlong(points, cumulative, -5.0)), 1e-6)
    }
}
