package com.bconf.a2maps_and

import android.os.Bundle
import android.view.View
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MapFragmentRobolectricTest {

    @Before
    fun setUp() {
        MapFragment.isMapRuntimeEnabled = false
        MapFragment.layoutResId = R.layout.fragment_map_robolectric
    }

    @After
    fun tearDown() {
        MapFragment.isMapRuntimeEnabled = true
        MapFragment.layoutResId = R.layout.fragment_map
    }

    @Test
    fun `launches map screen with primary controls hidden or visible as expected`() {
        val scenario = launchFragmentInContainer<MapFragment>(
            fragmentArgs = Bundle().apply {
                putFloat("placemarkLat", 999.0F)
                putFloat("placemarkLng", 999.0F)
            },
            themeResId = R.style.Theme__2mapsand
        )

        scenario.onFragment { fragment ->
            val root = fragment.requireView()

            assertNotNull(root.findViewById(R.id.mapViewInFragment))
            assertNotNull(root.findViewById(R.id.mainMenuButton))
            assertNotNull(root.findViewById(R.id.fabCenterOnLocationInFragment))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.fabToggleGasLayer).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabHideTrack).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialFollow).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialRecord).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialFollowRecord).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.trackStatsText).visibility)
        }
    }
}
