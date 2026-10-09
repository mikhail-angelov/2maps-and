package com.bconf.a2maps_and.navigation

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.location.LocationService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class NavigationViewModel(
    application: Application,
    private val engine: RoutingEngine,
    private val now: () -> Long,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, BRouterClient(application), SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var busy = false
    private var requestPending = false
    private var latestFix: GpsFix? = null
    private var lastSample = 0L
    private var matcher: RouteProgressMatcher? = null
    private val reroute = ReroutePolicy()
    private val arrival = ArrivalDetection()

    init {
        viewModelScope.launch { LocationService.lastLocation.collect { updateLocation(it) } }
        viewModelScope.launch {
            state.map { it.needsGps }.distinctUntilChanged().collect { active ->
                syncGpsCadence(active)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.needsGps && latestFix?.isGood(now()) != true) {
                    reroute.resetSamples(); arrival.reset()
                    _state.value = _state.value.copy(waitingForGps = true)
                }
            }
        }
    }

    private fun syncGpsCadence(active: Boolean = _state.value.needsGps) {
        if (!hasPrecisePermission()) return
        getApplication<Application>().startService(Intent(getApplication(), LocationService::class.java).apply {
            action = LocationService.ACTION_SET_NAVIGATION_ACTIVE
            putExtra(LocationService.EXTRA_NAVIGATION_ACTIVE, active)
        })
    }

    fun start(destination: GeoPoint) {
        if (!destination.isValid()) return
        generation++
        requestPending = true
        matcher = null; lastSample = 0; reroute.resetSamples(); arrival.reset()
        _state.value = NavigationState(NavigationPhase.WAITING_GPS, destination, waitingForGps = true)
        if (!checkProvider()) return
        tryCalculate()
    }

    private fun checkProvider(): Boolean {
        if (engine is BRouterClient && !engine.isAvailable()) {
            requestPending = false
            _state.value = _state.value.copy(phase = NavigationPhase.ERROR, waitingForGps = false,
                failure = RoutingFailure.UNAVAILABLE, message = "Offline routing requires BRouter. Install it and download road segments.")
            return false
        }
        return true
    }

    fun retry() {
        val target = _state.value.destination ?: return
        generation++
        requestPending = true
        _state.value = _state.value.copy(phase = if (_state.value.route == null) NavigationPhase.WAITING_GPS else _state.value.phase,
            destination = target, waitingForGps = latestFix?.isGood(now()) != true)
        syncGpsCadence()
        if (checkProvider()) tryCalculate(force = true)
    }

    fun stop() {
        generation++
        requestPending = false
        matcher = null; reroute.resetSamples(); arrival.reset()
        _state.value = NavigationState()
    }

    fun hasPrecisePermission() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun updateLocation(location: Location) {
        val bearing = if (location.hasBearing() && (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 30)) location.bearing.toDouble() else null
        updatePosition(GpsFix(GeoPoint(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            location.elapsedRealtimeNanos / 1_000_000, bearing, location.speed.toDouble()))
    }

    fun updatePosition(fix: GpsFix) {
        if (!hasPrecisePermission() || !fix.isGood(now())) {
            reroute.resetSamples(); arrival.reset()
            if (_state.value.needsGps) _state.value = _state.value.copy(waitingForGps = true)
            return
        }
        if (latestFix != null && fix.elapsedMillis <= latestFix!!.elapsedMillis) return
        latestFix = fix
        if (requestPending || _state.value.phase == NavigationPhase.WAITING_GPS) { tryCalculate(); return }
        val current = _state.value
        if (!current.needsGps || current.route == null || matcher == null || fix.elapsedMillis <= lastSample) return
        lastSample = fix.elapsedMillis
        val match = matcher!!.match(fix)
        val remaining = (match.totalMeters - match.alongMeters).coerceAtLeast(0.0)
        _state.value = current.copy(remainingMeters = remaining,
            remainingSeconds = current.route.estimatedDurationSeconds?.let { it * remaining / match.totalMeters }, waitingForGps = false)
        if (arrival.update(fix, match, current.destination!!, now())) {
            generation++
            _state.value = _state.value.copy(phase = NavigationPhase.ARRIVED, isRerouting = false, message = null, failure = null)
        } else if (reroute.shouldReroute(fix, match, now(), busy)) {
            tryCalculate(force = true)
        }
    }

    private fun tryCalculate(force: Boolean = false) {
        val current = _state.value
        val target = current.destination ?: return
        val fix = latestFix ?: return
        if (busy || !hasPrecisePermission() || !fix.isGood(now())) return
        if (!force && !requestPending && current.phase != NavigationPhase.WAITING_GPS) return
        val token = generation
        busy = true
        requestPending = false
        reroute.started(now())
        _state.value = current.copy(phase = if (current.route == null) NavigationPhase.CALCULATING else NavigationPhase.ACTIVE,
            isRerouting = current.route != null, waitingForGps = false, message = null, failure = null)
        viewModelScope.launch {
            try {
                val route = engine.route(fix.point, target)
                if (token != generation) return@launch
                matcher = RouteProgressMatcher(route)
                reroute.resetSamples(); arrival.reset()
                _state.value = NavigationState(NavigationPhase.ACTIVE, target, route, matcher!!.totalMeters,
                    route.estimatedDurationSeconds, waitingForGps = latestFix?.isGood(now()) != true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != generation) return@launch
                val error = e as? RoutingException
                _state.value = _state.value.copy(phase = NavigationPhase.ERROR, isRerouting = false,
                    failure = error?.kind ?: RoutingFailure.CONNECTION,
                    message = error?.message ?: "Offline route unavailable. Open BRouter and retry.")
            } finally {
                busy = false
                if (token != generation) tryCalculate()
            }
        }
    }
}
