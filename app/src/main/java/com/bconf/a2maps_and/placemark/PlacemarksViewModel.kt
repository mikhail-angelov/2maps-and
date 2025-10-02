package com.bconf.a2maps_and.placemark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.util.Log
import androidx.compose.ui.input.key.type
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

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
    fun deletePlacemark(id: String) {
        Log.d("PlacemarksViewModel", "Deleting placemark with ID: $id")
    }

    suspend fun importPlacemarksFromUri(uri: Uri): Boolean {
        return withContext(Dispatchers.IO) { // Perform file I/O and parsing on a background thread
            try {
                app.applicationContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    InputStreamReader(inputStream).use { reader ->
                        val gson = Gson()
                        val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                        val importedPlacemarks: List<Placemark>? = gson.fromJson(reader, placemarkListType)

                        if (importedPlacemarks == null) {
                            Log.e("PlacemarksViewModel", "Failed to parse placemarks from JSON, result is null.")
                            return@withContext false
                        }

                        Log.d("PlacemarksViewModel", "Successfully parsed ${importedPlacemarks.size} placemarks from URI.")

                        var importCount = 0
                        importedPlacemarks.forEach { importedPlacemark ->
                            // Optional: Check for duplicates based on ID or other criteria if needed.
                            // For this example, we'll add all of them.
                            // If IDs should be unique and you expect potential clashes,
                            // you might want to generate new IDs or have a conflict resolution strategy.

                            // We will directly use the addPlacemark function which sends it to the service
                            // The PlacemarkService should handle the actual addition to its list and persistence
                            addPlacemark(importedPlacemark) // This internally calls startService
                            importCount++
                            Log.d("PlacemarksViewModel", "Sent imported placemark to service: ${importedPlacemark.name}")
                        }
                        Log.d("PlacemarksViewModel", "Finished sending $importCount placemarks to PlacemarkService.")
                        true // Indicate success
                    }
                } ?: false // InputStream was null
            } catch (e: Exception) {
                Log.e("PlacemarkViewModel", "Error importing placemarks from URI", e)
                false
            }
        }
    }
}
