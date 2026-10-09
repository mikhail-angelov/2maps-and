package com.bconf.a2maps_and.navigation

import android.content.*
import android.os.*
import btools.routingapp.IBRouterService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BRouterClient(context: Context) : RoutingEngine {
    private val app = context.applicationContext
    private val mutex = Mutex()

    fun isAvailable(): Boolean = app.packageManager.resolveService(
        Intent().setClassName("btools.routingapp", "btools.routingapp.BRouterService"), 0
    )?.serviceInfo?.let { it.enabled && it.exported } == true

    override suspend fun route(origin: GeoPoint, destination: GeoPoint): Route = mutex.withLock {
        val ready = CompletableDeferred<IBRouterService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IBRouterService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(connectionError()) }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(connectionError()) }
            override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(connectionError()) }
        }
        var bound = false
        try {
            bound = app.bindService(Intent().setClassName("btools.routingapp", "btools.routingapp.BRouterService"), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw RoutingException(RoutingFailure.UNAVAILABLE, "Offline routing requires BRouter.")
            val service = withTimeout(10_000) { ready.await() }
            // Cancellation does not abort IPC. Keep binding and mutex until the blocking call returns.
            withContext(Dispatchers.IO) {
                val params = Bundle().apply {
                    putString("lonlats", "${origin.longitude},${origin.latitude}|${destination.longitude},${destination.latitude}")
                    putString("profile", "car-vario")
                    putString("trackFormat", "json")
                    putString("maxRunningTime", "30")
                }
                BRouterResponseParser.parse(service.getTrackFromParams(params), destination)
            }
        } catch (_: TimeoutCancellationException) {
            throw RoutingException(RoutingFailure.TIMEOUT, "BRouter did not connect. Open BRouter and retry.")
        } catch (_: TransactionTooLargeException) {
            throw RoutingException(RoutingFailure.TOO_LARGE, "Route exceeds Android IPC limits. Choose a closer destination.")
        } catch (_: RemoteException) {
            throw connectionError()
        } catch (_: SecurityException) {
            throw RoutingException(RoutingFailure.UNAVAILABLE, "BRouter service is unavailable. Install or enable BRouter.")
        } finally {
            if (bound) app.unbindService(connection)
        }
    }

    private fun connectionError() = RoutingException(RoutingFailure.CONNECTION, "BRouter disconnected. Open BRouter and retry; shorten the route if this persists.")
}
