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
| `provenance` | Yes | Yes | Current input is `VOICE_RECOGNIZED`; migrated pre-v8 rows use `LEGACY_IMPORTED` |
| `transcript` | Yes | No | Recognized speech text |
| `confidence_code` | Yes | Yes | Review confidence `0` through `3` |
| `traffic_state` | Yes | Yes | `LIGHT`, `MODERATE`, `DENSE_MOVING`, or `QUEUED` |

Canonical causes are `SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`, `CONSTRUCTION`,
`FRICTION`, `TURNING`, `MARKET`, and `UNKNOWN`. Friendly voice aliases are
normalized before persistence. Ambiguous or unmatched commands are not events.

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
Failure types include encoder initialization, frame processing, segment
finalization, interruption, and missing expected file. Missing or failed
segments remain indexed. Public archives contain no audio index or audio files,
but `qa/trips.json` retains the audio failure summary.

Restricted `audit/revisions.csv` retains append-only corrections and legacy
non-primary values plus archival-audio failure status, type, reason, and file
evidence. Public archives omit audit rows and reviewer/free-text data.
Legacy photo rows and files are retained only for historical compatibility and
restricted export/purge handling; current collection does not create photos.
