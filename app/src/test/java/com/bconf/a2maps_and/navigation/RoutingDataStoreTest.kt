package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RoutingDataStoreTest {
    @Test fun regionsUseSouthWestCornersAndWrapAtTheDateLine() {
        val local = RoadDataRegions.nearby(GeoPoint(56.3, 44.0))
        assertEquals("E40_N55.rd5", local.first().name)
        assertEquals("E45_N55.rd5", local.single { it.direction == RoadDataDirection.EAST }.name)
        assertEquals("W5_S5.rd5", RoadDataRegions.nearby(GeoPoint(-0.1, -0.1)).first().name)
        val boundary = RoadDataRegions.nearby(GeoPoint(89.0, 179.9))
        assertEquals(6, boundary.size)
        assertTrue(boundary.any { it.name == "W180_N85.rd5" })
        assertTrue(boundary.all { RoutingDataStore.validName(it.name) == it.name })
    }

    @Test fun importedSegmentSurvivesDeletionOfItsOriginalFile() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = javaClass.getResourceAsStream("/navigation/E0_S80.rd5")!!.use { it.readBytes() }
        val source = File(context.cacheDir, "Downloads/E0_S80.rd5").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        source.inputStream().use { RoutingDataStore(context).importSegment(source.name, it) }
        assertTrue(source.delete())
        val reopened = RoutingDataStore(context)
        assertArrayEquals(bytes, File(reopened.directory, source.name).readBytes())
        assertEquals(bytes.size.toLong(), reopened.segments().single().bytes)
    }

    @Test fun downloadUsesTheValidatedPrivateStore() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = javaClass.getResourceAsStream("/navigation/E0_S80.rd5")!!.use { it.readBytes() }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val data = RoutingDataStore(context)
        var latest: RoadDownloadProgress? = null
        val result = RoadDataDownloader(data, client).download("E0_S80.rd5") { latest = it }
        assertArrayEquals(bytes, File(data.directory, result.name).readBytes())
        assertEquals(bytes.size.toLong(), latest!!.received)
        assertFalse(data.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun serverErrorsAndCorruptDownloadsPreserveExistingData() = runBlocking {
        val data = RoutingDataStore(RuntimeEnvironment.getApplication())
        val existing = File(data.directory, "E40_N55.rd5").apply { writeText("previous") }
        for (code in listOf(404, 200)) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("test").body(ByteArray(64).toResponseBody()).build()
            }.build()
            try { RoadDataDownloader(data, client).download(existing.name) {}; fail() }
            catch (_: IOException) { }
            assertEquals("previous", existing.readText())
            assertFalse(data.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
        }
    }

    @Test fun cancellationBeforePublicationKeepsThePreviousFileAndRemovesTheTemporaryCopy() {
        val data = RoutingDataStore(RuntimeEnvironment.getApplication())
        val existing = File(data.directory, "E0_S80.rd5").apply { writeText("previous") }
        val input = javaClass.getResourceAsStream("/navigation/E0_S80.rd5")!!
        try {
            input.use { data.importSegment(existing.name, it) { throw CancellationException() } }
            fail()
        } catch (_: CancellationException) { }
        assertEquals("previous", existing.readText())
        assertFalse(data.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun invalidNamesCannotEscapeStorage() {
        val data = RoutingDataStore(RuntimeEnvironment.getApplication())
        for (name in listOf("../E40_N55.rd5", "x.mbtiles", "E999_N55.rd5", "E42_N55.rd5")) {
            try { data.importSegment(name, ByteArrayInputStream(byteArrayOf())); fail(name) }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals("E40_N55.rd5", RoutingDataStore.validName("e40_n55.RD5"))
    }
    @Test fun corruptImportDoesNotReplaceExistingSegment() {
        val data = RoutingDataStore(RuntimeEnvironment.getApplication())
        val existing = File(data.directory, "E40_N55.rd5").apply { writeText("previous") }
        try { data.importSegment(existing.name, ByteArrayInputStream(ByteArray(64))); fail() }
        catch (_: IOException) { }
        assertEquals("previous", existing.readText())
        assertFalse(data.directory.listFiles()!!.any { it.name.endsWith(".tmp") })
    }
    @Test fun bundledProfileIsPreparedAndMissingRoadDataIsActionable() {
        val data = RoutingDataStore(RuntimeEnvironment.getApplication())
        val profile = data.prepareProfile()
        assertTrue(profile.readText().contains("car"))
        assertTrue(File(profile.parentFile, "lookups.dat").readText().startsWith("---lookupversion:11\n---minorversion:2"))
        try { EmbeddedBRouter(data, 64).calculate(GeoPoint(56.292374, 43.985402), GeoPoint(56.2965, 43.9955), 0); fail() }
        catch (error: RoutingException) {
            assertEquals(RoutingFailure.SETUP, error.kind)
            assertTrue(error.message!!.contains("Road data"))
        }
    }
    @Test fun cancelledCalculationDoesNotStartSearch() {
        val router = EmbeddedBRouter(RoutingDataStore(RuntimeEnvironment.getApplication()), 64)
        router.cancel()
        try { router.calculate(GeoPoint(56.29, 43.98), GeoPoint(56.30, 44.0), 0); fail() }
        catch (_: CancellationException) { }
    }
}
