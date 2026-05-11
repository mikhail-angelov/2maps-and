package com.bconf.a2maps_and

import com.bconf.a2maps_and.placemark.PlacemarkService
import org.junit.Assert.*
import org.junit.Test

class PlacemarksViewModelTest {

    @Test
    fun `gas layer is false by default`() {
        assertFalse(PlacemarkService.isGasLayerVisible.value)
    }

    @Test
    fun `placemarks StateFlow is empty by default`() {
        assertTrue(PlacemarkService.placemarks.value.isEmpty())
    }

    @Test
    fun `gas stations StateFlow is empty by default`() {
        assertTrue(PlacemarkService.gasStations.value.isEmpty())
    }
}
