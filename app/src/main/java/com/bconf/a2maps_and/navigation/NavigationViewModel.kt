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
import kotlin.math.roundToLong

class NavigationViewModel(
    application: Application,
    private val engine: RoutingEngine,
    private val now: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val computation: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, LocalRoutingClient(application), SystemClock::elapsedRealtime)

    private val _state = MutableStateFlow(NavigationState())
    val state = _state.asStateFlow()
    private var generation = 0L
    private var busy = false
    private var calculationJob: Job? = null
    private var requestPending = false
    private var latestFix: GpsFix? = null
    private var lastReceivedFix: GpsFix? = null
    private var shownPosition: GeoPoint? = null
    private var lastSample = 0L
    private var matcher: RouteProgressMatcher? = null
    private var previewMatcher: RouteProgressMatcher? = null
    private var guidance: RouteGuidance? = null
    private val reroute = ReroutePolicy()
    private val arrival = ArrivalDetection()
    private var selectionOrigin: GeoPoint? = null

    init {
        viewModelScope.launch { LocationService.lastLocation.collect { updateLocation(it) } }
        viewModelScope.launch {
            state.map { it.wantsGps }.distinctUntilChanged().collect { active ->
                syncGpsCadence(active)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (_state.value.needsGps && !hasAccurateFix()) {
                    reroute.resetSamples(); arrival.reset()
                    val current = _state.value
                    // A limit of the last confirmed road must not outlive the position it belongs to.
                    _state.value = current.copy(waitingForGps = true, maxSpeedKmh = if (current.progressMeters != null) null else current.maxSpeedKmh)
                }
                val current = _state.value
                if (current.phase == NavigationPhase.ACTIVE && !current.positionStale) {
                    _state.value = current.copy(arrivalAtMillis = arrivalAt(current.remainingSeconds))
                }
            }
        }
    }

    private val NavigationState.wantsGps get() = needsGps || phase == NavigationPhase.SELECTING

    private fun syncGpsCadence(active: Boolean = _state.value.wantsGps) {
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
        matcher = null; previewMatcher = null; guidance = null; selectionOrigin = null; lastSample = 0; reroute.resetSamples(); arrival.reset()
        _state.value = NavigationState(NavigationPhase.WAITING_POSITION, destination, waitingForGps = true)
        calculationJob?.cancel()
        tryCalculate()
    }

    fun retry() {
        val target = _state.value.destination ?: return
        generation++
        requestPending = true
        _state.value = _state.value.copy(phase = if (_state.value.route == null) NavigationPhase.WAITING_POSITION else _state.value.phase,
            destination = target, waitingForGps = !hasAccurateFix())
        syncGpsCadence()
        calculationJob?.cancel()
        tryCalculate(force = true)
    }

    fun stop() {
        generation++
        requestPending = false
        matcher = null; previewMatcher = null; guidance = null; selectionOrigin = null; reroute.resetSamples(); arrival.reset()
        _state.value = NavigationState()
        calculationJob?.cancel()
    }

    fun select(index: Int) {
        val current = _state.value
        if (current.phase != NavigationPhase.SELECTING || index !in current.options.indices) return
        val route = current.options[index]
        _state.value = current.copy(selectedOption = index, route = route,
            remainingMeters = route.distanceMeters, remainingSeconds = route.estimatedDurationSeconds)
    }

    /** Starts driving the selected, already calculated route. */
    fun go() {
        val current = _state.value
        if (current.phase != NavigationPhase.SELECTING) return
        val route = current.route ?: return
        val target = current.destination ?: return
        val origin = selectionOrigin
        val position = shownPosition
        generation++
        calculationJob?.cancel()
        if (origin != null && position != null && distanceMeters(origin, position) > RESELECT_DISTANCE_METERS) {
            // The options were planned from elsewhere: refresh them visibly instead of silently driving another path.
            requestPending = true
            _state.value = NavigationState(NavigationPhase.WAITING_POSITION, target, waitingForGps = !hasAccurateFix())
            tryCalculate()
            return
        }
        activate(route, target)
    }

    fun hasPrecisePermission() = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasAccurateFix() = hasPrecisePermission() && lastReceivedFix?.isGood(now()) == true

    fun updateLocation(location: Location) {
        val bearing = if (location.hasBearing() && (!location.hasBearingAccuracy() || location.bearingAccuracyDegrees <= 30)) location.bearing.toDouble() else null
        updatePosition(GpsFix(GeoPoint(location.latitude, location.longitude),
            if (location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
            location.elapsedRealtimeNanos / 1_000_000, bearing, location.speed.toDouble()))
    }

    fun updatePosition(fix: GpsFix) {
        lastReceivedFix = fix
        if (fix.point.isValid() && fix.point != GeoPoint(0.0, 0.0)) shownPosition = fix.point
        if (!hasPrecisePermission() || !fix.isGood(now())) {
            reroute.resetSamples(); arrival.reset()
            val current = _state.value
            if (current.needsGps) {
                // After confirmed progress the car's road is unknown; only a never-started preview may use the shown point.
                _state.value = if (current.progressMeters != null) current.copy(waitingForGps = true, maxSpeedKmh = null)
                    else previewDetails(fix).let { current.copy(waitingForGps = true, upcomingManeuver = it.upcomingManeuver, maxSpeedKmh = it.maxSpeedKmh) }
            }
            if (requestPending) tryCalculate()
            return
        }
        if (latestFix != null && fix.elapsedMillis <= latestFix!!.elapsedMillis) return
        latestFix = fix
        if (requestPending || _state.value.phase == NavigationPhase.WAITING_POSITION) { tryCalculate(); return }
        val current = _state.value
        if (!current.needsGps || current.route == null || matcher == null || fix.elapsedMillis <= lastSample) return
        lastSample = fix.elapsedMillis
        val match = matcher!!.match(fix)
        val details = detailsFor(match, fix.accuracyMeters)
        // Off-route samples keep the last confirmed progress instead of projecting onto a far segment.
        val progress = if (match.crossTrackMeters <= corridor(fix.accuracyMeters)) match.alongMeters else current.progressMeters
        val remaining = progress?.let { (match.totalMeters - it).coerceAtLeast(0.0) } ?: current.remainingMeters
        val seconds = current.route.estimatedDurationSeconds?.let { it * remaining / match.totalMeters }
        _state.value = current.copy(remainingMeters = remaining, remainingSeconds = seconds, progressMeters = progress,
            arrivalAtMillis = arrivalAt(seconds), waitingForGps = false,
            upcomingManeuver = details.upcomingManeuver, maxSpeedKmh = details.maxSpeedKmh)
        if (arrival.update(fix, match, current.destination!!, now())) {
            generation++
            _state.value = _state.value.copy(phase = NavigationPhase.ARRIVED, isRerouting = false, message = null, failure = null,
                remainingMeters = 0.0, remainingSeconds = 0.0, progressMeters = match.totalMeters, upcomingManeuver = null, maxSpeedKmh = null)
        } else if (reroute.shouldReroute(fix, match, now(), busy)) {
            tryCalculate(force = true)
        }
    }

    private fun tryCalculate(force: Boolean = false) {
        val current = _state.value
        val target = current.destination ?: return
        if (busy) return
        val explicitRequest = requestPending || current.phase == NavigationPhase.WAITING_POSITION
        if (!force && !explicitRequest) return
        // Planning uses the same point as the map; only automatic rerouting needs accurate GPS.
        val origin = if (explicitRequest) shownPosition ?: return
            else latestFix?.takeIf { hasAccurateFix() }?.point ?: return
        val token = generation
        // Planning without a driven route offers choices; rerouting while driving replaces the route directly.
        val selecting = current.route == null
        busy = true
        requestPending = false
        reroute.started(now())
        _state.value = current.copy(phase = if (selecting) NavigationPhase.CALCULATING else NavigationPhase.ACTIVE,
            isRerouting = !selecting, waitingForGps = false, message = null, failure = null)
        calculationJob = viewModelScope.launch {
            try {
                val started = now()
                val route = engine.route(origin, target, 0)
                if (token != generation) return@launch
                if (!selecting) { activate(route, target); return@launch }
                selectionOrigin = origin
                _state.value = NavigationState(NavigationPhase.SELECTING, target, route, route.distanceMeters,
                    route.estimatedDurationSeconds, waitingForGps = !hasAccurateFix(), options = listOf(route), searchingAlternatives = true)
                withTimeoutOrNull((ALTERNATIVES_BUDGET_MS - (now() - started)).coerceAtLeast(1)) {
                    for (index in 1..MAX_ALTERNATIVES) {
                        val alternative = try { engine.route(origin, target, index) } catch (e: CancellationException) { throw e } catch (_: Exception) { break }
                        val offered = _state.value.options
                        // Densified geometry comparison is too heavy for the main thread on long routes.
                        val distinct = withContext(computation) { RouteSimilarity.isDistinct(alternative, offered) }
                        val shown = _state.value
                        if (token != generation || shown.phase != NavigationPhase.SELECTING) return@withTimeoutOrNull
                        if (distinct) _state.value = shown.copy(options = shown.options + alternative)
                    }
                }
                if (token == generation) _state.value = _state.value.copy(searchingAlternatives = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token != generation) return@launch
                val error = e as? RoutingException
                _state.value = _state.value.copy(phase = NavigationPhase.ERROR, isRerouting = false,
                    failure = error?.kind ?: RoutingFailure.CONNECTION,
                    message = error?.message ?: "Offline route unavailable. Retry or check Road data.")
            } finally {
                busy = false
                calculationJob = null
                if (token != generation) tryCalculate()
            }
        }
    }

    private fun activate(route: Route, target: GeoPoint) {
        matcher = RouteProgressMatcher(route)
        previewMatcher = RouteProgressMatcher(route)
        guidance = RouteGuidance(route)
        val details = lastReceivedFix?.let { previewDetails(it) } ?: guidance!!.at(0.0)
        reroute.resetSamples(); arrival.reset()
        _state.value = NavigationState(NavigationPhase.ACTIVE, target, route, matcher!!.totalMeters,
            route.estimatedDurationSeconds, waitingForGps = !hasAccurateFix(),
            upcomingManeuver = details.upcomingManeuver, maxSpeedKmh = details.maxSpeedKmh,
            arrivalAtMillis = arrivalAt(route.estimatedDurationSeconds))
    }

    /** Rounded to whole minutes, the precision shown, so the clock does not emit a state every second. */
    private fun arrivalAt(seconds: Double?) = seconds?.let { ((clock() + it * 1000) / 60_000).roundToLong() * 60_000 }

    private fun corridor(accuracy: Double) = kotlin.math.max(30.0, if (accuracy.isFinite()) accuracy.coerceIn(0.0, 230.0) * 2.5 else 30.0)

    private fun previewDetails(fix: GpsFix): RouteGuidanceInfo {
        if (!fix.point.isValid() || previewMatcher == null) return RouteGuidanceInfo(null, null)
        return detailsFor(previewMatcher!!.match(fix), fix.accuracyMeters)
    }

    private fun detailsFor(match: RouteMatch, accuracy: Double): RouteGuidanceInfo {
        return if (match.crossTrackMeters <= corridor(accuracy)) guidance?.at(match.alongMeters) ?: RouteGuidanceInfo(null, null)
            else RouteGuidanceInfo(null, null)
    }

    companion object {
        private const val MAX_ALTERNATIVES = 2
        private const val ALTERNATIVES_BUDGET_MS = 30_000L
        private const val RESELECT_DISTANCE_METERS = 300.0
    }
}
