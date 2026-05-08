package com.bconf.a2maps_and

import com.bconf.a2maps_and.navigation.CenterOnLocationState
import com.bconf.a2maps_and.navigation.NavigationViewModel
import org.junit.Assert.*
import org.junit.Test

class NavigationViewModelTest {

    @Test
    fun `initial state should be INACTIVE`() {
        val vm = NavigationViewModel()
        assertEquals(CenterOnLocationState.INACTIVE, vm.centerOnLocationState.value)
    }

    @Test
    fun `toggle states cycles correctly`() {
        val vm = NavigationViewModel()

        // Start INACTIVE
        assertEquals(CenterOnLocationState.INACTIVE, vm.centerOnLocationState.value)

        // Set to FOLLOW
        vm.centerOnLocationState.value = CenterOnLocationState.FOLLOW
        assertEquals(CenterOnLocationState.FOLLOW, vm.centerOnLocationState.value)

        // Set to FOLLOW_AND_RECORD directly
        vm.centerOnLocationState.value = CenterOnLocationState.FOLLOW_AND_RECORD
        assertEquals(CenterOnLocationState.FOLLOW_AND_RECORD, vm.centerOnLocationState.value)

        // Reset to INACTIVE
        vm.centerOnLocationState.value = CenterOnLocationState.INACTIVE
        assertEquals(CenterOnLocationState.INACTIVE, vm.centerOnLocationState.value)
    }

    @Test
    fun `RECORD state is accessible`() {
        val vm = NavigationViewModel()
        vm.centerOnLocationState.value = CenterOnLocationState.RECORD
        assertEquals(CenterOnLocationState.RECORD, vm.centerOnLocationState.value)
    }

    @Test
    fun `FOLLOW_AND_RECORD is a distinct state`() {
        assertNotEquals(CenterOnLocationState.FOLLOW, CenterOnLocationState.FOLLOW_AND_RECORD)
        assertNotEquals(CenterOnLocationState.RECORD, CenterOnLocationState.FOLLOW_AND_RECORD)
        assertNotEquals(CenterOnLocationState.INACTIVE, CenterOnLocationState.FOLLOW_AND_RECORD)
    }

    @Test
    fun `values enum has FOLLOW_AND_RECORD`() {
        val values = CenterOnLocationState.values()
        assertTrue(values.contains(CenterOnLocationState.FOLLOW_AND_RECORD))
    }

    @Test
    fun `values size is 4`() {
        assertEquals(4, CenterOnLocationState.values().size)
    }
}
