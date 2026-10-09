package com.bconf.a2maps_and.navigation

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class LocalRoutingClient(context: Context) : RoutingEngine {
    private val app = context.applicationContext

    override suspend fun route(origin: GeoPoint, destination: GeoPoint, alternative: Int): Route = mutex.withLock {
        val ready = CompletableDeferred<IRoutingService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.complete(IRoutingService.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(connectionError()) }
            override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(connectionError()) }
            override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(connectionError()) }
        }
        var bound = false
        try {
            bound = app.bindService(Intent(app, EmbeddedRoutingService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw connectionError()
            val service = withTimeout(10_000) { ready.await() }
            val id = requestIds.incrementAndGet()
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { service.cancel(id) } }
                worker.execute {
                    if (continuation.isActive) continuation.resumeWith(runCatching {
                        val response = service.calculate(id, origin.latitude, origin.longitude, destination.latitude, destination.longitude, alternative)
                        response.getString("failure")?.let { throw RoutingException(RoutingFailure.valueOf(it), response.getString("message") ?: "Route unavailable.") }
                        BRouterResponseParser.parse(response.getString("route"), destination)
                    })
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw RoutingException(RoutingFailure.TIMEOUT, "Routing did not start. Retry the route.")
        } catch (_: TransactionTooLargeException) {
            throw RoutingException(RoutingFailure.TOO_LARGE, "Route is too large. Choose a closer destination.")
        } catch (_: RemoteException) {
            throw connectionError()
        } finally { if (bound) app.unbindService(connection) }
    }

    private fun connectionError() = RoutingException(RoutingFailure.CONNECTION, "Routing process stopped. Retry or choose a shorter route.")
    companion object {
        private val mutex = Mutex()
        private val requestIds = AtomicLong()
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "routing-client").apply { isDaemon = true } }
    }
}
