package com.bconf.a2maps_and

import com.bconf.a2maps_and.placemark.Placemark
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacemarkSerializationTest {

    @Test
    fun `placemark serializes to expected JSON`() {
        val placemark = Placemark(
            id = "test-id",
            name = "Test Place",
            latitude = 56.32,
            longitude = 43.99,
            rate = 3,
            description = "A nice spot",
            timestamp = 1626944504856L
        )
        val json = Gson().toJson(placemark)

        assertTrue(json.contains("\"id\":\"test-id\""))
        assertTrue(json.contains("\"name\":\"Test Place\""))
        assertTrue(json.contains("\"lat\":56.32"))
        assertTrue(json.contains("\"lng\":43.99"))
        assertTrue(json.contains("\"rate\":3"))
        assertTrue(json.contains("\"description\":\"A nice spot\""))
        assertTrue(json.contains("\"timestamp\":1626944504856"))
    }

    @Test
    fun `placemark deserializes from JSON`() {
        val json = """{
            "id": "test-id-2",
            "name": "Restored",
            "lat": 55.75,
            "lng": 37.62,
            "rate": null,
            "description": null,
            "timestamp": 1000000
        }"""
        val placemark = Gson().fromJson(json, Placemark::class.java)

        assertEquals("test-id-2", placemark.id)
        assertEquals("Restored", placemark.name)
        assertEquals(55.75, placemark.latitude, 0.001)
        assertEquals(37.62, placemark.longitude, 0.001)
        assertEquals(null, placemark.rate)
        assertEquals(null, placemark.description)
    }

    @Test
    fun `coordinates are lazily computed`() {
        val placemark = Placemark(
            id = "xyz",
            name = "Coord Test",
            latitude = 60.0,
            longitude = 30.0,
            rate = null,
            description = null,
            timestamp = 0L
        )
        assertEquals(60.0, placemark.coordinates.latitude, 0.001)
        assertEquals(30.0, placemark.coordinates.longitude, 0.001)
    }
}
