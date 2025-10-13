package com.bconf.a2maps_and.track

import android.app.Application
import android.util.Log
import android.util.Xml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class TrackViewModel(application: Application) : AndroidViewModel(application) {

    private val _tracks = MutableLiveData<List<File>>()
    val tracks: LiveData<List<File>> = _tracks

    private val _trackPoints = MutableStateFlow<List<LatLng>>(emptyList())
    val trackPoints = _trackPoints.asStateFlow()

    // The GpxLogger also uses this directory, ensuring they are in sync.
    private val gpxDir = File(getApplication<Application>().filesDir, "gpx")

    init {
        Log.d("TrackViewModel", "Initializing TrackViewModel")
    }

    /**
     * Loads the list of .gpx track files from the dedicated 'gpx' directory.
     * This is the same directory GpxLogger writes to.
     */
    fun loadTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            if (gpxDir.exists() && gpxDir.isDirectory) {
                val trackFiles = gpxDir.listFiles { _, name -> name.endsWith(".gpx") }
                    ?.sortedByDescending { it.lastModified() } // Sort by most recent

                withContext(Dispatchers.Main) {
                    _tracks.value = trackFiles?.toList() ?: emptyList()
                }
            } else {
                withContext(Dispatchers.Main) {
                    _tracks.value = emptyList()
                }
            }
        }
    }

    /**
     * Deletes a specific track file and reloads the track list.
     * @param trackFile The file to be deleted.
     */
    fun deleteTrack(trackFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (trackFile.exists()) {
                    if (trackFile.delete()) {
                        Log.d("TrackViewModel", "Successfully deleted track: ${trackFile.name}")
                        // After deleting, reload the tracks to update the UI
                        loadTracks()
                    } else {
                        Log.e("TrackViewModel", "Failed to delete track: ${trackFile.name}")
                    }
                } else {
                    Log.w(
                        "TrackViewModel",
                        "Track file to delete does not exist: ${trackFile.name}"
                    )
                }
            } catch (e: SecurityException) {
                Log.e(
                    "TrackViewModel",
                    "Security exception while deleting track: ${trackFile.name}",
                    e
                )
            }
        }
    }

    fun displayTrackFromFile(trackFile: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = FileInputStream(trackFile)
                val points = parseGpx(inputStream)
                _trackPoints.value = points
                Log.d("TrackViewModel", "Loaded ${points.size} points from ${trackFile.name}")
            } catch (e: Exception) {
                Log.e("TrackViewModel", "Error parsing track file", e)
                _trackPoints.value = emptyList() // Clear on error
            }
        }
    }

    fun clearTrack(){
        _trackPoints.value = emptyList()
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun parseGpx(inputStream: InputStream): List<LatLng> {
        val points = mutableListOf<LatLng>()
        inputStream.use { stream ->
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals(
                        "trkpt",
                        ignoreCase = true
                    )
                ) {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) {
                        points.add(LatLng(lat, lon))
                    }
                }
                eventType = parser.next()
            }
        }
        Log.d("TrackViewModel", "Parsed ${points.size} points from GPX file.")
        return points
    }
}
