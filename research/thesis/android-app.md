# RoadLog Android App

## Purpose

RoadLog is the local-first Android collector for repeated traversals of one
configured Kathmandu corridor. The thesis compares both directions across the
`MORNING`, `OFF_PEAK`, and `EVENING` periods and repeated study dates. Corridor
geometry, endpoint labels, weather, wetness, personal stops, vehicle metadata,
and the study calendar remain external research inputs.

The app must preserve evidence and provenance without implying causal certainty.
Raw GPS, motion, audio, and recognition data are research evidence; roughness,
delay, and cause summaries are derived outputs.

## Current Collection Contract

- Required permissions are fine location and microphone.
- The operator selects `A_TO_B` or `B_TO_A` and one observation period before starting.
- The driver starts/stops while parked and uses no controls while moving.
- Accepted event commands use the activated grammar `log [CAUSE]`.
- Canonical causes are `SIGNAL`, `QUEUE`, `BUS`, `PED`, `ROUGH`,
  `CONSTRUCTION`, `FRICTION`, `TURNING`, `MARKET`, and `UNKNOWN`.
- `log unknown` and `log unclassified` store `UNKNOWN`. Unmatched,
  low-confidence, and ambiguous commands create no event.
- Each accepted command creates one event. Events retain provisional cause,
  transcript, recognition confidence, source location context, and
  `VOICE_RECOGNIZED` provenance.
- Review replaces the provisional value with one canonical primary cause and
  appends an annotation revision. Legacy non-primary values are retained in the
  generic audit trail during the v7-to-v8 migration.
- No current collection path requests camera permission or captures photos.
  Historical photo rows remain readable and are handled only by restricted
  export and purge paths.
- `INCIDENT_OR_BREAKDOWN` is an explicit post-trip exclusion flag. It is not
  inferred from weather, congestion, personal stops, diversion, or app failure.

## Architecture

```text
MainActivity
  selects direction/period, requests location+mic, displays voice/service status,
  and starts or stops LoggerService
        |
        v
LoggerService (non-exported foreground service)
  GPS + accelerometer + gyroscope + rotation sensors
  shared AudioRecord -> Vosk recognition + AAC-LC segment archive
  bounded Room writes, interruption recovery, and QA metrics
        |
        v
AppDatabase (Room schema 8)
  raw samples, trips, dedicated events, annotations, audit, sensor metadata,
  trip quality, audio segments, and legacy historical photos
        |
        v
TripHistoryActivity / TripDetailActivity
  review, annotation, source-location correction, exclusion, audio playback,
  historical media display, and controlled purge
```

## Recording Lifecycle

```text
IDLE -> PREPARING -> RECORDING -> FINALIZING -> IDLE
                  \-> FAILED / ABORTED / RECOVERABLE
```

1. `ACTION_START` validates direction and period.
2. The service promotes itself to the foreground and creates a durable
   `RECORDING` trip draft with UUID and version metadata.
3. Sensor rows and accepted voice events are buffered with immutable trip IDs.
4. Room writes are serialized in bounded batches and retained for retry until
   the transaction succeeds.
5. On explicit stop, producers stop, audio persistence is awaited, buffers are
   flushed, quality metrics are written, and the trip becomes `COMPLETED`.
6. Process/service interruption preserves samples as a recoverable incomplete
   trip; it is never silently deleted or reported as complete.

## Persisted Evidence

`TripData` retains raw GPS, accelerometer, gyroscope, rotation, event-compatibility
rows, provider, accuracy, speed validity, source epoch time, source monotonic
time, callback time, sensor type, and source type.

`TripEvent` retains stable event identity, marker epoch and monotonic time,
experienced and source locations, location-fix age, speed validity, provisional
and primary causes, traffic state, confidence, status, provenance, transcript,
recognition confidence, and codebook version.

`EventAnnotationRevision` stores one primary cause, traffic state, confidence,
notes, reviewer, version, timestamp, and supersession relation. It has no active
secondary-cause fields.

`TripQuality` stores stream counts, availability/frequency, interval and gap
metrics, GPS provider/accuracy metrics, write failures, dropped samples,
interruptions, stale-event warnings, and archival-audio failures.

## Export Contract

`ResearchExporter` creates a ZIP archive after the researcher chooses a mode:

```text
manifest.json
trips/trips.csv
sensors/trip_<id-or-uuid>.csv
qa/trips.json
metadata/device.json
metadata/schema.json
metadata/codebook.json
metadata/cause_config/trip_<uuid>.json
checksums.sha256
```

`RESTRICTED_RAW` includes controlled raw GPS, sensor timing, audio, transcripts,
device metadata, audit revisions, and historical media indexes/files.

`PUBLIC_DEIDENTIFIED` includes relative-time rows, canonical causes, QA flags,
and version metadata. It excludes precise GPS, absolute timestamps, local row
IDs, audio, transcripts, reviewer data, device identity, and historical media.

Incomplete trips are excluded by default and may be explicitly included for
recovery review. The archive is built in a temporary local file, validated, and
then copied to the selected document destination. Room data is not deleted by
export.

## Controlled Purge

The researcher can purge a reviewed trip from its detail screen after exporting
the restricted archive. The purge removes database-owned child rows and known
audio/photo files, then reports missing or failed file cleanup. It does not
silently claim that an unavailable file was deleted.

## Versions And Validation

- App: `1.1`, version code `2`
- Room schema: `8`
- Cause codebook: `3`
- Export format: `1`
- Protocol: `1`
- Sensor profile: `1`

Room schemas are stored under `app/schemas/`. The v7-to-v8 migration removes
the non-primary annotation tables/columns, canonicalizes legacy causes, and
preserves old non-primary values in `audit_revisions`.

The current gate remains `NOT READY FOR PILOT` until schema 8 is generated and
validated, export privacy/count/checksum tests pass, and physical-device
screen-lock, interruption, sensor, audio, and noisy-road voice checks complete.
