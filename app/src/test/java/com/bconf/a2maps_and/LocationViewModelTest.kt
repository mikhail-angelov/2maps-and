package com.bconf.a2maps_and

import com.bconf.a2maps_and.location.LocationMode
import org.junit.Assert.*
import org.junit.Test

class LocationViewModelTest {

    @Test
    fun `initial state ordinal is INACTIVE`() {
        assertEquals(0, LocationMode.INACTIVE.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD is a distinct state from FOLLOW and RECORD`() {
        assertNotEquals(LocationMode.FOLLOW, LocationMode.FOLLOW_AND_RECORD)
        assertNotEquals(LocationMode.RECORD, LocationMode.FOLLOW_AND_RECORD)
    }

    @Test
    fun `enum has exactly 4 states`() {
        assertEquals(4, LocationMode.values().size)
    }

    @Test
    fun `FOLLOW_AND_RECORD can be parsed from string`() {
        assertEquals(LocationMode.FOLLOW_AND_RECORD, LocationMode.valueOf("FOLLOW_AND_RECORD"))
    }
}
