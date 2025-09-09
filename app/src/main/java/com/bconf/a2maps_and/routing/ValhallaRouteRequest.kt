package com.bconf.a2maps_and.routing


data class ValhallaLocation(
    val lat: Double,
    val lon: Double
)

data class ValhallaRouteRequest(
    val locations: List<ValhallaLocation>,
    val costing: String = "auto" // Or other costing models like "pedestrian", "bicycle"
)