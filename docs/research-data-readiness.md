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
| Event marking | PASS |
| Structured annotation | WARN |
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
- AAPT2 build verification is unavailable on the current ARM64 host because the
  installed AGP AAPT2 binary is x86_64-only.

## Pilot Recommendation

```text
NOT READY FOR PILOT
```

Move to `READY FOR PILOT WITH KNOWN LIMITATIONS` only after schema 7 is generated
and validated, export counts/checksums pass, and representative devices complete
screen-lock, process-interruption, sensor, and audio checks.
