# Offline navigation validation — 2026-10-09

Branch: `feature/brouter-offline-navigation`. Changes are uncommitted.
Implementation scope: MVP slices 1–3; visual turn instructions remain post-MVP.

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
