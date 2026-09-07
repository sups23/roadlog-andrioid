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
import android.os.BatteryManager
import android.os.StatFs
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.location.LocationManager
import android.hardware.SensorManager
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoadLog"
    }

    private val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionRequestCode = 1001
    private val startupPermissionRequestCode = 1003
    private val batteryOptimizationRequestCode = 1002

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewTripsButton: Button
    private lateinit var statusText: TextView
    private lateinit var modelStatusText: TextView
    private lateinit var lastSpokenText: TextView
    private lateinit var mapView: MapView
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var pathOverlay: Polyline
    private lateinit var fabRecenter: FloatingActionButton
    private lateinit var directionSpinner: Spinner
    private lateinit var observationPeriodSpinner: Spinner
    private lateinit var studyDateText: TextView
    private lateinit var healthText: TextView

    private var isRecording = false
    private var isPreparingRecording = false
    private var isFinalizingRecording = false
    private var activeTripId = -1L

    private var mapFollowUser = true
    private var lastHeardText = "—"
    private var lastMatchedCause = "—"

    private val handler = Handler(Looper.getMainLooper())
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
                lastMatchedCause = cause
                statusText.text = "Voice event: $cause"
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
        directionSpinner = findViewById(R.id.directionSpinner)
        observationPeriodSpinner = findViewById(R.id.observationPeriodSpinner)
        studyDateText = findViewById(R.id.studyDateText)
        healthText = findViewById(R.id.healthText)

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
        unregisterReceiver(tripSavedReceiver)
        unregisterReceiver(recordingStartedReceiver)
        unregisterReceiver(recordingStateReceiver)
        pendingStatusHide?.let { handler.removeCallbacks(it) }
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
        startRecording()
    }

    private fun onStopClicked() {
        Log.d(TAG, "STOP button clicked")
        stopRecording()
    }

    private fun startRecording() {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_START
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
    }

    private fun stopRecording() {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_STOP
        }
        startService(intent)
        isPreparingRecording = false
        isFinalizingRecording = true
        updateUiState(isRecording = true)
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
        val checks = listOf(
            "GPS" to locationReady,
            "mic" to microphoneReady,
            "accel" to accelerometerReady,
            "gyro" to gyroscopeReady,
            "orientation" to orientationReady
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
        lastSpokenText.visibility = if (isRecording) View.VISIBLE else View.GONE
        modelStatusText.visibility = if (isRecording) View.VISIBLE else View.GONE
        directionSpinner.isEnabled = !isRecording && !isFinalizingRecording
        observationPeriodSpinner.isEnabled = !isRecording && !isFinalizingRecording
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
}
