package com.example.roadlog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class LoggerService : Service() {

    companion object {
        const val ACTION_START = "com.example.roadlog.START"
        const val ACTION_STOP = "com.example.roadlog.STOP"
        const val ACTION_CAUSE_SELECTED = "com.example.roadlog.CAUSE_SELECTED"
        const val ACTION_STATUS = "com.example.roadlog.STATUS_UPDATE"
        const val ACTION_HEARD_TEXT = "com.example.roadlog.HEARD_TEXT"
        const val ACTION_LOCATION_UPDATE = "com.example.roadlog.LOCATION_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_HEARD_TEXT = "heard_text"
        const val EXTRA_IS_PARTIAL = "is_partial"
        const val EXTRA_CAUSE_CODE = "cause_code"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ENABLE_CAMERA = "enable_camera"
        const val ACTION_CAPTURE_PHOTO = "com.example.roadlog.CAPTURE_PHOTO"
        const val EXTRA_PHOTO_TIME = "photo_time"
        const val ACTION_TRIP_SAVED = "com.example.roadlog.TRIP_SAVED"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_START_TIME_MS = "start_time_ms"
        const val NOTIFICATION_CHANNEL_ID = "roadlog_service_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "RoadLog"
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private lateinit var accelerometer: Sensor
    private var gyroscope: Sensor? = null
    private var rotationSensor: Sensor? = null
    private lateinit var database: AppDatabase
    private lateinit var causeConfig: CauseConfig
    private lateinit var fuzzyMatcher: FuzzyCauseMatcher

    private var voskRecognizer: VoskSpeechRecognizer? = null

    private val gpsBuffer = mutableListOf<GpsPoint>()
    private val accelBuffer = mutableListOf<AccelPoint>()
    private val gyroBuffer = mutableListOf<GyroPoint>()
    private val rotationBuffer = mutableListOf<RotationPoint>()
    private val eventBuffer = mutableListOf<DelayEvent>()
    private val bufferLock = Any()

    private var startTimeMs: Long = 0
    private var startNanoTime: Long = 0
    private var endNanoTime: Long = 0
    private var eventCount = 0
    private var gpsPointCount = 0
    private var accelPointCount = 0
    private var totalDistanceMeters = 0.0
    private var lastGpsPoint: GpsPoint? = null
    private val causeBreakdownMap = mutableMapOf<String, Int>()
    private var gpsLocked = false
    private var isListening = false
    private var modelReady = false
    private val pendingStartAfterModelReady = AtomicBoolean(false)

    private var captureEnabled = false
    private var draftTripId: Long = -1
    private val draftReady = AtomicBoolean(false)

    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    private val gpsCallback = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            gpsLocked = true
            Log.i(TAG, "Location from ${location.provider}: ${location.latitude},${location.longitude} acc=${location.accuracy}")
            val point = GpsPoint(
                timestampMs = System.currentTimeMillis(),
                lat = location.latitude,
                lon = location.longitude,
                speedKmh = location.speed * 3.6f
            )
            synchronized(bufferLock) {
                gpsBuffer.add(point)
                gpsPointCount++
            }
            val dist = lastGpsPoint?.let { prev ->
                val results = FloatArray(1)
                Location.distanceBetween(prev.lat, prev.lon, point.lat, point.lon, results)
                results[0].toDouble()
            } ?: 0.0
            if (dist > 1.0) totalDistanceMeters += dist
            lastGpsPoint = point
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                gpsLocked = false
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            synchronized(bufferLock) {
                accelBuffer.add(
                    AccelPoint(
                        timestampNano = System.nanoTime(),
                        x = x,
                        y = y,
                        z = z
                    )
                )
                accelPointCount++
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(bufferLock) {
                gyroBuffer.add(
                    GyroPoint(
                        timestampNano = System.nanoTime(),
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val w = if (event.values.size >= 4) event.values[3] else computeRotationScalar(x, y, z)
            synchronized(bufferLock) {
                rotationBuffer.add(
                    RotationPoint(
                        timestampNano = System.nanoTime(),
                        x = x,
                        y = y,
                        z = z,
                        w = w
                    )
                )
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun computeRotationScalar(x: Float, y: Float, z: Float): Float {
        val sumSq = x * x + y * y + z * z
        return if (sumSq < 1f) kotlin.math.sqrt(1f - sumSq) else 0f
    }

    private fun requestPhotoCapture() {
        val last = lastGpsPoint
        sendBroadcast(Intent(ACTION_CAPTURE_PHOTO).apply {
            putExtra(EXTRA_LAT, last?.lat ?: 0.0)
            putExtra(EXTRA_LON, last?.lon ?: 0.0)
            putExtra(EXTRA_PHOTO_TIME, System.currentTimeMillis())
        })
    }

    private val statusUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                broadcastStatus()
                handler.postDelayed(this, 1000)
            }
        }
    }

    private val locationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get()) {
                broadcastLatestLocation()
                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoadLog::WakeLock")

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!!
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        database = AppDatabase.getDatabase(this)
        causeConfig = try {
            CauseConfigLoader.load(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cause config, using defaults", e)
            CauseConfig(
                confidenceThreshold = 0.6f,
                fuzzyThreshold = 0.85,
                minWordLength = 3,
                causes = emptyList()
            )
        }
        fuzzyMatcher = FuzzyCauseMatcher(causeConfig)

        recoverAbandonedDrafts()
        prepareVoskModel()
    }

    private fun recoverAbandonedDrafts() {
        serviceScope.launch {
            try {
                val abandoned = database.tripDao().getAbandonedTrips()
                if (abandoned.isEmpty()) return@launch
                Log.i(TAG, "Cleaning up ${abandoned.size} abandoned draft trips")
                for (trip in abandoned) {
                    database.tripDao().deleteTripDataForTrip(trip.id)
                    database.tripDao().deleteTrip(trip.id)
                    Log.d(TAG, "Removed abandoned draft trip ${trip.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean up abandoned drafts", e)
            }
        }
    }

    private fun prepareVoskModel() {
        Log.i(TAG, "Preparing Vosk offline model...")
        voskRecognizer = VoskSpeechRecognizer(this, GrammarBuilder.buildGrammarJson(causeConfig))
        voskRecognizer?.prepare(
            onReady = {
                Log.i(TAG, "Vosk model ready")
                modelReady = true
                broadcastStatus("Vosk model ready. Waiting for START...")
                if (pendingStartAfterModelReady.getAndSet(false)) {
                    startRecording()
                }
            },
            onError = { error ->
                Log.e(TAG, "Vosk model error: $error")
                modelReady = false
                broadcastStatus("Vosk model error: $error")
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action} modelReady=$modelReady isRunning=${isRunning.get()}")
        when (intent?.action) {
            ACTION_START -> {
                captureEnabled = intent.getBooleanExtra(EXTRA_ENABLE_CAMERA, false)
                if (modelReady) {
                    startRecording()
                } else {
                    Log.i(TAG, "Model not ready yet, will start after preparation")
                    pendingStartAfterModelReady.set(true)
                    broadcastStatus("Loading Vosk model...")
                }
            }
            ACTION_STOP -> stopRecording()
            ACTION_CAUSE_SELECTED -> {
                val causeCode = intent.getStringExtra(EXTRA_CAUSE_CODE)
                if (causeCode != null && isRunning.get()) {
                    recordCauseEvent(causeCode)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning.get()) {
            stopRecording()
        }
        voskRecognizer?.destroy()
        flushJob?.cancel()
        // serviceScope is not canceled here so the background finalization coroutine can finish.
    }

    private fun startRecording() {
        if (isRunning.getAndSet(true)) return

        Log.i(TAG, "startRecording() called")

        startTimeMs = System.currentTimeMillis()
        startNanoTime = System.nanoTime()
        eventCount = 0
        gpsPointCount = 0
        accelPointCount = 0
        totalDistanceMeters = 0.0
        lastGpsPoint = null
        endNanoTime = 0
        gpsLocked = false

        causeBreakdownMap.clear()
        synchronized(bufferLock) {
            gpsBuffer.clear()
            accelBuffer.clear()
            gyroBuffer.clear()
            rotationBuffer.clear()
            eventBuffer.clear()
        }

        wakeLock.acquire(60 * 60 * 1000L)
        Log.i(TAG, "WakeLock acquired")

        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service started")

        serviceScope.launch {
            draftTripId = createDraftTrip()
            if (draftTripId < 0) {
                Log.e(TAG, "Failed to create draft trip, recording may be lost")
            } else {
                draftReady.set(true)
            }
        }

        startPeriodicFlush()

        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                gpsCallback,
                Looper.getMainLooper()
            )
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                1000L,
                0f,
                gpsCallback,
                Looper.getMainLooper()
            )
            Log.i(TAG, "Requested location updates from GPS and NETWORK providers")
            Log.i(TAG, "GPS enabled=${locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)} NETWORK enabled=${locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Location provider not available", e)
        }

        sensorManager.registerListener(accelListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        gyroscope?.let {
            sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        rotationSensor?.let {
            sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        startVoskListening()

        handler.post(statusUpdateRunnable)
        handler.post(locationUpdateRunnable)
    }

    private fun stopRecording() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "stopRecording() called. GPS=${gpsPointCount}, Accel=${accelPointCount}, Events=${eventCount}")

        handler.removeCallbacks(statusUpdateRunnable)
        handler.removeCallbacks(locationUpdateRunnable)
        flushJob?.cancel()
        flushJob = null

        try {
            locationManager.removeUpdates(gpsCallback)
            Log.i(TAG, "Location listeners removed")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing location updates", e)
        }

        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        sensorManager.unregisterListener(rotationListener)
        Log.i(TAG, "Sensor listeners removed")

        voskRecognizer?.stop()
        isListening = false
        captureEnabled = false
        Log.i(TAG, "Vosk listener stopped")

        val endTimeMs = System.currentTimeMillis()
        endNanoTime = System.nanoTime()

        serviceScope.launch {
            Log.i(TAG, "Starting final flush and finalization")
            try {
                flushBuffersToDatabase()
                finalizeTripAndBroadcast(endTimeMs, endNanoTime)
            } catch (e: Exception) {
                Log.e(TAG, "Room finalization failed", e)
            }

            withContext(Dispatchers.Main) {
                draftTripId = -1
                draftReady.set(false)
                if (wakeLock.isHeld) {
                    wakeLock.release()
                    Log.i(TAG, "WakeLock released")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Log.i(TAG, "Service stopped")
            }
        }
    }

    private suspend fun createDraftTrip(): Long {
        val trip = Trip(
            startTimeMs = startTimeMs,
            endTimeMs = 0,
            startNanoTime = startNanoTime,
            endNanoTime = 0,
            distanceMeters = 0.0,
            eventCount = 0,
            gpsPointCount = 0,
            accelPointCount = 0,
            causeBreakdown = "{}",
            createdAt = 0,
            status = TripStatus.RECORDING
        )
        return database.tripDao().insertTrip(trip)
    }

    private fun startPeriodicFlush() {
        flushJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                flushBuffersToDatabase()
            }
        }
    }

    private suspend fun flushBuffersToDatabase() {
        if (!draftReady.get() || draftTripId < 0) return
        val gpsSnapshot: List<GpsPoint>
        val accelSnapshot: List<AccelPoint>
        val gyroSnapshot: List<GyroPoint>
        val rotSnapshot: List<RotationPoint>
        val eventSnapshot: List<DelayEvent>
        synchronized(bufferLock) {
            gpsSnapshot = gpsBuffer.toList(); gpsBuffer.clear()
            accelSnapshot = accelBuffer.toList(); accelBuffer.clear()
            gyroSnapshot = gyroBuffer.toList(); gyroBuffer.clear()
            rotSnapshot = rotationBuffer.toList(); rotationBuffer.clear()
            eventSnapshot = eventBuffer.toList(); eventBuffer.clear()
        }

        if (gpsSnapshot.isEmpty() && accelSnapshot.isEmpty() && gyroSnapshot.isEmpty() &&
            rotSnapshot.isEmpty() && eventSnapshot.isEmpty()) return

        val rows = mutableListOf<TripData>()

        gpsSnapshot.forEach { point ->
            rows.add(
                TripData(
                    tripId = draftTripId,
                    timestamp = point.timestampMs,
                    latitude = point.lat,
                    longitude = point.lon,
                    speedKmh = point.speedKmh,
                    accelZ = null,
                    eventCause = null
                )
            )
        }

        accelSnapshot.forEach { point ->
            val timestampMs = startTimeMs + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                point.timestampNano - startNanoTime
            )
            rows.add(
                TripData(
                    tripId = draftTripId,
                    timestamp = timestampMs,
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    accelX = point.x,
                    accelY = point.y,
                    accelZ = point.z,
                    eventCause = null,
                    rawTimestamp = point.timestampNano
                )
            )
        }

        gyroSnapshot.forEach { point ->
            val timestampMs = startTimeMs + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                point.timestampNano - startNanoTime
            )
            rows.add(
                TripData(
                    tripId = draftTripId,
                    timestamp = timestampMs,
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    gyroX = point.x,
                    gyroY = point.y,
                    gyroZ = point.z,
                    eventCause = null,
                    rawTimestamp = point.timestampNano
                )
            )
        }

        rotSnapshot.forEach { point ->
            val timestampMs = startTimeMs + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                point.timestampNano - startNanoTime
            )
            rows.add(
                TripData(
                    tripId = draftTripId,
                    timestamp = timestampMs,
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    rotX = point.x,
                    rotY = point.y,
                    rotZ = point.z,
                    rotW = point.w,
                    eventCause = null,
                    rawTimestamp = point.timestampNano
                )
            )
        }

        eventSnapshot.forEach { event ->
            rows.add(
                TripData(
                    tripId = draftTripId,
                    timestamp = event.timestamp,
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    accelZ = null,
                    eventCause = event.causeCode
                )
            )
        }

        rows.chunked(500).forEach { chunk ->
            database.tripDao().insertAll(chunk)
        }

        Log.d(TAG, "Flushed ${rows.size} rows to draft trip $draftTripId")
    }

    private suspend fun finalizeTripAndBroadcast(endTimeMs: Long, endNanoTime: Long) {
        if (draftTripId < 0) return

        val breakdown = JSONObject().apply {
            synchronized(bufferLock) {
                causeBreakdownMap.forEach { (cause, count) -> put(cause, count) }
            }
        }.toString()

        database.tripDao().finalizeTrip(
            tripId = draftTripId,
            endTimeMs = endTimeMs,
            endNanoTime = endNanoTime,
            distanceMeters = totalDistanceMeters,
            eventCount = eventCount,
            gpsPointCount = gpsPointCount,
            accelPointCount = accelPointCount,
            causeBreakdown = breakdown,
            createdAt = System.currentTimeMillis()
        )

        Log.i(TAG, "Finalized trip id=$draftTripId distance=${"%.1f".format(totalDistanceMeters)}m events=$eventCount gps=$gpsPointCount accel=$accelPointCount")

        sendBroadcast(Intent(ACTION_TRIP_SAVED).apply {
            putExtra(EXTRA_TRIP_ID, draftTripId)
            putExtra(EXTRA_START_TIME_MS, startTimeMs)
        })
    }

    private fun startVoskListening() {
        if (!isRunning.get()) {
            Log.w(TAG, "startVoskListening skipped: service not running")
            return
        }

        val recognizer = voskRecognizer ?: run {
            Log.w(TAG, "startVoskListening skipped: voskRecognizer null")
            return
        }
        Log.i(TAG, "startVoskListening called, recognizer=$recognizer")
        isListening = true
        broadcastStatus()

        recognizer.startListening(object : VoskSpeechRecognizer.Callback {
            override fun onReady() {
                Log.i(TAG, "Vosk listening started")
                isListening = true
                broadcastStatus()
            }

            override fun onResult(text: String, confidence: Float) {
                Log.i(TAG, "Vosk result callback: '$text' confidence=$confidence")
                broadcastHeardText(text, isPartial = false)

                if (text.isEmpty()) {
                    Log.w(TAG, "Vosk result was empty")
                    return
                }

                // Reject low-confidence recognitions. With a grammar-constrained
                // recognizer, random speech is often forced into the closest grammar
                // phrase, but the confidence score remains low.
                if (confidence < causeConfig.confidenceThreshold) {
                    Log.d(TAG, "Recognition confidence $confidence below threshold ${causeConfig.confidenceThreshold}, ignoring: '$text'")
                    return
                }

                // Try exact grammar phrase mapping first (fast and deterministic).
                val exactCause = causeConfig.phraseToCauseMap[text.lowercase()]
                if (exactCause != null) {
                    Log.i(TAG, "Exact phrase mapped to cause: $exactCause")
                    recordCauseEvent(exactCause)
                    return
                }

                // Fall back to fuzzy matching for partial/noise distortions.
                val match = fuzzyMatcher.findBestMatch(text)
                if (match != null) {
                    Log.i(TAG, "Fuzzy matched cause: ${match.causeCode} (via '${match.matchedWord}', score=${match.score})")
                    recordCauseEvent(match.causeCode)
                } else {
                    Log.d(TAG, "No cause matched for: '$text'")
                }
            }

            override fun onPartialResult(text: String) {
                if (text.isNotEmpty()) {
                    Log.d(TAG, "Vosk partial callback: '$text'")
                    broadcastHeardText(text, isPartial = true)
                }
            }

            override fun onError(error: String) {
                Log.e(TAG, "Vosk error callback: $error")
                isListening = false
                broadcastHeardText("[error: $error]", isPartial = false)
                broadcastStatus()

                if (isRunning.get()) {
                    Log.d(TAG, "Restarting Vosk listener after error")
                    handler.postDelayed({ startVoskListening() }, 1000)
                }
            }
        })
    }

    private fun broadcastHeardText(text: String, isPartial: Boolean) {
        Log.d(TAG, "Broadcasting heard text: '$text' (partial=$isPartial)")
        sendBroadcast(Intent(ACTION_HEARD_TEXT).apply {
            putExtra(EXTRA_HEARD_TEXT, text)
            putExtra(EXTRA_IS_PARTIAL, isPartial)
        })
    }

    private fun broadcastLatestLocation() {
        val lastPoint = lastGpsPoint
        if (lastPoint == null) {
            Log.w(TAG, "broadcastLatestLocation: no GPS points yet, nothing to broadcast")
            return
        }
        Log.i(TAG, "broadcastLatestLocation: lat=${lastPoint.lat} lon=${lastPoint.lon} speed=${lastPoint.speedKmh} gpsCount=$gpsPointCount")
        sendBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
            putExtra(EXTRA_LAT, lastPoint.lat)
            putExtra(EXTRA_LON, lastPoint.lon)
            putExtra(EXTRA_SPEED, lastPoint.speedKmh)
        })
    }

    private fun broadcastStatus(customText: String? = null) {
        val statusText = customText ?: run {
            val duration = System.currentTimeMillis() - startTimeMs
            val seconds = (duration / 1000) % 60
            val minutes = (duration / 1000 / 60) % 60
            val hours = duration / 1000 / 60 / 60
            String.format(
                "Duration: %02d:%02d:%02d | Events: %d | GPS: %s | Mic: %s",
                hours, minutes, seconds,
                eventCount,
                if (gpsLocked) "locked" else "searching",
                if (isListening) "listening" else "idle"
            )
        }
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, statusText)
        })
    }

    private fun broadcastCauseRecognized(cause: String) {
        sendBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, "cause:$cause")
        })
    }

    private fun recordCauseEvent(causeCode: String) {
        if (!isRunning.get()) return
        Log.i(TAG, "Recording cause event: $causeCode")
        val event = DelayEvent(
            timestamp = System.currentTimeMillis(),
            causeCode = causeCode
        )
        synchronized(bufferLock) {
            eventBuffer.add(event)
            eventCount++
            causeBreakdownMap[causeCode] = (causeBreakdownMap[causeCode] ?: 0) + 1
        }
        broadcastCauseRecognized(causeCode)

        if (captureEnabled) {
            Log.i(TAG, "Triggering photo capture for cause: $causeCode")
            requestPhotoCapture()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "RoadLog Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground service for RoadLog trip recording"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val duration = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000 / 60) % 60
        val hours = duration / 1000 / 60 / 60

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RoadLog — Recording")
            .setContentText(
                String.format(
                    "GPS: %s | Mic: %s | Events: %d | %02d:%02d:%02d",
                    if (gpsLocked) "active" else "searching",
                    if (isListening) "listening" else "idle",
                    eventCount,
                    hours, minutes, seconds
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
