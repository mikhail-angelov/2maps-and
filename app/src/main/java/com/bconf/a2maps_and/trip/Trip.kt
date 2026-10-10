package com.bconf.a2maps_and.trip

import com.bconf.a2maps_and.navigation.GeoPoint
import com.bconf.a2maps_and.navigation.distanceMeters
import java.util.UUID

data class TripPoint(val latitude: Double, val longitude: Double, val name: String, val visited: Boolean = false) {
    val point get() = GeoPoint(latitude, longitude)
}

data class Trip(val id: String, val name: String, val createdAt: Long, val points: List<TripPoint>) {
    val visitedCount get() = points.count { it.visited }

    /** Index of the unvisited point closest to [from], skipping [excluded]; the trip is driven nearest-first. */
    fun nearestIndex(from: GeoPoint?, excluded: Set<Int> = emptySet()): Int? {
        val open = points.indices.filter { !points[it].visited && it !in excluded }
        return if (from == null) open.firstOrNull() else open.minByOrNull { distanceMeters(from, points[it].point) }
    }
}

/**
 * The QR text exported by Mamon: a header line, the trip name, then one `lat,lng,name` line per point.
 * Plain UTF-8 lines instead of JSON keep about 40 named points within one QR code.
 */
object TripCode {
    const val HEADER = "2MAPS-TRIP"
    private const val MAX_POINTS = 500

    fun parse(text: String, now: Long = System.currentTimeMillis()): Trip? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3 || lines[0] != HEADER) return null
        val points = lines.drop(2).take(MAX_POINTS).map { line ->
            val parts = line.split(",", limit = 3)
            val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val lng = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
            if (!GeoPoint(lat, lng).isValid()) return null
            TripPoint(lat, lng, parts.getOrNull(2)?.trim().orEmpty())
        }
        return Trip(UUID.randomUUID().toString(), lines[1], now, points)
    }
}
