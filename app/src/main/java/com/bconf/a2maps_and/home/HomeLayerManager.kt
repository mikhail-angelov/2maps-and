package com.bconf.a2maps_and.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.maps.MapsLayerManager
import com.bconf.a2maps_and.navigation.GeoPoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Draws the Home point as an orange house marker. */
class HomeLayerManager(private val context: Context) {
    private var style: Style? = null
    private var home: GeoPoint? = null

    fun onStyleLoaded(style: Style) {
        this.style = style
        if (style.getSource(SOURCE) == null) style.addSource(GeoJsonSource(SOURCE))
        if (style.getImage(IMAGE) == null) style.addImage(IMAGE, markerBitmap())
        if (style.getLayer(LAYER) == null) {
            val layer = SymbolLayer(LAYER, SOURCE).withProperties(iconImage(IMAGE), iconAllowOverlap(true), iconIgnorePlacement(true))
            // The position marker stays on top when the car is at home.
            if (style.getLayer(MapsLayerManager.CURRENT_LOCATION_LAYER_ID) != null) style.addLayerBelow(layer, MapsLayerManager.CURRENT_LOCATION_LAYER_ID)
            else style.addLayer(layer)
        }
        show(home)
    }

    fun show(home: GeoPoint?) {
        this.home = home
        val loaded = style?.takeIf { it.isFullyLoaded } ?: return
        loaded.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(
            listOfNotNull(home?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) })))
    }

    fun cleanup() { style = null }

    private fun markerBitmap(): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (32 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = size / 2f
        canvas.drawCircle(r, r, r - density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.home_marker)
            setShadowLayer(density, 0f, density / 2, 0x55000000)
        })
        canvas.drawCircle(r, r, r - 2 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2 * density; color = ContextCompat.getColor(context, R.color.nav_on_accent)
        })
        val icon = (18 * density).toInt()
        val inset = (size - icon) / 2
        ContextCompat.getDrawable(context, R.drawable.ic_home)!!.mutate().apply {
            setTint(ContextCompat.getColor(context, R.color.nav_on_accent))
            setBounds(inset, inset, inset + icon, inset + icon)
            draw(canvas)
        }
        return bitmap
    }

    private companion object {
        const val SOURCE = "home-source"
        const val LAYER = "home-layer"
        const val IMAGE = "home-marker"
    }
}
