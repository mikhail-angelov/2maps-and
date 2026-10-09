package com.bconf.a2maps_and

import android.Manifest
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.bconf.a2maps_and.location.LocationService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationGpsSourceTest {
    @Test fun `navigation uses one hardware GPS subscription and releases it on stop`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val controller = Robolectric.buildService(LocationService::class.java).create()
        val service = controller.get()
        val manager = app.getSystemService(LocationManager::class.java)
        val gps = shadowOf(manager)
        gps.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        fun navigation(active: Boolean) = service.onStartCommand(Intent(app, LocationService::class.java).apply {
            action = LocationService.ACTION_SET_NAVIGATION_ACTIVE
            putExtra(LocationService.EXTRA_NAVIGATION_ACTIVE, active)
        }, 0, 1)
        try {
            navigation(true)
            service.onStartCommand(Intent(app, LocationService::class.java).apply {
                action = LocationService.ACTION_START_LOCATION_SERVICE
            }, 0, 2)
            assertEquals(1, gps.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).size)
            assertEquals(1000L, gps.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).single().intervalMillis)
            navigation(true)
            assertEquals(1, gps.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).size)
            val fix = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 56.3; longitude = 44.0; accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            gps.simulateLocation(fix)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(fix.latitude, LocationService.lastLocation.value.latitude, 0.000001)
            assertEquals(5f, LocationService.lastLocation.value.accuracy)
            navigation(false)
            assertTrue(gps.getLegacyLocationRequests(LocationManager.GPS_PROVIDER).isEmpty())
        } finally {
            controller.destroy()
        }
    }
}
