package com.bconf.a2maps_and

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapTrackStatsTest {

    @Test
    fun `formats empty track with zero distance and time`() {
        val stats = MapTrackStats.format(
            points = emptyList(),
            startedAtMillis = null,
            nowMillis = 10_000L
        )

        assertEquals("0 m | 00:00", stats)
    }

    @Test
    fun `formats elapsed time below one hour`() {
        val stats = MapTrackStats.format(
            points = emptyList(),
            startedAtMillis = 0L,
            nowMillis = 754_000L
        )

        assertEquals("0 m | 12:34", stats)
    }

    @Test
    fun `formats elapsed time above one hour`() {
        val stats = MapTrackStats.format(
            points = emptyList(),
            startedAtMillis = 0L,
            nowMillis = 3_723_000L
        )

        assertEquals("0 m | 1:02:03", stats)
    }

    @Test
    fun `calculates short track distance in meters`() {
        val points = listOf(
            LatLng(56.0, 44.0),
            LatLng(56.0009, 44.0)
        )

        val distanceMeters = MapTrackStats.calculateDistanceMeters(points)

        assertTrue(distanceMeters in 99.0..101.0)
    }

    @Test
    fun `formats longer track distance in kilometers`() {
        val stats = MapTrackStats.format(
            points = listOf(
                LatLng(56.0, 44.0),
                LatLng(56.009, 44.0)
            ),
            startedAtMillis = 0L,
            nowMillis = 1_000L
        )

        assertEquals("1.00 km | 00:01", stats)
    }
}
