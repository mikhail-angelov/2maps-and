package com.bconf.a2maps_and.navigation

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*

class RouteLayerManager {
    private var style: Style? = null
    private var current = NavigationState()
    private var renderedRoute: Route? = null

    fun onStyleLoaded(style: Style) {
        this.style = style
        renderedRoute = null
        if (style.getSource("navigation-route") == null) style.addSource(GeoJsonSource("navigation-route"))
        if (style.getSource("navigation-target") == null) style.addSource(GeoJsonSource("navigation-target"))
        if (style.getLayer("navigation-line") == null) style.addLayer(LineLayer("navigation-line", "navigation-route").withProperties(
            lineColor("#FF9800"), lineWidth(6f), lineOpacity(0.9f)))
        if (style.getLayer("navigation-destination") == null) style.addLayer(CircleLayer("navigation-destination", "navigation-target").withProperties(
            circleColor("#FF9800"), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
        render(current, force = true)
    }

    fun render(state: NavigationState, force: Boolean = false) {
        current = state
        val loaded = style ?: return
        if (!loaded.isFullyLoaded) return
        if (state.route !== renderedRoute || force) {
            val points = state.route?.points?.map { Point.fromLngLat(it.longitude, it.latitude) }
            val features = if (points != null) arrayOf(Feature.fromGeometry(LineString.fromLngLats(points))) else emptyArray()
            loaded.getSourceAs<GeoJsonSource>("navigation-route")?.setGeoJson(FeatureCollection.fromFeatures(features))
            renderedRoute = state.route
        }
        val target = state.destination
        loaded.getSourceAs<GeoJsonSource>("navigation-target")?.setGeoJson(FeatureCollection.fromFeatures(
            if (target == null) emptyArray() else arrayOf(Feature.fromGeometry(Point.fromLngLat(target.longitude, target.latitude)))))
        loaded.getLayer("navigation-line")?.setProperties(lineOpacity(if (state.phase == NavigationPhase.ERROR) 0.4f else 0.9f))
    }

    fun cleanup() { style = null; renderedRoute = null }
}
