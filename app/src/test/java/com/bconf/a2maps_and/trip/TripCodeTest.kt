package com.bconf.a2maps_and.trip

import com.bconf.a2maps_and.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test

class TripCodeTest {
    @Test fun `parses the Mamon QR text with names containing commas`() {
        val trip = TripCode.parse("2MAPS-TRIP\nПоля 10.10\n56.61868,43.71380,Поле, у реки\n\n56.5,43.9\n", now = 42)!!
        assertEquals("Поля 10.10", trip.name)
        assertEquals(42, trip.createdAt)
        assertEquals(listOf(TripPoint(56.61868, 43.7138, "Поле, у реки"), TripPoint(56.5, 43.9, "")), trip.points)
    }

    @Test fun `rejects other QR codes and broken coordinates`() {
        assertNull(TripCode.parse("https://example.com"))
        assertNull(TripCode.parse("2MAPS-TRIP\nName"))
        assertNull(TripCode.parse("2MAPS-TRIP\nName\n56.6,x,A"))
        assertNull(TripCode.parse("2MAPS-TRIP\nName\n95,43,A"))
    }

    @Test fun `nearest open point is chosen, skipping visited and excluded ones`() {
        val trip = Trip("t", "T", 0, listOf(
            TripPoint(56.0, 44.0, "far"), TripPoint(56.30, 44.0, "visited", visited = true),
            TripPoint(56.31, 44.0, "near"), TripPoint(56.2, 44.0, "middle")))
        val here = GeoPoint(56.3, 44.0)
        assertEquals(2, trip.nearestIndex(here))
        assertEquals(3, trip.nearestIndex(here, setOf(2)))
        assertNull(trip.nearestIndex(here, setOf(0, 2, 3)))
        assertEquals(0, trip.nearestIndex(null))
    }
}
