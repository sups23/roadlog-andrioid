# RoadLog Agent Notes

## Project

- Single-module Android application: `:app`, package `com.example.roadlog`.
- Toolchain: Gradle 8.9, AGP 8.5.2, Kotlin 1.9.22, `compileSdk 34`, `minSdk 26`, and `targetSdk 29`.
- Production Kotlin is under `app/src/main/java/com/example/roadlog/` and uses XML layouts with view binding.
- `MainActivity` owns permissions, trip controls, osmdroid maps, CameraX capture, and service broadcast receivers.
- `LoggerService` is a non-exported foreground service for GPS, accelerometer, gyroscope, rotation sensors, Vosk recognition, and Room persistence.
- `TripHistoryActivity` lists completed trips and supports swipe deletion. `TripDetailActivity` renders route, timeline, cause breakdown, sensor charts, and photos.
- Debug builds register `DebugInitProvider` and may seed demo trips through `DebugSeeder`; production code must not depend on debug data.

## Recording Flow

- Fine location and microphone permissions are required to record. Camera permission is optional and is requested only when photos are enabled.
- Service actions, extras, and broadcast names are defined in `LoggerService.Companion`.
- The service prepares the bundled Vosk model asynchronously, buffers GPS and sensor data, and matches speech using `app/src/main/assets/cause_config.json`.
- A draft `Trip` with status `RECORDING` is created at start. Rows are flushed to Room in chunks of 500. On stop, `finalizeTrip()` writes summary fields and marks the trip `COMPLETED`; only completed trips appear in history.
- `LoggerService.onDestroy()` intentionally does not cancel `serviceScope`, allowing an asynchronous final Room flush to finish.

## Persistence

- `AppDatabase.kt` defines `TripData`, `Trip`, and `TripPhoto` and uses Room schema version 6.
- Explicit migrations cover `1 -> 2 -> 3 -> 4 -> 5 -> 6`.
- Room schema exports are under `app/schemas/`; schema changes require a migration and version increment.
- `TripDao.deleteTripCascade()` removes photos, sensor rows, and the trip transactionally.
- Never delete or replace `app/src/main/assets/model-en-us/`; it is required for Vosk model unpacking.

## Commands

Configure ignored `local.properties` with `sdk.dir` before building. This workspace may use `/workspace/android-sdk`.

```bash
./gradlew assembleDebug
./gradlew lintDebug
./gradlew installDebug
./gradlew clean
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

Focused tests:

```bash
./gradlew testDebugUnitTest --tests "com.example.roadlog.FixtureSmokeTest"
./gradlew connectedDebugAndroidTest --tests "com.example.roadlog.DatabaseSmokeTest"
```

Instrumentation requires a connected device or emulator. If none is available, skip it and report it as not run.

## Test Locations

- JVM fixtures and tests: `app/src/test/java/com/example/roadlog/`.
- Room instrumentation tests: `app/src/androidTest/java/com/example/roadlog/`.
- Tests should not require real GPS, microphone, camera, network access, or the bundled Vosk model unless explicitly marked as device/manual tests.

## Runtime Constraints

- AAPT2 from AGP 8.5.2 is a 64-bit Linux x86_64 binary. On aarch64 hosts without compatible emulation, resource processing may fail; use a standard x86_64 Android development host.
- Disable battery optimization for reliable long trips.
- Map tiles are cached under the app-private files directory.
- Use `adb logcat -s RoadLog:D` for diagnostics.

## Working Tree Rules

- Preserve unrelated user changes and never use destructive Git commands.
- Do not stage `local.properties`, `.gradle/`, build outputs, `.idea/`, the local SDK, generated APK/AAB files, or crash logs.
- Before declaring changes complete, inspect `git status --short`, run `git diff --check`, and review the complete diff.
- Do not commit, amend, or force-push unless explicitly requested.
