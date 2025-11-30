package com.bconf.a2maps_and.track

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bconf.a2maps_and.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

class TrackLayerManager(
    private val context: Context,
    private val map: MapLibreMap,
    private val trackViewModel: TrackViewModel, // Pass in the ViewModel
    private val lifecycle: Lifecycle,
) {

    private val sourceId = "track-source"
    private val layerId = "track-layer"
    private var layer: LineLayer? = null


    fun setupTrackLayer(style: Style) {
        if (!style.isFullyLoaded) {
            Log.e("TrackLayerManager", "Map style is not loaded, cannot add track layer.")
            return
        }
        val source = GeoJsonSource(sourceId)
        layer = LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor(ContextCompat.getColor(context, R.color.purple_500)),
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.visibility(Property.NONE)
        )

        style.addSource(source)
        style.addLayer(layer!!)
        observeTracks()
    }

    private fun observeTracks() {
        // Launch a coroutine that is automatically cancelled when the passed lifecycle is destroyed.
        // We use lifecycle.repeatOnLifecycle to ensure collection only happens when the
        // lifecycle is at least in the STARTED state.
        CoroutineScope(Dispatchers.Main).launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                trackViewModel.trackPoints.collectLatest { trackPoints ->
                    Log.d("TrackLayerManager", "Observed ${trackPoints.size} trackPoints.")
                    if (trackPoints.isNotEmpty()) {
                        val mapboxPoints =
                            trackPoints.map { Point.fromLngLat(it.longitude, it.latitude) }
                        val lineString = LineString.fromLngLats(mapboxPoints)
                        map.style?.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(lineString)
                        // Make the layer visible
                        layer?.setProperties(PropertyFactory.visibility(Property.VISIBLE))

                    } else {
                        // Make the layer invisible
                        layer?.setProperties(PropertyFactory.visibility(Property.NONE))
                        // Optional: Clear the data to free memory
                        map.style?.getSourceAs<GeoJsonSource>(sourceId)
                            ?.setGeoJson(LineString.fromLngLats(emptyList()))
                    }
                }
            }
        }

    }

}
