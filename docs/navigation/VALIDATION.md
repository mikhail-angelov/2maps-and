# Offline navigation validation — 2026-10-09

## Current release candidate: v0.0.5

Embedded BRouter and the navigation UI were checked on a Huawei EVE-LX9N
running Android 10. The signed release APK was installed over the existing app,
preserving imported maps and road data. External BRouter is not required.

- Latest JVM checks: **93 debug + 91 release tests**, zero failures/errors.
- Release preparation: Java 17 `./gradlew build` passed tests, lint and APK
  assembly after explicit handling of GPS permission revocation.
- Phone checks: real route calculation, three offered alternatives, selecting
  an alternative, Go, next maneuver, cancel and restoration of normal map controls.
- One-line panel checked in portrait and landscape at the phone's **145% font
  scale**. Remaining distance is readable; ETA is shown only when it fits.
- The Route preview label was removed. The normal position marker was checked
  as a blue dot outside guidance. Android-test sources compile with the new UI.
- GPS jumps and repeated selection refresh after a changed origin were observed.
  These checks do not establish reliable field guidance under unstable GPS.

The sections below record earlier iterations and their checks; their figures
and screenshots do not describe the final UI or its latest test count.

## Earlier implementation

Branch: `feature/brouter-offline-navigation`.
Original scope: MVP slices 1–3; visual turn instructions were added later.

## Checks

JDK 17: `/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home`.
`test`, `assembleDebug`, and `connectedDebugAndroidTest` all completed successfully.

| Suite | Tests | Failures | Skipped |
|---|---:|---:|---:|
| Debug JVM/Robolectric | 73 | 0 | 0 |
| Release JVM | 72 | 0 | 0 |
| Android 11 device integration | 13 | 0 | 0 |

The device was `2maps_android11_clean`, Android **11 / API 30**, with BRouter
**1.7.9**, `car-vario`, and `E40_N55.rd5` / `E45_N55.rd5`. Airplane mode was on;
Wi-Fi and mobile data were disabled before running the integration suite.

Verified behaviors:

- Real local Binder car route: **1,981 m**, 59 geometry points, **64 ms**.
- Real inter-tile route crossing 45°E: **5,513 m**, 58 points, **43 ms**.
- GPS replay through GMS and LocationService decreases remaining distance,
  ignores an isolated spike / poor accuracy, and triggers a stable reroute to
  the same destination.
- GPS recording retains **10 points** through navigation and rerouting;
  Stop navigation keeps recording enabled and does not clear its recorded path.
- Route, destination and track sources survive MBTiles style switching and
  activity recreation.
- Placemark-list navigation returns to the map and starts the offline route.
- Long-press chooser retains Yandex Navigator and OsmAnd and adds BRouter.
- Missing car-profile setup produced a clear recoverable error before the
  profile was installed. Merely installing the external APK was not treated
  as proof that routing worked.

Timings above are warm-provider measurements on an Apple Silicon emulator;
they are not older-phone performance claims.

## Map coverage and screenshots

The repository's existing `planet.mbtiles` is a **Malta** extract, not a
Nizhny Novgorod basemap. The Nizhny Novgorod test therefore deliberately shows
routing and GPX geometry independently of tile coverage. A local Nizhny Novgorod
MBTiles import is needed to display its streets.

[Navigation / reroute / recording screenshot](validation/navigation.png).
The orange geometry is the calculated road route; the recorded path is separate.

A separate manual run on the packaged Malta basemap used `E10_N35.rd5`, actual
emulator GPS input, and **long press → Navigate to → Offline (BRouter)**. In
continued airplane mode it displayed streets and a **1.91 km / ≈4 min** car route.
[Offline route over the packaged Malta basemap](validation/malta.png).
The screenshot also shows recalculation while the simulated fix is off the
road geometry. Navigation was stopped after the manual run; the app remains
installed and open on the Malta map in the local emulator. Available disk space after cleanup: approximately **2.0 GiB**.

## Local emulator setup

The installed SDK tools generated an Android 11 boot-device address pointing at
the cache disk (`a003800`) while QEMU attached the actual system disk at
`a003600`. Android 11 reads the first `boot_devices` kernel argument, so appending
another value did not correct it. Stock emulator startup rebooted before Android
could start.

For this acceptance run, a separate **local copy of Emulator 37.2.12** was made
with APFS clones in `/tmp/2maps-emulator-local`. Its boot-device descriptor was
corrected from `a003800` to `a003600` and the modified local binaries were signed
ad hoc, retaining their hypervisor entitlements. The Android 11 system image,
Android kernel, app APK and BRouter APK were unchanged. This compatibility fix is
only in the local emulator copy; no emulator binaries are included in the repo.

Restart that local test device with:

```bash
env ANDROID_SDK_ROOT=/Users/ma/Library/Android/sdk \
  /tmp/2maps-emulator-local/emulator \
  -avd 2maps_android11_clean -data /tmp/2maps-userdata-raw.img \
  -no-snapshot -no-audio -gpu swiftshader
```

Temporary archives, unused test AVDs, obsolete dependency caches, and intermediate
build files were cleaned up to limit disk use. The APK, verification reports and
screenshots are retained.

## Remaining physical-device acceptance

Real driving, older-phone memory/timing measurements, long oblast-route Binder
limits, and turn-hint quality still require physical-device acceptance. The
integration run confirms short offline routing and rerouting on Android 11; it
does not establish a maximum supported route length or screen-off guidance.

## Standards review

The independent review found no hard architectural or code-smell violations.
Its permission-recovery cadence defect was fixed by explicitly synchronizing the
navigation GPS action after granting precise permission, with a regression test.

## Spec review

The independent review found two defects: cadence after permission recovery and
Retry silently clearing stale-route status when GPS was stale. Both were fixed;
pending Retry now survives GPS loss. A follow-up review confirmed these fixes.


## Huawei GPS follow-up (October 9)

On the connected Huawei EVE-LX9N (Android 10), HMS fused location repeatedly
returned fresh fixes with 215.4 m reported accuracy while the system GPS provider
had fresh fixes with about 5 m accuracy. The existing map accepted the former,
but navigation correctly rejected fixes above 50 m, causing the confusing
waiting state. During navigation the service now switches its single location
subscription to the system GPS provider; Stop restores fused updates.

A stationary device acceptance route was built through native BRouter: 4.67 km,
approximately 8 minutes. Navigation reached ACTIVE, fresh GPS fixes arrived at
one-second intervals with 4.9 m reported accuracy, and the waiting message cleared.
No absolute user coordinates were logged. Diagnostic logging was removed afterward.
The new Robolectric regression verifies the single GPS subscription, its interval,
location publication and removal on Stop. Emulator replay feeds both platform GPS
and fused mocks so that navigation and recording use their real provider paths.
This check does not substitute for physical driving acceptance.


The final GPS follow-up passed `./gradlew test :app:connectedDebugAndroidTest`
with Java 17 and `ANDROID_SERIAL=emulator-5554`: all 13 emulator tests passed.
The route/recording replay now publishes platform GPS fixes continuously at one
second intervals, including across activity recreation and provider changes.

During this run the old MapLibre 11.8.0 library reproduced the known native
emulator crash in `GL2Encoder::s_glDrawElements` while reopening the map. Changing
the emulator GPU backend did not fix it. Updating to MapLibre 11.8.4 resolved the
repro and allowed the full suite to pass; see
[upstream issue #3308](https://github.com/maplibre/maplibre-native/issues/3308).


## Route planning without precise GPS (October 9)

At the user's request, initial planning and explicit Retry now use the same
known position shown on the map, including a restored point or a 215 m accuracy
fix. Tracking, arrival and automatic rerouting retain their precise/fresh GPS
gates. The panel displays Route preview instead of a GPS waiting prompt when a
route exists without tracking-quality GPS. No location still waits for a position.
Regression tests cover coarse/restored positions, missing fine permission, and
a newer displayed point replacing an older precise fix as the route origin.


The final planning check passed 151 JVM tests and all 14 emulator tests,
including an actual BRouter route from a 215 m accuracy map position with no
GPS Settings prompt. On the Huawei, the updated APK displayed Route preview,
4.43 km and approximately 10 minutes while precise GPS was unavailable.
Only Stop navigation was shown; the old GPS waiting prompt was absent.


## Next maneuver and posted speed (October 9)

BRouter 1.7.9 is requested with turnInstructionMode=3. JSON voice hints are
attached to geometry indices; distances use the route geometry and current
projection. Road sections use message endpoint coordinates and explicit WayTags.
Tests verify directional limits, mph conversion, unknown/symbolic limits,
unknown-section clearing, passed maneuvers and malformed optional metadata.
A small public-coordinate city fixture was generated locally with BRouter 1.7.9.

All 163 JVM tests and 14 Android emulator tests passed. The real Binder city
route has maneuver hints and a posted 20 km/h limit; the Android preview test
checks both new labels. The signed APK was installed over the phone application
and showed Route preview (1.09 km, approximately 4 minutes) and
Next: Turn left · 38 m. No numeric limit was shown for the phone's current
matched section, so the Max speed label was hidden. Driving acceptance remains
to be performed.


## Embedded engine acceptance (October 9)

The curated 1.7.9 Java kernel and bundled car-vario/lookup 11.2 data replace the
external application. A private `:routing` service calculates routes and handles
per-request cancellation. The existing parser, guidance and map layer are reused.
Imported road data uses the application data directory and is excluded from backup.

Verification was switched to the connected Huawei at the user's request. The
first emulator attempts failed during test-fixture import because local fixture
filenames had an extra `2maps-` prefix; those fixture names were corrected. No
subsequent emulator UI run was requested/performed. CI prepares canonical names
and world-readable fixture files; routing tests do not skip for absent BRouter.

All 171 debug/release JVM tests passed. The signed release was installed over the
existing phone app. Both road segments were imported through Android's actual
file picker into Road data, which reported Imported 2 road data files.
The external BRouter package was disabled during acceptance: a 1.09 km route,
approximately 4 minutes, was built with Next: Turn left · 38 m.
Main process PID 25894 and routing process PID 27783 were distinct; `ps` reported
about 214 MB and 59 MB resident memory respectively at this check. External BRouter
was re-enabled afterward; it is not used by the new implementation. Map data and
local placemarks/tracks were preserved by the signed update.

The external/early MVP checks above are historical. The current setup is described
in README.md; architecture and focused follow-ups are in ARCHITECTURE.md.
