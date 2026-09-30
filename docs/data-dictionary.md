# Export Data Dictionary

All restricted timestamps are decimal integers. Epoch timestamps use Unix
milliseconds; elapsed and sensor timestamps use Android monotonic nanoseconds.
Public exports replace absolute times with per-trip `elapsed_ms` values and omit
precise coordinates. Raw restricted fields are authoritative; derived fields are
reproducible summaries or offline assignments.

## Archive Modes

| Mode | Intended boundary | Includes |
|---|---|---|
| `RESTRICTED_RAW` | Controlled research storage | Raw GPS, sensor timing, audio, transcripts, device metadata, audit values, and historical media when present |
| `PUBLIC_DEIDENTIFIED` | External sharing | Relative-time trip/event/sensor data, canonical causes, QA flags, and version metadata; no precise GPS, audio, transcripts, reviewer notes, or device identity |

Both modes include `manifest.json`, `metadata/schema.json`,
`metadata/codebook.json`, per-trip runtime cause configuration files, and
`checksums.sha256`.

## Trip Fields

Restricted `trips/trips.csv` includes the local `id`, `trip_uuid`, absolute start/end
times, raw timestamps, static `session_id`/`corridor_id`, direction, period, QA
fields, interruption fields, version fields, `cause_config_path`, and
`exclusion_code`. Public `trips/trips.csv` contains the pseudonymous
`trip_uuid`, duration, summary counts, direction/period, QA flags, exclusion,
version fields, and `cause_config_path`, but no absolute time, local ID, device
ID, coordinates, or free-text notes.

Trip context fields are captured on new drafts before Vosk preparation. Restricted
exports include `driver_id`, `vehicle_id`, vehicle profile fields, `weather`,
`road_wetness`, `route_diversion`, `non_traffic_stop`, `context_note`, and
`context_collected_at_ms`. Public exports include vehicle profile and the
non-identifying category fields plus `context_collected`; driver/vehicle IDs and
the free-text context note are omitted. `context_collected_at_ms` is null for
historical rows created before this context contract, so those rows must not be
interpreted as having collected `route_diversion=NO`.

Allowed context values are weather `CLEAR`, `CLOUDY`, `LIGHT_RAIN`, `HEAVY_RAIN`,
`OTHER`; road wetness `DRY`, `DAMP`, `WET`, `STANDING_WATER`, `UNKNOWN`; and
non-traffic stop `NONE`, `PERSONAL`, `FUEL_OR_MAINTENANCE`, `RESEARCH_SETUP`,
`POLICE_OR_ADMINISTRATIVE`, `OTHER`. `INCIDENT_OR_BREAKDOWN` remains an
exclusion code and is never a cause value.

`exclusion_code` is nullable or `INCIDENT_OR_BREAKDOWN`. It excludes a trip from
analysis without deleting its raw data.

## Event Fields

| Field | Restricted | Public | Meaning |
|---|---|---|---|
| `event_id` | Yes | Yes | Stable event identity |
| `trip_id` | Yes | No | Local owning-trip ID |
| `trip_uuid` | Yes | Yes | Pseudonymous owning-trip identity |
| `marker_time_ms` | Yes | No | Absolute marker time |
| `marker_elapsed_ms` | No | Yes | Marker time relative to trip start |
| `experienced_latitude/longitude` | Yes | No | Experienced location |
| `source_latitude/longitude` | Yes | No | Reviewed source location |
| `provisional_cause_code` | Yes | Yes | Accepted voice candidate retained for provenance |
| `primary_cause_code` | Yes | Yes | One canonical current/reviewed cause |
| `codebook_version` | Yes | Yes | Existing event version field retained for compatibility; earlier app reviews may have overwritten it |
| `provisional_codebook_version` | Yes | Yes | Capture taxonomy resolved from consistent trip/config metadata; event metadata is used only when annotation history is absent; null means uncertain |
| `primary_codebook_version` | Yes | Yes | Latest annotation revision taxonomy, or provisional taxonomy when no review exists; null means uncertain |
| `provenance` | Yes | Yes | Current input is `VOICE_RECOGNIZED`; migrated pre-v8 rows use `LEGACY_IMPORTED` |
| `transcript` | Yes | No | Recognized speech text |
| `confidence_code` | Yes | Yes | Review confidence `0` through `3` |
| `traffic_state` | Yes | Yes | `LIGHT`, `MODERATE`, `DENSE_MOVING`, or `QUEUED` |

Codebook v4 causes are `SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`,
`CONSTRUCTION`, `TURNING`, `FRICTION`, `MARKET`, `UNKNOWN`,
`SLOW_LEAD_VEHICLE`, `MERGING`, `LEAD_TURN`, `CROSSING_TURN`, `PARKED_BIKE`,
`PARKED_CAR`, `DELIVERY_STOP`, and `SPEED_BREAKER`. Exact code meanings and
version-specific definitions are in `metadata/codebook.json`; per-trip
`cause_config_path` preserves the configuration used at capture when available.
For v3 records, original labels and meanings are retained. Missing historical
configuration remains unknown rather than being reconstructed as v4. In
particular, v3 `TURNING` and `FRICTION` retain their legacy voice-category
meanings; the v4 residual definitions must not be applied retroactively.

The current voice parser accepts one complete, unique command; ambiguous,
multi-cause, extra-token, unmatched, and low-confidence speech is not an event.
Accepted voice results are provisional, preserve recognized text and
recognition confidence, and have exactly one scalar primary cause. No real-time
speed-drop check gates capture, including for `SPEED_BREAKER`; research review
must apply the study episode rule. `TURNING` and `FRICTION` are bounded residual
causes in v4 only; v3 meanings remain unchanged.

The event `codebook_version` column is retained for compatibility. New code
records keep it as capture-time metadata; older reviewed rows may reflect the
prior app behavior that overwrote it. The added explicit version columns resolve
the provisional label from consistent trip/config evidence, falling back to
event metadata only when there is no annotation history, and resolve the current
primary from the latest annotation revision. Conflicts or unavailable evidence
are exported as unknown version, not assigned the current version.

## Sensor Rows

Restricted `sensors/trip_<local-id>.csv` preserves GPS coordinates, provider,
accuracy, speed/bearing, epoch and monotonic source timestamps, motion axes,
quaternion values, callback times, sensor types, and source type. Public sensor
files use `sensors/trip_<trip-uuid>.csv`, relative time, canonical event cause,
speed, motion values, and sensor type/accuracy only; they contain no GPS fields
or device-local row IDs.

## Audio, Audit, And Historical Media

Restricted audio rows identify AAC-LC M4A segments by `audio_id`, sequence,
timestamps, persisted status, final audio status, size, checksum, transcript,
interruption reason, failure type, completeness, and file presence/usability.
Present audio files are grouped by trip at
`audio/trip_<trip-id>_<trip-start-date>/<audio-id>.m4a`; the trip start date is
formatted as `YYYY_MM_DD` in the trip's recorded time zone, falling back to the
study time zone when it is missing or invalid. The shared
`audio/audio_index.csv` records each file's `archive_path`.
Failure types include encoder initialization, frame processing, segment
finalization, interruption, and missing expected file. Missing or failed
segments remain indexed. Public archives contain no audio index or audio files,
but `qa/trips.json` retains the audio failure summary.

Restricted `audit/revisions.csv` retains append-only corrections and legacy
non-primary values plus archival-audio failure status, type, reason, and file
evidence. Public archives omit audit rows and reviewer/free-text data.
Legacy photo rows and files are retained only for historical compatibility and
restricted export/purge handling; current collection does not create photos.
