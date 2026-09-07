# RoadLog Agent Notes

## Scope and Entry Points

- This is a single-module Android app: `:app`, package `com.example.roadlog`, with Kotlin 1.9.22, AGP 8.5.2, Gradle 8.9, compile SDK 34, min SDK 26, and target SDK 29.
- Production code is Kotlin under `app/src/main/java/com/example/roadlog/` with XML layouts and view binding. `MainActivity` owns permissions, recording controls, maps, and service broadcasts; `LoggerService` is the non-exported foreground recorder.
- `TripHistoryActivity` handles completed/recoverable trips, export, and deletion. `TripDetailActivity` handles route/timeline/QA review, sensor charts, audio, and legacy photos.
- Debug-only `DebugInitProvider`/`DebugSeeder` can add demo data. Do not make production behavior depend on `app/src/debug/` data or classes.

## Recording and Data Flow

- Recording requires `ACCESS_FINE_LOCATION` and `RECORD_AUDIO`; the manifest has no camera permission. Current collection has no camera or manual-cause controls, though legacy photo rows remain readable and exportable.
- `LoggerService.Companion` is the source of truth for service actions, extras, and broadcasts. It creates a `RECORDING` draft, buffers GPS/sensor/event rows, and finalizes explicit stops as `COMPLETED`; interrupted drafts remain recoverable/exportable.
- Vosk and segmented AAC/M4A archival audio share one `AudioRecord`. Speech causes, activation phrases, variants, and thresholds come from `app/src/main/assets/cause_config.json`; persisted causes must remain canonical.
- Audio failures are represented in `TripAudio`, `TripQuality`, audit revisions, and restricted ZIP audio indexes. Preserve failure evidence rather than dropping failed or missing segments.
- `LoggerService.onDestroy()` intentionally leaves `serviceScope` alive so final Room/audio flush work can finish.

## Persistence and Exports

- `AppDatabase.kt` is Room schema version 8 with explicit migrations `1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 7 -> 8`; v8 removes the old non-primary annotation tables after preserving their values in the audit trail.
- Schema JSON is exported under `app/schemas/`. Any entity/column change requires a matching migration, version bump, and regenerated schema export.
- Study session/corridor IDs are static fields on each trip; there are no session or corridor tables. `TripDao.deleteTripWithMediaFiles()` deletes rows and attempts cleanup of referenced audio/photo files, returning a cleanup report.
- `ResearchExporter` has `RESTRICTED_RAW` and `PUBLIC_DEIDENTIFIED` modes. Public output must not contain precise GPS, audio, transcripts, reviewer notes, device identity, or historical media.
- Never delete or replace `app/src/main/assets/model-en-us/`; it is required for Vosk model unpacking.

## Environment Model

- The AI agent runs inside a Docker container. Docker is primarily the code-editing, repository-inspection, and lightweight compile/test environment.
- The macOS host is authoritative for full Android builds, Android Studio, emulator testing, physical-device testing, ADB/device interaction, signing, packaging, and SDK-specific runtime debugging.
- Do not try to turn the Docker container into a complete Android workstation. Separate source or configuration failures from limitations of the Docker environment.

## Validation Hierarchy

Use the smallest validation level that provides reasonable confidence.

### Level 1: Source Validation

This is the default for every task:

- Inspect only relevant files and immediate dependencies as needed; do not scan the entire repository without a reason.
- Check `git status --short`, `git diff --name-only`, `git diff`, and `git diff --check`.
- Check imports, types, references, and nearby call sites, then reason statically about the changed code.

### Level 2: Targeted Gradle Validation

Use this only when the change reasonably requires compilation or unit testing. Prefer the smallest applicable task rather than a project-wide build:

```bash
./gradlew :app:compileDebugKotlin --console=plain -q
./gradlew :app:testDebugUnitTest --console=plain -q
```

If module or project names differ, determine the smallest equivalent task. Use lint when relevant, but do not run it automatically after every small edit:

```bash
./gradlew :app:lintDebug --console=plain
```

Focused JVM tests may use fully qualified names, for example:

```bash
./gradlew :app:testDebugUnitTest --tests "com.example.roadlog.FixtureSmokeTest" --console=plain -q
./gradlew :app:testDebugUnitTest --tests "com.example.roadlog.AudioEvidenceTest" --console=plain -q
```

### Level 3: Host and Device Validation

Use the macOS host for Android runtime or device validation. Instrumentation requires a connected device or emulator; report it as not run when none is available.

## Expensive Commands

Do not automatically run these during normal tasks:

```bash
./gradlew build
./gradlew clean
./gradlew clean build
./gradlew assembleDebug
./gradlew installDebug
./gradlew connectedAndroidTest
```

These commands are allowed only when the user explicitly asks for them, the task specifically concerns build or package behavior, or targeted validation cannot reasonably verify the change. Never use `clean` as a generic troubleshooting step.

## Android SDK Policy

```text
Docker SDK: /workspace/andriod-sdk
Configuration source: local.properties.docker
```

- Do not repeatedly discover the SDK or search the host for Android SDK paths.
- Do not install or update SDK packages unless the task explicitly concerns SDK configuration.
- Do not edit `local.properties.docker` unless the task explicitly requires an environment configuration change.
- Do not continuously rewrite `local.properties`. If Docker needs it, use the existing `local.properties.docker` setup rather than rediscovering or rewriting SDK paths.
- Preserve the spelling and path `/workspace/andriod-sdk` exactly.
- When validation fails because of the Docker SDK or another environment problem, identify that environment issue and stop retrying unrelated Gradle or SDK fixes.

## Device and Runtime-Only Validation

GPS/location, accelerometer and other sensors, microphone/audio, permissions, foreground/background services, lifecycle behavior, battery optimization, notifications, emulator/device-specific behavior, and Android platform integration may require host or device validation.

After reasonable source and targeted validation, report exactly:

```text
Host Android validation required
```

Do not repeatedly attempt to reproduce device behavior inside Docker.

## Token and Time Efficiency

- Inspect changed files first, then immediate dependencies only as required.
- Use `rg`, `find`, `sed`, `head`, and `tail` selectively.
- Avoid generated files, `.gradle/`, `build/`, Android SDK internals, caches, `.git/` internals, and large unrelated files.
- For Gradle failures, initially inspect only the useful final portion of output:

```bash
./gradlew <task> --console=plain 2>&1 | tail -100
```

- Do not repeatedly feed huge Gradle logs into context. If the actual error is already visible, diagnose it directly instead of rerunning with increasingly verbose flags.

## Failure and Retry Policy

Do not enter repetitive loops such as:

```text
build fails
-> modify SDK config
-> retry
-> modify local.properties
-> retry
-> run clean
-> retry
-> inspect entire project
-> retry
```

Instead:

1. Classify the failure as source-code, dependency, Gradle-configuration, or Docker-environment related.
2. Fix only problems relevant to the requested task.
3. Retry the smallest validation once.
4. If blocked by environment limitations, stop and report the limitation.

## Completion Criteria

A normal implementation task does not require a full APK build. It is complete when:

- The requested implementation is finished.
- Changed files have been reviewed and no obvious source, type, or syntax issues remain.
- Relevant targeted validation has passed where practical.
- Any required host or device validation is clearly stated.

Do not keep working merely to obtain a full Android build inside Docker when it adds no meaningful confidence.

## Commands and Tests

- JVM tests and fixtures are under `app/src/test/java/com/example/roadlog/`; Room/device tests are under `app/src/androidTest/java/com/example/roadlog/`. Keep JVM tests independent of real Android services and SDK stub behavior; use instrumentation for Room/framework behavior, hardware, or device-only flows.
- Instrumentation requires a connected device/emulator. Report it as not run when no device is available.

## Environment and Runtime Constraints

- AGP AAPT2 is an x86_64 Linux binary. On aarch64 hosts without x86_64 emulation and glibc, resource processing fails before Kotlin compilation; use a compatible x86_64 Android host.
- Disable battery optimization for reliable long trips. Map tiles are cached in the app-private files directory.
- Use `adb logcat -s RoadLog:D` for recorder diagnostics.
