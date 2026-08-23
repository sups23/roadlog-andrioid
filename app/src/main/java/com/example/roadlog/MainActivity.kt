package com.example.roadlog

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import android.os.BatteryManager
import android.os.StatFs
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.location.LocationManager
import android.hardware.SensorManager
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import android.graphics.BitmapFactory
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.security.MessageDigest
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoadLog"
    }

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    private val cameraPermission = Manifest.permission.CAMERA

    private val permissionRequestCode = 1001
    private val startupPermissionRequestCode = 1003
    private val batteryOptimizationRequestCode = 1002
    private val cameraPermissionRequestCode = 1004

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewTripsButton: Button
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var lastSpokenText: TextView
    private lateinit var causeLabels: Map<String, TextView>
    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var pathOverlay: Polyline
    private lateinit var fabRecenter: FloatingActionButton
    private lateinit var cameraCheckBox: CheckBox
    private lateinit var previewView: PreviewView
    private lateinit var takePhotoButton: Button
    private lateinit var directionSpinner: Spinner
    private lateinit var observationPeriodSpinner: Spinner
    private lateinit var studyDateText: TextView
    private lateinit var healthText: TextView
    private lateinit var causeLabelsContainer: LinearLayout

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var pendingStartAfterCameraPermission = false
    private var isRecording = false
    private var isPreparingRecording = false
    private var isFinalizingRecording = false
    private var activeTripId = -1L
    private val photoPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mapFollowUser = true
    private var lastHeardText = "—"
    private var lastMatchedCause = "—"

    private val handler = Handler(Looper.getMainLooper())
    private val pendingHighlightResets = mutableMapOf<String, Runnable>()
    private val pathPoints = mutableListOf<GeoPoint>()
    private var lastMapPoint: GeoPoint? = null
    private var lastStatsPoint: GeoPoint? = null
    private var totalDistanceMeters = 0.0
    private var eventCount = 0
    private var lastSpeedKmh = 0f

    private var pendingStatusHide: Runnable? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(LoggerService.EXTRA_STATUS) ?: return
            Log.d(TAG, "Status received: $status")
            if (status.startsWith("cause:")) {
                val cause = status.removePrefix("cause:")
                highlightCause(cause)
                lastMatchedCause = cause
                updateCauseHeardLine()
            } else {
                statusText.text = status
                if (status.contains("Vosk model", ignoreCase = true)) {
                    if (status.contains("ready", ignoreCase = true)) {
                        modelStatusText.text = "Model loaded. Start your trip now."
                    }
                } else {
                    parseEventCount(status)
                    updateStatsText(lastSpeedKmh)
                }
            }
        }
    }

    private val heardTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(LoggerService.EXTRA_HEARD_TEXT) ?: return
            val isPartial = intent.getBooleanExtra(LoggerService.EXTRA_IS_PARTIAL, false)
            Log.d(TAG, "Heard text received: '$text' (partial=$isPartial)")
            runOnUiThread {
                lastHeardText = if (isPartial) "$text (partial)" else text
                updateCauseHeardLine()
            }
        }
    }

    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lat = intent?.getDoubleExtra(LoggerService.EXTRA_LAT, 0.0) ?: return
            val lon = intent.getDoubleExtra(LoggerService.EXTRA_LON, 0.0)
            val speed = intent.getFloatExtra(LoggerService.EXTRA_SPEED, 0f)
            Log.i(TAG, "Location broadcast received: speed=$speed")
            runOnUiThread {
                lastSpeedKmh = speed
                updateMapLocation(lat, lon)
                updateStatsText(speed)
            }
        }
    }

    private val capturePhotoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val lat = intent?.getDoubleExtra(LoggerService.EXTRA_LAT, 0.0) ?: 0.0
            val lon = intent?.getDoubleExtra(LoggerService.EXTRA_LON, 0.0) ?: 0.0
            val photoTime = intent?.getLongExtra(LoggerService.EXTRA_PHOTO_TIME, System.currentTimeMillis()) ?: System.currentTimeMillis()
            val requestElapsedNanos = intent?.getLongExtra(LoggerService.EXTRA_REQUEST_ELAPSED_NANOS, 0L)?.takeIf { it > 0L }
            val tripId = intent?.getLongExtra(LoggerService.EXTRA_TRIP_ID, activeTripId) ?: activeTripId
            val captureId = intent?.getStringExtra(LoggerService.EXTRA_CAPTURE_ID)
            val eventId = intent?.getStringExtra(LoggerService.EXTRA_EVENT_ID)
            Log.i(TAG, "Capture photo request received")
            if (ContextCompat.checkSelfPermission(this@MainActivity, cameraPermission) == PackageManager.PERMISSION_GRANTED) {
                takePhoto(lat, lon, photoTime, requestElapsedNanos, tripId, captureId, eventId)
            } else {
                Log.w(TAG, "Camera permission not granted, skipping photo")
            }
        }
    }

    private val recordingStartedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            activeTripId = intent?.getLongExtra(LoggerService.EXTRA_TRIP_ID, -1L) ?: -1L
            if (activeTripId >= 0L) {
                isPreparingRecording = false
                isFinalizingRecording = false
                selectResearchValues(intent?.getStringExtra(LoggerService.EXTRA_DIRECTION), intent?.getStringExtra(LoggerService.EXTRA_OBSERVATION_PERIOD))
                statusText.text = "Recording started and saved locally"
                updateUiState(isRecording = true)
            }
        }
    }

    private val recordingStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val active = intent?.getBooleanExtra(LoggerService.EXTRA_ACTIVE, false) ?: false
            val state = intent?.getStringExtra(LoggerService.EXTRA_RECORDING_STATE)
            activeTripId = intent?.getLongExtra(LoggerService.EXTRA_TRIP_ID, -1L) ?: -1L
            if (active) {
                isRecording = true
                isPreparingRecording = state == RecordingState.PREPARING.name
                isFinalizingRecording = state == RecordingState.FINALIZING.name
                selectResearchValues(intent?.getStringExtra(LoggerService.EXTRA_DIRECTION), intent?.getStringExtra(LoggerService.EXTRA_OBSERVATION_PERIOD))
                updateUiState(isRecording = true)
            } else if (state == RecordingState.IDLE.name || state == null) {
                isPreparingRecording = false
                isFinalizingRecording = false
                activeTripId = -1L
                updateUiState(isRecording = false)
            }
        }
    }

    private val tripSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tripId = intent?.getLongExtra(LoggerService.EXTRA_TRIP_ID, -1L) ?: return
            if (tripId < 0) return
            Log.i(TAG, "Trip saved id=$tripId; local database is authoritative")
            if (activeTripId == tripId) {
                activeTripId = -1L
                isPreparingRecording = false
                isFinalizingRecording = false
                isRecording = false
                updateUiState(isRecording = false)
                statusText.text = "Trip saved locally"
                statusText.visibility = View.VISIBLE
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure osmdroid BEFORE setContentView/inflating MapView.
        MapTileConfiguration.initialize(this)
        Log.d(TAG, "osmdroid userAgent=${Configuration.getInstance().userAgentValue} cache=${Configuration.getInstance().osmdroidTileCache}")

        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        viewTripsButton = findViewById(R.id.viewTripsButton)
        statusText = findViewById(R.id.statusText)
        modelStatusText = findViewById(R.id.modelStatusText)
        lastSpokenText = findViewById(R.id.lastSpokenText)
        fabRecenter = findViewById(R.id.fabRecenter)
        cameraCheckBox = findViewById(R.id.cameraCheckBox)
        previewView = findViewById(R.id.previewView)
        takePhotoButton = findViewById(R.id.takePhotoButton)
        directionSpinner = findViewById(R.id.directionSpinner)
        observationPeriodSpinner = findViewById(R.id.observationPeriodSpinner)
        studyDateText = findViewById(R.id.studyDateText)
        healthText = findViewById(R.id.healthText)
        causeLabelsContainer = findViewById(R.id.causeLabelsContainer)
        cameraExecutor = Executors.newSingleThreadExecutor()

        directionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Select direction", ResearchDirection.A_TO_B, ResearchDirection.B_TO_A)
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        observationPeriodSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Select period", "Morning peak", "Afternoon / off-peak", "Evening peak")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        studyDateText.text = "Study date: ${ResearchClock.studyDateLocal(System.currentTimeMillis())} (${ResearchTime.KATHMANDU_ZONE_ID})"
        updatePreTripHealth()

        cameraCheckBox.text = getString(R.string.camera_checkbox_label)
        cameraCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && ContextCompat.checkSelfPermission(this, cameraPermission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(cameraPermission), cameraPermissionRequestCode)
            }
            if (isRecording) {
                takePhotoButton.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
            updatePreTripHealth()
        }

        takePhotoButton.setOnClickListener { captureManualPhoto() }

        // Dynamically build cause labels from cause_config.json so the UI stays in
        // sync with the configurable grammar and mapping.
        val causeConfig = try {
            CauseConfigLoader.load(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cause config for UI", e)
            CauseConfig(
                confidenceThreshold = 0.6f,
                fuzzyThreshold = 0.85,
                minWordLength = 3,
                activationPhrases = listOf("log"),
                causes = emptyList()
            )
        }

        Log.i(TAG, "Loaded ${causeConfig.causes.size} causes for UI: ${causeConfig.causes.map { it.code }}")

        val labels = mutableMapOf<String, TextView>()
        val displayMetrics = resources.displayMetrics
        val labelHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, displayMetrics).toInt()
        val columnCount = 3

        causeConfig.causes.filterNot { it.voiceOnly }.chunked(columnCount).forEach { rowCauses ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                weightSum = columnCount.toFloat()
            }

            rowCauses.forEach { cause ->
                val label = TextView(this).apply {
                    id = View.generateViewId()
                    text = cause.shortForm
                    setTextAppearance(R.style.CauseLabel)
                    gravity = Gravity.CENTER
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.gray))
                    setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    isClickable = true
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        labelHeight,
                        1f
                    ).apply {
                        setMargins(marginPx, marginPx * 2, marginPx, 0)
                    }
                    setOnClickListener {
                        sendCauseToService(cause.code)
                        highlightCause(cause.code)
                        lastMatchedCause = cause.code
                        updateCauseHeardLine()
                    }
                }
                row.addView(label)
                labels[cause.code] = label
            }

            // Fill remaining slots in the last row with invisible placeholders so weights line up.
            repeat(columnCount - rowCauses.size) {
                val placeholder = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, labelHeight, 1f).apply {
                        setMargins(marginPx, marginPx * 2, marginPx, 0)
                    }
                }
                row.addView(placeholder)
            }

            causeLabelsContainer.addView(row)
        }
        causeLabels = labels

        startButton.setOnClickListener { onStartClicked() }
        stopButton.setOnClickListener { onStopClicked() }
        viewTripsButton.setOnClickListener {
            Log.d(TAG, "View Trips button clicked")
            startActivity(Intent(this, TripHistoryActivity::class.java))
        }
        fabRecenter.setOnClickListener {
            mapFollowUser = true
            fabRecenter.hide()
            val point = lastMapPoint ?: getLastKnownLocation()
            if (point != null) {
                mapView.controller.animateTo(point, 16.0, 500L)
            } else if (!hasAllPermissions()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissions,
                    startupPermissionRequestCode
                )
            } else {
                Toast.makeText(this, "Waiting for GPS fix", Toast.LENGTH_SHORT).show()
            }
        }

        setupMap()
        MapTileConfiguration.configureAttributionView(findViewById(R.id.mapAttribution))

        updateUiState(isRecording = false)
        statusText.visibility = View.GONE
        modelStatusText.text = ""

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter(LoggerService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            heardTextReceiver,
            IntentFilter(LoggerService.ACTION_HEARD_TEXT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            locationReceiver,
            IntentFilter(LoggerService.ACTION_LOCATION_UPDATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            capturePhotoReceiver,
            IntentFilter(LoggerService.ACTION_CAPTURE_PHOTO),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            tripSavedReceiver,
            IntentFilter(LoggerService.ACTION_TRIP_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            recordingStartedReceiver,
            IntentFilter(LoggerService.ACTION_RECORDING_STARTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        ContextCompat.registerReceiver(
            this,
            recordingStateReceiver,
            IntentFilter(LoggerService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasAllPermissions()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissions,
                    startupPermissionRequestCode
                )
            } else {
                refreshLocationOverlay()
                centerMapOnLastKnownLocation()
            }
            checkBatteryOptimization()
        } else {
            centerMapOnLastKnownLocation()
        }
    }

    private fun refreshLocationOverlay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationOverlay.enableMyLocation()
        }
    }

    private fun centerMapOnLastKnownLocation() {
        val point = getLastKnownLocation() ?: return
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(point)
        mapView.post {
            mapView.invalidate()
            mapView.controller.animateTo(point)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        unregisterReceiver(heardTextReceiver)
        unregisterReceiver(locationReceiver)
        unregisterReceiver(capturePhotoReceiver)
        unregisterReceiver(tripSavedReceiver)
        unregisterReceiver(recordingStartedReceiver)
        unregisterReceiver(recordingStateReceiver)
        pendingStatusHide?.let { handler.removeCallbacks(it) }
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }

    override fun onResume() {
        super.onResume()
        updatePreTripHealth()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onStart() {
        super.onStart()
        startService(Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_QUERY_STATE
        })
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    private fun setupMap() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(MapTileConfiguration.tileSource)
        mapView.setMultiTouchControls(true)
        mapView.setTilesScaledToDpi(true)
        mapView.setUseDataConnection(true)
        mapView.setMinZoomLevel(3.0)
        mapView.setMaxZoomLevel(19.0)
        mapView.isVerticalMapRepetitionEnabled = false
        mapView.isHorizontalMapRepetitionEnabled = true

        locationOverlay = MyLocationNewOverlay(org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(this), mapView).apply {
            enableMyLocation()
        }
        mapView.overlays.add(locationOverlay)

        pathOverlay = Polyline().apply {
            outlinePaint.color = 0xFF1976D2.toInt()
            outlinePaint.strokeWidth = 6f
        }
        mapView.overlays.add(pathOverlay)

        // Prevent ScrollView from stealing map drags; disable auto-follow on user touch.
        mapView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    mapFollowUser = false
                    fabRecenter.show()
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        // Center on last known location if available, otherwise Kathmandu fallback
        val startPoint = getLastKnownLocation() ?: GeoPoint(27.7172, 85.3240)
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(startPoint)
        mapView.post {
            mapView.invalidate()
            mapView.controller.animateTo(startPoint)
        }

        Log.d(TAG, "MapView setup complete: tileSource=${mapView.tileProvider.tileSource.name()} start=$startPoint")
    }

    private fun getLastKnownLocation(): GeoPoint? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        return location?.let {
             Log.d(TAG, "Last known location available")
            GeoPoint(it.latitude, it.longitude)
        }
    }

    private fun updateMapLocation(lat: Double, lon: Double) {
        val geoPoint = GeoPoint(lat, lon)

        // Accumulate distance using a small threshold to ignore GPS jitter.
        val statsThresholdMeters = 2.0
        lastStatsPoint?.let {
            val delta = geoPoint.distanceToAsDouble(it)
            if (delta >= statsThresholdMeters) {
                totalDistanceMeters += delta
                lastStatsPoint = geoPoint
            }
        } ?: run {
            lastStatsPoint = geoPoint
        }

        // Add to path polyline only when moved enough to keep it clean.
        val pathThresholdMeters = 10.0
        val shouldAdd = lastMapPoint?.let { geoPoint.distanceToAsDouble(it) >= pathThresholdMeters } ?: true
        if (shouldAdd) {
            pathPoints.add(geoPoint)
            pathOverlay.setPoints(pathPoints)
            lastMapPoint = geoPoint
            Log.i(TAG, "Added path point: $lat,$lon (total ${pathPoints.size})")
        }
        if (mapFollowUser) {
            Log.i(TAG, "Centering map on $lat,$lon")
            mapView.controller.animateTo(geoPoint)
        }
        mapView.invalidate()
    }

    private fun clearMapPath() {
        pathPoints.clear()
        pathOverlay.setPoints(pathPoints)
        lastMapPoint = null
        lastStatsPoint = null
        totalDistanceMeters = 0.0
        eventCount = 0
        lastSpeedKmh = 0f
        mapFollowUser = true
        fabRecenter.hide()
        mapView.invalidate()
    }

    private fun onStartClicked() {
        Log.d(TAG, "START button clicked")
        val validation = TripStartValidator.validate(
            TripStartConfiguration(
                direction = selectedDirectionCode(),
                observationPeriod = selectedObservationPeriodCode()
            )
        )
        if (validation.isNotEmpty()) {
            Toast.makeText(this, validation.joinToString("; "), Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
            return
        }
        if (cameraCheckBox.isChecked && !hasCameraPermission()) {
            pendingStartAfterCameraPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(cameraPermission), cameraPermissionRequestCode)
            return
        }
        startRecording()
    }

    private fun onStopClicked() {
        Log.d(TAG, "STOP button clicked")
        stopRecording()
    }

    private fun startRecording() {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_START
            putExtra(LoggerService.EXTRA_ENABLE_CAMERA, cameraCheckBox.isChecked)
            putExtra(LoggerService.EXTRA_DIRECTION, selectedDirectionCode())
            putExtra(LoggerService.EXTRA_OBSERVATION_PERIOD, selectedObservationPeriodCode())
            putExtra(LoggerService.EXTRA_STUDY_DATE, ResearchClock.studyDateLocal(System.currentTimeMillis()))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        pendingStatusHide?.let { handler.removeCallbacks(it) }
        activeTripId = -1L
        isPreparingRecording = true
        isFinalizingRecording = false
        updateUiState(isRecording = true)
        lastHeardText = "—"
        lastMatchedCause = "—"
        updateCauseHeardLine()
        clearMapPath()
        modelStatusText.text = "Preparing local trip record..."
        if (cameraCheckBox.isChecked) {
            setKeepScreenOn()
            bindCameraIfEnabled()
        }
    }

    private fun stopRecording() {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_STOP
        }
        startService(intent)
        isPreparingRecording = false
        isFinalizingRecording = true
        updateUiState(isRecording = true)
        clearKeepScreenOn()
        unbindCamera()
        pendingStatusHide?.let { handler.removeCallbacks(it) }
        statusText.text = "Stopping and saving..."
        statusText.visibility = View.VISIBLE
        val hideRunnable = Runnable { statusText.visibility = View.GONE }
        pendingStatusHide = hideRunnable
        handler.postDelayed(hideRunnable, 3000)
        lastHeardText = "—"
        lastMatchedCause = "—"
        updateCauseHeardLine()
        modelStatusText.text = ""
    }

    private fun selectedDirectionCode(): String? = when (directionSpinner.selectedItemPosition) {
        1 -> ResearchDirection.A_TO_B
        2 -> ResearchDirection.B_TO_A
        else -> null
    }

    private fun selectedObservationPeriodCode(): String? = when (observationPeriodSpinner.selectedItemPosition) {
        1 -> ObservationPeriod.MORNING
        2 -> ObservationPeriod.OFF_PEAK
        3 -> ObservationPeriod.EVENING
        else -> null
    }

    private fun selectResearchValues(direction: String?, period: String?) {
        directionSpinner.setSelection(
            when (direction) {
                ResearchDirection.A_TO_B -> 1
                ResearchDirection.B_TO_A -> 2
                else -> 0
            }
        )
        observationPeriodSpinner.setSelection(
            when (period) {
                ObservationPeriod.MORNING -> 1
                ObservationPeriod.OFF_PEAK -> 2
                ObservationPeriod.EVENING -> 3
                else -> 0
            }
        )
    }

    private fun hasAllPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updatePreTripHealth() {
        if (!::healthText.isInitialized) return
        val locationReady = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            runCatching {
                val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        val microphoneReady = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometerReady = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) != null
        val gyroscopeReady = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null
        val orientationReady = sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GAME_ROTATION_VECTOR) != null ||
            sensors.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR) != null
        val storageMb = StatFs(filesDir.path).availableBytes / (1024.0 * 1024.0)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryPercent = battery?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) level * 100 / scale else null
        }
        val cameraReady = !cameraCheckBox.isChecked || hasCameraPermission()
        val checks = listOf(
            "GPS" to locationReady,
            "mic" to microphoneReady,
            "accel" to accelerometerReady,
            "gyro" to gyroscopeReady,
            "orientation" to orientationReady,
            "camera" to cameraReady
        )
        val missing = checks.filterNot { it.second }.map { it.first }
        healthText.text = "Pre-trip check: ${if (missing.isEmpty()) "READY" else "WARN ${missing.joinToString()}"} · Storage %.0f MB · Battery %s".format(
            storageMb,
            batteryPercent?.let { "$it%" } ?: "unknown"
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            startupPermissionRequestCode -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    refreshLocationOverlay()
                    centerMapOnLastKnownLocation()
                    updatePreTripHealth()
                } else {
                    Toast.makeText(
                        this,
                        "Location and microphone permissions are needed for full functionality",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            permissionRequestCode -> {
                if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    onStartClicked()
                    updatePreTripHealth()
                } else {
                    Toast.makeText(this, "Permissions required to record trip", Toast.LENGTH_LONG).show()
                }
            }
            cameraPermissionRequestCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (cameraCheckBox.isChecked) {
                        bindCameraIfEnabled()
                    }
                    updatePreTripHealth()
                    if (pendingStartAfterCameraPermission) {
                        pendingStartAfterCameraPermission = false
                        startRecording()
                    }
                } else {
                    pendingStartAfterCameraPermission = false
                    cameraCheckBox.isChecked = false
                    Toast.makeText(this, "Camera permission is required to capture bump photos", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage("For reliable recording, please disable battery optimization for RoadLog.")
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, batteryOptimizationRequestCode)
                }
                .setNegativeButton("Skip", null)
                .show()
        }
    }

    private fun updateUiState(isRecording: Boolean) {
        this.isRecording = isRecording
        startButton.isEnabled = !isRecording && !isFinalizingRecording
        stopButton.isEnabled = isRecording && !isFinalizingRecording
        cameraCheckBox.isEnabled = !isRecording
        lastSpokenText.visibility = if (isRecording) View.VISIBLE else View.GONE
        modelStatusText.visibility = if (isRecording) View.VISIBLE else View.GONE
        takePhotoButton.visibility = if (isRecording && !isFinalizingRecording && cameraCheckBox.isChecked) View.VISIBLE else View.GONE
        directionSpinner.isEnabled = !isRecording && !isFinalizingRecording
        observationPeriodSpinner.isEnabled = !isRecording && !isFinalizingRecording
        causeLabelsContainer.visibility = if (isRecording && !isPreparingRecording && !isFinalizingRecording) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (isRecording) {
            statusText.visibility = View.VISIBLE
            statusText.text = if (isFinalizingRecording) {
                "Stopping and saving locally..."
            } else if (isPreparingRecording) {
                "Preparing local trip record..."
            } else {
                "Trip running and saved locally"
            }
        }
    }

    private fun parseEventCount(status: String) {
        val regex = Regex("""Events:\s*(\d+)""")
        regex.find(status)?.groupValues?.get(1)?.toIntOrNull()?.let {
            eventCount = it
        }
    }

    private fun formatDistanceKm(meters: Double): String {
        val km = meters / 1000.0
        return if (km >= 1.0) String.format("%.1f km", km) else String.format("%.2f km", km)
    }

    private fun updateStatsText(speedKmh: Float) {
        val distanceText = formatDistanceKm(totalDistanceMeters)
        val speedText = String.format("%.1f km/h", speedKmh)
        modelStatusText.text = "Dist: $distanceText | Speed: $speedText | Events: $eventCount"
    }

    private fun updateCauseHeardLine() {
        lastSpokenText.text = "Heard: $lastHeardText | Last: $lastMatchedCause"
    }

    private fun highlightCause(cause: String) {
        val label = causeLabels[cause] ?: return

        // Cancel any pending reset for this cause so rapid repeats don't get cut off
        pendingHighlightResets[cause]?.let { handler.removeCallbacks(it) }

        label.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))

        val resetRunnable = Runnable {
            label.setBackgroundColor(ContextCompat.getColor(this, R.color.gray))
            pendingHighlightResets.remove(cause)
        }
        pendingHighlightResets[cause] = resetRunnable
        handler.postDelayed(resetRunnable, 500)
    }

    private fun sendCauseToService(causeCode: String) {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_CAUSE_SELECTED
            putExtra(LoggerService.EXTRA_CAUSE_CODE, causeCode)
        }
        startService(intent)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, cameraPermission) == PackageManager.PERMISSION_GRANTED
    }

    private fun bindCameraIfEnabled() {
        if (!cameraCheckBox.isChecked || !hasCameraPermission()) return
        previewView.visibility = View.INVISIBLE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider unavailable", e)
                return@addListener
            }
            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                Log.i(TAG, "Camera bound")
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                providerFuture.get().unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Camera unbind failed", e)
            }
            imageCapture = null
            previewView.visibility = View.GONE
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureManualPhoto() {
        val point = lastMapPoint ?: getLastKnownLocation()
        val lat = point?.latitude ?: 0.0
        val lon = point?.longitude ?: 0.0
        Log.i(TAG, "Manual photo requested")
        takePhoto(lat, lon, System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), activeTripId, null, null)
    }

    private fun takePhoto(
        lat: Double,
        lon: Double,
        timestamp: Long,
        requestElapsedRealtimeNanos: Long?,
        tripId: Long,
        captureId: String?,
        eventId: String?
    ) {
        val capture = imageCapture ?: run {
            Log.w(TAG, "takePhoto skipped: imageCapture not ready")
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        val ownedTripId = if (tripId >= 0L) tripId else activeTripId
        if (ownedTripId < 0L) {
            Log.w(TAG, "Photo request has no active durable trip")
            return
        }
        val stableCaptureId = captureId ?: UUID.randomUUID().toString()
        val photosDir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(photosDir, "capture_${stableCaptureId}.jpg")

        // A pending media row is created before requesting the asynchronous camera
        // operation. Delayed callbacks therefore have a stable owner.
        photoPersistenceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            if (db.tripDao().getPhotoByCaptureId(stableCaptureId) == null) {
                db.tripDao().insertPhoto(
                    TripPhoto(
                        tripId = ownedTripId,
                        timestamp = timestamp,
                        latitude = lat.takeIf { it != 0.0 },
                        longitude = lon.takeIf { it != 0.0 },
                        filePath = "",
                        captureId = stableCaptureId,
                        eventId = eventId,
                        requestTimeMs = timestamp,
                        requestElapsedRealtimeNanos = requestElapsedRealtimeNanos,
                        mimeType = "image/jpeg",
                        usabilityStatus = "PENDING",
                        privacyStatus = "UNREVIEWED"
                    )
                )
            }
            withContext(Dispatchers.Main) {
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(
                    options,
                    cameraExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed", exc)
                            photoPersistenceScope.launch {
                                db.tripDao().completePhoto(
                                    captureId = stableCaptureId,
                                    filePath = "",
                                    captureTimeMs = System.currentTimeMillis(),
                                    captureElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                                    fileSizeBytes = 0L,
                                    sha256 = null,
                                    width = null,
                                    height = null,
                                    usabilityStatus = "FAILED"
                                )
                            }
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            Log.i(TAG, "Photo saved for trip=$ownedTripId")
                            photoPersistenceScope.launch {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFile(file.absolutePath, bounds)
                                db.tripDao().completePhoto(
                                    captureId = stableCaptureId,
                                    filePath = file.absolutePath,
                                    captureTimeMs = System.currentTimeMillis(),
                                    captureElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                                    fileSizeBytes = file.length(),
                                    sha256 = sha256(file),
                                    width = bounds.outWidth.takeIf { it > 0 },
                                    height = bounds.outHeight.takeIf { it > 0 },
                                    usabilityStatus = "UNREVIEWED"
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    private fun sha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not checksum photo", e)
            null
        }
    }

    private fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setKeepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
