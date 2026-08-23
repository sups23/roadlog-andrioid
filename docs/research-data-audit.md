# Research Data Audit

## Single-Corridor Intensive Study Migration

This audit describes the v7 implementation for one Kathmandu study corridor.
The collector assigns static session and corridor IDs automatically; route
geometry and segment definitions remain offline analysis inputs.

| Requirement | Status | Current Implementation | Single-Corridor Impact | Required Change | Priority |
|---|---|---|---|---|---|
| Corridor architecture | Implemented | Static `ResearchStudy.CORRIDOR_ID` stored on every trip | No corridor setup is shown to the operator | Add offline corridor geometry before analysis | P1 |
| Repeated-trip support | Implemented | Room auto IDs and unique trip UUIDs; no metadata uniqueness constraint | Same direction/period/date may repeat | Add repeated-identical-metadata instrumentation coverage | P0 |
| Direction | Implemented | Canonical `A_TO_B` and `B_TO_A` spinner values and service validation | Direction is preserved per traversal | Add final endpoint labels | P0 |
| Observation period | Implemented | `MORNING`, `OFF_PEAK`, `EVENING`; explicit selection | Peak labels are displayed without deriving stored values | Freeze exact suggestion windows later | P1 |
| Study date | Partially implemented | Local Kathmandu date and timezone are stored on every trip | Date is explicit at creation | Add date correction audit workflow if required | P1 |
| Session model | Implemented | Static `ResearchStudy.SESSION_ID` is stored on every trip | Direction, period, and date remain trip-level variables | No session setup UI required | P1 |
| Trip QA | Partially implemented | QA status, notes, interruption and partial-coverage fields exist | Half-recorded traversals can be reviewed as warnings | Add richer QA metrics UI | P1 |
| Raw GPS and accuracy | Implemented | Raw coordinates, provider, source times, accuracy, speed validity, bearing | Offline filtering remains possible | Verify on physical devices | P0 |
| Accelerometer/gyro/orientation | Implemented | Raw axes/quaternion and source timestamps are retained | Sensor gaps remain measurable | Verify actual rates and registration health | P0 |
| Timestamp synchronization | Partially implemented | Epoch and `elapsedRealtimeNanos` anchors are stored | Cross-stream alignment is reproducible to millisecond derived precision | Add device clock-discontinuity tests | P0 |
| Categorized event marker | Implemented | Cause buttons and voice create immediate `TripEvent` rows with GPS snapshots | `UNK`/`UNCLASSIFIED` covers uncertain attribution | Test abrupt process interruption after marker | P0 |
| Event provenance | Partially implemented | Manual and voice-recognized provenance plus transcript are retained | Automatic detector provenance is reserved but not emitted | Freeze input-method vocabulary | P1 |
| Structured annotation | Partially implemented | Primary, secondaries, traffic state, confidence, revision table, detail dialog | Review can happen after collection | Add reviewer identity and source-location UI | P1 |
| Experienced/source location | Partially implemented | Separate event columns and source-location audit DAO | Source edits cannot overwrite experienced coordinates | Add map or coordinate editor | P1/P2 |
| Continuous voice | Partially implemented | Shared Vosk `AudioRecord` frames feed segmented AAC/M4A archival files | Whole-trip audio is local and segment-preserving | Verify encoder/storage behavior on devices | P0 |
| Photos | Partially implemented | Durable capture IDs and event/trip ownership | Camera remains optional evidence | Add pending-file reconciliation | P1 |
| Offline export | Partially implemented | ZIP contains trips, events, annotations, media, audio, sensors, QA, metadata and checksums; static IDs are in trips and manifest | No cloud dependency or redundant session/corridor files | Add archive count/checksum integration test | P0 |
| Background survival | Partially implemented | Foreground service and periodic Room writes | UI state can be queried after recreation | Verify screen lock and process interruption | P0 |
| Crash recovery | Partially implemented | Interrupted drafts become recoverable and preserve samples | Partial traversal is not treated as complete | Add operator recovery actions and device tests | P0 |
| Versioning | Partially implemented | App, schema, codebook, sensor profile and timezone fields | Collection provenance is explicit | Freeze codebook/profile before pilot | P1 |
| Migration safety | Partially implemented | Direct v6 to v7 migration and migration test added | v7 has not shipped, so migration can still be corrected | Generate and review schema 7 | P0 |

## Current Gate

```text
NOT READY FOR PILOT
```

The remaining pilot gates are schema validation, physical-device recording and
audio verification, exact period-window approval, offline corridor geometry
approval, and a complete export round-trip test.
