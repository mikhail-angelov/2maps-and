package com.bconf.a2maps_and.navigation

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RoadDataState(val files: List<RoadSegment> = emptyList(), val importing: Boolean = false, val message: String? = null)

class RoadDataViewModel(application: Application) : AndroidViewModel(application) {
    private val data = RoutingDataStore(application)
    private val mutableState = MutableStateFlow(RoadDataState())
    val state = mutableState.asStateFlow()
    init { viewModelScope.launch {
        val files = withContext(Dispatchers.IO) { data.segments() }
        mutableState.value = mutableState.value.copy(files = files)
    } }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty() || state.value.importing) return
        mutableState.value = state.value.copy(importing = true, message = null)
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val resolver = getApplication<Application>().contentResolver
                for (uri in uris) {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: ""
                    val stream = resolver.openInputStream(uri) ?: error("Could not open the selected file.")
                    stream.use { data.importSegment(name, it) }
                }
            } }
            val message = result.fold({ "Imported ${uris.size} road data file(s)." }, {
                if (it is IllegalArgumentException) it.message else "Cannot import road data. Check the file and available storage."
            })
            mutableState.value = RoadDataState(withContext(Dispatchers.IO) { data.segments() }, message = message)
        }
    }
}
