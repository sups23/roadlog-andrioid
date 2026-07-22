# RoadLog Implementation Plan 1

This is an execution plan, not a request to implement all phases at once. Work on exactly one phase per session/checkpoint. After a phase is implemented and verified, commit only that phase, report the commit, and ask the user whether to continue. Do not start the next phase without explicit approval.

## 1. Repository Context

### Project shape

- Repository root: `/workspace`.
- Single Android application module: `:app`.
- Production Kotlin package: `app/src/main/java/com/example/roadlog/`.
- Android resources: `app/src/main/res/`.
- Assets: `app/src/main/assets/`.
- No unit-test or instrumented-test source sets currently exist.
- There are no existing test dependencies.
- Do not delete or replace `app/src/main/assets/model-en-us/`; it is the bundled Vosk model and is required at runtime.

### Toolchain and commands

- Gradle wrapper: Gradle 8.9.
- Android Gradle Plugin: 8.5.2.
- Kotlin: 1.9.22.
- `compileSdk 34`, `minSdk 26`, `targetSdk 29`.
- Room: 2.6.1 with `kapt`.
- SDK configuration is in ignored `local.properties`; this environment uses `sdk.dir=/workspace/android-sdk`.
- Build: `./gradlew assembleDebug`.
- Lint: `./gradlew lintDebug`.
- Unit tests after adding them: `./gradlew testDebugUnitTest`.
- Instrumented tests after adding them: `./gradlew connectedDebugAndroidTest`.
- Install: `./gradlew installDebug`.
- Runtime diagnostics: `adb logcat -s RoadLog:D`.
- There is no guaranteed connected emulator; if instrumentation cannot run, report it as not run rather than claiming success.

### Important working-tree rules

- Existing unrelated changes may be present. Do not revert them.
- Known unrelated/untracked paths may include `.idea/`, `android-sdk/`, and `crash.log`; never stage them.
- Never stage `local.properties`, build outputs, generated APKs, or the Vosk model.
- Before each phase commit, run `git status --short`, `git diff --check`, and `git diff -- <phase files>`.
- Stage only files belonging to the current phase.
- Do not amend commits. Use a new commit for each phase.

## 2. Current Architecture

### UI and service

- `MainActivity.kt` owns the main screen, runtime permissions, osmdroid map, CameraX setup, start/stop buttons, manual cause buttons, and photo capture.
- `LoggerService.kt` is a foreground service. It receives start/stop/cause intents, obtains location and sensor callbacks, runs Vosk, and writes Room data.
- Service actions and extras are constants in `LoggerService.Companion`.
- `MainActivity` registers broadcasts with `RECEIVER_NOT_EXPORTED`, but the service currently emits implicit broadcasts containing status, speech, coordinates, and trip IDs.
- The service currently returns `START_NOT_STICKY`.
- `LoggerService.onDestroy()` intentionally does not cancel `serviceScope`, because the current stop path launches an asynchronous Room flush.

### Current recording flow

1. MainActivity checks location and microphone permissions; camera permission is optional.
2. MainActivity sends `ACTION_START`, using `startForegroundService()` on Android O+.
3. LoggerService prepares the Vosk model asynchronously.
4. LoggerService starts foreground mode only inside `startRecording()`, after model readiness.
5. Location callbacks append to `gpsBuffer`.
6. Accelerometer, gyroscope, and rotation callbacks append to sensor buffers.
7. Vosk callbacks append cause events to `eventBuffer`.
8. On stop, callbacks are removed and a coroutine calls `flushToDatabase()`.
9. `flushToDatabase()` calculates summary data, inserts a `Trip`, broadcasts `ACTION_TRIP_SAVED`, builds one large `rows` list, and inserts rows in chunks of 500.

### Current persistence model

- `AppDatabase.kt` contains `Trip`, `TripData`, and `TripPhoto` entities.
- Current Room schema version is 5.
- Existing migrations are `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, and `MIGRATION_4_5`.
- `TripData` stores GPS, accelerometer, gyroscope, rotation, and cause-event data in nullable columns.
- Sensor rows have a wall-clock `timestamp` plus optional raw `rawTimestamp`.
- Existing per-trip queries use `(tripId = :tripId OR tripId = 0)` to include legacy unassigned rows.
- Existing deletion uses `deleteTripDataInRange(fromMs, toMs)`, which is unsafe and must be removed/replaced.
- There are no foreign keys from `TripData`/`TripPhoto` to `Trip`.

### Current detail flow

- `TripHistoryActivity.kt` loads all trip summaries and supports swipe deletion.
- It passes trip ID, start time, and end time to `TripDetailActivity`.
- `TripDetailActivity.kt` loads all GPS, events, accelerometer, gyro, rotation, and photos, then computes sensor fusion on `Dispatchers.Default`.
- It binds charts on the main thread.
- It decodes every photo with `BitmapFactory.decodeFile()` on the main thread and adds all images to a `LinearLayout`.
- `RouteMapDialogFragment.kt` receives large lists through mutable companion-object fields.
- `SensorFusion.kt` contains chart downsampling helpers, but detail loading currently still queries and materializes full sensor traces.

### Current speech flow

- `VoskSpeechRecognizer.kt` unpacks `model-en-us` through `StorageService`.
- It owns an `AudioRecord`, Vosk `Model`, Vosk `Recognizer`, coroutine scope, and recognition job.
- The recognition loop runs on `Dispatchers.IO`.
- `startListening()` cancels a previous job but does not join it before replacing resources.
- `destroy()` cancels the job but does not wait for blocking reads before closing native resources.
- `cause_config.json` is the runtime source of grammar phrases, variants, cause codes, and thresholds.

## 3. Cross-Phase Invariants

Every implementation phase must preserve these behaviors unless the phase explicitly changes them:

- Existing manual cause buttons continue to record events while a trip is active.
- Existing speech cause matching continues to use `cause_config.json`.
- Existing GPS, accelerometer, gyroscope, rotation, chart, timeline, map, and photo features remain available.
- Existing Room migrations remain usable for databases created by older app versions.
- A completed history item must never point to a trip with missing/partially persisted child data.
- No production code may depend on debug-only seed data.
- No test should require the bundled Vosk model unless it is explicitly an instrumentation/device test.
- No test should require real GPS, microphone, camera, or network access unless explicitly marked as a manual/device test.
- Do not silently change schema version or migration behavior without adding a migration test.

## 4. Phase Protocol

For every phase:

1. Read this plan section and the referenced current files before editing.
2. Inspect `git status --short`; preserve unrelated changes.
3. Implement only the phase scope.
4. Add or update tests before declaring the phase complete.
5. Run the phase’s automated checks.
6. Perform the phase’s visual/manual checks on a debug build when a device/emulator is available.
7. Inspect `git diff --check` and the complete diff for the phase.
8. Commit only the phase files with the exact commit message listed below.
9. Report changed files, tests run, manual checks run/not run, and commit hash.
10. Ask: “The phase is committed. Continue to the next phase?”

If a test or build fails, fix it within the current phase. Do not move on with a known failure. If a device-only check is unavailable, state the limitation and keep the phase gate open for user confirmation.

# Phase 0: Test Foundation and Fixtures

## Objective

Create the smallest reliable test foundation before changing runtime behavior. Do not add user-visible demo controls in this phase.

## Files to inspect first

- `app/build.gradle`.
- `build.gradle`.
- `gradle.properties`.
- `settings.gradle`.
- `AppDatabase.kt`.
- `DataModels.kt`.
- `AGENTS.md`.

## Expected changes

### Gradle/test setup

- Add only dependencies required by planned tests. Use versions compatible with the existing AndroidX stack.
- Add local unit-test dependencies for JUnit and coroutine testing.
- Add Android test dependencies for AndroidX test runner/JUnit and Room testing.
- Configure `testInstrumentationRunner` in `defaultConfig` if needed.
- Configure Room schema export to a tracked location under the test/schema area.
- Do not add a dependency merely for convenience; keep the test APK small.

### Test fixture structure

Use clear fixture helpers, for example:

- `app/src/test/java/com/example/roadlog/Fixtures.kt` for pure Kotlin data fixtures.
- `app/src/androidTest/java/com/example/roadlog/DatabaseFixtures.kt` for Room/device fixtures.
- Adjust names to match repository conventions if implementation reveals a better boundary.

Fixtures must be deterministic and explicit:

- `Trip` records use fixed IDs only when inserted into a test database; production insertions must still auto-generate IDs.
- Include at least two trips whose time ranges overlap.
- Give each trip unique GPS coordinates, event causes, sensor values, and photo paths so accidental cross-trip reads are obvious.
- Include rows exactly at start/end boundaries.
- Include a long trace with enough rows to exercise batching and visual downsampling without making tests unnecessarily slow.
- Generate temporary JPEGs in the test cache/files directory. Do not add binary images to Git.
- Provide cleanup helpers that close databases and delete temporary files.

### Baseline tests


- Add one fixture/smoke test proving the test task executes.
- Add a Room open/insert/read smoke test if the test environment supports it.
- Do not test the old unsafe behavior as a desired contract.

## Automated verification

Run in this order:

```bash
./gradlew assembleDebug
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

If no emulator is connected, run the first three commands and report instrumentation as unavailable.

## Visual/manual verification

- Install the debug APK.
- Open MainActivity.
- Confirm the existing Start, Stop, cause buttons, map, and View Trips controls are present.
- Record one short trip if permissions and a device are available.
- Open Trip History and Trip Detail.
- Confirm this phase added no seed controls and did not alter the normal UI.

## Commit

Commit only test foundation files.

```text
Add Android test foundation and fixtures
```

Stop after the commit and ask the user to confirm the commit and approve Phase 1.

# Phase 1: Durable Transactional Recording

## Objective

Fix incomplete-save publication and unbounded recording memory. The implementation must write bounded batches during recording and expose a trip only after finalization commits.

## Files to inspect first

- `LoggerService.kt`, especially fields around lines 56-87, start/stop around 250-387, and `flushToDatabase()` around 531-654.
- `AppDatabase.kt` entities, DAOs, migrations, and database builder.
- `TripHistoryActivity.kt` query/load behavior.
- `TripDetailActivity.kt` trip loading behavior.
- `MainActivity.kt` trip-saved receiver around lines 169-181 and service start/stop around lines 529-592.
- Existing Room schema files produced in Phase 0.

## Required design decisions

### Draft/completed state

- Add an explicit completion state to `Trip`; prefer a small stable representation such as `status` with values `RECORDING`, `COMPLETED`, and `ABANDONED`, or an equivalent boolean plus clear semantics.
- Pick one representation and use it consistently in entities, queries, migrations, fixtures, and UI.
- Existing historical rows from versions 1-5 must migrate to `COMPLETED`, because they are already saved trips.
- New draft rows must not appear in `getAllTrips()` or normal detail navigation.

### Stable trip ID

- Insert the draft summary at recording start to obtain `tripId`.
- Store the active trip ID in the service session.
- All rows written for that session must use that ID.
- Do not use `tripId = 0` for new recording rows.

### Bounded writes

- Do not keep all GPS/sensor/event objects until stop.
- Use a bounded channel/queue or serialized writer with fixed-size chunks.
- Keep ordering deterministic enough for timestamp queries.
- Do not write from multiple unsynchronized producers directly into Room if that can reorder or race state.
- Keep only the state required for live status and final summary in memory.
- If a batch write fails, make the failure visible and prevent a false completed-trip broadcast.

### Finalization transaction

- Stop new producers first.
- Drain/await the writer.
- Calculate final summary from session aggregates or persisted rows.
- In one Room transaction, update the draft summary and mark it completed.
- Broadcast `ACTION_TRIP_SAVED` only after the transaction returns successfully.
- Do not call this a successful save if child-row insertion or finalization fails.

### Service lifecycle

- Prevent a second start from clearing an active session or starting while finalization is still running.
- STOP while model loading is pending must cancel the pending start; if this is not fully addressed in Phase 1, document it as a blocker for Phase 3/service-state work.
- Preserve the existing intentional behavior needed for asynchronous completion, but make the lifetime explicit and testable rather than relying on `onDestroy()` not cancelling a scope.

### Recovery

- Decide when stale drafts are identified: service creation, database open, or app startup.
- Do not delete a draft that may still belong to an active service session.
- Mark or clean stale drafts deterministically and delete their child rows if appropriate.
- Add logging with `RoadLog` for draft recovery.

## Room migration requirements

- Increment the version from 5 to the next version.
- Add a migration from version 5 to the new version.
- Register it in `Room.databaseBuilder()`.
- Export the new schema.
- Add a migration test from the previous schema and a test that historical trips are marked completed.
- Do not rewrite old migration files.

## Tests

- Draft is excluded from history.
- Completed trip is visible after finalization.
- Summary and every child row use the same trip ID.
- Broadcast is not emitted before finalization.
- Failed batch/finalization does not create a completed trip.
- Stop waits for pending writes.
- Second start cannot reuse/clear the first session.
- Large fixture is written in bounded chunks without constructing one full duplicate rows list.
- Stale draft recovery is deterministic.

Where direct Android service testing is too coupled, extract a small testable recording/session repository. Do not create a fake architecture that production code does not use.

## Automated verification

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew lintDebug
```

## Visual/manual verification

- Start a trip and confirm live map updates, status text, manual cause selection, and speech status still work.
- Stop once and confirm one new history entry appears after the “Stopping and saving...” state.
- Open the detail screen and verify route, events, charts, and existing photos remain available.
- Repeat start/stop quickly; verify the second trip is not stopped or mixed with the first.
- If possible, force-stop/restart during a recording and verify no incomplete trip appears as completed.
- Inspect `adb logcat -s RoadLog:D` and confirm the save broadcast follows successful finalization.

## Commit

```text
Persist trips transactionally in bounded batches
```

Stop after the commit and ask the user to confirm the commit and approve Phase 2.

# Phase 2: Trip-Scoped Transactional Deletion

## Objective

Deleting one trip must never delete another trip’s data, including when timestamp ranges overlap or share boundaries.

## Files to inspect first

- `AppDatabase.kt`, especially `TripDao` deletion methods and entity declarations.
- `TripHistoryActivity.kt`, `deleteTrip()` around lines 107-123.
- `TripDetailActivity.kt`, `deleteTrip()` around lines 543-559.
- All Room schema exports and migration tests from Phase 0/1.

## Required changes

- Replace `deleteTripDataInRange(fromMs, toMs)` with a trip-ID-based operation.
- Add an index on `TripData.tripId` if query plans/schema do not already provide one.
- Add foreign keys only with a valid migration and correct behavior for legacy data. If foreign keys are not practical because of legacy `tripId = 0` rows, document the decision and enforce integrity in a transaction instead.
- Add a single DAO/repository delete operation for one trip.
- The database operation must delete child rows and summary consistently.
- File deletion is not transactional with SQLite. Delete known photo files best effort, then remove metadata transactionally; log failures.
- Do not let a missing file prevent database cleanup.
- Use the same deletion implementation from history and detail.
- Refresh the UI only after deletion succeeds.
- Surface failure to the user instead of silently showing success.

## Legacy rows

- Normal queries for new trips must not reintroduce unrelated `tripId = 0` data.
- Do not broaden deletion to timestamps as a fallback.
- If legacy unassigned rows cannot be assigned safely, leave them isolated and test that deleting a normal trip does not remove them.

## Tests

- Two trips with overlapping time ranges; delete one; assert the other’s summary and all child rows remain.
- Rows exactly on shared start/end boundaries.
- Photos for both trips, including an absent file.
- Database failure or coroutine cancellation does not report successful deletion.
- History and detail use the same trip-scoped operation.
- Run migration tests for every supported schema transition.

## Automated verification

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew lintDebug
```

## Visual/manual verification

- Use a fixture/debug dataset with two overlapping trips.
- Delete the first trip from Trip History.
- Confirm only the first row disappears.
- Open the remaining trip and verify its route, events, charts, and photos are unchanged.
- Repeat from Trip Detail.
- Confirm no broken photo cards remain for the deleted trip.

## Commit

```text
Delete trips by ID transactionally
```

Stop after the commit and ask the user to confirm the commit and approve Phase 3.

# Phase 3: Safe Vosk Session Lifecycle

## Objective

Ensure an old recognition loop cannot release or mutate a newer loop, and native Vosk resources are closed only after active audio work has stopped.

## Files to inspect first

- `VoskSpeechRecognizer.kt`, especially `prepare()`, `startListening()`, `stopInternal()`, `destroy()`, and `runRecognitionLoop()`.
- `LoggerService.kt`, especially Vosk preparation and callback usage.
- `app/build.gradle` test dependencies from Phase 0.

## Required lifecycle model

- Create one explicit active-session object containing session ID, recorder, recognizer, callback, and job.
- Serialize lifecycle transitions with a `Mutex` or equivalent single-threaded owner.
- `startListening()` must stop and await the old session before replacing it.
- The recognition loop must use local session references, not mutable global recorder/recognizer fields.
- A loop may publish callbacks only while its session ID is current.
- `stop()` must stop/unblock AudioRecord, await loop completion, then clear callback/session.
- `destroy()` must invalidate preparation and active sessions, await loop completion, then close recognizer/model.
- Asynchronous `StorageService.unpack()` callbacks must check a generation/token before installing resources.
- Close stale unpacked models and recognizers instead of leaking them.
- Preserve the public behavior expected by `LoggerService` unless an adapter is necessary.

## AudioRecord robustness

- Use `maxOf(minBufferSize, desiredBufferSize)`.
- Release the local recorder on every constructor/start failure.
- Add bounded handling for repeated zero-byte reads; avoid a hot loop and log flood.
- Keep callback threading explicit. If callbacks remain on IO, ensure LoggerService serializes their state updates.

## Testability

- Do not require a real microphone or bundled model for unit tests.
- Introduce narrow interfaces/factories around recorder and native recognizer creation if needed.
- Keep Android/Vosk integration thin and test the state machine with fakes.

## Tests

- Start then stop: stop completes only after the loop exits.
- Rapid restart: old session cannot stop/release the new session.
- Destroy while read is blocked: read is unblocked, loop exits, then native resources close.
- Stale model callback after destroy: callback is ignored and returned resources are closed.
- Recorder initialization failure: all allocated resources are released.
- Repeated zero-byte reads: bounded backoff/failure behavior.
- Callback from an old session is ignored.

## Automated verification

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
```

## Visual/manual verification

- Start a trip and confirm status changes to microphone listening.
- Stop and immediately start another trip; confirm the second trip continues listening.
- Cause a recognizer error or deny microphone permission where possible; confirm the UI reports an error without freezing.
- Leave and return to the app during recording; confirm the foreground notification remains present.
- Inspect `adb logcat -s RoadLog:D` for one active session and orderly stop/release logs.

## Commit

```text
Serialize Vosk recognition lifecycle
```

Stop after the commit and ask the user to confirm the commit and approve Phase 4.

# Phase 4: Scalable Trip Detail and Photo Rendering

## Objective

Keep Trip Detail responsive for long recordings and many photos without changing the stored raw data.

## Files to inspect first

- `TripDetailActivity.kt`, especially `loadTripDetails()`, chart binders, and `bindPhotos()`.
- `AppDatabase.kt` capped queries and indexes.
- `SensorFusion.kt` downsampling helpers.
- `RouteMapDialogFragment.kt` companion-object data passing, route construction, and segment values.
- Detail and photo layouts under `app/src/main/res/layout/`.

## Data-loading rules

- Keep raw data in Room; bound only data used for visualization.
- Do not load the same full trace multiple times solely for charts.
- Use database-side limits/aggregation where correctness permits; otherwise aggregate on `Dispatchers.Default` before assigning UI data.
- Keep event/timeline ordering and event identity exact.
- Define explicit maximums for route points, speed points, sensor chart points, and photos retained in active views.
- Do not use a limit that silently drops all events or causes.
- Ensure cancellation of the detail coroutine when the activity is destroyed.
- Do not update views after cancellation or recreation.

## Charts and sensor fusion

- Perform sensor fusion off the main thread.
- Downsample/aggregate before creating MPAndroidChart `Entry` objects.
- Fix or test `downsampleToCount()` edge cases if used: empty input, max count 1, max count 2, and max count larger than input.
- Preserve chart labels/units and elapsed-time semantics.
- Add tests proving the number of chart entries never exceeds the configured bound.

## Map lifecycle and correctness

- Do not pass large lists through mutable companion-object fields.
- Prefer passing `tripId` through fragment arguments and loading bounded route/map data from Room/repository.
- If a state object is unavoidable, make it lifecycle-owned and bounded.
- Filter rows with null latitude/longitude before creating `GeoPoint`.
- Handle zero, one, and two valid route points without indexing crashes.
- Keep parameter-to-segment alignment correct after filtering/downsampling.
- Call the MapView lifecycle methods correctly and release/detach it in `onDestroyView()` as required by osmdroid.

## Photos

- Do not call full-resolution `BitmapFactory.decodeFile()` on the main thread.
- Read bounds first and use `inSampleSize` to target the thumbnail dimensions.
- Decode on a background dispatcher.
- Use a `RecyclerView` adapter or another bounded/lifecycle-aware view strategy instead of adding every bitmap to a `LinearLayout`.
- Avoid retaining full-size bitmaps after binding.
- Handle deleted/missing/corrupt files as an omitted/placeholder card, not a crash.

## Tests

- Downsampling limits and edge cases.
- Dense trace produces bounded route/chart entries.
- Sensor fusion/aggregation runs off the main thread.
- Thumbnail decode requests bounded dimensions.
- Missing/corrupt photo files are handled.
- Activity recreation/cancellation does not cause stale view updates.
- Route map survives recreation and invalid coordinates are excluded.
- Empty and single-point routes render without exceptions.

## Automated verification

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew lintDebug
```

## Visual/manual verification

- Use the dense fixture/demo trip.
- Open Trip Detail and verify the loading indicator transitions to usable content.
- Scroll through timeline, all charts, map, and photos.
- Confirm no visible freeze or crash.
- Confirm the route stays geographically correct and parameter colors align with route segments.
- Confirm photos display as thumbnails and missing files do not break the screen.
- Recreate the activity and route dialog; confirm data is still available.

## Commit

```text
Bound trip detail rendering and photo loading
```

Stop after the commit and ask the user to confirm the commit and approve Phase 5.

# Phase 5: Debug Demo Data and End-to-End QA

## Objective

Create repeatable visual data for manual testing without adding seed behavior to release builds or changing automated-test isolation.

## Files to inspect first

- `TripHistoryActivity.kt` and its layout.
- `TripAdapter.kt`.
- `AppDatabase.kt` and the repository/finalization path from Phase 1.
- `TripDetailActivity.kt` and photo storage behavior in `MainActivity.kt`.
- Gradle source-set configuration.

## Debug-only implementation

- Put seeding code under `app/src/debug/` or another source-set boundary excluded from release.
- Use the same completed-trip persistence contract as production where practical; do not insert malformed rows that production could not create.
- Add debug-only UI controls in Trip History, preferably clearly labelled.
- Seed deterministic records with a recognizable marker so clear only removes demo records.
- Include:
  - One normal short trip with route, events, all visual chart categories, and photos.
  - One dense trip for performance testing.
  - Two overlapping-time trips with distinct data for deletion testing.
- Generate small JPEG files into app-private storage.
- Make seeding idempotent: a second seed does not duplicate uncontrolled data.
- “Clear demo trips” must delete only marked demo trips and their files.
- Release builds must not show or execute seed controls.

## Tests

- Seed is idempotent.
- Clear removes all and only demo records/files.
- Demo photos have valid paths and trip IDs.
- Release source/build excludes debug controls.
- End-to-end deletion isolation remains true with seeded overlap.
- Full unit/instrumented test suite passes.

## Automated verification

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew lintDebug
```

If a release variant is configured and dependencies are available, also run its build/lint task and verify debug-only controls are absent.

## Visual/manual verification

- Install a debug build and open Trip History.
- Confirm seed/clear controls are present only in debug.
- Seed data and confirm expected normal, dense, and overlapping trips appear.
- Open normal and dense details; inspect route, timeline, charts, and photos.
- Delete one overlapping trip and confirm the other is unchanged.
- Clear demo trips and confirm only demo records/files disappear.
- Install a release build if available; confirm debug controls are absent.

## Commit

```text
Add debug demo data for manual QA
```

Stop after the commit and ask the user whether the complete implementation should receive final review.

## 5. Final Verification

Run all available checks after the last approved phase:

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew lintDebug
```

Confirm all of the following:

- Room migrations work from every supported old schema.
- Draft/incomplete trips never appear as completed history entries.
- Long recording persistence uses bounded memory and durable batches.
- Final save publication happens only after successful persistence.
- Vosk stop/restart/destroy operations do not race.
- Deletion is strictly scoped by `tripId`.
- Overlapping trips remain independent.
- Detail screen chart/map data is bounded.
- Photo decoding is sampled/off-main-thread and missing files are safe.
- Debug demo data is absent from release behavior.
- `git status --short` contains no accidental generated files or secrets.

Final reports must distinguish:

- Passed automated checks.
- Passed manual/device checks.
- Checks not run because no emulator/device was available.
- Known remaining limitations.
