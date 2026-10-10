package com.bconf.a2maps_and.home

import com.bconf.a2maps_and.navigation.GeoPoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HomeStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun `home is unset until saved and a new point replaces the old one`() {
        val store = HomeStore(context)
        assertNull(store.home)
        store.home = GeoPoint(56.326887, 44.005986)
        assertEquals(GeoPoint(56.326887, 44.005986), HomeStore(context).home)
        store.home = GeoPoint(56.2965, 43.9955)
        assertEquals(GeoPoint(56.2965, 43.9955), HomeStore(context).home)
        store.home = null
        assertNull(HomeStore(context).home)
    }
}
