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
- Precise location and microphone permissions for recording; approximate-only location cannot start a trip.
- Android 13+ requests notification permission on the first recording start. Denial does not block recording, but recording controls remain available in the app rather than the notification drawer.

The sideload app targets SDK 34 and supports the `armeabi-v7a`, `arm64-v8a`, `x86`, and
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

Permission-boundary tests require a dedicated empty install with location and
microphone permissions initially denied. The approximate-location case runs only
on Android 12+ and leaves its test grants in place; reset/uninstall that dedicated
install outside instrumentation before repeating those tests. Tests never revoke
their own process permissions, which can terminate the entire test run.

For target-SDK upgrades, manually verify on Android 11 and Android 14 or newer:
precise/approximate location, denied microphone and notifications, user-initiated
recording, screen-off/background recording, stop/recovery, and both export modes.
Recheck installation with Play Protect enabled; targeting SDK 34 addresses the
older-target warning, not unrelated scanner verdicts.

## Runtime Notes

- Keep `app/src/main/assets/model-en-us/`; it is the bundled Vosk model.
- Speech causes, phrases, variants, and thresholds are defined in
  `app/src/main/assets/cause_config.json`.
- Voice cause commands require one exact `log [CAUSE]` activation, such as
  `log slow lead vehicle`, `log parked car`, `log speed breaker`, `log other turn`, or
  `log unknown`. Extra, ambiguous, unmatched, and low-confidence speech is not
  accepted as an event.
- Current codebook v4 causes are `SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`,
  `CONSTRUCTION`, `TURNING`, `FRICTION`, `MARKET`, `UNKNOWN`,
  `SLOW_LEAD_VEHICLE`, `MERGING`, `LEAD_TURN`, `CROSSING_TURN`, `PARKED_BIKE`,
  `PARKED_CAR`, `DELIVERY_STOP`, and `SPEED_BREAKER`. `TURNING` and `FRICTION`
  have bounded v4 residual definitions; historical v3 meanings remain unchanged.
- Accepted speech creates one provisional primary cause without a real-time GPS
  speed-drop gate. `log speed breaker` is recorded provisionally; RoadLog does
  not automatically validate the study's slowdown-episode rule.
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
