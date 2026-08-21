# RoadLog

RoadLog is an Android app for recording and reviewing road trips. It combines
GPS, motion sensors, offline speech recognition, optional photos, and trip
analytics in one local-first logger.

## Features

- Foreground trip recording with GPS location and speed.
- Accelerometer, gyroscope, and rotation sensor capture.
- Offline Vosk speech recognition for configurable road-condition causes.
- Manual cause entry from the main screen.
- Optional automatic and manual CameraX photos with location and timestamps.
- OpenStreetMap route display and cached map tiles.
- Room-backed trip history with route, event timeline, sensor charts, cause
  breakdowns, and photos.
- Debug-only demo trip seeding for manual UI checks.

## Requirements

- Android SDK 34 for compilation.
- Android 8.0 / API 26 or newer.
- A device or emulator with GPS, microphone, and accelerometer support.
- Location and microphone permissions for recording. Camera permission is
  needed only when photos are enabled.

The app targets SDK 29 and supports the `armeabi-v7a`, `arm64-v8a`, `x86`, and
`x86_64` ABIs.

## Build

Set the Android SDK path in `local.properties`, for example:

```properties
sdk.dir=/path/to/android-sdk
```

Then run:

```bash
./gradlew assembleDebug
```

Install on a connected device or emulator with:

```bash
./gradlew installDebug
```

## Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

Instrumentation tests require a connected device or emulator.

## Runtime Notes

- Keep `app/src/main/assets/model-en-us/`; it is the bundled Vosk model.
- Speech causes, phrases, variants, and thresholds are defined in
  `app/src/main/assets/cause_config.json`.
- Voice cause commands require the `log` activation phrase. For example,
  say `log pothole` or `log unclassified`; unrelated speech, unmatched
  words, and low-confidence results are ignored.
- Disable battery optimization for reliable long recordings.
- Diagnostics: `adb logcat -s RoadLog:D`.

### OpenStreetMap Tiles

RoadLog uses the official OSM Standard tile endpoint for interactive map viewing.
The app sends a stable, contactable User-Agent identifying RoadLog and caches tiles
using osmdroid's HTTP cache metadata and seven-day fallback. It does not prefetch,
bulk download, or provide offline tile packs. Both map views display linked OSM
attribution and a map-issue reporting link.

Map traffic contact: `dynosups@gmail.com`. Project support page:
`https://github.com/sups23/roadlog-andrioid`.

Tile endpoint, policy flags, User-Agent, and attribution are centralized in
`MapTileConfiguration.kt`. Map traffic is best-effort because OSM's volunteer-run
tile servers have no availability guarantee.

## Project Layout

```text
app/src/main/java/com/example/roadlog/  Production Kotlin
app/src/main/res/                       Layouts and resources
app/src/main/assets/                    Vosk model and speech configuration
app/src/test/                           JVM tests and fixtures
app/src/androidTest/                    Room/device tests
app/schemas/                            Exported Room schemas
```

## License

No license has been declared for this repository yet.
