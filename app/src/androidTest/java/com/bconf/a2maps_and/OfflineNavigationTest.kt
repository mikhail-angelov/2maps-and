package com.bconf.a2maps_and

import android.Manifest
import android.location.Location
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.bconf.a2maps_and.navigation.*
import com.bconf.a2maps_and.location.LocationMode
import com.bconf.a2maps_and.location.LocationService
import com.bconf.a2maps_and.location.LocationViewModel
import com.bconf.a2maps_and.placemark.Placemark
import com.bconf.a2maps_and.placemark.PlacemarkModal
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/** BRouter integration requires manual data/profile setup; see docs/navigation/README.md. */
@RunWith(AndroidJUnit4::class)
class OfflineNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val origin = GeoPoint(56.292374, 43.985402)
    private val target = GeoPoint(56.2965, 43.9955)

    @Before fun permissions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("appops set ${context.packageName} android:mock_location allow")
    }

    @Test fun localBinderBuildsRealCarRoute() = runBlocking {
        requireBRouter()
        val start = SystemClock.elapsedRealtime()
        val route = BRouterClient(context).route(origin, target)
        assertTrue(route.points.size > 2)
        assertTrue(route.distanceMeters > 100)
        assertNotNull(route.estimatedDurationSeconds)
        assertTrue(distanceMeters(route.points.first(), origin) < 250)
        assertTrue(distanceMeters(route.points.last(), target) < 250)
        println("Offline Binder route: ${route.points.size} points, ${route.distanceMeters.toInt()} m, ${SystemClock.elapsedRealtime() - start} ms")
    }

    @Test fun routeCrosses45DegreeTileBoundary() = runBlocking {
        requireBRouter()
        val start = SystemClock.elapsedRealtime()
        val route = BRouterClient(context).route(GeoPoint(56.028, 44.995), GeoPoint(56.030, 45.015))
        assertTrue(route.points.any { it.longitude < 45 })
        assertTrue(route.points.any { it.longitude >= 45 })
        assertTrue(route.distanceMeters > 500)
        println("Inter-tile route: ${route.points.size} points, ${route.distanceMeters.toInt()} m, ${SystemClock.elapsedRealtime() - start} ms")
    }

    @Test fun mapShowsRealRouteReroutesSurvivesStyleAndRecreationAndPreservesRecording() {
        requireBRouter()
        val imported = File(context.filesDir, "maps/integration-map.mbtiles")
        imported.parentFile!!.mkdirs()
        context.assets.open("planet.mbtiles").use { input -> imported.outputStream().use { input.copyTo(it) } }
        Tasks.await(fused.setMockMode(true))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: NavigationViewModel
                lateinit var location: LocationViewModel
                scenario.onActivity {
                    location = ViewModelProvider(it)[LocationViewModel::class.java]
                    location.setLocationModeFromMenu(LocationMode.RECORD)
                    vm = ViewModelProvider(it)[NavigationViewModel::class.java]
                    vm.start(target)
                }
                mockPosition(origin)
                waitForRoute(vm)
                assertLayers(scenario)
                onView(withId(R.id.navigationPanel)).check(matches(isDisplayed()))
                onView(withId(R.id.navigationStop)).check(matches(isDisplayed()))
                onView(withId(R.id.mainMenuButton)).perform(click())
                onView(withText("integration-map")).perform(click())
                SystemClock.sleep(1500)
                assertLayers(scenario)
                scenario.recreate()
                assertLayers(scenario)
                assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
                val oldRoute = vm.state.value.route!!
                for (index in listOf(oldRoute.points.size / 4, oldRoute.points.size / 2)) {
                    mockPosition(oldRoute.points[index])
                    SystemClock.sleep(1200)
                }
                assertTrue(vm.state.value.remainingMeters < oldRoute.distanceMeters)
                // One accurate spike and poor accuracy must preserve the current route.
                val departure = GeoPoint(56.305, 43.980)
                mockPosition(departure)
                SystemClock.sleep(1200)
                mockPosition(oldRoute.points[oldRoute.points.size / 2], accuracy = 150f)
                SystemClock.sleep(1200)
                mockPosition(oldRoute.points[oldRoute.points.size / 2])
                SystemClock.sleep(1200)
                assertSame(oldRoute, vm.state.value.route)
                SystemClock.sleep(11000)
                for (sample in 0..3) {
                    mockPosition(departure)
                    SystemClock.sleep(1200)
                }
                waitForReplacement(vm, oldRoute)
                assertEquals(target, vm.state.value.route!!.destination)
                assertLayers(scenario)
                val recordedCount = LocationService.recordedPath.value.size
                assertTrue(recordedCount > 0)
                scenario.onActivity {
                    it.findViewById<MapView>(R.id.mapViewInFragment).getMapAsync { map ->
                        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(departure.latitude, departure.longitude), 13.0))
                    }
                }
                SystemClock.sleep(500)
                shell("screencap -p /data/local/tmp/2maps-navigation.png")
                onView(withId(R.id.navigationStop)).perform(click())
                assertEquals(NavigationPhase.IDLE, vm.state.value.phase)
                assertEquals(LocationMode.RECORD, location.locationMode.value)
                assertTrue(LocationService.recordedPath.value.size >= recordedCount)
                scenario.onActivity { location.setLocationModeFromMenu(LocationMode.INACTIVE) }
                val gpx = File(context.filesDir, "gpx").listFiles()!!.maxBy { it.lastModified() }
                SystemClock.sleep(250)
                assertTrue(gpx.readText().contains("<trkpt"))
                println("Recording retained $recordedCount points across navigation and reroute; Stop preserved recording")
            }
        } finally {
            Tasks.await(fused.setMockMode(false))
        }
    }

    @Test fun placemarkListStartsOfflineNavigationAndReturnsToMap() {
        requireBRouter()
        Tasks.await(fused.setMockMode(true))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: NavigationViewModel
                val placemark = Placemark(name = "Offline fixture", latitude = target.latitude, longitude = target.longitude,
                    description = "", rate = 0, timestamp = System.currentTimeMillis())
                scenario.onActivity {
                    vm = ViewModelProvider(it)[NavigationViewModel::class.java]
                    ViewModelProvider(it)[PlacemarksViewModel::class.java].addPlacemark(placemark)
                }
                mockPosition(origin)
                SystemClock.sleep(1500)
                scenario.onActivity {
                    val host = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    host.navController.navigate(R.id.action_mapFragment_to_mainMenuFragment)
                    host.navController.navigate(R.id.action_mainMenuFragment_to_placemarksFragment)
                }
                SystemClock.sleep(500)
                onView(withText("Offline fixture")).perform(click())
                onView(withId(R.id.navigateButton)).perform(click())
                onView(withText("Offline (BRouter)")).perform(click())
                mockPosition(origin)
                waitForRoute(vm)
                scenario.onActivity {
                    val host = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                    assertEquals(R.id.mapFragment, host.navController.currentDestination!!.id)
                }
                assertLayers(scenario)
                onView(withId(R.id.navigationStop)).perform(click())
                scenario.onActivity { ViewModelProvider(it)[PlacemarksViewModel::class.java].deletePlacemark(placemark.id) }
            }
        } finally {
            Tasks.await(fused.setMockMode(false))
        }
    }

    @Test fun longPressRetainsExternalChoicesAndOffersOfflineRouting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ready = CountDownLatch(1)
            scenario.onActivity { it.findViewById<MapView>(R.id.mapViewInFragment).getMapAsync { ready.countDown() } }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            onView(withId(R.id.mapViewInFragment)).perform(longClick())
            onView(withText("Navigate to")).perform(click())
            onView(withText("Yandex Navigator")).check(matches(isDisplayed()))
            onView(withText("OsmAnd")).check(matches(isDisplayed()))
            onView(withText("Offline (BRouter)")).check(matches(isDisplayed()))
            pressBack()
        }
    }

    private fun mockPosition(point: GeoPoint, accuracy: Float = 5f) {
        Tasks.await(fused.setMockLocation(Location("fused").apply {
            latitude = point.latitude
            longitude = point.longitude
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }))
    }

    private fun requireBRouter() = assumeTrue("Install BRouter and road data for this test",
        context.packageManager.getLaunchIntentForPackage("btools.routingapp") != null)

    private fun shell(command: String) {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun waitForReplacement(vm: NavigationViewModel, previous: Route) {
        val limit = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < limit && vm.state.value.route === previous && vm.state.value.phase != NavigationPhase.ERROR) SystemClock.sleep(250)
        assertNotSame(vm.state.value.message, previous, vm.state.value.route)
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
    }

    private fun waitForRoute(vm: NavigationViewModel) {
        val limit = SystemClock.elapsedRealtime() + 45000
        while (SystemClock.elapsedRealtime() < limit && vm.state.value.phase != NavigationPhase.ACTIVE && vm.state.value.phase != NavigationPhase.ERROR) SystemClock.sleep(250)
        assertEquals(vm.state.value.message, NavigationPhase.ACTIVE, vm.state.value.phase)
    }

    private fun assertLayers(scenario: ActivityScenario<MainActivity>) {
        val latch = CountDownLatch(1)
        var map: MapLibreMap? = null
        scenario.onActivity { it.findViewById<MapView>(R.id.mapViewInFragment).getMapAsync { ready -> map = ready; latch.countDown() } }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        val limit = SystemClock.elapsedRealtime() + 15000
        var loaded = false
        while (!loaded && SystemClock.elapsedRealtime() < limit) {
            scenario.onActivity { loaded = map?.style?.getLayer("navigation-line") != null }
            if (!loaded) SystemClock.sleep(250)
        }
        scenario.onActivity {
            assertNotNull(map!!.style!!.getSource("navigation-route"))
            assertNotNull(map!!.style!!.getSource("navigation-target"))
            assertNotNull(map!!.style!!.getLayer("navigation-destination"))
            assertNotNull(map!!.style!!.getSource("track-source"))
        }
    }
}
