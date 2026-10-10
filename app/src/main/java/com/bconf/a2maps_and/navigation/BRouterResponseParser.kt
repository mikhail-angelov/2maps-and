package com.bconf.a2maps_and.navigation

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonArray
import kotlin.math.abs
import kotlin.math.roundToInt

object BRouterResponseParser {
    fun parse(raw: String?, destination: GeoPoint): Route {
        if (raw == null) invalid()
        if (raw.length > 750_000) throw RoutingException(RoutingFailure.TOO_LARGE, "Route is too large. Choose a closer destination.")
        if (!raw.trimStart().startsWith("{")) {
            // BRouter errors can contain coordinates/paths. Keep only the diagnostic category.
            val detail = when {
                raw.contains("profile", true) -> "Car profile unavailable."
                raw.contains("segment", true) || raw.contains(".rd5", true) -> "Routing data unavailable."
                raw.contains("timeout", true) -> "Routing timed out. Try a shorter route."
                raw.contains("not found", true) -> "No road route found."
                else -> "BRouter could not calculate this route."
            }
            throw RoutingException(RoutingFailure.SETUP, "$detail Import road segments in Road data, then retry.")
        }
        try {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.get("type")?.asString != "FeatureCollection") invalid()
            val feature = root.getAsJsonArray("features").firstOrNull {
                it.isJsonObject && it.asJsonObject.get("geometry")?.let { geometry ->
                    geometry.isJsonObject && geometry.asJsonObject.get("type")?.asString == "LineString"
                } == true
            }?.asJsonObject ?: invalid()
            val coordinates = feature.getAsJsonObject("geometry").getAsJsonArray("coordinates")
            if (coordinates.size() !in 2..25000) invalid()
            val points = coordinates.map {
                val pair = it.asJsonArray
                if (pair.size() < 2) invalid()
                GeoPoint(latitude = pair[1].asDouble, longitude = pair[0].asDouble).also { point -> if (!point.isValid()) invalid() }
            }
            val length = points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }
            if (length <= 0) invalid()
            val props = feature.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
            return Route(points, destination, number(props?.get("track-length"))?.takeIf { it > 0 } ?: length,
                number(props?.get("total-time")), maneuvers(props?.get("voicehints"), points.size),
                roadSections(props?.get("messages"), points))
        } catch (e: RoutingException) {
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun number(value: JsonElement?): Double? = runCatching { value?.asString?.toDoubleOrNull() }.getOrNull()?.takeIf { it.isFinite() && it >= 0 }

    private fun integer(value: JsonElement?): Int? = runCatching { value?.asString?.toIntOrNull() }.getOrNull()

    private fun maneuvers(value: JsonElement?, pointCount: Int): List<RouteManeuver> {
        if (value?.isJsonArray != true) return emptyList()
        return value.asJsonArray.mapNotNull { entry -> runCatching {
            val row = entry.asJsonArray
            val index = integer(row.getOrNull(0)) ?: return@runCatching null
            if (index !in 1 until pointCount - 1) return@runCatching null
            val type = when (integer(row.getOrNull(1))) {
                1 -> ManeuverType.STRAIGHT; 2 -> ManeuverType.LEFT; 3 -> ManeuverType.SLIGHT_LEFT
                4 -> ManeuverType.SHARP_LEFT; 5 -> ManeuverType.RIGHT; 6 -> ManeuverType.SLIGHT_RIGHT
                7 -> ManeuverType.SHARP_RIGHT; 8 -> ManeuverType.KEEP_LEFT; 9 -> ManeuverType.KEEP_RIGHT
                10, 11, 15 -> ManeuverType.U_TURN; 13, 14 -> ManeuverType.ROUNDABOUT
                12 -> ManeuverType.REJOIN; 16 -> ManeuverType.CONTINUE
                17 -> ManeuverType.EXIT_LEFT; 18 -> ManeuverType.EXIT_RIGHT
                else -> return@runCatching null
            }
            RouteManeuver(index, type, integer(row.getOrNull(2))?.takeIf { it in -100..100 }?.let(::abs) ?: 0)
        }.getOrNull() }.sortedBy { it.pointIndex }.distinctBy { it.pointIndex }
    }

    private fun roadSections(value: JsonElement?, points: List<GeoPoint>): List<RouteRoadSection> = runCatching {
        if (value?.isJsonArray != true || value.asJsonArray.size() < 2) return@runCatching emptyList()
        val rows = value.asJsonArray
        val header = rows[0].asJsonArray.map { it.asString }
        val lon = header.indexOf("Longitude"); val lat = header.indexOf("Latitude"); val tags = header.indexOf("WayTags")
        val distance = header.indexOf("Distance")
        if (lon < 0 || lat < 0 || tags < 0) return@runCatching emptyList()
        val indices = points.indices.groupBy { (points[it].longitude * 1e6).roundToInt() to (points[it].latitude * 1e6).roundToInt() }
        val cumulative = cumulativeMeters(points)
        val sections = mutableListOf<RouteRoadSection>()
        var from = 0
        for (i in 1 until rows.size()) {
            val row = rows[i].asJsonArray
            val key = (integer(row.getOrNull(lon)) ?: return@runCatching emptyList()) to
                (integer(row.getOrNull(lat)) ?: return@runCatching emptyList())
            val candidates = indices[key]?.filter { it >= from }.orEmpty()
            val length = if (distance >= 0) number(row.getOrNull(distance)) else null
            val to = if (i == rows.size() - 1) points.lastIndex else candidates.minByOrNull {
                if (length != null) abs(cumulative[it] - cumulative[from] - length) else it.toDouble()
            } ?: return@runCatching emptyList()
            val wayTags = row.getOrNull(tags)?.asString ?: return@runCatching emptyList()
            if (to > from) sections.add(RouteRoadSection(from, to, speedLimit(wayTags)))
            from = to
        }
        sections
    }.getOrDefault(emptyList())

    private fun speedLimit(raw: String): Int? {
        val tags = raw.trim().split(Regex("\\s+(?=[\\w:]+=)"))
            .filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val directional = if (tags["reversedirection"] == "yes") "maxspeed:backward" else "maxspeed:forward"
        val speed = (tags[directional] ?: tags["maxspeed"])?.replace('_', ' ')?.trim() ?: return null
        val match = Regex("([0-9]{1,3})(?:\\s*(km/h|kmh|kph|mph))?", RegexOption.IGNORE_CASE).matchEntire(speed) ?: return null
        val number = match.groupValues[1].toInt()
        val kmh = if (match.groupValues[2].equals("mph", true)) (number * 1.609344).roundToInt() else number
        return kmh.takeIf { it in 1..300 }
    }

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null
    private fun invalid(): Nothing = throw RoutingException(RoutingFailure.INVALID_RESPONSE, "Routing returned an invalid route. Retry or check Road data.")
}
