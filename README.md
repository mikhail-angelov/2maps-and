# 2Maps-And Navigation App

This is an advanced mapping and navigation application for Android, built with a focus on customizability and offline capabilities. It uses MapLibre for map rendering, allowing for flexible map styling and the use of custom or open map sources. The app provides a rich user experience with features for navigation, placemark management, and GPS track recording.

## Key Features

<table>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-33-52" src="https://github.com/user-attachments/assets/1ca55ad5-a222-4894-b662-f443ab84eb57" />
</td>
<td valign="top">

**Interactive Map:**
  - Built using **MapLibre GL Native**, providing high-performance, hardware-accelerated map rendering.
  - Offline OSM map is supported.
  - Supports custom map styles loaded from assets, allowing for complete visual control (e.g., street, satellite, terrain views).
  - Smooth pan, zoom, and tilt gestures for intuitive map exploration.

</td>
</tr>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-33-52" src="https://github.com/user-attachments/assets/fb8607fa-8c30-4eb5-810d-a9b1e05a8791" />
<img width="160" height="340" alt="2025-10-13_18-33-52" src="https://github.com/user-attachments/assets/f9005611-c873-415b-a658-f928685175e7" />
</td>
<td valign="top">

**Load custom Maps:**
  - Allow to import ofline maps in [https://docs.mapbox.com/help/glossary/mbtiles/](.mbtiles format) with raster or vector tilesets.
  - There are many tools, wich could help you to get or generate mbtiles files, e.g. [https://github.com/systemed/tilemaker](https://github.com/systemed/tilemaker).
  - This is [https://github.com/mikhail-angelov/2maps-loader](it's my util), which help to download raster/vector tiles file for selected region/zoom, from web
  - Supports easy switch between maps, right from main screen.
</td>
</tr>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-35-02" src="https://github.com/user-attachments/assets/28872196-de4a-494d-b0b3-800dfc49680e" />
</td>
<td valign="top">
  
**Turn-by-Turn Navigation:**
  - **Online(unfortunately) navigation:** App process route request/response from [https://github.com/valhalla/valhalla](https://github.com/valhalla/valhalla) server.
  - **Custom Routing Engine:** Implements its own navigation logic, likely using an external routing service like Valhalla via Retrofit.
  - **Off-Route Detection & Rerouting:** Automatically detects when the user has deviated from the path and provides an option to recalculate the route.
  - **Route Visualization:** Draws the calculated navigation path clearly on the map.

</td>
</tr>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-34-37" src="https://github.com/user-attachments/assets/35fbe22d-e528-49e5-889f-a2064c9d8512" />
</td>
<td valign="top">
  
**Placemark Management:**
  - **Create & Save Placemarks:** Long-press anywhere on the map to save a point of interest.
  - **View Placemark Details:** Click on a placemark icon to open a modal with its details.
  - **List & Sort Placemarks:** View a comprehensive list of all saved placemarks, sorted by distance from your current location.
  - **Item Actions:** Swipe a placemark in the list to reveal actions:
    - **Center:** Center the map on the placemark's location.
    - **Navigate:** Start a navigation route to the placemark.
    - **Share:** Share placemark coordinates.
    - **Edit:** (Functionality to be implemented).
    - **Delete:** Remove the placemark.
      
  </td>
</tr>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-35-30" src="https://github.com/user-attachments/assets/03eb169d-a4e1-4c03-b522-1c657de5760a" />
</td>
<td valign="top">
  
**GPS Tracking:**
  - **Record Your Path:** Records the user's GPS location history to create a track.
  - **Track Visualization:** Displays the recorded track as a line on the map.
  - **Clear Track:** Provides an option to clear the currently recorded track from the map.

**Location Services Integration:**
  - **Fused Location Provider:** Utilizes Google's Fused Location Provider (`play-services-location`) and is structured to potentially support others (like Huawei HMS) for accurate and efficient location updates.
  - **Current Location Indicator:** Shows the user's current location on the map.
  - **"Center on Me" Functionality:** A floating action button allows the user to re-center the map on their current GPS location.
    
  </td>
</tr>
<tr>
<td width="180" valign="top">
<img width="160" height="340" alt="2025-10-13_18-35-30" src="https://github.com/user-attachments/assets/4e2e10a5-0fc3-426d-ba08-b4a0a15d6e61" />
</td>
<td valign="top">

**Gas Stations:**
- **Map Layer & Toggle:** Explained that gas stations appear on a separate map layer that can be shown or hidden, with the visibility state being saved.
- **Import from File:** Clarified that gas stations are imported from .geojson files, with logic to prevent duplicate entries.
    - **.geojson** file could be loaded from [https://overpass-turbo.eu/](https://overpass-turbo.eu/) with the following query:
```
node
  [amenity=fuel]
  ({{bbox}});
out;
```
  </td>
</tr>
</table>


### Modern Android Architecture:
  - **MVVM Architecture:** Separates UI logic from business logic using ViewModels (`PlacemarksViewModel`, `NavigationViewModel`, `TrackViewModel`).
  - **Kotlin & Coroutines:** Written entirely in Kotlin, using coroutines and `StateFlow` for managing asynchronous operations and state.
  - **Android Jetpack:**
    - **Navigation Component:** Manages in-app navigation between the map, placemark list, and other screens.
    - **Lifecycle-Aware Components:** Uses `lifecycleScope` and `flowWithLifecycle` to safely observe data streams, preventing memory leaks.
  - **ViewBinding:** Used for safe and efficient access to views.
---

## 📜 License

This project is licensed under the MIT License.
