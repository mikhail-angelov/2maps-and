package com.bconf.a2maps_and.service


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bconf.a2maps_and.MainActivity
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.navigation.ActiveManeuverDetails
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.routing.Maneuver
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.google.gson.Gson
import com.mapbox.geojson.Feature
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.FusedLocationProviderClient as HmsFusedLocationProviderClient
import com.huawei.hms.location.LocationCallback as HmsLocationCallback
import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import com.huawei.hms.location.LocationResult as HmsLocationResult
import com.huawei.hms.location.LocationServices as HmsLocationServices

// --- Mapbox Turf and GeoJSON Imports ---
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils // For decoding
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfConstants // For units if needed, e.g. TurfConstants.UNIT_METERS
import com.mapbox.turf.TurfMisc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

class NavigationEngineService : Service() {

        private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    // Huawei Location
    private lateinit var huaweiFusedLocationClient: HmsFusedLocationProviderClient
    private lateinit var huaweiLocationCallback: HmsLocationCallback
//

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private var originalFullRoutePath: List<LatLng> = emptyList()
    private var originalManeuvers: List<Maneuver> = emptyList()
    // Index of the last passed *vertex* on the originalFullRoutePath.
    // The current segment the user is on starts from this index.
    private var currentSnappedShapeIndex: Int = 0
    private var distanceAlongRouteToSnappedPoint: Double = 0.0 // Store distance from route start to current snapped point


    // Configuration for navigation logic
    private val offRouteThresholdMeters = 70.0  // If user is further than this from the route line
    private val arrivalThresholdMeters = 30.0   // If user is this close to the destination point
    private val rerouteDistanceThresholdMeters = 100.0 // If off-route by this much, consider rerouting
    private val maneuverCompletionThresholdMeters = 20.0 // How close to a maneuver point to consider it "passed" for advancing index

    companion object {
        const val ACTION_START_NAVIGATION = "com.bconf.a2maps_and.action.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.bconf.a2maps_and.action.STOP_NAVIGATION"
        const val ACTION_START_LOCATION_SERVICE = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP_LOCATION_SERVICE = "ACTION_STOP_LOCATION_SERVICE"
        const val ACTION_LOCATION_UPDATE = "com.bconf.a2maps_and.LOCATION_UPDATE"
        const val EXTRA_ROUTE_RESPONSE_JSON = "extra_route_response_json"

        const val EXTRA_LATITUDE = "com.bconf.a2maps_and.EXTRA_LATITUDE"
        const val EXTRA_LONGITUDE = "com.bconf.a2maps_and.EXTRA_LONGITUDE"
        const val ACCURACY = "com.bconf.a2maps_and.ACCURACY"
        const val BEARING = "com.bconf.a2maps_and.BEARING"
        const val BEARING_ACCURACY = "com.bconf.a2maps_and.BEARING_ACCURACY"

        private const val LOCATION_UPDATE_INTERVAL = 10000L // 10 seconds
        private const val FASTEST_LOCATION_INTERVAL = 5000L // 5 seconds

        private val _currentDisplayedPath = MutableStateFlow<List<LatLng>>(emptyList())
        val currentDisplayedPath: StateFlow<List<LatLng>> = _currentDisplayedPath.asStateFlow()

        private val _activeManeuverDetails = MutableStateFlow<ActiveManeuverDetails?>(null)
        val activeManeuverDetails: StateFlow<ActiveManeuverDetails?> = _activeManeuverDetails.asStateFlow()

        private val _navigationState = MutableStateFlow(NavigationState.IDLE)
        val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

        private val _lastLocation = MutableStateFlow(Location(LocationManager.GPS_PROVIDER))
        val lastLocation: StateFlow<Location> = _lastLocation.asStateFlow()
    }


    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "NavigationEngineChannel" // Changed channel ID

    override fun onCreate() {
        super.onCreate()
        Log.d("NavigationEngineService", "onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        huaweiFusedLocationClient = com.huawei.hms.location.LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        setupLocationCallbacks()

        Log.d("Location", "--- checkGMS." + checkGMS())
        Log.d("Location", "--- checkHMS." + checkHMS())
    }

    private fun checkGMS():Boolean {
        val gApi = GoogleApiAvailability.getInstance()
        val resultCode = gApi.isGooglePlayServicesAvailable(this)
        return resultCode ==
                ConnectionResult.SUCCESS
    }

    private fun checkHMS():Boolean {
        val hApi = HuaweiApiAvailability.getInstance()
        val resultCode = hApi.isHuaweiMobileServicesAvailable(this)
        return resultCode == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    private fun setupLocationCallbacks() {
        // Google Mobile Services (GMS) Location Callback
        if (checkGMS()) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.d(
                            "Location",
                            "GMS Location: ${location.latitude}, ${location.longitude}"
                        )

                        // TODO: Handle location update (e.g., save to database, send to server)
                        onNewLocationLogic(location)
                        // Send broadcast
                        val locationIntent = Intent(ACTION_LOCATION_UPDATE)
                        locationIntent.putExtra(EXTRA_LATITUDE, location.latitude)
                        locationIntent.putExtra(EXTRA_LONGITUDE, location.longitude)
                        locationIntent.putExtra(ACCURACY, location.accuracy)
                        locationIntent.putExtra(BEARING, location.bearing)
                        locationIntent.putExtra(BEARING_ACCURACY, location.bearingAccuracyDegrees)
                        LocalBroadcastManager.getInstance(this@NavigationEngineService)
                            .sendBroadcast(locationIntent)
                    }
                }
            }
        } else if (checkHMS()) {
            // Huawei Mobile Services (HMS) Location Callback
            huaweiLocationCallback = object : com.huawei.hms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.huawei.hms.location.LocationResult?) {
                    locationResult?.lastLocation?.let { location ->
                        Log.d(
                            "LocationService",
                            "HMS Location++++++: ${location.latitude}, ${location.longitude}, ${location.accuracy}, ${location.bearing}, ${location.bearingAccuracyDegrees}"
                        )
                        onNewLocationLogic(location)
                        // Send broadcast
                        val locationIntent = Intent(ACTION_LOCATION_UPDATE)
                        locationIntent.putExtra(EXTRA_LATITUDE, location.latitude)
                        locationIntent.putExtra(EXTRA_LONGITUDE, location.longitude)
                        locationIntent.putExtra(ACCURACY, location.accuracy)
                        locationIntent.putExtra(BEARING, location.bearing)
                        locationIntent.putExtra(BEARING_ACCURACY, location.bearingAccuracyDegrees)
                        LocalBroadcastManager.getInstance(this@NavigationEngineService)
                            .sendBroadcast(locationIntent)
                    }
                }
            }
        }
    }

        private fun startLocationUpdates() {
        if(checkGMS()) {
            try {
                // Check for GMS availability and start updates
                // You might want to add a more sophisticated check for GMS/HMS availability
                // GMS Location Request
                val locationRequestGMS =
                    com.google.android.gms.location.LocationRequest.create().apply {
                        interval =
                            com.bconf.a2maps_and.service.NavigationEngineService.Companion.LOCATION_UPDATE_INTERVAL
                        fastestInterval =
                            com.bconf.a2maps_and.service.NavigationEngineService.Companion.FASTEST_LOCATION_INTERVAL
                        priority =
                            com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                    }
                fusedLocationClient.requestLocationUpdates(
                    locationRequestGMS,
                    locationCallback,
                    Looper.getMainLooper()
                )
                Log.d("LocationService", "GMS location updates started.")
            } catch (e: SecurityException) {
                Log.e("LocationService", "GMS SecurityException: \${e.message}")
            } catch (e: Exception) {
                Log.e("LocationService", "Could not start GMS location updates: \${e.message}")
            }
        }

        if(checkHMS()) {
            try {
                // HMS Location Request
                val locationRequestHMS = com.huawei.hms.location.LocationRequest().apply {
                    interval =
                        com.bconf.a2maps_and.service.NavigationEngineService.Companion.LOCATION_UPDATE_INTERVAL
                    fastestInterval =
                        com.bconf.a2maps_and.service.NavigationEngineService.Companion.FASTEST_LOCATION_INTERVAL
                    priority = com.huawei.hms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                }
                // Check for HMS availability and start updates
                huaweiFusedLocationClient.requestLocationUpdates(
                    locationRequestHMS,
                    huaweiLocationCallback,
                    Looper.getMainLooper()
                )
                Log.d("LocationService", "HMS location updates started.")
            } catch (e: SecurityException) {
                Log.e("LocationService", "HMS SecurityException: \${e.message}")
            } catch (e: Exception) {
                Log.e("LocationService", "Could not start HMS location updates: \${e.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
          if(checkGMS()) {
              fusedLocationClient.removeLocationUpdates(locationCallback)
              Log.d("LocationService", "GMS location updates stopped.")
          }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop GMS location updates: \${e.message}")
        }

        try {
            if(checkHMS()) {
                huaweiFusedLocationClient.removeLocationUpdates(huaweiLocationCallback)
                Log.d("LocationService", "HMS location updates stopped.")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop HMS location updates: \${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NavigationEngineService", "onStartCommand, action: ${intent?.action}")
        val notification = buildNotification("Navigation Service Active") // Initial text
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                val routeJson = intent.getStringExtra(EXTRA_ROUTE_RESPONSE_JSON)
                if (routeJson != null) {
                    try {
                        val routeResponse = Gson().fromJson(routeJson, ValhallaRouteResponse::class.java)
                        startNavigationLogic(routeResponse)
                        startLocationUpdates() // Start location updates *after* route is processed
                    } catch (e: Exception) {
                        Log.e("NavigationEngineService", "Error parsing route for navigation", e)
                        _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                    }
                } else {
                    Log.e("NavigationEngineService", "Route JSON was null for START_NAVIGATION")
                    _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                }
            }
            ACTION_STOP_NAVIGATION -> {
                stopNavigationLogic()
            }
                        ACTION_START_LOCATION_SERVICE -> {
                startForegroundServiceWithNotification()
                startLocationUpdates()
            }
            ACTION_STOP_LOCATION_SERVICE -> {
                stopLocationUpdates()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startNavigationLogic(routeResponse: ValhallaRouteResponse) {
        serviceScope.launch {
            _navigationState.value = NavigationState.NAVIGATING
            val shape = routeResponse.trip?.legs?.firstOrNull()?.shape
            if (shape.isNullOrEmpty()) {
                Log.w("NavigationEngineService", "No shape data in response.")
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                _currentDisplayedPath.value = emptyList()
                _activeManeuverDetails.value = null
                return@launch
            }

            try {
                val decodedMapboxPoints: List<com.mapbox.geojson.Point> = PolylineUtils.decode(shape, 6)
                originalFullRoutePath = decodedMapboxPoints.map { LatLng(it.latitude(), it.longitude()) }
            } catch (e: Exception) {
                Log.e("NavigationEngineService", "Error decoding polyline: ${e.message}", e)
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                return@launch
            }

            if (originalFullRoutePath.isEmpty()) {
                Log.w("NavigationEngineService", "Decoded path is empty.")
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                return@launch
            }

            originalManeuvers = routeResponse.trip?.legs?.firstOrNull()?.maneuvers ?: emptyList()
            currentSnappedShapeIndex = 0 // Reset for new route

            _currentDisplayedPath.value = ArrayList(originalFullRoutePath)
            updateActiveManeuverDetails(currentSnappedShapeIndex, 0.0)
            updateNotificationText("Navigating...") // Update notification
            Log.d("NavigationEngineService", "Navigation started. Full path size: ${originalFullRoutePath.size}. Maneuvers: ${originalManeuvers.size}")
        }
    }

    private fun onNewLocationLogic(location: Location) {
        //update last location
        _lastLocation.value=location

        // Only process if navigating and route is valid
        if ((_navigationState.value != NavigationState.NAVIGATING && _navigationState.value != NavigationState.OFF_ROUTE) ||
            originalFullRoutePath.size < 2) {
            return
        }

        serviceScope.launch {
            val currentLocationLatLng = LatLng(location.latitude, location.longitude)

            // 1. Check for Arrival (first, to avoid processing if already there)
            originalFullRoutePath.lastOrNull()?.let { destination ->
                if (currentLocationLatLng.distanceTo(destination) < arrivalThresholdMeters) {
                    Log.i("NavigationEngineService", "User has ARRIVED at destination.")
                    _navigationState.value = NavigationState.ARRIVED
                    _currentDisplayedPath.value = emptyList()
                    _activeManeuverDetails.value = null
                    updateNotificationText("Arrived at destination.")
                    // Consider stopping location updates here or in stopNavigationLogic if ARRIVED leads to stop.
                    // stopHmsLocationUpdates()
                    stopNavigationLogic() // This will also stop updates and service
                    return@launch
                }
            }

            // 2. Snap to Route using TurfMisc.nearestPointOnLine
            val routePointsGeoJson = originalFullRoutePath.map { Point.fromLngLat(it.longitude, it.latitude) }
            val routeLineString = LineString.fromLngLats(routePointsGeoJson)
            val currentGeoJsonPoint = Point.fromLngLat(currentLocationLatLng.longitude, currentLocationLatLng.latitude)

            // TurfMisc.nearestPointOnLine returns a Feature<Point>.
            // The properties of this Feature typically include:
            // - "index": The index of the vertex on the original LineString that is an endpoint of the identified segment.
            // - "dist": The distance from the start of the LineString to the snapped point along the LineString.
            // - "location": The coordinates of the snapped point (this is the geometry of the returned Feature).
            val snappedFeature: Feature = TurfMisc.nearestPointOnLine(currentGeoJsonPoint, routePointsGeoJson) // Pass List<Point> for the line

            val snappedPointGeometry = snappedFeature.geometry() as? Point
            if (snappedPointGeometry == null) {
                Log.e("NavigationEngineService", "TurfMisc.nearestPointOnLine did not return a Point geometry.")
                return@launch
            }
            val snappedPointOnRoute = LatLng(snappedPointGeometry.latitude(), snappedPointGeometry.longitude())
            // Get distance along the line to the snapped point (from TurfMisc.nearestPointOnLine)
            // This 'dist' is usually in kilometers if the coordinates are geographic.
            val turfDistProperty = snappedFeature.properties()?.get("dist")?.asDouble
            if (turfDistProperty != null) {
                distanceAlongRouteToSnappedPoint = turfDistProperty * 1000 // Convert km to meters
            } else {
                // Fallback: calculate distance from route start to snappedPointOnRoute if 'dist' is not available
                // This is more complex, requires summing segment lengths up to currentSnappedShapeIndex + partial segment
                Log.w("NavigationEngineService", "'dist' property not found in Turf feature. Distance to maneuver might be less accurate.")
                // For now, if 'dist' is missing, remainingDistanceToManeuverMeters will be less accurate or null.
            }
            val distanceToRouteLine = currentLocationLatLng.distanceTo(snappedPointOnRoute)

            // 3. Off-Route Detection (as before)
            if (distanceToRouteLine > offRouteThresholdMeters) {
                if (_navigationState.value != NavigationState.OFF_ROUTE) {
                    Log.w("NavigationEngineService", "User is OFF-ROUTE. Distance: $distanceToRouteLine m")
                    _navigationState.value = NavigationState.OFF_ROUTE
                    updateNotificationText("Off-route.")
                }
            } else {
                if (_navigationState.value == NavigationState.OFF_ROUTE) {
                    _navigationState.value = NavigationState.NAVIGATING
                    updateNotificationText("Back on route. Navigating...")
                    Log.i("NavigationEngineService", "User is back ON-ROUTE.")
                }
            }

            // 4. Determine Progress along the route
            // The 'index' from TurfMisc.nearestPointOnLine is usually the index of the vertex
            // on the original route polyline that forms the start of the segment the user is currently on.
            val turfSnappedIndex = snappedFeature.properties()?.get("index")?.asInt ?: -1

            if (turfSnappedIndex == -1) {
                Log.e("NavigationEngineService", "TurfMisc.nearestPointOnLine returned invalid index property.")
                // Fallback: Try to find closest vertex manually if index is missing
                var closestVertexIndex = 0
                var minDist = Double.MAX_VALUE
                for (i in originalFullRoutePath.indices) {
                    val dist = originalFullRoutePath[i].distanceTo(snappedPointOnRoute)
                    if (dist < minDist) {
                        minDist = dist
                        closestVertexIndex = i
                    }
                }
                Log.w("NavigationEngineService", "Falling back to closest vertex index: $closestVertexIndex")
                currentSnappedShapeIndex = closestVertexIndex // Use this as a fallback
            } else {
                // Advance currentSnappedShapeIndex only if the new snapped index is ahead.
                if (turfSnappedIndex >= currentSnappedShapeIndex) {
                    currentSnappedShapeIndex = turfSnappedIndex
                    // Log.d("NavigationEngineService", "Advanced currentSnappedShapeIndex to: $currentSnappedShapeIndex from Turf")
                } else {
                    // Snapped to a previous segment, possibly due to GPS jitter or going in circles.
                    // Decide if we should allow moving backwards or hold the current index.
                    // For simplicity, we can prevent moving back significantly.
                    Log.w("NavigationEngineService", "Turf index $turfSnappedIndex is behind current $currentSnappedShapeIndex. Holding index.")
                }
            }


            // 5. Calculate Remaining Path
            val remainingPath = mutableListOf<LatLng>()
            if (currentSnappedShapeIndex < originalFullRoutePath.size) {
                remainingPath.add(snappedPointOnRoute) // Start with the exact snapped point
                if (currentSnappedShapeIndex + 1 < originalFullRoutePath.size) {
                    remainingPath.addAll(originalFullRoutePath.subList(currentSnappedShapeIndex + 1, originalFullRoutePath.size))
                } else if (originalFullRoutePath.isNotEmpty() && !remainingPath.contains(originalFullRoutePath.last())) {
                    // If currentSnappedShapeIndex is the second to last, and the last point isn't already the snapped point
                    if (originalFullRoutePath.last().distanceTo(snappedPointOnRoute) > 1.0) { // Avoid adding if snapped point is the last point
                        remainingPath.add(originalFullRoutePath.last())
                    }
                }
            }

            if (remainingPath.isEmpty() && originalFullRoutePath.isNotEmpty()) {
                if (currentLocationLatLng.distanceTo(originalFullRoutePath.last()) < arrivalThresholdMeters * 1.5) {
                    Log.i("NavigationEngineService", "Likely arrived (remaining path empty).")
                    _navigationState.value = NavigationState.ARRIVED
                    _currentDisplayedPath.value = emptyList()
                    _activeManeuverDetails.value = null
                    updateNotificationText("Arrived at destination.")
                    return@launch
                } else {
                    remainingPath.add(snappedPointOnRoute) // Show at least the snapped point
                }
            }
            _currentDisplayedPath.value = ArrayList(remainingPath)

            // 6. Update Current Maneuver
            updateActiveManeuverDetails(currentSnappedShapeIndex, distanceAlongRouteToSnappedPoint)

        }
    }

    private fun updateActiveManeuverDetails(
        routePointIndexOfLastPassedVertex: Int,
        distanceTravelledOnRouteMeters: Double
    ) {
        val activeManeuver = originalManeuvers.lastOrNull { maneuver ->
            (maneuver.begin_shape_index ?: 0) <= routePointIndexOfLastPassedVertex
        }

        if (activeManeuver != null) {
            val maneuverStartIndex = activeManeuver.begin_shape_index ?: 0
            var distanceToManeuverStartVertexMeters: Double? = null

            // Calculate distance from route start to this maneuver's start_shape_index
            if (maneuverStartIndex < originalFullRoutePath.size) {
                var pathLengthToManeuverStart = 0.0
                for (i in 0 until maneuverStartIndex) {
                    if (i + 1 < originalFullRoutePath.size) {
                        pathLengthToManeuverStart += originalFullRoutePath[i].distanceTo(originalFullRoutePath[i+1])
                    }
                }
                distanceToManeuverStartVertexMeters = pathLengthToManeuverStart
            }


            val remainingDistanceToManeuver: Double? = if (distanceToManeuverStartVertexMeters != null) {
                // Distance from current position (distanceTravelledOnRouteMeters) to the start of the maneuver
                (distanceToManeuverStartVertexMeters - distanceTravelledOnRouteMeters).coerceAtLeast(0.0)
            } else {
                // Fallback if we couldn't calculate distanceToManeuverStartVertexMeters accurately
                // Try direct distance to the maneuver's first point as a rough estimate
                val maneuverStartPoint = originalFullRoutePath.getOrNull(activeManeuver.begin_shape_index ?: 0)
                val currentSnappedLatLng = _currentDisplayedPath.value.firstOrNull() // Current snapped location
                if (maneuverStartPoint != null && currentSnappedLatLng != null) {
                    currentSnappedLatLng.distanceTo(maneuverStartPoint)
                } else {
                    null
                }
            }

            val newDetails = ActiveManeuverDetails(activeManeuver, remainingDistanceToManeuver)

            if (_activeManeuverDetails.value?.maneuver != activeManeuver ||
                _activeManeuverDetails.value?.remainingDistanceToManeuverMeters != remainingDistanceToManeuver) {
                _activeManeuverDetails.value = newDetails

                activeManeuver.let {
                    val distStr = if (remainingDistanceToManeuver != null) formatDistance(remainingDistanceToManeuver) else "..."
                    Log.i("NavigationEngineService", "Active Maneuver: '$distStr: ${it.instruction}' (begin_idx: ${it.begin_shape_index})")
                    updateNotificationText("$distStr: ${it.instruction}")
                }
            }
        } else {
            if (_activeManeuverDetails.value != null) {
                _activeManeuverDetails.value = null
                updateNotificationText("Navigating...") // Or "Route overview"
            }
        }
    }


    /**
     * Formats distance in meters to a readable string (e.g., "550 m", "1.2 km").
     */
    private fun formatDistance(distanceMeters: Double): String {
        return if (distanceMeters < 10.0) { // Show 1 decimal for very close distances like 9.5m
            String.format("%.1f m", distanceMeters)
        } else if (distanceMeters < 1000.0) {
            String.format("%.0f m", distanceMeters)
        } else {
            String.format("%.1f km", distanceMeters / 1000.0)
        }
    }

    private fun stopNavigationLogic() {
        serviceScope.launch {
            Log.d("NavigationEngineService", "Stopping Navigation Logic.")
            stopLocationUpdates()
            originalFullRoutePath = emptyList()
            originalManeuvers = emptyList()
            currentSnappedShapeIndex = 0
            _currentDisplayedPath.value = emptyList()
            _activeManeuverDetails.value = null
            _navigationState.value = NavigationState.IDLE
            updateNotificationText("Navigation stopped.")
            stopForeground(true)
            stopSelf()
        }
    }



    private fun updateNotificationText(text: String) {
        // Only update if the service is in a state where notifications are relevant
        // and avoid updating if it's about to be stopped/idle anyway.
        if (_navigationState.value != NavigationState.IDLE || text == "Navigation stopped.") {
            val notification = buildNotification(text) // Rebuild notification with new text
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification) // Notify with the same ID to update
            Log.d("NavigationEngineService", "Notification updated: $text")
        } else {
            Log.d("NavigationEngineService", "Skipped notification update, state is IDLE and text is not 'Navigation stopped.'")
        }
    }


    private fun startForegroundServiceWithNotification() {
        val notificationChannelId = "LOCATION_SERVICE_CHANNEL"
        val channelName = "Location Service"

        // Create a notification channel (required for Android Oreo and above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                notificationChannelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW // Or other importance
            )
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
        val notification = notificationBuilder.setOngoing(true)
//            .setSmallIcon(R.drawable.ic_your_notification_icon) // Replace with your icon
//            .setContentTitle("Location Service Running")
            .setContentText("Tracking your location...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // For Android 12 (API 31) and above, you need to specify foregroundServiceType
        // when calling startForeground if your service has a type defined in the manifest
        // and you haven't already specified it (though the manifest declaration is preferred).
        // However, the primary fix is the manifest declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }


    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java) // Opens MainActivity on tap
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("2Maps Navigation")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_navigation) // Ensure you have this drawable
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable by swipe
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Navigation Service Channel",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration for ongoing
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopLocationUpdates()
        Log.d("NavigationEngineService", "onDestroy")
    }
}

