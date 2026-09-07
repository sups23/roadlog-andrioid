# Research Data Audit

## Single-Corridor Protocol

RoadLog records repeated `A_TO_B` and `B_TO_A` traversals of the configured
Kathmandu corridor. Session and corridor IDs are static trip-level metadata;
route geometry, endpoint labels, vehicle metadata, weather, wetness, personal
stops, and study-calendar decisions remain external research inputs.

| Requirement | Status | Evidence in implementation | Remaining gate |
|---|---|---|---|
| Repeated trips and direction/period identity | Implemented | `ResearchStudy`, `ResearchDirection`, `ObservationPeriod`, unique `tripUuid` | Physical repeated-day check |
| Raw GPS and motion streams | Implemented | `TripData`, provider/accuracy/source clocks, sensor metadata | Physical sensor-rate and screen-lock check |
| One active event trigger | Implemented | `CauseCommandParser` and `LoggerService` accept activated hands-free voice only; older migrated rows are marked `LEGACY_IMPORTED` | Physical noisy-road voice check |
| Canonical cause vocabulary | Implemented | Codebook v3 and `CauseCodeMigration` | Freeze before pilot |
| Ambiguous/unknown speech handling | Implemented | `CauseCommandRejection`; `UNKNOWN` is explicit and canonical | Physical recognition validation |
| Provisional/reviewed cause provenance | Implemented | `TripEvent.provisionalCauseCode`, one reviewed primary, annotation revisions | Reviewer identity policy if required |
| Non-primary legacy values | Implemented | v8 migration records old values in `audit_revisions`; active schema has no secondary model | Migration test on representative database |
| Incident/breakdown exclusion | Implemented | `Trip.exclusionCode`, post-trip review control, export flag | Confirm researcher decision procedure |
| Continuous source audio | Implemented with warning paths | Shared `AudioRecord`, AAC-LC segments, hashes/statuses, persisted QA warnings | Physical encoder/storage/interruption check |
| Current photography | Removed | No camera permission, CameraX dependency, or collection control | None; legacy rows remain readable |
| Restricted/public exports | Implemented with warning paths | `ResearchExportMode`, raw and de-identified archive contracts, checksums | Archive round-trip/privacy test |
| Controlled purge | Implemented with report | `deleteTripWithMediaFiles`, reviewed-trip purge confirmation | Verify failed-file handling on device |
| Room migration | Implemented with warning | Version 8 and `MIGRATION_7_8` | Generate/review schema 8 and run instrumentation |

## Current Gate

```text
NOT READY FOR PILOT
```

The remaining gates are schema snapshot validation, JVM/instrumentation test
execution, export privacy/count checks, physical recording/audio/voice checks,
and approval of external corridor, period-window, and vehicle metadata inputs.
