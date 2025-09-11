package com.bconf.a2maps_and.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.LocationService // Your service
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.repository.RouteRepository
import com.bconf.a2maps_and.routing.Maneuver
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import android.location.Location
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val routeRepository = RouteRepository()


    private val _lastKnownGpsLocation = MutableStateFlow<Location?>(null) // Standard android.location.Location
    val lastKnownGpsLocation: StateFlow<Location?> = _lastKnownGpsLocation.asStateFlow()


    // --- Observables from LocationService (Navigation Engine) ---
    val currentDisplayedPath: StateFlow<List<LatLng>> = LocationService.currentDisplayedPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentManeuver: StateFlow<Maneuver?> = LocationService.currentManeuver
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val navigationState: StateFlow<NavigationState> = LocationService.navigationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavigationState.IDLE)


    // --- UI State derived from navigationState and currentManeuver ---
    val maneuverText: StateFlow<String> = currentManeuver.map { maneuver ->
        if (maneuver != null && navigationState.value == NavigationState.NAVIGATING) {
            val distanceKm = maneuver.length ?: 0.0
            val distanceMeters = distanceKm * 1000
            val instruction = maneuver.instruction ?: "Next step"
            String.format("%.0fm: %s", distanceMeters, instruction)
        } else {
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val isNavigationActive: StateFlow<Boolean> = navigationState.map {
        it == NavigationState.NAVIGATING || it == NavigationState.OFF_ROUTE
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    fun updateLastKnownGpsLocation(location: android.location.Location) {
        _lastKnownGpsLocation.value = location
    }


    fun requestNavigationTo(destinationLatLng: LatLng) {
        viewModelScope.launch {
            // Indicate that route calculation is starting (optional)
            // LocationService.navigationState.value = NavigationState.ROUTE_CALCULATION

            var currentGpsLocation = _lastKnownGpsLocation?.value

            // If current GPS location is null, wait for the next non-null value
            if (currentGpsLocation == null) {
                Log.d("NavigationViewModel", "Current GPS location is null. Waiting for a fix...")
                // Show some UI indication that we are waiting for GPS
                // You might want to emit a specific state for this, e.g., NavigationState.AWAITING_GPS
                // LocationService.navigationState.value = NavigationState.AWAITING_GPS // Define this state

                try {
                    // Wait for a non-null location, with a timeout
                    currentGpsLocation = withTimeoutOrNull(15000) { // Timeout after 15 seconds
                        _lastKnownGpsLocation.filterNotNull().first() // Suspends until a non-null value is emitted
                    }

                    if (currentGpsLocation == null) {
                        Log.e("NavigationViewModel", "Timed out waiting for GPS location.")
                        LocationService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED // Or a new GPS_TIMEOUT state
                        // Potentially inform UI: "Could not get current location."
                        return@launch
                    }
                    Log.d("NavigationViewModel", "Acquired GPS location: $currentGpsLocation")
                } catch (e: Exception) { // Catch any other exception during waiting
                    Log.e("NavigationViewModel", "Error while waiting for GPS location: ${e.message}", e)
                    LocationService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                    return@launch
                }
            }

            // At this point, currentGpsLocation should be non-null
            val fromLatLng = LatLng(currentGpsLocation!!.latitude, currentGpsLocation.longitude)

            Log.d("NavigationViewModel", "Requesting route from ${fromLatLng} to ${destinationLatLng}")
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
                        Log.w("NavigationViewModel", "No route legs in response: ${routeResponse.trip?.status_message}")
                        LocationService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                    }
                },
                onFailure = { exception ->
                    Log.e("NavigationViewModel", "Route request failed: ${exception.message}")
                    LocationService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                }
            )
        }
    }

    private fun startLocationServiceWithRoute(routeResponse: ValhallaRouteResponse, fromPoint: LatLng, toPoint: LatLng) {
        val serviceIntent = Intent(app, LocationService::class.java).apply {
            action = LocationService.ACTION_START_NAVIGATION
            putExtra(LocationService.EXTRA_ROUTE_RESPONSE_JSON, Gson().toJson(routeResponse))
            putExtra(LocationService.EXTRA_FROM_LAT, fromPoint.latitude)
            putExtra(LocationService.EXTRA_FROM_LON, fromPoint.longitude)
            putExtra(LocationService.EXTRA_TO_LAT, toPoint.latitude)
            putExtra(LocationService.EXTRA_TO_LON, toPoint.longitude)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(serviceIntent)
        } else {
            app.startService(serviceIntent)
        }
    }

    fun stopNavigation() {
        Log.d("NavigationViewModel", "Requesting to stop navigation service.")
        val serviceIntent = Intent(app, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_NAVIGATION
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
