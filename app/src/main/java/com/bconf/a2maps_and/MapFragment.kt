package com.bconf.a2maps_and

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
    private val mapsViewModel: MapsViewModel by activityViewModels()

    private var longPressedLatLng: LatLng? = null
    private var fabCenterOnLocation: FloatingActionButton? = null
    private var fabHideTrack: FloatingActionButton? = null
    private var fabToggleGasLayer: FloatingActionButton? = null
    private var fabRadialFollow: FloatingActionButton? = null
    private var fabRadialRecord: FloatingActionButton? = null
    private var fabRadialFollowRecord: FloatingActionButton? = null
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
    private var latestRecordedPath: List<LatLng> = emptyList()

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

    private fun setupLocationControls() {
        fabCenterOnLocation?.setOnClickListener {
            isUserPanning = false
            if (isRadialMenuOpen) {
                closeRadialMenu()
            } else if (locationViewModel.locationMode.value != LocationMode.INACTIVE) {
                locationViewModel.onCenterButtonClicked()
            }
        }
        fabCenterOnLocation?.setOnLongClickListener {
            isUserPanning = false
            if (!isRadialMenuOpen && locationViewModel.locationMode.value == LocationMode.INACTIVE) {
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
        fabToggleGasLayer?.visibility = View.VISIBLE
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
    }

    override fun onMapReady(mapLibreMap: MapLibreMap) {
        this.map = mapLibreMap
        setupScaleBar()
        setupMapListeners()
        setupLayerManagers()
        loadInitialMapStyle()
        observeLocation()
        observeTrackPoints()
        observeLocationMode()
        observeRecordedPath()
        startTrackStatsTimer()
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
                fabHideTrack?.visibility = if (isVisibleTrack) View.VISIBLE else View.GONE
                if (isVisibleTrack) zoomToRoute(points)
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
        mapsLayerManager.updateCurrentLocation(
            Point.fromLngLat(
                location.longitude,
                location.latitude
            )
        )

        val state = locationViewModel.locationMode.value
        if (isUserPanning && (System.currentTimeMillis() - lastUserInteractionTime > USER_INTERACTION_TIMEOUT_MS)) {
            isUserPanning = false
        }
        val lat = if (location.latitude == 0.0) DEFAULT_LOCATION_LAT else location.latitude
        val lng = if (location.longitude == 0.0) DEFAULT_LOCATION_LNG else location.longitude
        val currentLatLng = LatLng(lat, lng)
        if (force || !isLocationLoaded) {
            val zoom = when {
                !isLocationLoaded -> locationViewModel.zoomLevel.value
                force -> map.cameraPosition.zoom.coerceAtLeast(DEFAULT_FOCUS_ZOOM)
                else -> map.cameraPosition.zoom
            }
            isLocationLoaded = true
            lastFollowCameraTarget = currentLatLng
            lastFollowCameraUpdateTime = System.currentTimeMillis()
            val cameraBuilder = CameraPosition.Builder().target(currentLatLng)
                .zoom(zoom)
                .padding(0.0, 0.0, 0.0, 0.0)

            if (state.resetsCameraBearing()) {
                cameraBuilder.tilt(0.0)
                cameraBuilder.bearing(0.0)
            }

            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(cameraBuilder.build()),
                LOCATION_CAMERA_ANIMATION_MS
            )
        } else if (state.shouldFollowLocation() && !isUserPanning && shouldUpdateFollowCamera(currentLatLng)) {
            lastFollowCameraTarget = currentLatLng
            lastFollowCameraUpdateTime = System.currentTimeMillis()
            map.moveCamera(CameraUpdateFactory.newLatLng(currentLatLng))
        }
    }

    private fun shouldUpdateFollowCamera(target: LatLng): Boolean {
        val lastTarget = lastFollowCameraTarget ?: return true
        val now = System.currentTimeMillis()
        if (now - lastFollowCameraUpdateTime < FOLLOW_CAMERA_MIN_INTERVAL_MS) return false
        return distanceMeters(lastTarget, target) >= FOLLOW_CAMERA_MIN_DISTANCE_METERS
    }

    private fun distanceMeters(start: LatLng, end: LatLng): Double {
        val results = FloatArray(1)
        Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            results
        )
        return results[0].toDouble()
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

    private fun LocationMode.resetsCameraBearing(): Boolean =
        this == LocationMode.FOLLOW || this == LocationMode.RECORD

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
        isLocationLoaded = false
        if (isMapRuntimeEnabled && ::mapView.isInitialized) realMapView.onDestroy()
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
                    ExternalNavigationChooser.show(requireContext(), coords.latitude, coords.longitude)
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

        fabToggleGasLayer?.visibility = View.VISIBLE
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
