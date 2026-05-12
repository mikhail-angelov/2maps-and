package com.bconf.a2maps_and.navigation

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

enum class CenterOnLocationState {
    INACTIVE,
    FOLLOW,
    RECORD,
    FOLLOW_AND_RECORD
}

class NavigationViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    private val _centerOnLocationState = MutableStateFlow(CenterOnLocationState.INACTIVE)
    val centerOnLocationState: StateFlow<CenterOnLocationState> =
        _centerOnLocationState.asStateFlow()

    init {
        val prefs =
            app.getSharedPreferences(NavigationEngineService.PREFS_NAME, Context.MODE_PRIVATE)
        val savedStateName = prefs.getString(
            NavigationEngineService.KEY_CENTER_ON_LOCATION_STATE,
            CenterOnLocationState.INACTIVE.name
        )
        _centerOnLocationState.value =
            CenterOnLocationState.valueOf(savedStateName ?: CenterOnLocationState.INACTIVE.name)
    }

    /**
     * Short tap on FAB: if currently active (not INACTIVE), reset to INACTIVE.
     */
    fun onCenterButtonClicked() {
        if (_centerOnLocationState.value != CenterOnLocationState.INACTIVE) {
            _centerOnLocationState.value = CenterOnLocationState.INACTIVE
            setCenterOnLocationState(CenterOnLocationState.INACTIVE)
        }
    }

    fun setCenterOnLocationStateFromMenu(state: CenterOnLocationState) {
        _centerOnLocationState.value = state
        setCenterOnLocationState(state)
    }

    private fun setCenterOnLocationState(state: CenterOnLocationState) {
        val intent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_SET_CENTER_ON_LOCATION_STATE
            putExtra(NavigationEngineService.EXTRA_CENTER_ON_LOCATION_STATE, state.name)
        }
        app.startService(intent)
    }

    fun setZoomLevel(zoom: Double) {
        val intent = Intent(app, NavigationEngineService::class.java).apply {
            action = NavigationEngineService.ACTION_SET_ZOOM_LEVEL
            putExtra(NavigationEngineService.EXTRA_ZOOM_LEVEL, zoom)
        }
        app.startService(intent)
    }

    val lastKnownGpsLocation: StateFlow<Location?> = NavigationEngineService.lastLocation
        .map { location ->
            if (location.latitude == 0.0 && location.longitude == 0.0) null else location
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    val recordedPath: StateFlow<List<LatLng>> = NavigationEngineService.recordedPath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val zoomLevel: StateFlow<Double> = NavigationEngineService.zoomLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15.0)

    override fun onCleared() {
        super.onCleared()
        Log.d("NavigationViewModel", "onCleared")
    }
}
