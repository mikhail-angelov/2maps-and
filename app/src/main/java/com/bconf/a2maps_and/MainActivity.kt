package com.bconf.a2maps_and

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bconf.a2maps_and.navigation.NavigationState
import com.bconf.a2maps_and.service.NavigationEngineService
import com.bconf.a2maps_and.ui.viewmodel.NavigationViewModel
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var navigationViewModel: NavigationViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        navigationViewModel = ViewModelProvider(this).get(NavigationViewModel::class.java)
        setContentView(R.layout.activity_main)
        observeViewModel()
    }

    private fun observeViewModel() {

        lifecycleScope.launch {
            navigationViewModel.navigationState.collectLatest { state ->
                Log.d("MainActivity", "VM Navigation State Changed: $state")
                // ... (handle UI changes based on state: Toasts, button visibility etc.)
                if (state == NavigationState.ARRIVED) {
                    Toast.makeText(this@MainActivity, "You have arrived!", Toast.LENGTH_LONG).show()
                    // ViewModel will call stopNavigation on the service
                } else if (state == NavigationState.ROUTE_CALCULATION) {
                    Toast.makeText(this@MainActivity, "Requesting route...", Toast.LENGTH_LONG)
                        .show()
                }else if (state == NavigationState.OFF_ROUTE) {
                    Toast.makeText(this@MainActivity, "You are off route!", Toast.LENGTH_LONG).show()
                } else if (state == NavigationState.ROUTE_CALCULATION_FAILED) {
                    Toast.makeText(this@MainActivity, "Route calculation failed.", Toast.LENGTH_LONG).show()
                }
            }
        }

    }

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
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
//                    startLocationService()
//                } else {
//                    requestLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
//                }
//            } else {
                startLocationService()
//            }
        } else {
            requestLocationPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun startLocationService() {
        Intent(this, NavigationEngineService::class.java).also {
            it.action = NavigationEngineService.ACTION_START_LOCATION_SERVICE
//             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                startForegroundService(it)
//            } else {
                startService(it)
//            }
        }
    }

    private fun stopLocationService() {
        Intent(this, NavigationEngineService::class.java).also {
            it.action = NavigationEngineService.ACTION_STOP_LOCATION_SERVICE
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
