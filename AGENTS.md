# Project Guidance

## Engineering Rules

- Keep changes simple and focused. Prefer KISS/YAGNI over new abstractions unless there is a concrete current need.
- Preserve existing Android/Kotlin patterns: XML layouts, ViewBinding, Jetpack Navigation for in-app screens, activity-scoped ViewModels where already used.
- Do not revert unrelated user changes in this repo.

## Project Shape

- This is a Kotlin Android app using Gradle, MapLibre, ViewBinding, Jetpack Navigation, Retrofit/Gson, Google/Huawei fused location, and coroutines/flows.
- Main app code is under `app/src/main/java/com/bconf/a2maps_and`.
- In-app screen routing uses AndroidX Navigation and `app/src/main/res/navigation/nav_graph.xml`. Do not confuse this with turn-by-turn navigation features.

## Location And External Navigation

- Location tracking lives in `com.bconf.a2maps_and.location`:
  - `LocationService` owns foreground location updates, last location, GPX recording, recorded path, and zoom persistence.
  - `LocationViewModel` exposes `LocationMode`, last known GPS location, recorded path, and zoom level.
  - `GpxLogger` writes GPX files under internal `filesDir/gpx`.
- External app handoff is still supported and should remain. Use `ExternalNavigationChooser` for routing to apps such as Yandex Navigator or OsmAnd.

## Maps And Assets

- Map rendering is handled by MapLibre. `MapsLayerManager` loads the default `planet.mbtiles` asset or imported MBTiles files.
- Imported maps are copied into internal `filesDir/maps`; keep filename handling sanitized and only accept `.mbtiles`.

## Placemarks, Gas Stations, And Tracks

- `PlacemarkService` stores placemarks and gas stations in internal JSON files and exposes static `StateFlow`s.
- `PlacemarksViewModel` combines placemark/gas-station flows with current location for distance-sorted display items.
- `TrackViewModel` reads GPX files from internal `filesDir/gpx` and exposes selected track points for `TrackLayerManager`.

## Security And Privacy

- Do not enable body-level HTTP logging for auth calls. Login/signup payloads include passwords and responses include tokens.
- Backup rules intentionally exclude auth prefs, location prefs, the Home point (`home_prefs`), trips (`trips.json`), imported maps, GPX tracks, and local placemark/gas-station JSON.
- Keep `AuthRepository.init(this)` in `MainActivity` before auth state is used.

## Build And Verification

- Use Java 17 for Gradle on this machine. Java 25 fails this build with `Unsupported class file major version 69`.
- Verified test command:
  ```bash
  env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew test
  ```
- Plain `./gradlew test` may use the wrong default JDK.
