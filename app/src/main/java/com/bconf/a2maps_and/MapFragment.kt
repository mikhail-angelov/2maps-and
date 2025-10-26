package com.bconf.a2maps_and

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.placemark.GasLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkModal
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.bconf.a2maps_and.track.TrackLayerManager
import com.bconf.a2maps_and.track.TrackViewModel
import com.bconf.a2maps_and.navigation.NavigationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
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

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap // Made public to be accessible from MainActivity if needed initially

    private lateinit var placemarkLayerManager: PlacemarkLayerManager
    private lateinit var gasLayerManager: GasLayerManager
    private lateinit var trackLayerManager: TrackLayerManager
    private val navigationViewModel: NavigationViewModel by activityViewModels()
    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private val trackViewModel: TrackViewModel by activityViewModels()

    private var longPressedLatLng: LatLng? = null
    private val ID_MENU_NAVIGATE = 1
    private val ID_MENU_ADD_PLACEMARK = 2
    private var fabCenterOnLocation: FloatingActionButton? = null // Reference for the new FAB
    private var fabHideTrack: FloatingActionButton? = null
    private var fabToggleGasLayer: FloatingActionButton? = null
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
    private var mainMenuButton: FloatingActionButton? = null
    private val args: MapFragmentArgs by navArgs()
    private var initialPlacemarkLat: Float = 999.0F // Use the same "no value" default
    private var initialPlacemarkLng: Float = 999.0F // Use the same "no value" default
    private var initialPlacemarkId: String = ""
    private var isLocationLoaded: Boolean = false
    private var lastUserInteractionTime: Long = 0
    private val USER_INTERACTION_TIMEOUT_MS = 60 * 1000 // 1 minute

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(requireContext())

        initialPlacemarkLat = args.placemarkLat
        initialPlacemarkLng = args.placemarkLng
        initialPlacemarkId = args.placemarkId

        Log.d(
            "MapFragment",
            "Received args: Lat=$initialPlacemarkLat, Lng=$initialPlacemarkLng, ID=$initialPlacemarkId"
        )

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
        fabHideTrack = view.findViewById(R.id.fabHideTrack)
        fabToggleGasLayer = view.findViewById(R.id.fabToggleGasLayer)
        mainMenuButton = view.findViewById(R.id.mainMenuButton) // Initialize mainMenuButton

        mainMenuButton?.setOnClickListener { // Set click listener
            findNavController().navigate(R.id.action_mapFragment_to_mainMenuFragment)
        }

        rerouteButton?.setOnClickListener {
            navigationViewModel.recalculateRoute()
        }
        stopNavigationButton?.setOnClickListener {
            navigationViewModel.stopNavigation()
        }
        fabCenterOnLocation?.setOnClickListener {
            navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                val currentLatLng = LatLng(location.latitude, location.longitude)
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
        fabHideTrack?.setOnClickListener {
            trackViewModel.clearTrack()
        }

        fabToggleGasLayer?.setOnClickListener {
            placemarkViewModel.toggleGasLayerVisibility()
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
        // Add a listener to detect user gestures on the map
        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                // User started moving the map
                if (detector.pointersCount > 0) { // Check if it's a real user gesture
                    lastUserInteractionTime = System.currentTimeMillis()
                    Log.d("MapFragment", "User started panning/zooming.")
                }
            }

            override fun onMove(detector: MoveGestureDetector) {
                // Continuously update the time while the user is moving the map
                if (detector.pointersCount > 0) {
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }

            override fun onMoveEnd(detector: MoveGestureDetector) {
                // User finished moving the map
                Log.d("MapFragment", "User finished panning/zooming.")
            }
        })
        mapView.let { registerForContextMenu(it) }

        loadInitialMapStyle({ style ->
            setupLocationDisplay(style)
            placemarkLayerManager = PlacemarkLayerManager(
                requireContext(), map,
                placemarkViewModel,
                viewLifecycleOwner.lifecycle,
                onPlacemarkClickListener = { placemarkId ->
                    // Callback is triggered, now show the bottom sheet
                    val modal = PlacemarkModal.newInstance(placemarkId)
                    modal.show(childFragmentManager, "PlacemarkViewModal")
                }
            )
            placemarkLayerManager.onStyleLoaded(style)
            gasLayerManager = GasLayerManager(requireContext(), map, placemarkViewModel, viewLifecycleOwner.lifecycle) { placemarkId ->
                mapView.showContextMenu()
            }
            gasLayerManager.onStyleLoaded(style)
            trackLayerManager = TrackLayerManager(
                requireContext(), map, trackViewModel,
                viewLifecycleOwner.lifecycle,
            )

            if (initialPlacemarkLat != 999.0F && initialPlacemarkLng != 999.0F) {
                isLocationLoaded = true //prevent camera movement on first load
                val targetLatLng =
                    LatLng(initialPlacemarkLat.toDouble(), initialPlacemarkLng.toDouble())
                val cameraPosition = CameraPosition.Builder()
                    .target(targetLatLng)
                    .zoom(15.0) // Adjust zoom level as desired
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(cameraPosition),
                    1000
                ) // Animate over 1 second
                Log.d("MapFragment", "Centering map on: $targetLatLng :: $initialPlacemarkLat | ")

                // Optional: Highlight or show info for this placemark
                // You might need to tell PlacemarkLayerManager to highlight this specific placemarkId
                // placemarkLayerManager.highlightPlacemark(initialPlacemarkId)
            } else {
                Log.d("MapFragment", "Centering map on default: 56.292374, 43.985402")
                navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                    val lat = if (location.latitude == 0.0) 56.292374 else location.latitude
                    val lng = if (location.longitude == 0.0) 43.985402 else location.longitude

                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(lat, lng)) // Default position
                        .zoom(13.0) // Default zoom
                        .tilt(0.0)
                        .build()
                }
            }
        })
        navigationViewModel.lastKnownGpsLocation.value?.let { location ->
            if (initialPlacemarkLat != 999.0F && initialPlacemarkLng != 999.0F) {
                initialPlacemarkLat = 999.0F
                initialPlacemarkLng = 999.0F
                return@let
            }
            val lat = if (location.latitude == 0.0) 56.292374 else location.latitude
            val lng = if (location.longitude == 0.0) 43.985402 else location.longitude
            val currentLatLng = LatLng(lat, lng)
            Log.d("MapFragment", "Centering map callback on: $currentLatLng")
            val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                .zoom(map.cameraPosition.zoom.coerceAtLeast(15.0))
                .padding(0.0, 0.0, 0.0, 0.0)
                .tilt(0.0)

            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 600)
        }
        navigationViewModel.uiEvents
            .flowWithLifecycle(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.STARTED
            ) // Use STARTED state
            .onEach { event ->
                // Add a log here to see if events are being received
                Log.d("MapFragment", "Received UI event: $event")
                when (event) {
                    is NavigationViewModel.UiEvent.ShowToast -> {
                        showCustomToast(event.message, event.isError)
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)


        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                if (location != null) {
                    updateCurrentLocationIndicatorAndCamera(location)
                }
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
        viewLifecycleOwner.lifecycleScope.launch {
            trackViewModel.trackPoints.collectLatest { points ->
                // Show the button if the list is not empty, hide it otherwise
                Log.d("MapFragment", "Track points: $points: ${points.isNotEmpty()}")
                fabHideTrack?.visibility = if (points.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showCustomToast(message: String, isError: Boolean) {
        val toast = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG)
        if (isError) {
            // A simple way to make the toast "red" is to change its background color
            // This works on most API levels but might look different across devices.
            @Suppress("DEPRECATION")
            toast.view?.setBackgroundColor(android.graphics.Color.RED)
        }
        toast.show()
    }

    private fun updateNavigationUi(navState: NavigationState) {
        val isNavigatingOrOffRoute =
            navState != NavigationState.IDLE

        navigationInfoPanel?.visibility = if (isNavigatingOrOffRoute) View.VISIBLE else View.GONE
        rerouteButton?.visibility =
            if (navState == NavigationState.OFF_ROUTE || navState == NavigationState.ROUTE_CALCULATION_FAILED) View.VISIBLE else View.GONE

        val maneuverText = maneuverTextViewInFragment?.text
        if (isNavigatingOrOffRoute && !maneuverText.isNullOrBlank()) {
            maneuverTextViewInFragment?.visibility = View.VISIBLE
        } else {
            maneuverTextViewInFragment?.visibility = View.GONE
        }
        val showStandardFabs = !isNavigatingOrOffRoute
        fabCenterOnLocation?.visibility = if (showStandardFabs) View.VISIBLE else View.GONE
        mainMenuButton?.visibility = if (showStandardFabs) View.VISIBLE else View.GONE
        fabToggleGasLayer?.visibility = if (showStandardFabs) View.VISIBLE else View.GONE
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

        val sharedPreferences = requireContext().getSharedPreferences("maps_prefs", Context.MODE_PRIVATE)
        val selectedMapPath = sharedPreferences.getString("selected_map", null)

        val file = if (selectedMapPath != null) {
            File(selectedMapPath)
        } else {
            getFileFromAssets(context, "niz-osm.mbtiles")
        }

        if (!file.exists()) {
            Log.e("MapFragment", "Map file does not exist: ${file.absolutePath}")
            // Fallback to default map
            val defaultFile = getFileFromAssets(context, "niz-osm.mbtiles")
            loadMapStyle(defaultFile, onStyleLoaded)
            return
        }

        loadMapStyle(file, onStyleLoaded)
    }

    private fun loadMapStyle(file: File, onStyleLoaded: ((style: Style) -> Unit)?) {
        Log.i("--files", file.absolutePath)

        val format = try {
            val db =
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val cursor =
                db.query("metadata", arrayOf("value"), "name = ?", arrayOf("format"), null, null, null)
            var formatValue: String? = null
            if (cursor.moveToFirst()) {
                formatValue = cursor.getString(0)
            }
            cursor.close()
            db.close()
            formatValue
        } catch (e: Exception) {
            Log.e("MapFragment", "Error reading mbtiles metadata", e)
            null // Fallback
        }

        val styleJson = when (format) {
            "pbf", "mvt" -> {
                // It's a vector tileset, use the bright style
                context?.assets?.open("bright.json")?.bufferedReader()?.use { it.readText() }
                    ?.replace(
                        "\"url\": \"asset://niz-osm.mbtiles\"",
                        "\"url\": \"mbtiles://${file.absolutePath}\""
                    )
            }

            "png", "jpg" -> {
                // It's a raster tileset, generate a raster style
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
                Log.w(
                    "MapFragment",
                    "Unknown or unsupported mbtiles format: '$format'. Falling back to vector style."
                )
                // Fallback to vector style for unknown formats.
                context?.assets?.open("bright.json")?.bufferedReader()?.use { it.readText() }
                    ?.replace(
                        "\"url\": \"asset://niz-osm.mbtiles\"",
                        "\"url\": \"mbtiles://${file.absolutePath}\""
                    )
            }
        }


        if (styleJson == null) {
            Log.e("MapFragment", "Failed to create style JSON for ${file.absolutePath}")
            return
        }

        map.setStyle(
            Style.Builder().fromJson(styleJson)
        ) { style ->
            Log.i("MapFragment", "Style loaded for format '$format'.")
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
        mapView.showContextMenu()
        return true
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (::placemarkLayerManager.isInitialized && placemarkLayerManager.handleMapClick(point)) {
            return true
        }
        if (::gasLayerManager.isInitialized && gasLayerManager.handleMapClick(point)) {
            return true
        }
        return false
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
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
        if (!isLocationLoaded) {
            isLocationLoaded = true
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(13.0) // Default zoom
                .tilt(0.0)
                .build()
        }

        val isNavigating =
            navigationViewModel.navigationState.value == NavigationState.NAVIGATING ||
                    navigationViewModel.navigationState.value == NavigationState.OFF_ROUTE
        val userInteractionTimeoutPassed =
            (System.currentTimeMillis() - lastUserInteractionTime) > USER_INTERACTION_TIMEOUT_MS
        val shouldFollow = isNavigating && userInteractionTimeoutPassed

        if (shouldFollow) {

            // Calculate padding to shift the location 100px from the bottom
            isUserPanning = false
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
