package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class ReroutePolicyTest {
    private fun fix(t: Long, accuracy: Double = 5.0) = GpsFix(GeoPoint(56.0, 44.0), accuracy, t)
    private val off = RouteMatch(150.0, 100.0, 1000.0)
    @Test fun `requires three samples spanning three seconds and cooldown between starts`() {
        val policy = ReroutePolicy()
        policy.started(1000)
        assertFalse(policy.shouldReroute(fix(11000), off, 11000, false))
        assertFalse(policy.shouldReroute(fix(12000), off, 12000, false))
        assertFalse(policy.shouldReroute(fix(13000), off, 13000, false))
        assertTrue(policy.shouldReroute(fix(14000), off, 14000, false))
        policy.started(14000)
        for (t in listOf(15000L, 16000L, 17000L, 18000L)) assertFalse(policy.shouldReroute(fix(t), off, t, false))
        assertTrue(policy.shouldReroute(fix(24000), off, 24000, false))
    }
    @Test fun `busy jitter stale missing and inaccurate fixes do not reroute`() {
        val policy = ReroutePolicy()
        for (t in 1000L..6000L step 1000) assertFalse(policy.shouldReroute(fix(t), off, t, true))
        assertFalse(policy.shouldReroute(fix(7000, 100.0), off, 7000, false))
        assertFalse(policy.shouldReroute(fix(8000), off, 8000, false))
        assertFalse(policy.shouldReroute(fix(9000), RouteMatch(20.0, 0.0, 1000.0), 9000, false))
        assertFalse(policy.shouldReroute(fix(1000), off, 18000, false))
        assertFalse(GpsFix(GeoPoint(0.0, 0.0), 1.0, 0).isGood(1000))
    }
}
