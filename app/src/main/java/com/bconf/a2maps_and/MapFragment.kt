package com.bconf.a2maps_and

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import androidx.core.graphics.toColorInt
import android.os.Bundle
import android.util.Log
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.maps.MapsLayerManager
import com.bconf.a2maps_and.maps.MapsViewModel
import com.bconf.a2maps_and.maps.PopupItem
import com.bconf.a2maps_and.maps.PopupMapAdapter
import com.bconf.a2maps_and.navigation.CenterOnLocationState
import com.bconf.a2maps_and.navigation.NavigationChooser
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.navigation.NavigationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.bconf.a2maps_and.placemark.GasLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkModal
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.bconf.a2maps_and.track.TrackLayerManager
import com.bconf.a2maps_and.track.TrackViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import org.maplibre.android.MapLibre
import org.maplibre.android.plugins.scalebar.ScaleBarOptions
import org.maplibre.android.plugins.scalebar.ScaleBarPlugin
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
    private var fabRadialFollow: FloatingActionButton? = null
    private var fabRadialRecord: FloatingActionButton? = null
    private var fabRadialFollowRecord: FloatingActionButton? = null
    private var isRadialMenuOpen = false
    private val radialMenuAnimDuration = 200L
    private var isUserPanning = false
    private var maneuverTextViewInFragment: TextView? = null
    private var navigationInfoPanel: View? = null
    private var remainingDistanceTextView: TextView? = null
    private var stopNavigationButton: ImageButton? = null
    private var rerouteButton: ImageButton? = null
    private var mainMenuButton: FloatingActionButton? = null
    private val args: MapFragmentArgs by navArgs()

    private var initialPlacemarkLat: Float? = null
    private var initialPlacemarkLng: Float? = null
    private var initialPlacemarkId: String = ""
    private var isLocationLoaded: Boolean = false
    private var lastUserInteractionTime: Long = 0
    private val USER_INTERACTION_TIMEOUT_MS = 60 * 1000 // 1 minute

    companion object {
        private const val DEFAULT_LOCATION_LAT = 56.292374
        private const val DEFAULT_LOCATION_LNG = 43.985402
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(requireContext())

        initialPlacemarkLat = args.placemarkLat.takeUnless { it == 999.0F }
        initialPlacemarkLng = args.placemarkLng.takeUnless { it == 999.0F }
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
        fabRadialFollow = view.findViewById(R.id.fabRadialFollow)
        fabRadialRecord = view.findViewById(R.id.fabRadialRecord)
        fabRadialFollowRecord = view.findViewById(R.id.fabRadialFollowRecord)
        mainMenuButton = view.findViewById(R.id.mainMenuButton)

        mainMenuButton?.setOnClickListener { anchorView ->
            @SuppressLint("InflateParams")
            val popupView = LayoutInflater.from(context).inflate(R.layout.popup_window_layout, null)
            val recyclerView = popupView.findViewById<RecyclerView>(R.id.popup_recycler_view)

            // Build popup items: Menu action first, then available maps
            val popupItems = mutableListOf<PopupItem>(PopupItem.Menu)
            mapsViewModel.maps.value?.mapTo(popupItems) { PopupItem.Map(it) }

            val popupWindow = PopupWindow(
                popupView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )

            val adapter = PopupMapAdapter(popupItems) { selectedItem ->
                when (selectedItem) {
                    is PopupItem.Menu -> findNavController().navigate(R.id.action_mapFragment_to_mainMenuFragment)
                    is PopupItem.Map -> mapsLayerManager.loadMapStyleFromFile(selectedItem.mapItem.file) { style ->
                        placemarkLayerManager.onStyleLoaded(style)
                        gasLayerManager.onStyleLoaded(style)
                    }
                }
                popupWindow.dismiss()
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
            if (isRadialMenuOpen) {
                closeRadialMenu()
            } else if (navigationViewModel.centerOnLocationState.value != CenterOnLocationState.INACTIVE) {
                // If already active, just reset to inactive (cancel)
                navigationViewModel.onCenterButtonClicked()
            } else {
                // If INACTIVE and menu is closed, pressing again does nothing
            }
        }
        fabCenterOnLocation?.setOnLongClickListener {
            isUserPanning = false
            if (!isRadialMenuOpen && navigationViewModel.centerOnLocationState.value == CenterOnLocationState.INACTIVE) {
                openRadialMenu()
            }
            true
        }

        // Radial menu item: Follow
        fabRadialFollow?.setOnClickListener {
            navigationViewModel.setCenterOnLocationStateFromMenu(CenterOnLocationState.FOLLOW)
        navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                updateCurrentLocationIndicatorAndCamera(location, true)
            }
            closeRadialMenu()
        }

        // Radial menu item: Record
        fabRadialRecord?.setOnClickListener {
            navigationViewModel.setCenterOnLocationStateFromMenu(CenterOnLocationState.RECORD)
            navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                updateCurrentLocationIndicatorAndCamera(location, true)
            }
            closeRadialMenu()
        }

        // Radial menu item: Follow + Record
        fabRadialFollowRecord?.setOnClickListener {
            navigationViewModel.setCenterOnLocationStateFromMenu(CenterOnLocationState.FOLLOW_AND_RECORD)
            navigationViewModel.lastKnownGpsLocation.value?.let { location ->
                updateCurrentLocationIndicatorAndCamera(location, true)
            }
            closeRadialMenu()
        }
        fabHideTrack?.setOnClickListener {
            trackViewModel.clearTrack()
        }

        fabToggleGasLayer?.setOnClickListener {
            placemarkViewModel.toggleGasLayerVisibility()
        }

        // Observe gas layer visibility and update button appearance
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                placemarkViewModel.isGasLayerVisible.collect { isVisible ->
                    val tint = if (isVisible) {
                        ColorStateList.valueOf("#9C27B0".toColorInt()) // purple
                    } else {
                        ColorStateList.valueOf("#757575".toColorInt()) // default grey
                    }
                    fabToggleGasLayer?.backgroundTintList = tint
                    fabToggleGasLayer?.imageTintList = ColorStateList.valueOf(Color.WHITE)
                }
            }
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

        // Enable scale bar
        val scaleBarPlugin = ScaleBarPlugin(mapView, map)
        val scaleBarOptions = ScaleBarOptions(requireContext())
        scaleBarOptions
            .setTextColor(android.R.color.black)
            .setTextSize(44f)
            .setBarHeight(4f)
            .setBorderWidth(1f)
            .setRefreshInterval(15)
            .setMarginTop(20f)
            .setMarginLeft(10f)
            .setTextBarMargin(5f)
            .setShowTextBorder(true)
            .setMetricUnit(true)
        scaleBarPlugin.create(scaleBarOptions)

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
        map.addOnCameraIdleListener {
            val zoom = map.cameraPosition.zoom
            if (navigationViewModel.zoomLevel.value != zoom) {
                navigationViewModel.setZoomLevel(zoom)
            }
        }
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
            trackLayerManager.setupTrackLayer(style)

            val lat = initialPlacemarkLat
            val lng = initialPlacemarkLng
            if (lat != null && lng != null) {
                isLocationLoaded = true
                val targetLatLng = LatLng(lat.toDouble(), lng.toDouble())
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
        }
        viewLifecycleOwner.lifecycleScope.launch {
            trackViewModel.trackPoints.collectLatest { points ->
                val state = navigationViewModel.centerOnLocationState.value
                val isTracking =
                    state == CenterOnLocationState.RECORD || state == CenterOnLocationState.FOLLOW
                fabHideTrack?.visibility =
                    if (points.isNotEmpty() && !isTracking) View.VISIBLE else View.GONE
                if (points.isNotEmpty() && !isTracking) zoomToRoute(points)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.centerOnLocationState.collectLatest { state ->
                updateCenterOnLocationFab(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            navigationViewModel.recordedPath.collectLatest { points ->
                val state = navigationViewModel.centerOnLocationState.value
                if (state == CenterOnLocationState.RECORD) {
                    trackViewModel.setTrackPoints(points)
                }
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
            state == CenterOnLocationState.FOLLOW || state == CenterOnLocationState.RECORD || state == CenterOnLocationState.FOLLOW_AND_RECORD
        if (isUserPanning && (System.currentTimeMillis() - lastUserInteractionTime > USER_INTERACTION_TIMEOUT_MS)) {
            isUserPanning = false
        }
        if (force || (shouldFollow && !isUserPanning) || !isLocationLoaded) {
            var zoom = map.cameraPosition.zoom.coerceAtLeast(13.0)
            if (!isLocationLoaded) {
                zoom = navigationViewModel.zoomLevel.value
            }
            isLocationLoaded = true
            val lat = if (location.latitude == 0.0) DEFAULT_LOCATION_LAT else location.latitude
            val lng = if (location.longitude == 0.0) DEFAULT_LOCATION_LNG else location.longitude
            val currentLatLng = LatLng(lat, lng)
            val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                .zoom(zoom)
                .padding(0.0, 0.0, 0.0, 0.0)

            if (state == CenterOnLocationState.FOLLOW || state == CenterOnLocationState.RECORD) {
                cameraBuilder.tilt(0.0)
                cameraBuilder.bearing(0.0)
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

            CenterOnLocationState.FOLLOW_AND_RECORD -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_record_voice_over)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf("#FF9800".toColorInt())
            }
        }
    }

    private fun showCustomToast(message: String, isError: Boolean) {
        val toast = Toast.makeText(requireContext(), message, Toast.LENGTH_LONG)
        if (isError) {
            @Suppress("DEPRECATION")
            toast.view?.setBackgroundColor(Color.RED)
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
        if (!showStandardFabs) {
            closeRadialMenu()
        }
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
        placemarkLayerManager.cleanup()
        gasLayerManager.cleanup()
        trackLayerManager.cleanup()
        isLocationLoaded = false
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
            when (item.itemId) {
                ID_MENU_NAVIGATE -> {
                    NavigationChooser.show(requireContext(), coords.latitude, coords.longitude) {
                        if (navigationViewModel.navigationState.value != NavigationState.IDLE) {
                            navigationViewModel.stopNavigation()
                        }
                        navigationViewModel.requestNavigationTo(coords)
                    }
                    return true
                }

                ID_MENU_ADD_PLACEMARK -> {
                    Log.d("MapContextMenu", "add placemark")
                    placemarkLayerManager.showAddPlacemarkDialog(coords)
                    return true
                }
            }
        }
        return super.onContextItemSelected(item)
    }

    private val radialItems: List<FloatingActionButton>
        get() = listOfNotNull(fabRadialFollow, fabRadialRecord, fabRadialFollowRecord)

    /** Open the radial menu with a staggered scale+fade animation. */
    private fun openRadialMenu() {
        if (isRadialMenuOpen) return
        isRadialMenuOpen = true

        // Hide gas layer button while radial menu is open
        fabToggleGasLayer?.visibility = View.GONE

        val items = radialItems
        val startDelayStep = radialMenuAnimDuration / items.size.coerceAtLeast(1)

        items.forEachIndexed { index, fab ->
            fab.visibility = View.VISIBLE
            fab.scaleX = 0f
            fab.scaleY = 0f
            fab.alpha = 0f

            fab.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(radialMenuAnimDuration)
                .setStartDelay(index * startDelayStep)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    /** Close the radial menu with a reverse animation. */
    private fun closeRadialMenu() {
        if (!isRadialMenuOpen) return

        val items = radialItems
        val startDelayStep = radialMenuAnimDuration / items.size.coerceAtLeast(1)

        items.forEachIndexed { index, fab ->
            fab.animate()
                .scaleX(0f)
                .scaleY(0f)
                .alpha(0f)
                .setDuration(radialMenuAnimDuration)
                .setStartDelay((items.size - 1 - index) * startDelayStep)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    fab.visibility = View.GONE
                }
                .start()
        }

        isRadialMenuOpen = false

        // Restore gas layer button visibility after closing
        val navState = navigationViewModel.navigationState.value
        val showStandardFabs = navState == NavigationState.IDLE
        fabToggleGasLayer?.visibility = if (showStandardFabs) View.VISIBLE else View.GONE
    }
}