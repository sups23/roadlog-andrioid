# Android App Development Plan: RoadLog

**Purpose:** Single-sensor logger for in-vehicle delay cause observation during Master's thesis data collection.

---

## Objective

A single Android app (Kotlin) that runs as a foreground service, simultaneously logging GPS at 1 Hz, accelerometer at 50 Hz, and accepting voice-annotated cause codes, with one-tap CSV export after each trip. Screen-off operation. Minimal external dependencies beyond standard Android APIs. Output is **three separate CSV files plus a JSON manifest**; timestamp merging is handled by the Python post-processor.

---

## Architecture

```
+-----------------------------------------+
|              MainActivity               |
|  - Start/Stop buttons                   |
|  - Status display (duration, events)    |
|  - GPS lock indicator                   |
|  - Microphone listening indicator       |
|  - Current cause feedback (last spoken) |
|  - Starts/stops LoggerService           |
|  - Triggers CSV export on Stop          |
+------------------+----------------------+
                   |
+------------------v----------------------+
|           LoggerService                 |
|  - Foreground service + notification    |
|  - Partial wake lock                    |
|  - GPS listener: LocationManager, 1 Hz  |
|  - Accel listener: SensorManager, 50 Hz |
|  - Speech listener: SpeechRecognizer    |
|  - Buffers: gpsBuffer, accelBuffer,     |
|    eventBuffer (typed data classes)     |
|  - Writes to Room database as backup    |
+------------------+----------------------+
                   |
+------------------v----------------------+
|           CsvExporter                   |
|  - Writes 3 CSV files + manifest JSON   |
|  - gps.csv, accel.csv, events.csv       |
|  - manifest.json with calibration       |
|  - Saves to Downloads/RoadLog/          |
+-----------------------------------------+
```

---

## Complete File List

| # | File | Purpose | Lines (approx) |
|---|---|---|---|
| 1 | MainActivity.kt | UI, start/stop, status, permission handling | 180 |
| 2 | LoggerService.kt | Foreground service, GPS, accelerometer, Vosk speech rec | 420 |
| 3 | CsvExporter.kt | Write separate CSV files + JSON manifest | 110 |
| 4 | AppDatabase.kt | Room database (backup if CSV export fails) | 50 |
| 5 | DataModels.kt | GpsPoint, AccelPoint, DelayEvent, TripData, CauseCode | 40 |
| 6 | VoskSpeechRecognizer.kt | Vosk model loading + microphone listener | 180 |
| 7 | FuzzyCauseMatcher.kt | Levenshtein fuzzy matching for cause codes | 140 |
| 8 | AndroidManifest.xml | Permissions, service declaration, foreground type | manual |
| 9 | activity_main.xml | Layout: buttons, status text, indicators | 100 |
| 10 | build.gradle | Dependencies (includes Vosk + JNA) | manual |

---

## File Specifications

### File 1: MainActivity.kt (~160 lines)

**UI Elements:**
- Start Trip button (green, full width, 60dp height, at top)
- Stop Trip button (red, full width, 60dp height, at bottom)
- 8 cause feedback labels in a 2x4 grid (grayed out, highlights green when cause spoken)
- Status bar: trip duration (HH:MM:SS), event count, GPS lock status (green dot / red dot), microphone listening status
- Last spoken cause display with timestamp

**Behavior:**
- App launches: Start button enabled, Stop disabled, all cause labels gray
- Tap Start: checks permissions (Location, Microphone)
- If permissions missing: request them via standard Android dialogs
- If all granted: starts LoggerService with `startForegroundService(intent)`
- Updates UI every second via LiveData/Flow from service
- Tap Stop: stops service, triggers CSV export, enables Start button
- Export success/failure is broadcast back to MainActivity and shown as a Toast with the actual file path
- Cause labels highlight briefly (500ms) when a cause is recognized, then fade

**Permissions handled:**
- `ACCESS_FINE_LOCATION` — GPS
- `RECORD_AUDIO` — speech recognition
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — prompt user, optional but recommended

**Notes:**
- `POST_NOTIFICATIONS` runtime permission is not required on API 30 (Android 11); notification channel creation is still required.

---

### File 2: LoggerService.kt (~380 lines)

**Service Lifecycle:**
- `onCreate()`: create notification channel, acquire partial wake lock
- `onStartCommand()`: build persistent notification, register all listeners
- `onDestroy()`: unregister listeners, release wake lock, flush buffers to database, call CsvExporter

**GPS Listener:**
```kotlin
LocationManager.requestLocationUpdates(
    LocationManager.GPS_PROVIDER,
    1000,    // min time interval: 1 second (1 Hz)
    0f,      // min distance: 0 (log every update)
    gpsCallback,
    looper
)

gpsCallback.onLocationChanged(location):
    gpsBuffer.add(
        GpsPoint(
            timestampMs = System.currentTimeMillis(),
            lat = location.latitude,
            lon = location.longitude,
            speedKmh = location.speed * 3.6f
        )
    )
```

**Accelerometer Listener:**
```kotlin
SensorManager.registerListener(
    accelListener,
    accelerometerSensor,
    SensorManager.SENSOR_DELAY_GAME  // 20,000us = ~50 Hz
)

accelListener.onSensorChanged(event):
    accelBuffer.add(
        AccelPoint(
            timestampNano = System.nanoTime(),
            accelZ = event.values[2]
        )
    )
```

Uses `System.nanoTime()` for accelerometer timestamps (higher precision than millis). GPS uses millis (1 Hz does not need nano precision). Merging is done in Python post-processing using the calibration offset stored in `manifest.json`.

**WakeLock:**
```kotlin
wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadLog::WakeLock")
wakeLock.acquire(60 * 60 * 1000L) // 1 hour timeout
```
Released in `onDestroy()`.

**Speech Recognition (Vosk offline):**

The app uses **Vosk** for fully offline speech recognition. The small English model (`vosk-model-small-en-us-0.15`, ~40 MB) is bundled in `assets/model-en-us/` and unpacked on first run.

```kotlin
val vosk = VoskSpeechRecognizer(this)
vosk.prepare(
    onReady = { vosk.startListening(callback) },
    onError = { error -> Log.e(TAG, error) }
)
```

Vosk runs in **free recognition mode** with a direct `AudioRecord` loop. This avoids Vosk's `SpeechService` wrapper, which can get stuck after the first utterance on some devices. The custom loop:

- Captures raw PCM audio at 16 kHz mono
- Feeds buffers directly to `Recognizer.acceptWaveForm()`
- Calls `Recognizer.reset()` after each final utterance
- Emits partial results for the debug UI

This gives full control over continuous listening and reliably handles repeated single-word commands.

**Fuzzy matching:**

Recognized text is passed through `FuzzyCauseMatcher`, which uses normalized Levenshtein distance against an expanded keyword list (including common misheard variants like `seegal`, `roufness`, `markit`). This handles accent distortions and road noise.

```kotlin
val match = fuzzyMatcher.findBestMatch(text, threshold = 0.5)
if (match != null) {
    recordCauseEvent(match.causeCode)
}
```

**Number aliases:**

| Number | Cause |
|---|---|
| 1 | SIGNAL |
| 2 | QUEUE |
| 3 | BUS |
| 4 | PEDESTRIAN |
| 5 | ROUGHNESS |
| 6 | FRICTION |
| 7 | TURNING |
| 8 | MARKET |

**Buffers (typed, flushed on export):**
- `gpsBuffer: MutableList<GpsPoint>` — wall-clock millis + lat/lon/speed
- `accelBuffer: MutableList<AccelPoint>` — nanoTime + Z-axis
- `eventBuffer: MutableList<DelayEvent>` — wall-clock millis + cause code

For long trips (45+ minutes, ~2,700 GPS rows + ~135,000 accelerometer rows): total memory ~5-8 MB. Well within phone limits.

**Foreground Notification:**
- Title: "RoadLog — Recording"
- Content: "GPS: active | Mic: listening | Events: 12 | Duration: 23:45"
- Ongoing: true (cannot be swiped away)
- Channel: "RoadLog Service" (created once in `onCreate`)

---

### File 3: CsvExporter.kt (~110 lines)

**Export Logic:**
1. Receives all three buffers and calibration offset from LoggerService
2. Creates folder `Downloads/RoadLog/` if it does not exist
3. Writes four files per trip:
   - `roadlog_YYYYMMDD_HHMMSS_gps.csv`
   - `roadlog_YYYYMMDD_HHMMSS_accel.csv`
   - `roadlog_YYYYMMDD_HHMMSS_events.csv`
   - `roadlog_YYYYMMDD_HHMMSS_manifest.json`

**CSV formats:**
```csv
# *_gps.csv
timestamp_ms,latitude,longitude,speed_kmh
1720000001000,27.7000,85.3200,32.5
1720000002000,27.7001,85.3201,31.2

# *_accel.csv
timestamp_nano,accel_z
45839201830492,9.81
45839201850492,9.75

# *_events.csv
timestamp_ms,cause_code
1720000002400,SIGNAL
1720000003500,BUS
```

**Manifest JSON:**
```json
{
  "startTimeMs": 1720000000000,
  "endTimeMs": 1720002700000,
  "calibration": {
    "nanoTimeAtStart": 45839201830492,
    "currentTimeMillisAtStart": 1720000000000
  },
  "rowCounts": {
    "gps": 2700,
    "accel": 135000,
    "events": 12
  }
}
```

**Storage implementation (API 30):**

Primary location: public `Downloads/RoadLog/`. Fallback location: app-specific external files directory `Android/data/<package>/files/RoadLog/` (guaranteed writable, no permission needed).

```kotlin
val primaryFolder = File(
    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
    "RoadLog"
)
val fallbackFolder = File(context.getExternalFilesDir(null), "RoadLog")
```

`targetSdk 29` plus `requestLegacyExternalStorage="true"` allows direct file writes to `Downloads/RoadLog/` on Android 11 (API 30). If `WRITE_EXTERNAL_STORAGE` is denied or scoped storage blocks the public directory, the exporter automatically falls back to the app-specific directory and reports the actual path to the user via Toast.

**Export result:** `CsvExporter` returns a sealed `ExportResult` (Success or Failure). `LoggerService` broadcasts the result, and `MainActivity` shows a Toast with the file path or error message.

**Fallback to Room database:** If CSV export fails entirely, data is already saved to Room as a backup. User can retry export later.

---

### File 4: AppDatabase.kt (~50 lines)

Room database with one entity and one DAO. Used as persistent backup.

```kotlin
@Entity(tableName = "trip_data")
data class TripData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val speedKmh: Float?,
    val accelZ: Float?,
    val eventCause: String?
)

@Dao
interface TripDao {
    @Insert
    suspend fun insertAll(rows: List<TripData>)

    @Query("SELECT * FROM trip_data ORDER BY timestamp")
    suspend fun getAll(): List<TripData>

    @Query("DELETE FROM trip_data")
    suspend fun deleteAll()
}

@Database(entities = [TripData::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}
```

Flush on stop is performed on `Dispatchers.IO` in chunks of 500 rows to avoid ANR.

---

### File 5: DataModels.kt (~40 lines)

```kotlin
data class GpsPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float
)

data class AccelPoint(
    val timestampNano: Long,
    val accelZ: Float
)

data class DelayEvent(
    val timestamp: Long,
    val causeCode: String
)

enum class CauseCode(val displayName: String) {
    SIGNAL("Signal"),
    QUEUE("Queue"),
    BUS("Bus"),
    PEDESTRIAN("Pedestrian"),
    ROUGHNESS("Roughness"),
    FRICTION("Friction"),
    TURNING("Turning"),
    MARKET("Market")
}
```

---

### File 6: AndroidManifest.xml (manual additions)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

<uses-feature android:name="android.hardware.location.gps" android:required="true" />
<uses-feature android:name="android.hardware.microphone" android:required="true" />
<uses-feature android:name="android.hardware.sensor.accelerometer" android:required="true" />

<application
    android:requestLegacyExternalStorage="true"
    ...>
    <service
        android:name=".LoggerService"
        android:foregroundServiceType="location"
        android:exported="false" />
</application>
```

---

### File 7: activity_main.xml (~90 lines)

```xml
ScrollView
  LinearLayout (vertical, padding 24dp)
    TextView: "RoadLog" (title, 24sp, bold, centered)
    Button: "START TRIP" (green background, white text, 60dp height, full width)
    TextView: status (duration, event count, GPS status, mic status, 16sp)
    LinearLayout (horizontal, grid, 2 columns)
      LinearLayout (vertical, weight 1)
        TextView: "SIGNAL" (gray bg, 12sp, centered, 40dp height)
        TextView: "QUEUE"
        TextView: "BUS"
        TextView: "PEDESTRIAN"
      LinearLayout (vertical, weight 1)
        TextView: "ROUGHNESS"
        TextView: "FRICTION"
        TextView: "TURNING"
        TextView: "MARKET"
    TextView: lastSpoken (14sp, italic)
    Button: "STOP & EXPORT" (red background, white text, 60dp height, full width)
```

Cause labels are TextView elements, not buttons. They display feedback only — highlighting green when a cause is recognized via speech. No tap interaction needed.

---

### File 8: build.gradle (app dependencies)

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'kotlin-kapt'
}

android {
    compileSdk 34

    defaultConfig {
        applicationId "com.example.roadlog"
        minSdk 26
        targetSdk 29
        versionCode 1
        versionName "1.0"

        ndk {
            abiFilters 'armeabi-v7a', 'arm64-v8a', 'x86_64', 'x86'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = '1.8'
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging false
        }
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.room:room-runtime:2.6.1'
    kapt 'androidx.room:room-compiler:2.6.1'

    // Vosk offline speech recognition
    implementation 'com.alphacephei:vosk-android:0.3.75@aar'
    implementation 'net.java.dev.jna:jna:5.18.1@aar'
}
```

Also requires Android Gradle Plugin 8.5+ and Gradle 8.9+ for 16 KB native library alignment. `AndroidManifest.xml` must include `android:extractNativeLibs="true"` in the `<application>` tag.

---

## Build and Test Workflow

### Desk Verification (Before Driving)

| Test | Steps | Expected Result |
|---|---|---|
| Launch + Start | Open app, tap Start | Notification appears. Status shows "Recording." GPS indicator turns green within 5 sec. Mic indicator shows "listening." |
| Speech recognition | Speak "signal" or "light" clearly | SIGNAL label flashes green for 500ms. Event count increments. |
| Simpler aliases | Speak "bump", "turn", "stall" | ROUGHNESS, TURNING, MARKET labels flash respectively. |
| Accelerometer logging | Shake phone for 5 seconds | `*_accel.csv` contains Z-axis spikes > 1.5 at corresponding timestamps. |
| GPS logging | Walk outside for 30 seconds | `*_gps.csv` contains lat/lon changes along your walking path. |
| Screen-off logging | Start, lock phone, wait 2 min, unlock, Stop | CSV files contain continuous rows for the full 2 minutes. No gaps. |
| CSV export | Stop trip | Four files appear in `Downloads/RoadLog/`. Verify headers and row counts. |
| Sample rate verification | Run for exactly 60 seconds | ~60 GPS rows. ~2,700-3,000 accel rows. Count verified. |
| Manifest calibration | Open `*_manifest.json` | Contains `calibration.nanoTimeAtStart` and `calibration.currentTimeMillisAtStart`. |

### Pilot Drive Verification

| Test | Steps | Expected Result |
|---|---|---|
| Short route | Drive 5 min loop. Speak 3 known causes at specific landmarks. | `*_events.csv` shows 3 events at correct GPS coordinates after Python merge. |
| Real corridor | Drive one target corridor, 15 min. Use all 8 cause categories. | All 8 causes appear in events CSV. No gaps in data. |
| Heat test | Park in sun for 10 min, then drive 20 min. | No crash. No missing rows. Phone may be warm but functional. |

### Failure Recovery

If a trip's CSV is empty, truncated, or corrupted:
- Check Room database for raw rows
- If Room also failed: redo the trip
- Investigate logcat for crash stacktrace
- Most likely cause: Android killed the service due to battery optimization. Prompt user to ignore battery optimization before trips.

---

## Known Limitations (v1)

1. **Speech recognition requires internet on some phones.** Android's `SpeechRecognizer` can work offline for simple commands on devices with offline language packs installed. If your phone lacks the English offline pack, it will use Google's cloud service, which requires mobile data during trips. Fix: install the offline English speech recognition pack in Settings > Language and Input > Speech > Offline speech recognition. Test before first drive.

2. **Nepali-accented English may reduce accuracy.** Simpler aliases (`bump`, `turn`, `stall`, `light`) are now defaults to improve recognition. Speak slowly and clearly.

3. **Phone must be charged or have more than 70% battery for 45-minute trips.** Foreground GPS + accelerometer + microphone draws significant power. Without charging, expect 25-30% drain per 45-minute trip.

4. **File sizes per trip:** ~150 KB for GPS, ~2.7 MB for accelerometer, ~1 KB for events, ~500 B for manifest. Total ~2.9 MB per 45-minute trip. For 45 trips: ~130 MB total.

5. **Phone thermal throttling:** If the phone overheats (sunlight, charging, GPS, CPU load), the CPU may throttle and sensor sampling may drop. Test on a hot day before committing to full data collection.

6. **GPS accuracy in narrow streets:** Tall buildings in dense areas (Newroad, Ason) degrade GPS. Signal may jump between buildings. Verify that CSV coordinates follow the actual road, not a building.

---

## Development Timeline

| Day | Task | Hours |
|---|---|---|
| 1 | Install Android Studio, create project, implement MainActivity + LoggerService skeleton | 4-6 |
| 2 | Implement speech recognition, CSV export, manifest JSON, test at desk | 3-4 |
| 3 | Desk verification (all tests), first pilot drive, bug fixes | 3-4 |
| 4 | Second pilot drive, polish UI, final CSV/Python merge check | 2-3 |
| **Total** | | **12-17 hours over 4 days** |

---

## CSV Output Specification (For Python Post-Processing)

Three separate CSV files are exported per trip, plus one manifest JSON.

### `*_gps.csv`
```csv
timestamp_ms,latitude,longitude,speed_kmh
1720000001000,27.7000,85.3200,32.5
1720000002000,27.7001,85.3201,31.2
```

### `*_accel.csv`
```csv
timestamp_nano,accel_z
45839201830492,9.81
45839201850492,9.75
```

### `*_events.csv`
```csv
timestamp_ms,cause_code
1720000002400,SIGNAL
1720000003500,BUS
```

### `*_manifest.json`
```json
{
  "startTimeMs": 1720000000000,
  "endTimeMs": 1720002700000,
  "calibration": {
    "nanoTimeAtStart": 45839201830492,
    "currentTimeMillisAtStart": 1720000000000
  },
  "rowCounts": { "gps": 2700, "accel": 135000, "events": 12 }
}
```

### Python merge formula

To align accelerometer rows with wall-clock time:

```python
wall_time_ms = manifest['calibration']['currentTimeMillisAtStart'] + \
               (row['timestamp_nano'] - manifest['calibration']['nanoTimeAtStart']) / 1_000_000
```

The Python post-processing script can then:
1. Convert all accel timestamps to wall-clock milliseconds
2. Interpolate or forward-fill GPS lat/lon/speed onto accel rows
3. Detect delay events from GPS speed sequences below 5 km/hr for > 20 seconds
4. Join event annotations to the nearest detected delay segment

---

## Fallback Plan

While the custom app is being developed (2-3 days), collect pilot data immediately using:
- GPS Logger app (free, CSV export, 1 Hz)
- Physics Toolbox Suite (free, CSV export, 50 Hz accelerometer)
- Built-in voice recorder (for cause annotations)
- Python script (~80 lines) to merge all three by timestamp

This gives working data on day 1, while the custom app replaces the manual pipeline.
