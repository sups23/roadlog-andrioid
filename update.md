# Android App Research Update — Single-Corridor Intensive Study

> **Status note:** This file is the original implementation brief and historical
> design record. The current implementation contract is documented in
> `research/thesis/android-app.md`, `docs/field-protocol.md`, and
> `docs/data-dictionary.md`. Where the older sections below conflict with those
> files, the current contract wins: voice-only collection, canonical single
> primary causes, no current photography, Room schema 8, and restricted/public
> export modes.

## Purpose

This document updates the Android research data-collection application to match the revised thesis methodology.

It should be treated as the implementation brief for the coding agent.

The application was previously designed around a broader multi-corridor research plan. The thesis has now been narrowed to an **intensive single-corridor repeated-measures case study**.

The application should be adjusted to support this research design without unnecessarily rewriting working architecture.

The app should remain reusable and should **retain generic support for configurable corridors**, even though the current thesis will actively study only one selected Kathmandu corridor.

## Current Pilot Simplification Override

The current pilot workflow intentionally uses one static session ID and one static
corridor ID assigned by the service. The front screen must not ask for session,
corridor, pass, observer, vehicle, or mount metadata. Those fields are not part of
the current collection contract and should not be exported.

Events are always categorized when created. The `UNK` code is displayed as
`UNCLASSIFIED` and can be selected manually or spoken as `log unclassified`. The
neutral `MARK EVENT` control is not part of the pilot UI.

---

# 1. Updated Thesis

## Working Title

**Event-Level Attribution of Urban Traffic Slowdowns under Heterogeneous Traffic Using Smartphone Sensing and Structured Field Annotation: An Intensive Case Study of a Kathmandu Corridor**

---

# 2. Updated Research Design

The current study uses:

- **1 selected Kathmandu corridor**
- **both travel directions**
- **3 traffic periods**
  - morning peak
  - off-peak
  - evening peak
- approximately **10–15 weekdays**
- repeated round-trip sessions
- approximately **60–90 valid directional traversals**
- approximately **100 m directional road segments**
- repeated observations of the same segments and bottleneck locations

The main analytical focus is:

- event-level slowdown attribution;
- segment-level spatial patterns;
- travel-direction differences;
- time-period differences;
- repeated-day behaviour;
- bottleneck persistence;
- cause-specific persistence;
- cause consistency;
- event duration;
- stopped time;
- recovery duration;
- excess delay;
- and road-condition context.

The main trade-off of this design is:

> **greater temporal depth and repeated spatial observation in exchange for lower city-wide generalizability.**

The app should therefore optimize for repeated, reliable collection of the same route rather than broad multi-corridor field logistics.

---

# 3. Updated Research Questions

## RQ1 — Cause Distribution, Spatial Pattern, and Persistence

**What are the primary and contributing causes of traffic slowdown events along a selected Kathmandu urban corridor, and how do their frequency, duration, excess delay, spatial distribution, and persistence vary by road segment, travel direction, and traffic period?**

The app must collect enough data to later determine:

- what caused each slowdown;
- how often each cause occurs;
- how severe each event is;
- where events repeatedly occur;
- whether the same locations repeatedly experience the same causes;
- whether bottlenecks differ by time period;
- and whether the two travel directions behave differently.

## RQ2 — Factors Associated with Event Severity

**How are slowdown cause, background traffic condition, road-surface condition, road segment, travel direction, and time period associated with the severity and recovery duration of individual slowdown events?**

The app must therefore preserve variables that allow later modelling of:

- primary cause;
- secondary contributors;
- traffic state;
- road condition;
- road segment;
- direction;
- period;
- event duration;
- recovery duration;
- and excess delay.

The statistical analysis will be observational.

The app should not encode assumptions that imply causal certainty.

---

# 4. Core Architecture Rule

The thesis currently uses one corridor.

However:

> **Do not remove the corridor domain model or hard-code the app to one route.**

Correct architecture:

```text
Reusable Android research app
└── Configured thesis corridor
    ├── Direction A_TO_B
    └── Direction B_TO_A
```

Incorrect architecture:

```text
App has no corridor entity
because this thesis currently uses only one corridor
```

The current thesis should simply select one configured corridor as the active study corridor.

This preserves:

- research reproducibility;
- future reuse;
- later multi-corridor replication;
- and clean domain modelling.

---

# 5. Updated Repeated-Measurement Structure

The main research structure is now:

```text
Selected Corridor
├── Direction A_TO_B
│   ├── Segment A001
│   ├── Segment A002
│   ├── ...
│   └── Segment Axxx
│
└── Direction B_TO_A
    ├── Segment B001
    ├── Segment B002
    ├── ...
    └── Segment Bxxx
```

These directional traversals are repeated during:

```text
MORNING
OFF_PEAK
EVENING
```

and across:

```text
Day 1
Day 2
...
Day 10–15
```

The application must therefore reliably preserve:

- corridor ID;
- direction;
- date;
- observation period;
- session ID;
- trip ID;
- raw GPS;
- GPS accuracy;
- sensor streams;
- event markers;
- annotations;
- trip validity;
- and version metadata.

---

# 6. Repeated Traversal Integrity

Repeated traversal support is now critical.

The application must support unlimited valid trips with the same:

```text
corridor
direction
period
date
```

or any combination of those values.

No database uniqueness constraint should incorrectly prevent multiple repeated observations.

Every traversal must receive its own stable unique identifier.

Recommended IDs:

- UUID for session;
- UUID for trip;
- UUID for event;
- UUID for media.

Do not use combinations such as:

```text
corridor + direction + period
```

as a unique trip identity.

---

# 7. Session and Trip Model

A normal round-trip field session may look like:

```text
Session
├── Trip 1 — A_TO_B
└── Trip 2 — B_TO_A
```

However, the system must also support:

- only one direction being completed;
- aborted return trips;
- invalid outbound trip;
- valid return trip;
- repeated trips in the same direction;
- multiple sessions on the same date.

Trip validity must be independent.

Do not treat the entire session as valid merely because one trip was valid.

---

# 8. Direction

Direction is now a major research variable.

Preferred stable machine-readable codes:

```text
A_TO_B
B_TO_A
```

Human-readable labels may display actual corridor endpoints.

For example:

```text
A_TO_B
Koteshwor → Maitighar

B_TO_A
Maitighar → Koteshwor
```

Direction must be:

- selected or confirmed before trip start;
- stored in the trip record;
- visible during recording;
- shown in trip history;
- included in exports;
- available to events through `trip_id`;
- available to sensor processing through `trip_id`.

If direction is corrected after collection, the system should preserve an audit record rather than silently replacing research data.

---

# 9. Observation Period

The study uses exactly three primary traffic periods:

```text
MORNING
OFF_PEAK
EVENING
```

Observation period should be:

- selected or confirmed before recording;
- stored explicitly;
- mandatory for normal research trips;
- visible during recording;
- included in trip history;
- included in exports.

The app may suggest a period based on local time.

However:

> The stored research value must remain explicit.

Do not rely on deriving the study period later from timestamps.

---

# 10. Study Date

Every trip must reliably preserve:

- exact start timestamp;
- exact end timestamp;
- local collection date;
- session ID;
- trip ID.

The research date should be stored as a research field rather than inferred later from:

- file names;
- export folder names;
- media metadata.

---

# 11. Trip Status

The trip lifecycle should distinguish operational recording state from final research validity.

Possible operational states:

```text
PREPARED
RECORDING
PAUSED
COMPLETED
ABORTED
RECOVERY_REQUIRED
```

Possible research QA states:

```text
UNREVIEWED
VALID
VALID_WITH_WARNINGS
INVALID
```

Also preserve:

- route diversion flag;
- recording interruption flag;
- GPS interruption flag;
- sensor interruption flag;
- free-text QA notes.

Invalid trips should remain stored.

They should not be deleted simply because they will not enter the primary analysis.

---

# 12. Directional Road Segments

The final analysis will likely divide the selected corridor into approximately **100 m directional segments**.

Potential structure:

```text
Direction A
A001
A002
A003
...

Direction B
B001
B002
B003
...
```

The Android collector does **not necessarily need to assign segments live**.

The safer primary architecture is:

```text
Raw GPS
→ export
→ offline map matching
→ directional segment assignment
```

This keeps the raw dataset reusable if:

- segment length changes;
- corridor geometry is corrected;
- map-matching logic improves.

If the app already supports segment assignment, preserve:

- raw coordinates;
- segment-definition version;
- assignment algorithm/version;
- assignment provenance.

Never make the segment ID the only spatial evidence.

---

# 13. Bottleneck Persistence

The Android app does not need to calculate bottleneck persistence live.

It must collect enough data to calculate it later.

For location/segment `s`:

```text
number of valid traversals containing slowdown at s
----------------------------------------------------
total valid traversals through s
```

Conceptually:

\[
P_s =
\frac{\text{slowdown traversals at }s}
{\text{valid traversals through }s}
\]

The app must therefore preserve:

- valid traversal identity;
- direction;
- event location;
- probable source location;
- trip validity;
- period;
- study date.

---

# 14. Cause-Specific Persistence

The final analysis may calculate:

```text
number of traversals containing cause c at location s
------------------------------------------------------
total valid traversals through location s
```

This requires structured machine-readable cause codes.

The app must not store cause only as free text.

---

# 15. Time-Period Persistence

The final analysis will compare persistence during:

```text
MORNING
OFF_PEAK
EVENING
```

Example:

```text
Source location S17

Morning persistence: 84%
Off-peak persistence: 23%
Evening persistence: 88%
```

This can identify a conditional bottleneck.

Observation period integrity is therefore a P1 research requirement.

---

# 16. Cause Consistency

Repeated observations will examine whether the same bottleneck repeatedly has:

- the same primary cause;
- several recurring causes;
- different causes at different times;
- different causes by direction.

The Android app should preserve:

- every original event annotation;
- annotation timestamps;
- annotation version;
- reviewer where applicable;
- original cause before later adjudication if editing/review is supported.

Do not silently overwrite prior research annotations.

---

# 17. Event Cause Taxonomy

The planned machine-readable primary cause codes are:

```text
SIG
QUE
BUS
PED
PRK
TRN
ENC
RDS
INC
UNK
```

Definitions:

## `SIG` — Signal / Traffic Control

Red signal, police control, or controlled movement directly creates or sustains the slowdown.

## `QUE` — General Traffic Queue

Dense downstream traffic with no more specific immediate obstruction that can be confidently identified.

## `BUS` — Public Transport Interference

Bus, microbus, tempo, or similar vehicle:

- stops;
- loads passengers;
- unloads passengers;
- blocks the traffic stream;
- re-enters traffic.

## `PED` — Pedestrian Interference

Pedestrian movement causes vehicles to:

- slow;
- stop;
- change path.

## `PRK` — Parking / Loading

Includes:

- parking manoeuvre;
- pickup/drop-off;
- loading/unloading;
- parked vehicle reducing effective road width.

## `TRN` — Turning / Merging / Access Conflict

Includes:

- turning;
- U-turn;
- merging;
- access-road entry;
- conflicting manoeuvre.

## `ENC` — Encroachment / Roadside Activity

Includes:

- vendors;
- market activity;
- temporary road occupation;
- commercial activity constraining usable road width.

## `RDS` — Road Defect / Roughness

Includes:

- pothole;
- broken pavement;
- trench;
- standing water;
- surface defect;
- avoidance manoeuvre caused by surface condition.

## `INC` — Incident / Temporary Obstruction

Includes:

- crash;
- breakdown;
- construction;
- emergency activity;
- unexpected temporary obstruction.

## `UNK` — Unknown

Evidence is insufficient to confidently assign another category.

---

# 18. Primary and Secondary Causes

Each reviewed event should support:

## Primary

Exactly one cause.

## Secondary

Zero to two contributing causes.

Example:

```text
Primary: SIG
Secondary 1: BUS
Secondary 2: PRK
```

Validation rules:

- primary must exist for reviewed attributed events;
- secondary causes are optional;
- duplicate secondary causes are not allowed;
- primary cannot also be secondary;
- `UNK` should not normally coexist with confidently specified secondary causes unless the research codebook explicitly permits it.

---

# 19. Traffic State

Each event should support one traffic-state code:

```text
LIGHT
MODERATE
DENSE_MOVING
QUEUED
```

Definitions should remain stable across collection.

The app should store machine-readable codes and display user-friendly labels separately.

---

# 20. Attribution Confidence

Each event should support:

```text
3 = HIGH
2 = MEDIUM
1 = LOW
0 = UNUSABLE
```

The numeric value should be stored.

Friendly labels may be displayed in the UI.

The main analysis is expected to primarily use confidence levels:

```text
2
3
```

Low-confidence observations should remain stored for sensitivity analysis.

---

# 21. Experienced Location vs Source Location

These are separate research concepts.

## Experienced location

Where the study vehicle experiences the slowdown.

Usually derived from:

- event start;
- GPS trajectory;
- automatic detection.

## Source location

Where the observer/reviewer believes the slowdown originated.

Examples:

- signal;
- bus;
- intersection conflict;
- pothole;
- road narrowing;
- parking obstruction.

The source may be considerably downstream from the vehicle's experienced slowdown location.

The app should support at minimum:

```text
experienced_lat
experienced_lon

source_lat
source_lon

source_visible
```

Do not overwrite the experienced location when source location is added or edited.

---

# 22. Event Provenance

Manual and automatic evidence must remain distinguishable.

Recommended provenance values:

```text
MANUAL_MARKER
AUTO_DETECTED
MANUAL_AND_AUTO
REVIEW_CREATED
```

Equivalent modelling is acceptable.

An automatically detected event must not erase evidence that:

> the passenger independently marked the same event during field collection.

This distinction may later be useful for:

- detector validation;
- sensitivity analysis;
- data-quality review.

---

# 23. Manual Event Marker

The passenger should have a fast, prominent control to mark a suspected slowdown.

Core requirement:

> **The marker timestamp must be captured immediately.**

The observer should not need to finish a form before the event timestamp is stored.

Possible fields:

```text
marker_id
trip_id
timestamp
latitude
longitude
current_speed
provisional_cause
annotation_status
```

Additional annotation can occur afterwards.

---

# 24. Event Detection

The final event-detection analysis may use two forms of slowdown.

## Severe slowdown

Preliminary concept:

```text
speed < 5 km/h for approximately 20 seconds
```

## Relative slowdown

Conceptually:

```text
speed < 50% of segment reference speed
```

for a sustained interval.

The Android app should not permanently hard-code these research thresholds unless live detection genuinely requires them.

Preferred design:

```text
raw trajectory
+ versioned optional live detector
+ offline final event detection
```

Raw data remain authoritative.

---

# 25. Event Lifecycle

Offline processing may later identify:

```text
baseline movement
↓
deceleration onset
↓
event start
↓
core slowdown
↓
minimum speed
↓
recovery
↓
event end
```

The Android app does not need to calculate all boundaries live.

Its responsibility is to preserve enough data for later reconstruction.

Do not add live scientific complexity unless it directly improves field operation.

---

# 26. Raw Sensor Requirements

Raw sensor preservation remains critical.

## GPS

Store:

```text
timestamp
latitude
longitude
reported accuracy
speed
bearing
```

Altitude/provider may also be retained when useful.

Target:

> approximately 1 Hz or highest stable practical rate.

Do not discard low-quality GPS observations during field collection.

Quality filtering belongs to offline processing.

---

# 27. Accelerometer

Target:

> approximately 50 Hz

Store raw:

```text
timestamp
x
y
z
```

Do not replace raw values with:

- filtered values;
- smoothed values;
- normalized values;
- derived vertical acceleration.

Derived data may be stored separately.

---

# 28. Gyroscope

Target:

> approximately 50 Hz where supported.

Store:

```text
timestamp
x
y
z
```

---

# 29. Orientation / Rotation Vector

Collect where available and practical.

Purpose:

- detect phone orientation;
- later transform acceleration into an earth-relative coordinate system;
- identify mount/orientation changes.

Preserve enough raw information for offline processing.

---

# 30. Timestamp Synchronization

Timestamp synchronization remains a **P0 requirement**.

Audit and verify:

- Android sensor monotonic timestamps;
- epoch/system timestamps;
- location timestamps;
- manual marker timestamps;
- voice start/end timestamps;
- photograph timestamps.

The final pipeline must be able to align all evidence.

Document:

- timestamp source;
- unit;
- precision;
- offset/conversion method;
- mapping between monotonic and epoch clocks.

Add tests for timestamp conversion.

---

# 31. Road-Condition Research Rule

Do **not** implement the assumption:

```text
high accelerometer vibration
=
rough road caused this slowdown
```

This is scientifically unsafe.

Accelerometer measurements are affected by:

- vehicle speed;
- braking;
- turning;
- vehicle suspension;
- phone mounting;
- orientation;
- surface condition.

The final thesis will estimate a **smartphone-derived relative road-condition layer** from suitable non-event moving observations.

The app's responsibility is to preserve:

- raw acceleration;
- GPS;
- speed;
- orientation;
- timestamps;
- phone metadata;
- device and sensor metadata.

---

# 32. Device and Sensor Metadata

Preserve:

```text
device_id
manufacturer
device_model
Android_version
app_version

accelerometer_name
accelerometer_vendor

gyroscope_name
gyroscope_vendor

sensor_profile_version
```

Do not repeatedly ask the researcher for values Android can safely determine automatically.

---

# 33. App and Research Versioning

Every trip should be traceable to:

```text
app_version
schema_version
sensor_profile_version
codebook_version
```

If the app performs live event detection:

```text
event_detection_version
```

If segment definitions are used in-app:

```text
segment_definition_version
```

Research definitions must not silently change during main collection.

---

# 34. Background Recording Reliability

The app should safely record a full corridor traversal while:

- the screen locks;
- the app is backgrounded;
- another activity opens;
- the camera opens;
- voice recording starts;
- the UI activity is recreated;
- temporary location loss occurs.

Audit and implement as needed:

- foreground service;
- persistent notification;
- durable recording state;
- periodic database writes;
- buffering;
- unfinished-trip recovery;
- sensor-heartbeat monitoring.

Do not keep an entire trip only in RAM.

---

# 35. Process Death and Recovery

If Android kills the process during a trip:

The app should not silently act as if the trip completed normally.

Possible recovery behaviour:

```text
unfinished trip detected
↓
show recovery state
↓
preserve already collected samples
↓
record interruption
↓
allow resume or finalize as invalid/warning
```

Recording interruptions should be part of trip QA.

---

# 36. Sensor Health Before Trip

The pre-trip screen should verify or display:

- GPS available;
- current GPS accuracy;
- accelerometer available;
- gyroscope available;
- orientation sensor available;
- microphone permission where needed;
- camera permission where needed;
- notification/foreground-service permission where applicable;
- available storage;
- battery level;
- selected corridor;
- selected direction;
- selected period;
- observer;
- vehicle.

Non-critical issues may permit override.

Overrides should be logged.

---

# 37. During-Trip Status

The field screen should clearly show:

- recording active;
- elapsed trip time;
- direction;
- traffic period;
- GPS status;
- sensor health;
- event-marker count;
- storage warning;
- battery warning.

Avoid unnecessary live scientific graphs unless they provide operational value.

The passenger needs a simple field interface.

---

# 38. Actual Sampling Diagnostics

Requested sampling rate is not guaranteed.

Calculate/report where possible.

## GPS

```text
sample count
median sample interval
longest outage
median reported accuracy
percentage with acceptable accuracy
```

## Accelerometer

```text
requested rate
observed median rate
sample count
longest sample gap
```

## Gyroscope

Equivalent metrics where relevant.

These belong in trip QA.

---

# 39. Trip QA Summary

Each completed trip should eventually have a summary similar to:

```text
Trip ID
Study date
Direction
Period

Start time
End time
Duration

GPS samples
GPS median accuracy
GPS longest outage

Accelerometer samples
Requested accelerometer frequency
Observed accelerometer frequency
Longest accelerometer gap

Gyroscope samples

Manual event markers
Annotations
Voice notes
Photos

Route diversion
Recording interruption
Sensor warnings

Trip QA status
QA notes
```

This helps identify bad field collection before additional days are lost.

---

# 40. Offline-First Requirement

Core research collection must work without internet.

The following must not require connectivity:

- selecting the configured study corridor;
- starting a session;
- starting a trip;
- sensor recording;
- event marking;
- cause annotation;
- traffic-state annotation;
- confidence annotation;
- voice recording;
- photography;
- ending a trip;
- reviewing local trips;
- exporting data.

Cloud synchronization may be optional but is outside the core requirement.

---

# 41. Research Data Integrity

Every major entity should use stable unique IDs.

Relationships should be explicit:

```text
session
└── trip
    ├── sensor samples
    └── events
        ├── annotations
        └── media
```

Prevent:

- duplicated IDs;
- orphan annotations;
- media linked to wrong events;
- event overwrite;
- repeated-trip collision;
- accidental deletion of invalid research data.

Use database transactions where appropriate.

---

# 42. Raw vs Derived Data

Maintain a strict distinction.

## Raw

Never destructively modify:

- GPS;
- accelerometer;
- gyroscope;
- orientation;
- timestamps;
- manual marker;
- original annotation;
- source audio;
- original photograph.

## Derived

May include:

- smoothed speed;
- segment assignment;
- event boundaries;
- reference speed;
- excess delay;
- persistence;
- road-condition score;
- automatic classifications.

Derived values should be reproducible.

---

# 43. Export Requirements

The export must support offline research processing.

Recommended structure:

```text
research-export/
  manifest.json

  trips/
    trips.csv

  events/
    events.csv
    annotations.csv

  sensors/
    trip_<id>.csv

  audio/
    <event-id>.m4a

  photos/
    <event-id>_<timestamp>.jpg

  metadata/
    device.json
    schema.json
    codebook.json
```

The exact structure may follow existing application architecture.

High-frequency sensor data may later be converted to Parquet in the desktop analysis pipeline.

---

# 44. Exported Study Identity Fields

At minimum:

```text
session_id
 corridor_id
 study_date
 period
device_id
```

---

# 45. Exported Trip Fields

At minimum:

```text
trip_id
session_id
corridor_id
direction
study_date
period

start_time
end_time

device_id

route_diversion
qa_status
qa_notes

app_version
schema_version
sensor_profile_version
codebook_version
```

---

# 46. Exported Event Fields

At minimum:

```text
event_id
trip_id

manual_marker_timestamp

experienced_lat
experienced_lon

source_lat
source_lon
source_visible

event_provenance

review_status
```

Automatically derived event boundaries may be added later.

---

# 47. Exported Annotation Fields

At minimum:

```text
event_id

primary_cause
secondary_cause_1
secondary_cause_2

traffic_state
confidence

annotation_timestamp
annotation_version
notes
```

---

# 48. Export Manifest

Include where practical:

```text
export_timestamp

app_version
schema_version
codebook_version
sensor_profile_version

session_count
trip_count
GPS_sample_count
accelerometer_sample_count
gyroscope_sample_count

event_count
annotation_count
voice_count
photo_count
```

Checksums are desirable where practical.

---

# 49. Schema Migration

Do not destructively recreate the database to introduce this research update.

If fields or tables are required:

- use proper migrations;
- preserve existing development/pilot data where practical;
- increment schema version;
- test migration from the immediately previous schema;
- add migration tests where supported.

---

# 50. Privacy

The app may capture:

- people;
- vehicles;
- licence plates;
- public conversations;
- roadside activity.

Minimize unnecessary personal data.

Prefer coded IDs:

```text
device_id
```

Do not require personal names unless genuinely needed.

Raw media should:

- remain local by default;
- be included in controlled export;
- support later privacy processing;
- not be automatically uploaded without explicit research need.

---

# 51. Field Safety

The passenger performs all interaction.

The driver must not:

- select causes;
- annotate causes;
- record voice;
- take photographs;
- review app status.

If evidence collection is unsafe:

> skip the evidence.

Missing optional evidence is preferable to unsafe collection.

---

# 52. Audit Before Implementation

Before changing code:

1. inspect the entire repository;
2. inspect architecture;
3. inspect database schema;
4. inspect migrations;
5. inspect sensor collectors;
6. inspect location service;
7. inspect foreground/background recording;
8. inspect session/trip model;
9. inspect event model;
10. inspect annotation UI;
11. inspect media handling;
12. inspect exports;
13. inspect permissions;
14. inspect tests;
15. inspect research documentation.

Do not assume something is missing before inspecting its implementation.

---

# 53. Required Audit Document

Update:

`docs/research-data-audit.md`

Add:

# Single-Corridor Intensive Study Migration

Use:

| Requirement | Status | Current Implementation | Single-Corridor Impact | Required Change | Priority |
|---|---|---|---|---|---|

Status values:

- ✅ Implemented
- 🟡 Partially implemented
- 🔴 Missing
- ⚠️ Implemented but risky
- ➖ Intentionally deferred

Audit at minimum:

- corridor architecture;
- repeated-trip support;
- direction;
- observation period;
- study date;
- session model;
- trip QA;
- raw GPS;
- GPS accuracy;
- accelerometer;
- gyroscope;
- orientation;
- timestamp synchronization;
- event marker;
- event provenance;
- primary cause;
- secondary causes;
- traffic state;
- confidence;
- experienced location;
- source location;
- voice;
- photo;
- exports;
- background survival;
- crash recovery;
- versioning;
- migration safety.

---

# 54. Priority Levels

## P0 — Research/Data Integrity Blocker

Examples:

- repeated trips overwrite each other;
- direction not stored;
- timestamps cannot be synchronized;
- raw samples lost;
- sensor recording silently stops;
- unfinished trip disappears;
- export is incomplete/corrupt.

## P1 — Required Before Main Collection

Examples:

- observation period missing;
- primary/secondary cause incomplete;
- confidence missing;
- traffic state missing;
- source location unsupported;
- trip validity unsupported;
- repeated-day history inadequate;
- QA summary incomplete.

## P2 — Valuable Enhancement

Examples:

- map-based source-location editing;
- live segment display;
- richer trip review;
- advanced QA visualization.

## P3 — Future Research

Examples:

- automatic cause classification;
- ML traffic-state detection;
- cloud dashboard;
- live bottleneck persistence;
- real-time roughness prediction.

Do not allow P2/P3 work to delay P0/P1 readiness.

---

# 55. Implementation Order

After the audit, implement P0/P1 work roughly in this order.

## Step 1 — Repeated Traversal Integrity

Verify:

- unique trip identity;
- repeated same direction/period support;
- no incorrect uniqueness constraints.

## Step 2 — Direction

Add/verify:

```text
A_TO_B
B_TO_A
```

Storage, UI, history, export.

## Step 3 — Observation Period

Add/verify:

```text
MORNING
OFF_PEAK
EVENING
```

## Step 4 — Study Date / Session Identity

Ensure reliable repeated-day storage.

## Step 5 — Trip QA and Validity

Add/verify:

```text
UNREVIEWED
VALID
VALID_WITH_WARNINGS
INVALID
```

## Step 6 — Structured Event Annotation

Primary cause, secondaries, traffic state, confidence.

## Step 7 — Experienced / Source Location

Keep them separate.

## Step 8 — Event Provenance

Manual vs auto vs review-created.

## Step 9 — Raw Sensor Integrity

GPS, accuracy, accelerometer, gyro, orientation.

## Step 10 — Timestamp Synchronization

Verify and test.

## Step 11 — Background Reliability

Foreground service, process recovery, durable writes.

## Step 12 — Sensor QA

Actual rates, GPS outages, warnings.

## Step 13 — Export

Include all new research variables.

## Step 14 — Migration and Versioning

Verify schema upgrade.

## Step 15 — Documentation and Final Readiness Audit

---

# 56. Remove or Downgrade Obsolete Complexity

Review features that were built specifically for the old cross-corridor design.

Do **not** remove the generic corridor model.

However, features may be simplified if they only exist to support active comparison among several study corridors.

Potential simplifications:

- multi-corridor field dashboard;
- corridor-comparison UI;
- repeated corridor selection on every trip;
- cross-corridor summary during field collection.

A useful workflow may be:

```text
Study configuration
→ choose default thesis corridor once

Daily collection
→ period
→ direction
→ sensor check
→ start trip
```

This reduces field interaction while preserving reusable architecture.

---

# 57. Testing Requirements

## Repeated Trips

Test multiple records using the same:

```text
corridor
direction
period
date
```

Ensure no collision.

## Direction

Test:

- storage;
- persistence;
- export;
- correction if supported.

## Observation Period

Test:

- storage;
- persistence;
- export.

## Cause Validation

Test:

- primary required where appropriate;
- secondary optional;
- no duplicate secondary;
- primary cannot equal secondary.

## Traffic State

Test allowed values.

## Confidence

Test allowed numeric values.

## Source Location

Test editing source does not modify experienced location.

## Event Provenance

Test manual markers remain distinguishable from automatic events.

## Trip QA

Test invalid trips remain stored/exportable.

## Timestamp Synchronization

Test monotonic/epoch mapping.

## Export

Test repeated trips and all research variables survive export.

## Migration

Test upgrade from previous schema.

## Background / Recreation

Where instrumentation supports it:

- activity recreation;
- backgrounding;
- recording state recovery.

---

# 58. Documentation to Update

Update:

`README.md`

where user workflow has changed.

Update:

`docs/research-data-audit.md`

Update/create:

`docs/research-data-readiness.md`

Update/create:

`docs/data-dictionary.md`

Update/create:

`docs/field-protocol.md`

Update/create:

`docs/field-failure-analysis.md`

---

# 59. Field Protocol

The revised standard workflow should look approximately like:

```text
Static study session/corridor IDs
        ↓
Select study period
        ↓
Select travel direction
        ↓
Sensor readiness check
        ↓
Start trip
        ↓
Continuous sensor recording
        ↓
Passenger selects a cause or `UNCLASSIFIED`
        ↓
Optional annotation / voice / photo
        ↓
Complete trip
        ↓
Trip QA
        ↓
Reverse direction or next period
        ↓
Repeat across 10–15 study days
```

---

# 60. Field Failure Analysis

Create/update:

`docs/field-failure-analysis.md`

Use entries such as:

```text
Failure:
Android kills the recording service.

Research impact:
Part of the repeated traversal is missing.

Detection:
Sensor heartbeat gap / unfinished recording state.

Prevention:
Foreground service, persistent recording state, durable writes.

Recovery:
Preserve existing samples, mark interruption, allow trip recovery or QA invalidation.

Priority:
P0
```

Include failures such as:

- GPS unavailable;
- phone mount moved;
- storage full;
- battery critically low;
- process death;
- activity recreation;
- voice/camera interrupting sensor collection;
- wrong direction selected;
- wrong period selected;
- accidental duplicate marker;
- route diversion;
- forgot to stop trip;
- incomplete return traversal;
- media linked to wrong event;
- export interrupted.

---

# 61. Data Dictionary

`docs/data-dictionary.md` should document every exported field.

For each field include:

```text
name
type
unit
allowed values
nullable?
raw/derived
research meaning
source
```

Pay special attention to:

- timestamp source;
- timestamp units;
- GPS accuracy;
- speed units;
- acceleration units;
- direction codes;
- period codes;
- cause codes;
- confidence;
- traffic state;
- QA status;
- event provenance.

---

# 62. Research Readiness Document

Update:

`docs/research-data-readiness.md`

Use:

```text
Repeated traversal support: PASS/WARN/FAIL
Direction: PASS/WARN/FAIL
Observation period: PASS/WARN/FAIL
Study-date integrity: PASS/WARN/FAIL
Session/trip identity: PASS/WARN/FAIL

Raw GPS: PASS/WARN/FAIL
GPS accuracy: PASS/WARN/FAIL
Accelerometer: PASS/WARN/FAIL
Gyroscope: PASS/WARN/FAIL
Orientation: PASS/WARN/FAIL

Timestamp synchronization: PASS/WARN/FAIL
Background recording: PASS/WARN/FAIL
Crash recovery: PASS/WARN/FAIL

Event marking: PASS/WARN/FAIL
Structured annotation: PASS/WARN/FAIL
Event provenance: PASS/WARN/FAIL
Experienced/source location: PASS/WARN/FAIL

Trip QA: PASS/WARN/FAIL
Repeated-day integrity: PASS/WARN/FAIL

Export: PASS/WARN/FAIL
Schema migration: PASS/WARN/FAIL
Versioning: PASS/WARN/FAIL
```

Final recommendation:

```text
READY FOR PILOT
```

or:

```text
READY FOR PILOT WITH KNOWN LIMITATIONS
```

or:

```text
NOT READY FOR PILOT
```

---

# 63. Continue-Work Procedure for New AI Sessions

Whenever development continues in a new AI-agent session:

1. read `thesis.md`;
2. read this `update.md`;
3. read `docs/research-data-audit.md`;
4. read `docs/research-data-readiness.md`;
5. read `docs/data-dictionary.md`;
6. read `docs/field-protocol.md`;
7. read `README.md`;
8. inspect recent Git history;
9. inspect current working tree;
10. verify the latest claimed completed task in code.

Do not assume a feature is complete because documentation says it was planned.

Verify:

- implementation;
- tests;
- migration;
- export;
- documentation.

---

# 64. Continue-Work Priority Rule

For every new development session:

1. identify remaining P0 issues;
2. complete the highest-priority P0 issue;
3. then address P1;
4. do not start P2/P3 while P0/P1 remain;
5. make the smallest robust change;
6. add/update tests;
7. run relevant tests;
8. update audit/readiness documentation.

---

# 65. Git / Implementation Discipline

For each meaningful P0/P1 task:

1. inspect existing code first;
2. understand current behaviour;
3. identify the research risk;
4. choose the smallest robust change;
5. implement it;
6. add tests;
7. run tests;
8. update docs;
9. make a focused commit if the project workflow uses task-level commits.

Avoid unrelated refactoring.

Do not redesign working architecture merely to match this document's terminology.

Equivalent existing structures may be retained.

---

# 66. Important Scientific Rules

## Rule 1 — Preserve Raw Data

Raw sensor values are authoritative.

## Rule 2 — Repeated Observations Must Never Collide

Every traversal is an independent research observation.

## Rule 3 — Direction Is Required

Direction is one of the main analytical variables.

## Rule 4 — Period Is Required

Morning/off-peak/evening must remain explicit.

## Rule 5 — Human Attribution and Automatic Detection Are Different Evidence

Preserve provenance.

## Rule 6 — Record Uncertainty

Use confidence and `UNK`.

## Rule 7 — Experienced and Source Locations Are Different

Do not merge them.

## Rule 8 — Missing Means Missing

Do not fabricate research values.

## Rule 9 — Road Vibration Does Not Automatically Mean Road-Caused Slowdown

Road condition is estimated separately.

## Rule 10 — Data Integrity Beats UI Polish

This is a scientific instrument.

## Rule 11 — Core Collection Must Work Offline

Internet is not a research dependency.

## Rule 12 — Analysis Should Remain Reproducible Offline

Do not permanently embed unnecessary analysis assumptions in the collector.

---

# 67. What the Android App Should Not Become

Do not expand the application into:

- a city-wide live congestion dashboard;
- an automatic AI slowdown-cause classifier;
- a real-time IRI measurement tool;
- a cloud-only traffic platform;
- a traffic simulation system;
- an intervention recommendation engine.

These are outside the current thesis scope.

The app is primarily:

> **a reliable, synchronized, offline-capable event-level traffic research data collector.**

---

# 68. Current Definition of Research-Ready

The Android app is ready for the pilot when it can reliably support:

- one configured study corridor;
- both directions;
- morning/off-peak/evening;
- repeated sessions;
- repeated days;
- unique traversal identities;
- raw GPS;
- GPS accuracy;
- raw accelerometer;
- gyroscope where available;
- orientation where available;
- synchronized timestamps;
- fast manual event marking;
- structured event annotation;
- primary and secondary causes;
- traffic state;
- confidence;
- experienced location;
- source location;
- trip validity;
- background recording;
- crash/interruption recovery;
- trip QA;
- offline export;
- versioned data definitions.

---

# 69. Current Definition of Main-Study Ready

Main-study collection should begin only after:

1. pilot collection is completed;
2. pilot data quality is reviewed;
3. event thresholds are evaluated;
4. annotation categories are reviewed;
5. direction/period workflow is confirmed;
6. timestamp synchronization is verified;
7. actual sensor rates are confirmed;
8. background recording is proven reliable;
9. export completeness is verified;
10. P0 issues are closed;
11. critical P1 issues are closed;
12. the frozen research definitions are versioned.

---

# 70. Final Implementation Goal

The final goal is:

> **A stable Android research instrument capable of collecting approximately 60–90 repeated directional traversals of one selected Kathmandu corridor without losing the information required for event-level slowdown attribution, excess-delay analysis, directional/temporal comparison, road-condition context, and bottleneck-persistence analysis.**

The most important development priorities are:

```text
scientific reproducibility
        ↓
repeated-measure integrity
        ↓
field reliability
        ↓
data quality
        ↓
offline export
        ↓
field usability
        ↓
optional convenience features
```
