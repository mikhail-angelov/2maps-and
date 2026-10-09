package com.bconf.a2maps_and.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.io.File
import java.io.IOException

class MapsLayerManager(private val context: Context, private val map: MapLibreMap) {

    private var currentLocationSource: GeoJsonSource? = null
    private var currentLocation: Feature? = null

    companion object {
        private const val CURRENT_LOCATION_SOURCE_ID = "current-location-source"
        const val CURRENT_LOCATION_LAYER_ID = "current-location-layer"
        private const val CURRENT_LOCATION_ARROW_LAYER_ID = "current-location-arrow"
        private const val ARROW_IMAGE = "current-location-arrow-image"
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


    fun loadMapStyleFromFile(file: File, onStyleLoaded: ((style: Style) -> Unit)?) {

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
            "png", "jpg" -> context.assets?.open("raster_style.json")?.bufferedReader()
                ?.use { it.readText() }
                ?.replace("___FILE_URI___", "mbtiles://${file.absolutePath}")

            else -> context.assets?.open("bright.json")?.bufferedReader()?.use { it.readText() }
                ?.replace(
                    "\"url\": \"asset://planet.mbtiles\"",
                    "\"url\": \"mbtiles://${file.absolutePath}\""
                )
        }


        if (styleJson == null) {
            return
        }

        map.setStyle(
            Style.Builder().fromJson(styleJson)
        ) { style ->
            setupLocationDisplay(style)
            onStyleLoaded?.invoke(style)
        }
    }

    private fun setupLocationDisplay(style: Style) {
        // Create a source for the current location
        currentLocationSource = GeoJsonSource(CURRENT_LOCATION_SOURCE_ID)
        style.addSource(currentLocationSource!!)
        currentLocation?.let { currentLocationSource?.setGeoJson(it) }

        // Create a layer to display the current location
        val locationLayer = CircleLayer(CURRENT_LOCATION_LAYER_ID, CURRENT_LOCATION_SOURCE_ID)
        locationLayer.setProperties(
            PropertyFactory.circleColor(Color.BLUE),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
        // A neutral dot without a reliable course; an arrow rotated with the map when the course is known.
        locationLayer.setFilter(Expression.not(Expression.has("bearing")))
        style.addLayer(locationLayer)
        style.addImage(ARROW_IMAGE, arrowBitmap())
        style.addLayer(SymbolLayer(CURRENT_LOCATION_ARROW_LAYER_ID, CURRENT_LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(ARROW_IMAGE),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).withFilter(Expression.has("bearing")))
    }

    fun updateCurrentLocation(point: Point, bearing: Double? = null) {
        val feature = Feature.fromGeometry(point).apply { bearing?.let { addNumberProperty("bearing", it) } }
        currentLocation = feature
        if (map.style?.isFullyLoaded == true) currentLocationSource?.setGeoJson(feature)
    }

    private fun arrowBitmap(): Bitmap {
        val size = (36 * context.resources.displayMetrics.density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = size.toFloat()
        val arrow = Path().apply {
            moveTo(s * 0.5f, s * 0.08f); lineTo(s * 0.86f, s * 0.9f); lineTo(s * 0.5f, s * 0.72f); lineTo(s * 0.14f, s * 0.9f); close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.1f; strokeJoin = Paint.Join.ROUND })
        canvas.drawPath(arrow, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 71, 161) })
        return bitmap
    }
}
