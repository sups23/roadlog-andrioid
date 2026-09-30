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
- v4 examples include `log signal`, `log queue`, `log bus`, `log pedestrian`,
  `log roughness`, `log construction`, `log market`, `log unknown`,
  `log slow lead` (or `log slow lead vehicle`), `log merging`, `log lead turn`, `log crossing turn`,
  `log parked bike`, `log parked car`, `log delivery stop`, and
  `log speed breaker`. The bounded residual commands are `log other turn` and
  `log side obstruction`.
- Speak one cause command only. Extra cause words, multiple cause tokens,
  unmatched phrases, and low-confidence commands are rejected. Legacy broad
  phrases such as `log turning`, `log u turn`, `log friction`, `log parked`,
  and `log speed bump` are not mapped to a specific v4 cause; say `log unknown`
  when the cause cannot be classified.
- Every accepted command creates one event with one provisional scalar cause,
  recognized text, timestamp, and speech confidence. RoadLog does not require a
  real-time measured speed drop before recording any cause. In particular,
  `log speed breaker` is saved regardless of GPS speed; it does not itself
  validate a study slowdown episode. Do not treat ordinary manoeuvring as a
  validated speed-breaker event.
- `UNKNOWN` is valid when the cause cannot be classified. Repeated accepted
  commands are not deduplicated.
- GPS and motion sensors provide context only. Vibration, weather, congestion,
  personal stops, and route/app failures are not automatic cause detections.
- `QUEUE` describes general dense or stationary downstream traffic; a single
  constrained lead vehicle is `SLOW_LEAD_VEHICLE`, not automatically a queue.
  `ROUGH` concerns surface defects, not speed breakers.
- `LEAD_TURN`, `CROSSING_TURN`, and `MERGING` describe another vehicle's
  interference. The rider's own planned turn is none of these causes.
- `DELIVERY_STOP` requires observed evidence of delivery, collection, loading,
  or unloading; do not infer purpose from vehicle type or presence. Parked-bike
  and parked-car labels require direct obstruction of usable road space.
- v4 `TURNING` is the bounded residual for an impeding other-vehicle turn/U-turn
  that does not enter the rider's path as `MERGING`, turn out of the lane ahead
  as `LEAD_TURN`, or cross from oncoming traffic as `CROSSING_TURN`. v4
  `FRICTION` is a directly impeding lateral obstruction that cannot be assigned
  to a more specific cause; vehicle interactions and surface defects are
  excluded. The rider's own turn and mere roadside presence do not qualify.
- v3 trip labels keep their v3 meanings. Review presents each event's provisional
  capture label separately from its current reviewed primary and codebook version.
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
