package com.bconf.a2maps_and.navigation

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

class EmbeddedRoutingService : Service() {
    private val calculationLock = Any()
    private val cancelled = ConcurrentHashMap.newKeySet<Long>()
    @Volatile private var active: Pair<Long, EmbeddedBRouter>? = null
    @Volatile private var destroyed = false

    private val binder = object : IRoutingService.Stub() {
        override fun calculate(id: Long, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, alternative: Int): Bundle = synchronized(calculationLock) {
            if (destroyed) return@synchronized failure(RoutingFailure.CONNECTION, "Routing stopped. Retry the route.")
            val router = EmbeddedBRouter(RoutingDataStore(this@EmbeddedRoutingService), getSystemService(ActivityManager::class.java).memoryClass)
            active = id to router
            if (cancelled.remove(id)) router.cancel()
            try {
                Bundle().apply { putString("route", router.calculate(GeoPoint(fromLat, fromLon), GeoPoint(toLat, toLon), alternative)) }
            } catch (_: CancellationException) {
                failure(RoutingFailure.CONNECTION, "Routing cancelled.")
            } catch (error: RoutingException) {
                failure(error.kind, error.message ?: "Route unavailable.")
            } catch (_: OutOfMemoryError) {
                failure(RoutingFailure.TOO_LARGE, "Route exceeds routing memory limits. Choose a closer destination.")
            } catch (_: Exception) {
                failure(RoutingFailure.SETUP, "Road data cannot be read. Re-import the segments and retry.")
            } finally { active = null; cancelled.remove(id) }
        }
        override fun cancel(id: Long) {
            cancelled.add(id)
            active?.takeIf { it.first == id }?.second?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() { destroyed = true; active?.second?.cancel(); super.onDestroy() }
    private fun failure(kind: RoutingFailure, message: String) = Bundle().apply {
        putString("failure", kind.name); putString("message", message)
    }
}
