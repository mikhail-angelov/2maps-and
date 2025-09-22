package com.bconf.a2maps_and.placemark

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException

class PlacemarkService(private val context: Context) {

    private val placemarksFile = File(context.filesDir, "placemarks.json")
    private val gson = Gson()
    private var placemarks: MutableList<Placemark> = mutableListOf()

    init {
        loadPlacemarks()
    }

    fun getPlacemarks(): List<Placemark> {
        return placemarks
    }

    fun addPlacemark(placemark: Placemark) {
        placemarks.add(placemark)
        savePlacemarks()
    }

    fun updatePlacemark(updatedPlacemark: Placemark) {
        val index = placemarks.indexOfFirst { it.id == updatedPlacemark.id }
        if (index != -1) {
            placemarks[index] = updatedPlacemark
            savePlacemarks()
        }
    }

    fun deletePlacemark(placemarkId: String) {
        placemarks.removeAll { it.id == placemarkId }
        savePlacemarks()
    }

    private fun loadPlacemarks() {
        if (!placemarksFile.exists()) {
            return
        }
        try {
            FileReader(placemarksFile).use { reader ->
                val placemarkListType = object : TypeToken<List<Placemark>>() {}.type
                placemarks = gson.fromJson(reader, placemarkListType)
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle error
        }
    }

    private fun savePlacemarks() {
        try {
            FileWriter(placemarksFile).use { writer ->
                gson.toJson(placemarks, writer)
            }
        } catch (e: IOException) {e.printStackTrace()
            // Handle error
        }
    }
}
