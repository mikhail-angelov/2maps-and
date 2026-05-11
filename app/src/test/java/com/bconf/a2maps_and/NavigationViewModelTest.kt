package com.bconf.a2maps_and

import com.bconf.a2maps_and.navigation.CenterOnLocationState
import org.junit.Assert.*
import org.junit.Test

class NavigationViewModelTest {

    @Test
    fun `initial state ordinal is INACTIVE`() {
        assertEquals(0, CenterOnLocationState.INACTIVE.ordinal)
    }

    @Test
    fun `FOLLOW_AND_RECORD is a distinct state from FOLLOW and RECORD`() {
        assertNotEquals(CenterOnLocationState.FOLLOW, CenterOnLocationState.FOLLOW_AND_RECORD)
        assertNotEquals(CenterOnLocationState.RECORD, CenterOnLocationState.FOLLOW_AND_RECORD)
    }

    @Test
    fun `enum has exactly 4 states`() {
        assertEquals(4, CenterOnLocationState.values().size)
    }

    @Test
    fun `FOLLOW_AND_RECORD can be parsed from string`() {
        assertEquals(CenterOnLocationState.FOLLOW_AND_RECORD, CenterOnLocationState.valueOf("FOLLOW_AND_RECORD"))
    }
}
