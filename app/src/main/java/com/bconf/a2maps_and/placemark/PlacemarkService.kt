package com.bconf.a2maps_and.placemark

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

class PlacemarkService : Service() {

    private val binder = LocalBinder()
    private lateinit var placemarksFile: File

    companion object {

        var ACTION_START = "ACTION_START"
        val ACTION_ADD_PLACEMARK = "ACTION_ADD_PLACEMARK"
        val ACTION_UPDATE_PLACEMARK = "ACTION_UPDATE_PLACEMARK"
        val ACTION_DELETE_PLACEMARK = "ACTION_DELETE_PLACEMARK"
        val EXTRA_PLACEMARK = "EXTRA_PLACEMARK"
        val EXTRA_PLACEMARK_ID = "EXTRA_PLACEMARK_ID"

        private val _placemarks = MutableStateFlow(mutableListOf<Placemark>())
        val placemarks: StateFlow<List<Placemark>> =
            _placemarks.asStateFlow() // Expose as StateFlow<List<Placemark>>
    }
    inner class LocalBinder : Binder() {
        fun getService(): PlacemarkService = this@PlacemarkService
    }

    override fun onCreate() {
        super.onCreate()
        placemarksFile = File(filesDir, "placemarks.json")
        loadPlacemarks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NavigationEngineService", "onStartCommand, action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {

            }

            ACTION_ADD_PLACEMARK -> {
                val placemarkString = intent.getStringExtra(EXTRA_PLACEMARK)
                val placemark = Gson().fromJson(placemarkString, Placemark::class.java)
                if (placemark != null) {
                    addPlacemark(placemark)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun addPlacemark(placemark: Placemark) {
        val currentList = _placemarks.value.toMutableList()
        currentList.add(placemark)
        _placemarks.value = currentList
        savePlacemarks()
    }

    fun updatePlacemark(updatedPlacemark: Placemark) {
        val currentList = _placemarks.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedPlacemark.id }
        if (index != -1) {
            currentList[index] = updatedPlacemark
            _placemarks.value = currentList
            savePlacemarks()
        }
    }

    fun deletePlacemark(placemarkId: String) {
        val currentList = _placemarks.value.toMutableList()
        currentList.removeAll { it.id == placemarkId }
        _placemarks.value = currentList
        savePlacemarks()
    }

    private fun loadPlacemarks() {
        if (!placemarksFile.exists()) {
            _placemarks.value = mutableListOf<Placemark>() // Ensure placemarks is initialized if file doesn't exist
            return
        }
        try {
            FileReader(placemarksFile).use { reader ->
                val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                val loadedPlacemarks: List<Placemark>? = Gson().fromJson(reader, placemarkListType)
                _placemarks.value = loadedPlacemarks?.toMutableList() ?: mutableListOf<Placemark>()
            }
        } catch (e: IOException) {
            e.printStackTrace()
            _placemarks.value = mutableListOf<Placemark>() // Ensure placemarks is initialized on error
            // Handle error
        }
    }

    private fun savePlacemarks() {
        try {
            FileWriter(placemarksFile).use { writer ->
                Gson().toJson(_placemarks.value, writer)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle error
        }
    }
}
