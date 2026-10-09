package com.bconf.a2maps_and.navigation

import kotlin.math.*

/** Detects alternatives that follow practically the same roads as an already offered route. */
object RouteSimilarity {
    private const val SAMPLE_METERS = 10.0
    // Narrower than the spacing of typical parallel roads, wider than GPS-free geometry noise.
    private const val TOLERANCE_METERS = 15.0
    private const val SHARED_LIMIT = 0.9
    private const val EARTH_RADIUS = 6371000.0

    fun isDistinct(candidate: Route, offered: List<Route>) = offered.none { similar(candidate, it) }

    fun similar(a: Route, b: Route): Boolean {
        val projection = Projection(a.points.first())
        val samplesA = projection.samples(a.points)
        val samplesB = projection.samples(b.points)
        return sharedFraction(samplesA, Grid(samplesB)) >= SHARED_LIMIT && sharedFraction(samplesB, Grid(samplesA)) >= SHARED_LIMIT
    }

    private fun sharedFraction(samples: List<DoubleArray>, other: Grid) =
        samples.count { other.near(it[0], it[1]) }.toDouble() / samples.size

    private class Projection(origin: GeoPoint) {
        private val originLat = origin.latitude
        private val originLon = origin.longitude
        private val scale = cos(Math.toRadians(originLat)).coerceAtLeast(0.000001)

        fun samples(points: List<GeoPoint>): List<DoubleArray> {
            val result = ArrayList<DoubleArray>()
            var previous = xy(points.first()).also { result.add(it) }
            for (point in points.drop(1)) {
                val next = xy(point)
                val steps = ceil(hypot(next[0] - previous[0], next[1] - previous[1]) / SAMPLE_METERS).toInt()
                for (step in 1..steps) {
                    val t = step.toDouble() / steps
                    result.add(doubleArrayOf(previous[0] + t * (next[0] - previous[0]), previous[1] + t * (next[1] - previous[1])))
                }
                previous = next
            }
            return result
        }

        private fun xy(p: GeoPoint) = doubleArrayOf(
            Math.toRadians(longitudeDelta(p.longitude - originLon)) * EARTH_RADIUS * scale,
            Math.toRadians(p.latitude - originLat) * EARTH_RADIUS)
    }

    /** Spatial hash of densified samples, so long routes avoid pairwise point comparison. */
    private class Grid(samples: List<DoubleArray>) {
        private val cells = HashMap<Long, MutableList<DoubleArray>>()
        init { for (sample in samples) cells.getOrPut(key(cell(sample[0]), cell(sample[1]))) { ArrayList() }.add(sample) }

        fun near(x: Double, y: Double): Boolean {
            val cx = cell(x); val cy = cell(y)
            for (dx in -1..1) for (dy in -1..1) {
                if (cells[key(cx + dx, cy + dy)]?.any { hypot(it[0] - x, it[1] - y) <= TOLERANCE_METERS } == true) return true
            }
            return false
        }

        private fun cell(value: Double) = floor(value / TOLERANCE_METERS).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }
}
