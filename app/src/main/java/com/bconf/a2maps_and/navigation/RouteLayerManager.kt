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
    private var renderedSigns: Route? = null
    private var progressScale: ProgressScale? = null

    fun onStyleLoaded(style: Style) {
        this.style = style
        renderedRoute = null; renderedOptions = null; renderedProgress = null; renderedSigns = null
        if (style.getSource(SIGNS) == null) style.addSource(GeoJsonSource(SIGNS))
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
        // Speed-limit signs where the limit changes along the driven route, drawn as map signs like the reference.
        add(style, CircleLayer(SIGN_CIRCLE, SIGNS).withProperties(
            circleColor("#FFFFFF"), circleRadius(13f), circleStrokeColor(SPEED_SIGN_BORDER), circleStrokeWidth(3.5f)).apply { minZoom = SIGNS_MIN_ZOOM })
        add(style, SymbolLayer(SIGN_TEXT, SIGNS).withProperties(
            textField(get("limit")), textFont(arrayOf("Noto Sans Bold")), textSize(12f), textColor(TEXT),
            textAllowOverlap(true), textIgnorePlacement(true)).apply { minZoom = SIGNS_MIN_ZOOM })
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
        val signed = state.route.takeIf { state.phase == NavigationPhase.ACTIVE }
        if (signed !== renderedSigns || force) {
            loaded.getSourceAs<GeoJsonSource>(SIGNS)?.setGeoJson(FeatureCollection.fromFeatures(signed?.let(::speedSigns).orEmpty()))
            renderedSigns = signed
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

    fun cleanup() { style = null; renderedRoute = null; renderedOptions = null; renderedProgress = null; renderedSigns = null }

    /** One sign at the start of every section whose known limit differs from the previous section's. */
    private fun speedSigns(route: Route): List<Feature> {
        var previous: Int? = null
        return route.roadSections.mapNotNull { section ->
            val limit = section.maxSpeedKmh
            val changed = limit != null && limit != previous
            previous = limit
            if (!changed) return@mapNotNull null
            val point = route.points.getOrNull(section.fromPointIndex) ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply { addStringProperty("limit", limit.toString()) }
        }
    }

    private fun line(route: Route) = LineString.fromLngLats(route.points.map { Point.fromLngLat(it.longitude, it.latitude) })

    /**
     * Maps metres along the route to MapLibre's line-progress, which is measured in Web Mercator.
     * Using the same geometry as the matcher keeps the split exactly at the confirmed position.
     */
    private class ProgressScale(route: Route) {
        private val meters = cumulativeMeters(route.points)
        private val mercator = DoubleArray(route.points.size)
        init {
            for (i in 1 until route.points.size) {
                val a = route.points[i - 1]; val b = route.points[i]
                mercator[i] = mercator[i - 1] + hypot(Math.toRadians(longitudeDelta(b.longitude - a.longitude)), y(b.latitude) - y(a.latitude))
            }
        }

        fun fraction(along: Double): Double? {
            if (mercator.last() <= 0) return null
            val (index, t) = segmentAt(meters, along)
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
        private const val SIGNS = "navigation-speed-signs"
        private const val SIGN_CIRCLE = "navigation-speed-sign-circle"
        private const val SIGN_TEXT = "navigation-speed-sign-text"
        private const val SPEED_SIGN_BORDER = "#D93025"
        private const val SIGNS_MIN_ZOOM = 13f
    }
}
