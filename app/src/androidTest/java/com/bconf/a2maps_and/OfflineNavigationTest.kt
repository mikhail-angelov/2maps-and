package com.bconf.a2maps_and

import android.Manifest
import android.location.Location
import android.location.Criteria
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.hamcrest.Matchers.startsWith
import org.hamcrest.Matchers.containsString
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng

/** Bundled routing needs E40_N55/E45_N55 fixtures; see docs/navigation/README.md. */
@RunWith(AndroidJUnit4::class)
class OfflineNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(context) }
    private val gps by lazy { context.getSystemService(LocationManager::class.java) }
    private val origin = GeoPoint(56.292374, 43.985402)
    private val target = GeoPoint(56.2965, 43.9955)
    private val gpsReplayHandler = Handler(Looper.getMainLooper())
    @Volatile private var gpsReplayLocation: Location? = null
    private val gpsReplay = object : Runnable {
        override fun run() {
            gpsReplayLocation?.let { previous ->
                gps.setTestProviderLocation(LocationManager.GPS_PROVIDER, Location(previous).apply {
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
            }
            gpsReplayHandler.postDelayed(this, 1000)
        }
    }

    @Before fun permissions() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        shell("appops set ${context.packageName} android:mock_location allow")
        val data = RoutingDataStore(context)
        val source = InstrumentationRegistry.getArguments().getString("roadDataDir") ?: "/data/local/tmp/2maps-routing-test"
        for (name in listOf("E40_N55.rd5", "E45_N55.rd5")) {
            if (!java.io.File(data.directory, name).exists()) {
                val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $source/$name")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { data.importSegment(name, it) }
            }
        }
    }

    @Test fun localBinderBuildsRealCarRoute() = runBlocking {
        requireRoadData()
        val start = SystemClock.elapsedRealtime()
        val route = LocalRoutingClient(context).route(origin, target, 0)
        assertTrue(route.points.size > 2)
        assertTrue(route.distanceMeters > 100)
        assertNotNull(route.estimatedDurationSeconds)
        assertTrue(route.maneuvers.isNotEmpty())
        assertEquals(20, route.roadSections.first().maxSpeedKmh)
        assertTrue(distanceMeters(route.points.first(), origin) < 250)
        assertTrue(distanceMeters(route.points.last(), target) < 250)
        println("Offline Binder route: ${route.points.size} points, ${route.distanceMeters.toInt()} m, ${SystemClock.elapsedRealtime() - start} ms")
    }

    @Test fun alternativeIndexReturnsRouteForSameRequest() = runBlocking {
        requireRoadData()
        val client = LocalRoutingClient(context)
        val main = client.route(origin, target, 0)
        val alternative = client.route(origin, target, 1)
        assertTrue(alternative.points.size >= 2)
        assertTrue(distanceMeters(alternative.points.last(), target) < 250)
        println("BRouter alternative 1 distinct from main: ${RouteSimilarity.isDistinct(alternative, listOf(main))}")
    }

    @Test fun routeCrosses45DegreeTileBoundary() = runBlocking {
        requireRoadData()
        val start = SystemClock.elapsedRealtime()
        val route = LocalRoutingClient(context).route(GeoPoint(56.028, 44.995), GeoPoint(56.030, 45.015), 0)
        assertTrue(route.points.any { it.longitude < 45 })
        assertTrue(route.points.any { it.longitude >= 45 })
        assertTrue(route.distanceMeters > 500)
        println("Inter-tile route: ${route.points.size} points, ${route.distanceMeters.toInt()} m, ${SystemClock.elapsedRealtime() - start} ms")
    }

    @Test fun missingSegmentsLeadToRoadDataInsteadOfExternalInstallation() = runBlocking {
        val data = RoutingDataStore(context)
        val original = File(data.directory, "E40_N55.rd5")
        val hidden = File(data.directory, "E40_N55.rd5.test-backup")
        assertTrue(original.renameTo(hidden))
        try {
            LocalRoutingClient(context).route(origin, target, 0)
            fail("Expected missing segment")
        } catch (error: RoutingException) {
            assertEquals(RoutingFailure.SETUP, error.kind)
            assertTrue(error.message!!.contains("E40_N55.rd5"))
            assertTrue(error.message!!.contains("Road data"))
        } finally { assertTrue(hidden.renameTo(original)) }
    }

    @Test fun roadDataScreenListsImportedSegments() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity {
                val host = it.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                host.navController.navigate(R.id.action_mapFragment_to_mainMenuFragment)
                host.navController.navigate(R.id.action_mainMenuFragment_to_roadDataFragment)
            }
            onView(withId(R.id.roadDataFiles)).check(matches(withText(containsString("E40_N55.rd5"))))
            onView(withId(R.id.importRoadData)).check(matches(isEnabled()))
        }
    }

    @Test fun mapShowsRealRouteReroutesSurvivesStyleAndRecreationAndPreservesRecording() {
        requireRoadData()
        val imported = File(context.filesDir, "maps/integration-map.mbtiles")
        imported.parentFile!!.mkdirs()
        context.assets.open("planet.mbtiles").use { input -> imported.outputStream().use { input.copyTo(it) } }
        startMockLocations()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: NavigationViewModel
                lateinit var location: LocationViewModel
                scenario.onActivity {
                    location = ViewModelProvider(it)[LocationViewModel::class.java]
                    location.setLocationModeFromMenu(LocationMode.RECORD)
                    vm = ViewModelProvider(it)[NavigationViewModel::class.java]
                }
                // The map menu is hidden while navigating, so the imported map is chosen first.
                onView(withId(R.id.mainMenuButton)).perform(click())
                onView(withText("integration-map")).perform(click())
                SystemClock.sleep(1500)
                scenario.onActivity { vm.start(target) }
                mockPosition(origin)
                waitForRoute(vm)
                assertLayers(scenario)
                onView(withId(R.id.navigationPanel)).check(matches(isDisplayed()))
                onView(withId(R.id.navigationStop)).check(matches(isDisplayed()))
                onView(withId(R.id.mainMenuButton)).check(matches(withEffectiveVisibility(Visibility.INVISIBLE)))
                onView(withId(R.id.fabCenterOnLocationInFragment)).check(matches(withEffectiveVisibility(Visibility.GONE)))
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
            stopMockLocations()
        }
    }

    @Test fun placemarkListStartsOfflineNavigationAndReturnsToMap() {
        requireRoadData()
        startMockLocations()
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
                onView(withText("Offline")).perform(click())
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
            stopMockLocations()
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
            onView(withText("Offline")).check(matches(isDisplayed()))
            pressBack()
        }
    }

    @Test fun coarseMapPositionBuildsPreviewWithoutGpsSettingsPrompt() {
        requireRoadData()
        startMockLocations()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var vm: NavigationViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[NavigationViewModel::class.java] }
                mockPosition(origin, accuracy = 215f)
                val deadline = SystemClock.elapsedRealtime() + 15000
                while (LocationService.lastLocation.value.accuracy != 215f && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100)
                }
                assertEquals(215f, LocationService.lastLocation.value.accuracy)
                scenario.onActivity { vm.start(target) }
                waitForRoute(vm)
                assertTrue(vm.state.value.waitingForGps)
                assertTrue(distanceMeters(vm.state.value.route!!.points.first(), origin) < 250)
                onView(withId(R.id.navigationStatus)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.navigationSummary)).check(matches(isDisplayed()))
                onView(withId(R.id.navigationManeuverCard)).check(matches(isDisplayed()))
                onView(withId(R.id.navigationSpeedLimit)).check(matches(withText("20")))
                onView(withId(R.id.navigationSetup)).check(matches(withEffectiveVisibility(Visibility.GONE)))
                onView(withId(R.id.navigationStop)).perform(click())
            }
        } finally {
            stopMockLocations()
        }
    }

    private fun mockPosition(point: GeoPoint, accuracy: Float = 5f) {
        val fix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = point.latitude
            longitude = point.longitude
            this.accuracy = accuracy
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        gpsReplayLocation = fix
        gps.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix)
        Tasks.await(fused.setMockLocation(Location(fix).apply { provider = "fused" }))
    }

    @Suppress("DEPRECATION")
    private fun startMockLocations() {
        gps.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        gps.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        Tasks.await(fused.setMockMode(true))
        gpsReplayHandler.post(gpsReplay)
    }

    private fun stopMockLocations() {
        try { Tasks.await(fused.setMockMode(false)) }
        finally {
            instrumentation.runOnMainSync {
                gpsReplayHandler.removeCallbacks(gpsReplay)
                gpsReplayLocation = null
                gps.removeTestProvider(LocationManager.GPS_PROVIDER)
            }
        }
    }

    private fun requireRoadData() {
        assertTrue("Import road data test fixtures", RoutingDataStore(context).segments().size >= 2)
    }

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

    /** Waits for the route choice, starts the main option with Go and waits for driving. */
    private fun waitForRoute(vm: NavigationViewModel) {
        val limit = SystemClock.elapsedRealtime() + 45000
        fun waitFor(vararg phases: NavigationPhase) {
            while (SystemClock.elapsedRealtime() < limit && vm.state.value.phase !in phases && vm.state.value.phase != NavigationPhase.ERROR) SystemClock.sleep(250)
        }
        waitFor(NavigationPhase.SELECTING, NavigationPhase.ACTIVE)
        if (vm.state.value.phase == NavigationPhase.SELECTING) onView(withId(R.id.navigationGo)).perform(click())
        waitFor(NavigationPhase.ACTIVE)
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
