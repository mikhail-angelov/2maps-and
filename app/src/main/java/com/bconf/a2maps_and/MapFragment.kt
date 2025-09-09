package com.bconf.a2maps_and

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bconf.a2maps_and.databinding.FragmentMapBinding
import com.bconf.a2maps_and.routing.RetrofitClient
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteRequest
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.google.android.material.textview.MaterialTextView
import com.mapbox.geojson.utils.PolylineUtils
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.IOException

class MapFragment : Fragment(), MapLibreMap.OnMapLongClickListener, OnMapReadyCallback,
    NavigationBottomSheetFragment.NavigationBottomSheetListener {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mapView: MapView
    lateinit var map: MapLibreMap // Made public to be accessible from MainActivity if needed initially

    private var longPressedLatLng: LatLng? = null
    private val ID_MENU_FROM = 1
    private val ID_MENU_TO = 2
    private var fromPointString: String? = null
    private var toPointString: String? = null
    private var navigationBottomSheet: NavigationBottomSheetFragment? = null

    private var lastKnownLocation: android.location.Location? = null
    private var isUserPanning = false
    private var currentLocationSource: GeoJsonSource? = null
    private val CURRENT_LOCATION_SOURCE_ID = "current-location-source"
    private val CURRENT_LOCATION_LAYER_ID = "current-location-layer"
    private lateinit var locationReceiver: LocationBroadcastReceiver

    private val ROUTE_SOURCE_ID = "route-source"
    private val ROUTE_LAYER_ID = "route-layer"
    private var routeSource: GeoJsonSource? = null

    private var maneuverTextView: MaterialTextView? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        MapLibre.getInstance(requireContext())
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        mapView = binding.mapViewInFragment
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        locationReceiver = LocationBroadcastReceiver()

        return binding.root
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.map = mapLibreMap
        // Basic map setup, can be expanded or configured via listener
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(56.292374, 43.985402)) // Default position
            .zoom(10.0) // Default zoom
            .tilt(0.0)
            .build()
        map.addOnCameraIdleListener {
            isUserPanning = false
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                isUserPanning = true
            }
        }
        map.addOnMapLongClickListener(this)
        mapView.let { registerForContextMenu(it) }

        loadInitialMapStyle()
        setupLocationDisplay()
        setupManeuverTextView()
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

     fun loadInitialMapStyle(mbtilesFile: File? = null, onStyleLoaded: (() -> Unit)? = null) {
         if (!::map.isInitialized) return

        val file = getFileFromAssets(context, "niz-osm.mbtiles")
        Log.i("--files", file.absolutePath)

        val styleJson = context?.assets?.open("bright.json")?.bufferedReader().use { it?.readText() }
        if (styleJson == null) {
            Log.e("MapFragment", "Failed to read bright.json")
            return
        }

        val updatedStyleJson = styleJson.replace(
     "\"url\": \"asset://niz-osm.mbtiles\"",
     "\"url\": \"mbtiles://${file.absolutePath}\"")

        map.setStyle(
            Style.Builder().fromJson(updatedStyleJson)
        ) { style ->
            Log.i("MapFragment", "Style loaded.")
            onStyleLoaded?.invoke()
        }
     }


    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            locationReceiver,
            IntentFilter(LocationService.ACTION_LOCATION_UPDATE))
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(locationReceiver)
    }

    override fun onStop() {
        super.onStop()
        if (::mapView.isInitialized) mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (::mapView.isInitialized) mapView.onLowMemory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mapView.let { unregisterForContextMenu(it) }
        if (::mapView.isInitialized) mapView.onDestroy()
        if (::map.isInitialized) {
            map.removeOnMapLongClickListener(this)
        }
    }

    override fun onMapLongClick(point: LatLng): Boolean {
        if (!::map.isInitialized) return false
        longPressedLatLng = point
        mapView.showContextMenu() // Use mapView from fragment
        return true
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View, // This v is the MapView from the fragment
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        // Check the ID of the MapView inside the fragment's layout
        if (v.id == R.id.mapViewInFragment && longPressedLatLng != null) {
            menu.setHeaderTitle("Point Options")
            menu.add(0, ID_MENU_FROM, 0, "From")
            menu.add(0, ID_MENU_TO, 1, "To")
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        longPressedLatLng?.let { coords ->
            val coordinateText = "Lat: %.4f, Lng: %.4f".format(coords.latitude, coords.longitude)
            when (item.itemId) {
                ID_MENU_FROM -> {
                    fromPointString = coordinateText
                    Toast.makeText(requireContext(), "'From' set: $coordinateText", Toast.LENGTH_SHORT).show()
                    Log.d("MapContextMenu", "'From' point (manual): $coordinateText")

                    if (navigationBottomSheet?.isAdded == true && navigationBottomSheet?.dialog?.isShowing == true) {
                        navigationBottomSheet?.setFromText(fromPointString ?: "")
                    } else {
                        showNavigationBottomSheet()
                    }
                    return true
                }
                ID_MENU_TO -> {
                    toPointString = coordinateText
                    Toast.makeText(requireContext(), "'To' set: $coordinateText", Toast.LENGTH_SHORT).show()
                    Log.d("MapContextMenu", "'To' point: $coordinateText")

                    if (navigationBottomSheet?.isAdded == true && navigationBottomSheet?.dialog?.isShowing == true) {
                        navigationBottomSheet?.setToText(toPointString ?: "")
                    } else {
                        showNavigationBottomSheet()
                    }
                    return true
                }
            }
        }
        return super.onContextItemSelected(item)
    }

    override fun onNavigateClicked(fromInput: String, toInput: String) {
        if (!::map.isInitialized) {
            Toast.makeText(requireContext(), "Map not ready for navigation.", Toast.LENGTH_SHORT).show()
            return
        }
        navigationBottomSheet?.dismiss()

        var finalFromLatLng: LatLng? = null
        val toLatLng = parseCoordinates(toInput)

        if (fromInput.equals("Current Location", ignoreCase = true) && lastKnownLocation != null) {
            finalFromLatLng = LatLng(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude)
            Log.d("Navigate", "Using current location as 'From': $finalFromLatLng")
        } else {
            finalFromLatLng = parseCoordinates(fromInput)
            if (finalFromLatLng == null) {
                if (lastKnownLocation != null) {
                    finalFromLatLng = LatLng(lastKnownLocation!!.latitude, lastKnownLocation!!.longitude)
                    Log.d("Navigate", "Parsed 'From' failed, using current location: $finalFromLatLng")
                } else {
                    Toast.makeText(requireContext(), "Invalid 'From' point and current location unavailable.", Toast.LENGTH_LONG).show()
                    return
                }
            } else {
                Log.d("Navigate", "Using manually set/parsed 'From': $finalFromLatLng")
            }
        }

        if (toLatLng == null) {
            Toast.makeText(requireContext(), "Invalid 'To' point.", Toast.LENGTH_LONG).show()
            return
        }

        if (finalFromLatLng == null) {
            Toast.makeText(requireContext(), "'From' point could not be determined.", Toast.LENGTH_LONG).show()
            return
        }

        val valhallaRequest = ValhallaRouteRequest(
            locations = listOf(
                ValhallaLocation(lat = finalFromLatLng.latitude, lon = finalFromLatLng.longitude),
                ValhallaLocation(lat = toLatLng.latitude, lon = toLatLng.longitude)
            ),
            costing = "auto"
        )

        Log.d("ValhallaRequest", "Requesting route: $valhallaRequest")
        Toast.makeText(requireContext(), "Requesting route...", Toast.LENGTH_SHORT).show()
        val valhallaServiceUrl = "http://185.231.246.34:8002/route"

        lifecycleScope.launch {
            try {
                val response = RetrofitClient.instance.getRoute(url = valhallaServiceUrl, request = valhallaRequest)
                if (response.isSuccessful) {
                    val routeResponse = response.body()
                    if (routeResponse?.trip?.legs?.isNotEmpty() == true) {
                        Log.d("ValhallaResponse", "Route received: ${routeResponse.trip}")
                        displayRouteOnMap(routeResponse, finalFromLatLng, toLatLng)
                        displayNextManeuver(routeResponse.trip.legs.firstOrNull()?.maneuvers?.firstOrNull())
                    } else {
                        Log.w("ValhallaResponse", "No route found. Message: ${routeResponse?.trip?.status_message}")
                        Toast.makeText(requireContext(), "No route found: ${routeResponse?.trip?.status_message}", Toast.LENGTH_LONG).show()
                        clearRouteAndManeuver()
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e("ValhallaError", "Error: ${response.code()} - $errorBody")
                    Toast.makeText(requireContext(), "Error fetching route: ${response.code()}", Toast.LENGTH_LONG).show()
                    clearRouteAndManeuver()
                }
            } catch (e: Exception) {
                Log.e("ValhallaException", "Exception: ${e.message}", e)
                Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                clearRouteAndManeuver()
            }
        }
    }
    override fun onBottomSheetClosed() {
//        fromPointString = null
//        toPointString = null
        Log.d("MainActivity", "Bottom sheet closed, points reset.")
        clearRouteAndManeuver()
    }

    fun parseCoordinates(coordinateString: String): LatLng? {
        val parts = coordinateString.split(", Lng: ")
        if (parts.size == 2) {
            val latString = parts[0].substringAfter("Lat: ").trim()
            val lngString = parts[1].trim()

            return try {
                val latitude = latString.replace(',', '.').toDouble()
                val longitude = lngString.replace(',', '.').toDouble()
                return LatLng(latitude, longitude)
            } catch (e: NumberFormatException) {
                Log.e("ParseCoords", "Error parsing coordinate string: $coordinateString", e)
                null
            }
        } else {
            Log.e("ParseCoords", "Invalid coordinate string format: $coordinateString")
            return null
        }
    }

    private fun setupManeuverTextView() {
        maneuverTextView = MaterialTextView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                addRule(RelativeLayout.ALIGN_PARENT_START)
                setMargins(32, 32, 32, 32)
            }
            setBackgroundColor(Color.parseColor("#80FFFFFF"))
            setTextColor(Color.BLACK)
            setPadding(16, 8, 16, 8)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            visibility = View.GONE
        }

//        val rootView = findViewById<View>(android.R.id.content).rootView
//        if (rootView is ViewGroup) {
//            try {
//                (rootView as ViewGroup).addView(maneuverTextView)
//            } catch (e: Exception) {
//                Log.e("ManeuverDisplay", "Could not add maneuverTextView to root view", e)
//            }
//        }
    }




    private fun displayRouteOnMap(
        routeResponse: ValhallaRouteResponse,
        fromPoint: LatLng,
        toPoint: LatLng
    ) {
        if (!::map.isInitialized) return

        val shape = routeResponse.trip?.legs?.firstOrNull()?.shape
        if (shape.isNullOrEmpty()) {
            Log.w("DisplayRoute", "No shape data in response.")
            return
        }

        val decodedPointsMapbox: List<com.mapbox.geojson.Point> = PolylineUtils.decode(shape, 6)
        val mapLibrePath = decodedPointsMapbox.map { LatLng(it.latitude(), it.longitude()) }

        if (mapLibrePath.isEmpty()) {
            Log.w("DisplayRoute", "Decoded path is empty.")
            return
        }

        val lineStringJson = JSONObject()
        lineStringJson.put("type", "LineString")
        val coordinatesArray = JSONArray()
        for (point in mapLibrePath) {
            val coordinatePair = JSONArray().apply {
                put(point.longitude)
                put(point.latitude)
            }
            coordinatesArray.put(coordinatePair)
        }
        lineStringJson.put("coordinates", coordinatesArray)
        val lineStringGeoJsonString = lineStringJson.toString()

        map.getStyle { style ->
            style.removeLayer(ROUTE_LAYER_ID)
            style.removeSource(ROUTE_SOURCE_ID)

            routeSource = GeoJsonSource(ROUTE_SOURCE_ID, lineStringGeoJsonString)
            style.addSource(routeSource!!)

            val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.BLUE),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            style.addLayer(routeLayer)

            val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
            boundsBuilder.include(fromPoint)
            boundsBuilder.include(toPoint)
            for (pathPoint in mapLibrePath) {
                boundsBuilder.include(pathPoint)
            }
            try {
                val latLngBounds = boundsBuilder.build()
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 100), 1000)
            } catch (e: IllegalStateException) {
                Log.w(
                    "DisplayRoute",
                    "Cannot build LatLngBounds, likely only one unique point: ${e.message}"
                )
                if (mapLibrePath.isNotEmpty()) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(mapLibrePath.first(), 15.0),
                        1000
                    )
                }
            }
        }
    }

    private fun displayNextManeuver(maneuver: com.bconf.a2maps_and.routing.Maneuver?) {
        if (maneuver != null) {
            val distanceKm = maneuver.length ?: 0.0
            val distanceMeters = distanceKm * 1000
            val instruction = maneuver.instruction ?: "Next maneuver"
            maneuverTextView?.text = String.format("%.0fm: %s", distanceMeters, instruction)
            maneuverTextView?.visibility = View.VISIBLE
        } else {
            maneuverTextView?.visibility = View.GONE
        }
    }

    private fun clearRouteAndManeuver() {
        if (!::map.isInitialized) return
        map.getStyle { style ->
            style.removeLayer(ROUTE_LAYER_ID)
            style.removeSource(ROUTE_SOURCE_ID)
        }
        maneuverTextView?.visibility = View.GONE
    }

    private fun showNavigationBottomSheet() {
        val initialFromText =  fromPointString ?: "Tap map or use current"

        if (navigationBottomSheet == null || navigationBottomSheet?.isAdded == false || navigationBottomSheet?.dialog?.isShowing == false) {
            navigationBottomSheet = NavigationBottomSheetFragment.newInstance(initialFromText, toPointString)
            navigationBottomSheet?.listener = this
            navigationBottomSheet?.show(childFragmentManager, "NavigationBottomSheet")
        } else {
            navigationBottomSheet?.setFromText(initialFromText)
            navigationBottomSheet?.setToText(toPointString ?: "Tap map to set 'To'")
        }
    }

    private fun setupLocationDisplay() {
        if (!::map.isInitialized) return
        map.getStyle { style ->
            if (style.getSource(CURRENT_LOCATION_SOURCE_ID) == null) {
                currentLocationSource = GeoJsonSource(CURRENT_LOCATION_SOURCE_ID)
                style.addSource(currentLocationSource!!)
            } else {
                currentLocationSource = style.getSourceAs(CURRENT_LOCATION_SOURCE_ID)
            }

            if (style.getLayer(CURRENT_LOCATION_LAYER_ID) == null) {
                val circleLayer = CircleLayer(CURRENT_LOCATION_LAYER_ID, CURRENT_LOCATION_SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(10f),
                    PropertyFactory.circleColor("blue"),
                    PropertyFactory.circleStrokeColor("white"),
                    PropertyFactory.circleStrokeWidth(2f)
                )
                style.addLayer(circleLayer)
            }
        }
    }
    private fun updateLocationOnMap(latLng: LatLng) {
        if (!::map.isInitialized || currentLocationSource == null) return
        val pointJson = JSONObject()
        pointJson.put("type", "Point")
        val coordinatesArray = org.json.JSONArray(listOf(latLng.longitude, latLng.latitude))
        pointJson.put("coordinates", coordinatesArray)
        val geoJsonString = pointJson.toString()
        currentLocationSource?.setGeoJson(geoJsonString)
    }

    private fun updateCurrentLocationIndicatorAndCamera(latitude: Double, longitude: Double, accuracy: Float? = null, bearing: Float? = null, bearingAccuracy: Float? = null) {
        if (!::map.isInitialized) return // Guard against map not being ready

        val newLocation = android.location.Location("LocationService")
        newLocation.latitude = latitude
        newLocation.longitude = longitude
        lastKnownLocation = newLocation
        val newLocationLatLng = LatLng(latitude, longitude)

        updateLocationOnMap(newLocationLatLng)

        Log.d("LocationUpdates", "Received from Service: Lat: $latitude, Lng: $longitude")

        if (!isUserPanning) {
            val cameraPositionBuilder = CameraPosition.Builder()
                .target(newLocationLatLng)
            cameraPositionBuilder.zoom(map.cameraPosition.zoom.coerceAtLeast(16.0))
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()), 1500)
        }
    }

    inner class LocationBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationService.ACTION_LOCATION_UPDATE) {
                val latitude = intent.getDoubleExtra(LocationService.EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(LocationService.EXTRA_LONGITUDE, 0.0)
                val accuracy = intent.getFloatExtra(LocationService.ACCURACY, 0.0f)
                val bearing = intent.getFloatExtra(LocationService.BEARING, 0.0f)
                val bearingAccuracy = intent.getFloatExtra(LocationService.BEARING_ACCURACY, 0.0f)
                if (latitude != 0.0 && longitude != 0.0 && accuracy > 100.0f) {
                    updateCurrentLocationIndicatorAndCamera(latitude, longitude, accuracy, bearing, bearingAccuracy)
                }
            }
        }
    }
}
