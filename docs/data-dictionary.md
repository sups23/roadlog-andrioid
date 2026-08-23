# Export Data Dictionary

All timestamps are exported as decimal integers. Epoch timestamps use Unix
milliseconds. `elapsed_realtime` and sensor timestamps use Android monotonic
nanoseconds. Raw fields are authoritative; derived fields are reproducible
summaries or offline assignments.

## Trips

| Field | Type / unit | Allowed values | Nullable | Raw/derived | Meaning / source |
|---|---|---|---|---|---|
| `trip_uuid` | string | UUID | No | Raw identity | Stable traversal identity |
| `session_id` | string | `STUDY_SESSION` | No | Raw | Static study session identity |
| `corridor_id` | string | `STUDY_CORRIDOR` | No | Raw | Static selected-corridor identity |
| `direction` | string | `A_TO_B`, `B_TO_A` | No for research trips | Raw | Directional analytical variable |
| `study_date_local` | ISO date | `YYYY-MM-DD` | No for new trips | Raw | Kathmandu local collection date |
| `time_zone_id` | string | `Asia/Kathmandu` | No for new trips | Raw metadata | Date interpretation zone |
| `observation_period` | string | `MORNING`, `OFF_PEAK`, `EVENING` | No for research trips | Raw | Explicit field-selected period |
| `qa_status` | string | `UNREVIEWED`, `VALID`, `VALID_WITH_WARNINGS`, `INVALID` | No | Reviewed/derived | Research usability decision |
| `partial_traversal` | Boolean | `true`, `false` | No | QA | Whether collection ended before full traversal |
| `coverage_end_time_ms` | epoch ms | integer | Yes | Raw/QA | Last preserved coverage boundary |
| `route_diversion` | Boolean | `true`, `false` | No | QA | Observer-reported route deviation |
| `recording_interruption` | Boolean | `true`, `false` | No | QA | Recording/service interruption |
| `gps_interruption` | Boolean | `true`, `false` | No | QA | GPS stream interruption |
| `sensor_interruption` | Boolean | `true`, `false` | No | QA | Motion-sensor interruption |
| `sensor_profile_version` | string | version | Yes | Metadata | Requested sampling configuration |
| `codebook_version` | string | version | Yes | Metadata | Cause taxonomy version |

## Device and Sensor Metadata

`metadata/device.json` records `device_id`, manufacturer, device model, Android
version, app version, Room schema version, and sensor profile version. Observer,
vehicle, pass, and mount fields are intentionally not collected.
`metadata/sensors.csv` records the selected profile, requested period,
registration result, sensor name/vendor/version, and the raw sensor-type code,
including unavailable sensor rows.

## Sensor Rows

`sensors/trip_<id>.csv` preserves raw GPS, accelerometer, gyroscope, rotation,
and event compatibility rows. GPS includes latitude, longitude, speed,
provider, reported accuracy, bearing, altitude, source epoch time, and source
elapsed time. Motion rows preserve raw axis/quaternion values, sensor source
nanoseconds, callback time, sensor type, and sensor accuracy.

`timestamp_ms` is a derived alignment timestamp. `raw_timestamp` and
`source_timestamp_nanos` must be used when maximum precision is required.

## Events and Annotations

| Field | Type / unit | Allowed values | Nullable | Raw/derived | Meaning |
|---|---|---|---|---|---|
| `event_id` | string | UUID | No | Raw identity | Stable slowdown marker identity |
| `trip_id` / `trip_uuid` | integer/string | local ID plus UUID | No | Relationship | Owning traversal |
| `marker_time_ms` | epoch ms | integer | No | Raw | Immediate marker time |
| `experienced_latitude/longitude` | decimal degrees | WGS84 | Yes | Raw/observed | Where slowdown was experienced |
| `source_latitude/longitude` | decimal degrees | WGS84 | Yes | Reviewed | Believed source location; separate from experienced location |
| `event_provenance` | string | `MANUAL_MARKER`, `VOICE_RECOGNIZED`, `AUTO_DETECTED`, `MANUAL_AND_AUTO`, `REVIEW_CREATED` | No | Provenance | Evidence origin |
| `primary_cause` | string | `SIG`, `QUE`, `BUS`, `PED`, `PRK`, `TRN`, `ENC`, `RDS`, `INC`, `UNK` | Yes until reviewed | Reviewed | Exactly one reviewed primary cause; `UNK` is displayed as `UNCLASSIFIED` |
| `secondary_cause_1/2` | string | same cause codes | Yes | Reviewed | Zero to two distinct contributors |
| `traffic_state` | string | `LIGHT`, `MODERATE`, `DENSE_MOVING`, `QUEUED` | Yes | Reviewed | Background traffic state |
| `confidence` | integer | `0`, `1`, `2`, `3` | Yes | Reviewed | Attribution confidence |
| `annotation_timestamp_ms` | epoch ms | integer | No | Raw revision metadata | Time annotation was submitted |
| `annotation_version` | integer | positive | No | Revision metadata | Append-only revision number |

Audio segments are AAC-LC M4A files linked to a trip by `audio_id`, sequence,
start/end epoch time, start/end monotonic time, codec metadata, file size,
checksum, and completion status.

Photo rows retain a stable `capture_id`, event link, request/capture epoch and
monotonic timestamps, location provenance, dimensions, checksum, usability, and
privacy status. Missing or failed captures remain indexed rather than silently
disappearing from the export.
