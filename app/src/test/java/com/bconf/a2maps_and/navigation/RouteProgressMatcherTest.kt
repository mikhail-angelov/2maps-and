package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteProgressMatcherTest {
    private fun route(vararg points: GeoPoint) = Route(points.toList(), points.last(), 1000.0, null)
    private fun fix(lat: Double, lon: Double, bearing: Double? = null) = GpsFix(GeoPoint(lat, lon), 5.0, 1000, bearing, if (bearing != null) 10.0 else 0.0)
    @Test fun `invalid heading metadata does not spoil a good position`() {
        for (bearing in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val matcher = RouteProgressMatcher(route(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)))
            val match = matcher.match(fix(0.0, 0.005, bearing))
            assertEquals(0.0, match.crossTrackMeters, 0.01)
            assertEquals(556.0, match.alongMeters, 1.0)
        }
    }
    @Test fun `projects onto segment rather than nearest vertex`() {
        val matcher = RouteProgressMatcher(route(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)))
        val match = matcher.match(fix(0.001, 0.005))
        assertEquals(111.2, match.crossTrackMeters, 0.5)
        assertEquals(556.0, match.alongMeters, 1.0)
    }
    @Test fun `scales longitude at high latitudes and crosses antimeridian`() {
        val high = RouteProgressMatcher(route(GeoPoint(80.0, 0.0), GeoPoint(80.0, 0.01))).match(fix(80.0, 0.005))
        assertEquals(96.5, high.alongMeters, 1.0)
        val edge = RouteProgressMatcher(route(GeoPoint(0.0, 179.99), GeoPoint(0.0, -179.99))).match(fix(0.0, 180.0))
        assertEquals(1112.0, edge.alongMeters, 2.0)
        assertEquals(0.0, edge.crossTrackMeters, 0.01)
    }
    @Test fun `curve progress tolerates reverse movement and U turn`() {
        val matcher = RouteProgressMatcher(route(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01)))
        val before = matcher.match(fix(0.005, 0.01)).alongMeters
        val reverse = matcher.match(fix(0.0049, 0.01)).alongMeters
        assertTrue(before - reverse in 10.0..12.0)
        assertTrue(matcher.match(fix(0.0, 0.004)).alongMeters < before)
    }
    @Test fun `repeated and parallel roads prefer continuous progress and reliable direction`() {
        val matcher = RouteProgressMatcher(route(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.0001, 0.01), GeoPoint(0.0001, 0.0)))
        matcher.match(fix(0.0, 0.003, 90.0))
        val match = matcher.match(fix(0.00007, 0.004, 90.0))
        assertTrue(match.alongMeters < 600)
    }
}
