# Offline car routing inside 2Maps

BRouter 1.7.9's Java kernel is bundled in the APK. LocalRoutingClient implements
the existing RoutingEngine Interface and calls the private EmbeddedRoutingService
in the app's `:routing` process. No external BRouter application, GBox, HTTP
router or account is needed for route calculation. The existing parser, matcher,
arrival/reroute policy, maneuvers and map layers are reused.

## Setup

1. Open **Menu → Road data → Download for my location**. The app uses the last
   known map position to select the current five-degree region. You can also
   select neighbouring regions before starting the download; only the current
   one is selected by default. Downloads show progress and can be cancelled.
   The app must stay running; leaving the Road data screen cancels its download.
   Official files: https://brouter.de/brouter/segments4/ . Nizhny Novgorod uses
   `E40_N55.rd5`; routes east of 45° longitude may also need `E45_N55.rd5`.
2. Alternatively, open **Menu → Road data → Import .rd5 files** and select one or more files.
   Imports validate names and file integrity, then replace files atomically.
   Road data is stored in private `filesDir/routing/segments4`. Existing files
   survive failed imports/downloads. Both methods copy the bytes into app storage,
   rather than keeping links to Downloads. Once import finishes successfully,
   deleting the original files or clearing Downloads does not affect routing.
   Profiles/lookups are installed from bundled assets.
3. Import a `.mbtiles` basemap through **Maps** when needed. Road graph files
   and map tiles serve different purposes. The built-in basemap covers Malta.
4. Long press the map or open a placemark and choose **Navigate to → Offline**.
   Initial planning and explicit Retry start at the position shown on the map,
   including a restored/coarse point. Precise GPS is not required to draw a route.
   A missing segment is reported by filename with a **Road data** action.

Before starting, the app offers the main route and up to two distinct alternatives
when available. Select a route on the map or in its card, then press **Go**.
Alternative calculations do not block starting the already available main route.

The top card shows the next maneuver and distance to it. The compact bottom
panel keeps remaining time/distance on one line, with arrival time when space
permits, a thin progress bar and a right-hand **×** to stop. A separate sign shows numeric
posted speed limits when present. Roundabouts include supplied exit numbers.
Unknown/symbolic speed limits remain hidden; profile estimated speeds are not
shown as legal limits. Direction-specific tags override the general limit.

Preview projects the displayed position onto the planned route for road details.
Progress, arrival and automatic rerouting still require fixes ≤50 m and ≤15 s
old. Navigation uses one system GPS subscription at a one-second interval;
Stop restores normal Google/Huawei fused updates and preserves recording mode.
Cancel/Stop terminates pending computation; new destinations replace old requests.
The map and recording stay in the main process if the routing worker fails.
The traversed route is muted and the remaining route highlighted. The position
marker is a blue dot outside guidance and an arrow during guidance when the
course is known. Background/screen-off guidance, voice and recovery after
process death remain outside the current implementation.

## Verification

Use Java 17 on this machine:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew test
```

Android routing tests need fixture files, but no external BRouter APK:

```bash
adb shell mkdir -p /data/local/tmp/2maps-routing-test
adb push E40_N55.rd5 E45_N55.rd5 /data/local/tmp/2maps-routing-test/
adb shell chmod 644 /data/local/tmp/2maps-routing-test/E40_N55.rd5 /data/local/tmp/2maps-routing-test/E45_N55.rd5
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew connectedDebugAndroidTest
```

Tests import these streams through RoutingDataStore. CI downloads the same two
fixture tiles before the Android suite, rather than skipping tests for a missing
external app. The test folder is configurable through instrumentation argument
`roadDataDir`. GPS mocks/replay belong on a test device.

## Dependency and architecture

Pinned JAR provenance/license: [third_party/brouter](../../third_party/brouter/README.md).
Focused architecture choices and proposed follow-ups: [ARCHITECTURE.md](ARCHITECTURE.md).
Local acceptance history and current embedded results: [VALIDATION.md](VALIDATION.md).
