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
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
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

    init {
        setupTrackLayer()
    }

    private fun setupTrackLayer() {
        if (map.style?.isFullyLoaded == true) {
            val source = GeoJsonSource(sourceId)
            layer = LineLayer(layerId, sourceId).withProperties(
                PropertyFactory.lineColor(ContextCompat.getColor(context, R.color.purple_500)),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.visibility(Property.NONE)
            )

            map.style?.addSource(source)
            map.style?.addLayer(layer!!)
            observeTracks()
        } else {
            Log.e("TrackLayerManager", "Map style is not loaded, cannot add track layer.")
        }
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
                        //zoom to track
                        if (trackPoints.size > 1) {
                            val boundsBuilder = LatLngBounds.Builder()
                            for (point in trackPoints) {
                                boundsBuilder.include(point)
                            }
                            val bounds = boundsBuilder.build()

                            // Calculate the distance span of the bounds
                            val latSpan = bounds.latitudeSpan
                            val lonSpan = bounds.longitudeSpan
                            val zoomThreshold = 0.005 // Approx 500 meters. Adjust as needed.

                            // If the bounds are very small, just center with a fixed zoom.
                            if (latSpan < zoomThreshold && lonSpan < zoomThreshold) {
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(bounds.center, 17.0), 1500
                                )
                            } else {
                                // Otherwise, animate camera to the calculated bounds with padding.
                                map.animateCamera(
                                    CameraUpdateFactory.newLatLngBounds(bounds, 100), 1500
                                )
                            }
                        } else if (trackPoints.size == 1) {
                            // If only one point, just center on it
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    trackPoints.first(), 15.0
                                ), 1500
                            )
                        }
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
