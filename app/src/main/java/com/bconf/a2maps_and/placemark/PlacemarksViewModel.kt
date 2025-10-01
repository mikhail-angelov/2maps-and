package com.bconf.a2maps_and.placemark

import android.app.Application
import android.content.Intent
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PlacemarksViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // Raw placemarks from the service
    private val rawPlacemarks: StateFlow<List<Placemark>> = PlacemarkService.placemarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // MutableStateFlow for the current location, to be updated from the Fragment
    private val _currentLocation = MutableStateFlow<Location?>(null)

    val displayItems: StateFlow<List<PlacemarkDisplayItem>> =
        rawPlacemarks.combine(_currentLocation) { placemarks, location ->
            val mappedItems = placemarks.map { placemark ->
                var distanceString = "N/A"
                var calculatedDistanceInMeters: Float? = null

                if (location != null) {
                    val results = FloatArray(1)
                    try {
                        Location.distanceBetween(
                            location.latitude, location.longitude,
                            placemark.latitude, placemark.longitude,
                            results
                        )
                        calculatedDistanceInMeters = results[0]
                        distanceString = if (calculatedDistanceInMeters < 1000) {
                            "${calculatedDistanceInMeters.toInt()} m"
                        } else {
                            "${(calculatedDistanceInMeters / 1000).toInt()} km"
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e("PlacemarksViewModel", "Error calculating distance for ${placemark.name}", e)
                        distanceString = "Error" // Or "N/A"
                        calculatedDistanceInMeters = null
                    }
                }
                PlacemarkDisplayItem(placemark, distanceString, calculatedDistanceInMeters)
            }
            // Sort the items
            mappedItems.sortedWith(compareBy {
                if (it.distanceInMeters == null) Float.MAX_VALUE else it.distanceInMeters
            })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateCurrentLocation(location: Location?) {
        _currentLocation.value = location
    }

    fun addPlacemark(placemark: Placemark) {
        Log.d("PlacemarksViewModel", "Adding placemark: $placemark")
        val serviceIntent = Intent(app, PlacemarkService::class.java).apply {
            action = PlacemarkService.ACTION_ADD_PLACEMARK
            putExtra(
                PlacemarkService.EXTRA_PLACEMARK,
                Gson().toJson(placemark)
            )
        }
        app.startService(serviceIntent)
    }
}
