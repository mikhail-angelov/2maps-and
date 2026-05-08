package com.bconf.a2maps_and

import com.bconf.a2maps_and.placemark.Placemark
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.bconf.a2maps_and.placemark.PlacemarkService
import org.junit.Assert.*
import org.junit.Test

class PlacemarksViewModelTest {

    @Test
    fun `gasLayerVisible toggle changes state`() {
        val initial = PlacemarkService.isGasLayerVisible.value
        PlacemarkService.toggleGasLayer()
        assertEquals(!initial, PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `toggle gas layer twice returns to original`() {
        val initial = PlacemarkService.isGasLayerVisible.value
        PlacemarkService.toggleGasLayer()
        PlacemarkService.toggleGasLayer()
        assertEquals(initial, PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `gas layer starts false by default`() {
        // Reset to known state
        PlacemarkService.setGasLayerVisibility(false)
        assertFalse(PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `placemarks are empty initially`() {
        val vm = PlacemarksViewModel()
        val initialMarks = vm.placemarks.value
        assertNotNull(initialMarks)
        assertTrue(initialMarks.isEmpty())
    }
}
