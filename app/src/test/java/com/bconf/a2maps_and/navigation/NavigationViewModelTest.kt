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
    private lateinit var vm: NavigationViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        vm = NavigationViewModel(app, RoutingEngine { _, target ->
            targets.add(target)
            CompletableDeferred<Route>().also { pending.add(it) }.await()
        }, { time })
        store.put("navigation", vm)
        dispatcher.scheduler.runCurrent()
    }
    @After fun cleanup() { store.clear(); dispatcher.scheduler.runCurrent(); Dispatchers.resetMain() }
    private fun good(point: GeoPoint = a) { vm.updatePosition(GpsFix(point, 5.0, time)); dispatcher.scheduler.runCurrent() }
    private fun route(target: GeoPoint = b) = Route(listOf(a, target), target, 1500.0, 120.0)
    @Test fun `restored or stale GPS waits then fresh GPS calculates and stop clears only navigation`() {
        val path = LocationService.recordedPath.value
        good()
        vm.start(b); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.CALCULATING, vm.state.value.phase)
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        vm.stop(); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.IDLE, vm.state.value.phase)
        assertEquals(path, LocationService.recordedPath.value)
        time = 30000
        vm.start(c); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.WAITING_GPS, vm.state.value.phase)
        assertEquals(1, pending.size)
        good()
        assertEquals(2, pending.size)
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
        assertEquals(1, pending.size)
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
    @Test fun `retry retains stale route until a fresh GPS fix permits replacement`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        for (t in listOf(11000L, 12000L, 13000L, 14000L)) { time = t; good(c) }
        pending[1].completeExceptionally(RoutingException(RoutingFailure.SETUP, "Missing data"))
        dispatcher.scheduler.runCurrent()
        time = 40000
        vm.retry(); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ERROR, vm.state.value.phase)
        assertNotNull(vm.state.value.message)
        assertEquals(2, pending.size)
        good(a)
        assertEquals(3, pending.size)
        pending[2].complete(route()); dispatcher.scheduler.runCurrent()
        assertEquals(NavigationPhase.ACTIVE, vm.state.value.phase)
        assertNull(vm.state.value.message)
    }
    @Test fun `GPS expiry preserves route and marks waiting`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
        time = 20000
        dispatcher.scheduler.advanceTimeBy(1001); dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.waitingForGps)
        assertNotNull(vm.state.value.route)
        assertEquals(1, pending.size)
    }
    @Test fun `stable deviation reroutes to same target and retains stale route on failure`() {
        good(); vm.start(b); dispatcher.scheduler.runCurrent()
        pending[0].complete(route()); dispatcher.scheduler.runCurrent()
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
}
