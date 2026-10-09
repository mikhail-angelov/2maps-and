package com.bconf.a2maps_and.navigation

import android.graphics.PointF
import android.graphics.RectF
import androidx.core.graphics.toColorInt
import com.bconf.a2maps_and.maps.MapsLayerManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.*
import kotlin.math.*

/** Draws the route, offered alternatives and the travelled part; [label] formats an option's time label. */
class RouteLayerManager(private val label: (Route) -> String) {
    private var style: Style? = null
    private var current = NavigationState()
    private var renderedRoute: Route? = null
    private var renderedOptions: List<Route>? = null
    private var renderedProgress: Double? = null
    private var progressScale: ProgressScale? = null

    fun onStyleLoaded(style: Style) {
        this.style = style
        renderedRoute = null; renderedOptions = null; renderedProgress = null
        if (style.getSource(ROUTE) == null) style.addSource(GeoJsonSource(ROUTE, GeoJsonOptions().withLineMetrics(true)))
        if (style.getSource(ALTERNATIVES) == null) style.addSource(GeoJsonSource(ALTERNATIVES))
        if (style.getSource(LABELS) == null) style.addSource(GeoJsonSource(LABELS))
        if (style.getSource(TARGET) == null) style.addSource(GeoJsonSource(TARGET))
        add(style, LineLayer(ALTERNATIVES_LINE, ALTERNATIVES).withProperties(
            lineColor(ALTERNATIVE_COLOR), lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        add(style, LineLayer(CASING, ROUTE).withProperties(
            lineColor("#FFFFFF"), lineWidth(10f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        add(style, LineLayer(LINE, ROUTE).withProperties(
            lineWidth(6f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineGradient(color(ACCENT.toColorInt()))))
        add(style, CircleLayer(DESTINATION, TARGET).withProperties(
            circleColor(ACCENT), circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
        add(style, SymbolLayer(LABELS_LAYER, LABELS).withProperties(
            textField(get("label")), textFont(arrayOf("Noto Sans Bold")), textSize(13f),
            textColor(switchCase(get("selected"), color(ACCENT.toColorInt()), color(TEXT.toColorInt()))),
            textHaloColor("#FFFFFF"), textHaloWidth(2f), textAllowOverlap(true)))
        render(current, force = true)
    }

    /** Route layers stay below the position marker so it is never hidden by the line. */
    private fun add(style: Style, layer: Layer) {
        if (style.getLayer(layer.id) != null) return
        if (style.getLayer(MapsLayerManager.CURRENT_LOCATION_LAYER_ID) != null) style.addLayerBelow(layer, MapsLayerManager.CURRENT_LOCATION_LAYER_ID)
        else style.addLayer(layer)
    }

    fun render(state: NavigationState, force: Boolean = false) {
        current = state
        val loaded = style ?: return
        if (!loaded.isFullyLoaded) return
        val routeChanged = state.route !== renderedRoute || force
        if (routeChanged) {
            val route = state.route
            loaded.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(
                if (route == null) emptyArray() else arrayOf(Feature.fromGeometry(line(route)))))
            progressScale = route?.let(::ProgressScale)
            renderedRoute = route
            renderedProgress = null
        }
        val options = if (state.phase == NavigationPhase.SELECTING) state.options else emptyList()
        if (options != renderedOptions || routeChanged) {
            loaded.getSourceAs<GeoJsonSource>(ALTERNATIVES)?.setGeoJson(FeatureCollection.fromFeatures(options.mapIndexedNotNull { index, option ->
                if (option === state.route) null else Feature.fromGeometry(line(option)).apply { addNumberProperty("option", index) }
            }))
            loaded.getSourceAs<GeoJsonSource>(LABELS)?.setGeoJson(FeatureCollection.fromFeatures(
                if (options.size < 2) emptyList() else options.mapIndexed { index, option ->
                    val middle = option.points[option.points.size / 2]
                    Feature.fromGeometry(Point.fromLngLat(middle.longitude, middle.latitude)).apply {
                        addNumberProperty("option", index)
                        addStringProperty("label", label(option))
                        addBooleanProperty("selected", option === state.route)
                    }
                }))
            renderedOptions = options
        }
        val progress = state.progressMeters?.takeIf { state.phase == NavigationPhase.ACTIVE || state.phase == NavigationPhase.ARRIVED }
        if (progress != renderedProgress || force) {
            val fraction = progress?.let { progressScale?.fraction(it) }
            loaded.getLayer(LINE)?.setProperties(lineGradient(
                if (fraction == null || fraction <= 0.0) color(ACCENT.toColorInt())
                else step(lineProgress(), color(PASSED_COLOR.toColorInt()), stop(fraction.coerceAtMost(1.0), color(ACCENT.toColorInt())))))
            renderedProgress = progress
        }
        val target = state.destination
        loaded.getSourceAs<GeoJsonSource>(TARGET)?.setGeoJson(FeatureCollection.fromFeatures(
            if (target == null) emptyArray() else arrayOf(Feature.fromGeometry(Point.fromLngLat(target.longitude, target.latitude)))))
        loaded.getLayer(LINE)?.setProperties(lineOpacity(if (state.phase == NavigationPhase.ERROR) 0.4f else 1f))
    }

    /** Option index of an alternative line or time label under the touch, if any. */
    fun optionAt(map: MapLibreMap, screen: PointF, slopPx: Float): Int? =
        map.queryRenderedFeatures(RectF(screen.x - slopPx, screen.y - slopPx, screen.x + slopPx, screen.y + slopPx), LABELS_LAYER, ALTERNATIVES_LINE)
            .firstNotNullOfOrNull { it.getNumberProperty("option")?.toInt() }

    fun cleanup() { style = null; renderedRoute = null; renderedOptions = null; renderedProgress = null }

    private fun line(route: Route) = LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })

    /**
     * Maps metres along the route to MapLibre's line-progress, which is measured in Web Mercator.
     * Using the same geometry as the matcher keeps the split exactly at the confirmed position.
     */
    private class ProgressScale(route: Route) {
        private val meters = DoubleArray(route.points.size)
        private val mercator = DoubleArray(route.points.size)
        init {
            for (i in 1 until route.points.size) {
                val a = route.points[i - 1]; val b = route.points[i]
                meters[i] = meters[i - 1] + distanceMeters(a, b)
                mercator[i] = mercator[i - 1] + hypot(Math.toRadians(longitudeDelta(b.longitude - a.longitude)), y(b.latitude) - y(a.latitude))
            }
        }

        fun fraction(along: Double): Double? {
            if (mercator.last() <= 0) return null
            val target = along.coerceIn(0.0, meters.last())
            val index = meters.binarySearch(target).let { if (it >= 0) it else -it - 2 }.coerceIn(0, meters.size - 2)
            val length = meters[index + 1] - meters[index]
            val t = if (length > 0) (target - meters[index]) / length else 0.0
            return (mercator[index] + t * (mercator[index + 1] - mercator[index])) / mercator.last()
        }

        private fun y(latitude: Double) = ln(tan(PI / 4 + Math.toRadians(latitude.coerceIn(-85.0, 85.0)) / 2))
    }

    companion object {
        const val ACCENT = "#1A73E8"
        private const val PASSED_COLOR = "#9AA0A6"
        private const val ALTERNATIVE_COLOR = "#8AB4F8"
        private const val TEXT = "#202124"
        private const val ROUTE = "navigation-route"
        private const val ALTERNATIVES = "navigation-alternatives"
        private const val LABELS = "navigation-labels"
        private const val TARGET = "navigation-target"
        private const val ALTERNATIVES_LINE = "navigation-alternatives-line"
        private const val CASING = "navigation-casing"
        private const val LINE = "navigation-line"
        private const val DESTINATION = "navigation-destination"
        private const val LABELS_LAYER = "navigation-labels-layer"
    }
}
