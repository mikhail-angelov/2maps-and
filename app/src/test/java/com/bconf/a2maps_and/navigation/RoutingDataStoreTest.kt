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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RoutingDataStoreTest {
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
