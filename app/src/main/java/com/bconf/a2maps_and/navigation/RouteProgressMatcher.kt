package com.bconf.a2maps_and.navigation

import kotlin.math.*

data class RouteMatch(val crossTrackMeters: Double, val alongMeters: Double, val totalMeters: Double)

class RouteProgressMatcher(private val route: Route) {
    private val cumulative = cumulativeMeters(route.points)
    private var lastSegment = 0
    private var progress = 0.0
    private var hasMatch = false
    val totalMeters get() = cumulative.last()

    /** The point on the route geometry [alongMeters] from its start. */
    fun pointAt(alongMeters: Double) = pointAlong(route.points, cumulative, alongMeters)

    fun match(fix: GpsFix): RouteMatch {
        val nearOrigin = distanceMeters(route.points.first(), fix.point) < 100
        fun scan(indices: IntRange): Pair<Int, RouteMatch> {
            var bestIndex = indices.first
            var best = RouteMatch(Double.MAX_VALUE, 0.0, totalMeters)
            var bestScore = Double.MAX_VALUE
            for (i in indices) {
                val a = route.points[i]
                val b = route.points[i + 1]
                val scale = cos(Math.toRadians(fix.point.latitude)).coerceAtLeast(0.000001)
                fun x(p: GeoPoint) = Math.toRadians(longitudeDelta(p.longitude - fix.point.longitude)) * 6371000 * scale
                fun y(p: GeoPoint) = Math.toRadians(p.latitude - fix.point.latitude) * 6371000
                val ax = x(a); val ay = y(a); val dx = x(b) - ax; val dy = y(b) - ay
                val fraction = if (dx * dx + dy * dy == 0.0) 0.0 else (-(ax * dx + ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                val cross = hypot(ax + fraction * dx, ay + fraction * dy)
                val along = cumulative[i] + fraction * (cumulative[i + 1] - cumulative[i])
                val reversePenalty = if (hasMatch) ((progress - along - 35).coerceAtLeast(0.0) * 0.15).coerceAtMost(60.0) else 0.0
                val jumpPenalty = if (hasMatch) ((along - progress - 300).coerceAtLeast(0.0) * 0.05).coerceAtMost(80.0) else 0.0
                val headingPenalty = fix.courseDegrees?.let { course ->
                    val heading = Math.toDegrees(atan2(dx, dy))
                    abs(longitudeDelta(heading - course)) / 180 * 35
                } ?: 0.0
                val initialPenalty = if (!hasMatch && nearOrigin && along > 300) 80.0 else 0.0
                val score = cross + reversePenalty + jumpPenalty + headingPenalty + initialPenalty
                if (score < bestScore) { bestScore = score; bestIndex = i; best = RouteMatch(cross, along, totalMeters) }
            }
            return bestIndex to best
        }
        var result = scan(if (hasMatch) (lastSegment - 40).coerceAtLeast(0)..(lastSegment + 120).coerceAtMost(route.points.size - 2) else 0..route.points.size - 2)
        if (result.second.crossTrackMeters > max(30.0, fix.accuracyMeters * 2.5)) result = scan(0..route.points.size - 2)
        if (result.second.crossTrackMeters <= max(30.0, fix.accuracyMeters * 2.5)) {
            lastSegment = result.first
            progress = result.second.alongMeters
            hasMatch = true
        }
        return result.second
    }
}

class ReroutePolicy {
    private var count = 0
    private var firstAt = 0L
    private var lastStart: Long? = null
    fun resetSamples() { count = 0; firstAt = 0 }
    fun started(now: Long) { lastStart = now; resetSamples() }
    fun shouldReroute(fix: GpsFix, match: RouteMatch, now: Long, busy: Boolean): Boolean {
        if (!fix.isGood(now)) { resetSamples(); return false }
        if (match.crossTrackMeters <= max(30.0, 2.5 * fix.accuracyMeters)) { resetSamples(); return false }
        if (count == 0) firstAt = fix.elapsedMillis
        count++
        return count >= 3 && fix.elapsedMillis - firstAt >= 3000 && !busy && (lastStart == null || now - lastStart!! >= 10000)
    }
}

/** Chooses among reroute candidates the one that continues the current direction of travel. */
object RerouteSelector {
    private const val LOOKAHEAD_METERS = 150.0
    private const val REVERSAL_DEGREES = 150.0
    private const val REVERSAL_PENALTY = 180.0
    /** A candidate must beat the main route by this much, so equal-looking routes keep index 0. */
    private const val CLEAR_MARGIN = 45.0

    /** False when no candidate could be clearly better than [main], so extra candidates are not worth requesting. */
    fun canImprove(main: Route, course: Double?) = course != null && score(main, course) > CLEAR_MARGIN

    /** Index into [candidates] to drive; 0 unless another one clearly continues [course] better. */
    fun select(candidates: List<Route>, course: Double?): Int {
        if (course == null || candidates.size < 2) return 0
        val scores = candidates.map { score(it, course) }
        val best = scores.indices.minBy { scores[it] }
        return if (scores[best] + CLEAR_MARGIN < scores[0]) best else 0
    }

    /**
     * Angle between [course] and the route's heading over its first [LOOKAHEAD_METERS],
     * so a single noisy vertex cannot decide, plus a penalty for an immediate reversal.
     */
    fun score(route: Route, course: Double): Double {
        val cumulative = cumulativeMeters(route.points)
        if (cumulative.last() <= 0) return 0.0
        val ahead = pointAlong(route.points, cumulative, LOOKAHEAD_METERS)
        val deviation = abs(longitudeDelta(bearingDegrees(route.points.first(), ahead) - course))
        val uTurn = route.maneuvers.any { it.type == ManeuverType.U_TURN && cumulative[it.pointIndex] <= LOOKAHEAD_METERS }
        return deviation + if (uTurn || deviation >= REVERSAL_DEGREES) REVERSAL_PENALTY else 0.0
    }
}

class ArrivalDetection {
    private var count = 0
    fun reset() { count = 0 }
    fun update(fix: GpsFix, match: RouteMatch, target: GeoPoint, now: Long): Boolean {
        if (!fix.isGood(now)) { reset(); return false }
        val nearEnd = match.alongMeters >= match.totalMeters * 0.9 && match.totalMeters - match.alongMeters <= 40
        count = if (nearEnd && match.crossTrackMeters <= 30 && distanceMeters(fix.point, target) <= 30) count + 1 else 0
        return count >= 3
    }
}
