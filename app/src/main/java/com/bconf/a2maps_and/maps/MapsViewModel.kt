package com.bconf.a2maps_and.maps

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import java.io.File

class MapsViewModel : ViewModel() {

    private val _maps = MutableLiveData<List<File>>()
    val maps: LiveData<List<File>> = _maps

    fun loadMaps(mapsDir: File) {
        if (mapsDir.exists() && mapsDir.isDirectory) {
            _maps.value = mapsDir.listFiles { file ->
                file.isFile && file.extension == "mbtiles"
            }?.toList() ?: emptyList()
        }
    }
}