package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class BRouterResponseParserTest {
    private val target = GeoPoint(56.3, 44.0)
    private fun json(coords: String = "[[43.98,56.29,100],[44,56.3,101]]", props: String = "\"track-length\":\"1800\",\"total-time\":\"120\"") =
        """{"type":"FeatureCollection","features":[{"geometry":{"type":"Point","coordinates":[44,56.3]}},{"geometry":{"type":"LineString","coordinates":$coords},"properties":{$props}}]}"""
    @Test fun `reads lon lat elevation numeric strings and extra features`() {
        val route = BRouterResponseParser.parse(json(), target)
        assertEquals(GeoPoint(56.29, 43.98), route.points.first())
        assertEquals(1800.0, route.distanceMeters, 0.0)
        assertEquals(120.0, route.estimatedDurationSeconds!!, 0.0)
    }
    @Test fun `accepts numeric metadata and calculates absent length without inventing time`() {
        assertEquals(120.0, BRouterResponseParser.parse(json(props = "\"total-time\":120"), target).estimatedDurationSeconds!!, 0.0)
        val route = BRouterResponseParser.parse(json(props = ""), target)
        assertTrue(route.distanceMeters > 1500)
        assertNull(route.estimatedDurationSeconds)
    }
    @Test fun `rejects empty short invalid and malformed geometry`() {
        for (raw in listOf(json("[]"), json("[[44,56]]"), json("[[181,56],[44,56]]"), json("[[44,91],[44,56]]"), "{bad", "{}", json("[[44,56],[44,56]]"))) {
            try { BRouterResponseParser.parse(raw, target); fail(raw) }
            catch (e: RoutingException) { assertEquals(RoutingFailure.INVALID_RESPONSE, e.kind) }
        }
    }
    @Test fun `plain provider error is sanitized and actionable`() {
        try { BRouterResponseParser.parse("segment /private/E40_N55.rd5 missing at 43.985,56.292", target); fail() }
        catch (e: RoutingException) {
            assertEquals(RoutingFailure.SETUP, e.kind)
            assertTrue(e.message!!.contains("Import"))
            assertFalse(e.message!!.contains("43.985"))
            assertFalse(e.message!!.contains("/private"))
        }
    }
    @Test fun `null metadata still permits valid route geometry`() {
        val raw = json(props = "").replace("\"properties\":{}", "\"properties\":null")
        val route = BRouterResponseParser.parse(raw, target)
        assertTrue(route.distanceMeters > 1500)
        assertNull(route.estimatedDurationSeconds)
    }
    @Test fun `limits response size`() {
        try { BRouterResponseParser.parse(" ".repeat(750001), target); fail() }
        catch (e: RoutingException) { assertEquals(RoutingFailure.TOO_LARGE, e.kind) }
    }
    @Test fun authenticCityRouteContainsManeuversAndOnlyExplicitLimits() {
        val raw = javaClass.getResource("/navigation/brouter-city.json")!!.readText()
        val route = BRouterResponseParser.parse(raw, target)
        assertEquals(10, route.maneuvers.size)
        assertEquals(ManeuverType.SLIGHT_RIGHT, route.maneuvers[0].type)
        assertEquals(2, route.maneuvers[0].pointIndex)
        assertEquals(20, route.roadSections.first().maxSpeedKmh)
        assertTrue(route.roadSections.any { it.maxSpeedKmh == null })
        assertEquals(route.points.lastIndex, route.roadSections.last().toPointIndex)
    }
    @Test fun directionalLimitsOverrideGeneralAndUnknownValuesStayUnknown() {
        fun limit(tags: String) = BRouterResponseParser.parse(json(props = """"messages":[["Longitude","Latitude","WayTags"],["44000000","56300000","$tags"]]"""), target).roadSections.single().maxSpeedKmh
        assertEquals(40, limit("highway=primary maxspeed=60 maxspeed:forward=40 maxspeed:backward=30"))
        assertEquals(30, limit("reversedirection=yes maxspeed=60 maxspeed:forward=40 maxspeed:backward=30"))
        assertEquals(48, limit("maxspeed=30 mph"))
        for (value in listOf("none", "urban", "RU:urban", "walk", "30;50", "999")) assertNull(limit("maxspeed=$value"))
        assertNull(limit("maxspeed=60 maxspeed:forward=none"))
    }
    @Test fun malformedOptionalGuidanceDoesNotRejectGeometry() {
        val route = BRouterResponseParser.parse(json(props = """"voicehints":[[99999,5,0],[1,999,0],null],"messages":[null]"""), target)
        assertTrue(route.maneuvers.isEmpty())
        assertTrue(route.roadSections.isEmpty())
        assertEquals(2, route.points.size)
    }
}
