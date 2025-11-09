package com.bconf.a2maps_and

import android.content.res.ColorStateList
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
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.maps.MapItem
import com.bconf.a2maps_and.maps.MapsLayerManager
import com.bconf.a2maps_and.maps.MapsViewModel
import com.bconf.a2maps_and.maps.PopupMapAdapter
import com.bconf.a2maps_and.navigation.CenterOnLocationState
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.navigation.NavigationViewModel
import com.bconf.a2maps_and.placemark.GasLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkModal
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.bconf.a2maps_and.track.TrackLayerManager
import com.bconf.a2maps_and.track.TrackViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.geojson.Point
import java.io.File

class MapFragment : Fragment(), MapLibreMap.OnMapLongClickListener, MapLibreMap.OnMapClickListener,
    OnMapReadyCallback {

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    private lateinit var mapsLayerManager: MapsLayerManager
    private lateinit var placemarkLayerManager: PlacemarkLayerManager
    private lateinit var gasLayerManager: GasLayerManager
    private lateinit var trackLayerManager: TrackLayerManager
    private val navigationViewModel: NavigationViewModel by activityViewModels()
    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private val trackViewModel: TrackViewModel by activityViewModels()
    private val mapsViewModel: MapsViewModel by activityViewModels()

    private var longPressedLatLng: LatLng? = null
    private val ID_MENU_NAVIGATE = 1
    private val ID_MENU_ADD_PLACEMARK = 2
    private var fabCenterOnLocation: FloatingActionButton? = null
    private var fabHideTrack: FloatingActionButton? = null
    private var fabToggleGasLayer: FloatingActionButton? = null
    private var isUserPanning = false
    private var maneuverTextViewInFragment: TextView? = null
    private var navigationInfoPanel: View? = null
    private var remainingDistanceTextView: TextView? = null
    private var stopNavigationButton: ImageButton? = null
    private var rerouteButton: ImageButton? = null
    private var mainMenuButton: FloatingActionButton? = null
    private val args: MapFragmentArgs by navArgs()
    private var initialPlacemarkLat: Float = 999.0F
    private var initialPlacemarkLng: Float = 999.0F
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
        mainMenuButton = view.findViewById(R.id.mainMenuButton)

        mainMenuButton?.setOnClickListener { anchorView ->
            // Inflate the popup window layout
            val popupView = LayoutInflater.from(context).inflate(R.layout.popup_window_layout, null)
            val recyclerView = popupView.findViewById<RecyclerView>(R.id.popup_recycler_view)

            // Create the list of items for the popup
            val popupItems = mutableListOf<MapItem>()

            // Add the static "Menu" item first
            val menuDummyFile = File("menu") // Create a dummy file for the MapItem
            val menuMapItem = MapItem(menuDummyFile, "Menu", menuDummyFile)
            popupItems.add(menuMapItem)

            // Add all the map items from the view model
            mapsViewModel.maps.value?.let { maps ->
                popupItems.addAll(maps)
            }

            // Create and configure the PopupWindow
            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true // Makes the popup focusable
            )

            // Set up the adapter and click listener
            val adapter = PopupMapAdapter(popupItems) { selectedMapItem ->
                if (selectedMapItem.name == "Menu") {
                    findNavController().navigate(R.id.action_mapFragment_to_mainMenuFragment)
                } else {
                    mapsLayerManager.loadMapStyleFromFile(selectedMapItem.file) { style ->
                        placemarkLayerManager.onStyleLoaded(style)
                        gasLayerManager.onStyleLoaded(style)
                    }
                }
                popupWindow.dismiss() // Dismiss the popup on click
            }

            recyclerView.layoutManager = LinearLayoutManager(context)
            recyclerView.adapter = adapter

            // Show the popup window anchored to the button
            popupWindow.showAsDropDown(anchorView)
        }


        rerouteButton?.setOnClickListener {
            navigationViewModel.recalculateRoute()
        }
        stopNavigationButton?.setOnClickListener {
            navigationViewModel.stopNavigation()
            mapsLayerManager.clearRoute()
        }
        fabCenterOnLocation?.setOnClickListener {
            isUserPanning = false
            navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                updateCurrentLocationIndicatorAndCamera(location, true)
            }
        }
        fabCenterOnLocation?.setOnLongClickListener {
            navigationViewModel.onCenterOnLocationFabClicked()
            true
        }
        fabHideTrack?.setOnClickListener {
            trackViewModel.clearTrack()
        }

        fabToggleGasLayer?.setOnClickListener {
            placemarkViewModel.toggleGasLayerVisibility()
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapsDir = File(requireContext().filesDir, "maps")
        if (!mapsDir.exists()) {
            mapsDir.mkdirs()
        }
        mapsViewModel.loadMaps(mapsDir)
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.map = mapLibreMap

        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                isUserPanning = true
            }
        }
        map.addOnMapLongClickListener(this)
        map.addOnMapClickListener(this)
        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: MoveGestureDetector) {
                if (detector.pointersCount > 0) {
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }

            override fun onMove(detector: MoveGestureDetector) {
                if (detector.pointersCount > 0) {
                    lastUserInteractionTime = System.currentTimeMillis()
                }
            }

            override fun onMoveEnd(detector: MoveGestureDetector) {}
        })
        mapView.let { registerForContextMenu(it) }

        mapsLayerManager = MapsLayerManager(requireContext(), map)
        placemarkLayerManager = PlacemarkLayerManager(
            requireContext(), map,
            placemarkViewModel,
            viewLifecycleOwner.lifecycle,
            onPlacemarkClickListener = { placemarkId ->
                val modal = PlacemarkModal.newInstance(placemarkId)
                modal.show(childFragmentManager, "PlacemarkViewModal")
            }
        )
        gasLayerManager = GasLayerManager(
            requireContext(),
            map,
            placemarkViewModel,
            viewLifecycleOwner.lifecycle
        ) { _ ->
            mapView.showContextMenu()
        }
        trackLayerManager = TrackLayerManager(
            requireContext(), map, trackViewModel,
            viewLifecycleOwner.lifecycle,
        )
        mapsLayerManager.loadInitialMapStyle({ style ->
            placemarkLayerManager.onStyleLoaded(style)
            gasLayerManager.onStyleLoaded(style)


            if (initialPlacemarkLat != 999.0F && initialPlacemarkLng != 999.0F) {
                isLocationLoaded = true
                val targetLatLng =
                    LatLng(initialPlacemarkLat.toDouble(), initialPlacemarkLng.toDouble())
                val cameraPosition = CameraPosition.Builder()
                    .target(targetLatLng)
                    .zoom(13.0)
                    .build()
                map.animateCamera(
                    CameraUpdateFactory.newCameraPosition(cameraPosition),
                    1000
                )
            }
        })

        navigationViewModel.uiEvents
            .flowWithLifecycle(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.STARTED
            )
            .onEach { event ->
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
                mapsLayerManager.drawRoute(pathSegment)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.maneuverText.collectLatest { text ->
                maneuverTextViewInFragment?.text = text
                updateNavigationUi(navigationViewModel.navigationState.value)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.remainingDistance.collectLatest { distanceText ->
                remainingDistanceTextView?.text = distanceText
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.navigationState.collectLatest { navState ->
                updateNavigationUi(navState)
            }
            navigationViewModel.lastKnownGpsLocation.collectLatest { location ->
                if (location != null) {
                    updateCurrentLocationIndicatorAndCamera(location)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            trackViewModel.trackPoints.collectLatest { points ->
                fabHideTrack?.visibility = if (points.isNotEmpty()) View.VISIBLE else View.GONE
                if (points.isNotEmpty()) zoomToRoute(points)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.centerOnLocationState.collectLatest { state ->
                updateCenterOnLocationFab(state)
            }
        }
    }

    private fun zoomToRoute(points: List<LatLng>) {
        if (points.isEmpty()) return
        val latLngBounds = LatLngBounds.Builder()
            .includes(points)
            .build()

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(latLngBounds, 150),
            1000
        )
    }

    private fun updateCurrentLocationIndicatorAndCamera(
        location: Location,
        force: Boolean = false
    ) {
        mapsLayerManager.updateCurrentLocation(
            Point.fromLngLat(
                location.longitude,
                location.latitude
            )
        )

        val state = navigationViewModel.centerOnLocationState.value
        val shouldFollow =
            state == CenterOnLocationState.FOLLOW || state == CenterOnLocationState.RECORD
        if (isUserPanning && (System.currentTimeMillis() - lastUserInteractionTime > USER_INTERACTION_TIMEOUT_MS)) {
            isUserPanning = false
        }
        if (force || (shouldFollow && !isUserPanning) || !isLocationLoaded) {
            isLocationLoaded = true
            val lat = if (location.latitude == 0.0) 56.292374 else location.latitude
            val lng = if (location.longitude == 0.0) 43.985402 else location.longitude
            val currentLatLng = LatLng(lat, lng)

            val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                .zoom(map.cameraPosition.zoom.coerceAtLeast(13.0))
                .padding(0.0, 0.0, 0.0, 0.0)

            if (state == CenterOnLocationState.FOLLOW || state == CenterOnLocationState.RECORD) {
                cameraBuilder.tilt(0.0)
                cameraBuilder.bearing(0.0)
            }
            if (state == CenterOnLocationState.RECORD) {
                trackViewModel.setTrackPoints(navigationViewModel.recordedPath.value)
            }

            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraBuilder.build()), 600)
        }
    }

    private fun updateCenterOnLocationFab(state: CenterOnLocationState) {
        when (state) {
            CenterOnLocationState.INACTIVE -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_my_location)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            }

            CenterOnLocationState.FOLLOW -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_navigation)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
            }

            CenterOnLocationState.RECORD -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_record)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.RED)
            }
        }
    }

    private fun showCustomToast(message: String, isError: Boolean) {
        val toast = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG)
        if (isError) {
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


    override fun onStart() {
        super.onStart()
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) mapView.onResume()
    }

    override fun onPause() {
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
        map.removeOnMapLongClickListener(this)
        map.removeOnMapClickListener(this)
        if (::mapView.isInitialized) mapView.onDestroy()
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (placemarkLayerManager.handleMapClick(point)) return true
        if (gasLayerManager.handleMapClick(point)) return true
        return false
    }

    override fun onMapLongClick(point: LatLng): Boolean {
        longPressedLatLng = point
        mapView.showContextMenu()
        return true
    }

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        longPressedLatLng?.let {
            menu.setHeaderTitle("Actions")
            menu.add(0, ID_MENU_NAVIGATE, 0, "Navigate to")
            menu.add(0, ID_MENU_ADD_PLACEMARK, 0, "Add Placemark")
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
}