package com.bconf.a2maps_and.navigation

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bconf.a2maps_and.location.LocationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoadDataState(
    val files: List<RoadSegment> = emptyList(),
    val busy: Boolean = false,
    val progress: RoadDownloadProgress? = null,
    val regions: List<RoadDataRegion> = emptyList(),
    val message: String? = null,
)

class RoadDataViewModel(application: Application) : AndroidViewModel(application) {
    private val data = RoutingDataStore(application)
    private val downloader = RoadDataDownloader(data)
    private val mutableState = MutableStateFlow(RoadDataState())
    val state = mutableState.asStateFlow()
    private var downloadJob: Job? = null

    init {
        viewModelScope.launch { refreshFiles() }
        viewModelScope.launch {
            LocationService.lastLocation.collect { location ->
                val point = GeoPoint(location.latitude, location.longitude)
                val regions = if (point.isValid() && point != GeoPoint(0.0, 0.0)) RoadDataRegions.nearby(point) else emptyList()
                mutableState.update { it.copy(regions = regions) }
            }
        }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || state.value.busy) return
        mutableState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            var imported = 0
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    for (uri in uris) {
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
                        val stream = resolver.openInputStream(uri) ?: error("Could not open the selected file.")
                        stream.use { data.importSegment(name, it) }
                        imported++
                    }
                }
                mutableState.update { it.copy(message = "Copied $imported road data file(s) into app storage. Originals can now be deleted.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "Copied $imported file(s). Cannot import the remaining data. Check the files and available storage.") }
            } finally {
                withContext(NonCancellable) { refreshFiles() }
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun download(names: List<String>) {
        if (names.isEmpty() || state.value.busy) return
        val selected = names.distinct()
        mutableState.update { it.copy(busy = true, progress = RoadDownloadProgress(selected.first(), 0, -1), message = null) }
        downloadJob = viewModelScope.launch {
            var completed = 0
            try {
                for (name in selected) {
                    downloader.download(name) { progress -> mutableState.update { it.copy(progress = progress) } }
                    completed++
                }
                mutableState.update { it.copy(message = "Downloaded $completed road data file(s) into app storage.") }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(message = "Download cancelled. Completed files remain available.") }
                throw cancelled
            } catch (error: Exception) {
                mutableState.update { it.copy(message = "Downloaded $completed file(s). ${error.message ?: "Download failed. Check the connection and available storage."}") }
            } finally {
                withContext(NonCancellable) { refreshFiles() }
                mutableState.update { it.copy(busy = false, progress = null) }
                downloadJob = null
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

    private suspend fun refreshFiles() {
        val files = withContext(Dispatchers.IO) { data.segments() }
        mutableState.update { it.copy(files = files) }
    }
}
