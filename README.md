# RoadLog

RoadLog is an offline-first Android research instrument for repeated directional
traversals of one configured Kathmandu corridor. It combines GPS, raw motion
sensors, continuous segmented voice audio, offline speech recognition, structured
event annotation, and trip QA in one local-first logger.

## Features

- Foreground trip recording with GPS location and speed.
- Accelerometer, gyroscope, and rotation sensor capture.
- Explicit `A_TO_B`/`B_TO_A` direction and `MORNING`/`OFF_PEAK`/`EVENING`
  observation-period codes.
- Static study session/corridor IDs plus unique trip, event, media, and audio-segment identities.
- Raw timestamp provenance and Kathmandu local study date.
- Driver-operated, hands-free voice event marking with one canonical primary cause,
  provisional-cause provenance, traffic state, confidence, and source-location review.
- Segmented AAC/M4A source audio from the same microphone stream used by Vosk.
- Offline Vosk speech recognition for configurable road-condition causes.
- `log [CAUSE]` activation grammar with explicit `UNKNOWN` handling and ambiguous-command rejection.
- OpenStreetMap route display and cached map tiles.
- Room-backed trip history with route, event timeline, sensor charts, cause
  breakdowns, structured annotations, QA status, and historical media display.
- Clearly labeled `RESTRICTED_RAW` and `PUBLIC_DEIDENTIFIED` ZIP exports with
  checksums, manifest counts, version metadata, and per-trip runtime cause configuration.
- Post-trip `INCIDENT_OR_BREAKDOWN` exclusion review and researcher-initiated purge.
- Debug-only demo trip seeding for manual UI checks.

## Requirements

- Android SDK 34 for compilation.
- Android 8.0 / API 26 or newer.
- A device or emulator with GPS, microphone, and accelerometer support.
- Location and microphone permissions for recording.

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
  say `log roughness`, `log queue`, or `log unknown`; unrelated speech,
  unmatched words, low-confidence results, and ambiguous commands are ignored.
- Current canonical causes are `SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`,
  `CONSTRUCTION`, `FRICTION`, `TURNING`, `MARKET`, and `UNKNOWN`.
- The collection screen has no camera or manual-cause controls. Legacy photo rows
  remain only for historical compatibility and restricted export/purge handling.
- `RESTRICTED_RAW` may contain precise GPS, audio, transcripts, device metadata,
  and historical media. `PUBLIC_DEIDENTIFIED` uses relative time and excludes
  precise GPS, audio, transcripts, reviewer notes, and device identity.
- Disable battery optimization for reliable long recordings.
- The field screen uses one static study session ID and one static corridor ID;
  neither needs to be entered before a trip.
- The stored period is always explicitly selected. Exact Kathmandu suggestion
  windows remain a field-protocol decision and are not inferred from timestamps.
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
