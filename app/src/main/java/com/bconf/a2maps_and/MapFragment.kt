package com.bconf.a2maps_and

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bconf.a2maps_and.databinding.FragmentMapBinding
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.routing.RetrofitClient
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteRequest
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import com.google.android.material.textview.MaterialTextView
import com.mapbox.geojson.utils.PolylineUtils
import kotlinx.coroutines.flow.collectLatest
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


class MapFragment : Fragment(), MapLibreMap.OnMapLongClickListener, OnMapReadyCallback {

    private lateinit var  navigationViewModel: NavigationViewModel // Shared ViewModel


    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mapView: MapView
    lateinit var map: MapLibreMap // Made public to be accessible from MainActivity if needed initially

    private var longPressedLatLng: LatLng? = null
    private val ID_MENU_NAVIGATE = 1

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
        navigationViewModel = ViewModelProvider(this).get(NavigationViewModel::class.java)
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
//        setupCurrentUserLocationLayer() // For the blue dot
        mapView.let { registerForContextMenu(it) }

        loadInitialMapStyle()
        setupLocationDisplay()
        // Observe displayed path from ViewModel (which gets it from LocationService)
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.currentDisplayedPath.collectLatest { pathSegment ->
                drawRouteSegmentOnMapUI(pathSegment) // Your existing method
            }
        }
        // Observe navigation state for camera adjustments (e.g., zoom to route on start)
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.navigationState.collectLatest { navState ->
                if (navState == NavigationState.NAVIGATING && navigationViewModel.currentDisplayedPath.value.isNotEmpty()) {
                    // Avoid re-zooming if already navigating and path just updated slightly
                    // This logic needs to be smarter, e.g., only zoom on first NAVIGATING state after IDLE
                    if(map.cameraPosition.zoom < 10) { // Simple check, improve this
                        zoomToRoute(navigationViewModel.currentDisplayedPath.value)
                    }
                }
                // Center map on user when navigating if not panned by user
                if (navState == NavigationState.NAVIGATING || navState == NavigationState.OFF_ROUTE) {
                    navigationViewModel.lastKnownGpsLocation.value?.let { loc ->
                        centerMapOnLocation(LatLng(loc.latitude, loc.longitude), loc.bearing)
                    }
                }
            }
        }
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
        Log.i("MapFragment", "Started")
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        Log.i("MapFragment", "Resumed")
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            locationReceiver,
            IntentFilter(LocationService.ACTION_LOCATION_UPDATE))
    }

    override fun onPause() {
        Log.i("MapFragment", "Paused")
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
//            menu.setHeaderTitle("Point Options")
            val label = if (navigationViewModel.navigationState.value === NavigationState.IDLE) "Navigate to this point" else "Stop navigation"
            menu.add(0, ID_MENU_NAVIGATE, 1, label)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        longPressedLatLng?.let { coords ->
            val coordinateText = "Lat: %.4f, Lng: %.4f".format(coords.latitude, coords.longitude)
            when (item.itemId) {
                ID_MENU_NAVIGATE -> {
                    // Toast.makeText(requireContext(), "'To' set: $coordinateText", Toast.LENGTH_SHORT).show()
                    Log.d("MapContextMenu", "Navigate to point: $coordinateText : ${navigationViewModel.navigationState.value}")
                    if (navigationViewModel.navigationState.value === NavigationState.IDLE) {
                        navigationViewModel.requestNavigationTo(coords) // ViewModel handles the rest
                    }else{
                        navigationViewModel.stopNavigation()
                    }
                    return true
                }
            }
        }
        return super.onContextItemSelected(item)
    }

    private fun drawRouteSegmentOnMapUI(routeSegment: List<LatLng>) {
        if (!::map.isInitialized || !map.style!!.isFullyLoaded) { // Check if style is loaded
            Log.w("MapFragment", "Map or style not ready for drawing route.")
            return
        }

        map.getStyle { style -> // Ensure working with current style
            style.removeLayer(ROUTE_LAYER_ID)
            style.removeSource(ROUTE_SOURCE_ID) // Remove by ID

            if (routeSegment.isEmpty()) {
                Log.d("MapFragment", "Route segment is empty, clearing route from map.")
                return@getStyle
            }

            val lineStringJson = JSONObject()
            lineStringJson.put("type", "LineString")
            val coordinatesArray = JSONArray()
            routeSegment.forEach { point ->
                coordinatesArray.put(JSONArray().apply {
                    put(point.longitude)
                    put(point.latitude)
                })
            }
            lineStringJson.put("coordinates", coordinatesArray)
            val lineStringGeoJsonString = lineStringJson.toString()

            routeSource = GeoJsonSource(ROUTE_SOURCE_ID, lineStringGeoJsonString)
            style.addSource(routeSource!!)

            val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(Color.parseColor("#3887be")), // A nice blue
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
            )
            style.addLayerBelow(routeLayer, "road-label") // Draw below labels if possible, or just addLayer

            Log.d("MapFragment", "Route segment drawn/updated on map. Points: ${routeSegment.size}")
        }
    }

    private fun zoomToRoute(routePath: List<LatLng>) {
        if (!::map.isInitialized || routePath.size < 2) return

        val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
        routePath.forEach { boundsBuilder.include(it) }
        try {
            val bounds = boundsBuilder.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 150), 1000) // 150px padding
        } catch (e: IllegalStateException) {
            Log.w("MapFragment", "Could not create bounds for zooming to route: ${e.message}")
            // Fallback: zoom to the first point
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(routePath.first(), 15.0), 1000)
        }
    }

    // Called by MainActivity when non-navigational GPS update is available
    fun updateCurrentUserLocationOnMap(point: LatLng) {
        if (!::map.isInitialized || !map.style!!.isFullyLoaded) return
        val pointJson = JSONObject().apply {
            put("type", "Point")
            put("coordinates", JSONArray().put(point.longitude).put(point.latitude))
        }
        currentLocationSource?.setGeoJson(pointJson.toString())

        // Center map if not navigating and not user panning
        if (navigationViewModel.navigationState.value == NavigationState.IDLE /* && !isUserPanning */) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 15.0))
        }
    }


    private fun setupCurrentUserLocationLayer() {
        if (!::map.isInitialized || !map.style!!.isFullyLoaded) return
        map.getStyle { style ->
            if (style.getSource(CURRENT_LOCATION_SOURCE_ID) == null) {
                currentLocationSource = GeoJsonSource(CURRENT_LOCATION_SOURCE_ID)
                style.addSource(currentLocationSource!!)
            }
            if (style.getLayer(CURRENT_LOCATION_LAYER_ID) == null) {
                val circleLayer = CircleLayer(CURRENT_LOCATION_LAYER_ID, CURRENT_LOCATION_SOURCE_ID)
                    .withProperties( /* ... your blue dot style ... */ )
                style.addLayer(circleLayer)
            }
        }
    }
    private fun centerMapOnLocation(latLng: LatLng, bearing: Float?) {
        if (!::map.isInitialized /* || isUserPanning */) return // Add isUserPanning flag if MapFragment manages it

        val cameraBuilder = org.maplibre.android.camera.CameraPosition.Builder().target(latLng)
        bearing?.let { cameraBuilder.bearing(it.toDouble()) }
        cameraBuilder.zoom(map.cameraPosition.zoom.coerceAtLeast(16.0)) // Keep zoom or zoom in

        map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 1000)
    }


    private fun clearRouteAndManeuver() {
        if (!::map.isInitialized) return
        map.getStyle { style ->
            style.removeLayer(ROUTE_LAYER_ID)
            style.removeSource(ROUTE_SOURCE_ID)
        }
        maneuverTextView?.visibility = View.GONE
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
                Log.d("LocationUpdates", "BroadcastReceiver from Service: Lat: $latitude, Lng: $longitude, accuracy: $accuracy, bearing: $bearing, bearingAccuracy: $bearingAccuracy")
                if (latitude != 0.0 && longitude != 0.0 && accuracy > 1.0f) {
                    updateCurrentLocationIndicatorAndCamera(latitude, longitude, accuracy, bearing, bearingAccuracy)
                    val updateLastKnownGpsLocation = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude
                        longitude
                    }
                    updateLastKnownGpsLocation.accuracy = accuracy
                    updateLastKnownGpsLocation.bearing = bearing
                    updateLastKnownGpsLocation.latitude = latitude
                    updateLastKnownGpsLocation.longitude = longitude
                    updateLastKnownGpsLocation.time = System.currentTimeMillis()

                    navigationViewModel.updateLastKnownGpsLocation(updateLastKnownGpsLocation)
                }
            }
        }
    }
}
