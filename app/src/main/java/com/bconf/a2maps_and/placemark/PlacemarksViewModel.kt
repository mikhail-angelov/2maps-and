package com.bconf.a2maps_and.placemark

import android.app.Application
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.utils.PlacemarkUtils
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PlacemarksViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // Raw placemarks from the service
    private val rawPlacemarks: StateFlow<List<Placemark>> = PlacemarkService.placemarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawGasStations: StateFlow<List<Placemark>> = PlacemarkService.gasStations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isGasLayerVisible: StateFlow<Boolean> = PlacemarkService.isGasLayerVisible
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // MutableStateFlow for the current location, to be updated from the Fragment
    private val _currentLocation = MutableStateFlow<Location?>(null)

    val displayItems: StateFlow<List<PlacemarkDisplayItem>> =
        rawPlacemarks.combine(_currentLocation) { placemarks, location ->
            val mappedItems = placemarks.map { placemark ->
                val distanceResult = PlacemarkUtils.calculateDistance(location, placemark)
                PlacemarkDisplayItem(placemark, distanceResult.distanceString, distanceResult.distanceInMeters)
            }
            // Sort the items
            mappedItems.sortedWith(compareBy {
                if (it.distanceInMeters == null) Float.MAX_VALUE else it.distanceInMeters
            })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gasStationItems: StateFlow<List<PlacemarkDisplayItem>> =
        rawGasStations.combine(_currentLocation) { placemarks, location ->
            val mappedItems = placemarks.map { placemark ->
                val distanceResult = PlacemarkUtils.calculateDistance(location, placemark)
                PlacemarkDisplayItem(placemark, distanceResult.distanceString, distanceResult.distanceInMeters)
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
    fun updatePlacemark(placemark: Placemark) {
        Log.d("PlacemarksViewModel", "Updating placemark: ${placemark.name}")
        val serviceIntent = Intent(app, PlacemarkService::class.java).apply {
            action = PlacemarkService.ACTION_UPDATE_PLACEMARK // You will need to define this action
            putExtra(
                PlacemarkService.EXTRA_PLACEMARK,
                Gson().toJson(placemark)
            )
        }
        app.startService(serviceIntent)
    }
    fun deletePlacemark(id: String) {
        Log.d("PlacemarksViewModel", "Deleting placemark with ID: $id")
    }

    suspend fun importPlacemarksFromUri(uri: Uri): Boolean {
        Log.d("PlacemarksViewModel", "Sending import request to service for URI: $uri")
        val serviceIntent = Intent(app, PlacemarkService::class.java).apply {
            action = PlacemarkService.ACTION_IMPORT_PLACEMARKS_FROM_URI
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        app.startService(serviceIntent)
        return true
    }

    suspend fun importGasStationsFromUri(uri: Uri): Boolean {
        Log.d("PlacemarksViewModel", "Sending import gas stations request to service for URI: $uri")
        val serviceIntent = Intent(app, PlacemarkService::class.java).apply {
            action = PlacemarkService.ACTION_IMPORT_GAS_STATIONS_FROM_URI
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        app.startService(serviceIntent)
        return true
    }

    fun toggleGasLayerVisibility() {
        val serviceIntent = Intent(app, PlacemarkService::class.java).apply {
            action = PlacemarkService.ACTION_TOGGLE_GAS_LAYER_VISIBILITY
        }
        app.startService(serviceIntent)
    }
}
