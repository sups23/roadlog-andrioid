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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

class LoggerService : Service() {

    companion object {
        const val ACTION_START = "com.example.roadlog.START"
        const val ACTION_STOP = "com.example.roadlog.STOP"
        const val ACTION_STATUS = "com.example.roadlog.STATUS_UPDATE"
        const val ACTION_HEARD_TEXT = "com.example.roadlog.HEARD_TEXT"
        const val ACTION_LOCATION_UPDATE = "com.example.roadlog.LOCATION_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_HEARD_TEXT = "heard_text"
        const val EXTRA_IS_PARTIAL = "is_partial"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ACCURACY = "accuracy"
        const val ACTION_TRIP_SAVED = "com.example.roadlog.TRIP_SAVED"
        const val ACTION_RECORDING_STARTED = "com.example.roadlog.RECORDING_STARTED"
        const val ACTION_QUERY_STATE = "com.example.roadlog.QUERY_STATE"
        const val ACTION_STATE = "com.example.roadlog.RECORDING_STATE"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_TRIP_UUID = "trip_uuid"
        const val EXTRA_START_TIME_MS = "start_time_ms"
        const val EXTRA_DIRECTION = "direction"
        const val EXTRA_OBSERVATION_PERIOD = "observation_period"
        const val EXTRA_STUDY_DATE = "study_date"
        const val EXTRA_RECORDING_STATE = "recording_state"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_TIME_ZONE_ID = "time_zone_id"
        const val EXTRA_SENSOR_PROFILE_VERSION = "sensor_profile_version"
        const val NOTIFICATION_CHANNEL_ID = "roadlog_service_channel"
        const val NOTIFICATION_ID = 1
        const val TAG = "RoadLog"
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationSensor: Sensor? = null
    private lateinit var database: AppDatabase
    private lateinit var causeConfig: CauseConfig
    private lateinit var commandParser: CauseCommandParser

    private var voskRecognizer: VoskSpeechRecognizer? = null
    private var tripAudioRecorder: TripAudioRecorder? = null

    private val gpsBuffer = mutableListOf<GpsPoint>()
    private val accelBuffer = mutableListOf<AccelPoint>()
    private val gyroBuffer = mutableListOf<GyroPoint>()
    private val rotationBuffer = mutableListOf<RotationPoint>()
    private val eventBuffer = mutableListOf<DelayEvent>()
    private val bufferLock = Any()
    private val flushMutex = Mutex()
    private val bufferOverflowReported = AtomicBoolean(false)
    private val maxBufferedSamples = 20_000

    private var startTimeMs: Long = 0
    private var startNanoTime: Long = 0
    private var endNanoTime: Long = 0
    private var eventCount = 0
    private var gpsPointCount = 0
    private var accelPointCount = 0
    private var totalDistanceMeters = 0.0
    private var lastGpsPoint: GpsPoint? = null
    private val causeBreakdownMap = mutableMapOf<String, Int>()
    private var latestLocationProvider: String? = null
    private var isListening = false
    @Volatile private var modelReady = false

    @Volatile private var draftTripId: Long = -1
    private val draftReady = AtomicBoolean(false)
    private var draftTripUuid: String = ""
    @Volatile private var lifecycleState = RecordingState.IDLE
    @Volatile private var stopRequested = false
    private var startJob: Job? = null
    @Volatile private var writeFailureCount = 0
    @Volatile private var droppedSampleCount = 0
    private var lastWriteTimeMs = 0L
    private var configuredDirection: String? = null
    private var configuredObservationPeriod: String? = null
    private var configuredDeviceId: String? = null
    private var configuredStudyDate: String? = null
    private var audioFailureInjection = AudioFailureInjectionPoint.NONE
    private val sensorProfileVersion = "1"
    private var recoveryJob: Job? = null
    private val sensorRegistrationJobs = mutableListOf<Job>()
    private val audioPersistenceJobs = mutableListOf<Job>()
    @Volatile private var audioFrameFailureCount = 0
    @Volatile private var modelPreparationError: String? = null
    @Volatile private var causeConfigError: String? = null
    @Volatile private var gpsInterruptionDetected = false
    @Volatile private var sensorInterruptionDetected = false
    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var flushJob: Job? = null

    private val gpsCallback = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLocationProvider = location.provider
            val callbackTimeMs = System.currentTimeMillis()
            val sourceEpochTimeMs = location.time.takeIf { it > 0L }
            val speedValid = location.hasSpeed()
            val point = GpsPoint(
                timestampMs = sourceEpochTimeMs ?: callbackTimeMs,
                lat = location.latitude,
                lon = location.longitude,
                speedKmh = if (speedValid) location.speed * 3.6f else null,
                sourceElapsedRealtimeNanos = location.elapsedRealtimeNanos.takeIf { it > 0L },
                sourceEpochTimeMs = sourceEpochTimeMs,
                callbackTimeMs = callbackTimeMs,
                provider = location.provider,
                horizontalAccuracyMeters = location.accuracy,
                speedValid = speedValid,
                speedAccuracyMps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                    location.speedAccuracyMetersPerSecond
                } else {
                    null
                },
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
                    location.bearingAccuracyDegrees
                } else {
                    null
                },
                altitudeMeters = if (location.hasAltitude()) location.altitude else null
            )
            if (!appendBuffered(gpsBuffer, point)) return
            synchronized(bufferLock) { gpsPointCount++ }
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
            if (latestLocationProvider == provider) latestLocationProvider = null
            if (provider == LocationManager.GPS_PROVIDER) {
                gpsInterruptionDetected = true
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
            val point = AccelPoint(
                timestampNano = event.timestamp,
                x = x,
                y = y,
                z = z,
                callbackTimeMs = System.currentTimeMillis(),
                accuracy = event.accuracy,
                sensorType = event.sensor.type
            )
            if (appendBuffered(accelBuffer, point)) {
                synchronized(bufferLock) {
                    accelPointCount++
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            appendBuffered(
                gyroBuffer,
                GyroPoint(
                    timestampNano = event.timestamp,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                    callbackTimeMs = System.currentTimeMillis(),
                    accuracy = event.accuracy,
                    sensorType = event.sensor.type
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val w = if (event.values.size >= 4) event.values[3] else computeRotationScalar(x, y, z)
            appendBuffered(
                rotationBuffer,
                RotationPoint(
                    timestampNano = event.timestamp,
                    x = x,
                    y = y,
                    z = z,
                    w = w,
                    callbackTimeMs = System.currentTimeMillis(),
                    accuracy = event.accuracy,
                    sensorType = event.sensor.type
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun computeRotationScalar(x: Float, y: Float, z: Float): Float {
        val sumSq = x * x + y * y + z * z
        return if (sumSq < 1f) kotlin.math.sqrt(1f - sumSq) else 0f
    }

    private fun <T> appendBuffered(buffer: MutableList<T>, value: T): Boolean {
        var overflowed = false
        synchronized(bufferLock) {
            val bufferedCount = gpsBuffer.size + accelBuffer.size + gyroBuffer.size +
                rotationBuffer.size + eventBuffer.size
            if (bufferedCount >= maxBufferedSamples) {
                droppedSampleCount++
                overflowed = true
            } else {
                buffer.add(value)
            }
        }
        if (overflowed && bufferOverflowReported.compareAndSet(false, true)) {
            handler.post {
                broadcastStatus("Buffer limit reached; recording is stopping with dropped samples")
                if (lifecycleState == RecordingState.RECORDING) stopRecording()
            }
        }
        return !overflowed
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
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        database = AppDatabase.getDatabase(this)
        configuredDeviceId = DeviceIdentity.get(this)
        reconcilePendingAudio()
        causeConfig = try {
            val loaded = CauseConfigLoader.load(this)
            require(loaded.causes.isNotEmpty()) { "cause configuration contains no causes" }
            causeConfigError = null
            loaded
        } catch (e: Exception) {
            causeConfigError = e.message ?: "cause configuration is unavailable"
            Log.e(TAG, "Failed to load cause config", e)
            CauseConfig(
                confidenceThreshold = 0.6f,
                fuzzyThreshold = 0.85,
                minWordLength = 3,
                activationPhrases = listOf("log"),
                causes = emptyList()
            )
        }
        commandParser = CauseCommandParser(causeConfig)

        recoveryJob = recoverAbandonedDrafts()
        prepareVoskModel()
    }

    private fun recoverAbandonedDrafts(): Job {
        return serviceScope.launch {
            try {
                val abandoned = database.tripDao().getAbandonedTrips()
                if (abandoned.isEmpty()) return@launch
                Log.i(TAG, "Preserving ${abandoned.size} interrupted draft trips")
                for (trip in abandoned) {
                    val endTimeMs = System.currentTimeMillis()
                    database.tripDao().markTripInterrupted(
                        tripId = trip.id,
                        status = TripStatus.RECOVERABLE,
                        endTimeMs = endTimeMs,
                        endNanoTime = SystemClock.elapsedRealtimeNanos(),
                        reason = "PROCESS_OR_SERVICE_INTERRUPTION",
                        lastWriteTimeMs = trip.lastWriteTimeMs,
                        writeFailureCount = trip.writeFailureCount,
                        droppedSampleCount = trip.droppedSampleCount,
                        gpsInterruption = trip.gpsInterruption,
                        sensorInterruption = trip.sensorInterruption
                    )
                    database.tripDao().getAudioForTrip(trip.id)
                        .forEach { persistAudioFailureEvidence(it) }
                    persistTripQuality(trip.id, endTimeMs, trip.startTimeMs)
                    Log.d(TAG, "Preserved interrupted trip ${trip.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to preserve interrupted drafts", e)
            }
        }
    }

    private fun reconcilePendingAudio() {
        serviceScope.launch {
            try {
                database.tripDao().getAllAudioForExport()
                    .forEach { audio -> persistAudioFailureEvidence(audio) }
            } catch (error: Exception) {
                Log.e(TAG, "Could not reconcile pending audio segments", error)
            }
        }
    }

    private fun prepareVoskModel() {
        Log.i(TAG, "Preparing Vosk offline model...")
        modelPreparationError = null
        voskRecognizer = VoskSpeechRecognizer(this, GrammarBuilder.buildGrammarJson(causeConfig))
        voskRecognizer?.prepare(
            onReady = {
                Log.i(TAG, "Vosk model ready")
                modelReady = true
                modelPreparationError = null
                broadcastStatus("Vosk model ready. Waiting for START...")
                if (lifecycleState == RecordingState.RECORDING && !isListening) {
                    startVoskListening()
                }
            },
            onError = { error ->
                Log.e(TAG, "Vosk model error: $error")
                modelReady = false
                modelPreparationError = error
                broadcastStatus("Vosk model error: $error")
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action} state=$lifecycleState modelReady=$modelReady")
        when (intent?.action) {
            ACTION_START -> {
                configuredDirection = intent.getStringExtra(EXTRA_DIRECTION)
                configuredObservationPeriod = intent.getStringExtra(EXTRA_OBSERVATION_PERIOD)
                configuredStudyDate = intent.getStringExtra(EXTRA_STUDY_DATE)
                    ?: ResearchClock.studyDateLocal(System.currentTimeMillis())
                val validation = TripStartValidator.validate(
                    TripStartConfiguration(
                        direction = configuredDirection,
                        observationPeriod = configuredObservationPeriod
                    )
                )
                if (validation.isNotEmpty()) {
                    broadcastStatus("Cannot start: ${validation.joinToString(", ")}")
                } else {
                    audioFailureInjection = AudioFailureInjectionConfig.consumeNext(this)
                    startRecording()
                }
            }
            ACTION_STOP -> stopRecording()
            ACTION_QUERY_STATE -> {
                sendRecordingState()
                if (lifecycleState == RecordingState.IDLE) {
                    handler.post { stopSelf(startId) }
                }
            }
        }
        sendRecordingState()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val wasActive = isRunning.get() || lifecycleState == RecordingState.PREPARING
        if (wasActive) {
            interruptForServiceDestroy()
        } else {
            voskRecognizer?.destroy()
        }
        super.onDestroy()
        // Do not cancel serviceScope: a final Room transaction may still be in flight.
        // serviceScope is not canceled here so the background finalization coroutine can finish.
    }

    private fun interruptForServiceDestroy() {
        val tripId: Long
        val interruptionTimeMs = System.currentTimeMillis()
        val interruptionNanoTime = SystemClock.elapsedRealtimeNanos()
        synchronized(this) {
            stopRequested = true
            if (lifecycleState == RecordingState.PREPARING) {
                startJob?.cancel()
            }
            lifecycleState = RecordingState.FINALIZING
            isRunning.set(false)
            tripId = draftTripId
        }
        handler.removeCallbacks(statusUpdateRunnable)
        handler.removeCallbacks(locationUpdateRunnable)
        flushJob?.cancel()
        flushJob = null
        try { locationManager.removeUpdates(gpsCallback) } catch (_: Exception) {}
        sensorManager.unregisterListener(accelListener)
        sensorManager.unregisterListener(gyroListener)
        sensorManager.unregisterListener(rotationListener)
        voskRecognizer?.stop()
        serviceScope.launch {
            voskRecognizer?.stopAndWait()
            tripAudioRecorder?.stop(interruptionTimeMs, interruptionNanoTime, "SERVICE_DESTROYED")
            awaitAudioPersistence()
            awaitSensorRegistration()
            voskRecognizer?.destroy()
            if (tripId >= 0L) {
                repeat(3) { attempt ->
                    if (!hasPendingBuffers()) return@repeat
                    if (flushBuffersToDatabase()) return@repeat
                    if (attempt < 2) delay((attempt + 1) * 500L)
                }
                try {
                    database.tripDao().markTripInterrupted(
                        tripId = tripId,
                        status = TripStatus.RECOVERABLE,
                        endTimeMs = interruptionTimeMs,
                        endNanoTime = interruptionNanoTime,
                        reason = "SERVICE_DESTROYED",
                        lastWriteTimeMs = lastWriteTimeMs,
                        coverageEndLatitude = lastGpsPoint?.lat,
                        coverageEndLongitude = lastGpsPoint?.lon,
                        gpsInterruption = gpsInterruptionDetected,
                        sensorInterruption = sensorInterruptionDetected
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Could not preserve service-destroyed trip", e)
                }
                try {
                    persistTripQuality(tripId, interruptionTimeMs)
                } catch (qualityError: Exception) {
                    Log.e(TAG, "Could not persist quality for interrupted trip", qualityError)
                }
            }
            finishServiceWithoutCompletion()
        }
    }

    private fun startRecording() {
        val configError = causeConfigError
        if (configError != null || causeConfig.causes.isEmpty()) {
            broadcastStatus("Cannot start: cause configuration unavailable")
            Log.e(TAG, "Recording blocked because cause configuration is unavailable: $configError")
            return
        }
        synchronized(this) {
            if (lifecycleState != RecordingState.IDLE) {
                Log.w(TAG, "Ignoring START while state=$lifecycleState")
                return
            }
            lifecycleState = RecordingState.PREPARING
            stopRequested = false
        }

        Log.i(TAG, "startRecording() preparing durable draft")

        startTimeMs = System.currentTimeMillis()
        startNanoTime = SystemClock.elapsedRealtimeNanos()
        eventCount = 0
        gpsPointCount = 0
        accelPointCount = 0
        totalDistanceMeters = 0.0
        lastGpsPoint = null
        endNanoTime = 0
        latestLocationProvider = null
        writeFailureCount = 0
        droppedSampleCount = 0
        lastWriteTimeMs = 0L
        bufferOverflowReported.set(false)
        gpsInterruptionDetected = false
        sensorInterruptionDetected = false
        draftTripId = -1L
        draftTripUuid = ""
        draftReady.set(false)
        tripAudioRecorder = null

        causeBreakdownMap.clear()
        synchronized(bufferLock) {
            gpsBuffer.clear()
            accelBuffer.clear()
            gyroBuffer.clear()
            rotationBuffer.clear()
            eventBuffer.clear()
        }
        latestLocationProvider = null
        audioFrameFailureCount = 0

        if (!wakeLock.isHeld) wakeLock.acquire(60 * 60 * 1000L)
        Log.i(TAG, "WakeLock acquired")

        // Foreground promotion happens before model loading or database work.
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.i(TAG, "Foreground service started")

        startJob = serviceScope.launch {
            try {
                recoveryJob?.join()
                val createdTripId = createDraftTrip()
                draftTripId = createdTripId
                draftReady.set(true)
                persistSensorMetadata(createdTripId)
                if (stopRequested) {
                    database.tripDao().markTripInterrupted(
                        tripId = createdTripId,
                        status = TripStatus.ABORTED,
                        endTimeMs = System.currentTimeMillis(),
                        endNanoTime = SystemClock.elapsedRealtimeNanos(),
                        reason = "STOP_DURING_PREPARATION",
                        lastWriteTimeMs = System.currentTimeMillis()
                    )
                    persistTripQuality(createdTripId, System.currentTimeMillis())
                    finishServiceWithoutCompletion()
                    return@launch
                }

                // The same AudioRecord feeds Vosk and the archival encoder. Do not
                // announce an active trip until that shared source is available.
                while (!modelReady && modelPreparationError == null && !stopRequested && isActive) {
                    delay(250L)
                }
                check(modelReady || stopRequested) {
                    "offline speech/audio model is not ready: ${modelPreparationError ?: "unknown error"}"
                }
                if (stopRequested) {
                    database.tripDao().markTripInterrupted(
                        tripId = createdTripId,
                        status = TripStatus.ABORTED,
                        endTimeMs = System.currentTimeMillis(),
                        endNanoTime = SystemClock.elapsedRealtimeNanos(),
                        reason = "STOP_DURING_PREPARATION",
                        lastWriteTimeMs = System.currentTimeMillis()
                    )
                    persistTripQuality(createdTripId, System.currentTimeMillis())
                    finishServiceWithoutCompletion()
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (stopRequested) return@withContext
                    lifecycleState = RecordingState.RECORDING
                    isRunning.set(true)
                    startProducers()
                    startPeriodicFlush()
                    broadcastRecordingStarted()
                    broadcastStatus("Recording started")
                    handler.post(statusUpdateRunnable)
                    handler.post(locationUpdateRunnable)
                }
                if (stopRequested && lifecycleState == RecordingState.PREPARING) {
                    database.tripDao().markTripInterrupted(
                        tripId = createdTripId,
                        status = TripStatus.ABORTED,
                        endTimeMs = System.currentTimeMillis(),
                        endNanoTime = SystemClock.elapsedRealtimeNanos(),
                        reason = "STOP_DURING_PREPARATION",
                        lastWriteTimeMs = lastWriteTimeMs
                    )
                    persistTripQuality(createdTripId, System.currentTimeMillis())
                    finishServiceWithoutCompletion()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create durable recording draft", e)
                if (draftTripId >= 0L) {
                    try {
                        database.tripDao().markTripInterrupted(
                            tripId = draftTripId,
                            status = TripStatus.RECOVERABLE,
                            endTimeMs = System.currentTimeMillis(),
                            endNanoTime = SystemClock.elapsedRealtimeNanos(),
                            reason = "PREPARATION_FAILURE: ${e.message ?: "unknown"}",
                            lastWriteTimeMs = lastWriteTimeMs
                        )
                        persistTripQuality(draftTripId, System.currentTimeMillis())
                    } catch (markError: Exception) {
                        Log.e(TAG, "Could not mark preparation failure recoverable", markError)
                    }
                }
                lifecycleState = RecordingState.FAILED
                broadcastStatus("Recording could not be started: ${e.message ?: "database error"}")
                finishServiceWithoutCompletion()
            }
        }
    }

    private fun startProducers() {
        var locationProviderRegistered = false
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    gpsCallback,
                    Looper.getMainLooper()
                )
                locationProviderRegistered = true
            } catch (e: SecurityException) {
                gpsInterruptionDetected = true
                Log.e(TAG, "Location permission missing for $provider", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Location provider not available: $provider", e)
            }
        }
        if (!locationProviderRegistered) {
            gpsInterruptionDetected = true
            broadcastStatus("No location provider registered")
        }

        accelerometer?.let { sensor ->
            val registered = sensorManager.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_GAME)
            if (!registered) {
                sensorInterruptionDetected = true
                broadcastStatus("Accelerometer registration failed")
            }
            enqueueSensorRegistration(sensor.type, registered)
        } ?: run {
            sensorInterruptionDetected = true
            broadcastStatus("Accelerometer unavailable")
        }
        gyroscope?.let {
            val registered = sensorManager.registerListener(gyroListener, it, SensorManager.SENSOR_DELAY_GAME)
            if (!registered) {
                sensorInterruptionDetected = true
                broadcastStatus("Gyroscope registration failed")
            }
            enqueueSensorRegistration(it.type, registered)
        } ?: run {
            sensorInterruptionDetected = true
            Log.w(TAG, "Gyroscope unavailable")
        }
        rotationSensor?.let {
            val registered = sensorManager.registerListener(rotationListener, it, SensorManager.SENSOR_DELAY_GAME)
            if (!registered) {
                sensorInterruptionDetected = true
                broadcastStatus("Orientation sensor registration failed")
            }
            enqueueSensorRegistration(it.type, registered)
        } ?: run {
            sensorInterruptionDetected = true
            Log.w(TAG, "Orientation sensor unavailable")
        }

        if (modelReady) {
            startVoskListening()
        } else {
            broadcastStatus("Recording; speech model still preparing")
        }
    }

    private fun stopRecording() {
        synchronized(this) {
            when (lifecycleState) {
                RecordingState.IDLE, RecordingState.FAILED, RecordingState.ABORTED -> return
                RecordingState.PREPARING -> {
                    stopRequested = true
                    broadcastStatus("Stopping before recording started")
                    return
                }
                RecordingState.FINALIZING -> return
                RecordingState.RECORDING -> {
                    lifecycleState = RecordingState.FINALIZING
                    isRunning.set(false)
                }
            }
        }

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
        Log.i(TAG, "Vosk listener stopped")

        val endTimeMs = System.currentTimeMillis()
        endNanoTime = SystemClock.elapsedRealtimeNanos()
        val tripId = draftTripId

        serviceScope.launch {
            Log.i(TAG, "Starting final flush and finalization")
            try {
                voskRecognizer?.stopAndWait()
                tripAudioRecorder?.stop(endTimeMs, endNanoTime)
                awaitAudioPersistence()
                awaitSensorRegistration()
                var flushed = false
                repeat(3) { attempt ->
                    if (flushed) return@repeat
                    flushed = flushBuffersToDatabase()
                    if (!flushed && attempt < 2) delay((attempt + 1) * 500L)
                }
                if (!flushed || hasPendingBuffers()) {
                    error("Recording buffers were not durably written")
                }
                finalizeTripAndBroadcast(tripId, endTimeMs, endNanoTime)
            } catch (e: Exception) {
                Log.e(TAG, "Room finalization failed", e)
                if (tripId >= 0L) {
                    try {
                        database.tripDao().markTripInterrupted(
                            tripId = tripId,
                            status = TripStatus.RECOVERABLE,
                            endTimeMs = endTimeMs,
                            endNanoTime = endNanoTime,
                            reason = "FINALIZATION_FAILURE: ${e.message ?: "unknown"}",
                            lastWriteTimeMs = lastWriteTimeMs,
                            coverageEndLatitude = lastGpsPoint?.lat,
                            coverageEndLongitude = lastGpsPoint?.lon,
                            gpsInterruption = gpsInterruptionDetected,
                            sensorInterruption = sensorInterruptionDetected
                        )
                        persistTripQuality(tripId, endTimeMs)
                    } catch (markError: Exception) {
                        Log.e(TAG, "Could not mark failed trip recoverable", markError)
                    }
                }
                broadcastStatus("Trip preserved for recovery; finalization failed")
            }
            finishServiceWithoutCompletion()
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
            createdAt = startTimeMs,
            status = TripStatus.RECORDING,
            tripUuid = UUID.randomUUID().toString(),
            sessionId = ResearchStudy.SESSION_ID,
            corridorId = ResearchStudy.CORRIDOR_ID,
            direction = configuredDirection,
            observationPeriod = configuredObservationPeriod,
            validityStatus = null,
            qaStatus = TripQaStatus.UNREVIEWED,
            codebookVersion = causeConfig.version,
            appVersion = BuildConfig.VERSION_NAME,
            schemaVersion = ResearchVersions.ROOM_SCHEMA_VERSION,
            studyDateLocal = configuredStudyDate,
            timeZoneId = ResearchTime.KATHMANDU_ZONE_ID,
            sensorProfileVersion = sensorProfileVersion,
            exportFormatVersion = ResearchVersions.EXPORT_FORMAT_VERSION,
            protocolVersion = ResearchVersions.PROTOCOL_VERSION,
            causeConfigJson = causeConfig.rawJson.takeIf { it.isNotBlank() }
                ?: "{}",
            deviceId = configuredDeviceId,
            lastWriteTimeMs = startTimeMs
        )
        draftTripUuid = trip.tripUuid
        return database.tripDao().insertTrip(trip)
    }

    private suspend fun persistSensorMetadata(tripId: Long) {
        val sensors = listOf(
            sensorMetadata(tripId, accelerometer, Sensor.TYPE_ACCELEROMETER),
            sensorMetadata(tripId, gyroscope, Sensor.TYPE_GYROSCOPE),
            sensorMetadata(tripId, rotationSensor, rotationSensor?.type ?: Sensor.TYPE_GAME_ROTATION_VECTOR)
        )
        database.tripDao().upsertSensorMetadata(sensors)
    }

    private fun sensorMetadata(tripId: Long, sensor: Sensor?, sensorType: Int): SensorMetadata {
        return SensorMetadata(
            metadataId = "$tripId-$sensorType",
            tripId = tripId,
            sensorType = sensorType,
            sensorName = sensor?.name,
            vendor = sensor?.vendor,
            version = sensor?.version?.toString(),
            resolution = sensor?.resolution,
            maximumRange = sensor?.maximumRange,
            selectedProfile = "SENSOR_DELAY_GAME",
            registrationResult = if (sensor == null) "UNAVAILABLE" else "AVAILABLE",
            sensorProfileVersion = sensorProfileVersion,
            requestedPeriodUs = 20_000
        )
    }

    private fun startPeriodicFlush() {
        flushJob = serviceScope.launch {
            while (isActive) {
                delay(5_000L)
                if (lifecycleState == RecordingState.RECORDING) {
                    flushBuffersToDatabase()
                }
            }
        }
    }

    private suspend fun flushBuffersToDatabase(): Boolean = flushMutex.withLock {
        if (!draftReady.get() || draftTripId < 0) return@withLock false
        val tripId = draftTripId
        val gpsSnapshot: List<GpsPoint>
        val accelSnapshot: List<AccelPoint>
        val gyroSnapshot: List<GyroPoint>
        val rotSnapshot: List<RotationPoint>
        val eventSnapshot: List<DelayEvent>
        synchronized(bufferLock) {
            // Do not acknowledge these rows until the Room transaction commits.
            gpsSnapshot = gpsBuffer.toList()
            accelSnapshot = accelBuffer.toList()
            gyroSnapshot = gyroBuffer.toList()
            rotSnapshot = rotationBuffer.toList()
            eventSnapshot = eventBuffer.toList()
        }

        if (gpsSnapshot.isEmpty() && accelSnapshot.isEmpty() && gyroSnapshot.isEmpty() &&
            rotSnapshot.isEmpty() && eventSnapshot.isEmpty()) return@withLock true

        val rows = mutableListOf<TripData>()
        val events = mutableListOf<TripEvent>()

        gpsSnapshot.forEach { point ->
            rows.add(
                TripData(
                    tripId = tripId,
                    timestamp = point.timestampMs,
                    latitude = point.lat,
                    longitude = point.lon,
                    speedKmh = point.speedKmh,
                    eventCause = null,
                    callbackTimeMs = point.callbackTimeMs,
                    sourceElapsedRealtimeNanos = point.sourceElapsedRealtimeNanos,
                    sourceEpochTimeMs = point.sourceEpochTimeMs,
                    provider = point.provider,
                    horizontalAccuracyMeters = point.horizontalAccuracyMeters,
                    speedValid = point.speedValid,
                    speedAccuracyMps = point.speedAccuracyMps,
                    bearingDegrees = point.bearingDegrees,
                    bearingAccuracyDegrees = point.bearingAccuracyDegrees,
                    altitudeMeters = point.altitudeMeters,
                    sourceType = "LOCATION"
                )
            )
        }

        fun sensorTimestampMs(timestampNano: Long): Long {
            return TimestampCalibration.elapsedToWallTimeMs(
                ClockAnchor(startTimeMs, startNanoTime),
                timestampNano
            )
        }

        accelSnapshot.forEach { point ->
            rows.add(
                TripData(
                    tripId = tripId,
                    timestamp = sensorTimestampMs(point.timestampNano),
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    accelX = point.x,
                    accelY = point.y,
                    accelZ = point.z,
                    eventCause = null,
                    rawTimestamp = point.timestampNano,
                    sourceTimestampNanos = point.timestampNano,
                    callbackTimeMs = point.callbackTimeMs,
                    sensorType = point.sensorType,
                    sensorAccuracy = point.accuracy,
                    sourceType = "ACCELEROMETER"
                )
            )
        }

        gyroSnapshot.forEach { point ->
            rows.add(
                TripData(
                    tripId = tripId,
                    timestamp = sensorTimestampMs(point.timestampNano),
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    gyroX = point.x,
                    gyroY = point.y,
                    gyroZ = point.z,
                    eventCause = null,
                    rawTimestamp = point.timestampNano,
                    sourceTimestampNanos = point.timestampNano,
                    callbackTimeMs = point.callbackTimeMs,
                    sensorType = point.sensorType,
                    sensorAccuracy = point.accuracy,
                    sourceType = "GYROSCOPE"
                )
            )
        }

        rotSnapshot.forEach { point ->
            rows.add(
                TripData(
                    tripId = tripId,
                    timestamp = sensorTimestampMs(point.timestampNano),
                    latitude = null,
                    longitude = null,
                    speedKmh = null,
                    rotX = point.x,
                    rotY = point.y,
                    rotZ = point.z,
                    rotW = point.w,
                    eventCause = null,
                    rawTimestamp = point.timestampNano,
                    sourceTimestampNanos = point.timestampNano,
                    callbackTimeMs = point.callbackTimeMs,
                    sensorType = point.sensorType,
                    sensorAccuracy = point.accuracy,
                    sourceType = "ROTATION"
                )
            )
        }

        eventSnapshot.forEach { event ->
            rows.add(
                TripData(
                    tripId = tripId,
                    timestamp = event.timestamp,
                    latitude = event.latitude,
                    longitude = event.longitude,
                    speedKmh = event.speedKmh,
                    eventCause = event.causeCode,
                    sourceElapsedRealtimeNanos = event.elapsedRealtimeNanos,
                    sourceType = "EVENT"
                )
            )
            events.add(
                TripEvent(
                    eventId = event.eventId,
                    tripId = tripId,
                    markerTimeMs = event.timestamp,
                    markerElapsedRealtimeNanos = event.elapsedRealtimeNanos,
                    experiencedLatitude = event.latitude,
                    experiencedLongitude = event.longitude,
                    sourceLatitude = null,
                    sourceLongitude = null,
                    sourceLocationVisible = false,
                    locationAccuracyMeters = event.locationAccuracyMeters,
                    locationProvider = event.locationProvider,
                    locationFixTimeMs = event.locationFixTimeMs,
                    locationFixElapsedRealtimeNanos = event.locationFixElapsedRealtimeNanos,
                    locationFixAgeMs = event.locationFixTimeMs?.let { event.timestamp - it },
                    speedValid = event.speedValid,
                    speedKmh = event.speedKmh,
                    provisionalCauseCode = event.causeCode,
                    primaryCauseCode = event.causeCode,
                    provenance = event.provenance,
                    transcript = event.transcript,
                    recognitionConfidence = event.recognitionConfidence,
                    codebookVersion = causeConfig.version
                )
            )
        }

        try {
            database.tripDao().insertBatch(rows, events)
            synchronized(bufferLock) {
                gpsBuffer.removePrefix(gpsSnapshot.size)
                accelBuffer.removePrefix(accelSnapshot.size)
                gyroBuffer.removePrefix(gyroSnapshot.size)
                rotationBuffer.removePrefix(rotSnapshot.size)
                eventBuffer.removePrefix(eventSnapshot.size)
            }
            lastWriteTimeMs = System.currentTimeMillis()
            try {
                database.tripDao().updateTripHealth(
                    tripId = tripId,
                    lastWriteTimeMs = lastWriteTimeMs,
                    writeFailureCount = writeFailureCount,
                    droppedSampleCount = droppedSampleCount
                )
            } catch (healthError: Exception) {
                Log.e(TAG, "Batch committed but health heartbeat could not be updated", healthError)
            }
            Log.d(TAG, "Flushed ${rows.size} rows to draft trip $tripId")
            true
        } catch (e: Exception) {
            incrementWriteFailure()
            Log.e(TAG, "Room batch write failed; retaining ${rows.size} rows for retry", e)
            try {
                database.tripDao().updateTripHealth(
                    tripId = tripId,
                    lastWriteTimeMs = lastWriteTimeMs,
                    writeFailureCount = writeFailureCount,
                    droppedSampleCount = droppedSampleCount
                )
            } catch (healthError: Exception) {
                Log.e(TAG, "Could not persist write failure count", healthError)
            }
            broadcastStatus("Storage write failed; samples retained for retry")
            false
        }
    }

    private fun <T> MutableList<T>.removePrefix(count: Int) {
        if (count <= 0) return
        subList(0, minOf(count, size)).clear()
    }

    private fun incrementWriteFailure() {
        synchronized(this) {
            writeFailureCount++
        }
    }

    private fun hasPendingBuffers(): Boolean = synchronized(bufferLock) {
        gpsBuffer.isNotEmpty() || accelBuffer.isNotEmpty() || gyroBuffer.isNotEmpty() ||
            rotationBuffer.isNotEmpty() || eventBuffer.isNotEmpty()
    }

    private suspend fun finalizeTripAndBroadcast(tripId: Long, endTimeMs: Long, endNanoTime: Long) {
        if (tripId < 0) return

        val breakdown = JSONObject().apply {
            synchronized(bufferLock) {
                causeBreakdownMap.forEach { (cause, count) -> put(cause, count) }
            }
        }.toString()

        persistTripQuality(
            tripId = tripId,
            endTimeMs = endTimeMs,
            tripStatusOverride = TripStatus.COMPLETED
        )
        database.tripDao().finalizeTrip(
            tripId = tripId,
            endTimeMs = endTimeMs,
            endNanoTime = endNanoTime,
            distanceMeters = totalDistanceMeters,
            eventCount = eventCount,
            gpsPointCount = gpsPointCount,
            accelPointCount = accelPointCount,
            causeBreakdown = breakdown,
            createdAt = System.currentTimeMillis()
        )

        Log.i(TAG, "Finalized trip id=$tripId distance=${"%.1f".format(totalDistanceMeters)}m events=$eventCount gps=$gpsPointCount accel=$accelPointCount")

        sendAppBroadcast(Intent(ACTION_TRIP_SAVED).apply {
            putExtra(EXTRA_TRIP_ID, tripId)
            putExtra(EXTRA_TRIP_UUID, draftTripUuid)
            putExtra(EXTRA_START_TIME_MS, startTimeMs)
        })
    }

    private suspend fun persistTripQuality(
        tripId: Long,
        endTimeMs: Long,
        startTimeMsOverride: Long? = null,
        tripStatusOverride: Int? = null
    ) {
        val trip = database.tripDao().getTripById(tripId)
        val qualityTripStatus = tripStatusOverride ?: trip?.status
        val qualityStartTimeMs = trip?.startTimeMs ?: startTimeMsOverride ?: startTimeMs
        val qualityEndTimeMs = maxOf(endTimeMs, trip?.endTimeMs ?: endTimeMs)
        val gps = database.tripDao().getGpsForTrip(tripId, qualityStartTimeMs, qualityEndTimeMs)
        val accel = database.tripDao().getAccelForTrip(tripId, qualityStartTimeMs, qualityEndTimeMs)
        val gyro = database.tripDao().getGyroForTrip(tripId, qualityStartTimeMs, qualityEndTimeMs)
        val rotation = database.tripDao().getRotationForTrip(tripId, qualityStartTimeMs, qualityEndTimeMs)
        val voiceEventCount = database.tripDao().countVoiceEventsForTrip(tripId)
        val photoCount = database.tripDao().countPhotosForTrip(tripId)
        val audioSegments = database.tripDao().getAudioForTrip(tripId)
            .map { persistAudioFailureEvidence(it) }
        val audioQuality = AudioEvidence.summarize(audioSegments)
        val audioSegmentCount = audioSegments.size
        val staleEventCount = database.tripDao().countStaleEventsForTrip(
            tripId,
            ResearchQualityThresholds.EVENT_LOCATION_STALE_WARNING_MS
        )
        if (gps.isEmpty()) gpsInterruptionDetected = true
        if (accel.isEmpty() || gyro.isEmpty() || rotation.isEmpty()) sensorInterruptionDetected = true
        val gpsStream = QualityMetrics.stream(gps.map { it.timestamp }, qualityStartTimeMs, qualityEndTimeMs, expectedFrequencyHz = 1.0)
        val accelStream = QualityMetrics.stream(accel.map { it.timestamp }, qualityStartTimeMs, qualityEndTimeMs, expectedFrequencyHz = 50.0)
        val gyroStream = QualityMetrics.stream(gyro.map { it.timestamp }, qualityStartTimeMs, qualityEndTimeMs, expectedFrequencyHz = 50.0)
        val rotationStream = QualityMetrics.stream(rotation.map { it.timestamp }, qualityStartTimeMs, qualityEndTimeMs, expectedFrequencyHz = 50.0)
        val gpsQuality = QualityMetrics.gps(gps)
        val warnings = JSONArray().apply {
            if (qualityTripStatus != null && qualityTripStatus != TripStatus.COMPLETED) {
                put("trip is recoverable/interrupted and must not be treated as a completed valid trip")
                trip?.interruptionReason?.let { put("trip interruption reason: $it") }
            }
            if (writeFailureCount > 0) put("$writeFailureCount storage write failure(s)")
            if (droppedSampleCount > 0) put("$droppedSampleCount samples dropped after buffer limit")
            if (gps.isEmpty()) put("no GPS samples")
            if (accel.isEmpty()) put("no accelerometer samples")
            if (gyro.isEmpty()) put("no gyroscope samples")
            if (rotation.isEmpty()) put("no orientation samples")
            if (gpsInterruptionDetected) put("GPS stream interruption or unavailable provider")
            if (sensorInterruptionDetected) put("one or more sensor streams interrupted or unavailable")
            if (staleEventCount > 0) put("$staleEventCount event(s) used a GPS fix older than ${ResearchQualityThresholds.EVENT_LOCATION_STALE_WARNING_MS} ms")
            if (audioFrameFailureCount > 0) put("$audioFrameFailureCount archival audio frame callback failure(s)")
            audioQuality.warningMessages.forEach(::put)
        }
        database.tripDao().upsertTripQuality(
            TripQuality(
                tripId = tripId,
                gpsSampleCount = gps.size,
                gpsAvailabilityPercent = gpsStream.observedCoveragePercent,
                medianGpsAccuracyMeters = gpsQuality.medianAccuracyMeters,
                accelSampleCount = accel.size,
                accelEffectiveHz = accelStream.effectiveFrequencyHz,
                gyroSampleCount = gyro.size,
                gyroEffectiveHz = gyroStream.effectiveFrequencyHz,
                rotationSampleCount = rotation.size,
                rotationEffectiveHz = rotationStream.effectiveFrequencyHz,
                medianIntervalMs = gpsStream.medianIntervalMs,
                p05IntervalMs = gpsStream.p05IntervalMs,
                p95IntervalMs = gpsStream.p95IntervalMs,
                longestGapMs = listOfNotNull(
                    gpsStream.longestGapMs,
                    accelStream.longestGapMs,
                    gyroStream.longestGapMs,
                    rotationStream.longestGapMs
                ).maxOrNull(),
                accelMedianIntervalMs = accelStream.medianIntervalMs,
                accelLongestGapMs = accelStream.longestGapMs,
                gyroMedianIntervalMs = gyroStream.medianIntervalMs,
                gyroLongestGapMs = gyroStream.longestGapMs,
                rotationMedianIntervalMs = rotationStream.medianIntervalMs,
                rotationLongestGapMs = rotationStream.longestGapMs,
                gpsLongestGapMs = gpsStream.longestGapMs,
                timeToFirstGpsFixMs = gps.firstOrNull()?.timestamp?.minus(qualityStartTimeMs),
                voiceEventCount = voiceEventCount,
                photoCount = photoCount,
                audioSegmentCount = audioSegmentCount,
                gpsProviderJson = JSONObject().apply {
                    gpsQuality.providerCounts.forEach { (provider, count) -> put(provider, count) }
                }.toString(),
                gpsBelowFivePercent = gpsQuality.belowFiveMetersPercent,
                gpsBelowTenPercent = gpsQuality.belowTenMetersPercent,
                gpsAbovePoorQualityPercent = gpsQuality.abovePoorQualityPercent,
                invalidSpeedCount = gpsQuality.invalidSpeedCount,
                unavailableSpeedCount = gpsQuality.unavailableSpeedCount,
                duplicateTimestampCount = gpsStream.duplicateTimestampCount + accelStream.duplicateTimestampCount + gyroStream.duplicateTimestampCount + rotationStream.duplicateTimestampCount,
                nonMonotonicTimestampCount = gpsStream.nonMonotonicTimestampCount + accelStream.nonMonotonicTimestampCount + gyroStream.nonMonotonicTimestampCount + rotationStream.nonMonotonicTimestampCount,
                interruptionCount = (if (gpsInterruptionDetected || sensorInterruptionDetected) 1 else 0) +
                    audioQuality.interruptionCount,
                storageFailureCount = writeFailureCount,
                droppedSampleCount = droppedSampleCount,
                warningsJson = warnings.toString(),
                completeness = when {
                    qualityTripStatus != null && qualityTripStatus != TripStatus.COMPLETED ||
                        audioQuality.failureCount > 0 -> "INCOMPLETE"
                    warnings.length() == 0 -> "COMPLETE"
                    else -> "COMPLETE_WITH_WARNINGS"
                }
            )
        )
    }

    private fun broadcastRecordingStarted() {
        sendAppBroadcast(Intent(ACTION_RECORDING_STARTED).apply {
            putExtra(EXTRA_TRIP_ID, draftTripId)
            putExtra(EXTRA_TRIP_UUID, draftTripUuid)
            putExtra(EXTRA_START_TIME_MS, startTimeMs)
            putExtra(EXTRA_DIRECTION, configuredDirection)
            putExtra(EXTRA_OBSERVATION_PERIOD, configuredObservationPeriod)
            putExtra(EXTRA_STUDY_DATE, configuredStudyDate)
        })
    }

    private fun sendRecordingState() {
        sendAppBroadcast(Intent(ACTION_STATE).apply {
            putExtra(EXTRA_ACTIVE, lifecycleState != RecordingState.IDLE)
            putExtra(EXTRA_RECORDING_STATE, lifecycleState.name)
            putExtra(EXTRA_TRIP_ID, draftTripId)
            putExtra(EXTRA_TRIP_UUID, draftTripUuid)
            putExtra(EXTRA_START_TIME_MS, startTimeMs)
            putExtra(EXTRA_DIRECTION, configuredDirection)
            putExtra(EXTRA_OBSERVATION_PERIOD, configuredObservationPeriod)
            putExtra(EXTRA_STUDY_DATE, configuredStudyDate)
            putExtra(EXTRA_TIME_ZONE_ID, ResearchTime.KATHMANDU_ZONE_ID)
        })
    }

    private fun finishServiceWithoutCompletion() {
        synchronized(this) {
            isRunning.set(false)
            lifecycleState = RecordingState.IDLE
            stopRequested = false
        }
        sendRecordingState()
        handler.removeCallbacks(statusUpdateRunnable)
        handler.removeCallbacks(locationUpdateRunnable)
        flushJob?.cancel()
        flushJob = null
        draftReady.set(false)
        startJob = null
        if (wakeLock.isHeld) {
            wakeLock.release()
            Log.i(TAG, "WakeLock released")
        }
        handler.post {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        draftTripId = -1L
        draftTripUuid = ""
        Log.i(TAG, "Service stopped")
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
        val audioTripId = draftTripId
        val audioRecorder = tripAudioRecorder ?: TripAudioRecorder(
            directory = File(filesDir, "audio"),
            onSegmentStarted = { segment ->
                enqueueAudioPersistence {
                    database.tripDao().insertAudio(
                        TripAudio(
                            audioId = segment.audioId,
                            tripId = audioTripId,
                            segmentSequence = segment.segmentSequence,
                            startTimeMs = segment.startTimeMs,
                            startElapsedRealtimeNanos = segment.startElapsedRealtimeNanos,
                            filePath = segment.file.absolutePath,
                            status = TripAudioStatus.PENDING
                        )
                    )
                }
            },
            onSegmentCompleted = { segment, endTimeMs, endElapsedNanos, status, sha256, reason ->
                enqueueAudioPersistence {
                    val updated = database.tripDao().completeAudio(
                        audioId = segment.audioId,
                        endTimeMs = endTimeMs,
                        endElapsedRealtimeNanos = endElapsedNanos,
                        fileSizeBytes = segment.file.length().takeIf { it > 0L },
                        sha256 = sha256,
                        status = status,
                        interruptionReason = reason
                    )
                    if (updated == 0) {
                        database.tripDao().insertAudio(
                            TripAudio(
                                audioId = segment.audioId,
                                tripId = audioTripId,
                                segmentSequence = segment.segmentSequence,
                                startTimeMs = segment.startTimeMs,
                                endTimeMs = endTimeMs,
                                startElapsedRealtimeNanos = segment.startElapsedRealtimeNanos,
                                endElapsedRealtimeNanos = endElapsedNanos,
                                filePath = segment.file.absolutePath,
                                fileSizeBytes = segment.file.length().takeIf { it > 0L },
                                sha256 = sha256,
                                status = status,
                                interruptionReason = reason
                            )
                        )
                    }
                    val persisted = database.tripDao().getAudioById(segment.audioId)
                    if (persisted != null) {
                        persistAudioFailureEvidence(persisted)
                    }
                }
            },
            onFailure = { reason ->
                audioFrameFailureCount++
                Log.e(TAG, "Archival audio failure: $reason")
            },
            failureInjection = audioFailureInjection
        ).also {
            it.start(startTimeMs, startNanoTime)
            tripAudioRecorder = it
        }
        isListening = true
        broadcastStatus()

        val archivalAudioListener = object : VoskSpeechRecognizer.AudioFrameListener {
            override fun onAudioFrame(samples: ShortArray, length: Int, elapsedRealtimeNanos: Long) {
                try {
                    audioRecorder.onAudioFrame(samples, length, elapsedRealtimeNanos)
                } catch (error: Exception) {
                    audioFrameFailureCount++
                    Log.e(TAG, "Archival audio encoder rejected a frame", error)
                    try {
                        audioRecorder.fail(
                            endTimeMs = System.currentTimeMillis(),
                            endElapsedRealtimeNanos = elapsedRealtimeNanos,
                            reason = TripAudioFailureType.reason(
                                TripAudioFailureType.FRAME_PROCESSING,
                                error.message ?: "archival audio frame callback failed"
                            )
                        )
                    } catch (failureError: Exception) {
                        Log.e(TAG, "Could not persist archival frame failure", failureError)
                    }
                }
            }
        }

        recognizer.startListening(object : VoskSpeechRecognizer.Callback {
            override fun onReady() {
                Log.i(TAG, "Vosk listening started")
                isListening = true
                broadcastStatus()
            }

            override fun onResult(text: String, confidence: Float) {
                Log.i(TAG, "Vosk result callback confidence=$confidence")
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

                val parsed = commandParser.parse(text)
                val cause = parsed.causeCode
                if (cause == null) {
                    Log.d(TAG, "Rejected voice command '${parsed.rejection}': '$text'")
                    return
                }
                Log.i(TAG, "Voice command mapped to canonical cause: $cause")
                recordCauseEvent(cause, text, confidence)
            }

            override fun onPartialResult(text: String) {
                if (text.isNotEmpty()) {
                    Log.d(TAG, "Vosk partial callback received")
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
        }, archivalAudioListener)
    }

    private suspend fun persistAudioFailureEvidence(audio: TripAudio): TripAudio {
        var current = audio
        val originalStatus = current.status
        var evidence = AudioEvidence.inventory(current)
        val normalizedReason = AudioEvidence.normalizedReason(evidence)
        val normalizedStatus = AudioEvidence.finalStatus(current.status, evidence.failureTypes)
        if (normalizedStatus != current.status || normalizedReason != current.interruptionReason) {
            if (database.tripDao().updateAudioStatus(
                    audioId = current.audioId,
                    status = normalizedStatus,
                    interruptionReason = normalizedReason
                ) == 1
            ) {
                current = current.copy(
                    status = normalizedStatus,
                    interruptionReason = normalizedReason
                )
                evidence = AudioEvidence.inventory(current)
            }
        }
        if (evidence.failureTypes.isNotEmpty()) {
            database.tripDao().insertAuditRevisionIfAbsent(
                AudioEvidence.auditRevision(evidence, originalStatus = originalStatus)
            )
        }
        return current
    }

    private fun enqueueAudioPersistence(operation: suspend () -> Unit) {
        synchronized(audioPersistenceJobs) {
            val previous = audioPersistenceJobs.lastOrNull()
            val job = serviceScope.launch {
                previous?.join()
                try {
                    operation()
                } catch (error: Exception) {
                    incrementWriteFailure()
                    Log.e(TAG, "Could not persist audio segment metadata", error)
                }
            }
            audioPersistenceJobs += job
        }
    }

    private fun enqueueSensorRegistration(sensorType: Int, registered: Boolean) {
        val tripId = draftTripId
        synchronized(sensorRegistrationJobs) {
            val job = serviceScope.launch {
                try {
                    database.tripDao().updateSensorRegistration(
                        tripId,
                        sensorType,
                        if (registered) "REGISTERED" else "REGISTRATION_FAILED"
                    )
                } catch (error: Exception) {
                    incrementWriteFailure()
                    Log.e(TAG, "Could not persist sensor registration result", error)
                }
            }
            sensorRegistrationJobs += job
        }
    }

    private suspend fun awaitSensorRegistration() {
        val jobs = synchronized(sensorRegistrationJobs) {
            sensorRegistrationJobs.toList().also { sensorRegistrationJobs.clear() }
        }
        jobs.joinAll()
    }

    private suspend fun awaitAudioPersistence() {
        val jobs = synchronized(audioPersistenceJobs) {
            audioPersistenceJobs.toList().also { audioPersistenceJobs.clear() }
        }
        jobs.joinAll()
    }

    private fun broadcastHeardText(text: String, isPartial: Boolean) {
        Log.d(TAG, "Broadcasting speech result (partial=$isPartial)")
        sendAppBroadcast(Intent(ACTION_HEARD_TEXT).apply {
            putExtra(EXTRA_HEARD_TEXT, text)
            putExtra(EXTRA_IS_PARTIAL, isPartial)
        })
    }

    private fun broadcastLatestLocation() {
        val lastPoint = lastGpsPoint
        if (lastPoint == null) {
            Log.w(TAG, "broadcastLatestLocation: no GPS point yet")
            return
        }
        Log.i(TAG, "broadcastLatestLocation: provider=${lastPoint.provider} accuracy=${lastPoint.horizontalAccuracyMeters} gpsCount=$gpsPointCount")
        sendAppBroadcast(Intent(ACTION_LOCATION_UPDATE).apply {
            putExtra(EXTRA_LAT, lastPoint.lat)
            putExtra(EXTRA_LON, lastPoint.lon)
            putExtra(EXTRA_SPEED, lastPoint.speedKmh ?: 0f)
            putExtra(EXTRA_ACCURACY, lastPoint.horizontalAccuracyMeters ?: -1f)
        })
    }

    private fun broadcastStatus(customText: String? = null) {
        val statusText = customText ?: run {
            val duration = System.currentTimeMillis() - startTimeMs
            val seconds = (duration / 1000) % 60
            val minutes = (duration / 1000 / 60) % 60
            val hours = duration / 1000 / 60 / 60
            String.format(
                "State: %s | %s/%s | Duration: %02d:%02d:%02d | Events: %d | GPS: %s | Mic: %s | Writes: %d",
                lifecycleState.name,
                configuredDirection ?: "direction?",
                configuredObservationPeriod ?: "period?",
                hours, minutes, seconds,
                eventCount,
                latestLocationProvider?.uppercase() ?: "searching",
                if (isListening) "listening" else "idle",
                writeFailureCount
            )
        }
        sendAppBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, statusText)
        })
        if (lifecycleState != RecordingState.IDLE) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun broadcastCauseRecognized(cause: String) {
        sendAppBroadcast(Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, "cause:$cause")
        })
    }

    private fun recordCauseEvent(
        causeCode: String,
        transcript: String? = null,
        recognitionConfidence: Float? = null
    ) {
        if (!isRunning.get()) return
        if (!ResearchCodebook.isPrimaryCodeValid(causeCode)) {
            Log.w(TAG, "Ignoring non-canonical cause code: $causeCode")
            return
        }
        Log.i(TAG, "Recording cause event: $causeCode")
        val event = recordEvent(
            causeCode = causeCode,
            provenance = EventProvenance.VOICE_RECOGNIZED,
            transcript = transcript,
            recognitionConfidence = recognitionConfidence
        ) ?: return
        broadcastCauseRecognized(causeCode)
    }

    private fun recordEvent(
        causeCode: String,
        provenance: String,
        transcript: String? = null,
        recognitionConfidence: Float? = null
    ): DelayEvent? {
        val gps = lastGpsPoint
        val markerTimeMs = System.currentTimeMillis()
        val markerElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val fixTimeMs = gps?.sourceEpochTimeMs ?: gps?.timestampMs
        val fixAgeMs = fixTimeMs?.let { markerTimeMs - it }
        if (fixAgeMs != null && fixAgeMs > ResearchQualityThresholds.EVENT_LOCATION_STALE_WARNING_MS) {
            Log.w(TAG, "Event is using a stale location fix: ageMs=$fixAgeMs")
        }
        val event = DelayEvent(
            timestamp = markerTimeMs,
            causeCode = causeCode,
            elapsedRealtimeNanos = markerElapsedRealtimeNanos,
            latitude = gps?.lat,
            longitude = gps?.lon,
            speedKmh = gps?.speedKmh,
            locationAccuracyMeters = gps?.horizontalAccuracyMeters,
            locationProvider = gps?.provider,
            locationFixTimeMs = fixTimeMs,
            locationFixElapsedRealtimeNanos = gps?.sourceElapsedRealtimeNanos,
            speedValid = gps?.speedValid,
            provenance = provenance,
            transcript = transcript,
            recognitionConfidence = recognitionConfidence
        )
        if (!appendBuffered(eventBuffer, event)) return null
        synchronized(bufferLock) {
            eventCount++
            causeBreakdownMap[causeCode] = (causeBreakdownMap[causeCode] ?: 0) + 1
        }
        // A marker is high-value evidence. Trigger a serialized flush immediately;
        // the periodic writer remains responsible for normal sensor throughput.
        serviceScope.launch { flushBuffersToDatabase() }
        return event
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
            .setContentTitle(if (lifecycleState == RecordingState.PREPARING) "RoadLog — Preparing" else "RoadLog — Recording")
            .setContentText(
                String.format(
                    "%s | %s/%s | GPS: %s | Mic: %s | Events: %d | %02d:%02d:%02d",
                    lifecycleState.name,
                    configuredDirection ?: "direction?",
                    configuredObservationPeriod ?: "period?",
                    latestLocationProvider?.uppercase() ?: "searching",
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

    private fun sendAppBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
