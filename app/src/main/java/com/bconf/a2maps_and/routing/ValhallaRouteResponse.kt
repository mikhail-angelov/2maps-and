package com.bconf.a2maps_and.routing


data class ValhallaRouteResponse(
    val trip: Trip?
)

data class Trip(
    val locations: List<TripLocation>?,
    val legs: List<Leg>?,
    val summary: TripSummary?,
    val status: Int?,
    val status_message: String?
)

data class TripLocation(
    val type: String?,
    val lat: Double?,
    val lon: Double?,
    // Add other fields like side_of_street, original_index if needed
)

data class Leg(
    val maneuvers: List<Maneuver>?,
    val summary: LegSummary?,
    val shape: String? // This is the encoded polyline
)

data class Maneuver(
    val type: Int?,
    val instruction: String?,
    val time: Double?,
    val length: Double?, // In kilometers (based on your example response "units":"kilometers")
    val verbal_pre_transition_instruction: String?, // Or other verbal instructions
    val begin_shape_index: Int?,
    val end_shape_index: Int?
    // Add other maneuver fields as needed
)

data class TripSummary(
    val time: Double?, // Total time in seconds
    val length: Double? // Total length in kilometers
    // Add other summary fields
)

data class LegSummary(
    val time: Double?,
    val length: Double?
    // Add other leg summary fields
)