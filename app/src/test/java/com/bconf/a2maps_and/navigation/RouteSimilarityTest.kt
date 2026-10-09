package com.bconf.a2maps_and.navigation

import org.junit.Assert.*
import org.junit.Test

class RouteSimilarityTest {
    private fun route(vararg points: Pair<Double, Double>) =
        points.map { GeoPoint(it.first, it.second) }.let { Route(it, it.last(), 1.0, null) }

    @Test fun `same road with different vertices is a duplicate`() {
        val a = route(56.29 to 43.98, 56.30 to 43.98)
        val b = route(56.29 to 43.98, 56.295 to 43.98, 56.30 to 43.98)
        assertTrue(RouteSimilarity.similar(a, b))
        assertFalse(RouteSimilarity.isDistinct(b, listOf(a)))
    }

    @Test fun `parallel road 40 m away is distinct`() {
        val a = route(56.29 to 43.98, 56.30 to 43.98)
        val b = route(56.29 to 43.98, 56.2902 to 43.98065, 56.2998 to 43.98065, 56.30 to 43.98)
        assertFalse(RouteSimilarity.similar(a, b))
    }
}
