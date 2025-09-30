package com.bconf.a2maps_and.placemark

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class PlacemarksViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val placemarks: StateFlow<List<Placemark>> = PlacemarkService.placemarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf<Placemark>())


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
