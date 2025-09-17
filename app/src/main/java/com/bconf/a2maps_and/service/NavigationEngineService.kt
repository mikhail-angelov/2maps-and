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
import com.bconf.a2maps_and.MainActivity
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.navigation.ActiveManeuverDetails
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.repository.RouteRepository
import com.bconf.a2maps_and.routing.Maneuver
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.google.gson.Gson
import com.mapbox.geojson.Feature
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.FusedLocationProviderClient as HmsFusedLocationProviderClient
import com.huawei.hms.location.LocationCallback as HmsLocationCallback
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMisc
import com.mapbox.turf.TurfMeasurement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private val routeRepository = RouteRepository()
    private lateinit var gpxLogger: GpxLogger

    private var originalFullRoutePath: List<LatLng> = emptyList()
    private var originalManeuvers: List<Maneuver> = emptyList()
    private var currentSnappedShapeIndex: Int = 0
    private var distanceAlongRouteToSnappedPoint: Double = 0.0

    private val offRouteThresholdMeters = 70.0
    private val arrivalThresholdMeters = 30.0
    private val rerouteDistanceThresholdMeters = 100.0
    private val maneuverCompletionThresholdMeters = 20.0
    private var rerouteCount = 5

    companion object {
        const val ACTION_START_NAVIGATION = "com.bconf.a2maps_and.action.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.bconf.a2maps_and.action.STOP_NAVIGATION"
        const val ACTION_START_LOCATION_SERVICE = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP_LOCATION_SERVICE = "ACTION_STOP_LOCATION_SERVICE"
        const val EXTRA_ROUTE_RESPONSE_JSON = "extra_route_response_json"

        private const val LOCATION_UPDATE_INTERVAL = 10000L
        private const val FASTEST_LOCATION_INTERVAL = 5000L

        private val _currentDisplayedPath = MutableStateFlow<List<LatLng>>(emptyList())
        val currentDisplayedPath: StateFlow<List<LatLng>> = _currentDisplayedPath.asStateFlow()

        private val _activeManeuverDetails = MutableStateFlow<ActiveManeuverDetails?>(null)
        val activeManeuverDetails: StateFlow<ActiveManeuverDetails?> = _activeManeuverDetails.asStateFlow()

        private val _navigationState = MutableStateFlow(NavigationState.IDLE)
        val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()

        private val _lastLocation = MutableStateFlow(Location(LocationManager.GPS_PROVIDER))
        val lastLocation: StateFlow<Location> = _lastLocation.asStateFlow()

        private val _remainingDistanceInMeters = MutableStateFlow(0.0)
        val remainingDistanceInMeters: StateFlow<Double> = _remainingDistanceInMeters.asStateFlow()
    }

    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "NavigationEngineChannel"

    override fun onCreate() {
        super.onCreate()
        Log.d("NavigationEngineService", "onCreate")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        huaweiFusedLocationClient = com.huawei.hms.location.LocationServices.getFusedLocationProviderClient(this)
        gpxLogger = GpxLogger(this)
        createNotificationChannel()
        setupLocationCallbacks()

        Log.d("Location", "--- checkGMS." + checkGMS())
        Log.d("Location", "--- checkHMS." + checkHMS())
    }

    private fun checkGMS(): Boolean {
        val gApi = GoogleApiAvailability.getInstance()
        val resultCode = gApi.isGooglePlayServicesAvailable(this)
        return resultCode == ConnectionResult.SUCCESS
    }

    private fun checkHMS(): Boolean {
        val hApi = HuaweiApiAvailability.getInstance()
        val resultCode = hApi.isHuaweiMobileServicesAvailable(this)
        return resultCode == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    private fun setupLocationCallbacks() {
        if (checkGMS()) {
            locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.d("NavigationEngineService", "GMS Location: ${location.latitude}, ${location.longitude}, ${location.accuracy}")
                        onNewLocationLogic(location)
                    }
                }
            }
        } else if (checkHMS()) {
            huaweiLocationCallback = object : com.huawei.hms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.huawei.hms.location.LocationResult?) {
                    locationResult?.lastLocation?.let { location ->
                        Log.d("NavigationEngineService", "HMS Location: ${location.latitude}, ${location.longitude}, ${location.accuracy}, ${location.bearing}, ${location.bearingAccuracyDegrees}")
                        onNewLocationLogic(location)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (checkGMS()) {
            try {
                val locationRequestGMS = com.google.android.gms.location.LocationRequest.create().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
                    priority = com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                }
                fusedLocationClient.requestLocationUpdates(locationRequestGMS, locationCallback, Looper.getMainLooper())
                Log.d("LocationService", "GMS location updates started.")
            } catch (e: SecurityException) {
                Log.e("LocationService", "GMS SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e("LocationService", "Could not start GMS location updates: ${e.message}")
            }
        }

        if (checkHMS()) {
            try {
                val locationRequestHMS = com.huawei.hms.location.LocationRequest().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
                    priority = com.huawei.hms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
                }
                huaweiFusedLocationClient.requestLocationUpdates(locationRequestHMS, huaweiLocationCallback, Looper.getMainLooper())
                Log.d("LocationService", "HMS location updates started.")
            } catch (e: SecurityException) {
                Log.e("LocationService", "HMS SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e("LocationService", "Could not start HMS location updates: ${e.message}")
            }
        }
    }

    private fun stopLocationUpdates() {
        try {
            if (checkGMS()) {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                Log.d("LocationService", "GMS location updates stopped.")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop GMS location updates: ${e.message}")
        }

        try {
            if (checkHMS()) {
                huaweiFusedLocationClient.removeLocationUpdates(huaweiLocationCallback)
                Log.d("LocationService", "HMS location updates stopped.")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop HMS location updates: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NavigationEngineService", "onStartCommand, action: ${intent?.action}")
        val notification = buildNotification("Navigation Service Active")
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                rerouteCount = 5
                val routeJson = intent.getStringExtra(EXTRA_ROUTE_RESPONSE_JSON)
                if (routeJson != null) {
                    try {
                        val routeResponse = Gson().fromJson(routeJson, ValhallaRouteResponse::class.java)
                        startNavigationLogic(routeResponse)
                        startLocationUpdates()
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
            gpxLogger.startGpxLogging()
            _navigationState.value = NavigationState.NAVIGATING
            val shape = routeResponse.trip?.legs?.firstOrNull()?.shape
            if (shape.isNullOrEmpty()) {
                Log.w("NavigationEngineService", "No shape data in response.")
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                _currentDisplayedPath.value = emptyList()
                _activeManeuverDetails.value = null
                _remainingDistanceInMeters.value = 0.0
                return@launch
            }

            try {
                val decodedMapboxPoints: List<com.mapbox.geojson.Point> = PolylineUtils.decode(shape, 6)
                originalFullRoutePath = decodedMapboxPoints.map { LatLng(it.latitude(), it.longitude()) }

                val fullRouteLineString = LineString.fromLngLats(decodedMapboxPoints)
                _remainingDistanceInMeters.value = TurfMeasurement.length(fullRouteLineString, TurfConstants.UNIT_METERS)
            } catch (e: Exception) {
                Log.e("NavigationEngineService", "Error decoding polyline: ${e.message}", e)
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                return@launch
            }

            if (originalFullRoutePath.isEmpty()) {
                Log.w("NavigationEngineService", "Decoded path is empty.")
                _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED
                _remainingDistanceInMeters.value = 0.0
                return@launch
            }

            originalManeuvers = routeResponse.trip?.legs?.firstOrNull()?.maneuvers ?: emptyList()
            currentSnappedShapeIndex = 0

            _currentDisplayedPath.value = ArrayList(originalFullRoutePath)
            updateActiveManeuverDetails(currentSnappedShapeIndex, 0.0)
            updateNotificationText("Navigating...")
            Log.d("NavigationEngineService", "Navigation started. Full path size: ${originalFullRoutePath.size}. Maneuvers: ${originalManeuvers.size}")
        }
    }

    private fun attemptReroute(currentLocation: Location, destination: LatLng) {
        rerouteCount--
        serviceScope.launch { // Launch a coroutine
            Log.d("NavEngineService", "Attempting reroute ($rerouteCount) from $currentLocation to $destination")
            val result = routeRepository.getRoute(
                ValhallaLocation(currentLocation.latitude, currentLocation.longitude),
                ValhallaLocation(destination.latitude, destination.longitude)
            )

            result.fold(
                onSuccess = { newRouteResponse ->
                    if (newRouteResponse.trip?.legs?.isNotEmpty() == true) {
                        Log.i("NavEngineService", "Reroute successful. Processing new route.")
                        // Now you have a new routeResponse
                        // You would then call startNavigationLogic (or a similar method)
                        // to re-initialize the navigation with this new route.
                        startNavigationLogic(newRouteResponse)
                        // You might need to update the _navigationState to NAVIGATING
                        _navigationState.value = NavigationState.NAVIGATING
                    } else {
                        Log.w("NavEngineService", "Reroute resulted in no route legs: ${newRouteResponse.trip?.status_message}")
                        // Handle failure - maybe stay OFF_ROUTE or try again later
                        _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED // Or keep OFF_ROUTE
                    }
                },
                onFailure = { exception ->
                    Log.e("NavEngineService", "Reroute request failed: ${exception.message}")
                    // Handle failure
                    _navigationState.value = NavigationState.ROUTE_CALCULATION_FAILED // Or keep OFF_ROUTE
                }
            )
        }
    }
    private fun onNewLocationLogic(location: Location) {
        if (_navigationState.value == NavigationState.NAVIGATING) {
            gpxLogger.appendGpxTrackPoint(location)
        }
        //drop location with low accuracy
        if (location.accuracy > 230) {
            return
        }

        _lastLocation.value = location


        if ((_navigationState.value != NavigationState.NAVIGATING && _navigationState.value != NavigationState.OFF_ROUTE) || originalFullRoutePath.size < 2) {
            return
        }

        serviceScope.launch {
            val currentLocationLatLng = LatLng(location.latitude, location.longitude)

            originalFullRoutePath.lastOrNull()?.let { destination ->
                if (currentLocationLatLng.distanceTo(destination) < arrivalThresholdMeters) {
                    Log.i("NavigationEngineService", "User has ARRIVED at destination.")
                    _navigationState.value = NavigationState.ARRIVED
                    _currentDisplayedPath.value = emptyList()
                    _activeManeuverDetails.value = null
                    _remainingDistanceInMeters.value = 0.0
                    updateNotificationText("Arrived at destination.")
                    stopNavigationLogic()
                    return@launch
                }
            }

            val routePointsGeoJson = originalFullRoutePath.map { Point.fromLngLat(it.longitude, it.latitude) }
            val routeLineString = LineString.fromLngLats(routePointsGeoJson)
            val currentGeoJsonPoint = Point.fromLngLat(currentLocationLatLng.longitude, currentLocationLatLng.latitude)

            val snappedFeature: Feature = TurfMisc.nearestPointOnLine(currentGeoJsonPoint, routePointsGeoJson)

            val snappedPointGeometry = snappedFeature.geometry() as? Point
            if (snappedPointGeometry == null) {
                Log.e("NavigationEngineService", "TurfMisc.nearestPointOnLine did not return a Point geometry.")
                return@launch
            }
            val snappedPointOnRoute = LatLng(snappedPointGeometry.latitude(), snappedPointGeometry.longitude())
            val turfDistProperty = snappedFeature.properties()?.get("dist")?.asDouble
            if (turfDistProperty != null) {
                distanceAlongRouteToSnappedPoint = turfDistProperty * 1000
            } else {
                Log.w("NavigationEngineService", "'dist' property not found in Turf feature. Distance to maneuver might be less accurate.")
            }
            val distanceToRouteLine = currentLocationLatLng.distanceTo(snappedPointOnRoute)

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

            if (_navigationState.value == NavigationState.OFF_ROUTE && distanceToRouteLine > rerouteDistanceThresholdMeters && rerouteCount > 0) {
                originalFullRoutePath.lastOrNull()?.let { destination ->
                    // Check if a reroute isn't already in progress
                    if (_navigationState.value != NavigationState.ROUTE_CALCULATION) {
                        _navigationState.value = NavigationState.ROUTE_CALCULATION // Indicate rerouting
                        attemptReroute(location, destination)
                    }
                }
            }

            val turfSnappedIndex = snappedFeature.properties()?.get("index")?.asInt ?: -1

            if (turfSnappedIndex == -1) {
                Log.e("NavigationEngineService", "TurfMisc.nearestPointOnLine returned invalid index property.")
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
                currentSnappedShapeIndex = closestVertexIndex
            } else {
                if (turfSnappedIndex >= currentSnappedShapeIndex) {
                    currentSnappedShapeIndex = turfSnappedIndex
                } else {
                    Log.w("NavigationEngineService", "Turf index $turfSnappedIndex is behind current $currentSnappedShapeIndex. Holding index.")
                }
            }

            val remainingPath = mutableListOf<LatLng>()
            if (currentSnappedShapeIndex < originalFullRoutePath.size) {
                remainingPath.add(snappedPointOnRoute)
                if (currentSnappedShapeIndex + 1 < originalFullRoutePath.size) {
                    remainingPath.addAll(originalFullRoutePath.subList(currentSnappedShapeIndex + 1, originalFullRoutePath.size))
                } else if (originalFullRoutePath.isNotEmpty() && !remainingPath.contains(originalFullRoutePath.last())) {
                    if (originalFullRoutePath.last().distanceTo(snappedPointOnRoute) > 1.0) {
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
                    _remainingDistanceInMeters.value = 0.0
                    updateNotificationText("Arrived at destination.")
                    return@launch
                } else {
                    remainingPath.add(snappedPointOnRoute)
                }
            }
            _currentDisplayedPath.value = ArrayList(remainingPath)

            // --- Calculate and update remaining distance ---
            val remainingPoints = remainingPath.map { Point.fromLngLat(it.longitude, it.latitude) }
            if (remainingPoints.size > 1) {
                val remainingLineString = LineString.fromLngLats(remainingPoints)
                _remainingDistanceInMeters.value = TurfMeasurement.length(remainingLineString, TurfConstants.UNIT_METERS)
            } else {
                _remainingDistanceInMeters.value = 0.0
            }

            updateActiveManeuverDetails(currentSnappedShapeIndex, distanceAlongRouteToSnappedPoint)
        }
    }

    private fun updateActiveManeuverDetails(routePointIndexOfLastPassedVertex: Int, distanceTravelledOnRouteMeters: Double) {
        val activeManeuver = originalManeuvers.lastOrNull { maneuver ->
            (maneuver.begin_shape_index ?: 0) <= routePointIndexOfLastPassedVertex
        }

        if (activeManeuver != null) {
            val maneuverStartIndex = activeManeuver.begin_shape_index ?: 0
            var distanceToManeuverStartVertexMeters: Double? = null

            if (maneuverStartIndex < originalFullRoutePath.size) {
                var pathLengthToManeuverStart = 0.0
                for (i in 0 until maneuverStartIndex) {
                    if (i + 1 < originalFullRoutePath.size) {
                        pathLengthToManeuverStart += originalFullRoutePath[i].distanceTo(originalFullRoutePath[i + 1])
                    }
                }
                distanceToManeuverStartVertexMeters = pathLengthToManeuverStart
            }

            val remainingDistanceToManeuver: Double? = if (distanceToManeuverStartVertexMeters != null) {
                (distanceToManeuverStartVertexMeters - distanceTravelledOnRouteMeters).coerceAtLeast(0.0)
            } else {
                val maneuverStartPoint = originalFullRoutePath.getOrNull(activeManeuver.begin_shape_index ?: 0)
                val currentSnappedLatLng = _currentDisplayedPath.value.firstOrNull()
                if (maneuverStartPoint != null && currentSnappedLatLng != null) {
                    currentSnappedLatLng.distanceTo(maneuverStartPoint)
                } else {
                    null
                }
            }

            val newDetails = ActiveManeuverDetails(activeManeuver, remainingDistanceToManeuver)

            if (_activeManeuverDetails.value?.maneuver != activeManeuver || _activeManeuverDetails.value?.remainingDistanceToManeuverMeters != remainingDistanceToManeuver) {
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
                updateNotificationText("Navigating...")
            }
        }
    }

    private fun formatDistance(distanceMeters: Double): String {
        return if (distanceMeters < 10.0) {
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
            gpxLogger.stopGpxLogging()
            stopLocationUpdates()
            originalFullRoutePath = emptyList()
            originalManeuvers = emptyList()
            currentSnappedShapeIndex = 0
            _currentDisplayedPath.value = emptyList()
            _activeManeuverDetails.value = null
            _navigationState.value = NavigationState.IDLE
            _remainingDistanceInMeters.value = 0.0
            updateNotificationText("Navigation stopped.")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateNotificationText(text: String) {
        if (_navigationState.value != NavigationState.IDLE || text == "Navigation stopped.") {
            val notification = buildNotification(text)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d("NavigationEngineService", "Notification updated: $text")
        } else {
            Log.d("NavigationEngineService", "Skipped notification update, state is IDLE and text is not 'Navigation stopped.'")
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notificationChannelId = "LOCATION_SERVICE_CHANNEL"
        val channelName = "Location Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(notificationChannelId, channelName, NotificationManager.IMPORTANCE_LOW)
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
        val notification = notificationBuilder.setOngoing(true)
            .setContentText("Tracking your location...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("2Maps Navigation")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Navigation Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        stopLocationUpdates()
        gpxLogger.stopGpxLogging()
        Log.d("NavigationEngineService", "onDestroy")
    }
}
