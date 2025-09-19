package com.bconf.a2maps_and.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.service.NavigationEngineService // Your service
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
import androidx.compose.ui.text.intl.Locale
import com.bconf.a2maps_and.navigation.ActiveManeuverDetails
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.text.format

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val routeRepository = RouteRepository()


    val lastKnownGpsLocation: StateFlow<Location> = NavigationEngineService.lastLocation
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Location(""))


    // --- Observables from LocationService (Navigation Engine) ---
    val currentDisplayedPath: StateFlow<List<LatLng>> = NavigationEngineService.currentDisplayedPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Observe the new ActiveManeuverDetails from the service
    val activeManeuverDetails: StateFlow<ActiveManeuverDetails?> = NavigationEngineService.activeManeuverDetails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val navigationState: StateFlow<NavigationState> = NavigationEngineService.navigationState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NavigationState.IDLE)

    val remainingDistance: StateFlow<String> = NavigationEngineService.remainingDistanceInMeters.map { distance ->
        formatDistanceForDisplay(distance)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "0.0 m")

    // --- UI State derived from navigationState and currentManeuver ---
    val maneuverText: StateFlow<String> = activeManeuverDetails.map { details ->
        if (details != null && details.maneuver != null &&
            (navigationState.value == NavigationState.NAVIGATING || navigationState.value == NavigationState.OFF_ROUTE)) {

            val distanceStr = if (details.remainingDistanceToManeuverMeters != null) {
                formatDistanceForDisplay(details.remainingDistanceToManeuverMeters) // Use a local formatter
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
            Log.d("NavViewModel", "ManeuverDetails is null or not navigating, emitting blank maneuverText.")
            ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // Helper function for formatting distance in ViewModel (can be shared or localized)
    private fun formatDistanceForDisplay(distanceMeters: Double): String {
        return if (distanceMeters < 1.0 && distanceMeters > 0) { // for < 1m show cm or "now"
            "Now" // Or String.format("%.0f cm", distanceMeters * 100) but "Now" is common
        } else if (distanceMeters < 10.0) { // Distances like 9.5m
            String.format("%.0f m", distanceMeters) // Show without decimal for values like 7m, 8m, 9m
        } else if (distanceMeters < 50.0 && distanceMeters >=10.0) { // For 10m to 49m, round to nearest 5 or 10
            String.format("%.0f m", (Math.round(distanceMeters / 5.0) * 5.0))
        }
        else if (distanceMeters < 1000.0) { // From 50m up to 999m
            // Round to nearest 10m for cleaner display (e.g., 50m, 60m, not 53m)
            String.format("%.0f m", (Math.round(distanceMeters / 10.0) * 10.0))
        } else { // Kilometers
            val km = distanceMeters / 1000.0
            if (km < 10.0) { // e.g. 1.2 km, 9.8 km
                String.format("%.1f km", km)
            } else { // e.g. 10 km, 125 km
                String.format("%.0f km", km)
            }
        }
    }

    fun requestNavigationTo(destinationLatLng: LatLng) {
        viewModelScope.launch {
            // Indicate that route calculation is starting (optional)
            // LocationService.navigationState.value = NavigationState.ROUTE_CALCULATION

            var currentGpsLocation = lastKnownGpsLocation?.value

            // If current GPS location is null, wait for the next non-null value
            if (currentGpsLocation == null) {
                Log.d("NavigationViewModel", "Current GPS location is null. Waiting for a fix...")
                // Show some UI indication that we are waiting for GPS
                // You might want to emit a specific state for this, e.g., NavigationState.AWAITING_GPS
                // LocationService.navigationState.value = NavigationState.AWAITING_GPS // Define this state

                try {
                    // Wait for a non-null location, with a timeout
                    currentGpsLocation = withTimeoutOrNull(15000) { // Timeout after 15 seconds
                        lastKnownGpsLocation.filterNotNull().first() // Suspends until a non-null value is emitted
                    }

                    if (currentGpsLocation == null) {
                        Log.e("NavigationViewModel", "Timed out waiting for GPS location.")
//                        NavigationEngineService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED // Or a new GPS_TIMEOUT state
                        // Potentially inform UI: "Could not get current location."
                        return@launch
                    }
                    Log.d("NavigationViewModel", "Acquired GPS location: $currentGpsLocation")
                } catch (e: Exception) { // Catch any other exception during waiting
                    Log.e("NavigationViewModel", "Error while waiting for GPS location: ${e.message}", e)
//                    NavigationEngineService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                    return@launch
                }
            }

            // At this point, currentGpsLocation should be non-null
            val fromLatLng = LatLng(currentGpsLocation.latitude, currentGpsLocation.longitude)

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
//                        NavigationEngineService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                    }
                },
                onFailure = { exception ->
                    Log.e("NavigationViewModel", "Route request failed: ${exception.message}")
//                    NavigationEngineService._navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                }
            )
        }
    }

    private fun startLocationServiceWithRoute(routeResponse: ValhallaRouteResponse, fromPoint: LatLng, toPoint: LatLng) {
        val intent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_START_NAVIGATION
            putExtra(NavigationEngineService.EXTRA_ROUTE_RESPONSE_JSON, Gson().toJson(routeResponse))
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
