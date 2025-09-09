package com.bconf.a2maps_and

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.GoogleApiAvailability
//import androidx.privacysandbox.tools.core.generator.build
import com.google.android.gms.location.*
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.*

class LocationService : Service() {

    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationCallback: com.google.android.gms.location.LocationCallback

    // Huawei Location
    private lateinit var huaweiFusedLocationClient: com.huawei.hms.location.FusedLocationProviderClient
    private lateinit var huaweiLocationCallback: com.huawei.hms.location.LocationCallback

    companion object {
        const val ACTION_START_LOCATION_SERVICE = "ACTION_START_LOCATION_SERVICE"
        const val ACTION_STOP_LOCATION_SERVICE = "ACTION_STOP_LOCATION_SERVICE"
        const val ACTION_LOCATION_UPDATE = "com.bconf.a2maps_and.LOCATION_UPDATE"
        const val EXTRA_LATITUDE = "com.bconf.a2maps_and.EXTRA_LATITUDE"
        const val EXTRA_LONGITUDE = "com.bconf.a2maps_and.EXTRA_LONGITUDE"
        const val ACCURACY = "com.bconf.a2maps_and.ACCURACY"
        const val BEARING = "com.bconf.a2maps_and.BEARING"
        const val BEARING_ACCURACY = "com.bconf.a2maps_and.BEARING_ACCURACY"
        private const val NOTIFICATION_CHANNEL_ID = "location_service_channel"
        private const val SERVICE_ID = 101 // Choose a unique ID
        private const val LOCATION_UPDATE_INTERVAL = 20000L // 20 seconds
        private const val FASTEST_LOCATION_INTERVAL = 15000L // 15 seconds
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
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
                com.google.android.gms.common.ConnectionResult.SUCCESS
    }

    private fun checkHMS():Boolean {
        val hApi = HuaweiApiAvailability.getInstance()
        val resultCode = hApi.isHuaweiMobileServicesAvailable(this)
        return resultCode == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    private fun setupLocationCallbacks() {
        // Google Mobile Services (GMS) Location Callback
        if (checkGMS()) {
            locationCallback = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.google.android.gms.location.LocationResult) {
                    locationResult.lastLocation?.let { location ->
                        Log.d(
                            "LocationService",
                            "GMS Location: \${location.latitude}, \${location.longitude}"
                        )
                        // TODO: Handle location update (e.g., save to database, send to server)
                    }
                }
            }
        }

        // Huawei Mobile Services (HMS) Location Callback
        if (checkHMS()) {
            huaweiLocationCallback = object : com.huawei.hms.location.LocationCallback() {
                override fun onLocationResult(locationResult: com.huawei.hms.location.LocationResult?) {
                    locationResult?.lastLocation?.let { location ->
                        Log.d(
                            "LocationService",
                            "HMS Location++++++: ${location.latitude}, ${location.longitude}, ${location.accuracy}, ${location.bearing}, ${location.bearingAccuracyDegrees}"
                        )
                        // Send broadcast
                        val locationIntent = Intent(ACTION_LOCATION_UPDATE)
                        locationIntent.putExtra(EXTRA_LATITUDE, location.latitude)
                        locationIntent.putExtra(EXTRA_LONGITUDE, location.longitude)
                        locationIntent.putExtra(ACCURACY, location.accuracy)
                        locationIntent.putExtra(BEARING, location.bearing)
                        locationIntent.putExtra(BEARING_ACCURACY, location.bearingAccuracyDegrees)
                        LocalBroadcastManager.getInstance(this@LocationService)
                            .sendBroadcast(locationIntent)
                    }
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let {
            when (it) {
                ACTION_START_LOCATION_SERVICE -> {
                    startForegroundServiceWithNotification()
                    startLocationUpdates()
                }
                ACTION_STOP_LOCATION_SERVICE -> {
                    stopLocationUpdates()
                    stopForeground(true)
                    stopSelf()
                }
            }
        }
        return START_STICKY // If the service is killed, restart it
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
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(SERVICE_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Location Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
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
                Log.e("LocationService", "GMS SecurityException: \${e.message}")
            } catch (e: Exception) {
                Log.e("LocationService", "Could not start GMS location updates: \${e.message}")
            }
        }

        if(checkHMS()) {
            try {
                // HMS Location Request
                val locationRequestHMS = com.huawei.hms.location.LocationRequest().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
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
          if(locationCallback != null) {
              fusedLocationClient.removeLocationUpdates(locationCallback)
              Log.d("LocationService", "GMS location updates stopped.")
          }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop GMS location updates: \${e.message}")
        }

        try {
            if(huaweiLocationCallback != null) {
                huaweiFusedLocationClient.removeLocationUpdates(huaweiLocationCallback)
                Log.d("LocationService", "HMS location updates stopped.")
            }
        } catch (e: Exception) {
            Log.e("LocationService", "Could not stop HMS location updates: \${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We are not using binding
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }
}
