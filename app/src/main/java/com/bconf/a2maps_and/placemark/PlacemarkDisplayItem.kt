package com.bconf.a2maps_and.placemark

import com.google.gson.annotations.SerializedName

data class PlacemarkDisplayItem(
    val placemark: Placemark,
    val distanceString: String,
    val distanceInMeters: Float? // Added for sorting
)
