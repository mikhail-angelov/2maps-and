package com.bconf.a2maps_and

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.placemark.PlacemarkLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkService
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.io.File
import java.io.IOException


class MapFragment : Fragment(), MapLibreMap.OnMapLongClickListener, MapLibreMap.OnMapClickListener,
    OnMapReadyCallback {

    private lateinit var navigationViewModel: NavigationViewModel // Shared ViewModel
    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap // Made public to be accessible from MainActivity if needed initially

    private lateinit var placemarkLayerManager: PlacemarkLayerManager
    private lateinit var placemarkService: PlacemarkService

    private var longPressedLatLng: LatLng? = null
    private val ID_MENU_NAVIGATE = 1
    private val ID_MENU_ADD_PLACEMARK = 2
    private var fabCenterOnLocation: FloatingActionButton? = null // Reference for the new FAB
    private var isUserPanning = false
    private var currentLocationSource: GeoJsonSource? = null
    private val CURRENT_LOCATION_SOURCE_ID = "current-location-source"
    private val CURRENT_LOCATION_LAYER_ID = "current-location-layer"
    private val ROUTE_SOURCE_ID = "route-source"
    private val ROUTE_LAYER_ID = "route-layer"
    private var routeSource: GeoJsonSource? = null
    private var maneuverTextViewInFragment: TextView? = null
    private var navigationInfoPanel: View? = null
    private var remainingDistanceTextView: TextView? = null
    private var stopNavigationButton: ImageButton? = null
    private var rerouteButton: ImageButton? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationViewModel = ViewModelProvider(this).get(NavigationViewModel::class.java)
        placemarkService = PlacemarkService(requireContext())
        MapLibre.getInstance(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = view.findViewById(R.id.mapViewInFragment)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)
        maneuverTextViewInFragment = view.findViewById(R.id.mapFragmentManeuverTextView)
        navigationInfoPanel = view.findViewById(R.id.navigationInfoPanel)
        remainingDistanceTextView = view.findViewById(R.id.remainingDistanceTextView)
        stopNavigationButton = view.findViewById(R.id.stopNavigationButton)
        rerouteButton = view.findViewById(R.id.rerouteButton)
        fabCenterOnLocation = view.findViewById(R.id.fabCenterOnLocationInFragment)

        rerouteButton?.setOnClickListener {
            navigationViewModel.recalculateRoute()
        }
        stopNavigationButton?.setOnClickListener {
            navigationViewModel.stopNavigation()
        }
        fabCenterOnLocation?.setOnClickListener {
            navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                val currentLatLng =
                    org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                    .zoom(map.cameraPosition.zoom.coerceAtLeast(15.0))
                    .padding(0.0, 0.0, 0.0, 0.0)
                    .tilt(0.0)

                map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 600)
            } ?: run {
                Toast.makeText(
                    requireContext(),
                    "Current location not available yet.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        Log.d(
            "MapFragment",
            "maneuverTextViewInFragment is null: ${maneuverTextViewInFragment == null}"
        ) // ADD THIS LOG
        return view
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.map = mapLibreMap
        // Basic map setup, can be expanded or configured via listener
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(56.292374, 43.985402)) // Default position
            .zoom(13.0) // Default zoom
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
        map.addOnMapClickListener(this)
        mapView.let { registerForContextMenu(it) }

        placemarkLayerManager = PlacemarkLayerManager(requireContext(), map, placemarkService)
        loadInitialMapStyle({ style ->
            setupLocationDisplay(style)
            placemarkLayerManager.onStyleLoaded(style)
        })


        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                updateCurrentLocationIndicatorAndCamera(location)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.currentDisplayedPath.collectLatest { pathSegment ->
                drawRouteSegmentOnMapUI(pathSegment)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.maneuverText.collectLatest { text ->
                Log.d("MapFragment", "Received maneuverText from ViewModel: '$text'")
                maneuverTextViewInFragment?.text = text
                updateNavigationUi(navigationViewModel.navigationState.value)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.remainingDistance.collectLatest { distanceText ->
                remainingDistanceTextView?.text = distanceText
            }
        }

        // Observe navigation state for camera adjustments (e.g., zoom to route on start)
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.navigationState.collectLatest { navState ->
                updateNavigationUi(navState)

                if (navState == NavigationState.NAVIGATING && navigationViewModel.currentDisplayedPath.value.isNotEmpty()) {
                    // Avoid re-zooming if already navigating and path just updated slightly
                    // This logic needs to be smarter, e.g., only zoom on first NAVIGATING state after IDLE
                    if (map.cameraPosition.zoom < 10) { // Simple check, improve this
                        zoomToRoute(navigationViewModel.currentDisplayedPath.value)
                    }
                }
            }
        }
    }

    private fun updateNavigationUi(navState: NavigationState) {
        val isNavigatingOrOffRoute =
            navState == NavigationState.NAVIGATING || navState == NavigationState.OFF_ROUTE

        navigationInfoPanel?.visibility = if (isNavigatingOrOffRoute) View.VISIBLE else View.GONE
        rerouteButton?.visibility =
            if (navState == NavigationState.OFF_ROUTE) View.VISIBLE else View.GONE

        val maneuverText = maneuverTextViewInFragment?.text
        if (isNavigatingOrOffRoute && !maneuverText.isNullOrBlank()) {
            maneuverTextViewInFragment?.visibility = View.VISIBLE
        } else {
            maneuverTextViewInFragment?.visibility = View.GONE
        }
        fabCenterOnLocation?.visibility = if (navState == NavigationState.IDLE) {
            View.VISIBLE
        } else {
            View.GONE
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

    fun loadInitialMapStyle(onStyleLoaded: ((style: Style) -> Unit)? = null) {
        if (!::map.isInitialized) return

        val file = getFileFromAssets(context, "niz-osm.mbtiles")
        Log.i("--files", file.absolutePath)

        val styleJson =
            context?.assets?.open("bright.json")?.bufferedReader().use { it?.readText() }
        if (styleJson == null) {
            Log.e("MapFragment", "Failed to read bright.json")
            return
        }

        val updatedStyleJson = styleJson.replace(
            "\"url\": \"asset://niz-osm.mbtiles\"",
            "\"url\": \"mbtiles://${file.absolutePath}\""
        )

        map.setStyle(
            Style.Builder().fromJson(updatedStyleJson)
        ) { style ->
            Log.i("MapFragment", "Style loaded.")
            onStyleLoaded?.invoke(style)
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
    }

    override fun onPause() {
        Log.i("MapFragment", "Paused")
        super.onPause()
        if (::mapView.isInitialized) mapView.onPause()
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

    override fun onMapClick(point: LatLng): Boolean {
        // First, let PlacemarkLayerManager try to handle the click
        if (placemarkLayerManager.handleMapClick(point)) {
            return true // Placemark click was handled
        }

        // If not handled by PlacemarkLayerManager, do other map click logic if any
        // For example, deselecting something or showing coordinates
        Log.d("MapFragment", "Map clicked at: Lat ${point.latitude}, Lng ${point.longitude}")
        // Return false if you want other listeners (if any) to also process this click,
        // or true if you consider it handled here.
        return false
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View, // This v is the MapView from the fragment
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        // Check the ID of the MapView inside the fragment's layout
        if (v.id == R.id.mapViewInFragment && longPressedLatLng != null) {
            menu.setHeaderTitle("Navigate")
            val label =
                if (navigationViewModel.navigationState.value == NavigationState.IDLE) "to this point" else "stop"
            menu.add(0, ID_MENU_NAVIGATE, 1, label)
            menu.add(0, ID_MENU_ADD_PLACEMARK, 1, "add placemark")
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        longPressedLatLng?.let { coords ->
            val coordinateText = "Lat: %.4f, Lng: %.4f".format(coords.latitude, coords.longitude)
            when (item.itemId) {
                ID_MENU_NAVIGATE -> {
                    // Toast.makeText(requireContext(), "'To' set: $coordinateText", Toast.LENGTH_SHORT).show()
                    Log.d(
                        "MapContextMenu",
                        "Navigate to point: $coordinateText : ${navigationViewModel.navigationState.value}"
                    )
                    if (navigationViewModel.navigationState.value == NavigationState.IDLE) {
                        navigationViewModel.requestNavigationTo(coords) // ViewModel handles the rest
                    } else {
                        navigationViewModel.stopNavigation()
                    }
                    return true
                }

                ID_MENU_ADD_PLACEMARK -> {
                    // Toast.makeText(requireContext(), "'To' set: $coordinateText", Toast.LENGTH_SHORT).show()
                    Log.d("MapContextMenu", "add placemark")
                    placemarkLayerManager.showAddPlacemarkDialog(coords)
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

            val geoJson = JSONObject()
            geoJson.put("type", "Feature")
            geoJson.put("geometry", lineStringJson)

            routeSource = GeoJsonSource(ROUTE_SOURCE_ID, geoJson.toString())
            style.addSource(routeSource!!)

            val routeLayer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).apply {
                setProperties(
                    PropertyFactory.lineColor(Color.parseColor("#FF3887BE")),
                    PropertyFactory.lineWidth(5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            }
            style.addLayer(routeLayer)
        }
    }


    private fun setupLocationDisplay(style: Style) {
        // Check if source and layer already exist
        if (style.getSource(CURRENT_LOCATION_SOURCE_ID) == null) {
            currentLocationSource = GeoJsonSource(CURRENT_LOCATION_SOURCE_ID)
            style.addSource(currentLocationSource!!)
        } else {
            currentLocationSource = style.getSource(CURRENT_LOCATION_SOURCE_ID) as GeoJsonSource
        }

        if (style.getLayer(CURRENT_LOCATION_LAYER_ID) == null) {
            val locationLayer =
                CircleLayer(CURRENT_LOCATION_LAYER_ID, CURRENT_LOCATION_SOURCE_ID).apply {
                    setProperties(
                        PropertyFactory.circleColor(Color.BLUE),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(Color.WHITE),
                        PropertyFactory.circleStrokeWidth(2f)
                    )
                }
            style.addLayer(locationLayer)
        }
    }

    private fun updateCurrentLocationIndicatorAndCamera(
        location: Location
    ) {
        if (!::map.isInitialized || currentLocationSource == null) return
        val point = Point.fromLngLat(location.longitude, location.latitude)
        currentLocationSource?.setGeoJson(point)

        if ((navigationViewModel.navigationState.value == NavigationState.NAVIGATING ||
                    navigationViewModel.navigationState.value == NavigationState.OFF_ROUTE) && !isUserPanning
        ) {

            // Calculate padding to shift the location 100px from the bottom
            val bottomPaddingPx = 2000f
            val density = resources.displayMetrics.density
            val topPaddingDp = (bottomPaddingPx / density).toDouble()


            // When navigating, camera should follow the user's location and bearing
            val cameraBuilder = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(map.cameraPosition.zoom.coerceAtLeast(16.0)) // Higher zoom during nav
                .tilt(45.0) // Tilted view
                .bearing(location.bearing.toDouble()) // Follow user's direction
                .padding(0.0, topPaddingDp, 0.0, 0.0)

            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 400)
        }
    }


    fun zoomToRoute(route: List<LatLng>) {
        if (!::map.isInitialized || route.isEmpty()) {
            return
        }

        // Create a LatLngBounds object that includes all points of the route
        val builder = org.maplibre.android.geometry.LatLngBounds.Builder()
        route.forEach {
            builder.include(it)
        }
        val bounds = builder.build()

        // Animate the camera to show the entire route with padding
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100), 1000)
    }

}

