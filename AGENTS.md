# RoadLog — Agent Notes

Single-module Android app (`:app`) for in-vehicle passenger-annotated micro-delay logging.

## Repo layout

- `app/src/main/java/com/example/roadlog/` — all Kotlin source.
  - `MainActivity.kt` — launch UI, starts/stops `LoggerService`, receives broadcasts, and supports automatic (every cause trigger) and manual photo capture.
  - `LoggerService.kt` — foreground service; GPS 1 Hz, accelerometer/gyroscope/rotation-vector ~50 Hz, Vosk speech, Room flush.
  - `TripHistoryActivity.kt` / `TripDetailActivity.kt` — saved trips list and map/timeline detail view; `TripDetailActivity` also charts speed, vertical roughness, lateral/longitudinal acceleration, yaw rate, and displays bump photos with timestamps and a "Show on map" marker.
  - `VoskSpeechRecognizer.kt` — offline Vosk model loading + direct `AudioRecord` recognition loop.
  - `FuzzyCauseMatcher.kt` — Levenshtein fuzzy matching of spoken text to `CauseCode` values.
  - `AppDatabase.kt` — Room database (`roadlog_database`, version 5, migrations 1→2, 2→3, 3→4, and 4→5).
  - `DataModels.kt` — typed in-memory models and `CauseCode` enum.
- `app/src/main/assets/model-en-us/` — bundled Vosk English model (~40 MB). Unpacked on first run by `StorageService`.
- `research/thesis/android-app.md` — design plan / spec. **The current code differs from the plan**: trip history with Room exists; CSV export described in the plan is not implemented.
- `research/` — thesis documentation and reference literature; not part of the app build.

## Build

- Gradle wrapper 8.9, AGP 8.5.2, Kotlin 1.9.22, `compileSdk 34`, `minSdk 26`, `targetSdk 29`.
- Room uses `kapt`; view binding is enabled.
- Common commands:
  - `./gradlew assembleDebug`
  - `./gradlew clean`
  - `./gradlew installDebug` (needs an attached device/emulator)
  - `./gradlew lintDebug`
- No unit tests or Android instrumented tests exist.

## SDK setup

`local.properties` must contain a valid `sdk.dir`. A repo-local SDK is provided at `/workspace/android-sdk` (`android-34`, build-tools `34.0.0`). If you use it:

```properties
sdk.dir=/workspace/android-sdk
```

`local.properties` is gitignored.

## Architecture notes

- `LoggerService` is started with `startForegroundService()` and declared with `foregroundServiceType="location|microphone|camera"`.
- UI ↔ service communication uses broadcast Intents whose actions and extras are defined in `LoggerService`. `MainActivity` registers receivers with `RECEIVER_NOT_EXPORTED`.
- While recording, data lives in in-memory buffers (`gpsBuffer`, `accelBuffer`, `eventBuffer`). On stop, the service flushes to Room and inserts a `Trip` summary row.
- The single `TripData` table stores GPS points, accelerometer points, and cause events via nullable columns. As of version 3, rows are tagged with the generated `Trip` id, and accelerometer rows keep their raw nanotime alongside a wall-clock-millisecond timestamp.
- `LoggerService` records `startNanoTime`/`endNanoTime` and flushes the `Trip` summary first so all rows can be tagged with the generated `tripId`.
- `MIGRATION_2_3` performs a best-effort backfill of old accelerometer rows: it converts their raw nanotime `timestamp` to wall-clock milliseconds and assigns them to the correct trip by using the old insertion order (accel rows were inserted before GPS rows). Trips that have no GPS rows cannot be backfilled.
- osmdroid map tiles are cached in the app's private files dir; `TripDetailActivity` plots the route and color-codes it by roughness.
- `TripDetailActivity` uses MPAndroidChart to show speed, vertical roughness, lateral/longitudinal acceleration, and yaw-rate line charts.
- `SensorFusion.kt` rotates raw accelerometer and gyroscope data into a world frame using the game-rotation-vector sensor, producing vertical, lateral, longitudinal, and yaw-rate metrics.

## Gotchas

- Do not delete `assets/model-en-us/`; the Vosk model is bundled there and unpacked to app storage at runtime.
- `FuzzyCauseMatcher.findBestMatch()` default threshold is `0.7` in code. The design doc says `0.5`; trust the code.
- `AndroidManifest.xml` still declares `package="com.example.roadlog"` and `android:extractNativeLibs="true"`. AGP 8.5 prints warnings that these should be removed/moved but they are non-fatal.
- `requestLegacyExternalStorage` is **not** declared, and the app does not write to public `Downloads/RoadLog/` despite the design doc describing CSV export there.
- `LoggerService.onDestroy()` deliberately does **not** cancel `serviceScope`, so the background Room flush coroutine can finish.
- Runtime requirements: `ACCESS_FINE_LOCATION`, `RECORD_AUDIO`, foreground-service permissions, and the user must disable battery optimization for reliable trips.

## Logging / debugging

- All major components log with tag `RoadLog`. Use `adb logcat -s RoadLog:D`.

## Environment note

- AAPT2 from AGP 8.5.2 is a 64-bit Linux binary. If the build fails with `AAPT2 ... Daemon startup failed` and a missing `/lib64/ld-linux-x86-64.so.2` error, the current environment lacks 64-bit glibc support for the Android toolchain. Build on a standard x86_64 Android development host.
