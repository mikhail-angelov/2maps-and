package com.bconf.a2maps_and.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import java.io.IOException

class MapsLayerManager(private val context: Context, private val map: MapLibreMap) {

    private var currentLocationSource: GeoJsonSource? = null
    private var routeSource: GeoJsonSource? = null

    companion object {
        private const val CURRENT_LOCATION_SOURCE_ID = "current-location-source"
        private const val CURRENT_LOCATION_LAYER_ID = "current-location-layer"
        private const val ROUTE_SOURCE_ID = "route-source"
        private const val ROUTE_LAYER_ID = "route-layer"
    }

    fun loadInitialMapStyle(onStyleLoaded: ((style: Style) -> Unit)) {

        val sharedPreferences = context.getSharedPreferences("maps_prefs", Context.MODE_PRIVATE)
        val selectedMapPath = sharedPreferences.getString("selected_map", null)

        val file = if (selectedMapPath != null) {
            File(selectedMapPath)
        } else {
            getFileFromAssets(context, "planet.mbtiles")
        }

        if (!file.exists()) {
            val defaultFile = getFileFromAssets(context, "planet.mbtiles")
            loadMapStyleFromFile(defaultFile, onStyleLoaded)
            return
        }

        loadMapStyleFromFile(file, onStyleLoaded)
    }

    @Throws(IOException::class)
    fun getFileFromAssets(context: Context?, fileName: String): File =
        File(context?.cacheDir, fileName)
            .also {
                if (!it.exists()) {
                    it.outputStream().use { cache ->
                        context?.assets?.open(fileName).use { inputStream ->
                            inputStream?.copyTo(cache)
                        }
                    }
                }
            }


    private fun loadMapStyleFromFile(file: File, onStyleLoaded: ((style: Style) -> Unit)?) {

        val format = try {
            val db =
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor =
                db.query(
                    "metadata",
                    arrayOf("value"),
                    "name = ?",
                    arrayOf("format"),
                    null,
                    null,
                    null
                )
            var formatValue: String? = null
            if (cursor.moveToFirst()) {
                formatValue = cursor.getString(0)
            }
            cursor.close()
            db.close()
            formatValue
        } catch (e: Exception) {
            null
        }

        val styleJson = when (format) {
            "pbf", "mvt" -> {
                context.assets?.open("bright.json")?.bufferedReader()?.use { it.readText() }
                    ?.replace(
                        "\"url\": \"asset://planet.mbtiles\"",
                        "\"url\": \"mbtiles://${file.absolutePath}\""
                    )
            }

            "png", "jpg" -> {
                """
            {
              "version": 8,
              "name": "Raster MBTiles",
              "sources": {
                "raster-tiles": {
                  "type": "raster",
                  "url": "mbtiles://${file.absolutePath}",
                  "tileSize": 256
                }
              },
              "layers": [
                {
                  "id": "simple-tiles",
                  "type": "raster",
                  "source": "raster-tiles",
                  "minzoom": 0,
                  "maxzoom": 22
                }
              ]
            }
            """.trimIndent()
            }

            else -> {
                context.assets?.open("bright.json")?.bufferedReader()?.use { it.readText() }
                    ?.replace(
                        "\"url\": \"asset://planet.mbtiles\"",
                        "\"url\": \"mbtiles://${file.absolutePath}\""
                    )
            }
        }


        if (styleJson == null) {
            return
        }

        map.setStyle(
            Style.Builder().fromJson(styleJson)
        ) { style ->
            onStyleLoaded?.invoke(style)

            setupLocationDisplay(style)
            setupRouteLayer(style)
        }
    }

    private fun setupLocationDisplay(style: Style) {
        // Create a source for the current location
        currentLocationSource = GeoJsonSource(CURRENT_LOCATION_SOURCE_ID)
        style.addSource(currentLocationSource!!)

        // Create a layer to display the current location
        val locationLayer = CircleLayer(CURRENT_LOCATION_LAYER_ID, CURRENT_LOCATION_SOURCE_ID)
        locationLayer.setProperties(
            PropertyFactory.circleColor(Color.BLUE),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
        style.addLayer(locationLayer)
    }

    private fun setupRouteLayer(style: Style) {
        routeSource = GeoJsonSource(ROUTE_SOURCE_ID)
        style.addSource(routeSource!!)
        val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID)
        routeLayer.setProperties(
            PropertyFactory.lineColor(Color.parseColor("#FF3887BE")),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
        )
        // Ensure route layer is under the location indicator
        style.addLayerBelow(routeLayer, CURRENT_LOCATION_LAYER_ID)

    }


    fun updateCurrentLocation(point: Point) {
        currentLocationSource?.setGeoJson(point)
    }

    fun drawRoute(routeSegment: List<LatLng>?) {
        if (routeSegment == null || routeSegment.isEmpty()) {
            clearRoute()
            return
        }


        map.getStyle { style -> // Ensure working with current style
            val lineString = LineString.fromLngLats(routeSegment.map {
                Point.fromLngLat(it.longitude, it.latitude)
            })
            if (routeSource == null) {
                routeSource = GeoJsonSource(ROUTE_SOURCE_ID, lineString)
                style.addSource(routeSource!!)
                val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.lineColor(Color.parseColor("#FF3887BE")),
                        PropertyFactory.lineWidth(5f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                    )
                }
                style.addLayerBelow(routeLayer, CURRENT_LOCATION_LAYER_ID)
            } else {
                routeSource?.setGeoJson(lineString)
            }
        }
    }

    fun clearRoute() {
        // To clear the route, we can set an empty LineString
        routeSource?.setGeoJson(LineString.fromLngLats(emptyList()))
    }
}