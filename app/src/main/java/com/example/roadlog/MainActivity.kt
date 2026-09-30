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
import android.widget.EditText
import android.widget.Switch
import android.widget.AdapterView
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
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
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionRequestCode = 1001
    private val startupPermissionRequestCode = 1003
    private val notificationPermissionRequestCode = 1004
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
    private lateinit var fixedTripProfileText: TextView
    private lateinit var weatherSpinner: Spinner
    private lateinit var roadWetnessSpinner: Spinner
    private lateinit var routeDiversionSwitch: Switch
    private lateinit var nonTrafficStopSpinner: Spinner
    private lateinit var contextNoteEditText: EditText
    private lateinit var contextSelectionErrorText: TextView
    private lateinit var contextControls: List<View>

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
            if (status.startsWith("Recording could not be started:")) {
                isRecording = false
                isPreparingRecording = false
                isFinalizingRecording = false
                updateUiState(isRecording = false)
                statusText.visibility = View.VISIBLE
                statusText.text = status
                modelStatusText.visibility = View.VISIBLE
                modelStatusText.text = "Preparation failed. The trip was preserved for recovery."
            } else if (status.startsWith("cause:")) {
                val cause = status.removePrefix("cause:")
                val displayCause = CauseDisplay.name(cause)
                lastMatchedCause = displayCause
                statusText.text = "Voice event: $displayCause"
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
                intent?.let { selectTripContext(it) }
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
            if (state == RecordingState.FAILED.name) {
                isRecording = false
                isPreparingRecording = false
                isFinalizingRecording = false
                updateUiState(isRecording = false)
            } else if (active) {
                isRecording = true
                isPreparingRecording = state == RecordingState.PREPARING.name
                isFinalizingRecording = state == RecordingState.FINALIZING.name
                selectResearchValues(intent?.getStringExtra(LoggerService.EXTRA_DIRECTION), intent?.getStringExtra(LoggerService.EXTRA_OBSERVATION_PERIOD))
                intent?.let { selectTripContext(it) }
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
        fixedTripProfileText = findViewById(R.id.fixedTripProfileText)
        weatherSpinner = findViewById(R.id.weatherSpinner)
        roadWetnessSpinner = findViewById(R.id.roadWetnessSpinner)
        routeDiversionSwitch = findViewById(R.id.routeDiversionSwitch)
        nonTrafficStopSpinner = findViewById(R.id.nonTrafficStopSpinner)
        contextNoteEditText = findViewById(R.id.contextNoteEditText)
        contextSelectionErrorText = findViewById(R.id.contextSelectionErrorText)
        contextControls = listOf(
            weatherSpinner, roadWetnessSpinner, directionSpinner, observationPeriodSpinner, routeDiversionSwitch,
            nonTrafficStopSpinner, contextNoteEditText
        )

        fixedTripProfileText.text = "Driver: ${ResearchTripDefaults.DRIVER_ID}\n" +
            "Vehicle: ${ResearchTripDefaults.VEHICLE_ID}\n" +
            "Profile: ${ResearchVehicleProfile.TYPE} · ${ResearchVehicleProfile.MAKE} · " +
            "${ResearchVehicleProfile.MODEL} · ${ResearchVehicleProfile.YEAR}"

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
        weatherSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Select weather") + TripWeather.values
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        roadWetnessSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Select road wetness") + RoadWetness.values
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        nonTrafficStopSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            NonTrafficStop.values
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        nonTrafficStopSpinner.setSelection(0)
        restoreContextState(savedInstanceState)
        contextNoteEditText.setOnFocusChangeListener { _, _ -> updateContextValidation() }
        contextNoteEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateContextValidation()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        listOf(weatherSpinner, roadWetnessSpinner, directionSpinner, observationPeriodSpinner, nonTrafficStopSpinner)
            .forEach { spinner -> spinner.setOnItemSelectedListener(SimpleItemSelectedListener { updateContextValidation() }) }
        routeDiversionSwitch.setOnCheckedChangeListener { _, _ -> updateContextValidation() }
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

        isRecording = savedInstanceState?.getBoolean("recording_active", false) ?: false
        isPreparingRecording = savedInstanceState?.getBoolean("recording_preparing", false) ?: false
        isFinalizingRecording = savedInstanceState?.getBoolean("recording_finalizing", false) ?: false
        activeTripId = savedInstanceState?.getLong("recording_trip_id", -1L) ?: -1L
        updateUiState(isRecording = isRecording)
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

    override fun onSaveInstanceState(outState: Bundle) {
        saveContextState(outState)
        outState.putBoolean("recording_active", isRecording)
        outState.putBoolean("recording_preparing", isPreparingRecording)
        outState.putBoolean("recording_finalizing", isFinalizingRecording)
        outState.putLong("recording_trip_id", activeTripId)
        super.onSaveInstanceState(outState)
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
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Toast.makeText(this, "Open RoadLog to start recording", Toast.LENGTH_LONG).show()
            return
        }
        val context = currentTripContext()
        val validation = TripStartValidator.validate(
            TripStartConfiguration(
                direction = selectedDirectionCode(),
                observationPeriod = selectedObservationPeriodCode()
            )
        )
        val allValidation = validation + TripContextValidator.validate(context)
        if (allValidation.isNotEmpty()) {
            updateContextValidation()
            Toast.makeText(this, allValidation.joinToString("; "), Toast.LENGTH_LONG).show()
            return
        }
        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, permissionRequestCode)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val preferences = getSharedPreferences("recording_permissions", MODE_PRIVATE)
            if (!preferences.getBoolean("notifications_requested", false)) {
                preferences.edit().putBoolean("notifications_requested", true).apply()
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    notificationPermissionRequestCode
                )
                return
            }
            Toast.makeText(
                this,
                "Notifications are disabled; reopen RoadLog to see recording status and stop the trip",
                Toast.LENGTH_LONG
            ).show()
        }
        startRecording(context)
    }

    private fun onStopClicked() {
        Log.d(TAG, "STOP button clicked")
        stopRecording()
    }

    private fun startRecording(context: TripContext) {
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_START
            putExtra(LoggerService.EXTRA_DIRECTION, selectedDirectionCode())
            putExtra(LoggerService.EXTRA_OBSERVATION_PERIOD, selectedObservationPeriodCode())
            putExtra(LoggerService.EXTRA_STUDY_DATE, ResearchClock.studyDateLocal(System.currentTimeMillis()))
            putExtra(LoggerService.EXTRA_DRIVER_ID, context.driverId)
            putExtra(LoggerService.EXTRA_VEHICLE_ID, context.vehicleId)
            putExtra(LoggerService.EXTRA_VEHICLE_TYPE, context.vehicleType)
            putExtra(LoggerService.EXTRA_VEHICLE_MAKE, context.vehicleMake)
            putExtra(LoggerService.EXTRA_VEHICLE_MODEL, context.vehicleModel)
            putExtra(LoggerService.EXTRA_VEHICLE_YEAR, context.vehicleYear ?: -1)
            putExtra(LoggerService.EXTRA_WEATHER, context.weather)
            putExtra(LoggerService.EXTRA_ROAD_WETNESS, context.roadWetness)
            putExtra(LoggerService.EXTRA_ROUTE_DIVERSION, context.routeDiversion)
            putExtra(LoggerService.EXTRA_NON_TRAFFIC_STOP, context.nonTrafficStop)
            putExtra(LoggerService.EXTRA_CONTEXT_NOTE, context.contextNote)
        }
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (error: SecurityException) {
            Log.e(TAG, "Recording service permission denied", error)
            Toast.makeText(this, "Cannot start: precise location and microphone permissions are required", Toast.LENGTH_LONG).show()
            return
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Recording service startup restricted", error)
            Toast.makeText(this, "Cannot start recording now; keep RoadLog open and try again", Toast.LENGTH_LONG).show()
            return
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

    private fun currentTripContext(): TripContext = TripContext(
        driverId = ResearchTripDefaults.DRIVER_ID,
        vehicleId = ResearchTripDefaults.VEHICLE_ID,
        vehicleType = ResearchVehicleProfile.TYPE,
        vehicleMake = ResearchVehicleProfile.MAKE,
        vehicleModel = ResearchVehicleProfile.MODEL,
        vehicleYear = ResearchVehicleProfile.YEAR,
        weather = weatherSpinner.selectedItemPosition.takeIf { it > 0 }?.let { TripWeather.values[it - 1] },
        roadWetness = roadWetnessSpinner.selectedItemPosition.takeIf { it > 0 }?.let { RoadWetness.values[it - 1] },
        routeDiversion = routeDiversionSwitch.isChecked,
        nonTrafficStop = NonTrafficStop.values[nonTrafficStopSpinner.selectedItemPosition],
        contextNote = contextNoteEditText.text.toString().trim().takeIf { it.isNotEmpty() }
    )

    private fun selectTripContext(intent: Intent) {
        intent.getStringExtra(LoggerService.EXTRA_WEATHER)?.let { value -> weatherSpinner.setSelection(TripWeather.values.indexOf(value) + 1) }
        intent.getStringExtra(LoggerService.EXTRA_ROAD_WETNESS)?.let { value -> roadWetnessSpinner.setSelection(RoadWetness.values.indexOf(value) + 1) }
        routeDiversionSwitch.isChecked = intent.getBooleanExtra(LoggerService.EXTRA_ROUTE_DIVERSION, false)
        intent.getStringExtra(LoggerService.EXTRA_NON_TRAFFIC_STOP)?.let { value -> nonTrafficStopSpinner.setSelection(NonTrafficStop.values.indexOf(value).coerceAtLeast(0)) }
        contextNoteEditText.setText(intent.getStringExtra(LoggerService.EXTRA_CONTEXT_NOTE).orEmpty())
    }

    private fun updateContextValidation() {
        if (!::contextNoteEditText.isInitialized) return
        val errors = TripContextValidator.validate(currentTripContext())
        contextSelectionErrorText.text = errors.filter { it.contains("weather") || it.contains("wetness") || it.contains("stop") }.joinToString("; ")
        contextSelectionErrorText.visibility = if (contextSelectionErrorText.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (::startButton.isInitialized && !isRecording && !isFinalizingRecording) {
            startButton.isEnabled = errors.isEmpty() && TripStartValidator.validate(
                TripStartConfiguration(selectedDirectionCode(), selectedObservationPeriodCode())
            ).isEmpty()
        }
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

    private fun saveContextState(outState: Bundle) {
        outState.putInt("context_weather", weatherSpinner.selectedItemPosition)
        outState.putInt("context_wetness", roadWetnessSpinner.selectedItemPosition)
        outState.putInt("context_direction", directionSpinner.selectedItemPosition)
        outState.putInt("context_period", observationPeriodSpinner.selectedItemPosition)
        outState.putBoolean("context_diversion", routeDiversionSwitch.isChecked)
        outState.putInt("context_stop", nonTrafficStopSpinner.selectedItemPosition)
        outState.putString("context_note", contextNoteEditText.text.toString())
    }

    private fun restoreContextState(savedInstanceState: Bundle?) {
        savedInstanceState ?: return
        weatherSpinner.setSelection(savedInstanceState.getInt("context_weather", 0))
        roadWetnessSpinner.setSelection(savedInstanceState.getInt("context_wetness", 0))
        directionSpinner.setSelection(savedInstanceState.getInt("context_direction", 0))
        observationPeriodSpinner.setSelection(savedInstanceState.getInt("context_period", 0))
        routeDiversionSwitch.isChecked = savedInstanceState.getBoolean("context_diversion", false)
        nonTrafficStopSpinner.setSelection(savedInstanceState.getInt("context_stop", 0))
        contextNoteEditText.setText(savedInstanceState.getString("context_note").orEmpty())
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
            notificationPermissionRequestCode -> onStartClicked()
            startupPermissionRequestCode -> {
                if (hasAllPermissions()) {
                    refreshLocationOverlay()
                    centerMapOnLastKnownLocation()
                    updatePreTripHealth()
                } else {
                    Toast.makeText(
                        this,
                        "Precise location and microphone permissions are needed for recording",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            permissionRequestCode -> {
                if (hasAllPermissions()) {
                    onStartClicked()
                    updatePreTripHealth()
                } else {
                    Toast.makeText(this, "Precise location and microphone permissions are required to record a trip", Toast.LENGTH_LONG).show()
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
        contextControls.forEach { it.isEnabled = !isRecording && !isFinalizingRecording }
        updateContextValidation()
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

private class SimpleItemSelectedListener(
    private val onSelected: () -> Unit
) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelected()
    override fun onNothingSelected(parent: AdapterView<*>?) = onSelected()
}
