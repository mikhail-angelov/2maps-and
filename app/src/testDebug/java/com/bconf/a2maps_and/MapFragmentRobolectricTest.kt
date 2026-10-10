package com.bconf.a2maps_and

import android.os.Bundle
import android.view.View
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.widget.TextView
import com.bconf.a2maps_and.navigation.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
            assertNotNull(root.findViewById(R.id.fabHome))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.fabCompass).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.fabToggleGasLayer).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabHideTrack).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialFollow).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialRecord).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.fabRadialFollowRecord).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.trackStatsText).visibility)
        }
    }

    @Test
    fun `navigation panel renders selection, driving and stale states`() {
        val scenario = launchFragmentInContainer<MapFragment>(
            fragmentArgs = Bundle().apply {
                putFloat("placemarkLat", 999.0F)
                putFloat("placemarkLng", 999.0F)
            },
            themeResId = R.style.Theme__2mapsand
        )
        scenario.onFragment { fragment ->
            val root = fragment.requireView()
            var selected = -1
            var recentered = 0
            val panel = NavigationPanel(root, {}, {}, {}, { selected = it }, {}, { recentered++ }, {})
            val a = GeoPoint(56.29, 43.98); val b = GeoPoint(56.30, 44.00)
            val main = Route(listOf(a, b), b, 8400.0, 720.0)
            val other = Route(listOf(a, GeoPoint(56.29, 44.0), b), b, 9000.0, 900.0)
            panel.render(NavigationState(NavigationPhase.SELECTING, b, main, 8400.0, 720.0,
                options = listOf(main, other), searchingAlternatives = true), true)
            val options = root.findViewById<android.view.ViewGroup>(R.id.navigationOptions)
            assertEquals(2, options.childCount)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigationGo).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.navigationManeuverCard).visibility)
            options.getChildAt(1).performClick()
            assertEquals(1, selected)

            val driving = NavigationState(NavigationPhase.ACTIVE, b, main, 350.0, 60.0,
                upcomingManeuver = UpcomingManeuver(RouteManeuver(1, ManeuverType.RIGHT), 347.0), maxSpeedKmh = 60,
                progressMeters = 8050.0, arrivalAtMillis = 0L, speedKmh = 54)
            panel.render(driving, true)
            assertTrue(root.findViewById<TextView>(R.id.navigationSummary).text.toString().startsWith("350 m"))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigationRouteProgress).visibility)
            val speed = root.findViewById<TextView>(R.id.navigationCurrentSpeed)
            assertEquals("54", speed.text.toString())
            assertEquals(fragment.requireContext().getColor(R.color.nav_text), speed.currentTextColor)
            panel.render(driving.copy(speedKmh = 72), true)
            assertEquals(fragment.requireContext().getColor(R.color.nav_speeding), speed.currentTextColor)
            panel.render(driving.copy(maxSpeedKmh = null), true)
            assertEquals(View.GONE, root.findViewById<View>(R.id.navigationSpeedLimit).visibility)
            assertEquals(View.VISIBLE, speed.visibility)
            panel.render(driving, true)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigationManeuverCard).visibility)
            assertEquals("350 m", root.findViewById<TextView>(R.id.navigationManeuverDistance).text.toString())
            assertEquals("60", root.findViewById<TextView>(R.id.navigationSpeedLimit).text.toString())
            assertEquals(View.GONE, root.findViewById<View>(R.id.navigationGo).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.navigationStop).visibility)
            root.findViewById<View>(R.id.navigationStop).performClick()
            assertEquals("Stop is not a re-centre tap", 0, recentered)
            panel.bottomCard.performClick()
            assertEquals(1, recentered)

            panel.render(driving.copy(waitingForGps = true, maxSpeedKmh = null), true)
            assertEquals("Position is not updating", root.findViewById<TextView>(R.id.navigationStatus).text.toString())
            assertEquals(View.GONE, root.findViewById<View>(R.id.navigationSpeedLimit).visibility)
        }
    }
}
