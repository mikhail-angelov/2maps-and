package com.bconf.a2maps_and.navigation

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.repository.RouteRepository
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.bconf.a2maps_and.utils.PlacemarkUtils
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

enum class CenterOnLocationState {
    INACTIVE,
    FOLLOW,
    RECORD
}

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val routeRepository = RouteRepository()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents = _uiEvents.asSharedFlow()

    private val _centerOnLocationState = MutableStateFlow(CenterOnLocationState.INACTIVE)
    val centerOnLocationState: StateFlow<CenterOnLocationState> = _centerOnLocationState.asStateFlow()

    init {
        val prefs = app.getSharedPreferences("NavigationEnginePrefs", Context.MODE_PRIVATE)
        val savedStateName = prefs.getString("centerOnLocationState", CenterOnLocationState.INACTIVE.name)
        _centerOnLocationState.value = CenterOnLocationState.valueOf(savedStateName ?: CenterOnLocationState.INACTIVE.name)
    }

    fun onCenterOnLocationFabClicked() {
        val newState = when (_centerOnLocationState.value) {
            CenterOnLocationState.INACTIVE -> CenterOnLocationState.FOLLOW
            CenterOnLocationState.FOLLOW -> CenterOnLocationState.RECORD
            CenterOnLocationState.RECORD -> CenterOnLocationState.INACTIVE
        }
        _centerOnLocationState.value = newState
        setCenterOnLocationState(newState)
    }

    private fun setCenterOnLocationState(state: CenterOnLocationState) {
        val intent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_SET_CENTER_ON_LOCATION_STATE
            putExtra(NavigationEngineService.EXTRA_CENTER_ON_LOCATION_STATE, state.name)
        }
        app.startService(intent)
    }

    sealed class UiEvent {
        data class ShowToast(val message: String, val isError: Boolean = false) : UiEvent()
    }

    val lastKnownGpsLocation: StateFlow<Location?> = NavigationEngineService.lastLocation
        .map { location ->
            // Filter out the initial blank location from the service
            if (location.latitude == 0.0 && location.longitude == 0.0) null else location
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null // Start with null
        )

    // --- Observables from LocationService (Navigation Engine) ---
    val currentDisplayedPath: StateFlow<List<LatLng>> = NavigationEngineService.currentDisplayedPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Observe the new ActiveManeuverDetails from the service
    val activeManeuverDetails: StateFlow<ActiveManeuverDetails?> =
        NavigationEngineService.activeManeuverDetails
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val navigationState: StateFlow<NavigationState> = NavigationEngineService.navigationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavigationState.IDLE)

    val remainingDistance: StateFlow<String> =
        NavigationEngineService.remainingDistanceInMeters.map { distance ->
            PlacemarkUtils.formatDistanceForDisplay(distance)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.0 m")

    // --- UI State derived from navigationState and currentManeuver ---
    val maneuverText: StateFlow<String> = activeManeuverDetails.map { details ->
        if (details != null && details.maneuver != null &&
            (navigationState.value == NavigationState.NAVIGATING || navigationState.value == NavigationState.OFF_ROUTE)
        ) {

            val distanceStr = if (details.remainingDistanceToManeuverMeters != null) {
                PlacemarkUtils.formatDistanceForDisplay(details.remainingDistanceToManeuverMeters) // Use a local formatter
            } else {
                // If distance is null, maybe use the maneuver segment length as a fallback or indicate unknown
                // For now, let's just use "..." or try to format maneuver.length
                // (details.maneuver.length?.let { formatDistanceForDisplay(it * 1000) } ?: "...")
                "..." // Placeholder if distance calculation failed in service for some reason
            }
            val instruction = details.maneuver.instruction ?: "Next step"
            val text = "$distanceStr: $instruction"
            Log.d("NavViewModel", "Formatted maneuverText: '$text'")
            text
        } else {
            Log.d(
                "NavViewModel",
                "ManeuverDetails is null or not navigating, emitting blank maneuverText."
            )
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Helper function for formatting distance in ViewModel (can be shared or localized)


    fun requestNavigationTo(destinationLatLng: LatLng) {
        viewModelScope.launch {
            val currentGpsLocation = lastKnownGpsLocation.value

            if (currentGpsLocation == null) {
                Log.d("NavigationViewModel", "Current GPS location is null. Waiting for a fix...")
                _uiEvents.emit(
                    UiEvent.ShowToast(
                        app.getString(R.string.error_gps_timeout),
                        isError = true
                    )
                )
                return@launch
            }

            // At this point, currentGpsLocation should be non-null
            val fromLatLng = LatLng(currentGpsLocation.latitude, currentGpsLocation.longitude)

            Log.d(
                "NavigationViewModel",
                "Requesting route from ${fromLatLng} to ${destinationLatLng}"
            )
            val result = routeRepository.getRoute(
                ValhallaLocation(fromLatLng.latitude, fromLatLng.longitude),
                ValhallaLocation(destinationLatLng.latitude, destinationLatLng.longitude)
            )

            result.fold(
                onSuccess = { routeResponse ->
                    if (routeResponse.trip?.legs?.isNotEmpty() == true) {
                        Log.i("NavigationViewModel", "Route received, starting navigation service.")
                        startLocationServiceWithRoute(routeResponse, fromLatLng, destinationLatLng)
                    } else {
                        Log.w(
                            "NavigationViewModel",
                            "No route legs in response: ${routeResponse.trip?.status_message}"
                        )
                        _uiEvents.emit(
                            UiEvent.ShowToast(
                                app.getString(R.string.error_no_route_found),
                                isError = true
                            )
                        )
                    }
                },
                onFailure = { exception ->
                    Log.e("NavigationViewModel", "Route request failed: ${exception.message}")
                    _uiEvents.emit(
                        UiEvent.ShowToast(
                            app.getString(R.string.error_route_request_failed),
                            isError = true
                        )
                    )

                }
            )
        }
    }

    private fun startLocationServiceWithRoute(
        routeResponse: ValhallaRouteResponse,
        fromPoint: LatLng,
        toPoint: LatLng
    ) {
        val intent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_START_NAVIGATION
            putExtra(
                NavigationEngineService.EXTRA_ROUTE_RESPONSE_JSON,
                Gson().toJson(routeResponse)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
    }

    fun recalculateRoute() {
        val intent = Intent(getApplication(), NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_REROUTE_NAVIGATION
        }
        app.startService(intent)
    }

    fun stopNavigation() {
        Log.d("NavigationViewModel", "Requesting to stop navigation service.")
        val serviceIntent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_STOP_NAVIGATION
        }
        app.startService(serviceIntent) // Service will handle its own stopping
    }


    override fun onCleared() {
        super.onCleared()
        Log.d("NavigationViewModel", "onCleared")
        // If the ViewModel is cleared and navigation is not meant to persist headless,
        // you might consider stopping the service, but typically service lifecycle is independent.
    }
}
