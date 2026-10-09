# BRouter embedded kernel

Pinned engine version: 1.7.9 (MIT).
Source: https://github.com/abrensch/brouter/releases/tag/v1.7.9

`brouter-engine-1.7.9.jar` contains only the router, mapaccess, codec, expressions
and util class packages from the official release `brouter-1.7.9-all.jar`.
Original JAR SHA-256:
`7d0987f3b4ed239a9f08d5699890b946b1a633c33fb499c9deee622943d5a934`.
The curated JAR is 304,508 bytes. The Android application, server, map creator
and unrelated dependencies are excluded. No GitHub Packages token is needed.

The bundled car-vario profile is from 1.7.9. The bundled lookup table is from
upstream tag v1.7.10 (lookup 11, minor 2), matching current road data while
remaining compatible with lookup 11/minor 1 segments. The profile and MIT notice
are under `app/src/main/assets/routing`. Keep the kernel, profile and lookup
versions explicit when updating and run real-road route tests afterward.
