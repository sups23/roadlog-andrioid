# RoadLog Agent Notes

## Project

- Single-module Android app: `:app`; all production Kotlin is under `app/src/main/java/com/example/roadlog/`.
- `MainActivity` owns the UI, map, permissions, and CameraX capture; `LoggerService` owns foreground recording, GPS/sensors, Vosk, and Room persistence.
- UI/service communication uses broadcasts and action/extra constants defined in `LoggerService`; receivers are registered as `RECEIVER_NOT_EXPORTED`.
- Recording data is buffered in memory and flushed asynchronously when stopping. `LoggerService` inserts the `Trip` summary first, then tags `TripData` rows with its generated ID.
- `AppDatabase.kt` is Room schema version 5 with explicit migrations `1 -> 2 -> 3 -> 4 -> 5`; schema changes require a migration and version update.
- `TripHistoryActivity` and `TripDetailActivity` read Room data for trip summaries, route/timeline views, charts, and photos. Map tiles are cached under the app-private files directory.

## Commands

- Configure `local.properties` with `sdk.dir`; the repository-local SDK is `/workspace/android-sdk`.
- Build: `./gradlew assembleDebug`
- Lint: `./gradlew lintDebug`
- Install on a connected device/emulator: `./gradlew installDebug`
- Clean generated build output: `./gradlew clean`
- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests (requires emulator/device): `./gradlew connectedDebugAndroidTest`
- Single test class: `./gradlew testDebugUnitTest --tests "com.example.roadlog.FixtureSmokeTest"`
- Single instrumented class: `./gradlew connectedDebugAndroidTest --tests "com.example.roadlog.DatabaseSmokeTest"`
- Test fixtures are in `app/src/test/java/com/example/roadlog/TestFixtures.kt`; database fixtures are in `app/src/androidTest/java/com/example/roadlog/DatabaseFixtures.kt`.
- If no emulator is connected, skip instrumented tests; do not claim they passed.

## Environment note

- AAPT2 from AGP 8.5.2 is a 64-bit Linux x86_64 binary. On aarch64 hosts without qemu-user-static, resource processing fails with `AAPT2 ... Daemon startup failed`. The test foundation and fixtures are syntactically valid Kotlin but cannot be compiled end-to-end in this environment. Build on a standard x86_64 Android development host.

## Runtime Constraints

- Never delete `app/src/main/assets/model-en-us/`; Vosk unpacks this bundled model into app storage on first run.
- `app/src/main/assets/cause_config.json` is the source of truth for speech grammar, cause labels, variants, and matching thresholds; the UI and recognizer load it at runtime.
- Recording requires fine location and microphone permissions; camera permission is additionally required for optional automatic/manual photos. Battery optimization should be disabled for reliable long trips.
- Use `adb logcat -s RoadLog:D` for app/service diagnostics.
- `LoggerService.onDestroy()` intentionally leaves its flush coroutine scope active so the asynchronous Room write can finish.
