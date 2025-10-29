package com.bconf.a2maps_and.navigation


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import com.bconf.a2maps_and.R
import com.bconf.a2maps_and.repository.RouteRepository
import com.bconf.a2maps_and.routing.Maneuver
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.gson.Gson
import com.huawei.hms.api.HuaweiApiAvailability
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.utils.PolylineUtils
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import com.mapbox.turf.TurfMisc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import com.huawei.hms.location.FusedLocationProviderClient as HmsFusedLocationProviderClient
import com.huawei.hms.location.LocationCallback as HmsLocationCallback

class NavigationEngineService : Service() {

    private data class SerializableLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val altitude: Double,
        val speed: Float,
        val time: Long
    )

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
    private val arrivalThresholdMeters = 100.0
    private val rerouteDistanceThresholdMeters = 100.0
    private val maneuverCompletionThresholdMeters = 20.0
    private var rerouteCount = 5

    companion object {
        const val ACTION_START_NAVIGATION = "com.bconf.a2maps_and.action.START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "com.bconf.a2maps_and.action.STOP_NAVIGATION"
        const val ACTION_SET_CENTER_ON_LOCATION_STATE =
            "com.bconf.a2maps_and.action.SET_CENTER_ON_LOCATION_STATE"

        const val ACTION_REROUTE_NAVIGATION =
            "com.bconf.a2maps_and.action.ACTION_REROUTE_NAVIGATION"
        const val ACTION_START_LOCATION_SERVICE = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val EXTRA_DESTINATION_LATLNG = "extra_destination_latlng"
        const val EXTRA_CENTER_ON_LOCATION_STATE = "extra_center_on_location_state"
        const val PREFS_NAME = "NavigationEnginePrefs"
        const val KEY_CENTER_ON_LOCATION_STATE = "centerOnLocationState"
        private const val KEY_SAVED_ROUTE_RESPONSE = "savedRouteResponse"
        private const val KEY_LAST_LOCATION = "last_location"

        private const val LOCATION_UPDATE_INTERVAL = 10000L
        private const val FASTEST_LOCATION_INTERVAL = 5000L

        private val _currentDisplayedPath = MutableStateFlow<List<LatLng>>(emptyList())
        val currentDisplayedPath: StateFlow<List<LatLng>> = _currentDisplayedPath.asStateFlow()

        private val _activeManeuverDetails = MutableStateFlow<ActiveManeuverDetails?>(null)
        val activeManeuverDetails: StateFlow<ActiveManeuverDetails?> =
            _activeManeuverDetails.asStateFlow()

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
        huaweiFusedLocationClient =
            com.huawei.hms.location.LocationServices.getFusedLocationProviderClient(this)
        gpxLogger = GpxLogger(this)
        createNotificationChannel()
        setupLocationCallbacks()

        Log.d("Location", "--- checkGMS." + checkGMS())
        Log.d("Location", "--- checkHMS." + checkHMS())

        restoreLastLocation()
        restoreNavigationStateIfNecessary()
    }

    private fun saveLastLocation(location: Location) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val serializableLocation = SerializableLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = location.altitude,
            speed = location.speed,
            time = location.time
        )
        val locationJson = Gson().toJson(serializableLocation)
        prefs.edit().putString(KEY_LAST_LOCATION, locationJson).apply()
        Log.d(
            "NavigationEngineService",
            "Saved last location: ${location.latitude}, ${location.longitude}"
        )
    }

    private fun restoreLastLocation() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val locationJson = prefs.getString(KEY_LAST_LOCATION, null)
        if (locationJson != null) {
            try {
                // Deserialize to the data class first
                val serializableLocation =
                    Gson().fromJson(locationJson, SerializableLocation::class.java)

                // Reconstruct the Android Location object
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = serializableLocation.latitude
                    longitude = serializableLocation.longitude
                    accuracy = serializableLocation.accuracy
                    altitude = serializableLocation.altitude
                    speed = serializableLocation.speed
                    time = serializableLocation.time
                }

                _lastLocation.value = location
                Log.d(
                    "NavigationEngineService",
                    "Restored last location: ${location.latitude}, ${location.longitude}"
                )
            } catch (e: Exception) {
                Log.e("NavigationEngineService", "Failed to restore last location", e)
            }

        } else {
            Log.d("NavigationEngineService", "No saved location found to restore.")
        }
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
                        Log.d(
                            "NavigationEngineService",
                            "GMS Location: ${location.latitude}, ${location.longitude}, ${location.accuracy}"
                        )
                        onNewLocationLogic(location)
                    }
                }
            }
        } else if (checkHMS()) {
            huaweiLocationCallback = object : com.huawei.hms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.huawei.hms.location.LocationResult?) {
                    locationResult?.lastLocation?.let { location ->
                        Log.d(
                            "NavigationEngineService",
                            "HMS Location: ${location.latitude}, ${location.longitude}, ${location.accuracy}, ${location.bearing}, ${location.bearingAccuracyDegrees}"
                        )
                        onNewLocationLogic(location)
                    }
                }
            }
        }
    }

    private fun startLocationUpdates() {
        if (checkGMS()) {
            try {
                val locationRequestGMS =
                    com.google.android.gms.location.LocationRequest.create().apply {
                        interval = LOCATION_UPDATE_INTERVAL
                        fastestInterval = FASTEST_LOCATION_INTERVAL
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
                huaweiFusedLocationClient.requestLocationUpdates(
                    locationRequestHMS,
                    huaweiLocationCallback,
                    Looper.getMainLooper()
                )
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

        when (intent?.action) {
            ACTION_START_NAVIGATION -> {
                Log.d("NavigationEngineService", "Starting navigation...")
                rerouteCount = 5
                val destinationJson = intent.getStringExtra(EXTRA_DESTINATION_LATLNG)
                if (destinationJson != null) {
                    val destinationLatLng = Gson().fromJson(destinationJson, LatLng::class.java)
                    handleStartNavigationWithRouteFetch(destinationLatLng)
                } else {
                    Log.w("NavigationEngineService", "Destination LatLng not provided.")
                }
            }

            ACTION_STOP_NAVIGATION -> {
                stopNavigationAndService()
            }

            ACTION_REROUTE_NAVIGATION -> {
                rerouteCount = 5
                rerouteNavigationLogic()
            }

            ACTION_START_LOCATION_SERVICE -> {
                startLocationUpdates()
            }

            ACTION_SET_CENTER_ON_LOCATION_STATE -> {
                val stateName = intent.getStringExtra(EXTRA_CENTER_ON_LOCATION_STATE)
                val state = stateName?.let { CenterOnLocationState.valueOf(it) }
                if (state != null) {
                    setCenterOnLocationState(state)
                }
            }

            ACTION_STOP_SERVICE -> {
                Log.i("NavEngineService", "Received ACTION_STOP_SERVICE. Shutting down.")
                stopService()
                return START_NOT_STICKY // Service will not be recreated.
            }

            else -> {
                Log.w("NavigationService", "Received action: ${intent?.action} or service restart.")
                if (flags and START_FLAG_REDELIVERY == 0 && intent?.action == null) {
                    Log.d(
                        "NavigationService",
                        "Service likely restarted by system without specific intent, stopping."
                    )
                    stopNavigationAndService()
                } else if (intent?.action == null) {
                    Log.d(
                        "NavigationService",
                        "Service started with null action, stopping to be safe."
                    )
                    stopNavigationAndService()
                }
            }
        }
        return START_STICKY
    }

    private fun setCenterOnLocationState(state: CenterOnLocationState) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putString(KEY_CENTER_ON_LOCATION_STATE, state.name)
        prefs.apply()

        when (state) {
            CenterOnLocationState.RECORD -> gpxLogger.startGpxLogging()
            else -> gpxLogger.stopGpxLogging()
        }
    }

    private fun handleStartNavigationWithRouteFetch(destinationLatLng: LatLng) {
        serviceScope.launch {
            // Get the last known location from the service's own flow
            val currentGpsLocation = lastLocation.value

            if (currentGpsLocation.latitude == 0.0 && currentGpsLocation.longitude == 0.0) {
                Log.w("NavigationEngineService", "Cannot fetch route, GPS location is not available yet.")
                // Optional: Send a failure event back to the UI via a SharedFlow
                // For now, we just log and do nothing.
                return@launch
            }

            val fromLatLng = LatLng(currentGpsLocation.latitude, currentGpsLocation.longitude)

            Log.d("NavigationEngineService", "Service is fetching route from $fromLatLng to $destinationLatLng")
            val result = routeRepository.getRoute(
                ValhallaLocation(fromLatLng.latitude, fromLatLng.longitude),
                ValhallaLocation(destinationLatLng.latitude, destinationLatLng.longitude)
            )

            result.fold(
                onSuccess = { routeResponse ->
                    if (routeResponse.trip?.legs?.isNotEmpty() == true) {
                        Log.i("NavigationEngineService", "Route received, proceeding to start navigation.")
                        // We now have the route, so we can start the actual navigation engine
                        startNavigationLogic(routeResponse) // Call the internal method
                    } else {
                        Log.w(
                            "NavigationEngineService",
                            "No route legs in response: ${routeResponse.trip?.status_message}"
                        )
                        // Optional: Send failure event back to UI
                    }
                },
                onFailure = { exception ->
                    Log.e("NavigationEngineService", "Route request failed in service: ${exception.message}")
                    // Optional: Send failure event back to UI
                }
            )
        }
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
                val decodedMapboxPoints: List<com.mapbox.geojson.Point> =
                    PolylineUtils.decode(shape, 6)
                originalFullRoutePath =
                    decodedMapboxPoints.map { LatLng(it.latitude(), it.longitude()) }

                val fullRouteLineString = LineString.fromLngLats(decodedMapboxPoints)
                _remainingDistanceInMeters.value =
                    TurfMeasurement.length(fullRouteLineString, TurfConstants.UNIT_METERS)
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
            updateActiveManeuverDetails(currentSnappedShapeIndex)
            updateNotificationText("Navigating...")
            Log.d(
                "NavigationEngineService",
                "Navigation started. Full path size: ${originalFullRoutePath.size}. Maneuvers: ${originalManeuvers.size}"
            )
        }
    }

    private fun attemptReroute(currentLocation: Location, destination: LatLng) {
        rerouteCount--
        serviceScope.launch { // Launch a coroutine
            Log.d(
                "NavEngineService",
                "Attempting reroute ($rerouteCount) from $currentLocation to $destination"
            )
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
                    } else {
                        Log.w(
                            "NavEngineService",
                            "Reroute resulted in no route legs: ${newRouteResponse.trip?.status_message}"
                        )
                        // Handle failure - maybe stay OFF_ROUTE or try again later
                        _navigationState.value =
                            NavigationState.ROUTE_CALCULATION_FAILED // Or keep OFF_ROUTE
                    }
                },
                onFailure = { exception ->
                    Log.e("NavEngineService", "Reroute request failed: ${exception.message}")
                    // Handle failure
                    _navigationState.value =
                        NavigationState.ROUTE_CALCULATION_FAILED // Or keep OFF_ROUTE
                }
            )
        }
    }

    private fun composeRemainingPath(snappedFeature: Feature): ArrayList<LatLng> {
        val snappedPointGeometry = snappedFeature.geometry() as Point
        val snappedPointOnRoute =
            LatLng(snappedPointGeometry.latitude(), snappedPointGeometry.longitude())
        val turfSnappedIndex = snappedFeature.properties()?.get("index")?.asInt ?: -1

        val remainingPath = mutableListOf<LatLng>()

        if (turfSnappedIndex == -1) {
            Log.e(
                "NavigationEngineService",
                "TurfMisc.nearestPointOnLine returned invalid index property."
            )
            var closestVertexIndex = 0
            var minDist = Double.MAX_VALUE
            for (i in originalFullRoutePath.indices) {
                val dist = originalFullRoutePath[i].distanceTo(snappedPointOnRoute)
                if (dist < minDist) {
                    minDist = dist
                    closestVertexIndex = i
                }
            }
            Log.w(
                "NavigationEngineService",
                "Falling back to closest vertex index: $closestVertexIndex"
            )
            currentSnappedShapeIndex = closestVertexIndex
        } else {
            if (turfSnappedIndex >= currentSnappedShapeIndex) {
                currentSnappedShapeIndex = turfSnappedIndex
            } else {
                Log.w(
                    "NavigationEngineService",
                    "Turf index $turfSnappedIndex is behind current $currentSnappedShapeIndex. Holding index."
                )
            }
        }

        if (currentSnappedShapeIndex < originalFullRoutePath.size) {
            remainingPath.add(snappedPointOnRoute)
            if (currentSnappedShapeIndex + 1 < originalFullRoutePath.size) {
                remainingPath.addAll(
                    originalFullRoutePath.subList(
                        currentSnappedShapeIndex + 1,
                        originalFullRoutePath.size
                    )
                )
            } else if (originalFullRoutePath.isNotEmpty() && !remainingPath.contains(
                    originalFullRoutePath.last()
                )
            ) {
                if (originalFullRoutePath.last().distanceTo(snappedPointOnRoute) > 1.0) {
                    remainingPath.add(originalFullRoutePath.last())
                }
            }
        }

        if (remainingPath.isEmpty() && originalFullRoutePath.isNotEmpty()) {
            remainingPath.add(snappedPointOnRoute)
        }
        return ArrayList(remainingPath)
    }

    private fun getRemainingDistance(remainingPath: ArrayList<LatLng>): Double {
        val remainingPoints = remainingPath.map { Point.fromLngLat(it.longitude, it.latitude) }
        if (remainingPoints.size > 1) {
            val remainingLineString = LineString.fromLngLats(remainingPoints)
            return TurfMeasurement.length(remainingLineString, TurfConstants.UNIT_METERS)
        } else {
            return 0.0
        }
    }

    private fun getPathBearing(path: List<LatLng>): Float? {
        if (path.size > 1) {
            val firstPointInPath = path[0]
            val nextPointInPath = path[1]
            val bearingToNextPoint = TurfMeasurement.bearing(
                Point.fromLngLat(firstPointInPath.longitude, firstPointInPath.latitude),
                Point.fromLngLat(nextPointInPath.longitude, nextPointInPath.latitude)
            )
            return bearingToNextPoint.toFloat()
        }
        return null
    }

    private fun onNewLocationLogic(location: Location) {
        //drop location with low accuracy
        if (location.accuracy > 230) {
            return
        }
        if (navigationState.value != NavigationState.IDLE) {
            gpxLogger.appendGpxTrackPoint(location)
        }

        _lastLocation.value = location
        saveLastLocation(location)


        if (navigationState.value == NavigationState.IDLE || originalFullRoutePath.size < 2) {
            return
        }

        serviceScope.launch {
            val currentLocationLatLng = LatLng(location.latitude, location.longitude)

            originalFullRoutePath.lastOrNull()?.let { destination ->
                if (currentLocationLatLng.distanceTo(destination) < arrivalThresholdMeters) {
                    Log.i("NavigationEngineService", "User has ARRIVED at destination.")
                    _navigationState.value = NavigationState.IDLE
                    _currentDisplayedPath.value = emptyList()
                    _activeManeuverDetails.value = null
                    _remainingDistanceInMeters.value = 0.0
                    updateNotificationText("Arrived at destination.")
                    stopNavigationAndService()
                    return@launch
                }
            }

            val routePointsGeoJson =
                originalFullRoutePath.map { Point.fromLngLat(it.longitude, it.latitude) }
            val currentGeoJsonPoint =
                Point.fromLngLat(currentLocationLatLng.longitude, currentLocationLatLng.latitude)

            val snappedFeature: Feature =
                TurfMisc.nearestPointOnLine(currentGeoJsonPoint, routePointsGeoJson)

            val snappedPointGeometry = snappedFeature.geometry() as? Point
            if (snappedPointGeometry == null) {
                Log.e(
                    "NavigationEngineService",
                    "TurfMisc.nearestPointOnLine did not return a Point geometry."
                )
                return@launch
            }
            val snappedPointOnRoute =
                LatLng(snappedPointGeometry.latitude(), snappedPointGeometry.longitude())
            val turfDistProperty = snappedFeature.properties()?.get("dist")?.asDouble
            if (turfDistProperty != null) {
                distanceAlongRouteToSnappedPoint = turfDistProperty * 1000
            } else {
                Log.w(
                    "NavigationEngineService",
                    "'dist' property not found in Turf feature. Distance to maneuver might be less accurate."
                )
            }
            val distanceToRouteLine = currentLocationLatLng.distanceTo(snappedPointOnRoute)

            if (distanceToRouteLine <= offRouteThresholdMeters) {
                if (_navigationState.value != NavigationState.NAVIGATING && _navigationState.value != NavigationState.IDLE) {
                    //return to route
                    _navigationState.value = NavigationState.NAVIGATING
                    updateNotificationText("Back on route. Navigating...")
                    Log.i("NavigationEngineService", "User is back ON-ROUTE.")
                }

                val remainingPath = composeRemainingPath(snappedFeature)
                _currentDisplayedPath.value = remainingPath
                _remainingDistanceInMeters.value = getRemainingDistance(remainingPath)

                updateActiveManeuverDetails(currentSnappedShapeIndex)

                val routeLocation = Location(LocationManager.GPS_PROVIDER)
                routeLocation.latitude = snappedPointOnRoute.latitude
                routeLocation.longitude = snappedPointOnRoute.longitude
                routeLocation.accuracy = location.accuracy
                routeLocation.bearing = getPathBearing(remainingPath) ?: location.bearing
                _lastLocation.value = routeLocation
            }

            if (distanceToRouteLine > offRouteThresholdMeters) {
                if (_navigationState.value != NavigationState.OFF_ROUTE) {
                    Log.w(
                        "NavigationEngineService",
                        "User is OFF-ROUTE. Distance: $distanceToRouteLine m"
                    )
                    _navigationState.value = NavigationState.OFF_ROUTE
                    updateNotificationText("Off-route.")
                }
            }

            //reroute
            if (_navigationState.value == NavigationState.OFF_ROUTE && distanceToRouteLine > rerouteDistanceThresholdMeters && rerouteCount > 0) {
                originalFullRoutePath.lastOrNull()?.let { destination ->
                    // Check if a reroute isn't already in progress
                    if (_navigationState.value != NavigationState.ROUTE_CALCULATION) {
                        _navigationState.value =
                            NavigationState.ROUTE_CALCULATION // Indicate rerouting
                        attemptReroute(location, destination)
                    }
                }
            }


        }
    }

    private fun updateActiveManeuverDetails(routePointIndexOfLastPassedVertex: Int) {
        val activeManeuver = originalManeuvers.firstOrNull { maneuver ->
            (maneuver.begin_shape_index ?: 0) > routePointIndexOfLastPassedVertex
        }

        if (activeManeuver != null) {
            val maneuverStartIndex = activeManeuver.begin_shape_index ?: 0

            if (maneuverStartIndex < originalFullRoutePath.size) {
                var pathLengthToManeuverStart = 0.0
                for (i in 0 until maneuverStartIndex) {
                    if (i + 1 < originalFullRoutePath.size) {
                        pathLengthToManeuverStart += originalFullRoutePath[i].distanceTo(
                            originalFullRoutePath[i + 1]
                        )
                    }
                }
            }

            val maneuverStartPoint =
                originalFullRoutePath.getOrNull(activeManeuver.begin_shape_index ?: 0)
            val currentSnappedLatLng = _currentDisplayedPath.value.firstOrNull()
            val remainingDistanceToManeuver: Double? =
                if (maneuverStartPoint != null && currentSnappedLatLng != null) {
                    currentSnappedLatLng.distanceTo(maneuverStartPoint)
                } else {
                    null
                }


            val newDetails = ActiveManeuverDetails(activeManeuver, remainingDistanceToManeuver)

            if (_activeManeuverDetails.value?.maneuver != activeManeuver || _activeManeuverDetails.value?.remainingDistanceToManeuverMeters != remainingDistanceToManeuver) {
                _activeManeuverDetails.value = newDetails

                activeManeuver.let {
                    val distStr = if (remainingDistanceToManeuver != null) formatDistance(
                        remainingDistanceToManeuver
                    ) else "..."
                    Log.i(
                        "NavigationEngineService",
                        "Active Maneuver: '$distStr: ${it.instruction}' (begin_idx: ${it.begin_shape_index})"
                    )
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

    private fun rerouteNavigationLogic() {
        originalFullRoutePath.lastOrNull()?.let { destination ->
            // Check if a reroute isn't already in progress
            if (_navigationState.value != NavigationState.ROUTE_CALCULATION) {
                _navigationState.value = NavigationState.ROUTE_CALCULATION
                attemptReroute(lastLocation.value, destination)
            }
        }
    }

    private fun stopNavigationLogic() {
        serviceScope.launch {
            Log.d("NavigationEngineService", "Stopping Navigation Logic.")
            gpxLogger.stopGpxLogging()
            _navigationState.value = NavigationState.IDLE
            _currentDisplayedPath.value = emptyList()
            _activeManeuverDetails.value = null
            _remainingDistanceInMeters.value = 0.0
            originalFullRoutePath = emptyList()
            originalManeuvers = emptyList()
            currentSnappedShapeIndex = 0

            clearSavedNavigationState()
            updateNotificationText("Navigation stopped.")
            stopForeground(true)
            stopSelf()
        }
    }

    private fun updateNotificationText(text: String) {
        if (_navigationState.value != NavigationState.IDLE || text == "Navigation stopped.") {
//            val notification = buildNotification(text)
//            val notificationManager =
//                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d("NavigationEngineService", "Notification updated: $text")
        } else {
            Log.d(
                "NavigationEngineService",
                "Skipped notification update, state is IDLE and text is not 'Navigation stopped.'"
            )
        }
    }

    private fun startForegroundServiceWithNotification() {
        val notificationChannelId = "LOCATION_SERVICE_CHANNEL"
        val channelName = "Location Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                notificationChannelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(chan)
        }

        val notificationBuilder = NotificationCompat.Builder(this, notificationChannelId)
        val notification = notificationBuilder.setOngoing(true)
            .setContentText("2 Maps Navigation")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Navigation Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun saveNavigationState(routeResponse: ValhallaRouteResponse) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        val routeJson = Gson().toJson(routeResponse)
        prefs.putString(KEY_SAVED_ROUTE_RESPONSE, routeJson)

        prefs.apply()
    }

    private fun clearSavedNavigationState() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.remove(KEY_SAVED_ROUTE_RESPONSE)
        prefs.apply()
        Log.d("NavigationEngineService", "Cleared saved navigation state from SharedPreferences.")
    }

    private fun restoreNavigationStateIfNecessary() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val routeJson = prefs.getString(KEY_SAVED_ROUTE_RESPONSE, null)

        if (routeJson != null) {
            Log.i("NavigationEngineService", "Restoring navigation state from SharedPreferences.")
            val restoredRouteResponse =
                Gson().fromJson(routeJson, ValhallaRouteResponse::class.java)

            startNavigationLogic(restoredRouteResponse)
            startForegroundServiceWithNotification()
            Log.i(
                "NavigationEngineService",
                "Navigation state restored. Current state: ${_navigationState.value}"
            )

        } else {
            Log.d(
                "NavigationEngineService",
                "No saved route shape found or was not navigating, not restoring."
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * This method is called when the user removes the task that this service was
     * started in (e.g., swiping it away from the recent apps list).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("NavigationService", "Task removed, stopping service.")
        stopService()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopNavigationAndService() {
        Log.d("NavigationService", "stopNavigationAndService called.")

        gpxLogger.stopGpxLogging()
        _navigationState.value = NavigationState.IDLE
        _currentDisplayedPath.value = emptyList()
        _activeManeuverDetails.value = null
        _remainingDistanceInMeters.value = 0.0
        originalFullRoutePath = emptyList()
        originalManeuvers = emptyList()
        currentSnappedShapeIndex = 0

        clearSavedNavigationState()
        updateNotificationText("Navigation stopped.")

        stopForeground(STOP_FOREGROUND_REMOVE) // Pass true to remove the notification.

        Log.d("NavigationService", "navigation definitively stopped.")
    }

    private fun stopService() {
        Log.i("NavEngineService", "stopService called. Cleaning up...")
        stopNavigationAndService()
        stopLocationUpdates()

        // Cancel any ongoing coroutines.
        serviceJob.cancel()

        // stop the service itself.
        stopSelf()
    }


    override fun onDestroy() {
        Log.d("NavigationService", "onDestroy: Service is being destroyed.")
        super.onDestroy()
        stopService()
    }
}
