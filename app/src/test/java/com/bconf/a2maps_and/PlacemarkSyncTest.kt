package com.bconf.a2maps_and

import com.bconf.a2maps_and.auth.ServerPlacemark
import com.bconf.a2maps_and.placemark.Placemark
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class PlacemarkSyncTest {

    // Real data from https://2maps.xyz/marks/m/sync
    private val serverJson = """[
        {"id":"4c817f59-efbc-4800-8625-fa0676a1f2b3","name":"мельницы","description":"","lng":44.287555258,"lat":55.980700945,"timestamp":1716395742184,"rate":1},
        {"id":"e3e519b0-259a-45fe-8026-efcbfa96f3d7","name":"охта","description":"","lng":45.110147714,"lat":55.695998874,"timestamp":1716397374248,"rate":1},
        {"id":"e404b551-485a-4d1f-8f51-75567ff479ff","name":"д Ивановка","description":"","lng":44.920845218,"lat":55.736438793,"timestamp":1643273241642,"rate":null},
        {"id":"b551485a-2d1f-4f51-b556-7ff479ff6c61","name":"д Дубрава","description":"","lng":44.882325211,"lat":55.79610203,"timestamp":1643273241642,"rate":null}
    ]"""

    private val gson = Gson()
    private val listType = object : TypeToken<List<ServerPlacemark>>() {}.type

    private fun parseServer(json: String): List<ServerPlacemark> = gson.fromJson(json, listType)

    private fun ServerPlacemark.toPlacemark() = Placemark(
        id = id, name = name, latitude = lat, longitude = lng,
        rate = rate, description = description, timestamp = timestamp
    )

    // --- Parsing ---

    @Test
    fun `parses server JSON into correct number of marks`() {
        assertEquals(4, parseServer(serverJson).size)
    }

    @Test
    fun `parses id, name, coordinates, rate, timestamp correctly`() {
        val mark = parseServer(serverJson)[0]
        assertEquals("4c817f59-efbc-4800-8625-fa0676a1f2b3", mark.id)
        assertEquals("мельницы", mark.name)
        assertEquals(55.980700945, mark.lat, 1e-9)
        assertEquals(44.287555258, mark.lng, 1e-9)
        assertEquals(1, mark.rate)
        assertEquals(1716395742184L, mark.timestamp)
    }

    @Test
    fun `null rate is preserved`() {
        val marks = parseServer(serverJson)
        assertNull(marks[2].rate)
        assertNull(marks[3].rate)
    }

    @Test
    fun `removed defaults to false when field is absent`() {
        parseServer(serverJson).forEach { assertNotEquals(true, it.removed) }
    }

    @Test
    fun `parses explicit removed=true`() {
        val json = """[{"id":"abc","name":"x","description":"","lng":1.0,"lat":1.0,"timestamp":1000,"rate":null,"removed":true}]"""
        assertEquals(true, parseServer(json)[0].removed)
    }

    // --- Conversion ServerPlacemark → Placemark ---

    @Test
    fun `converts to Placemark with correct lat lng mapping`() {
        val pm = parseServer(serverJson)[0].toPlacemark()
        assertEquals(55.980700945, pm.latitude, 1e-9)
        assertEquals(44.287555258, pm.longitude, 1e-9)
    }

    @Test
    fun `converted Placemark coordinates match`() {
        val pm = parseServer(serverJson)[0].toPlacemark()
        assertEquals(pm.latitude, pm.coordinates.latitude, 1e-9)
        assertEquals(pm.longitude, pm.coordinates.longitude, 1e-9)
    }

    @Test
    fun `null rate survives conversion`() {
        val pm = parseServer(serverJson)[2].toPlacemark()
        assertNull(pm.rate)
    }

    // --- Filtering removed marks ---

    @Test
    fun `removed marks are excluded on sync`() {
        val json = """[
            {"id":"1","name":"keep","description":"","lng":44.0,"lat":55.0,"timestamp":1000,"rate":null},
            {"id":"2","name":"drop","description":"","lng":44.0,"lat":55.0,"timestamp":1000,"rate":null,"removed":true}
        ]"""
        val active = parseServer(json).filter { it.removed != true }
        assertEquals(1, active.size)
        assertEquals("keep", active[0].name)
    }

    @Test
    fun `all real server marks are not removed`() {
        val active = parseServer(serverJson).filter { it.removed != true }
        assertEquals(4, active.size)
    }

    // --- Upsert merge logic (mirrors PlacemarkService.upsertPlacemarks) ---

    @Test
    fun `upsert adds new mark to empty list`() {
        val existing = emptyList<Placemark>()
        val incoming = parseServer(serverJson).map { it.toPlacemark() }
        val result = upsert(existing, incoming)
        assertEquals(4, result.size)
    }

    @Test
    fun `upsert updates existing mark by id`() {
        val old = Placemark(
            id = "4c817f59-efbc-4800-8625-fa0676a1f2b3",
            name = "старое имя", latitude = 0.0, longitude = 0.0,
            rate = null, description = "", timestamp = 1L
        )
        val updated = parseServer(serverJson)[0].toPlacemark()
        val result = upsert(listOf(old), listOf(updated))
        assertEquals(1, result.size)
        assertEquals("мельницы", result[0].name)
        assertEquals(1, result[0].rate)
    }

    @Test
    fun `upsert keeps local-only marks not present on server`() {
        val localOnly = Placemark(
            id = "local-only-id", name = "Моя точка",
            latitude = 56.0, longitude = 44.0,
            rate = 5, description = "local", timestamp = 9999L
        )
        val incoming = parseServer(serverJson).map { it.toPlacemark() }
        val result = upsert(listOf(localOnly), incoming)
        assertEquals(5, result.size)
        assertTrue(result.any { it.id == "local-only-id" })
    }

    @Test
    fun `upsert with duplicate incoming keeps last write`() {
        val incoming = listOf(
            parseServer(serverJson)[0].toPlacemark(),
            parseServer(serverJson)[0].toPlacemark().copy(name = "дубликат")
        )
        val result = upsert(emptyList(), incoming)
        assertEquals(1, result.size)
        assertEquals("дубликат", result[0].name)
    }

    // --- Round-trip serialization ---

    @Test
    fun `cyrillic names survive JSON round-trip`() {
        val marks = parseServer(serverJson)
        val json = gson.toJson(marks)
        val back: List<ServerPlacemark> = gson.fromJson(json, listType)
        assertEquals("мельницы", back[0].name)
        assertEquals("охта", back[1].name)
        assertEquals("д Ивановка", back[2].name)
        assertEquals("д Дубрава", back[3].name)
    }

    @Test
    fun `Placemark round-trips through ServerPlacemark without data loss`() {
        val original = parseServer(serverJson)[0].toPlacemark()
        val server = ServerPlacemark(
            id = original.id, name = original.name,
            lat = original.latitude, lng = original.longitude,
            rate = original.rate, description = original.description,
            timestamp = original.timestamp
        )
        val restored = server.toPlacemark()
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.latitude, restored.latitude, 1e-9)
        assertEquals(original.longitude, restored.longitude, 1e-9)
        assertEquals(original.rate, restored.rate)
        assertEquals(original.timestamp, restored.timestamp)
    }

    // Mirrors PlacemarkService.upsertPlacemarks
    private fun upsert(existing: List<Placemark>, incoming: List<Placemark>): List<Placemark> {
        val map = existing.associateBy { it.id }.toMutableMap()
        incoming.forEach { map[it.id] = it }
        return map.values.toList()
    }
}
