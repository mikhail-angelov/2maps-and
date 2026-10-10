package com.bconf.a2maps_and.trip

import com.bconf.a2maps_and.maps.MapsLayerManager
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Shows the points of the trip being driven: open ones in purple, visited ones grey. */
class TripLayerManager {
    private var style: Style? = null
    private var points: List<TripPoint> = emptyList()

    fun onStyleLoaded(style: Style) {
        this.style = style
        if (style.getSource(SOURCE) == null) style.addSource(GeoJsonSource(SOURCE))
        add(style, CircleLayer(CIRCLES, SOURCE).withProperties(
            circleColor(switchCase(get("visited"), literal(VISITED), literal(OPEN))),
            circleRadius(7f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f)))
        add(style, SymbolLayer(LABELS, SOURCE).withProperties(
            textField(get("name")), textFont(arrayOf("Noto Sans Bold")), textSize(12f), textColor(TEXT),
            textHaloColor("#FFFFFF"), textHaloWidth(1.5f), textOffset(arrayOf(0f, 1.3f)), textAnchor("top"),
            textOptional(true)).apply { minZoom = LABELS_MIN_ZOOM })
        show(points)
    }

    fun show(points: List<TripPoint>) {
        this.points = points
        val loaded = style?.takeIf { it.isFullyLoaded } ?: return
        loaded.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(points.map {
            Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply {
                addStringProperty("name", it.name)
                addBooleanProperty("visited", it.visited)
            }
        }))
    }

    fun cleanup() { style = null }

    private fun add(style: Style, layer: Layer) {
        if (style.getLayer(layer.id) != null) return
        // The position marker stays on top of the trip points.
        if (style.getLayer(MapsLayerManager.CURRENT_LOCATION_LAYER_ID) != null) style.addLayerBelow(layer, MapsLayerManager.CURRENT_LOCATION_LAYER_ID)
        else style.addLayer(layer)
    }

    private companion object {
        const val SOURCE = "trip-source"
        const val CIRCLES = "trip-points"
        const val LABELS = "trip-labels"
        const val OPEN = "#7B1FA2"
        const val VISITED = "#9E9E9E"
        const val TEXT = "#4A148C"
        const val LABELS_MIN_ZOOM = 12f
    }
}
