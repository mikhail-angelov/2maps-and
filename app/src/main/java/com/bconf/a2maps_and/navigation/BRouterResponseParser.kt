package com.bconf.a2maps_and.navigation

import com.google.gson.JsonElement
import com.google.gson.JsonParser

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
            throw RoutingException(RoutingFailure.SETUP, "$detail Open BRouter, check car-vario and download road segments, then retry.")
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
                number(props?.get("total-time")))
        } catch (e: RoutingException) {
            throw e
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun number(value: JsonElement?): Double? = runCatching { value?.asString?.toDoubleOrNull() }.getOrNull()?.takeIf { it.isFinite() && it >= 0 }
    private fun invalid(): Nothing = throw RoutingException(RoutingFailure.INVALID_RESPONSE, "BRouter returned an invalid route. Check its setup and retry.")
}
