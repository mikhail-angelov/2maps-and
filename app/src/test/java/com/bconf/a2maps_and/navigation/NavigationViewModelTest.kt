package com.bconf.a2maps_and.navigation

import android.Manifest
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.bconf.a2maps_and.location.LocationService

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var time = 1000L
    private val a = GeoPoint(56.29, 43.98)
    private val b = GeoPoint(56.30, 44.00)
    private val c = GeoPoint(56.31, 44.01)
    private val pending = mutableListOf<CompletableDeferred<Route>>()
    private val targets = mutableListOf<GeoPoint>()
    private val origins = mutableListOf<GeoPoint>()
    private val alternatives = mutableListOf<Int>()
    private var offerAlternatives = false
    private lateinit var vm: NavigationViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        vm = NavigationViewModel(app, RoutingEngine { origin, target, alternative ->
            if (alternative > 0 && !offerAlternatives) throw RoutingException(RoutingFailure.SETUP, "No alternative")
            alternatives.add(alternative)
            origins.add(origin)
            targets.add(target)
            CompletableDeferred<Route>().also { pending.add(it) }.await()
        }, { time }, computation = dispatcher)
        store.put("navigation", vm)
        dispatcher.scheduler.runCurrent()
    }
    @After fun cleanup() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun good(point: GeoPoint = a) { vm.updatePosition(GpsFix(point, 5.0, time)); dispatcher.scheduler.runCurrent() }
    private fun route(target: GeoPoint = b) = Route(listOf(a, target), target, 1500.0, 120.0)
    private fun go() { vm.go(); dispatcher.scheduler.runCurrent() }
    @Test fun routePlanningUsesShownMapPointWithoutAccurateGps() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        vm.updatePosition(GpsFix(a, 215.4, 0))
        vm.start(b); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.CALCULATING, vm.state.value.phase)
        assertEquals(listOf(a), origins)
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.SELECTING, vm.state.value.phase)
        assertFalse(vm.state.value.searchingAlternatives)
        assertEquals(1, vm.state.value.options.size)
        go()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertTrue(vm.state.value.waitingForGps)
    }
    @Test fun routePlanningUsesNewerShownPointInsteadOfOlderGoodFix() {
        good(a)
        vm.updatePosition(GpsFix(c, 215.4, time + 1))
        vm.start(b); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(c), origins)
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        assertTrue(vm.state.value.waitingForGps)
    }
    @Test fun guidanceUpdatesAndUnknownRoadClearsSpeedLimit() {
        good(a); vm.start(c); dispatcher.scheduler.runCurrent()
        val rich = Route(listOf(a, b, c), c, 3000.0, 180.0,
            listOf(RouteManeuver(1, ManeuverType.RIGHT)),
            listOf(RouteRoadSection(0, 1, 60), RouteRoadSection(1, 2, null)))
        pending[0].complete(rich); dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.upcomingManeuver)
        go()
        assertEquals(60, vm.state.value.maxSpeedKmh)
        assertEquals(ManeuverType.RIGHT, vm.state.value.upcomingManeuver!!.maneuver.type)
        val initialDistance = vm.state.value.upcomingManeuver!!.distanceMeters
        time = 2000; good(GeoPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2))
        assertTrue(vm.state.value.upcomingManeuver!!.distanceMeters < initialDistance)
        time = 3000
        vm.updatePosition(GpsFix(b, 215.0, time)); dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.maxSpeedKmh)
        assertTrue(vm.state.value.waitingForGps)
        assertEquals(1, pending.size)
    }
    @Test fun `known map point plans with stale GPS and stop clears only navigation`() {
        val path = LocationService.recordedPath.value
        good()
        vm.start(b); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.CALCULATING, vm.state.value.phase)
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        vm.stop(); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.IDLE, vm.state.value.phase)
        assertEquals(path, LocationService.recordedPath.value)
        time = 30000
        vm.start(c); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.CALCULATING, vm.state.value.phase)
        assertEquals(2, pending.size)
        assertEquals(a, origins.last())
        pending[1].complete(route(c)); dispatcher.scheduler.runCurrent()
        go()
        assertTrue(vm.state.value.waitingForGps)
        good()
        assertFalse(vm.state.value.waitingForGps)
    }
    @Test fun `stop during calculation ignores late response`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        vm.stop()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.route)
    }
    @Test fun `latest destination wins without simultaneous calculations`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        vm.start(c); dispatcher.scheduler.runCurrent()
        assertEquals(2, pending.size)
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        assertNull(vm.state.value.route)
        assertEquals(listOf(b, c), targets)
        pending[1].complete(route(c)); dispatcher.scheduler.runCurrent()
        assertEquals(c, vm.state.value.route!!.destination)
    }
    @Test fun `unavailable provider reports error and explicit retry recovers`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].completeExceptionally(RoutingException(RoutingFailure.UNAVAILABLE, "Install BRouter"))
        dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ERROR, vm.state.value.phase)
        assertFalse(vm.state.value.needsGps)
        vm.retry(); dispatcher.scheduler.runCurrent()
        pending[1].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
    }
    @Test fun `permission recovery explicitly enables navigation cadence`() {
        val app = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) { }
        shadow.denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        vm.start(b); dispatcher.scheduler.runCurrent()
        shadow.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        vm.retry(); dispatcher.scheduler.runCurrent()
        var activated = false
        var intent = shadow.nextStartedService
        while (intent != null) {
            if (intent.action == LocationService.ACTION_SET_NAVIGATION_ACTIVE && intent.getBooleanExtra(LocationService.EXTRA_NAVIGATION_ACTIVE, false)) activated = true
            intent = shadow.nextStartedService
        }
        assertTrue(activated)
    }
    @Test fun `explicit retry replaces stale route from the shown point without GPS`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        for (t in listOf(11000L, 12000L, 13000L, 14000L)) { time = t; good(c) }
        pending[1].completeExceptionally(RoutingException(RoutingFailure.SETUP, "Missing data"))
        dispatcher.scheduler.runCurrent()
        time = 40000
        vm.retry(); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertTrue(vm.state.value.isRerouting)
        assertNotNull(vm.state.value.route)
        assertEquals(3, pending.size)
        assertEquals(c, origins.last())
        pending[2].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertNull(vm.state.value.message)
        assertTrue(vm.state.value.waitingForGps)
        good(a)
        assertFalse(vm.state.value.waitingForGps)
    }
    @Test fun `GPS expiry preserves route and marks waiting`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        time = 20000
        dispatcher.scheduler.advanceTimeBy(1001); dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.waitingForGps)
        assertNotNull(vm.state.value.route)
        assertEquals(1, pending.size)
    }
    @Test fun `stable deviation reroutes to same target and retains stale route on failure`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        for (t in listOf(11000L, 12000L, 13000L, 14000L)) { time = t; good(c) }
        assertEquals(listOf(b, b), targets)
        assertTrue(vm.state.value.isRerouting)
        assertNotNull(vm.state.value.route)
        pending[1].completeExceptionally(RoutingException(RoutingFailure.SETUP, "Missing data"))
        dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ERROR, vm.state.value.phase)
        assertNotNull(vm.state.value.route)
        time = 15000; good(c)
        assertEquals(2, pending.size)
    }
    @Test fun `reroute drives the candidate that continues the direction of travel`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        offerAlternatives = true
        // Driving east at c, away from the planned line.
        for (t in listOf(11000L, 12000L, 13000L, 14000L)) {
            time = t; vm.updatePosition(GpsFix(c, 5.0, time, bearingDegrees = 90.0, speedMetersPerSecond = 15.0)); dispatcher.scheduler.runCurrent()
        }
        val back = Route(listOf(c, GeoPoint(56.31, 44.00), b), b, 2000.0, 200.0)
        val forward = Route(listOf(c, GeoPoint(56.31, 44.02), b), b, 2600.0, 260.0)
        pending[1].complete(back); dispatcher.scheduler.runCurrent()
        assertSame("the main route is driven while candidates load", back, vm.state.value.route)
        pending[2].complete(forward); dispatcher.scheduler.runCurrent()
        pending[3].complete(back.copy(distanceMeters = 2001.0)); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(0, 0, 1, 2), alternatives)
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertSame(forward, vm.state.value.route)
    }
    @Test fun `reroute without a course keeps the main route and requests no candidates`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        offerAlternatives = true
        for (t in listOf(11000L, 12000L, 13000L, 14000L)) { time = t; good(c) }
        val back = Route(listOf(c, GeoPoint(56.31, 44.00), b), b, 2000.0, 200.0)
        pending[1].complete(back); dispatcher.scheduler.runCurrent()
        assertSame(back, vm.state.value.route)
        assertEquals(2, pending.size)
    }
    @Test fun `position arrow eases onto the route inside the corridor and is raw outside`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        go()
        assertNull(vm.state.value.snappedPosition)
        val onLine = interpolate(a, b, 0.5)
        val beside = GeoPoint(onLine.latitude + 0.0001, onLine.longitude) // about 11 m off the line
        val snaps = (1..3).map { time += 1000; good(beside); vm.state.value.snappedPosition!! }
        val distances = snaps.map { distanceMeters(beside, it) }
        assertTrue("blends towards the line: $distances", distances[0] > 0 && distances[0] < distances[1] && distances[1] < distances[2])
        assertEquals(bearingDegrees(a, b), bearingDegrees(a, snaps[2]), 0.1)
        time += 1000; good(c)
        assertNull(vm.state.value.snappedPosition)
    }
    @Test fun `distinct alternatives are offered and Go drives the selected one without recalculation`() {
        offerAlternatives = true
        good(); vm.start(c); dispatcher.scheduler.runCurrent()
        val main = Route(listOf(a, b, c), c, 3000.0, 180.0)
        val other = Route(listOf(a, GeoPoint(56.29, 44.01), c), c, 3500.0, 240.0)
        pending[0].complete(main); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.SELECTING, vm.state.value.phase)
        assertTrue(vm.state.value.searchingAlternatives)
        assertSame(main, vm.state.value.route)
        pending[1].complete(main.copy(distanceMeters = 3001.0)); dispatcher.scheduler.runCurrent()
        assertEquals(1, vm.state.value.options.size)
        pending[2].complete(other); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(0, 1, 2), alternatives)
        assertEquals(listOf(main, other), vm.state.value.options)
        assertFalse(vm.state.value.searchingAlternatives)
        vm.select(1)
        assertSame(other, vm.state.value.route)
        assertEquals(240.0, vm.state.value.remainingSeconds!!, 0.0)
        go()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertSame(other, vm.state.value.route)
        assertEquals(3, pending.size)
    }
    @Test fun `each guided drive starts and finishes its own track recording`() {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        fun recording(): List<Boolean> = generateSequence { shadow.nextStartedService }
            .filter { it.action == LocationService.ACTION_SET_NAVIGATION_RECORDING }
            .map { it.getBooleanExtra(LocationService.EXTRA_NAVIGATION_RECORDING, false) }.toList()
        recording()
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(emptyList<Boolean>(), recording())
        go()
        assertEquals(listOf(true), recording())
        vm.stop(); dispatcher.scheduler.runCurrent()
        assertEquals(listOf(false), recording())
    }
    @Test fun `Go stops the alternative search and a new destination ignores late alternatives`() {
        offerAlternatives = true
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        val driven = vm.state.value.route
        go()
        pending[1].complete(Route(listOf(a, GeoPoint(56.29, 44.01), b), b, 2000.0, 200.0)); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertSame(driven, vm.state.value.route)
        vm.start(c); dispatcher.scheduler.runCurrent()
        pending[2].complete(route(c)); dispatcher.scheduler.runCurrent()
        vm.start(b); dispatcher.scheduler.runCurrent()
        pending[3].complete(Route(listOf(a, GeoPoint(56.29, 44.01), c), c, 2000.0, 200.0)); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.CALCULATING, vm.state.value.phase)
        assertNull(vm.state.value.route)
        assertEquals(b, targets.last())
    }
    @Test fun `alternative search is limited by its time budget`() {
        offerAlternatives = true
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        dispatcher.scheduler.advanceTimeBy(30_001); dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.searchingAlternatives)
        assertEquals(NavigationPhase.SELECTING, vm.state.value.phase)
    }
    @Test fun `GPS loss after confirmed progress keeps remaining distance and hides the limit`() {
        good(a); vm.start(c); dispatcher.scheduler.runCurrent()
        pending[0].complete(Route(listOf(a, b, c), c, 3000.0, 180.0, roadSections = listOf(RouteRoadSection(0, 2, 60))))
        dispatcher.scheduler.runCurrent()
        go()
        assertNull(vm.state.value.progressMeters)
        time = 2000; good(b)
        val remaining = vm.state.value.remainingMeters
        assertNotNull(vm.state.value.progressMeters)
        assertNotNull(vm.state.value.arrivalAtMillis)
        assertEquals(60, vm.state.value.maxSpeedKmh)
        val arrival = vm.state.value.arrivalAtMillis
        time = 20000
        dispatcher.scheduler.advanceTimeBy(1001); dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.positionStale)
        assertNull(vm.state.value.maxSpeedKmh)
        assertEquals(remaining, vm.state.value.remainingMeters, 0.0)
        assertEquals(arrival, vm.state.value.arrivalAtMillis)
    }
}
