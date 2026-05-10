package com.bconf.a2maps_and.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PlacemarkUtils.
 * These are pure JVM tests and don't require Android emulator.
 */
class PlacemarkUtilsTest {

    @Test
    fun `formatDistanceForDisplay returns meters for distances under 1 km`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(500.0)
        assertEquals("500 m", result)
    }

    @Test
    fun `formatDistanceForDisplay returns kilometers for distances over 1 km`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(1500.0)
        assertEquals("1.5 km", result)
    }

    @Test
    fun `formatDistanceForDisplay rounds to nearest 10m between 50m and 999m`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(153.0)
        assertEquals("150 m", result)
    }

    @Test
    fun `formatDistanceForDisplay rounds to nearest 5m between 10m and 49m`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(23.0)
        assertEquals("25 m", result)
    }

    @Test
    fun `formatDistanceForDisplay shows exact meters for values under 10m`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(7.5)
        assertEquals("8 m", result)
    }

    @Test
    fun `formatDistanceForDisplay shows Now for very small distances`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(0.5)
        assertEquals("Now", result)
    }

    @Test
    fun `formatDistanceForDisplay shows integer km for distances over 10 km`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(15000.0)
        assertEquals("15 km", result)
    }

    @Test
    fun `formatDistanceForDisplay handles zero distance`() {
        val result = PlacemarkUtils.formatDistanceForDisplay(0.0)
        assertEquals("0 m", result)
    }
}
