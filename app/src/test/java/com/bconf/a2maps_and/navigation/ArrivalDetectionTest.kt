package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class ArrivalDetectionTest {
    private val target = GeoPoint(56.0, 44.0)
    private fun fix(t: Long) = GpsFix(target, 5.0, t)
    @Test fun `initial heading cannot jump to a loop ending near the origin`() {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.01, 0.001), GeoPoint(0.01, 0.0), GeoPoint(0.0, 0.0001))
        val matcher = RouteProgressMatcher(Route(points, points.last(), 2300.0, null))
        val arrival = ArrivalDetection()
        for (t in 1000L..5000L step 1000) {
            val fix = GpsFix(points.first(), 5.0, t, 180.0, 10.0)
            val match = matcher.match(fix)
            assertTrue(match.alongMeters < 100)
            assertFalse(arrival.update(fix, match, points.last(), t))
        }
    }
    @Test fun `requires repeated good fixes near route end`() {
        val arrival = ArrivalDetection()
        val end = RouteMatch(0.0, 980.0, 1000.0)
        assertFalse(arrival.update(fix(1000), end, target, 1000))
        assertFalse(arrival.update(fix(2000), end, target, 2000))
        assertTrue(arrival.update(fix(3000), end, target, 3000))
    }
    @Test fun `loop near target partial route and poor GPS never declare arrival`() {
        val arrival = ArrivalDetection()
        for (t in 1000L..5000L step 1000) assertFalse(arrival.update(fix(t), RouteMatch(0.0, 200.0, 1000.0), target, t))
        assertFalse(arrival.update(fix(6000).copy(accuracyMeters = 100.0), RouteMatch(0.0, 1000.0, 1000.0), target, 6000))
        assertFalse(arrival.update(fix(7000), RouteMatch(0.0, 1000.0, 1000.0), GeoPoint(56.01, 44.0), 7000))
    }
}
