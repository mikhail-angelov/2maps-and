package com.bconf.a2maps_and

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.doOnLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.bconf.a2maps_and.navigation.*
import android.annotation.SuppressLint
import android.content.Context
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
import android.widget.PopupWindow
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.bconf.a2maps_and.external.ExternalNavigationChooser
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bconf.a2maps_and.maps.MapsLayerManager
import com.bconf.a2maps_and.maps.MapsViewModel
import com.bconf.a2maps_and.maps.PopupItem
import com.bconf.a2maps_and.maps.PopupMapAdapter
import com.bconf.a2maps_and.location.LocationMode
import com.bconf.a2maps_and.location.LocationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.bconf.a2maps_and.placemark.GasLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkLayerManager
import com.bconf.a2maps_and.placemark.PlacemarkModal
import com.bconf.a2maps_and.placemark.PlacemarksViewModel
import com.bconf.a2maps_and.track.TrackLayerManager
import com.bconf.a2maps_and.track.TrackViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MapFragment : Fragment(), MapLibreMap.OnMapLongClickListener, MapLibreMap.OnMapClickListener,
    OnMapReadyCallback {

    private lateinit var mapView: View
    private lateinit var map: MapLibreMap
    private lateinit var mapsLayerManager: MapsLayerManager
    private lateinit var placemarkLayerManager: PlacemarkLayerManager
    private lateinit var gasLayerManager: GasLayerManager
    private lateinit var trackLayerManager: TrackLayerManager
    private val locationViewModel: LocationViewModel by activityViewModels()
    private val placemarkViewModel: PlacemarksViewModel by activityViewModels()
    private val trackViewModel: TrackViewModel by activityViewModels()
    private val navigationViewModel: NavigationViewModel by activityViewModels()
    private var navigationPanel: NavigationPanel? = null
    private val routeLayerManager = RouteLayerManager { route ->
        route.estimatedDurationSeconds?.let { navigationPanel?.durationText(it) }.orEmpty()
    }
    private val precisePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        navigationViewModel.retry()
    }
    private val mapsViewModel: MapsViewModel by activityViewModels()

    private var longPressedLatLng: LatLng? = null
    private var fabCenterOnLocation: FloatingActionButton? = null
    private var fabHideTrack: FloatingActionButton? = null
    private var fabToggleGasLayer: FloatingActionButton? = null
    private var fabRadialFollow: FloatingActionButton? = null
    private var fabRadialRecord: FloatingActionButton? = null
    private var fabRadialFollowRecord: FloatingActionButton? = null
    private var fabCompass: FloatingActionButton? = null
    private var trackStatsText: TextView? = null
    private var isRadialMenuOpen = false
    private val radialMenuAnimDuration = 200L
    private var isUserPanning = false
    private var mainMenuButton: FloatingActionButton? = null
    private val args: MapFragmentArgs by navArgs()

    private var initialPlacemarkLat: Float? = null
    private var initialPlacemarkLng: Float? = null
    private var isLocationLoaded: Boolean = false
    private var lastUserInteractionTime: Long = 0
    private var lastFollowCameraTarget: LatLng? = null
    private var lastFollowCameraUpdateTime: Long = 0
    private var lastBearingLocation: LatLng? = null
    private var currentHeadingBearing: Double? = null
    private var latestRecordedPath: List<LatLng> = emptyList()
    /** Set by the compass: keep the chosen bearing instead of rotating with the course until re-centred. */
    private var headingPaused = false
    private var renderedNavigationPhase = NavigationPhase.IDLE
    private var overviewOptionCount = 0
    private var navigationLayoutActive: Boolean? = null
    private var scaleBar: ScaleBarPlugin? = null
    private val idleLayoutParams = mutableMapOf<Int, ConstraintLayout.LayoutParams>()

    companion object {
        internal var isMapRuntimeEnabled = true
        internal var layoutResId = R.layout.fragment_map
        private const val ID_MENU_NAVIGATE = 1
        private const val ID_MENU_ADD_PLACEMARK = 2
        private const val DEFAULT_LOCATION_LAT = 56.292374
        private const val DEFAULT_LOCATION_LNG = 43.985402
        private const val INITIAL_PLACEMARK_SENTINEL = 999.0F
        private const val DEFAULT_FOCUS_ZOOM = 13.0
        private const val CAMERA_ANIMATION_MS = 1000
        private const val LOCATION_CAMERA_ANIMATION_MS = 600
        private const val ROUTE_CAMERA_PADDING = 150
        private const val USER_INTERACTION_TIMEOUT_MS = 60 * 1000
        private const val FOLLOW_CAMERA_MIN_INTERVAL_MS = 2_000L
        private const val FOLLOW_CAMERA_MIN_DISTANCE_METERS = 25.0
        private const val HEADING_MIN_DISTANCE_METERS = 8.0
        private const val HEADING_MIN_CHANGE_DEGREES = 12.0
        private const val HEADING_GPS_MIN_CHANGE_DEGREES = 4.0
        private const val HEADING_MIN_SPEED_MPS = 3.0
        private const val HEADING_STOPPED_SPEED_MPS = 1.0
        private const val HEADING_MAX_ACCURACY_METERS = 50.0
        private const val HEADING_MAX_BEARING_ACCURACY_DEGREES = 30.0
        private const val NAVIGATION_ZOOM = 16.0
        private const val NAVIGATION_CAMERA_MS = 900
        private const val NAVIGATION_CAMERA_MIN_INTERVAL_MS = 900L
        // Marker position within the free map area between the cards; more road ahead than behind.
        private const val NAVIGATION_MARKER_FRACTION = 0.67
        private const val OPTION_TOUCH_SLOP_DP = 24
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeMapRuntime(requireContext())

        initialPlacemarkLat = args.placemarkLat.takeUnless { it == INITIAL_PLACEMARK_SENTINEL }
        initialPlacemarkLng = args.placemarkLng.takeUnless { it == INITIAL_PLACEMARK_SENTINEL }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(layoutResId, container, false)
        bindViews(view)
        if (isMapRuntimeEnabled) {
            realMapView.onCreate(savedInstanceState)
            realMapView.getMapAsync(this)
        }
        setupMainMenuButton()
        setupCompass()
        setupLocationControls()
        setupTrackControls()
        setupGasLayerControls()

        return view
    }

    private fun initializeMapRuntime(context: Context) {
        if (isMapRuntimeEnabled) {
            MapLibre.getInstance(context)
        }
    }

    private fun bindViews(view: View) {
        mapView = view.findViewById(R.id.mapViewInFragment)
        fabCenterOnLocation = view.findViewById(R.id.fabCenterOnLocationInFragment)
        fabHideTrack = view.findViewById(R.id.fabHideTrack)
        fabToggleGasLayer = view.findViewById(R.id.fabToggleGasLayer)
        fabRadialFollow = view.findViewById(R.id.fabRadialFollow)
        fabRadialRecord = view.findViewById(R.id.fabRadialRecord)
        fabRadialFollowRecord = view.findViewById(R.id.fabRadialFollowRecord)
        fabCompass = view.findViewById(R.id.fabCompass)
        mainMenuButton = view.findViewById(R.id.mainMenuButton)
        trackStatsText = view.findViewById(R.id.trackStatsText)
    }

    private fun setupMainMenuButton() {
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
                        trackLayerManager.setupTrackLayer(style)
                        routeLayerManager.onStyleLoaded(style)
                        locationViewModel.lastKnownGpsLocation.value?.let { updateCurrentLocationIndicatorAndCamera(it) }
                    }
                }
                popupWindow.dismiss()
            }

            recyclerView.layoutManager = LinearLayoutManager(context)
            recyclerView.adapter = adapter

            // Show the popup window anchored to the button
            popupWindow.showAsDropDown(anchorView)
        }
    }

    private fun setupCompass() {
        fabCompass?.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            val cameraPosition = CameraPosition.Builder(map.cameraPosition)
                .bearing(0.0)
                .build()
            headingPaused = true
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraPosition),
                LOCATION_CAMERA_ANIMATION_MS
            )
            updateCompassRotation(0.0)
        }
    }

    private fun setupLocationControls() {
        fabCenterOnLocation?.setOnClickListener {
            isUserPanning = false
            headingPaused = false
            if (isRadialMenuOpen) {
                closeRadialMenu()
            } else if (locationViewModel.locationMode.value != LocationMode.INACTIVE) {
                locationViewModel.onCenterButtonClicked()
            }
        }
        fabCenterOnLocation?.setOnLongClickListener {
            isUserPanning = false
            if (!isRadialMenuOpen && locationViewModel.locationMode.value == LocationMode.INACTIVE &&
                navigationViewModel.state.value.phase == NavigationPhase.IDLE) {
                openRadialMenu()
            }
            true
        }

        fabRadialFollow?.setOnClickListener { selectLocationMode(LocationMode.FOLLOW) }
        fabRadialRecord?.setOnClickListener { selectLocationMode(LocationMode.RECORD) }
        fabRadialFollowRecord?.setOnClickListener { selectLocationMode(LocationMode.FOLLOW_AND_RECORD) }
    }

    private fun selectLocationMode(mode: LocationMode) {
        locationViewModel.setLocationModeFromMenu(mode)
        locationViewModel.lastKnownGpsLocation.value?.let { location ->
            updateCurrentLocationIndicatorAndCamera(location, true)
        }
        closeRadialMenu()
    }

    private fun setupTrackControls() {
        fabHideTrack?.setOnClickListener {
            trackViewModel.clearTrack()
        }
    }

    private fun setupGasLayerControls() {
        updateQuickActions()
        fabToggleGasLayer?.setOnClickListener {
            placemarkViewModel.toggleGasLayerVisibility()
        }

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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val mapsDir = File(requireContext().filesDir, "maps")
        if (!mapsDir.exists()) {
            mapsDir.mkdirs()
        }
        mapsViewModel.loadMaps(mapsDir)
        setupNavigationPanel(view)
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.map = mapLibreMap
        // The app's own compass button replaces MapLibre's, which would sit under the top-right controls.
        map.uiSettings.isCompassEnabled = false
        setupScaleBar()
        setupMapListeners()
        setupLayerManagers()
        loadInitialMapStyle()
        observeLocation()
        observeTrackPoints()
        observeLocationMode()
        observeRecordedPath()
        startTrackStatsTimer()
        updateNavigationCamera(navigationViewModel.state.value)
    }


    private fun requestPreciseLocation() {
        precisePermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    private fun openRoadData() { findNavController().navigate(R.id.roadDataFragment) }

    private fun setupNavigationPanel(root: View) {
        val panel = NavigationPanel(root,
            onStop = { navigationViewModel.stop() },
            onRetry = { navigationViewModel.retry() },
            onSetup = {
                val state = navigationViewModel.state.value
                when {
                    state.phase == NavigationPhase.ERROR -> openRoadData()
                    !navigationViewModel.hasPrecisePermission() -> requestPreciseLocation()
                    else -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            },
            onSelect = { navigationViewModel.select(it) },
            onGo = { navigationViewModel.go() },
            onRecenter = { recenterOnCar() })
        navigationPanel = panel
        launchWhenStarted {
            navigationViewModel.state.collect { state ->
                panel.render(state, navigationViewModel.hasPrecisePermission())
                routeLayerManager.render(state)
                if (::mapsLayerManager.isInitialized) {
                    locationViewModel.lastKnownGpsLocation.value?.let { updateCurrentLocationMarker(it) }
                }
                applyNavigationLayout(state.phase != NavigationPhase.IDLE)
                updateNavigationCamera(state)
            }
        }
    }

    /** Resumes following the car after a map gesture; while navigating the bottom panel is the re-centre control. */
    private fun recenterOnCar() {
        if (!navigationViewModel.state.value.needsGps) return
        isUserPanning = false
        headingPaused = false
        locationViewModel.lastKnownGpsLocation.value?.let { updateCurrentLocationIndicatorAndCamera(it, true) }
    }

    /** Moves map buttons out of the cards' way while navigating and hides actions not needed for driving. */
    private fun applyNavigationLayout(active: Boolean) {
        if (navigationLayoutActive == active) return
        navigationLayoutActive = active
        val root = view ?: return
        val density = resources.displayMetrics.density
        fun place(id: Int, change: ConstraintLayout.LayoutParams.() -> Unit) {
            val target = root.findViewById<View>(id) ?: return
            val idle = idleLayoutParams.getOrPut(id) { ConstraintLayout.LayoutParams(target.layoutParams as ConstraintLayout.LayoutParams) }
            target.layoutParams = ConstraintLayout.LayoutParams(idle).apply { if (active) change() }
        }
        place(R.id.fabCompass) {
            startToStart = ConstraintLayout.LayoutParams.UNSET
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.UNSET
            topToBottom = R.id.mainMenuButton
            marginEnd = (24 * density).toInt()
            topMargin = (12 * density).toInt()
        }
        place(R.id.trackStatsText) {
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            bottomToTop = R.id.navigationPanel
        }
        if (active) closeRadialMenu()
        // The scale bar would show under the maneuver card; guidance needs only the cards and the map.
        scaleBar?.isEnabled = !active
        updateQuickActions()
    }

    private fun updateQuickActions() {
        val navigating = navigationViewModel.state.value.phase != NavigationPhase.IDLE
        // Browsing chrome competes with the guidance cards; invisible keeps the compass anchored below the menu.
        mainMenuButton?.isInvisible = navigating
        fabCenterOnLocation?.isVisible = !navigating
        fabToggleGasLayer?.isVisible = !navigating && !isRadialMenuOpen
        fabHideTrack?.isVisible = !navigating && trackViewModel.trackPoints.value.isNotEmpty() &&
            !locationViewModel.locationMode.value.isRecording()
    }

    private fun updateNavigationCamera(state: NavigationState) {
        // The phase counts as shown only once the map can move; onMapReady replays the current state.
        if (!::map.isInitialized) return
        val previous = renderedNavigationPhase
        renderedNavigationPhase = state.phase
        when {
            state.phase == NavigationPhase.SELECTING && (previous != NavigationPhase.SELECTING || state.options.size != overviewOptionCount) -> {
                if (previous != NavigationPhase.SELECTING) isUserPanning = false
                overviewOptionCount = state.options.size
                if (!isUserPanning) showRouteOverview(state.options)
            }
            state.phase == NavigationPhase.ACTIVE && previous == NavigationPhase.SELECTING -> {
                isUserPanning = false
                headingPaused = false
                locationViewModel.lastKnownGpsLocation.value?.let { updateCurrentLocationIndicatorAndCamera(it, true) }
            }
            state.phase == NavigationPhase.IDLE && previous != NavigationPhase.IDLE ->
                map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
        }
        if (state.phase != NavigationPhase.SELECTING) overviewOptionCount = 0
    }

    /** Fits every offered route above the bottom panel. */
    private fun showRouteOverview(options: List<Route>) {
        val panel = navigationPanel ?: return
        val points = options.flatMap { it.points }.map { LatLng(it.latitude, it.longitude) }
        if (points.size < 2) return
        panel.bottomCard.doOnLayout {
            // Layout may complete after Go, Stop or a new set of options; only the still-shown selection is fitted.
            val current = navigationViewModel.state.value
            if (!::map.isInitialized || isUserPanning || current.phase != NavigationPhase.SELECTING || current.options != options) return@doOnLayout
            val margin = (24 * resources.displayMetrics.density).toInt()
            val top = (mainMenuButton?.bottom ?: 0) + margin
            val bottom = mapView.height - panel.bottomCard.top + margin
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(),
                margin, top, margin, bottom.coerceAtLeast(margin)), CAMERA_ANIMATION_MS)
        }
    }

    /** Camera padding that puts the followed position at about 2/3 of the free area between the cards. */
    private fun navigationCameraPadding(): DoubleArray {
        val panel = navigationPanel ?: return DoubleArray(4)
        val height = mapView.height.toDouble()
        if (height <= 0) return DoubleArray(4)
        val top = if (panel.maneuverCard.isVisible) panel.maneuverCard.bottom.toDouble() else 0.0
        val bottom = if (panel.bottomCard.isVisible && panel.bottomCard.height > 0) panel.bottomCard.top.toDouble() else height
        val markerY = top + NAVIGATION_MARKER_FRACTION * (bottom - top)
        // The camera centre lies midway between the paddings, so solve the top padding for markerY.
        return doubleArrayOf(0.0, (2 * markerY - bottom).coerceAtLeast(0.0), 0.0, height - bottom)
    }

    private fun setupScaleBar() {
        val scaleBarPlugin = ScaleBarPlugin(realMapView, map)
        val scaleBarOptions = ScaleBarOptions(requireContext())
        scaleBarOptions
            .setTextColor(android.R.color.black)
            .setTextSize(32f)
            .setBarHeight(4f)
            .setBorderWidth(1f)
            .setRefreshInterval(15)
            .setMarginTop(20f)
            .setMarginLeft(10f)
            .setTextBarMargin(5f)
            .setShowTextBorder(true)
            .setMetricUnit(true)
        scaleBarPlugin.create(scaleBarOptions)
        scaleBar = scaleBarPlugin
        scaleBarPlugin.isEnabled = navigationLayoutActive != true
    }

    private val realMapView: MapView
        get() = mapView as MapView

    private fun setupMapListeners() {
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
            if (locationViewModel.zoomLevel.value != zoom) {
                locationViewModel.setZoomLevel(zoom)
            }
            updateCompassRotation(map.cameraPosition.bearing)
        }
        mapView.let { registerForContextMenu(it) }
    }

    private fun setupLayerManagers() {
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
    }

    private fun loadInitialMapStyle() {
        mapsLayerManager.loadInitialMapStyle({ style ->
            placemarkLayerManager.onStyleLoaded(style)
            gasLayerManager.onStyleLoaded(style)
            trackLayerManager.setupTrackLayer(style)
            routeLayerManager.onStyleLoaded(style)
            focusInitialPlacemark()
        })
    }

    private fun focusInitialPlacemark() {
        val lat = initialPlacemarkLat
        val lng = initialPlacemarkLng
        if (lat == null || lng == null) return
        isLocationLoaded = true
        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(lat.toDouble(), lng.toDouble()))
            .zoom(DEFAULT_FOCUS_ZOOM)
            .build()
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition),
            CAMERA_ANIMATION_MS
        )
    }

    private fun observeLocation() {
        launchWhenStarted {
            locationViewModel.lastKnownGpsLocation.collectLatest { location ->
                if (location != null) {
                    updateCurrentLocationIndicatorAndCamera(location)
                }
            }
        }
    }

    private fun observeTrackPoints() {
        launchWhenStarted {
            trackViewModel.trackPoints.collectLatest { points ->
                val isVisibleTrack = points.isNotEmpty() &&
                        !locationViewModel.locationMode.value.isRecording()
                updateQuickActions()
                if (isVisibleTrack && navigationViewModel.state.value.phase == NavigationPhase.IDLE) zoomToRoute(points)
            }
        }
    }

    private fun observeLocationMode() {
        launchWhenStarted {
            locationViewModel.locationMode.collectLatest { state ->
                updateCenterOnLocationFab(state)
                updateTrackStatsVisibility(state)
            }
        }
    }

    private fun observeRecordedPath() {
        launchWhenStarted {
            locationViewModel.recordedPath.collectLatest { points ->
                latestRecordedPath = points
                if (locationViewModel.locationMode.value.isRecording()) {
                    trackViewModel.setTrackPoints(points)
                }
                updateTrackStats()
            }
        }
    }

    private fun startTrackStatsTimer() {
        launchWhenStarted {
            while (true) {
                updateTrackStats()
                delay(1000)
            }
        }
    }

    private fun launchWhenStarted(block: suspend () -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                block()
            }
        }
    }

    private fun zoomToRoute(points: List<LatLng>) {
        if (points.isEmpty()) return
        val latLngBounds = LatLngBounds.Builder()
            .includes(points)
            .build()

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(latLngBounds, ROUTE_CAMERA_PADDING),
            CAMERA_ANIMATION_MS
        )
    }

    private fun updateCurrentLocationIndicatorAndCamera(
        location: Location,
        force: Boolean = false
    ) {
        updateHeading(location)
        updateCurrentLocationMarker(location)

        val state = locationViewModel.locationMode.value
        val navigation = navigationViewModel.state.value
        val driving = navigation.needsGps
        // While navigating, a gesture pauses following until the bottom panel is tapped; there is no timer.
        if (isUserPanning && navigation.phase == NavigationPhase.IDLE &&
            System.currentTimeMillis() - lastUserInteractionTime > USER_INTERACTION_TIMEOUT_MS) {
            isUserPanning = false
        }
        // The camera follows the arrow, which sits on the route while guiding.
        val shown = navigation.snappedPosition
        val lat = shown?.latitude ?: if (location.latitude == 0.0) DEFAULT_LOCATION_LAT else location.latitude
        val lng = shown?.longitude ?: if (location.longitude == 0.0) DEFAULT_LOCATION_LNG else location.longitude
        val currentLatLng = LatLng(lat, lng)
        if (force || !isLocationLoaded) {
            val zoom = when {
                !isLocationLoaded -> locationViewModel.zoomLevel.value
                force && driving -> map.cameraPosition.zoom.coerceAtLeast(NAVIGATION_ZOOM)
                force -> map.cameraPosition.zoom.coerceAtLeast(DEFAULT_FOCUS_ZOOM)
                else -> map.cameraPosition.zoom
            }
            isLocationLoaded = true
            lastFollowCameraTarget = currentLatLng
            lastFollowCameraUpdateTime = System.currentTimeMillis()
            val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                .zoom(zoom)
                .bearing(followBearing())
                .padding(if (driving) navigationCameraPadding() else DoubleArray(4))

            if (!state.shouldFollowLocation()) {
                cameraBuilder.tilt(0.0)
            }

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraBuilder.build()),
                LOCATION_CAMERA_ANIMATION_MS
            )
        } else if ((state.shouldFollowLocation() || driving) && navigation.phase != NavigationPhase.SELECTING &&
            !isUserPanning && shouldUpdateFollowCamera(currentLatLng, driving)) {
            lastFollowCameraTarget = currentLatLng
            lastFollowCameraUpdateTime = System.currentTimeMillis()
            val cameraPosition = CameraPosition.Builder(map.cameraPosition)
                .target(currentLatLng)
                .bearing(followBearing())
            if (driving) {
                cameraPosition.padding(navigationCameraPadding())
                map.easeCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()), NAVIGATION_CAMERA_MS)
            } else {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition.build()))
            }
        }
    }

    private fun updateCurrentLocationMarker(location: Location) {
        val navigation = navigationViewModel.state.value
        val guiding = navigation.phase == NavigationPhase.ACTIVE ||
            (navigation.phase == NavigationPhase.ERROR && navigation.route != null)
        val shown = navigation.snappedPosition
        mapsLayerManager.updateCurrentLocation(
            Point.fromLngLat(shown?.longitude ?: location.longitude, shown?.latitude ?: location.latitude),
            currentHeadingBearing.takeIf { guiding }
        )
    }

    private fun followBearing(): Double =
        if (headingPaused) map.cameraPosition.bearing else currentHeadingBearing ?: map.cameraPosition.bearing

    /**
     * Single course source for the camera and the position arrow. A fresh GPS bearing at driving speed wins;
     * otherwise the direction between accurate positions far enough apart. Stops and poor fixes keep the last course.
     */
    private fun updateHeading(location: Location) {
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.MAX_VALUE
        if (accuracy > HEADING_MAX_ACCURACY_METERS) return
        val point = LatLng(location.latitude, location.longitude)
        val stopped = location.hasSpeed() && location.speed < HEADING_STOPPED_SPEED_MPS
        val gpsBearing = location.takeIf {
            it.hasBearing() && it.hasSpeed() && it.speed > HEADING_MIN_SPEED_MPS &&
                (!it.hasBearingAccuracy() || it.bearingAccuracyDegrees <= HEADING_MAX_BEARING_ACCURACY_DEGREES)
        }?.bearing?.toDouble()
        val nextBearing: Double
        val minChange: Double
        if (gpsBearing != null) {
            nextBearing = normalizeBearing(gpsBearing)
            minChange = HEADING_GPS_MIN_CHANGE_DEGREES
        } else {
            val lastLocation = lastBearingLocation
            if (lastLocation == null || stopped) {
                if (lastLocation == null) lastBearingLocation = point
                return
            }
            val movement = distanceAndBearing(lastLocation, point)
            if (movement.distanceMeters < maxOf(HEADING_MIN_DISTANCE_METERS, 2 * accuracy)) return
            nextBearing = normalizeBearing(movement.bearingDegrees)
            minChange = HEADING_MIN_CHANGE_DEGREES
        }
        lastBearingLocation = point
        val currentBearing = currentHeadingBearing
        if (currentBearing == null || bearingDeltaDegrees(currentBearing, nextBearing) >= minChange) {
            currentHeadingBearing = nextBearing
        }
    }

    private fun shouldUpdateFollowCamera(target: LatLng, driving: Boolean): Boolean {
        val lastTarget = lastFollowCameraTarget ?: return true
        val now = System.currentTimeMillis()
        if (driving) return now - lastFollowCameraUpdateTime >= NAVIGATION_CAMERA_MIN_INTERVAL_MS
        if (now - lastFollowCameraUpdateTime < FOLLOW_CAMERA_MIN_INTERVAL_MS) return false
        return distanceMeters(lastTarget, target) >= FOLLOW_CAMERA_MIN_DISTANCE_METERS
    }

    private fun distanceMeters(start: LatLng, end: LatLng): Double {
        return distanceAndBearing(start, end).distanceMeters
    }

    private fun distanceAndBearing(start: LatLng, end: LatLng): Movement {
        val bearingResults = FloatArray(2)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            bearingResults
        )
        return Movement(
            distanceMeters = bearingResults[0].toDouble(),
            bearingDegrees = bearingResults[1].toDouble()
        )
    }

    private fun normalizeBearing(bearing: Double): Double {
        val normalized = bearing % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }

    private fun bearingDeltaDegrees(first: Double, second: Double): Double {
        val delta = kotlin.math.abs(normalizeBearing(first) - normalizeBearing(second))
        return kotlin.math.min(delta, 360.0 - delta)
    }

    private fun updateCompassRotation(mapBearing: Double) {
        fabCompass?.rotation = (-mapBearing).toFloat()
    }

    private fun updateCenterOnLocationFab(state: LocationMode) {
        when (state) {
            LocationMode.INACTIVE -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_my_location)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            }

            LocationMode.FOLLOW -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_navigation)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
            }

            LocationMode.RECORD -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_record)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf(Color.RED)
            }

            LocationMode.FOLLOW_AND_RECORD -> {
                fabCenterOnLocation?.setImageResource(R.drawable.ic_record_voice_over)
                fabCenterOnLocation?.backgroundTintList = ColorStateList.valueOf("#FF9800".toColorInt())
            }
        }
    }

    private fun updateTrackStatsVisibility(state: LocationMode) {
        trackStatsText?.visibility = if (state.isRecording()) View.VISIBLE else View.GONE
        if (state.isRecording()) {
            updateTrackStats()
        }
    }

    private fun updateTrackStats() {
        if (trackStatsText?.visibility != View.VISIBLE) return
        trackStatsText?.text = MapTrackStats.format(
            points = latestRecordedPath,
            startedAtMillis = locationViewModel.recordingStartedAtMillis.value
        )
    }

    private fun LocationMode.isRecording(): Boolean =
        this == LocationMode.RECORD || this == LocationMode.FOLLOW_AND_RECORD

    private fun LocationMode.shouldFollowLocation(): Boolean =
        this == LocationMode.FOLLOW || isRecording()

    private data class Movement(
        val distanceMeters: Double,
        val bearingDegrees: Double
    )

    override fun onStart() {
        super.onStart()
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onLowMemory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (::map.isInitialized) {
            map.removeOnMapLongClickListener(this)
            map.removeOnMapClickListener(this)
        }
        if (::placemarkLayerManager.isInitialized) placemarkLayerManager.cleanup()
        if (::gasLayerManager.isInitialized) gasLayerManager.cleanup()
        if (::trackLayerManager.isInitialized) trackLayerManager.cleanup()
        routeLayerManager.cleanup()
        navigationPanel = null
        navigationLayoutActive = null
        scaleBar = null
        idleLayoutParams.clear()
        renderedNavigationPhase = NavigationPhase.IDLE
        isLocationLoaded = false
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onDestroy()
    }

    override fun onMapClick(point: LatLng): Boolean {
        if (navigationViewModel.state.value.phase == NavigationPhase.SELECTING) {
            val slop = OPTION_TOUCH_SLOP_DP * resources.displayMetrics.density
            routeLayerManager.optionAt(map, map.projection.toScreenLocation(point), slop)?.let {
                navigationViewModel.select(it)
                return true
            }
        }
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
                    ExternalNavigationChooser.show(requireContext(), coords.latitude, coords.longitude) {
                        navigationViewModel.start(GeoPoint(coords.latitude, coords.longitude))
                        if (!navigationViewModel.hasPrecisePermission()) requestPreciseLocation()
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
        updateQuickActions()

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

        updateQuickActions()
    }
}

internal object MapTrackStats {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun format(
        points: List<LatLng>,
        startedAtMillis: Long?,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val distance = formatDistance(calculateDistanceMeters(points))
        val elapsed = formatElapsedTime(startedAtMillis, nowMillis)
        return "$distance | $elapsed"
    }

    fun calculateDistanceMeters(points: List<LatLng>): Double {
        if (points.size < 2) return 0.0
        return points.zipWithNext().sumOf { (start, end) ->
            calculateDistanceMeters(start, end)
        }
    }

    private fun calculateDistanceMeters(start: LatLng, end: LatLng): Double {
        val startLat = Math.toRadians(start.latitude)
        val endLat = Math.toRadians(end.latitude)
        val deltaLat = endLat - startLat
        val deltaLng = Math.toRadians(end.longitude - start.longitude)
        val a = sin(deltaLat / 2).pow(2.0) +
                cos(startLat) * cos(endLat) * sin(deltaLng / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    private fun formatDistance(distanceMeters: Double): String {
        return if (distanceMeters >= 1000.0) {
            String.format(Locale.US, "%.2f km", distanceMeters / 1000.0)
        } else {
            String.format(Locale.US, "%.0f m", distanceMeters)
        }
    }

    private fun formatElapsedTime(startedAtMillis: Long?, nowMillis: Long): String {
        val elapsedSeconds = startedAtMillis
            ?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) }
            ?: 0
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
