package com.bconf.a2maps_and.placemark

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader

class PlacemarkService : Service() {

    private val binder = LocalBinder()
    private lateinit var placemarksFile: File
    private lateinit var gasStationsFile: File
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: SharedPreferences

    private data class FeatureCollection(val features: List<Feature>)
    private data class Feature(val id: String, val properties: Properties, val geometry: Geometry)
    private data class Properties(
        val name: String?,
        @SerializedName("fuel:octane_92") val fuelOctane92: String?
    )

    private data class Geometry(val type: String, val coordinates: List<Double>)

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_ADD_PLACEMARK = "ACTION_ADD_PLACEMARK"
        const val ACTION_UPDATE_PLACEMARK = "ACTION_UPDATE_PLACEMARK"
        const val ACTION_DELETE_PLACEMARK = "ACTION_DELETE_PLACEMARK"
        const val ACTION_IMPORT_PLACEMARKS_FROM_URI = "ACTION_IMPORT_PLACEMARKS_FROM_URI"
        const val ACTION_IMPORT_GAS_STATIONS_FROM_URI = "ACTION_IMPORT_GAS_STATIONS_FROM_URI"
        const val ACTION_TOGGLE_GAS_LAYER_VISIBILITY = "ACTION_TOGGLE_GAS_LAYER_VISIBILITY"
        const val EXTRA_PLACEMARK = "EXTRA_PLACEMARK"

        private const val PREFS_NAME = "PlacemarkServicePrefs"
        private const val GAS_LAYER_VISIBILITY_KEY = "gas_layer_visibility"

        private val _placemarks = MutableStateFlow<List<Placemark>>(emptyList())
        val placemarks: StateFlow<List<Placemark>> = _placemarks.asStateFlow()

        private val _gasStations = MutableStateFlow<List<Placemark>>(emptyList())
        val gasStations: StateFlow<List<Placemark>> = _gasStations.asStateFlow()

        private val _isGasLayerVisible = MutableStateFlow(false)
        val isGasLayerVisible: StateFlow<Boolean> = _isGasLayerVisible.asStateFlow()
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlacemarkService = this@PlacemarkService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _isGasLayerVisible.value = prefs.getBoolean(GAS_LAYER_VISIBILITY_KEY, false)
        placemarksFile = File(filesDir, "placemarks.json")
        gasStationsFile = File(filesDir, "gas_stations.json")
        loadPlacemarks()
        loadGasStations()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("PlacemarkService", "onStartCommand, action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                // Handle start action if needed
            }

            ACTION_ADD_PLACEMARK -> {
                intent.getStringExtra(EXTRA_PLACEMARK)?.let { placemarkJson ->
                    Gson().fromJson(placemarkJson, Placemark::class.java)?.let {
                        addPlacemark(it)
                    }
                }
            }

            ACTION_UPDATE_PLACEMARK -> {
                intent.getStringExtra(EXTRA_PLACEMARK)?.let { placemarkJson ->
                    try {
                        val updatedPlacemark = Gson().fromJson(placemarkJson, Placemark::class.java)
                        updatePlacemark(updatedPlacemark)
                    } catch (e: Exception) {
                        Log.e("PlacemarkService", "Error deserializing placemark for update", e)
                    }
                }
            }

            ACTION_IMPORT_PLACEMARKS_FROM_URI -> {
                intent.data?.let { uri ->
                    serviceScope.launch {
                        importPlacemarksFromFile(uri)
                    }
                }
            }

            ACTION_IMPORT_GAS_STATIONS_FROM_URI -> {
                intent.data?.let { uri ->
                    serviceScope.launch {
                        importGasStationsFromFile(uri)
                    }
                }
            }

            ACTION_TOGGLE_GAS_LAYER_VISIBILITY -> {
                toggleGasLayerVisibility()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    fun addPlacemark(placemark: Placemark) {
        _placemarks.update { it + placemark }
        savePlacemarks()
    }

    fun updatePlacemark(updatedPlacemark: Placemark) {
        _placemarks.update { placemarks ->
            placemarks.map { if (it.id == updatedPlacemark.id) updatedPlacemark else it }
        }
        savePlacemarks()
    }

    fun deletePlacemark(placemarkId: String) {
        _placemarks.update { placemarks ->
            placemarks.filterNot { it.id == placemarkId }
        }
        savePlacemarks()
    }

    private fun importPlacemarksFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                    val importedPlacemarks: List<Placemark> =
                        Gson().fromJson(reader, placemarkListType) ?: emptyList()
                    upsertPlacemarks(importedPlacemarks)
                    Log.d(
                        "PlacemarkService",
                        "Successfully imported/updated ${importedPlacemarks.size} placemarks."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("PlacemarkService", "Error importing placemarks from URI", e)
        }
    }

    private fun upsertPlacemarks(placemarksToUpsert: List<Placemark>) {
        _placemarks.update { currentPlacemarks ->
            val existingPlacemarksMap = currentPlacemarks.associateBy { it.id }.toMutableMap()
            placemarksToUpsert.forEach {
                existingPlacemarksMap[it.id] = it
            }
            existingPlacemarksMap.values.toList()
        }
        savePlacemarks()
    }

    private fun importGasStationsFromFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    val gson = Gson()
                    val jsonElement = gson.fromJson(reader, com.google.gson.JsonElement::class.java)

                    val features: List<Feature> = when {
                        jsonElement.isJsonObject && jsonElement.asJsonObject.has("features") -> {
                            gson.fromJson(jsonElement, FeatureCollection::class.java)?.features
                                ?: emptyList()
                        }

                        jsonElement.isJsonArray -> {
                            gson.fromJson(jsonElement, object : TypeToken<List<Feature>>() {}.type)
                                ?: emptyList()
                        }

                        else -> emptyList()
                    }

                    val importedGasStations = features.mapNotNull { feature ->
                        if (feature.geometry.type == "Point" && feature.geometry.coordinates.size >= 2) {
                            Placemark(
                                id = feature.id,
                                name = feature.properties.name ?: "Unnamed Gas Station",
                                longitude = feature.geometry.coordinates[0],
                                latitude = feature.geometry.coordinates[1],
                                rate = 0,
                                description = if (feature.properties.fuelOctane92 != null) "92" else "",
                                timestamp = System.currentTimeMillis(),
                            )
                        } else {
                            null
                        }
                    }

                    upsertGasStations(importedGasStations)
                    Log.d(
                        "PlacemarkService",
                        "Successfully imported/updated ${importedGasStations.size} gas stations."
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("PlacemarkService", "Error importing gas stations from URI", e)
        }
    }

    private fun upsertGasStations(gasStationsToUpsert: List<Placemark>) {
        _gasStations.update { currentGasStations ->
            val existingGasStationsMap = currentGasStations.associateBy { it.id }.toMutableMap()
            gasStationsToUpsert.forEach {
                existingGasStationsMap[it.id] = it
            }
            existingGasStationsMap.values.toList()
        }
        saveGasStations()
    }

    private fun loadPlacemarks() {
        if (!placemarksFile.exists()) {
            _placemarks.value = emptyList()
            return
        }
        try {
            FileReader(placemarksFile).use { reader ->
                val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                val loadedPlacemarks: List<Placemark> =
                    Gson().fromJson(reader, placemarkListType) ?: emptyList()
                _placemarks.value = loadedPlacemarks
            }
        } catch (e: IOException) {
            e.printStackTrace()
            _placemarks.value = emptyList()
        }
    }

    private fun loadGasStations() {
        if (!gasStationsFile.exists()) {
            _gasStations.value = emptyList()
            return
        }
        try {
            FileReader(gasStationsFile).use { reader ->
                val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                val loadedGasStations: List<Placemark> =
                    Gson().fromJson(reader, placemarkListType) ?: emptyList()
                _gasStations.value = loadedGasStations
            }
        } catch (e: IOException) {
            e.printStackTrace()
            _gasStations.value = emptyList()
        }
    }

    private fun savePlacemarks() {
        serviceScope.launch {
            try {
                FileWriter(placemarksFile).use { writer ->
                    Gson().toJson(_placemarks.value, writer)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun saveGasStations() {
        serviceScope.launch {
            try {
                FileWriter(gasStationsFile).use { writer ->
                    Gson().toJson(_gasStations.value, writer)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleGasLayerVisibility() {
        _isGasLayerVisible.update { currentState ->
            val newState = !currentState
            prefs.edit().putBoolean(GAS_LAYER_VISIBILITY_KEY, newState).apply()
            newState
        }
    }
}