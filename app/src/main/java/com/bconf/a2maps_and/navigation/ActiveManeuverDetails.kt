package com.bconf.a2maps_and.navigation // Or your preferred package

import com.bconf.a2maps_and.routing.Maneuver

data class ActiveManeuverDetails(
    val maneuver: Maneuver,
    val remainingDistanceToManeuverMeters: Double? // Distance from current location to start of this maneuver
)