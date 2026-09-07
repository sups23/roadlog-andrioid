# Research Data Readiness

## Status

| Capability | Status |
|---|---|
| Repeated traversal support | PASS |
| Direction | PASS |
| Observation period | PASS |
| Study-date integrity | WARN |
| Session/trip identity | PASS |
| Raw GPS | PASS |
| GPS accuracy | PASS |
| Accelerometer | PASS |
| Gyroscope | PASS |
| Orientation | PASS |
| Timestamp synchronization | WARN |
| Background recording | WARN |
| Crash recovery | WARN |
| Event marking | WARN |
| Structured annotation | PASS |
| Event provenance | WARN |
| Experienced/source location | WARN |
| Trip QA | WARN |
| Repeated-day integrity | PASS |
| Export | WARN |
| Schema migration | WARN |
| Versioning | PASS |

## Known Limitations

- The collector uses the static `STUDY_CORRIDOR` identifier; route geometry and
  endpoint labels are maintained outside the collector for offline analysis.
- Exact Kathmandu observation-period suggestion windows are not frozen. Stored
  values remain explicit and use `Asia/Kathmandu`.
- A process-interrupted traversal is preserved as partial and must be reviewed;
  covered segments may later be analyzed, but the app does not assign segments.
- Continuous audio uses segmented AAC/M4A and requires physical-device storage,
  encoder, privacy, and interruption testing.
- Current collection accepts only driver-operated hands-free voice commands and
  stores one canonical primary cause plus provisional provenance. Legacy photo
  rows remain readable but no new collection path creates photos.
- `RESTRICTED_RAW` and `PUBLIC_DEIDENTIFIED` exports have different privacy
  contracts; public archives omit precise GPS, audio, transcripts, reviewer data,
  and device identity.
- A trip may be explicitly excluded with `INCIDENT_OR_BREAKDOWN` after collection.
- AAPT2 build verification is unavailable on the current ARM64 host because the
  installed AGP AAPT2 binary is x86_64-only.

## Pilot Recommendation

```text
NOT READY FOR PILOT
```

Move to `READY FOR PILOT WITH KNOWN LIMITATIONS` only after schema 8 is generated
and validated, both export modes pass count/privacy/checksum checks, and
representative devices complete screen-lock, process-interruption, sensor,
audio, and voice-command checks.
