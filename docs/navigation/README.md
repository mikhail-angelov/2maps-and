# Offline car navigation

2Maps calls the separately installed BRouter Android Binder service. Routing does
not use HTTP or read BRouter storage. Map tiles and road routing data are separate.
The implementation covers MVP slices 1–3 of the October 8, 2026 specification;
turn instructions and voice guidance remain outside this MVP.

## Setup

1. Install [BRouter](https://f-droid.org/packages/btools.routingapp/) and launch it
   once to initialize its base directory.
2. Download road segments in BRouter's Download Manager. For Nizhny Novgorod city,
   start with `E40_N55.rd5`. Trips outside that tile need adjacent segments.
3. Check that BRouter has `profiles2/car-vario.brf`. The tested APK needed the
   profile installed manually. The [official 1.7.9 release ZIP](https://github.com/abrensch/brouter/releases/download/v1.7.9/brouter-1.7.9.zip)
   contains `profiles2/car-vario.brf` and `lookups.dat`; copy the matching profiles
   to `brouter/profiles2` inside the base directory selected in BRouter.
4. Allow precise location in 2Maps and enable GPS.
5. Long press the map, select **Navigate to → Offline (BRouter)**. The same choice
   is available on a placemark. Wait for a fresh fix (≤15 seconds, ≤50 m accuracy).

The orange route and target marker are separate from GPX tracks. The panel shows
remaining geometric distance and approximate travel time. Stop clears navigation
and restores the normal GPS interval without changing recording/follow modes.
Navigation state survives screen recreation while the process remains alive.
Navigation is supported with the map visible; screen-off/background guidance and
recovery after process death are outside the MVP.

## Verification

Use Java 17 on this machine:

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew assembleDebug
```

The device integration tests need BRouter initialized with the car profile and
`E40_N55.rd5`. Grant precise permission before running, and disable Wi-Fi/mobile
data after setup to verify offline execution:

```bash
adb shell pm grant com.bconf.a2maps_and android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.bconf.a2maps_and android.permission.ACCESS_COARSE_LOCATION
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew connectedDebugAndroidTest
```

`OfflineNavigationTest` checks a real Binder road route, the long-press chooser,
route/track layer coexistence, MBTiles style switching, activity recreation and
Stop with recording still enabled. The BRouter-dependent tests skip when the
external APK is absent; a meaningful routing acceptance run requires it present.
Controller tests cover late replies, destination changes, retries, permission
recovery, GPS loss and reroute failures. Geometry/policy tests cover segment
projection, high latitude, antimeridian, parallel roads, jitter and arrival.

Physical driving, inter-tile trips, and the maximum usable Binder reply size on
an older phone still require device acceptance. A too-large reply fails visibly
and suggests a closer destination; it never falls back to a network router.

## Upstream interface

The AIDL interface is copied from [BRouter's MIT-licensed source](https://github.com/abrensch/brouter/blob/master/brouter-routing-app/src/main/aidl/btools/routingapp/IBRouterService.aidl).
Its notice is retained in [BRouter-LICENSE.txt](BRouter-LICENSE.txt).

Local Android 11 results, screenshots and setup details: [VALIDATION.md](VALIDATION.md).
