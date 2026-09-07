# Field Failure Analysis

| Failure | Research impact | Detection | Prevention | Recovery / QA | Priority |
|---|---|---|---|---|---|
| GPS unavailable or stale | Spatial coverage and event alignment weaken | GPS status, fix age, QA gaps | Pre-trip fix check | Preserve raw sensor data; mark GPS interruption | P0 |
| Phone mount moved | Sensor orientation and road-condition context change | Observer note and QA review | Secure mount; do not adjust while driving | Mark warning and preserve mount metadata | P1 |
| Storage full | Samples/audio may stop | Storage warning, writer failures | Pre-trip storage check; export often | Preserve completed batches; mark incomplete and invalid/warning | P0 |
| Battery critically low | Process/service interruption | Battery monitoring | Charge and disable optimization | Preserve partial traversal and interruption reason | P0 |
| Android kills service | Traversal is incomplete | Heartbeat and recoverable draft | Foreground service and battery policy | Keep samples before interruption; review as partial | P0 |
| Activity recreation | Operator loses controls or metadata | Service-state query | Service-owned state restoration | Reattach to active trip; never start a duplicate | P0 |
| Voice encoder failure | Audio evidence is missing | Audio segment status | Shared AudioRecord and storage checks | Continue sensors; mark audio segment failed | P1 |
| Archival audio failure | Audio corroboration is missing or partial | Audio frame/segment status and QA warning | Shared AudioRecord, bounded segments, storage checks | Continue GPS/sensors; retain failed/interrupted segment metadata | P1 |
| Wrong direction selected | Directional comparison is contaminated | Pre-start confirmation | Fixed direction choices | Preserve original value; correct only with audit or invalidate | P0 |
| Wrong period selected | Time-period comparison is contaminated | Pre-start confirmation | Fixed period choices | Preserve original value; do not silently overwrite | P0 |
| Accidental duplicate marker | Event count is inflated | Event review and timestamps | Large but deliberate marker control | Retain marker; mark unusable rather than delete raw evidence | P1 |
| Route diversion | Segment denominator becomes ambiguous | Observer QA note and route review | Confirm route before start | Mark diversion and use only covered/matched segments | P1 |
| Forgot to stop | Extra non-study data appended | Duration and QA review | Notification reminder | End manually; review boundary and mark warning | P1 |
| Incomplete return traversal | Session has asymmetric trips | Independent trip status | Treat each trip independently | Preserve outbound/return separately; partial trip is not full traversal | P0 |
| Incident or breakdown | Traversal is not analytically comparable | Post-trip exclusion review | Do not infer exclusion from weather, congestion, stops, diversion, or app failure | Mark `INCIDENT_OR_BREAKDOWN`; retain raw trip and export flag | P0 |
| Export interrupted | Archive may be incomplete | Temporary archive/checksum validation | Export to temporary local file first | Retry without modifying Room data | P0 |
