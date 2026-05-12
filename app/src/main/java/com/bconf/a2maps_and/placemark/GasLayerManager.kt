package com.bconf.a2maps_and.placemark

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

class GasLayerManager(
    private val context: Context,
    private val map: MapLibreMap,
    private val placemarksViewModel: PlacemarksViewModel, // Pass in the ViewModel
    private val lifecycle: Lifecycle,
    private val onGasStationClickListener: (String) -> Unit
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val SOURCE_ID = "gas-stations-source"
        private const val CIRCLE_LAYER_ID = "gas-stations-circle-layer"
        private const val TEXT_LAYER_ID = "gas-stations-text-layer"

        const val PROPERTY_ID = "id"
        const val PROPERTY_NAME = "name"
        const val PROPERTY_DESCRIPTION = "description"
        const val PROPERTY_RATE = "rate"
    }

    fun onStyleLoaded(style: Style) {
        setupSource(style)
        setupCircleLayer(style)
        setupTextLayer(style)
        observeGasStations()
        observeVisibility()
    }

    private fun observeVisibility() {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                placemarksViewModel.isGasLayerVisible.collectLatest { isVisible ->
                    Log.d("GasLayerManager", "Gas layer visibility changed to: $isVisible")
                    map.getStyle { style ->
                        val visibility = if (isVisible) Property.VISIBLE else Property.NONE
                        style.getLayer(CIRCLE_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
                        style.getLayer(TEXT_LAYER_ID)?.setProperties(PropertyFactory.visibility(visibility))
                    }
                }
            }
        }
    }


    private fun observeGasStations() {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                placemarksViewModel.gasStationItems.collectLatest { gasStations ->
                    Log.d(
                        "GasLayerManager",
                        "Observed ${gasStations.size} gas stations from ViewModel."
                    )
                    updateGasStations(gasStations)
                }
            }
        }

    }

    fun handleMapClick(point: LatLng): Boolean {
        val screenPoint = map.projection.toScreenLocation(point)
        // Query features on the specific placemark layer
        val features = map.queryRenderedFeatures(screenPoint, CIRCLE_LAYER_ID)

        if (features.isNotEmpty()) {
            val clickedFeature = features[0] // Get the top-most feature if overlapping
            return onGasStationClicked(clickedFeature)
        }
        return false // No gas station feature was clicked
    }

    private fun setupSource(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID))
        }
    }


    private fun setupCircleLayer(style: Style) {
        if (style.getLayer(CIRCLE_LAYER_ID) == null) {
            Log.d("GasLayerManager", "Setting up CircleLayer: $CIRCLE_LAYER_ID")
            val circleLayer = CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID)
                .withProperties(
                    PropertyFactory.circleColor(Color.RED), // Set circle color to red for gas stations
                    PropertyFactory.circleRadius(8f),       // Set circle radius (in pixels)
                    PropertyFactory.circleStrokeColor(Color.BLACK), // Optional: circle stroke color
                    PropertyFactory.circleStrokeWidth(1.5f),    // Optional: circle stroke width
                    PropertyFactory.visibility(Property.NONE)
                )
            style.addLayer(circleLayer)
        } else {
            Log.d(
                "GasLayerManager",
                "CircleLayer $CIRCLE_LAYER_ID already exists."
            )
        }
    }

    private fun setupTextLayer(style: Style) {
        if (style.getLayer(TEXT_LAYER_ID) == null) {
            val textLayer = SymbolLayer(TEXT_LAYER_ID, SOURCE_ID)
                .withProperties(
                    PropertyFactory.textField("{$PROPERTY_NAME}"),
                    PropertyFactory.textFont(arrayOf("Arial Unicode Regular")), // [1] Standard font stack
                    PropertyFactory.textColor(Color.RED),
                    PropertyFactory.textHaloColor(Color.WHITE),
                    PropertyFactory.textHaloWidth(1.0f),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM), // Anchor text above the circle's center
                    PropertyFactory.textOffset(
                        arrayOf(
                            0f,
                            -2f // Offset text slightly for gas stations
                        )
                    ),
                    PropertyFactory.textAllowOverlap(true), // Avoid text disappearing if circles are close
                    PropertyFactory.textIgnorePlacement(true),
                    PropertyFactory.visibility(Property.NONE)
                )
            style.addLayer(textLayer)
        }
    }

    fun updateGasStations(gasStations: List<PlacemarkDisplayItem>) {
        val featureCollection = buildFeatureCollection(gasStations, "GasLayerManager")
        map.getStyle { style ->
            style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(featureCollection)
        }
    }

    private fun onGasStationClicked(feature: Feature): Boolean {
        Log.d("GasLayerManager", "Clicked on gas station: ${feature.toJson()}")
        feature.getStringProperty("id")?.let { placemarkId ->
            onGasStationClickListener(placemarkId) // Invoke the callback
            return true
        }
        return false
    }

    fun cleanup() {
        scope.cancel()
        map.getStyle { style ->
            if (style.isFullyLoaded) {
                style.removeLayer(CIRCLE_LAYER_ID)
                style.removeLayer(TEXT_LAYER_ID)
            }
        }
    }
}