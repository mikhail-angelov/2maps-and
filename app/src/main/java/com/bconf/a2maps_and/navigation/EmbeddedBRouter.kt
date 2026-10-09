package com.bconf.a2maps_and.navigation

import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import btools.router.RoutingEngine as BRouterKernel

internal class EmbeddedBRouter(private val data: RoutingDataStore, private val memoryMb: Int) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var engine: BRouterKernel? = null

    fun calculate(origin: GeoPoint, destination: GeoPoint, alternative: Int): String {
        require(origin.isValid() && destination.isValid())
        val context = RoutingContext().apply {
            localFunction = data.prepareProfile().absolutePath
            turnInstructionMode = 3
            memoryclass = memoryMb.coerceIn(32, 96)
            setAlternativeIdx(alternative.coerceIn(0, 3))
        }
        fun point(position: GeoPoint, label: String) = OsmNodeNamed().apply {
            ilon = ((position.longitude + 180) * 1e6).roundToInt()
            ilat = ((position.latitude + 90) * 1e6).roundToInt()
            name = label
        }
        val kernel = BRouterKernel(null, null, data.directory, listOf(point(origin, "from"), point(destination, "to")), context)
        engine = kernel
        if (cancelled.get()) throw CancellationException()
        kernel.quite = true
        kernel.doRun(30_000)
        if (cancelled.get()) throw CancellationException()
        kernel.errorMessage?.let { error ->
            val tile = Regex("[EW]\\d{1,3}_[NS]\\d{1,2}\\.rd5").find(error)?.value
            val message = when {
                tile != null -> "Import $tile in Road data and retry."
                error.contains("memory", true) -> "Route exceeds routing memory limits. Choose a closer destination."
                error.contains("timeout", true) -> "Routing timed out. Choose a closer destination."
                else -> "Route could not be calculated. Check Road data or choose a point on a road."
            }
            val kind = when {
                error.contains("memory", true) -> RoutingFailure.TOO_LARGE
                error.contains("timeout", true) -> RoutingFailure.TIMEOUT
                else -> RoutingFailure.SETUP
            }
            throw RoutingException(kind, message)
        }
        val track = kernel.foundTrack ?: throw RoutingException(RoutingFailure.INVALID_RESPONSE, "Routing returned no road route.")
        return FormatJson(context).format(track)
    }

    fun cancel() { cancelled.set(true); engine?.terminate() }
}
