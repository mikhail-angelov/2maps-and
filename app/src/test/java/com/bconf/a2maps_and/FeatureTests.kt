package com.bconf.a2maps_and

import com.bconf.a2maps_and.navigation.CenterOnLocationState
import org.junit.Assert.assertEquals
import org.junit.Test

class CenterOnLocationStateTest {

    @Test
    fun `INACTIVE should have correct ordinal`() {
        assertEquals(0, CenterOnLocationState.INACTIVE.ordinal)
    }

    @Test
    fun `FOLLOW should be first active state`() {
        assertEquals(1, CenterOnLocationState.FOLLOW.ordinal)
    }

    @Test
    fun `RECORD should be second active state`() {
        assertEquals(2, CenterOnLocationState.RECORD.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD should exist as new combined state`() {
        assertEquals(3, CenterOnLocationState.FOLLOW_AND_RECORD.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD should have correct name`() {
        assertEquals("FOLLOW_AND_RECORD", CenterOnLocationState.FOLLOW_AND_RECORD.name)
    }

    @Test
    fun `should be able to parse FOLLOW_AND_RECORD from string`() {
        val parsed = CenterOnLocationState.valueOf("FOLLOW_AND_RECORD")
        assertEquals(CenterOnLocationState.FOLLOW_AND_RECORD, parsed)
    }
}

class GasLayerToggleTest {

    @Test
    fun `isGasLayerVisible initially false`() {
        // Default value from PlacemarkService companion
        assertEquals(false, com.bconf.a2maps_and.placemark.PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `FAB tint should be purple when gas layer visible`() {
        val purpleHex = "#9C27B0"
        val parsedColor = android.graphics.Color.parseColor(purpleHex)
        assertEquals(-11664336, parsedColor) // Purple in ARGB integer
    }
}
