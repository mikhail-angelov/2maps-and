package com.bconf.a2maps_and.navigation

enum class NavigationState {
    IDLE,                  // Not navigating
    NAVIGATING,            // Actively navigating on-route
    OFF_ROUTE,             // User has deviated significantly from the route
    ROUTE_CALCULATION,     // (Optional) If you want a state while Valhalla is being called
    ROUTE_CALCULATION_FAILED // Valhalla failed or returned no route
}