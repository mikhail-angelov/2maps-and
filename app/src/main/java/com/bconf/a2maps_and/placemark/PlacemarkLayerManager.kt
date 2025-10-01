package com.bconf.a2maps_and.placemark

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.maplibre.geojson.Point

class PlacemarkLayerManager(
    private val context: Context,
    private val map: MapLibreMap,
    private val placemarksViewModel: PlacemarksViewModel, // Pass in the ViewModel
    private val lifecycle: Lifecycle
) {

    companion object {
        private const val SOURCE_ID = "placemarks-source"
        private const val LAYER_ID = "placemarks-layer"
        private const val ICON_ID = "placemark-icon"
        private const val CIRCLE_LAYER_ID = "placemarks-circle-layer"
        private const val TEXT_LAYER_ID = "placemarks-text-layer"

        const val PROPERTY_NAME = "name"
        const val PROPERTY_DESCRIPTION = "description"
        const val PROPERTY_RATE = "rate"
    }


    fun onStyleLoaded(style: Style) {
//        addPlacemarkIcon(style)
        setupSource(style)
        setupCircleLayer(style)
        setupTextLayer(style)
//        setupLayer(style)

        observePlacemarks()

    }

    private fun observePlacemarks() {
        // Launch a coroutine that is automatically cancelled when the passed lifecycle is destroyed.
        // We use lifecycle.repeatOnLifecycle to ensure collection only happens when the
        // lifecycle is at least in the STARTED state.
        CoroutineScope(Dispatchers.Main).launch { // Or use a scope provided if this class has its own lifecycle
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                placemarksViewModel.displayItems.collectLatest { placemarks ->
                    Log.d("PlacemarkManager", "Observed ${placemarks.size} placemarks from ViewModel.")
                    updatePlacemarks(placemarks)
                }
            }
        }

    }
    fun showAddPlacemarkDialog(coordinates: LatLng) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("Add New Placemark")

        // Set up the input
        val inputLayout = LinearLayout(context)
        inputLayout.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(50, 20, 50, 20) // Add some margins for the EditText

        val inputName = EditText(context)
        inputName.inputType = InputType.TYPE_CLASS_TEXT
        inputName.hint = "Enter placemark name"
        inputName.layoutParams = lp
        inputLayout.addView(inputName)

        // Optionally, add more fields like description
        // val inputDescription = EditText(context)
        // inputDescription.inputType = InputType.TYPE_CLASS_TEXT
        // inputDescription.hint = "Description (optional)"
        // inputDescription.layoutParams = lp
        // inputLayout.addView(inputDescription)

        builder.setView(inputLayout)

        // Set up the buttons
        builder.setPositiveButton("Save") { dialog, _ ->
            val placemarkName = inputName.text.toString().trim()
            // val placemarkDescription = inputDescription.text.toString().trim() // if added

            if (placemarkName.isNotEmpty()) {
                val newPlacemark = Placemark(
                    name = placemarkName,
                    latitude = coordinates.latitude,
                    longitude = coordinates.longitude,
                     description = "",
                     rate = 0,
                )
                placemarksViewModel.addPlacemark(newPlacemark)
                Toast.makeText(context, "Placemark '$placemarkName' added", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(context, "Placemark name cannot be empty", Toast.LENGTH_SHORT).show()
                // Don't dismiss, let user correct
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ ->
            dialog.cancel()
            Toast.makeText(context, "Add placemark cancelled", Toast.LENGTH_SHORT).show()
        }

        builder.show()
    }

    fun handleMapClick(point: LatLng): Boolean {
        val screenPoint = map.projection.toScreenLocation(point)
        // Query features on the specific placemark layer
        val features = map.queryRenderedFeatures(screenPoint, LAYER_ID)

        if (features.isNotEmpty()) {
            val clickedFeature = features[0] // Get the top-most feature if overlapping

            // Retrieve properties from the feature
            val placemarkName = clickedFeature.getStringProperty(PROPERTY_NAME) ?: "N/A"
            val placemarkDescription = clickedFeature.getStringProperty(PROPERTY_DESCRIPTION) ?: "No description."
            val placemarkRate = clickedFeature.getNumberProperty(PROPERTY_RATE)?.toInt() ?: 0
            // val placemarkId = clickedFeature.getStringProperty(PROPERTY_PLACEMARK_ID) // If you use IDs

            // Construct the message for the dialog
            val dialogMessage = StringBuilder()
            dialogMessage.append("Name: $placemarkName\n")
            dialogMessage.append("Description: $placemarkDescription\n")
            dialogMessage.append("Rating: $placemarkRate star(s)")
            // Add more details if available

            // Show an AlertDialog with the placemark's information
            AlertDialog.Builder(context)
                .setTitle("Placemark Details")
                .setMessage(dialogMessage.toString())
                .setPositiveButton("OK") { dialog, _ ->
                    dialog.dismiss()
                }
                // Optional: Add more buttons for actions like "Edit" or "Delete"
                // .setNegativeButton("Delete") { _, _ ->
                //     // Handle delete action, potentially using placemarkId
                //     Toast.makeText(context, "Delete: $placemarkName", Toast.LENGTH_SHORT).show()
                // }
                // .setNeutralButton("Edit") { _, _ ->
                //    // Handle edit action
                //    Toast.makeText(context, "Edit: $placemarkName", Toast.LENGTH_SHORT).show()
                // }
                .create() // Create the dialog
                .show()   // Show the dialog

            return true // Signify that the click was handled
        }
        return false // No placemark feature was clicked
    }
    private fun addPlacemarkIcon(style: Style) {
        // Use a standard Android drawable as the placemark icon
        getBitmapFromVectorDrawable(context, android.R.drawable.ic_dialog_map)?.let {
            style.addImage(ICON_ID, it)
        }
    }

    private fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth,
            drawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun setupSource(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID))
        }
    }


    private fun setupCircleLayer(style: Style) {
        if (style.getLayer(CIRCLE_LAYER_ID) == null) {
            Log.d("PlacemarkManager", "Setting up CircleLayer: $CIRCLE_LAYER_ID") // <--- ADD THIS
            val circleLayer = CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID)
                .withProperties(
                    PropertyFactory.circleColor(Color.GREEN), // Set circle color to green
                    PropertyFactory.circleRadius(8f),       // Set circle radius (in pixels)
                    PropertyFactory.circleStrokeColor(Color.BLACK), // Optional: circle stroke color
                    PropertyFactory.circleStrokeWidth(1.5f)    // Optional: circle stroke width
                    // Add other circle properties as needed:
                    // PropertyFactory.circleOpacity(0.8f),
                    // PropertyFactory.circleBlur(0.5f)
                )
            style.addLayer(circleLayer)
        } else {
            Log.d("PlacemarkManager", "CircleLayer $CIRCLE_LAYER_ID already exists.") // <--- ADD THIS
        }
    }
    private fun setupTextLayer(style: Style) {
        if (style.getLayer(TEXT_LAYER_ID) == null) {
            val textLayer = SymbolLayer(TEXT_LAYER_ID, SOURCE_ID)
                .withProperties(
                    PropertyFactory.textField("{$PROPERTY_NAME}"),
                    PropertyFactory.textFont(arrayOf("Arial Unicode Regular")), // [1] Standard font stack
                    PropertyFactory.textColor(Color.BLACK),
                    PropertyFactory.textHaloColor(Color.WHITE),
                    PropertyFactory.textHaloWidth(1.0f),
                    PropertyFactory.textSize(14f),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM), // Anchor text above the circle's center
                    PropertyFactory.textOffset(arrayOf(0f, 2f)), // Offset text slightly above the circle
                    PropertyFactory.textAllowOverlap(true), // Avoid text disappearing if circles are close
                    PropertyFactory.textIgnorePlacement(true)
                )
            style.addLayer(textLayer)
        }
    }

    private fun setupLayer(style: Style) {
        if (style.getLayer(LAYER_ID) == null) {
            val layer = SymbolLayer(LAYER_ID, SOURCE_ID)
                .withProperties(
                    PropertyFactory.iconImage(ICON_ID),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(1.2f),
                    PropertyFactory.textField("{name}"),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1.0f)),
                    PropertyFactory.textColor("#000000"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(2f)
                )
            style.addLayer(layer)
        }
    }

    fun updatePlacemarks(placemarks: List<PlacemarkDisplayItem>) {
        Log.d("PlacemarkManager", "Fetched ${placemarks.size} placemarks from service.")
        if (placemarks.isEmpty()) {
            Log.w("PlacemarkManager", "No placemarks to display.")
            // Ensure the source is cleared if there are no placemarks
            map.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
                source?.setGeoJson(FeatureCollection.fromFeatures(emptyList())) // Clear the source
            }
            return // Exit early
        }

        val features: List<Feature> = placemarks.mapNotNull { p -> // Use mapNotNull
            val placemark = p.placemark
            try {
                // Ensure longitude and latitude are valid numbers
                if (placemark.longitude.isNaN() || placemark.latitude.isNaN()) {
                    Log.e("PlacemarkManager", "Invalid coordinates for placemark: ${placemark.name} - Lat: ${placemark.latitude}, Lng: ${placemark.longitude}")
                    return@mapNotNull null
                }
                val point = Point.fromLngLat(placemark.longitude, placemark.latitude)
                val feature = Feature.fromGeometry(point)
                feature.addStringProperty(PROPERTY_NAME, placemark.name ?: "Unnamed") // Handle null name
                feature.addStringProperty(PROPERTY_DESCRIPTION, placemark.description ?: "")
                feature.addNumberProperty(PROPERTY_RATE, placemark.rate ?: 0)
                feature // Return the valid feature
            } catch (e: Exception) {
                Log.e("PlacemarkManager", "Error creating feature for placemark: ${placemark.name}", e)
                null // Skip this placemark if there's an error
            }
        }

        Log.d("PlacemarkManager", "Number of features created: ${features.size}")
        if (features.isEmpty() && placemarks.isNotEmpty()) {
            Log.e("PlacemarkManager", "All placemarks resulted in invalid features. Check for coordinate or property errors.")
            // Clear the source if all features failed
            map.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
                source?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            }
            return
        }
        // Log individual features if the list is small
        features.forEachIndexed { index, feature ->
            Log.d("PlacemarkManager", "Feature $index: ${feature.toJson()}")
        }

        val featureCollection: FeatureCollection = FeatureCollection.fromFeatures(features)
        Log.d("PlacemarkManager", "FeatureCollection JSON: ${featureCollection.toJson()}") // Already have this, good!

        map.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID)
            if (source == null) {
                Log.e("PlacemarkManager", "GeoJsonSource with ID '$SOURCE_ID' not found!")
                return@getStyle
            }
            source.setGeoJson(featureCollection) // This is the line in question
            Log.d("PlacemarkManager", "GeoJsonSource updated with FeatureCollection.")
        }
    }

    fun cleanup() {
        map.getStyle { style ->
            if (style.isFullyLoaded) {
                style.removeLayer(CIRCLE_LAYER_ID)
                style.removeLayer(TEXT_LAYER_ID)
            }
        }
    }
}