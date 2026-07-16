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
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.location.LocationManager
import android.widget.TextView
import android.widget.Toast
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var captureSessionStartMs = 0L
    private var captureSessionEndMs = 0L
    private var pendingStartAfterCameraPermission = false
    private var isRecording = false

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
            Log.i(TAG, "Location broadcast received: lat=$lat lon=$lon speed=$speed")
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
            Log.i(TAG, "Capture photo received: lat=$lat lon=$lon")
            if (ContextCompat.checkSelfPermission(this@MainActivity, cameraPermission) == PackageManager.PERMISSION_GRANTED) {
                takePhoto(lat, lon, photoTime)
            } else {
                Log.w(TAG, "Camera permission not granted, skipping photo")
            }
        }
    }

    private val tripSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val tripId = intent?.getLongExtra(LoggerService.EXTRA_TRIP_ID, -1L) ?: return
            if (tripId < 0) return
            Log.i(TAG, "Trip saved id=$tripId, attaching pending photos")
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@MainActivity)
                val photos = db.tripDao().getPhotosForTrip(0L)
                    .filter { it.timestamp in captureSessionStartMs..captureSessionEndMs }
                for (photo in photos) {
                    db.tripDao().updatePhoto(photo.copy(tripId = tripId))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure osmdroid BEFORE setContentView/inflating MapView.
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "RoadLog/1.0 (com.example.roadlog)"
        Configuration.getInstance().osmdroidBasePath = filesDir
        Configuration.getInstance().osmdroidTileCache = File(filesDir, "tiles")
        Configuration.getInstance().osmdroidTileCache.mkdirs()
        Configuration.getInstance().isDebugMapTileDownloader = true
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
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraCheckBox.text = getString(R.string.camera_checkbox_label)
        cameraCheckBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && ContextCompat.checkSelfPermission(this, cameraPermission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(cameraPermission), cameraPermissionRequestCode)
            }
            if (isRecording) {
                takePhotoButton.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
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
                causes = emptyList()
            )
        }

        Log.i(TAG, "Loaded ${causeConfig.causes.size} causes for UI: ${causeConfig.causes.map { it.code }}")

        val container = findViewById<LinearLayout>(R.id.causeLabelsContainer)
        val labels = mutableMapOf<String, TextView>()
        val displayMetrics = resources.displayMetrics
        val labelHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 56f, displayMetrics).toInt()
        val marginPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, displayMetrics).toInt()
        val columnCount = 3

        causeConfig.causes.chunked(columnCount).forEach { rowCauses ->
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

            container.addView(row)
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
        pendingStatusHide?.let { handler.removeCallbacks(it) }
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::mapView.isInitialized) {
            mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::mapView.isInitialized) {
            mapView.onPause()
        }
    }

    private fun setupMap() {
        mapView = findViewById(R.id.mapView)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
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
            Log.d(TAG, "Last known location: ${it.latitude},${it.longitude}")
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
        captureSessionStartMs = System.currentTimeMillis()
        captureSessionEndMs = 0L
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_START
            putExtra(LoggerService.EXTRA_ENABLE_CAMERA, cameraCheckBox.isChecked)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        pendingStatusHide?.let { handler.removeCallbacks(it) }
        updateUiState(isRecording = true)
        lastHeardText = "—"
        lastMatchedCause = "—"
        updateCauseHeardLine()
        clearMapPath()
        modelStatusText.text = "Trip started — waiting for GPS..."
        if (cameraCheckBox.isChecked) {
            setKeepScreenOn()
            bindCameraIfEnabled()
        }
    }

    private fun stopRecording() {
        captureSessionEndMs = System.currentTimeMillis()
        val intent = Intent(this, LoggerService::class.java).apply {
            action = LoggerService.ACTION_STOP
        }
        startService(intent)
        updateUiState(isRecording = false)
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

    private fun hasAllPermissions(): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
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
                    startRecording()
                } else {
                    Toast.makeText(this, "Permissions required to record trip", Toast.LENGTH_LONG).show()
                }
            }
            cameraPermissionRequestCode -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (cameraCheckBox.isChecked) {
                        bindCameraIfEnabled()
                    }
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
        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording
        cameraCheckBox.isEnabled = !isRecording
        takePhotoButton.visibility = if (isRecording && cameraCheckBox.isChecked) View.VISIBLE else View.GONE
        if (isRecording) {
            statusText.visibility = View.VISIBLE
            statusText.text = "Trip running"
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
        Log.i(TAG, "Manual photo requested at lat=$lat lon=$lon")
        takePhoto(lat, lon, System.currentTimeMillis())
    }

    private fun takePhoto(lat: Double, lon: Double, timestamp: Long) {
        val capture = imageCapture ?: run {
            Log.w(TAG, "takePhoto skipped: imageCapture not ready")
            Toast.makeText(this, "Camera not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        val photosDir = File(filesDir, "photos").apply { mkdirs() }
        val file = File(photosDir, "bump_${timestamp}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.i(TAG, "Photo saved: ${file.absolutePath}")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(this@MainActivity)
                        db.tripDao().insertPhoto(
                            TripPhoto(
                                tripId = 0L,
                                timestamp = timestamp,
                                latitude = if (lat != 0.0) lat else null,
                                longitude = if (lon != 0.0) lon else null,
                                filePath = file.absolutePath
                            )
                        )
                    }
                }
            }
        )
    }

    private fun clearKeepScreenOn() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setKeepScreenOn() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
