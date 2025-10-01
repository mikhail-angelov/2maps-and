package com.bconf.a2maps_and.placemark

data class PlacemarkDisplayItem(
    val placemark: Placemark,
    val distanceString: String,
    val distanceInMeters: Float? // Added for sorting
)
