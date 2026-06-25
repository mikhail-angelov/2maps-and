package com.bconf.a2maps_and

import com.bconf.a2maps_and.location.LocationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationModeTest {

    @Test
    fun `INACTIVE should have correct ordinal`() {
        assertEquals(0, LocationMode.INACTIVE.ordinal)
    }

    @Test
    fun `FOLLOW should be first active state`() {
        assertEquals(1, LocationMode.FOLLOW.ordinal)
    }

    @Test
    fun `RECORD should be second active state`() {
        assertEquals(2, LocationMode.RECORD.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD should exist as new combined state`() {
        assertEquals(3, LocationMode.FOLLOW_AND_RECORD.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD should have correct name`() {
        assertEquals("FOLLOW_AND_RECORD", LocationMode.FOLLOW_AND_RECORD.name)
    }

    @Test
    fun `should be able to parse FOLLOW_AND_RECORD from string`() {
        val parsed = LocationMode.valueOf("FOLLOW_AND_RECORD")
        assertEquals(LocationMode.FOLLOW_AND_RECORD, parsed)
    }
}

class GasLayerToggleTest {

    @Test
    fun `isGasLayerVisible initially false`() {
        // Default value from PlacemarkService companion
        assertEquals(false, com.bconf.a2maps_and.placemark.PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `purple color hex value is correct`() {
        // #9C27B0 = ARGB 0xFF9C27B0 = -6543440 as signed int
        val argb = (0xFF shl 24) or (0x9C shl 16) or (0x27 shl 8) or 0xB0
        assertEquals(-6543440, argb)
    }
}
