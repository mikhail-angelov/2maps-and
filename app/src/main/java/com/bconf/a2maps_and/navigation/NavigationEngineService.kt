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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.huawei.hms.api.HuaweiApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLng
import com.huawei.hms.location.FusedLocationProviderClient as HmsFusedLocationProviderClient
import com.huawei.hms.location.LocationCallback as HmsLocationCallback

class NavigationEngineService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    private lateinit var huaweiFusedLocationClient: HmsFusedLocationProviderClient
    private lateinit var huaweiLocationCallback: HmsLocationCallback

    private val serviceJob = SupervisorJob()
    @Suppress("unused")
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var gpxLogger: GpxLogger

    companion object {
        const val ACTION_SET_CENTER_ON_LOCATION_STATE =
            "com.bconf.a2maps_and.action.SET_CENTER_ON_LOCATION_STATE"
        const val ACTION_START_LOCATION_SERVICE = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val EXTRA_CENTER_ON_LOCATION_STATE = "extra_center_on_location_state"
        const val PREFS_NAME = "NavigationEnginePrefs"
        const val KEY_CENTER_ON_LOCATION_STATE = "centerOnLocationState"
        const val KEY_ZOOM_LEVEL = "zoomLevel"
        const val ACTION_SET_ZOOM_LEVEL = "com.bconf.a2maps_and.action.SET_ZOOM_LEVEL"
        const val EXTRA_ZOOM_LEVEL = "extra_zoom_level"
        private const val KEY_LAST_LAT = "last_lat"
        private const val KEY_LAST_LNG = "last_lng"
        private const val KEY_LAST_ACCURACY = "last_accuracy"
        private const val KEY_LAST_ALTITUDE = "last_altitude"
        private const val KEY_LAST_SPEED = "last_speed"
        private const val KEY_LAST_TIME = "last_time"

        private const val LOCATION_UPDATE_INTERVAL = 10000L
        private const val FASTEST_LOCATION_INTERVAL = 5000L

        private val _recordedPath = MutableStateFlow<List<LatLng>>(emptyList())
        val recordedPath: StateFlow<List<LatLng>> = _recordedPath.asStateFlow()

        private val state = MutableStateFlow<CenterOnLocationState>(CenterOnLocationState.INACTIVE)

        private val _lastLocation = MutableStateFlow(Location(LocationManager.GPS_PROVIDER))
        val lastLocation: StateFlow<Location> = _lastLocation.asStateFlow()

        private val _zoomLevel = MutableStateFlow(15.0)
        val zoomLevel: StateFlow<Double> = _zoomLevel.asStateFlow()
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
        restoreZoomLevel()
    }

    private fun saveZoomLevel(zoom: Double) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putFloat(KEY_ZOOM_LEVEL, zoom.toFloat()).apply()
        _zoomLevel.value = zoom
    }

    private fun restoreZoomLevel() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedZoom = prefs.getFloat(KEY_ZOOM_LEVEL, 15.0f).toDouble()
        _zoomLevel.value = savedZoom
    }

    private fun saveLastLocation(location: Location) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_LAST_LAT, location.latitude.toFloat())
            .putFloat(KEY_LAST_LNG, location.longitude.toFloat())
            .putFloat(KEY_LAST_ACCURACY, location.accuracy)
            .putFloat(KEY_LAST_ALTITUDE, location.altitude.toFloat())
            .putFloat(KEY_LAST_SPEED, location.speed)
            .putLong(KEY_LAST_TIME, location.time)
            .apply()
    }

    private fun restoreLastLocation() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_LAST_LAT)) return
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = prefs.getFloat(KEY_LAST_LAT, 0f).toDouble()
            longitude = prefs.getFloat(KEY_LAST_LNG, 0f).toDouble()
            accuracy = prefs.getFloat(KEY_LAST_ACCURACY, 0f)
            altitude = prefs.getFloat(KEY_LAST_ALTITUDE, 0f).toDouble()
            speed = prefs.getFloat(KEY_LAST_SPEED, 0f)
            time = prefs.getLong(KEY_LAST_TIME, 0L)
        }
        _lastLocation.value = location
        Log.d(
            "NavigationEngineService",
            "Restored last location: ${location.latitude}, ${location.longitude}"
        )
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
                            "HMS Location: ${location.latitude}, ${location.longitude}, ${location.accuracy}"
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
                    com.google.android.gms.location.LocationRequest.Builder(
                        com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
                        LOCATION_UPDATE_INTERVAL
                    ).setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL).build()
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
            ACTION_START_LOCATION_SERVICE -> {
                startLocationUpdates()
            }

            ACTION_SET_CENTER_ON_LOCATION_STATE -> {
                val stateName = intent.getStringExtra(EXTRA_CENTER_ON_LOCATION_STATE)
                val newState = stateName?.let { CenterOnLocationState.valueOf(it) }
                if (newState != null) {
                    setCenterOnLocationState(newState)
                }
            }

            ACTION_SET_ZOOM_LEVEL -> {
                val zoom = intent.getDoubleExtra(EXTRA_ZOOM_LEVEL, -1.0)
                if (zoom != -1.0) {
                    saveZoomLevel(zoom)
                }
            }

            ACTION_STOP_SERVICE -> {
                Log.i("NavEngineService", "Received ACTION_STOP_SERVICE. Shutting down.")
                stopService()
                return START_NOT_STICKY
            }

            else -> {
                Log.w("NavigationService", "Received action: ${intent?.action} or service restart.")
                if (intent?.action == null) {
                    Log.d("NavigationService", "Service started with null action, stopping.")
                    stopService()
                }
            }
        }
        return START_STICKY
    }

    private fun setCenterOnLocationState(newState: CenterOnLocationState) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putString(KEY_CENTER_ON_LOCATION_STATE, newState.name)
        prefs.apply()
        _recordedPath.value = emptyList()
        state.value = newState

        when (newState) {
            CenterOnLocationState.RECORD,
            CenterOnLocationState.FOLLOW_AND_RECORD -> gpxLogger.startGpxLogging()
            else -> gpxLogger.stopGpxLogging()
        }
    }

    private fun onNewLocationLogic(location: Location) {
        if (location.accuracy > 230) return
        val isRecording = state.value == CenterOnLocationState.RECORD ||
                state.value == CenterOnLocationState.FOLLOW_AND_RECORD
        if (isRecording) {
            gpxLogger.appendGpxTrackPoint(location)
            _recordedPath.value = _recordedPath.value + LatLng(location.latitude, location.longitude)
        }
        _lastLocation.value = location
        saveLastLocation(location)
    }

    private fun startForegroundServiceWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("NavigationService", "Task removed, stopping service.")
        stopService()
        super.onTaskRemoved(rootIntent)
    }

    private fun stopService() {
        Log.i("NavEngineService", "stopService called. Cleaning up...")
        gpxLogger.stopGpxLogging()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopLocationUpdates()
        serviceJob.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        Log.d("NavigationService", "onDestroy: Service is being destroyed.")
        super.onDestroy()
        stopService()
    }
}
