package com.bconf.a2maps_and.navigation

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RoadDownloadProgress(val name: String, val received: Long, val total: Long)

/** Streams into the validated, atomic private-store import used by the file picker. */
class RoadDataDownloader(
    private val data: RoutingDataStore,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
) {
    suspend fun download(name: String, progress: (RoadDownloadProgress) -> Unit): RoadSegment {
        require(RoutingDataStore.validName(name) == name)
        val call = client.newCall(Request.Builder().url("https://brouter.de/brouter/segments4/$name").build())
        val finished = CompletableDeferred<Unit>()
        return try { suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try { continuation.resumeWithException(e) }
                    finally { finished.complete(Unit) }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val segment = response.use {
                            if (!continuation.isActive) return
                            if (it.code == 404) throw IOException("No road data is available for $name.")
                            if (!it.isSuccessful) throw IOException("Road data server returned HTTP ${it.code}. Retry later.")
                            val body = it.body ?: throw IOException("The road data response is empty.")
                            val total = body.contentLength()
                            if (total > data.directory.usableSpace) throw IOException("Not enough space to download $name.")
                            var received = 0L
                            var reportedAt = 0L
                            progress(RoadDownloadProgress(name, 0, total))
                            val input = object : FilterInputStream(body.byteStream()) {
                                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                                    if (!continuation.isActive) throw IOException("Download cancelled.")
                                    val count = super.read(buffer, offset, length)
                                    if (count > 0) received += count
                                    val now = System.nanoTime()
                                    if (continuation.isActive && (count == -1 || now - reportedAt >= 100_000_000L)) {
                                        progress(RoadDownloadProgress(name, received, total))
                                        reportedAt = now
                                    }
                                    return count
                                }
                            }
                            input.use { stream -> data.importSegment(name, stream) {
                                if (!continuation.isActive) throw CancellationException("Download cancelled.")
                            } }
                        }
                        continuation.resume(segment)
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    } finally { finished.complete(Unit) }
                }
            })
        } } finally {
            // Cancellation must finish validation/temp-file cleanup before another import can start.
            withContext(NonCancellable) { finished.await() }
        }
    }
}
