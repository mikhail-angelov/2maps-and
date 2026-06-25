package com.bconf.a2maps_and.location

import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.geometry.LatLng

enum class LocationMode {
    INACTIVE,
    FOLLOW,
    RECORD,
    FOLLOW_AND_RECORD
}

class LocationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _locationMode = MutableStateFlow(LocationMode.INACTIVE)
    val locationMode: StateFlow<LocationMode> = _locationMode.asStateFlow()

    init {
        val prefs =
            app.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
        val savedModeName = prefs.getString(
            LocationService.KEY_LOCATION_MODE,
            LocationMode.INACTIVE.name
        )
        _locationMode.value = LocationMode.valueOf(savedModeName ?: LocationMode.INACTIVE.name)
    }

    fun onCenterButtonClicked() {
        if (_locationMode.value != LocationMode.INACTIVE) {
            _locationMode.value = LocationMode.INACTIVE
            setLocationMode(LocationMode.INACTIVE)
        }
    }

    fun setLocationModeFromMenu(mode: LocationMode) {
        _locationMode.value = mode
        setLocationMode(mode)
    }

    private fun setLocationMode(mode: LocationMode) {
        val intent = Intent(app, LocationService::class.java).apply {
            action = LocationService.ACTION_SET_LOCATION_MODE
            putExtra(LocationService.EXTRA_LOCATION_MODE, mode.name)
        }
        app.startService(intent)
    }

    fun setZoomLevel(zoom: Double) {
        val intent = Intent(app, LocationService::class.java).apply {
            action = LocationService.ACTION_SET_ZOOM_LEVEL
            putExtra(LocationService.EXTRA_ZOOM_LEVEL, zoom)
        }
        app.startService(intent)
    }

    val lastKnownGpsLocation: StateFlow<Location?> = LocationService.lastLocation
        .map { location ->
            if (location.latitude == 0.0 && location.longitude == 0.0) null else location
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val recordedPath: StateFlow<List<LatLng>> = LocationService.recordedPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recordingStartedAtMillis: StateFlow<Long?> = LocationService.recordingStartedAtMillis
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val zoomLevel: StateFlow<Double> = LocationService.zoomLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15.0)

    override fun onCleared() {
        super.onCleared()
        Log.d("LocationViewModel", "onCleared")
    }
}
