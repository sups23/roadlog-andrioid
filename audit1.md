# RoadLog Android Application Technical Audit

> **Historical snapshot:** This audit records the pre-migration implementation and
> is not the current source of truth. Current behavior is documented in
> `AGENTS.md`, `docs/field-protocol.md`, `docs/data-dictionary.md`, and
> `research/thesis/android-app.md`; the current collector is voice-only with
> canonical single-primary causes, no current photography, and Room schema 8.

**Purpose:** Read-only technical audit for thesis methodology documentation.

**Audit basis:** Static inspection of source code, Gradle configuration,
`AndroidManifest.xml`, Room entities and migrations, export code, documentation,
tests, and retained project files. No physical-device test, build, installation,
release build, or runtime measurement was performed for this audit.

Status labels used below:

- **CONFIRMED FROM CODE**: directly implemented or declared in the inspected source.
- **CONFIRMED FROM DOCUMENTATION**: stated in repository documentation, without being treated as runtime proof.
- **USER/EXTERNAL CLAIM ONLY**: proposed, historical, or informal material not verified as an executed test.
- **NOT FOUND**: no implementation or retained evidence was located during this audit.

## 1. Application Identity

### CONFIRMED FROM CODE

| Item | Value | Evidence |
|---|---|---|
| Application/display name | `RoadLog` | `app/src/main/res/values/strings.xml:3` |
| Package/application ID | `com.example.roadlog` | `app/build.gradle:8-16`; `app/src/main/AndroidManifest.xml:2-3` |
| Gradle namespace | `com.example.roadlog` | `app/build.gradle:8` |
| Version name | `1.0` | `app/build.gradle:15-16` |
| Version code | `1` | `app/build.gradle:15-16` |
| Compile SDK | `34` | `app/build.gradle:9` |
| Minimum SDK | `26` / Android 8.0 | `app/build.gradle:13`; `README.md:29-38` |
| Target SDK | `29` | `app/build.gradle:14`; `README.md:37-38` |
| ABIs | `armeabi-v7a`, `arm64-v8a`, `x86_64`, `x86` | `app/build.gradle:20-22` |
| Android Gradle Plugin | `8.5.2` | `build.gradle:2-10` |
| Kotlin | `1.9.22` | `build.gradle:2-10` |
| Gradle wrapper | `8.9` | `gradle/wrapper/gradle-wrapper.properties:1-7` |
| Java/Kotlin JVM target | Java 8 / JVM target 1.8 | `app/build.gradle:32-39` |

### CONFIRMED FROM DOCUMENTATION

- The README describes RoadLog as an offline-first Android research instrument
  for repeated directional traversals of one configured Kathmandu corridor.
  `README.md:3-6`
- The README states that a device or emulator with GPS, microphone, and
  accelerometer support is required. `README.md:29-35`

### NOT FOUND

- A release version different from `1.0`/version code `1`.
- A separate build identifier, Git commit identifier, or thesis-protocol build
  identifier tied to a validation run.

## 2. Data Versions

### CONFIRMED FROM CODE

| Item | Value | Evidence |
|---|---|---|
| Room database version | `7` | `app/src/main/java/com/example/roadlog/AppDatabase.kt:1259-1273` |
| Database name | `roadlog_database` | `app/src/main/java/com/example/roadlog/AppDatabase.kt:1281-1287` |
| Per-trip schema version | `7` for newly created trips | `app/src/main/java/com/example/roadlog/LoggerService.kt:837-864` |
| Cause codebook version | `2` | `app/src/main/java/com/example/roadlog/ResearchValidation.kt:3-14`; `app/src/main/assets/cause_config.json:2` |
| Runtime cause configuration version | `2` | `app/src/main/assets/cause_config.json:1-8` |
| Sensor-profile version | `1` | `app/src/main/java/com/example/roadlog/LoggerService.kt:123-128`, `857-863` |
| Export-format version | No explicit version field found | Export implementation: `app/src/main/java/com/example/roadlog/ResearchExporter.kt:77-301` |
| Protocol/configuration version | No formal protocol version field found | `app/src/main/java/com/example/roadlog/ResearchValidation.kt:3-70`; `LoggerService.kt:837-864` |

### Migration history

| Migration | Implemented change | Evidence |
|---|---|---|
| 1 → 2 | Creates the `trips` table with basic trip summary fields | `AppDatabase.kt:786-804` |
| 2 → 3 | Adds `tripId`, `rawTimestamp`, trip monotonic start/end fields; performs best-effort legacy accelerometer, GPS, and event ownership/time backfill | `AppDatabase.kt:806-943` |
| 3 → 4 | Adds accelerometer, gyroscope, and rotation dimensions | `AppDatabase.kt:945-957` |
| 4 → 5 | Creates `trip_photos` and its trip index | `AppDatabase.kt:959-975` |
| 5 → 6 | Adds trip status and a `trip_data.tripId` index | `AppDatabase.kt:977-982` |
| 6 → 7 | Adds source timestamp/provenance fields, trip research metadata, photo provenance, event/secondary-cause/sensor/QA/audio/annotation/audit tables and indexes | `AppDatabase.kt:984-1256` |

The v6→v7 migration generates UUIDs for legacy trips with empty UUIDs and
backfills static session/corridor identifiers, but does not reconstruct all new
research fields from historical data. `AppDatabase.kt:1004-1035`

### CONFIRMED FROM DOCUMENTATION

- Exported Room schema files were found for versions 6 and 7.
  `app/schemas/com.example.roadlog.AppDatabase/6.json:1-9` and
  `app/schemas/com.example.roadlog.AppDatabase/7.json:1-10`
- Documentation says v7 has not shipped to a device and remains a pre-pilot
  migration boundary. `docs/research-data-audit.md:30-31`

### NOT FOUND

- A machine-readable export-format version.
- A formal versioned field protocol/configuration document.
- Exported copies of the exact runtime `cause_config.json` content, including
  phrases, variants, activation phrase, and thresholds.

## 3. GPS/Location Configuration

### CONFIRMED FROM CODE

| Item | Value | Evidence |
|---|---|---|
| API/provider | Android `LocationManager`; `GPS_PROVIDER` and `NETWORK_PROVIDER` | `app/src/main/java/com/example/roadlog/LoggerService.kt:350-351`, `685-703` |
| Requested update interval | `1000 ms` | `LoggerService.kt:689-695` |
| Minimum update interval | No separate minimum interval; the `LocationManager.requestLocationUpdates` call uses the 1,000 ms interval parameter | `LoggerService.kt:689-695` |
| Minimum update distance | `0f` metres | `LoggerService.kt:689-695` |
| Accuracy/priority | No explicit accuracy or priority setting; provider-specific `LocationManager` requests are used | `LoggerService.kt:685-703` |
| Provider registration | Attempts both providers; a successful registration of either sets `locationProviderRegistered` | `LoggerService.kt:685-706` |
| Location timestamp | `Location.time` epoch milliseconds when positive; otherwise callback wall time | `LoggerService.kt:140-145` |
| GPS fields stored | Latitude, longitude, speed in km/h, source epoch time, source elapsed-realtime ns, callback epoch time, provider, horizontal accuracy, speed-valid flag, speed accuracy, bearing, bearing accuracy, altitude, source type | `LoggerService.kt:137-166`, `930-950`; `AppDatabase.kt:20-52` |
| Distance calculation | Consecutive coordinates are passed to `Location.distanceBetween`; distances over 1 m are accumulated | `LoggerService.kt:169-175` |
| Requested versus observed rates | Requested interval is configured as 1,000 ms; observed GPS counts, intervals, coverage, and gaps are computed after collection | `LoggerService.kt:689-695`, `1155-1227`; `AppDatabase.kt:242-281` |
| Provider status | `gpsLocked` is true only when the latest callback provider is `GPS_PROVIDER` | `LoggerService.kt:137-145` |

### Behaviour and limitations

- GPS permission failure or unavailable provider registration sets a GPS
  interruption flag and may continue recording without GPS. `LoggerService.kt:697-706`
- Disabling the GPS provider clears `gpsLocked` and sets the GPS interruption
  flag. `LoggerService.kt:178-183`
- An empty final GPS stream also sets the interruption flag. `LoggerService.kt:1155-1164`
- No maximum location-fix age rule is applied when assigning the latest location
  snapshot to an event. The event stores the calculated fix age, but does not
  reject stale fixes. `LoggerService.kt:1041-1057`, `1519-1538`
- No watchdog was found for an enabled provider that stops producing callbacks.
  `LoggerService.kt:178-187`, `1155-1178`

### Contradiction

`gpsLocked` is presented as a GPS lock indicator, but network-provider locations
are accepted and persisted. A network callback makes `gpsLocked` false even
though a valid location row may have been stored. `LoggerService.kt:137-145`,
`685-703`

### NOT FOUND

- An observed GPS sampling rate from a physical device.
- A fused-location priority or explicit accuracy request.
- A documented stale-fix rejection threshold.

## 4. Motion Sensors

### Sensor configuration

| Stream | Exact Android type | Required/optional behaviour | Requested configuration | Evidence |
|---|---|---|---|---|
| Accelerometer | `Sensor.TYPE_ACCELEROMETER` | Declared required hardware; runtime registration failure sets interruption but recording continues | `SensorManager.SENSOR_DELAY_GAME`; metadata nominal period `20,000 us` | `AndroidManifest.xml:17-20`; `LoggerService.kt:352-355`, `709-719`, `870-893` |
| Gyroscope | `Sensor.TYPE_GYROSCOPE` | Not declared as required hardware; runtime unavailable/failed registration sets interruption and recording continues | `SensorManager.SENSOR_DELAY_GAME`; metadata nominal period `20,000 us` | `LoggerService.kt:353`, `720-730`, `870-893` |
| Orientation | `Sensor.TYPE_GAME_ROTATION_VECTOR`, falling back to `Sensor.TYPE_ROTATION_VECTOR` | Not declared as required hardware; runtime unavailable/failed registration sets interruption and recording continues | `SensorManager.SENSOR_DELAY_GAME`; metadata nominal period `20,000 us` | `LoggerService.kt:354-355`, `731-741`, `870-893` |

### Requested versus observed rates

- `SENSOR_DELAY_GAME` is the requested Android sampling profile. It is not a
  guaranteed 50 Hz observed rate. `LoggerService.kt:709-741`
- The metadata records `selectedProfile = "SENSOR_DELAY_GAME"` and
  `requestedPeriodUs = 20_000`. `LoggerService.kt:879-893`
- Quality metrics later calculate effective accelerometer, gyroscope, and
  rotation frequencies from persisted timestamps using `expectedFrequencyHz =
  50.0`. This is an analytical expectation, not evidence that 50 Hz occurred.
  `LoggerService.kt:1165-1168`
- Observed sample counts, effective Hz, median intervals, longest gaps, duplicate
  timestamps, and non-monotonic timestamps are calculated and stored in
  `TripQuality`. `LoggerService.kt:1155-1227`; `AppDatabase.kt:242-281`
- No maximum reporting latency is passed to `registerListener`; no explicit
  batching-latency configuration was found. `LoggerService.kt:709-741`

### Stored values and units

- Accelerometer rows store raw `x`, `y`, and `z` values; Android's sensor
  contract supplies these in m/s². `LoggerService.kt:190-209`; `AppDatabase.kt:27-29`
- Gyroscope rows store raw angular-rate `x`, `y`, and `z` values; Android's
  sensor contract supplies these in rad/s. `LoggerService.kt:214-230`;
  `AppDatabase.kt:30-32`
- Rotation rows store raw `x`, `y`, `z`, and `w` quaternion/vector components.
  `LoggerService.kt:233-251`; `AppDatabase.kt:33-36`
- Motion rows also store raw `SensorEvent.timestamp` nanoseconds, derived
  alignment epoch milliseconds, callback epoch milliseconds, Android sensor type,
  Android sensor accuracy, and source type. `LoggerService.kt:961-1024`;
  `AppDatabase.kt:38-52`

### Availability and transformation

- If a sensor is unavailable, the service sets `sensorInterruptionDetected` and
  broadcasts a warning; it does not abort the entire trip. `LoggerService.kt:709-741`
- If registration returns false, the same interruption path is used. `LoggerService.kt:710-737`
- Orientation is not transformed into a different coordinate frame. The only
  derived operation is calculation of `w` when the event has fewer than four
  values: `sqrt(1 - x² - y² - z²)` when the sum of squares is below 1, otherwise
  `0`. `LoggerService.kt:233-260`

### NOT FOUND

- Device-observed rates, latency, calibration accuracy, or orientation-frame
  validation on a physical device.
- A maximum reporting latency value.

## 5. Timestamp Handling

### CONFIRMED FROM CODE

| Record/source | Clock source and unit | Basis and preservation | Evidence |
|---|---|---|---|
| Trip start | `System.currentTimeMillis()`; epoch ms | Wall-clock start retained; matching `SystemClock.elapsedRealtimeNanos()` monotonic anchor also retained | `LoggerService.kt:563-564`; `AppDatabase.kt:67-70` |
| Trip end | `System.currentTimeMillis()`; epoch ms | Wall-clock end retained; matching elapsed-realtime ns retained | `LoggerService.kt:791-793`; `AppDatabase.kt:67-70` |
| GPS source | `Location.time`; epoch ms | Original source epoch time retained in `sourceEpochTimeMs`; selected `timestamp` uses it when present | `LoggerService.kt:140-150`; `AppDatabase.kt:41-42` |
| GPS callback | `System.currentTimeMillis()`; epoch ms | Callback time retained separately in `callbackTimeMs` | `LoggerService.kt:140-150`; `AppDatabase.kt:40` |
| GPS elapsed source | `Location.elapsedRealtimeNanos`; monotonic ns | Retained as `sourceElapsedRealtimeNanos` when positive | `LoggerService.kt:143-149`; `AppDatabase.kt:41` |
| Accelerometer | `SensorEvent.timestamp`; monotonic ns | Original raw value retained in `rawTimestamp` and `sourceTimestampNanos`; alignment `timestamp` is derived epoch ms | `LoggerService.kt:961-980`; `AppDatabase.kt:38-39` |
| Gyroscope | `SensorEvent.timestamp`; monotonic ns | Same raw and derived preservation as accelerometer | `LoggerService.kt:983-1001` |
| Orientation | `SensorEvent.timestamp`; monotonic ns | Same raw and derived preservation as accelerometer | `LoggerService.kt:1005-1024` |
| Voice event | `System.currentTimeMillis()` and `SystemClock.elapsedRealtimeNanos()` at `recordEvent` | Acceptance/marker time is retained in `markerTimeMs` and `markerElapsedRealtimeNanos` | `LoggerService.kt:1519-1538`; `AppDatabase.kt:154-181` |
| Database-created records | `System.currentTimeMillis()` defaults for selected entity `createdAt` fields | Creation time is stored where the entity defines `createdAt`; it is not a separate acquisition clock | `AppDatabase.kt:154-181`, `196-200`, `215-228`, `242-281`, `302-323`, `343-353`, `368-383` |
| Audio segment | Epoch ms plus elapsed-realtime ns at segment start/end | Both bases are persisted in `TripAudio`; encoded presentation time is based on elapsed ns | `TripAudioRecorder.kt:45-70`, `121-136`; `AppDatabase.kt:302-323` |

### Conversion and synchronization

- A trip-start `ClockAnchor` pairs wall-clock milliseconds with elapsed-realtime
  nanoseconds. `TimestampCalibration.elapsedToWallTimeMs` converts sensor
  monotonic timestamps to epoch milliseconds using that anchor. `TimestampCalibration.kt:3-15`;
  `LoggerService.kt:954-959`
- The inverse wall-to-monotonic conversion is implemented for calibration but no
  separate runtime synchronization procedure was found. `TimestampCalibration.kt:13-15`
- The stored study timezone is `Asia/Kathmandu`. It is used for study-date
  interpretation, not for changing epoch timestamps. `ResearchValidation.kt:31-33`;
  `LoggerService.kt:857-864`
- Study date is derived using Kathmandu local time when no explicit date is
  supplied. `LoggerService.kt:453-455`; `ResearchClock.kt:7-13`

### Known timing offsets and limitations

- The voice event timestamp is assigned when the final recognized result is
  accepted by the callback path, not at speech onset. `LoggerService.kt:1345-1390`,
  `1519-1538`
- Event coordinates and fix provenance come from the latest received GPS point,
  not interpolation at speech onset. `LoggerService.kt:1519-1538`
- The event stores fix age as `event.timestamp - locationFixTimeMs`, but no maximum
  accepted age is enforced. `LoggerService.kt:1041-1057`
- Vosk word-level timing is enabled with `rec.setWords(true)`, but recognized word
  timing is not extracted or persisted. `VoskSpeechRecognizer.kt:78-90`;
  `LoggerService.kt:1345-1390`
- No measured offset was found between speech onset, recognition acceptance,
  event creation, GPS snapshot, and audio frame time.
- No wall-clock-change/discontinuity detector was found. Sensor alignment is based
  on the original trip-start anchor, while GPS callbacks and events use current
  wall-clock readings. `LoggerService.kt:140-150`, `954-959`, `1519-1524`

### NOT FOUND

- A physical measurement of recognition latency.
- A documented correction for clock changes during a trip.
- A timezone offset embedded in the numeric epoch values.

## 6. Audio Recording

### CONFIRMED FROM CODE

| Item | Value | Evidence |
|---|---|---|
| Whole-trip recording | Intended continuous capture during active recording, split into nominal five-minute segments | `LoggerService.kt:1282-1334`; `TripAudioRecorder.kt:23-80` |
| Audio API | Android `AudioRecord` | `VoskSpeechRecognizer.kt:121-166` |
| Audio source | `MediaRecorder.AudioSource.VOICE_RECOGNITION` | `VoskSpeechRecognizer.kt:135-142` |
| Input format | PCM, 16-bit, mono | `VoskSpeechRecognizer.kt:121-142` |
| Input sample rate | 16,000 Hz | `VoskSpeechRecognizer.kt:33`, `121-142` |
| Archive container | MPEG-4 / M4A | `TripAudioRecorder.kt:87-103` |
| Archive codec | AAC-LC, profile 2, nominal bit rate 32,000 | `TripAudioRecorder.kt:91-94` |
| Archive channels | 1 | `TripAudioRecorder.kt:30-33`, `91` |
| Segment duration | 5 minutes by default | `TripAudioRecorder.kt:23-28` |
| File name | `audio_<UUID>.m4a` | `TripAudioRecorder.kt:87-90` |
| Storage location | App-private `filesDir/audio` | `LoggerService.kt:1293-1296` |
| Audio linkage | `TripAudio.tripId`; optional `eventId` field exists, but segments are created without an event ID | `AppDatabase.kt:302-323`; `LoggerService.kt:1297-1327` |
| Export | Audio index and existing audio files are included in ZIP exports | `ResearchExporter.kt:140-160` |
| Audio metadata | Start/end epoch and monotonic times, sequence, MIME, codec, sample rate, channel count, size, SHA-256, status, interruption reason | `AppDatabase.kt:302-323`; `ResearchExporter.kt:140-159` |

### Start/stop and interruption behaviour

- The same `AudioRecord` read frames feed both Vosk and the archival encoder; a
  second recorder is not opened. `VoskSpeechRecognizer.kt:299-316`;
  `TripAudioRecorder.kt:19-22`
- Audio archiving starts when Vosk listening starts, after a durable trip draft is
  created and the model is ready. `LoggerService.kt:599-624`, `1282-1334`
- Explicit stop stops Vosk, waits for the recognition session, stops the audio
  recorder, waits for pending audio metadata persistence, flushes data, and then
  finalizes the trip. `LoggerService.kt:786-834`
- Service destruction stops Vosk/audio, marks audio as `SERVICE_DESTROYED` where
  applicable, attempts final persistence, and marks the trip recoverable.
  `LoggerService.kt:497-547`
- Encoder initialization failure creates a failed audio metadata record. `TripAudioRecorder.kt:96-118`
- An exception while accepting an individual archival frame is logged and does
  not terminate GPS/sensor collection or speech recognition. `VoskSpeechRecognizer.kt:299-309`
- A segment with no encoded samples is changed to `FAILED`. `TripAudioRecorder.kt:197-221`

### Offline, background, locked-screen, and storage behaviour

- No network is required by the Vosk implementation or Room collection path;
  model assets are bundled locally. `VoskSpeechRecognizer.kt:58-103`;
  `app/src/main/assets/model-en-us/`; `AppDatabase.kt:1281-1296`
- The audio recorder is owned by a foreground service. `AndroidManifest.xml:55-59`;
  `LoggerService.kt:595-597`
- Screen-locked operation is not established by source inspection alone. The
  foreground service and wake lock are intended to support continued work, but no
  device evidence was found.
- Audio persistence failures increment the general write-failure count and are
  logged; archival frame failures are not explicitly added to trip-quality
  warnings. `VoskSpeechRecognizer.kt:299-309`; `LoggerService.kt:1170-1227`

### Retention and deletion

- Trip deletion removes database rows for photos, raw trip data, sensor metadata,
  audio, audit revisions, and the trip itself in a Room transaction.
  `AppDatabase.kt:609-620`
- No code path was found that deletes the corresponding app-private `.m4a` files
  when a trip is deleted. This may leave orphaned audio files on device.
  `AppDatabase.kt:609-620`, `716-717`; `TripHistoryActivity.kt:186-197`;
  `TripDetailActivity.kt:847-858`
- No independent audio-retention policy or bulk media purge function was found.

### NOT FOUND

- Physical-device proof of continuous whole-trip audio.
- A measured archive bit depth; the source PCM input is 16-bit, while AAC-LC is
  encoded rather than fixed-bit-depth PCM.
- Audio import/replay verification or audio-to-event onset calibration.

## 7. Voice Attribution

### Vosk model and grammar

- The bundled model directory is `model-en-us`. `VoskSpeechRecognizer.kt:33-35`
- The recognizer is created at 16,000 Hz and uses a grammar JSON generated from
  `assets/cause_config.json`. `VoskSpeechRecognizer.kt:58-103`;
  `LoggerService.kt:426-443`
- Vosk word output is enabled with `setWords(true)`. `VoskSpeechRecognizer.kt:78-90`
- The grammar consists of activation-plus-cause phrases and `[unk]`. Variants are
  not included as grammar phrases; they are available to the fuzzy matcher.
  `CauseConfig.kt:28-51`; `GrammarBuilder.kt:18-27`

### Activation phrase and accepted cause tokens

The activation phrase is `log`. `cause_config.json:6-8`

| Internal code | Display name | Short form | Exact grammar phrases | Fuzzy/pronunciation aliases |
|---|---|---|---|---|
| `SIG` | `SIGNAL` | `SIGNAL` | `signal`; `traffic signal`; `red light` | `seegal`; `sihgnal`; `sigmal`; `sig nal`; `cignal`; `cignel` |
| `QUE` | `QUEUE` | `QUEUE` | `queue`; `traffic jam`; `congestion` | `cue`; `kyu`; `kue`; `kew`; `kyew`; `qu`; `cu` |
| `BUS` | `BUS` | `BUS` | `bus`; `microbus`; `minibus` | `buss`; `bss`; `buz`; `buzes` |
| `PED` | `PEDESTRIAN` | `PED` | `pedestrian`; `pedestrian crossing` | `ped`; `pedestrien`; `padestrian`; `pdestrian`; `pedy` |
| `RDS` | `ROUGHNESS` | `ROUGH` | `roughness`; `rough road`; `pothole`; `pot hole`; `speed breaker`; `speed bump` | `rough`; `bumps`; `roufness`; `rowghness`; `ruffness`; `rufness`; `rougnes`; `roufnes`; `bamp`; `potholes`; `pot holes`; `pothol`; `pothawl`; `speed breakers`; `speedbump`; `speed bumps`; `speedbrake`; `speed brake` |
| `INC` | `CONSTRUCTION` | `CONSTR` | `construction`; `road work` | `roadwork`; `roadblock`; `road block`; `construction zone` |
| `PRK` | `FRICTION` | `FRICT` | `friction`; `parked car`; `side friction` | `frictions`; `parked`; `friktion`; `frickshun`; `parkin`; `parkd`; `frikshin` |
| `TRN` | `TURNING` | `TURN` | `turning`; `turning vehicle`; `u turn` | `turns`; `u-turn`; `tern`; `terning`; `torn`; `tarning`; `uturn` |
| `ENC` | `MARKET` | `MARKET` | `market`; `street vendor`; `vendors` | `markets`; `stalls`; `vendor`; `markit`; `markat`; `stawl`; `vendr`; `vendur` |
| `UNK` | `UNCLASSIFIED` | `UNCLASSIFIED` | `unclassified` | none |

Evidence for the complete table: `app/src/main/assets/cause_config.json:9-194`.

### Normalisation and acceptance

- Text is lowercased, characters outside `[a-z0-9- ]` are replaced by spaces,
  surrounding whitespace is trimmed, and repeated whitespace is collapsed.
  `CauseConfig.kt:60-66`
- A result must have confidence at least `0.6`. `cause_config.json:2-4`;
  `LoggerService.kt:1354-1359`
- It must contain the activation phrase. `LoggerService.kt:1362-1367`
- An empty command or `[unk]` is ignored. `LoggerService.kt:1369-1373`
- Exact phrase mapping is attempted first; fuzzy Levenshtein matching is then
  accepted at score at least `0.85`. `LoggerService.kt:1375-1390`;
  `FuzzyCauseMatcher.kt:36-63`
- Empty, unmatched, no-activation, and below-threshold results do not create a
  cause event. `LoggerService.kt:1349-1390`
- There is no multiple-cause voice parser. An accepted utterance creates one
  primary event at most; a phrase containing multiple causes is treated as one
  text candidate for exact/fuzzy matching and otherwise is ignored. Secondary
  causes are review-time annotation fields, not voice-recognition outputs.
  `LoggerService.kt:1375-1390`; `FuzzyCauseMatcher.kt:36-63`;
  `AppDatabase.kt:368-383`
- There is no debounce or duplicate suppression. Repeated accepted commands can
  create repeated events. `LoggerService.kt:1375-1389`, `1491-1547`
- The configured `minWordLength = 3` is parsed but no use of it was found in
  matching or acceptance logic. `cause_config.json:5`; `CauseConfigLoader.kt:26-37`;
  `CauseConfig.kt:9-15`
- `voiceOnly` is parsed but no observed behavioral use was found.
  `CauseConfig.kt:80-86`; `CauseConfigLoader.kt:40-48`

### Timestamp and persisted values

- An accepted command receives the `recordEvent` marker timestamp, which is the
  current wall-clock epoch ms and elapsed-realtime ns at event creation. It is not
  a speech-onset timestamp. `LoggerService.kt:1513-1538`
- Each accepted voice event stores the internal cause code, provenance
  `VOICE_RECOGNIZED`, original recognized transcript, recognition confidence,
  event epoch/monotonic timestamps, latest GPS snapshot, GPS fix provenance, and
  codebook version. `LoggerService.kt:1041-1064`, `1519-1538`;
  `AppDatabase.kt:154-181`
- `log unclassified` is supported and maps to `UNK`. `cause_config.json:185-194`;
  `CauseMatchingTest.kt:33-48`
- Literal `UNKNOWN` is not an accepted runtime token in the current asset.
- A provisional captured cause and reviewed final cause are stored separately:
  `trip_events.primaryCauseCode` contains the captured event value, while
  `event_annotations.primaryCauseCode` and secondary fields contain reviewed
  annotations. `AppDatabase.kt:154-201`, `368-383`
- Secondary causes remain present in both `event_secondary_causes` and annotation
  `secondaryCause1`/`secondaryCause2`. `AppDatabase.kt:183-201`, `368-383`

### NOT FOUND

- A documented confidence calibration study.
- Voice false-activation, missed-command, multiple-command, or pronunciation
  accuracy measurements.
- Persisted Vosk word-level onset/end timestamps.

## 8. Canonical Cause Alignment

The requested canonical analytical values are:

`SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`, `CONSTRUCTION`, `FRICTION`, `TURNING`, `MARKET`, `UNKNOWN`.

The current implementation persists internal codes rather than exactly those
canonical strings. Complete mapping:

| Internal persisted/runtime code | Current display/meaning | Requested canonical value | Alignment |
|---|---|---|---|
| `SIG` | `SIGNAL` | `SIGNAL` | Meaning matches; persisted token differs |
| `QUE` | `QUEUE` | `QUEUE` | Meaning matches; persisted token differs |
| `BUS` | `BUS` | `BUS` | Meaning matches; persisted token differs |
| `PED` | `PEDESTRIAN` | `PED` | Internal code matches; display name differs |
| `RDS` | `ROUGHNESS`, short form `ROUGH` | `ROUGH` | Persisted token differs; short form matches |
| `INC` | `CONSTRUCTION` | `CONSTRUCTION` | Meaning matches; persisted token differs |
| `PRK` | `FRICTION` | `FRICTION` | Meaning matches; persisted token differs |
| `TRN` | `TURNING` | `TURNING` | Meaning matches; persisted token differs |
| `ENC` | `MARKET` | `MARKET` | Meaning matches; persisted token differs |
| `UNK` | `UNCLASSIFIED` | `UNKNOWN` | Neither persisted token nor display label is the requested literal |

Evidence: `app/src/main/assets/cause_config.json:9-194`;
`app/src/main/java/com/example/roadlog/ResearchValidation.kt:3-14`.

### Missing, extra, obsolete, and secondary values

- **Missing requested value:** literal `UNKNOWN` is not present as the codebook
  value. The implementation uses `UNK` / `UNCLASSIFIED`. `ResearchValidation.kt:3-14`;
  `cause_config.json:185-194`
- **Extra current internal values:** `SIG`, `QUE`, `PED`, `RDS`, `INC`, `PRK`,
  `TRN`, `ENC`, and `UNK` are internal abbreviations rather than the requested
  canonical strings. `ResearchValidation.kt:7-9`
- **Secondary values:** zero to two secondary causes remain supported and use the
  same internal code set. `ResearchValidation.kt:103-123`;
  `AppDatabase.kt:183-201`, `368-383`
- **Legacy compatibility field:** `trip_data.eventCause` still exists and is
  exported in raw sensor compatibility rows. `AppDatabase.kt:37`;
  `ResearchExporter.kt:172-184`
- No additional obsolete cause code was found in the current asset or hard-coded
  codebook.

### Contradiction

The runtime asset is one source of truth for voice capture, while
`ResearchCodebook` is a separate hard-coded source for annotation validation.
They currently contain matching sets, but future asset changes could produce
captured codes rejected by annotation validation. `CauseConfigLoader.kt:13-48`;
`ResearchValidation.kt:3-14`; `TripDetailActivity.kt:558-568`.

## 9. Session and Trip Metadata

### CONFIRMED FROM CODE

The `Trip` entity stores the following fields:

| Field | Meaning/value/status | Evidence |
|---|---|---|
| `id` | Local auto-generated Room trip ID | `AppDatabase.kt:64-67` |
| `tripUuid` | Stable UUID identity | `AppDatabase.kt:78`; `LoggerService.kt:866-867` |
| `sessionId` | Static `STUDY_SESSION` | `ResearchValidation.kt:35-38`; `LoggerService.kt:851-852` |
| `corridorId` | Static `STUDY_CORRIDOR` | `ResearchValidation.kt:35-38`; `LoggerService.kt:852` |
| `direction` | `A_TO_B` or `B_TO_A` | `ResearchValidation.kt:16-21`; `LoggerService.kt:451-465` |
| `observationPeriod` | `MORNING`, `OFF_PEAK`, or `EVENING` | `ResearchValidation.kt:23-29`; `LoggerService.kt:451-465` |
| `deviceId` | Device identity captured at trip creation | `LoggerService.kt:357-358`, `863`; `AppDatabase.kt:83` |
| `startTimeMs`, `endTimeMs` | Epoch-ms trip boundaries | `AppDatabase.kt:67-68`; `LoggerService.kt:563`, `791` |
| `startNanoTime`, `endNanoTime` | Elapsed-realtime nanosecond boundaries | `AppDatabase.kt:69-70`; `LoggerService.kt:564`, `792` |
| `studyDateLocal` | Kathmandu local date | `LoggerService.kt:453-455`, `860`; `ResearchClock.kt:7-13` |
| `timeZoneId` | `Asia/Kathmandu` | `ResearchValidation.kt:31-33`; `LoggerService.kt:861` |
| `appVersion` | `BuildConfig.VERSION_NAME` | `LoggerService.kt:858`; `app/build.gradle:15-16` |
| `schemaVersion` | `7` for new trips | `LoggerService.kt:859`; `AppDatabase.kt:1272` |
| `codebookVersion` | Runtime cause config version | `LoggerService.kt:857` |
| `sensorProfileVersion` | `1` | `LoggerService.kt:127`, `862` |
| `status` | `COMPLETED`, `RECORDING`, `ABORTED`, `INVALID`, or `RECOVERABLE` integer values | `AppDatabase.kt:55-62` |
| `validityStatus` | Null at creation; set to `COLLECTED` on normal completion | `LoggerService.kt:855`; `AppDatabase.kt:674-675` |
| `qaStatus` | `UNREVIEWED`, `VALID`, `VALID_WITH_WARNINGS`, or `INVALID` | `ResearchValidation.kt:40-47`; `AppDatabase.kt:95` |
| `qaNotes` | Free-text QA notes | `AppDatabase.kt:96` |
| `routeDiversion` | Boolean field | `AppDatabase.kt:97`; `ResearchExporter.kt:86-88` |
| `recordingInterruption` | Boolean interruption flag | `AppDatabase.kt:98`; `markTripInterrupted` at `AppDatabase.kt:686-700` |
| `gpsInterruption` | Boolean GPS interruption flag | `AppDatabase.kt:99`; `LoggerService.kt:824-825` |
| `sensorInterruption` | Boolean sensor interruption flag | `AppDatabase.kt:100`; `LoggerService.kt:825` |
| `partialTraversal` | Boolean partial-coverage flag | `AppDatabase.kt:101`; `markTripInterrupted` at `686-700` |
| `coverageEndTimeMs` | End of preserved coverage on interruption | `AppDatabase.kt:102`; `markTripInterrupted` at `686-700` |
| `coverageEndLatitude`, `coverageEndLongitude` | Last known coverage location | `AppDatabase.kt:103-104`; `LoggerService.kt:821-823`, `538-539` |
| `continuationOfTripUuid` | Optional continuation relationship | `AppDatabase.kt:105`; `ResearchExporter.kt:86-88` |
| `lastWriteTimeMs` | Last successful Room batch-write heartbeat | `AppDatabase.kt:86`; `LoggerService.kt:1077-1084` |
| `writeFailureCount` | Room write failure count | `AppDatabase.kt:87`; `LoggerService.kt:1090-1104` |
| `droppedSampleCount` | Shared buffer overflow count | `AppDatabase.kt:88`; `LoggerService.kt:262-280` |
| `interruptionReason` | Recovery/finalization reason | `AppDatabase.kt:89`; `LoggerService.kt:815-825`, `531-542` |
| `distanceMeters`, `eventCount`, `gpsPointCount`, `accelPointCount` | Trip summary values | `AppDatabase.kt:71-75`; `LoggerService.kt:1124-1146` |
| `causeBreakdown` | JSON count map keyed by internal cause code | `AppDatabase.kt:75`; `LoggerService.kt:1127-1143` |
| `createdAt` | Epoch-ms summary update time | `AppDatabase.kt:76`; `LoggerService.kt:1143` |
| `notes` | Optional trip notes | `AppDatabase.kt:85` |

### Required metadata specifically requested

- **Session and trip IDs:** present as static session/corridor IDs plus local trip
  ID and UUID. `ResearchValidation.kt:35-38`; `AppDatabase.kt:64-80`
- **Direction:** present and validated before start. `ResearchValidation.kt:16-21`,
  `77-90`; `LoggerService.kt:455-465`
- **Traffic period:** stored as `observationPeriod`; exact time windows are not
  encoded or inferred. `ResearchValidation.kt:23-29`; `docs/research-data-readiness.md:34-35`
- **Vehicle/device identifiers:** `deviceId` is stored. Vehicle ID/type, observer,
  pass, and mount fields are not implemented. `AppDatabase.kt:81-83`;
  `docs/data-dictionary.md:29-36`
- **Application/schema/codebook versions:** app, schema, codebook, and sensor
  profile are stored per trip. `LoggerService.kt:857-863`
- **Weather and road wetness:** not present in the entity, UI, or export schema
  located.
- **Diversions:** `routeDiversion` exists and is exported, but no visible runtime
  assignment path was found. `AppDatabase.kt:97`; `ResearchExporter.kt:86-88`
- **Personal/non-traffic stops:** no dedicated field or event category was found.
- **Validity:** `validityStatus` and `qaStatus` exist; normal completion writes
  `validityStatus = 'COLLECTED'`, while QA has separate review states.
  `AppDatabase.kt:674-678`; `ResearchValidation.kt:40-47`
- **Exclusion reason:** no dedicated field was found; `notes` and
  `interruptionReason` are not equivalent structured exclusion fields.
- **`INCIDENT_OR_BREAKDOWN`:** no exact value or field was found in code,
  documentation, or export headers.
- **Protocol deviations:** interruption, partial traversal, diversion, and QA
  fields exist, but no general structured protocol-deviation field was found.

### NOT FOUND

- Actual physical-corridor geometry, endpoint labels, or segment identifiers in
  the collector database.
- A stored vehicle identifier, vehicle type, observer ID, pass number, mount
  position, or mount orientation.
- Weather, wetness, personal-stop, exclusion-reason, or incident/breakdown data.
- A visible writer for `routeDiversion` and `continuationOfTripUuid`.

## 10. Offline/Background Operation

### CONFIRMED FROM CODE

#### Foreground service

- `LoggerService` is declared as a non-exported foreground service with
  `location|microphone` service types. `app/src/main/AndroidManifest.xml:55-59`
- Android O and later starts it with `startForegroundService`; older versions use
  `startService`. `MainActivity.kt:652-664`
- The service calls `startForeground` before model/database preparation work.
  `LoggerService.kt:592-597`
- The notification reports state, direction/period, duration, event count, GPS
  indicator, microphone/listening state, and write failures. `LoggerService.kt:1458-1482`,
  `1564-1595`

#### Offline operation

- Vosk uses the bundled model and Room is local. No network call is part of the
  collection or speech path. `VoskSpeechRecognizer.kt:58-103`;
  `AppDatabase.kt:1281-1296`
- OpenStreetMap tiles are a separate best-effort map-display dependency; the
  README says tiles are cached but not packaged as offline tile packs.
  `README.md:85-99`
- Export writes a local temporary ZIP and copies it to a content URI. `ResearchExporter.kt:35-57`

#### Background and screen-locked operation

- The foreground service is designed to continue collection when the activity is
  not in the foreground. `AndroidManifest.xml:55-59`; `LoggerService.kt:595-597`
- A partial wake lock is acquired for up to 60 minutes. `LoggerService.kt:347-349`,
  `592-593`
- The activity requests exemption from battery optimization but permits the user
  to skip the request. `MainActivity.kt:823-837`
- The wake lock is not renewed after the one-hour acquire duration. No code was
  found that re-acquires it during a longer trip.
- Screen-locked continuity is not proven by source code and has no retained dated
  device result.

#### Permission requirements

- Main recording permission requests are fine location and microphone.
  `MainActivity.kt:68-72`
- Camera permission is optional and requested only when photo capture is enabled.
  `MainActivity.kt:623-644`
- The manifest also declares foreground service, foreground location/microphone,
  camera, wake-lock, battery-optimization, internet, and network-state permissions.
  `AndroidManifest.xml:5-15`
- No `ACCESS_BACKGROUND_LOCATION` permission is declared. `AndroidManifest.xml:5-15`
- GPS, microphone, and accelerometer are declared required hardware; camera is
  optional. `AndroidManifest.xml:17-20`

#### Process restart/recovery

- `onStartCommand` returns `START_NOT_STICKY`, so automatic service recreation is
  not requested. `LoggerService.kt:446-483`
- On a later service creation, residual `RECORDING` trips are changed to
  `RECOVERABLE` with `PROCESS_OR_SERVICE_INTERRUPTION`. `LoggerService.kt:378-403`
- Pending audio rows are reconciled on service creation: non-empty files become
  `INTERRUPTED`; missing/empty files become `FAILED`. `LoggerService.kt:405-423`
- `onDestroy` intentionally does not cancel `serviceScope`, allowing a final Room
  flush coroutine to finish. `LoggerService.kt:485-495`

#### Failure handling

- Room batch writes are transactional and retain buffers for retry on failure.
  `AppDatabase.kt:385-397`; `LoggerService.kt:1068-1105`
- Explicit finalization retries flushes three times, then marks the trip
  recoverable if buffers remain or finalization fails. `LoggerService.kt:795-834`
- Buffer overflow increments a shared dropped count, broadcasts a warning, and
  initiates stop. `LoggerService.kt:262-280`
- Sensor/provider failure sets interruption state and permits the rest of the
  recording to continue. `LoggerService.kt:709-741`
- Storage failure during preparation marks a draft recoverable where a draft ID
  exists. `LoggerService.kt:660-679`
- Vosk preparation failure reports an error but leaves `modelReady = false`; the
  start coroutine waits in a loop while the service remains in preparation. This
  can leave a durable draft in `PREPARING` until intervention.
  `LoggerService.kt:426-443`, `599-624`

### CONFIRMED FROM DOCUMENTATION

- The field protocol instructs operators to keep the foreground-service
  notification active and to check permissions, storage, battery, and battery
  optimization before a trip. `docs/field-protocol.md:3-18`
- The readiness documentation labels background recording and crash recovery as
  warnings and requires screen-lock/process-interruption testing before pilot use.
  `docs/research-data-readiness.md:17-20`, `43-51`

### NOT FOUND

- Automatic service restart after process death.
- A robust response to permission revocation during an active trip.
- A persistent provider-callback watchdog.
- Dated evidence for offline, background, or locked-screen operation.

## 11. Export and Reproducibility

### Export format and files

`ResearchExporter.exportToUri` produces a ZIP archive. `ResearchExporter.kt:35-57`

| Archive entry | Content | Evidence |
|---|---|---|
| `trips/trips.csv` | Trip identity, timestamps, summary, metadata, QA, interruption, version, and notes fields | `ResearchExporter.kt:86-90` |
| `events/events.csv` | Structured event markers, location provenance, cause, status, transcript, confidence, and codebook version | `ResearchExporter.kt:98-106` |
| `events/annotations.csv` | Reviewed annotation revisions, primary/secondary causes, traffic state, confidence, notes, reviewer, and superseded revision | `ResearchExporter.kt:108-115` |
| `events/secondary_causes.csv` | Normalized secondary cause rows | `ResearchExporter.kt:117-121` |
| `media/media_index.csv` | Photo metadata and archive paths | `ResearchExporter.kt:123-159` |
| `audio/audio_index.csv` | Audio segment metadata, statuses, checksums, and archive paths | `ResearchExporter.kt:140-159` |
| `photos/<capture-id>.jpg` | Existing photo binaries | `ResearchExporter.kt:129-160` |
| `audio/<audio-id>.m4a` | Existing audio binaries | `ResearchExporter.kt:144-160` |
| `sensors/trip_<id>.csv` | Raw/compatibility rows for GPS, accelerometer, gyroscope, rotation, and events | `ResearchExporter.kt:162-190` |
| `qa/trips.json` | Persisted quality metrics and warnings | `ResearchExporter.kt:192-235` |
| `metadata/sensors.csv` | Sensor hardware metadata, registration result, profile, and requested period | `ResearchExporter.kt:237-241` |
| `audit/revisions.csv` | Audit revision records | `ResearchExporter.kt:243-249` |
| `metadata/device.json` | Device, Android, app, Room schema, static study, and sensor profile metadata | `ResearchExporter.kt:251-262` |
| `metadata/codebook.json` | Codebook version, primary internal codes, and traffic states | `ResearchExporter.kt:263-267` |
| `metadata/schema.json` | Room schema, timestamp units, and raw-field-authority declaration | `ResearchExporter.kt:268` |
| `manifest.json` | Export time, versions, counts, clock-source declarations, completeness | `ResearchExporter.kt:270-296` |
| `checksums.sha256` | SHA-256 digest for each archive entry | `ResearchExporter.kt:77-84`, `298-301` |

### Raw versus derived values

- The sensor CSV exports raw GPS fields, source epoch/elapsed timestamps, raw
  sensor nanoseconds, callback times, axes/quaternion values, sensor type,
  accuracy, and source type. `ResearchExporter.kt:172-184`
- `timestamp_ms` is a derived alignment timestamp for sensor rows; raw source
  timestamps remain available. `docs/data-dictionary.md:38-47`;
  `ResearchExporter.kt:172-184`
- Event records preserve captured/provisional cause and reviewed annotations are
  exported separately. `ResearchExporter.kt:98-121`
- Audio files are exported separately from the audio index and preserve segment
  timing/status/checksum metadata. `ResearchExporter.kt:140-160`

### Version and provenance export

- Per-trip CSV includes app, schema, codebook, sensor profile, device, session,
  corridor, and study-date/timezone values. `ResearchExporter.kt:86-88`
- Top-level device, codebook, schema, and manifest entries include current app,
  Room schema, codebook, sensor profile, and timestamp-unit metadata.
  `ResearchExporter.kt:251-295`
- The top-level export does not include the exact runtime cause-config asset,
  activation phrase, phrase/variant mappings, or thresholds. `ResearchExporter.kt:263-268`
- Top-level metadata is generated from current exporter constants and may differ
  from selected historical trips' per-trip values. `ResearchExporter.kt:251-277`;
  `LoggerService.kt:857-863`

### Counts, checksums, and verification

- The manifest contains trip, row, event, photo, annotation, audio, GPS,
  accelerometer, gyroscope, and orientation counts. `ResearchExporter.kt:270-295`
- SHA-256 is generated for text and binary entries. `ResearchExporter.kt:77-84`,
  `315-334`
- Before copying to the destination, the ZIP is opened and required entries,
  every checksum entry, and selected manifest counts are validated. `ResearchExporter.kt:337-368`
- Incomplete trips are excluded by default and require `includeIncomplete = true`
  to be selected. `ResearchExporter.kt:35-46`; `270-295`
- ZIP entry/export timestamps vary, so byte-for-byte deterministic archive
  regeneration is not established. `ResearchExporter.kt:47`, `270-301`

### Reproducibility gaps and contradictions

- `TripQuality` persists `rotationSampleCount`, `rotationEffectiveHz`, and
  `generatedAt`, but `qa/trips.json` does not export those fields. `AppDatabase.kt:242-281`;
  `LoggerService.kt:1180-1227`; `ResearchExporter.kt:192-235`
- Missing photos remain indexed with an empty archive path, while a completed
  audio segment whose file is missing causes export failure. `ResearchExporter.kt:123-159`
- Historical v6 `trip_data` rows are not transformed into the new
  `trip_events` table by v6→v7. Legacy event evidence remains in raw compatibility
  rows rather than appearing in `events/events.csv`. `AppDatabase.kt:37`,
  `1058-1092`; `ResearchExporter.kt:93-106`, `172-184`
- The v6→v7 migration test expects a legacy `trip_data.tripId = 0` row to become
  trip ID `1`, but the migration code contains no such update for that inserted
  GPS row. `DatabaseMigrationTest.kt:31-56`; `AppDatabase.kt:984-1035`.
  Static inspection indicates this test expectation is inconsistent with the
  migration implementation; the test was not executed during this audit.
- Only v6 and v7 schema snapshots were found, and only v6→v7 has a visible
  migration test. `app/schemas/com.example.roadlog.AppDatabase/`;
  `app/src/androidTest/java/com/example/roadlog/DatabaseMigrationTest.kt:20-70`

### NOT FOUND

- An importer for the ZIP format.
- A round-trip export/import test.
- A formal export schema version.
- Independent checksum verification evidence from a retained archive.

## 12. Validation Evidence

The following distinction is important: implemented code, test fixtures, a test
definition, or an informal/proposed procedure is not treated as completed
physical validation.

| Validation item | Test date | Device/Android | App version/build | Procedure | Sample size | Result | Evidence location | Status |
|---|---|---|---|---|---|---|---|---|
| Physical-device testing | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No executed device record found | NOT DOCUMENTED | NOT DOCUMENTED | No dated device report found | NOT FOUND |
| Offline operation | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Proposed by offline-first implementation/documentation, not recorded execution | NOT DOCUMENTED | NOT DOCUMENTED | `README.md:3-6`; `research/thesis/android-app.md:465-485` | USER/EXTERNAL CLAIM ONLY |
| Background operation | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Required/proposed foreground-service verification | NOT DOCUMENTED | NOT DOCUMENTED | `docs/research-data-readiness.md:17-20`, `43-51`; `docs/research-data-audit.md:28-29` | NOT DOCUMENTED |
| Locked-screen recording | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Start, lock for two minutes, unlock, stop; expected continuous rows | NOT DOCUMENTED | NOT DOCUMENTED | `research/thesis/android-app.md:474` | USER/EXTERNAL CLAIM ONLY |
| Continuous audio recording | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Physical-device storage/encoder/interruption check required | NOT DOCUMENTED | NOT DOCUMENTED | `docs/research-data-readiness.md:38-39`, `49-51` | NOT DOCUMENTED |
| GPS continuity | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Proposed walk/outdoor GPS check | NOT DOCUMENTED | NOT DOCUMENTED | `research/thesis/android-app.md:473` | USER/EXTERNAL CLAIM ONLY |
| Motion-sensor continuity | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Proposed shake/sensor-row check; actual rates to be verified on devices | NOT DOCUMENTED | NOT DOCUMENTED | `research/thesis/android-app.md:472`, `476`; `docs/research-data-audit.md:18-20` | USER/EXTERNAL CLAIM ONLY |
| Voice-recognition accuracy | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No executed accuracy protocol, sample, or result found | NOT DOCUMENTED | NOT DOCUMENTED | `app/src/test/java/com/example/roadlog/CauseMatchingTest.kt:33-48` is logic testing only | NOT DOCUMENTED |
| False activation testing | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No executed false-activation study found | NOT DOCUMENTED | NOT DOCUMENTED | No retained evidence found | NOT DOCUMENTED |
| Missed-command testing | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No executed missed-command study found | NOT DOCUMENTED | NOT DOCUMENTED | No retained evidence found | NOT DOCUMENTED |
| Timestamp latency | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No speech-onset/acceptance/GPS/audio latency procedure or result found | NOT DOCUMENTED | NOT DOCUMENTED | `LoggerService.kt:1519-1538` shows implementation timestamping only | NOT DOCUMENTED |
| Driver-distraction/usability | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Documentation requires passenger-only operation; no usability study result | NOT DOCUMENTED | NOT DOCUMENTED | `docs/field-protocol.md:14-22` | NOT DOCUMENTED |
| Export integrity | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | Code validates ZIP entries/checksums internally | NOT DOCUMENTED | Runtime validation result not retained | `ResearchExporter.kt:337-368` | CONFIRMED FROM CODE; validation NOT DOCUMENTED |
| Export round trip | NOT DOCUMENTED | NOT DOCUMENTED | NOT DOCUMENTED | No importer or round-trip procedure found | NOT DOCUMENTED | NOT DOCUMENTED | `docs/research-data-audit.md:39-41` | NOT DOCUMENTED |

### Tests present in the repository

#### CONFIRMED FROM CODE

- JVM tests contain fixture, cause-matching, quality-metric, annotation,
  lifecycle-state, timestamp-calibration, study-configuration, provenance, and
  Kathmandu-date tests. `app/src/test/java/com/example/roadlog/FixtureSmokeTest.kt:8-165`;
  `CauseMatchingTest.kt:33-48`; `ResearchQualityTest.kt:9-142`
- Instrumentation tests contain in-memory Room CRUD/data-flow tests and one
  v6→v7 migration test. `app/src/androidTest/java/com/example/roadlog/DatabaseSmokeTest.kt:37-379`;
  `DatabaseMigrationTest.kt:20-70`
- The tests use synthetic fixtures and do not establish physical GPS, microphone,
  camera, Vosk, screen-lock, process-death, or device-rate behaviour.

#### CONFIRMED FROM DOCUMENTATION

- Instrumentation tests require a connected device or emulator. `README.md:60-68`
- The readiness documents explicitly retain a `NOT READY FOR PILOT` gate until
  schema, export, physical-device, screen-lock, process-interruption, sensor, and
  audio checks are completed. `docs/research-data-readiness.md:43-51`;
  `docs/research-data-audit.md:33-41`

#### USER/EXTERNAL CLAIM ONLY

- `research/thesis/android-app.md:465-485` describes desk and pilot-drive tests,
  including launch, speech, accelerometer, GPS, screen-off, sample-rate, short
  route, real-corridor, and heat tests. The file contains expected outcomes, not
  dated execution records.
- The same document contains historical implementation claims about Android
  `SpeechRecognizer`, Downloads CSV files, and simple commands. These do not match
  the current Vosk/Room/ZIP implementation. `research/thesis/android-app.md:465-507`.

## Cross-Cutting Contradictions And Risks

1. **Canonical cause mismatch:** the requested canonical values are not the exact
   persisted values. The current code uses abbreviations and `UNK`/`UNCLASSIFIED`
   rather than exact strings, especially `UNKNOWN`. `ResearchValidation.kt:3-14`;
   `cause_config.json:9-194`.
2. **Outdated thesis procedure:** the thesis app document describes an older speech
   API, command grammar, file/export model, and sample-rate expectations. Current
   code uses Vosk, `log <cause>`, Room, ZIP export, and AAC/M4A. `research/thesis/android-app.md:465-507`;
   `VoskSpeechRecognizer.kt:58-103`; `ResearchExporter.kt:35-57`.
3. **Requested versus observed rate ambiguity:** quality code expects 50 Hz for
   motion streams, but Android `SENSOR_DELAY_GAME` does not guarantee 50 Hz.
   `LoggerService.kt:709-741`, `1165-1168`.
4. **GPS indicator ambiguity:** `gpsLocked` only means the latest provider was GPS,
   while network fixes are accepted and stored. `LoggerService.kt:137-145`,
   `685-703`.
5. **Audio error visibility:** individual archival encoder frame failures are
   logged without necessarily appearing in trip QA warnings. `VoskSpeechRecognizer.kt:299-309`;
   `LoggerService.kt:1170-1227`.
6. **Vosk preparation failure:** a model error does not release the preparation
   wait, potentially leaving a draft/service in `PREPARING`. `LoggerService.kt:426-443`,
   `599-624`.
7. **Migration/test mismatch:** the v6→v7 test expects a legacy row reassignment
   not implemented in the migration. `DatabaseMigrationTest.kt:31-56`;
   `AppDatabase.kt:984-1035`.
8. **Historical event migration gap:** legacy `eventCause` data is not converted
   into structured `trip_events`. `AppDatabase.kt:37`, `1058-1092`.
9. **Runtime configuration not fully reproducible:** the export includes code list
   and version but not the exact cause asset, phrases, aliases, activation phrase,
   or thresholds. `ResearchExporter.kt:263-268`.
10. **Metadata completeness gap:** route diversion and continuation fields exist
    but no visible writer was found; weather, wetness, vehicle, personal-stop,
    exclusion, and incident/breakdown fields are absent. `AppDatabase.kt:81-106`;
    `docs/data-dictionary.md:29-36`.
11. **Audio orphan risk:** trip deletion removes database audio rows but no observed
    audio-file deletion. `AppDatabase.kt:609-620`, `716-717`.
12. **Quality export omission:** several persisted quality fields are not exported.
    `AppDatabase.kt:242-281`; `ResearchExporter.kt:192-235`.
13. **Recovery limitations:** service is `START_NOT_STICKY`; the wake lock is only
    acquired for 60 minutes; process recovery depends on later service creation.
    `LoggerService.kt:446-483`, `592-593`, `378-423`.
14. **No measured timing evidence:** raw and derived clocks are implemented, but
    speech onset and recognition latency are not retained or validated.
    `LoggerService.kt:1345-1390`, `1519-1538`.

## Concise Requested-Item Summary

| Requested item | Value/finding | Evidence location | Status |
|---|---|---|---|
| Application identity | RoadLog; `com.example.roadlog`; version `1.0`, code `1`; min 26; target 29 | `strings.xml:3`; `app/build.gradle:8-16` | CONFIRMED FROM CODE |
| Database/schema versions | Room/database schema v7; per-trip schema field v7; migrations 1→2→3→4→5→6→7 | `AppDatabase.kt:786-1296`; `LoggerService.kt:857-863` | CONFIRMED FROM CODE |
| Export/protocol versions | No explicit export-format or formal protocol version | `ResearchExporter.kt:77-301`; `ResearchValidation.kt:3-70` | NOT FOUND |
| Cause versions | Codebook/config version 2; sensor-profile version 1 | `cause_config.json:2-8`; `ResearchValidation.kt:3-14`; `LoggerService.kt:857-863` | CONFIRMED FROM CODE |
| GPS | LocationManager GPS + network; 1,000 ms; 0 m; no priority; fields/provenance retained | `LoggerService.kt:685-703`, `137-166`; `AppDatabase.kt:20-52` | CONFIRMED FROM CODE |
| GPS observed rate | Computed after collection, not known from configuration | `LoggerService.kt:1155-1227` | CONFIRMED mechanism; observed value NOT FOUND |
| Accelerometer | `TYPE_ACCELEROMETER`; `SENSOR_DELAY_GAME`; nominal 20,000 us; raw axes and timestamps | `LoggerService.kt:352`, `709-719`, `870-893`; `AppDatabase.kt:27-52` | CONFIRMED FROM CODE |
| Gyroscope | `TYPE_GYROSCOPE`; `SENSOR_DELAY_GAME`; nominal 20,000 us; raw axes and timestamps | `LoggerService.kt:353`, `720-730`, `870-893` | CONFIRMED FROM CODE |
| Orientation | Game rotation vector with rotation-vector fallback; raw quaternion/vector; no frame transform | `LoggerService.kt:354-355`, `731-741`, `233-260` | CONFIRMED FROM CODE |
| Sensor observed rate | Effective Hz calculated, but physical observed rate not retained in audit evidence | `LoggerService.kt:1165-1168`; `AppDatabase.kt:247-262` | CONFIG CONFIRMED; OBSERVATION NOT FOUND |
| Timestamp model | Epoch ms plus monotonic ns; sensor conversion from trip-start anchor; source timestamps retained | `TimestampCalibration.kt:3-15`; `LoggerService.kt:954-1024` | CONFIRMED FROM CODE |
| Timestamp latency | No speech-onset/recognition/GPS/audio offset measurement | `LoggerService.kt:1519-1538` | NOT FOUND |
| Audio | Shared `AudioRecord`, 16 kHz mono PCM input, AAC-LC/M4A five-minute segments, app-private storage | `VoskSpeechRecognizer.kt:121-166`; `TripAudioRecorder.kt:19-103` | CONFIRMED FROM CODE |
| Audio export/deletion | Exported with index and binary; database deletion exists, file deletion not found | `ResearchExporter.kt:140-160`; `AppDatabase.kt:609-620` | CONFIRMED / GAP |
| Voice | Vosk `model-en-us`, `log` activation, confidence .6, fuzzy .85, internal cause codes | `cause_config.json:1-195`; `LoggerService.kt:1345-1390` | CONFIRMED FROM CODE |
| Unknown cause | `log unclassified` → `UNK`; literal `UNKNOWN` unsupported | `cause_config.json:185-194`; `CauseMatchingTest.kt:33-48` | CONFIRMED FROM CODE |
| Provisional/final cause | Capture event and reviewed annotation records are separate; secondary causes remain | `AppDatabase.kt:154-201`, `368-383` | CONFIRMED FROM CODE |
| Canonical alignment | Internal codes map to requested meanings but not exact requested tokens; `UNK` differs from `UNKNOWN` | `ResearchValidation.kt:3-14`; `cause_config.json:9-194` | CONTRADICTION |
| Session/trip metadata | IDs, direction, period, device, versions, QA/interruption/coverage fields | `AppDatabase.kt:64-106`; `LoggerService.kt:837-867` | CONFIRMED FROM CODE |
| Weather/wetness/vehicle/stops/exclusions | No corresponding structured fields found | `AppDatabase.kt:64-106`; `docs/data-dictionary.md:29-36` | NOT FOUND |
| Foreground/offline/background | Foreground location/mic service; local Room/Vosk; 60-minute partial wake lock; no background-location permission | `AndroidManifest.xml:5-20`, `55-59`; `LoggerService.kt:592-597` | CONFIRMED FROM CODE |
| Recovery | Recoverable drafts and pending-audio reconciliation on later service creation; `START_NOT_STICKY` | `LoggerService.kt:378-423`, `446-483` | CONFIRMED FROM CODE |
| Export | ZIP with CSV/JSON/raw sensors/media/metadata/manifest/checksums | `ResearchExporter.kt:77-301` | CONFIRMED FROM CODE |
| Export verification | Internal ZIP/checksum/count validation; no importer/round-trip test | `ResearchExporter.kt:337-368`; `docs/research-data-audit.md:39-41` | PARTIAL; ROUND TRIP NOT FOUND |
| Validation evidence | No dated executed physical/device validation record found | `docs/research-data-readiness.md:43-51`; `README.md:60-68` | NOT DOCUMENTED |

## INFORMATION STILL REQUIRED FROM THE DEVELOPER

- Approved canonical persisted/exported cause vocabulary, especially whether
  `UNK` must be changed to `UNKNOWN`, and whether `PED`/`ROUGH` must replace
  display labels.
- Formal export-format version and field-protocol/configuration version.
- Approved observation-period time windows.
- Actual study session/corridor identifiers, route geometry, endpoints, and
  segment definitions.
- Policy and values for weather, road wetness, vehicle/device/mount metadata,
  personal/non-traffic stops, exclusions, and `INCIDENT_OR_BREAKDOWN`.
- Dated physical-device validation records containing device model, Android
  version, app version/build, procedure, sample size, raw results, pass/fail
  outcome, and retained evidence location.
- Measured GPS and sensor rates, sensor registration results, screen-lock and
  background duration, battery behaviour, and process-interruption outcomes.
- Continuous audio evidence, encoder/storage failure evidence, audio retention
  policy, and audio-file deletion verification.
- Voice-recognition accuracy, false-activation, missed-command, multiple-cause,
  pronunciation-alias, confidence-threshold, and recognition-latency results.
- Timestamp-latency measurements linking speech onset, recognition acceptance,
  event creation, GPS snapshot, and audio segment/frame time.
- Driver-distraction/usability evaluation records demonstrating passenger-only
  operation and safe interaction.
- A validated export/import or independent round-trip procedure and retained
  archive/checksum results.
- Resolution of the v6→v7 migration/test mismatch and evidence from running all
  supported migration paths on representative legacy databases.
- Decision on whether to export the exact runtime cause configuration asset and
  all per-trip configuration values needed for reproducibility.
