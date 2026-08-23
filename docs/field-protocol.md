# Field Protocol

## Before Collection

1. Confirm Kathmandu local date and the approved period-window protocol.
2. Select one period: `MORNING`, `OFF_PEAK`, or `EVENING`.
3. Select direction: `A_TO_B` or `B_TO_A`.
4. The app assigns the static study session and corridor IDs automatically.
5. Check location permission, recent GPS accuracy, accelerometer, gyroscope,
   orientation sensor, microphone, storage, battery, and battery optimization.

## During a Trip

- The passenger performs all interaction. The driver does not operate the app.
- Start only after the direction and period are confirmed.
- Keep the foreground-service notification active.
- Tap the matching cause button immediately when a slowdown is observed. Use
  `UNCLASSIFIED` when no exact cause is identifiable, or say `log unclassified`.
- Optional voice commands, continuous audio, photos, and later annotations are
  secondary evidence. Skip them if unsafe.
- Do not interpret vibration as proof of a road-caused slowdown.
- Do not change direction or period fields after recording starts.

## Completing a Trip

1. Stop the trip deliberately and wait for `Trip saved`.
2. Review the trip QA summary and warnings.
3. Annotate events with one primary cause, zero to two secondary causes, traffic
   state, and confidence.
4. Mark the trip `VALID`, `VALID_WITH_WARNINGS`, or `INVALID` only after review.
5. For a partial interruption, retain the trip as partial. Only the covered
   segment range may be considered later.
6. Select the reverse direction or next period for the next traversal.

## Export

Export locally after collection. Verify trip/event/sample/audio counts and the
archive checksum list before transferring the controlled research archive.
