# RoadLog Research Data Gap and Implementation Plan

> **Historical baseline:** This audit records the pre-migration design and its
> original gaps. Current behavior and remaining gates are tracked in
> `docs/research-data-audit.md`, `docs/research-data-readiness.md`, and
> `research/thesis/android-app.md`. Do not use superseded camera, passenger,
> secondary-cause, or legacy-code examples below as the active protocol.

## Scope

This plan is based on a read-only audit of the current RoadLog implementation. The
application is an Android XML/View Binding app with a Room database, a foreground
`LoggerService`, GPS and motion-sensor collection, offline Vosk recognition,
optional CameraX photos, trip history, and local trip-detail analytics.

The current v6 installations are assumed to contain only test/demo data. The next
schema can therefore prioritize a clean research contract while retaining normal
Room migration compatibility.

The research priority order is:

1. Scientific reproducibility
2. Data integrity
3. Field reliability
4. Usability
5. Convenience

## Executive Conclusion

Current state: **NOT READY FOR PILOT**.

The app has a useful collection foundation, but several defects can lose or
scientifically contaminate research data. The highest risks are destructive crash
recovery, recording lifecycle races, unsafe buffer flushing, incorrect timestamp
provenance, insufficient GPS quality fields, missing research hierarchy, limited
event semantics, unsafe photo ownership, delayed foreground promotion, and absent
portable export.

## Existing Strengths

- Raw accelerometer, gyroscope, and rotation components are retained.
- Room migrations currently exist from schema 1 through schema 6.
- Recording uses a non-exported foreground service and a partial wake lock.
- Sensor data is periodically written in batches rather than synchronously at sensor
  frequency on the UI thread.
- Completed trips are separated from recording drafts in history queries.
- Trip deletion has a transactionally scoped DAO path.
- Vosk recognition is local and uses the bundled offline model.
- Camera permission is optional and media is stored in app-private storage.
- Cause display and speech matching are already asset-configured.
- Core recording does not depend on internet connectivity.
- Existing JVM and Room instrumentation fixtures provide a starting point for new
  validation coverage.

## P0 Gaps: Data Integrity and Field Collection Blockers

| Gap | Current evidence | Research impact | Required direction |
| --- | --- | --- | --- |
| Destructive crash recovery | `LoggerService.kt:257-270` deletes every `RECORDING` trip during service creation | Persisted partial trips are lost after process death or service restart | Preserve interrupted trips as `ABORTED`, `INVALID`, or `RECOVERABLE`; never delete automatically |
| Start/stop lifecycle races | Draft creation is asynchronous in `LoggerService.kt:328-365`; stop/finalization runs separately in `:406-456` | Short trips can disappear; consecutive trips can share mutable counters, buffers, and IDs | Add explicit lifecycle states and serialize start, stop, and finalization |
| Unsafe buffer flushing | `LoggerService.kt:485-598` clears buffers before Room insertion succeeds | Database or storage failures silently drop samples | Use one serialized writer; retain batches until transaction success; retry or fail visibly |
| Incorrect source timestamps | Sensors use callback `System.nanoTime()`; GPS uses callback wall time | Sensor, GPS, media, and marker streams cannot be reliably synchronized | Persist source monotonic timestamps and explicit wall/monotonic calibration anchors |
| Insufficient GPS provenance | `TripData` stores only coordinates and speed; provider and accuracy are logged then discarded | Invalid network fixes or unavailable speed can become false zero-speed observations | Store provider, source times, accuracy, speed validity, bearing, altitude, and quality flags |
| Missing study hierarchy | `Trip` has no session, corridor, direction, observation period, observer, or vehicle | Corridor, direction, period, and repeat analysis cannot be reconstructed | Add configurable corridor/session/trip metadata and stable identifiers |
| Event model is too small | Events are stored as timestamp plus one `eventCause` string | Primary/secondary attribution, provenance, location, confidence, and review are impossible | Add a dedicated versioned event entity and annotation relations |
| Photo ownership is unsafe | Photos begin with `tripId=0` and are reassigned by an Activity receiver and time range | Photos may be orphaned or assigned to the wrong trip | Link media directly to stable trip/event IDs at capture request time |
| Foreground startup is delayed by Vosk | `startForeground()` is called only after model preparation in `LoggerService.kt:274-356` | Android may kill the service before collection begins | Promote immediately with a preparation notification; load Vosk independently |
| Sensitive broadcasts are implicit | Location, recognized speech, and trip data use unrestricted broadcasts | Other applications may receive research location or speech data | Package-scope broadcasts or replace them with in-process state delivery |

### P0 implementation requirements

1. Do not report a trip as recording until a durable draft exists.
2. Cancel pending model preparation when STOP is received.
3. Reject START while a previous trip is finalizing.
4. Stop all producers before final draining and finalization.
5. Broadcast completion only after a validated database state transition.
6. Preserve incomplete rows and metadata for review/export.
7. Keep raw values and original timestamps immutable.
8. Make event and photo relationships stable without Activity lifetime assumptions.

## P1 Gaps: Required Before Main Research Collection

- No dedicated one-action neutral event marker independent of cause selection.
- No exactly-one primary cause plus zero-to-two validated secondary causes.
- No numeric attribution confidence using codes `0`, `1`, `2`, and `3`.
- No separate traffic-state code such as `LIGHT`, `MODERATE`, `DENSE_MOVING`, or
  `QUEUED`.
- No distinction between experienced location and source location.
- No original voice audio, durable transcript, or recognition provenance.
- No session/corridor configuration workflow.
- No device, mounting, sensor, app, schema, codebook, or sensor-profile metadata.
- No actual sampling-frequency, gap, GPS-quality, or interruption metrics.
- No post-trip human-readable QA summary.
- No CSV/ZIP research export, manifest, checksums, or media inventory.
- No incomplete-trip review and recovery workflow.
- No event correction or audit trail.
- No migration tests or service failure tests.
- No readiness dashboard for GPS, sensors, storage, battery, permissions, corridor,
  direction, and observation period.
- Normal queries include legacy `tripId=0` rows, which can mix data across trips.
- The foreground notification is not refreshed with current collection health.
- Sensor registration failures and absent optional sensors are mostly silent.
- The current cause taxonomy does not use the requested stable research codes and
  has no persisted version.
- Location, recognized speech, and file paths are written to logs.
- Android backup is enabled without explicit sensitive-data rules.

## P2/P3 Deferred Enhancements

These must not delay P0/P1 work:

- Map-based source-location correction and advanced event review.
- Complete offline map packs or predownload support.
- Exploratory live roughness previews.
- Advanced visual analytics.
- Machine-learning cause classification.
- Cloud synchronization or dashboarding.
- Real-time IRI prediction.
- Traffic simulation or real-time reference-speed modeling.

Roughness must remain exploratory. The collector must never infer that high vertical
acceleration proves that road roughness caused a slowdown.

## Phased Implementation Plan

### Phase 1: Stabilize the Recording Lifecycle

Introduce explicit service states:

```text
IDLE -> PREPARING -> RECORDING -> FINALIZING -> IDLE
                         \-> FAILED / ABORTED
```

Actions:

- Call `startForeground()` immediately on `ACTION_START` with a preparation
  notification.
- Create the draft trip synchronously or await its completion before accepting
  samples and reporting success.
- Store a stable trip UUID/session token and a heartbeat or last-write timestamp.
- Cancel pending starts when STOP arrives.
- Reject a second START while the service is preparing, recording, or finalizing.
- Stop location, sensor, speech, and media producers before draining the writer.
- Await writer completion before finalizing.
- Make finalization require the current status to be `RECORDING` and require exactly
  one affected row.
- Broadcast trip completion only after the transaction succeeds.
- Preserve failed or interrupted trips for review and export.

### Phase 2: Make Persistence Loss-Aware

Replace independent flush jobs and mutable shared session state with one immutable
recording context and one serialized writer.

The writer must:

- Have a bounded queue or bounded batch policy.
- Tag every sample with an immutable trip ID.
- Remove or acknowledge a batch only after `insertAll()` commits.
- Retry transient Room/storage failures with bounded backoff.
- Record failed batches, dropped rows, queue overflow, and writer interruptions.
- Expose fatal storage failure immediately to the operator.
- Drain deterministically on STOP.
- Avoid constructing a full duplicate trip-sized row list in memory.

Final counts should be reconciled with persisted rows or explicitly marked as
collection counters with a completeness status.

### Phase 3: Add the Research Data Model

Use a migration rather than destructive recreation. Add the minimum model needed to
represent the research design without hard-coding the planned number of corridors,
sessions, or trips.

Recommended entities:

- `Corridor`: stable ID, name, endpoints, approximate length, type, notes, optional
  geometry.
- `Session`: stable ID, date, corridor, period, observer, vehicle, phone/device,
  weather, wetness, notes, start/end timestamps.
- `Trip`: stable ID, session/corridor IDs, direction, period, pass number, timestamps,
  vehicle/observer/device IDs, diversion flag, validity/status, notes, and version
  metadata.
- `TripEvent`: stable event ID, trip ID, marker timestamps, experienced location,
  source location, speed, status, and provenance.
- `EventSecondaryCause`: event ID plus validated secondary cause code, limited to two.
- `TripPhoto` or `EventMedia`: stable media ID, trip/event IDs, capture metadata,
  usability and privacy status.
- `SensorMetadata`: sensor names, vendors, types, capabilities, selected profile,
  and registration result.
- `TripQuality`: observed rates, interval statistics, GPS quality, interruptions,
  storage failures, warnings, and validity.
- `AuditRevision`: immutable original/current values, editor, time, reason, and
  revision type.

Add foreign keys and composite indexes such as `(tripId, timestamp)`. New normal
queries must use exact ownership. Legacy `tripId=0` rows should not contaminate
ordinary trip queries.

### Phase 4: Correct Timestamp and GPS Acquisition

Persist the following without destructive conversion:

#### Sensor streams

- `SensorEvent.timestamp` in nanoseconds as the source monotonic timestamp.
- Callback receipt time separately, if useful for latency diagnostics.
- Raw X/Y/Z values for accelerometer and gyroscope.
- Raw rotation-vector values required to reproduce orientation.
- Sensor accuracy changes and selected sensor type.

#### Location stream

- `Location.elapsedRealtimeNanos` when available.
- `Location.time` epoch timestamp.
- Callback receipt time.
- Latitude and longitude.
- Horizontal accuracy.
- Speed and explicit speed-validity flag.
- Speed accuracy where supported.
- Bearing and bearing accuracy where supported.
- Altitude and provider/source.

#### Trip synchronization

- Paired wall-clock and elapsed-realtime anchors at start and end.
- Clock source and units in the export manifest.
- Any detected wall-clock discontinuity or calibration uncertainty.

GPS and sensors should use a dedicated collection thread or handler where practical.
Do not silently merge GPS and network observations without provenance. Preserve poor
fixes for later filtering and analysis.

### Phase 5: Implement Structured Event Marking

The passenger needs a large, fast, one-action marker. The immediate operation must:

1. Create a stable event ID.
2. Record the marker timestamp immediately.
3. Capture the nearest/current valid GPS location and speed if available.
4. Associate the event with the active trip.
5. Mark the annotation as pending rather than requiring a long form.

Later annotation must support:

- Exactly one primary cause.
- Zero to two distinct secondary causes.
- No duplicate primary/secondary cause.
- Numeric confidence code `0`, `1`, `2`, or `3`.
- Traffic-state code.
- Experienced-location fields.
- Source-location fields, source-visible flag, and optional location confidence.
- Free-text event notes.
- Manual, voice, or later-review provenance.
- Original values and correction revisions.

Manual markers must never be overwritten by automatic detection. Automatic detection,
if added later, must retain algorithm name/version, threshold, rule, and trigger time.

### Phase 6: Version the Cause Codebook

Use a single versioned source of truth for the primary taxonomy. The preferred
canonical codes are:

```text
SIG  Signal / traffic control
QUE  General traffic queue / traffic volume
BUS  Bus, microbus, tempo, or public-transport interference
PED  Pedestrian crossing/interference
PRK  Parking, pickup/drop-off, loading/unloading
TRN  Turning, merging, U-turn, or access-road conflict
ENC  Market, vendor, roadside activity, or encroachment
RDS  Road defect / roughness / pothole / trench / standing-water avoidance
INC  Crash, breakdown, construction, emergency, or temporary obstruction
UNK  Unknown / insufficient evidence
```

Persist with every relevant trip/event:

- Codebook version.
- Canonical code.
- Display label at the time of annotation where needed.
- Manual versus voice origin.
- Original transcript and recognition confidence where applicable.
- Exact/fuzzy match method and score.
- Matched phrase or variant.

Do not change research definitions silently during field collection.

### Phase 7: Fix Media Evidence

#### Photos

- Create a stable capture ID before requesting CameraX capture.
- Pass trip and event IDs directly to the capture path.
- Store request time and completed capture time separately.
- Store approximate location and its timestamp/accuracy.
- Store file reference, MIME type, size, checksum, dimensions, usability, and privacy
  processing status where practical.
- Keep capture asynchronous so it cannot stop sensor collection.
- Ensure delayed callbacks and Activity recreation do not orphan the record.

#### Voice annotations

- Preserve original audio in app-private storage.
- Store event linkage, recording start/end times, file reference, and status.
- Preserve generated transcript in addition to source audio.
- Do not block sensor collection while recording or transcribing.
- Keep rejected or low-confidence recognition behavior explicit and auditable.

### Phase 8: Add Readiness and Operational Health

Before starting, show:

- GPS enabled and recent fix/accuracy.
- Accelerometer active and registration result.
- Gyroscope availability.
- Orientation sensor availability.
- Storage available.
- Microphone and optional camera permission state.
- Battery level and optimization status.
- Selected corridor, direction, period, session, observer, and vehicle.
- App/configuration/schema versions.

Allow overrides only where scientifically acceptable, and record the reason and
validity impact.

During recording, show:

- Authoritative recording state.
- Elapsed time.
- GPS freshness, provider, and accuracy.
- Sensor health and last-sample age.
- Event-marker count.
- Storage and battery warnings.
- Selected corridor and direction.
- Write failures, dropped samples, and interruptions.

The notification should be refreshed at a low operational rate and must warn the
researcher immediately when required collection stops.

### Phase 9: Add QA Metrics

Implement pure, JVM-testable calculations for every stream:

- Sample count.
- Effective frequency.
- Median interval.
- P05/P95 interval.
- Longest gap.
- Duplicate timestamps.
- Nonmonotonic timestamps.
- First and last sample offsets.
- Expected versus observed coverage.

For GPS also calculate:

- Provider split.
- Median accuracy.
- Percentage below 5 m.
- Percentage below 10 m.
- Percentage above the configured poor-quality threshold.
- Maximum continuous outage.
- Time to first valid fix.
- Invalid or unavailable speed count.

Persist a human-readable and machine-readable trip QA summary containing:

```text
Trip ID
Corridor
Direction
Start
End
Duration
GPS samples and availability
Median GPS accuracy
Accelerometer samples and effective frequency
Gyroscope samples and effective frequency
Orientation samples and effective frequency
Event markers
Voice annotations
Photos
Audio recordings
Sensor interruptions
Storage/write warnings
Trip validity
```

Keep roughness and slowdown boundaries as derived outputs. Raw observations must
remain authoritative and reusable for later offline algorithms.

### Phase 10: Implement Research Export

Export must be local-first and must not depend on cloud infrastructure. Stream data
from Room so long trips do not require a full in-memory materialization.

Recommended archive:

```text
research-export/
  manifest.json
  sessions/sessions.csv
  trips/trips.csv
  events/events.csv
  events/annotations.csv
  sensors/trip_<id>.csv
  media/media_index.csv
  photos/<event-id>_<timestamp>.jpg
  audio/<event-id>.m4a
  metadata/device.json
  metadata/schema.json
  metadata/codebook.json
  qa/trip_<id>.json
  checksums.sha256
```

The exact shape may evolve, but the archive must define:

- Export timestamp.
- App version and build.
- Room schema version.
- Sensor profile/configuration version.
- Codebook version.
- Event detection version, when applicable.
- Device and Android metadata.
- Timestamp units and clock sources.
- Raw versus derived field classification.
- Session/trip/event/media counts.
- Sensor sample counts.
- QA warnings and completeness state.
- File sizes and SHA-256 checksums where practical.

Write the archive to a temporary path, validate counts and checksums, then publish
atomically through the Storage Access Framework. An export failure must not alter the
completed trip and must remain retryable.

Until this exists, rename the current misleading `STOP & EXPORT` action to
`STOP & SAVE`.

### Phase 11: Add Tests

#### JVM unit tests

- Sensor monotonic-to-wall-clock conversion.
- Nanosecond precision and timestamp ordering.
- GPS source-time and validity handling.
- Cause codebook validation.
- Primary/secondary cause rules.
- Confidence code validation.
- Trip state transitions.
- Immediate event-marker creation.
- Sampling-frequency and gap metrics.
- GPS-quality metrics.
- CSV escaping, nulls, units, and locale independence.
- Manifest generation and checksum calculation.
- Export count reconciliation.

#### Room/database tests

- `6 -> next` migration.
- Stable IDs and foreign keys.
- Trip/event/media relationships.
- Cascade deletion scoped to one trip.
- Completion only from `RECORDING`.
- Incomplete-trip preservation and recovery.
- No legacy `tripId=0` contamination in normal queries.
- Photo/event attachment transaction behavior.
- Schema identity and exported schema validation.

#### Service and failure tests

- Delayed draft creation followed by immediate STOP.
- START during previous finalization.
- STOP while Vosk is preparing.
- Vosk preparation failure after immediate foreground promotion.
- Writer insert failure and retry.
- Disk-full or SQLite failure.
- Queue backpressure and bounded-memory behavior.
- Missing accelerometer, gyroscope, or rotation sensor.
- Sensor registration failure.
- GPS unavailable or poor accuracy.
- Process/service interruption and recovery.
- Finalization failure emits no success broadcast.

#### UI/instrumentation tests

- Session/trip setup.
- Readiness verification.
- Start trip.
- One-action event marker.
- Attach primary/secondary cause and confidence.
- Attach voice and photo evidence.
- Activity recreation during recording.
- Stop and completion.
- Local export.
- Recovery/review of an interrupted trip.

Instrumentation requires a connected device or emulator. If none is available, report
those tests as not run rather than treating JVM tests as a substitute.

### Phase 12: Complete Documentation

Create and maintain:

- `docs/research-data-audit.md`: requirement status table and data-coverage matrix.
- `docs/field-failure-analysis.md`: research-data loss and contamination scenarios.
- `docs/research-data-readiness.md`: final readiness status and pilot recommendation.
- Researcher data-collection guide.
- Export data dictionary.
- Timestamp and coordinate-system specification.
- Architecture and storage notes.
- Sensor metadata and sampling QA notes.
- Recovery, backup, privacy, and media-retention instructions.

The data dictionary must define every exported field, type, unit, allowed value,
nullable status, timestamp clock source, raw/derived classification, and validation
rule.

### Phase 13: Verification and Pilot Gate

Run, where the environment permits:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

Before pilot approval, perform a device/manual check covering:

- Screen-off recording.
- Background Activity destruction and recreation.
- Incoming call interruption.
- GPS loss and recovery.
- Sensor unavailability.
- Storage warning/failure.
- Battery optimization behavior.
- Camera delay/failure.
- Voice recording failure.
- Process kill and unfinished-trip recovery.
- Export validation on a separate machine.

## Required Readiness Criteria

The application should not be considered pilot-ready until all of the following are
true:

- Raw GPS, accelerometer, gyroscope, and orientation data are preserved with
  reproducible source timestamps.
- GPS accuracy and provider provenance are stored.
- A trip cannot complete successfully if its durable writer failed.
- Interrupted trips are preserved rather than deleted.
- Session, corridor, direction, and observation period are persisted.
- A one-action event marker exists.
- Structured primary/secondary cause annotation and confidence are validated.
- Event experienced/source location fields exist or are explicitly marked missing.
- Photo and voice evidence have stable event relationships.
- App/schema/sensor/codebook versions are stored.
- QA metrics report observed rates, gaps, GPS quality, and warnings.
- A verified offline export contains raw data, annotations, media indexes, metadata,
  and a manifest.
- Automated tests cover migration, state, persistence failure, timestamp, QA, and
  export invariants.

## Pilot Recommendation

Until the P0 lifecycle, timestamp, GPS provenance, persistence, media ownership,
research-model, and export requirements are implemented and tested:

```text
NOT READY FOR PILOT
```

After P0 and essential P1 work is verified on representative devices, the status may
move to:

```text
READY FOR PILOT WITH KNOWN LIMITATIONS
```

P2/P3 analytics and cloud features should remain deferred until collection integrity
and export validation are stable.
