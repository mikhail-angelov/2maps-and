package com.bconf.a2maps_and.navigation

import kotlin.math.abs
import kotlin.math.floor

enum class RoadDataDirection { CURRENT, NORTH, NORTH_EAST, EAST, SOUTH_EAST, SOUTH, SOUTH_WEST, WEST, NORTH_WEST }
data class RoadDataRegion(val name: String, val direction: RoadDataDirection)

/** BRouter files cover five-degree cells named after their south-west corner. */
object RoadDataRegions {
    fun nearby(point: GeoPoint): List<RoadDataRegion> {
        require(point.isValid())
        val longitude = (floor(point.longitude / 5).toInt() * 5).coerceIn(-180, 175)
        val latitude = (floor(point.latitude / 5).toInt() * 5).coerceIn(-90, 85)
        val offsets = listOf(0 to 0, 0 to 1, 1 to 1, 1 to 0, 1 to -1, 0 to -1, -1 to -1, -1 to 0, -1 to 1)
        return offsets.mapIndexedNotNull { index, (dx, dy) ->
            val lat = latitude + dy * 5
            if (lat !in -90..85) return@mapIndexedNotNull null
            val lon = ((longitude + dx * 5 + 540) % 360) - 180
            val name = "${if (lon < 0) "W" else "E"}${abs(lon)}_${if (lat < 0) "S" else "N"}${abs(lat)}.rd5"
            RoadDataRegion(name, RoadDataDirection.values()[index])
        }
    }
}
