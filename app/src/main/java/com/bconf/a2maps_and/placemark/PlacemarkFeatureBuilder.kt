package com.bconf.a2maps_and.placemark

import android.util.Log
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

internal fun buildFeatureCollection(
    items: List<PlacemarkDisplayItem>,
    tag: String
): FeatureCollection {
    val features = items.mapNotNull { p ->
        val placemark = p.placemark
        try {
            if (placemark.longitude.isNaN() || placemark.latitude.isNaN()) {
                Log.e(tag, "Invalid coordinates for ${placemark.name}")
                return@mapNotNull null
            }
            Feature.fromGeometry(Point.fromLngLat(placemark.longitude, placemark.latitude)).also {
                it.addStringProperty("id", placemark.id)
                it.addStringProperty("name", placemark.name ?: "Unnamed")
                it.addStringProperty("description", placemark.description ?: "")
                it.addNumberProperty("rate", placemark.rate ?: 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error creating feature for ${placemark.name}", e)
            null
        }
    }
    return FeatureCollection.fromFeatures(features)
}
