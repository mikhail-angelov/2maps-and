package com.bconf.a2maps_and

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent // Added for LocationService
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ContextMenu
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.bconf.a2maps_and.routing.RetrofitClient
import com.bconf.a2maps_and.routing.ValhallaLocation
import com.bconf.a2maps_and.routing.ValhallaRouteRequest
import com.bconf.a2maps_and.routing.ValhallaRouteResponse
// Import the correct FloatingActionButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textview.MaterialTextView
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView // Keep for type
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.io.IOException
import com.mapbox.geojson.utils.PolylineUtils

class MainActivity : AppCompatActivity() {

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
            val coarseLocationGranted = permissions.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)

            if (fineLocationGranted || coarseLocationGranted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (permissions.getOrDefault(Manifest.permission.ACCESS_BACKGROUND_LOCATION, false)) {
                        startLocationService()
                    } else {
                        Log.w("Location", "Background location permission denied.")
                        Toast.makeText(this, "Background location permission is needed for full functionality.", Toast.LENGTH_LONG).show()
                    }
                } else {
                    startLocationService()
                }
            } else {
                Log.w("Location", "Location permission denied.")
                Toast.makeText(this, "Location permissions are required for map functionality.", Toast.LENGTH_LONG).show()
            }
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)



        val fabShowNavigation: FloatingActionButton? = findViewById(R.id.fab_show_navigation)
        fabShowNavigation?.setOnClickListener {
//            showNavigationBottomSheet()
        }

        if (savedInstanceState == null) {
            val mapFragment = MapFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.mapFragmentContainer, mapFragment)
                .commit()
        }
    }


    private fun checkLocationPermissionsAndStartUpdates() {
        val permissionsToRequest = mutableListOf<String>()
        permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val foregroundPermissionsGranted = ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (foregroundPermissionsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    startLocationService()
                } else {
                    requestLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
            } else {
                startLocationService()
            }
        } else {
            requestLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startLocationService() {
        Intent(this, LocationService::class.java).also {
            it.action = LocationService.ACTION_START_LOCATION_SERVICE
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(it)
            } else {
                startService(it)
            }
        }
    }

    private fun stopLocationService() {
        Intent(this, LocationService::class.java).also {
            it.action = LocationService.ACTION_STOP_LOCATION_SERVICE
            startService(it) 
        }
    }


    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
         checkLocationPermissionsAndStartUpdates() // This is now called after map is ready
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopLocationService() 
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }




}
