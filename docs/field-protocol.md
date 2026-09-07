# Field Protocol

## Before Collection

1. Confirm the Kathmandu local date and approved period-window protocol.
2. Select one period: `MORNING`, `OFF_PEAK`, or `EVENING`.
3. Select one direction: `A_TO_B` or `B_TO_A`.
4. The app assigns the static study session and corridor IDs automatically.
5. Check location, microphone, accelerometer, gyroscope, orientation, storage,
   battery, and battery-optimization status.

## During a Trip

- The driver starts and stops the app while parked, then operates no controls while moving.
- Keep the foreground-service notification active.
- Speak one hands-free command at the observed slowdown: `log [CAUSE]`.
- Valid examples include `log signal`, `log queue`, `log bus`, `log pedestrian`,
  `log roughness`, `log construction`, `log friction`, `log turning`,
  `log market`, and `log unknown`.
- `UNKNOWN` is valid when the cause cannot be classified. Unmatched,
  low-confidence, and ambiguous commands create no event.
- Each accepted command creates one event; repeated commands are not deduplicated.
- GPS and motion sensors provide context only. Vibration, weather, congestion,
  personal stops, and route/app failures are not automatic cause detections.
- Do not change direction or period after recording starts.
- No current-trip photos are captured.

## Completing a Trip

1. Stop deliberately and wait for the local `Trip saved` status.
2. Review event provisional causes, transcripts, GPS age, sensor/audio warnings,
   interruption state, and partial coverage.
3. Replace a provisional cause only with one canonical primary value, including
   `UNKNOWN`; preserve the provisional value and audit revision.
4. Mark the trip `VALID`, `VALID_WITH_WARNINGS`, or `INVALID` after review.
5. Mark `INCIDENT_OR_BREAKDOWN` only for a crash, breakdown, or emergency that
   invalidates the traversal. Do not use it for weather, congestion, personal
   stops, route diversion, or application failure.
6. Retain interrupted or partial trips. Consider only their covered range later.
7. Treat the reverse traversal as a separate trip.

## Export And Purge

- Export `RESTRICTED_RAW` for controlled research storage and `PUBLIC_DEIDENTIFIED`
  only for sharing outside the restricted data boundary.
- Verify manifest counts, mode flags, version metadata, and `checksums.sha256`.
- Export the restricted archive before a researcher-initiated purge.
- A purge removes database rows and known media files; any missing or failed file
  cleanup is reported and must be recorded with the purge result.
