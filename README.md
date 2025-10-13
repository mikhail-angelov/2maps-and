# 2Maps-And Navigation App

This is an advanced mapping and navigation application for Android, built with a focus on customizability and offline capabilities. It uses MapLibre for map rendering, allowing for flexible map styling and the use of custom or open map sources. The app provides a rich user experience with features for navigation, placemark management, and GPS track recording.

## Key Features

- **Interactive Map:**
  - Built using **MapLibre GL Native**, providing high-performance, hardware-accelerated map rendering.
  - Supports custom map styles loaded from assets, allowing for complete visual control (e.g., street, satellite, terrain views).
  - Smooth pan, zoom, and tilt gestures for intuitive map exploration.

- **Turn-by-Turn Navigation:**
  - **Custom Routing Engine:** Implements its own navigation logic, likely using an external routing service like Valhalla via Retrofit.
  - **Real-time Guidance:** Displays maneuver instructions, remaining distance, and updates the route dynamically.
  - **Off-Route Detection & Rerouting:** Automatically detects when the user has deviated from the path and provides an option to recalculate the route.
  - **Route Visualization:** Draws the calculated navigation path clearly on the map.

- **Placemark Management:**
  - **Create & Save Placemarks:** Long-press anywhere on the map to save a point of interest.
  - **View Placemark Details:** Click on a placemark icon to open a modal with its details.
  - **List & Sort Placemarks:** View a comprehensive list of all saved placemarks, sorted by distance from your current location.
  - **Item Actions:** Swipe a placemark in the list to reveal actions:
    - **Center:** Center the map on the placemark's location.
    - **Navigate:** Start a navigation route to the placemark.
    - **Edit:** (Functionality to be implemented).
    - **Delete:** Remove the placemark.

- **GPS Tracking:**
  - **Record Your Path:** Records the user's GPS location history to create a track.
  - **Track Visualization:** Displays the recorded track as a line on the map.
  - **Clear Track:** Provides an option to clear the currently recorded track from the map.

- **Location Services Integration:**
  - **Fused Location Provider:** Utilizes Google's Fused Location Provider (`play-services-location`) and is structured to potentially support others (like Huawei HMS) for accurate and efficient location updates.
  - **Current Location Indicator:** Shows the user's current location on the map.
  - **"Center on Me" Functionality:** A floating action button allows the user to re-center the map on their current GPS location.

- **Modern Android Architecture:**
  - **MVVM Architecture:** Separates UI logic from business logic using ViewModels (`PlacemarksViewModel`, `NavigationViewModel`, `TrackViewModel`).
  - **Kotlin & Coroutines:** Written entirely in Kotlin, using coroutines and `StateFlow` for managing asynchronous operations and state.
  - **Android Jetpack:**
    - **Navigation Component:** Manages in-app navigation between the map, placemark list, and other screens.
    - **Lifecycle-Aware Components:** Uses `lifecycleScope` and `flowWithLifecycle` to safely observe data streams, preventing memory leaks.
  - **ViewBinding:** Used for safe and efficient access to views.
