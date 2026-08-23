package com.example.roadlog

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TripDetailActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoadLog"
        private const val VISUAL_ROW_LIMIT = 5_000
        private const val VISUAL_SENSOR_LIMIT = 30_000
    }

    private lateinit var database: AppDatabase

    private lateinit var dateText: TextView
    private lateinit var researchText: TextView
    private lateinit var durationText: TextView
    private lateinit var distanceText: TextView
    private lateinit var speedText: TextView
    private lateinit var eventsText: TextView
    private lateinit var qaActionsContainer: LinearLayout
    private lateinit var qaValidButton: Button
    private lateinit var qaWarningsButton: Button
    private lateinit var qaInvalidButton: Button
    private lateinit var breakdownContainer: LinearLayout
    private lateinit var timelineContainer: LinearLayout
    private lateinit var speedChart: LineChart
    private lateinit var roughnessChart: LineChart
    private lateinit var lateralChart: LineChart
    private lateinit var longitudinalChart: LineChart
    private lateinit var yawChart: LineChart
    private lateinit var deleteButton: Button
    private lateinit var viewRouteButton: Button
    private lateinit var photosContainer: LinearLayout
    private lateinit var contentScrollView: ScrollView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var loadingStatusText: TextView

    private var tripId: Long = -1
    private var tripStart: Long = 0
    private var tripEnd: Long = 0
    private var currentQaStatus: String = TripQaStatus.UNREVIEWED

    private var gpsRouteData: List<TripData> = emptyList()
    private var worldAccelData: List<WorldAccelSample> = emptyList()
    private var worldGyroData: List<WorldGyroSample> = emptyList()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val dateFormatter = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        setContentView(R.layout.activity_trip_detail)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        tripId = intent.getLongExtra(TripHistoryActivity.EXTRA_TRIP_ID, -1)
        tripStart = intent.getLongExtra(TripHistoryActivity.EXTRA_TRIP_START, 0)
        tripEnd = intent.getLongExtra(TripHistoryActivity.EXTRA_TRIP_END, 0)

        database = AppDatabase.getDatabase(this)

        dateText = findViewById(R.id.detailDateText)
        researchText = findViewById(R.id.detailResearchText)
        durationText = findViewById(R.id.detailDurationText)
        distanceText = findViewById(R.id.detailDistanceText)
        speedText = findViewById(R.id.detailSpeedText)
        eventsText = findViewById(R.id.detailEventsText)
        qaActionsContainer = findViewById(R.id.qaActionsContainer)
        qaValidButton = findViewById(R.id.qaValidButton)
        qaWarningsButton = findViewById(R.id.qaWarningsButton)
        qaInvalidButton = findViewById(R.id.qaInvalidButton)
        breakdownContainer = findViewById(R.id.breakdownContainer)
        timelineContainer = findViewById(R.id.timelineContainer)
        speedChart = findViewById(R.id.speedChart)
        roughnessChart = findViewById(R.id.roughnessChart)
        lateralChart = findViewById(R.id.lateralChart)
        longitudinalChart = findViewById(R.id.longitudinalChart)
        yawChart = findViewById(R.id.yawChart)
        deleteButton = findViewById(R.id.deleteTripButton)
        viewRouteButton = findViewById(R.id.viewRouteButton)
        photosContainer = findViewById(R.id.photosContainer)
        contentScrollView = findViewById(R.id.contentScrollView)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        loadingStatusText = findViewById(R.id.loadingStatusText)

        setupChart(speedChart, "Speed (km/h)")
        setupChart(roughnessChart, "Vertical roughness (m/s²)")
        setupChart(lateralChart, "Lateral acceleration (m/s²)")
        setupChart(longitudinalChart, "Longitudinal acceleration (m/s²)")
        setupChart(yawChart, "Yaw rate (rad/s)")

        deleteButton.setOnClickListener { confirmDelete() }
        viewRouteButton.setOnClickListener { showRouteMap() }
        qaValidButton.setOnClickListener { updateQaStatus(TripQaStatus.VALID) }
        qaWarningsButton.setOnClickListener { updateQaStatus(TripQaStatus.VALID_WITH_WARNINGS) }
        qaInvalidButton.setOnClickListener { updateQaStatus(TripQaStatus.INVALID) }
        showLoading(true)

        if (tripId == -1L || tripStart == 0L || tripEnd == 0L) {
            Log.e(TAG, "Invalid trip extras: tripId=$tripId, start=$tripStart, end=$tripEnd")
            eventsText.text = "Error: invalid trip"
            showLoading(false)
            return
        }

        loadTripDetails()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        scope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupChart(chart: LineChart, label: String) {
        chart.description.isEnabled = false
        chart.contentDescription = label
        chart.setDrawGridBackground(false)
        chart.legend.textSize = 12f
        chart.axisRight.isEnabled = false
        chart.xAxis.position = XAxis.XAxisPosition.BOTTOM
        chart.xAxis.setDrawGridLines(false)
        chart.axisLeft.setDrawGridLines(true)
        chart.axisLeft.textSize = 10f
        chart.setNoDataText("No data")
    }

    private fun loadTripDetails() {
        showLoading(true)
        setStatus("Loading trip data...")
        Log.d(TAG, "Loading trip details: tripId=$tripId, start=$tripStart, end=$tripEnd")
        scope.launch {
            try {
                val trip = withContext(Dispatchers.IO) {
                    database.tripDao().getTripById(tripId)
                }
                if (trip == null) {
                    Log.e(TAG, "Trip not found for id=$tripId")
                    eventsText.text = "Trip not found"
                    showLoading(false)
                    return@launch
                }
                currentQaStatus = trip.qaStatus
                ensureActive()

                setStatus("Querying GPS and sensor data...")
                val dbStart = System.currentTimeMillis()
                val gpsData = withContext(Dispatchers.IO) {
                    database.tripDao().getGpsForTripCapped(tripId, tripStart, tripEnd, VISUAL_ROW_LIMIT)
                }
                gpsRouteData = gpsData
                val events = withContext(Dispatchers.IO) { database.tripDao().getTripEvents(tripId) }
                val accelData = withContext(Dispatchers.IO) {
                    database.tripDao().getAccelForTripCapped(tripId, tripStart, tripEnd, VISUAL_SENSOR_LIMIT)
                }
                val gyroData = withContext(Dispatchers.IO) {
                    database.tripDao().getGyroForTripCapped(tripId, tripStart, tripEnd, VISUAL_SENSOR_LIMIT)
                }
                val rotationData = withContext(Dispatchers.IO) {
                    database.tripDao().getRotationForTripCapped(tripId, tripStart, tripEnd, VISUAL_SENSOR_LIMIT)
                }
                val photos = withContext(Dispatchers.IO) {
                    database.tripDao().getPhotosForTrip(tripId)
                }
                Log.d(TAG, "DB queries took ${System.currentTimeMillis() - dbStart}ms; gps=${gpsData.size}, events=${events.size}, accel=${accelData.size}, gyro=${gyroData.size}, rot=${rotationData.size}, photos=${photos.size}")
                ensureActive()

                setStatus("Computing sensor fusion...")
                val worldAccel = withContext(Dispatchers.Default) {
                    computeWorldAccel(accelData, rotationData)
                }
                worldAccelData = worldAccel
                val worldGyro = withContext(Dispatchers.Default) {
                    computeWorldGyro(gyroData, rotationData)
                }
                worldGyroData = worldGyro
                ensureActive()

                setStatus("Preparing charts...")
                bindHeader(trip, gpsData)
                bindBreakdown(trip.causeBreakdown)
                bindTimeline(events, trip.startTimeMs)
                bindSpeedChart(gpsData)
                bindRoughnessChart(worldAccel)
                bindLateralChart(worldAccel)
                bindLongitudinalChart(worldAccel)
                bindYawChart(worldGyro)
                bindPhotos(photos)

                showLoading(false)
            } catch (e: CancellationException) {
                Log.d(TAG, "Trip detail loading cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load trip details", e)
                showLoading(false)
                eventsText.text = "Error loading trip details"
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        Log.d(TAG, "showLoading: $isLoading")
        if (isLoading) {
            contentScrollView.visibility = View.GONE
            loadingProgressBar.visibility = View.VISIBLE
        } else {
            loadingProgressBar.visibility = View.GONE
            contentScrollView.visibility = View.VISIBLE
            loadingStatusText.visibility = View.GONE
        }
        val parent = contentScrollView.parent as? View
        parent?.requestLayout()
        parent?.postInvalidate()
        contentScrollView.post {
            val scrollVisible = contentScrollView.visibility == View.VISIBLE
            val progressVisible = loadingProgressBar.visibility == View.VISIBLE
            Log.d(TAG, "showLoading posted: scrollVisible=$scrollVisible, progressVisible=$progressVisible")
            if (!isLoading && (!scrollVisible || progressVisible)) {
                Log.w(TAG, "Forcing visibility: scroll=visible, progress=gone")
                contentScrollView.visibility = View.VISIBLE
                loadingProgressBar.visibility = View.GONE
                (contentScrollView.parent as? View)?.requestLayout()
            }
        }
    }

    private fun setStatus(message: String) {
        loadingStatusText.text = message
        loadingStatusText.visibility = View.VISIBLE
    }

    private fun showRouteMap() {
        if (gpsRouteData.isEmpty()) return
        RouteMapDialogFragment.show(this, gpsRouteData, worldAccelData, worldGyroData)
    }

    private fun bindHeader(trip: Trip, gpsData: List<TripData>) {
        Log.d(TAG, "bindHeader: gps=${gpsData.size}")
        dateText.text = dateFormatter.format(Date(trip.startTimeMs))
        researchText.text = listOfNotNull(
            "Study date: ${trip.studyDateLocal ?: "unknown"} (${trip.timeZoneId ?: ResearchTime.KATHMANDU_ZONE_ID})",
            "Study corridor: ${trip.corridorId}",
            "Direction: ${trip.direction ?: "unknown"}",
            "Period: ${trip.observationPeriod ?: "unknown"}",
            "QA: ${trip.qaStatus}",
            if (trip.partialTraversal) "Partial coverage until ${trip.coverageEndTimeMs ?: trip.endTimeMs}" else null,
            trip.interruptionReason?.let { "Interruption: $it" }
        ).joinToString("\n")

        val minutes = TimeUnit.MILLISECONDS.toMinutes(trip.endTimeMs - trip.startTimeMs)
        val hours = minutes / 60
        val remainingMinutes = minutes % 60
        durationText.text = if (hours > 0) {
            "Duration: ${hours}h ${remainingMinutes}m"
        } else {
            "Duration: ${minutes} min"
        }

        val km = trip.distanceMeters / 1000.0
        distanceText.text = if (km >= 1.0) {
            String.format("Distance: %.1f km", km)
        } else {
            String.format("Distance: %.2f km", km)
        }

        val speeds = gpsData.mapNotNull { it.speedKmh }
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        val maxSpeed = if (speeds.isNotEmpty()) speeds.maxOrNull() ?: 0f else 0f
        speedText.text = String.format("Avg speed: %.1f km/h · Max: %.1f km/h", avgSpeed, maxSpeed)

        eventsText.text = "Events: ${trip.eventCount}"
        qaActionsContainer.visibility = if (trip.qaStatus == TripQaStatus.UNREVIEWED) View.VISIBLE else View.GONE
    }

    private fun updateQaStatus(status: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    database.tripDao().updateTripQa(
                        tripId = tripId,
                        qaStatus = status,
                        qaNotes = if (status == TripQaStatus.VALID_WITH_WARNINGS) {
                            "Partial or warned traversal reviewed; only covered segments may be used."
                        } else null
                    )
                }
                loadTripDetails()
            } catch (error: Exception) {
                Log.e(TAG, "Could not update trip QA", error)
            }
        }
    }

    private fun bindBreakdown(causeBreakdown: String) {
        Log.d(TAG, "bindBreakdown: $causeBreakdown")
        breakdownContainer.removeAllViews()
        try {
            val json = org.json.JSONObject(causeBreakdown)
            val keys = json.keys().asSequence().sorted().toList()
            if (keys.isEmpty()) {
                addBreakdownChip("No causes recorded", false)
                return
            }
            keys.forEach { cause ->
                addBreakdownChip("${displayCause(cause)} ×${json.getInt(cause)}", true)
            }
        } catch (e: Exception) {
            addBreakdownChip("No causes recorded", false)
        }
    }

    private fun addBreakdownChip(text: String, colored: Boolean) {
        val chip = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(24, 12, 24, 12)
            if (colored) {
                setBackgroundColor(ContextCompat.getColor(this@TripDetailActivity, R.color.teal_700))
                setTextColor(ContextCompat.getColor(this@TripDetailActivity, android.R.color.white))
            } else {
                setBackgroundColor(ContextCompat.getColor(this@TripDetailActivity, R.color.gray))
                setTextColor(ContextCompat.getColor(this@TripDetailActivity, android.R.color.white))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 16, 16)
            layoutParams = params
        }
        breakdownContainer.addView(chip)
    }

    private fun bindTimeline(events: List<TripEvent>, tripStartMs: Long) {
        Log.d(TAG, "bindTimeline: events=${events.size}")
        timelineContainer.removeAllViews()
        if (events.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "No events recorded"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@TripDetailActivity, R.color.gray))
            }
            timelineContainer.addView(emptyText)
            return
        }

        events.forEach { event ->
            val elapsedMs = event.markerTimeMs - tripStartMs
            val elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
            val minutes = elapsedSeconds / 60
            val seconds = elapsedSeconds % 60
            val timeText = String.format("%02d:%02d", minutes, seconds)

            val row = TextView(this).apply {
                val cause = event.primaryCauseCode?.let(::displayCause) ?: "UNANNOTATED"
                text = "$timeText · $cause · ${event.status} · ${event.provenance}"
                textSize = 15f
                setPadding(0, 8, 0, 8)
                setOnClickListener { showAnnotationDialog(event) }
            }
            timelineContainer.addView(row)
        }
    }

    private fun displayCause(code: String): String = when (code) {
        ResearchCodebook.UNCLASSIFIED_CODE -> "UNCLASSIFIED"
        else -> code
    }

    private fun showAnnotationDialog(event: TripEvent) {
        scope.launch {
            val existing = withContext(Dispatchers.IO) {
                database.tripDao().getAnnotationsForEvent(event.eventId).lastOrNull()
            }
            ensureActive()
            showAnnotationDialog(event, existing)
        }
    }

    private fun showAnnotationDialog(event: TripEvent, existing: EventAnnotationRevision?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 8, 32, 0)
        }
        fun spinner(values: List<String>): Spinner = Spinner(this).also { view ->
            view.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                values
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            form.addView(view)
        }
        fun label(text: String) {
            form.addView(TextView(this).apply {
                this.text = text
                setPadding(0, 12, 0, 2)
            })
        }

        val causes = ResearchCodebook.primaryCodes.toList().sorted()
        label("Primary cause")
        val primary = spinner(causes)
        label("Secondary cause 1")
        val secondary1 = spinner(listOf("None") + causes)
        label("Secondary cause 2")
        val secondary2 = spinner(listOf("None") + causes)
        label("Traffic state")
        val traffic = spinner(listOf("Unspecified") + ResearchCodebook.trafficStates.toList().sorted())
        label("Confidence")
        val confidence = spinner(listOf("0", "1", "2", "3"))
        label("Notes")
        val notes = EditText(this).apply {
            hint = "Optional annotation note"
            minLines = 2
            setText(existing?.notes.orEmpty())
        }
        form.addView(notes)
        val sourceVisible = CheckBox(this).apply {
            text = "Source was visible at experienced location"
            isChecked = event.sourceLocationVisible == true
        }
        form.addView(sourceVisible)
        label("Source latitude (optional)")
        val sourceLatitude = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(event.sourceLatitude?.toString().orEmpty())
        }
        form.addView(sourceLatitude)
        label("Source longitude (optional)")
        val sourceLongitude = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(event.sourceLongitude?.toString().orEmpty())
        }
        form.addView(sourceLongitude)

        val existingPrimary = (existing?.primaryCauseCode ?: event.primaryCauseCode)?.let { causes.indexOf(it) } ?: 0
        primary.setSelection(existingPrimary.coerceAtLeast(0))
        secondary1.setSelection((existing?.secondaryCause1?.let { causes.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0))
        secondary2.setSelection((existing?.secondaryCause2?.let { causes.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: 0))
        confidence.setSelection((existing?.confidenceCode ?: event.confidenceCode ?: 0).coerceIn(0, 3))
        (existing?.trafficState ?: event.trafficState)?.let { state ->
            val index = ResearchCodebook.trafficStates.toList().sorted().indexOf(state)
            if (index >= 0) traffic.setSelection(index + 1)
        }

        AlertDialog.Builder(this)
            .setTitle("Annotate event")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val secondaryCodes = listOf(secondary1, secondary2)
                    .map { it.selectedItem.toString() }
                    .filter { it != "None" }
                val trafficState = traffic.selectedItem.toString().takeUnless { it == "Unspecified" }
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            database.tripDao().annotateEvent(
                                eventId = event.eventId,
                                annotation = EventAnnotation(
                                    primaryCauseCode = primary.selectedItem.toString(),
                                    secondaryCauseCodes = secondaryCodes,
                                    confidenceCode = confidence.selectedItem.toString().toInt(),
                                    trafficState = trafficState,
                                    notes = notes.text.toString().trim().takeIf { it.isNotEmpty() }
                                )
                            )
                            database.tripDao().updateEventSourceLocationWithAudit(
                                eventId = event.eventId,
                                latitude = sourceLatitude.text.toString().trim().toDoubleOrNull(),
                                longitude = sourceLongitude.text.toString().trim().toDoubleOrNull(),
                                visible = sourceVisible.isChecked,
                                reason = "event annotation review"
                            )
                        }
                        loadTripDetails()
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not save event annotation", error)
                        AlertDialog.Builder(this@TripDetailActivity)
                            .setTitle("Annotation not saved")
                            .setMessage(error.message ?: "Validation or database error")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun bindSpeedChart(gpsData: List<TripData>) {
        Log.d(TAG, "bindSpeedChart: gps=${gpsData.size}")
        if (gpsData.isEmpty()) {
            speedChart.clear()
            return
        }

        val entries = gpsData.map { point ->
            val elapsedSec = ((point.timestamp - tripStart) / 1000f)
            Entry(elapsedSec, point.speedKmh ?: 0f)
        }

        val dataSet = LineDataSet(entries, "Speed (km/h)").apply {
            color = ContextCompat.getColor(this@TripDetailActivity, R.color.teal_700)
            setDrawCircles(false)
            lineWidth = 2f
            valueTextSize = 0f
            setDrawValues(false)
        }

        speedChart.data = LineData(dataSet)
        speedChart.invalidate()
    }

    private fun bindRoughnessChart(worldAccel: List<WorldAccelSample>) {
        Log.d(TAG, "bindRoughnessChart: worldAccel=${worldAccel.size}")
        if (worldAccel.isEmpty()) {
            roughnessChart.clear()
            return
        }

        val bins = downsampleToRmsBins(worldAccel.map { it.timestamp to it.vertical }, tripStart)
        if (bins.isEmpty()) {
            roughnessChart.clear()
            return
        }

        val entries = bins.map { (second, roughness) ->
            Entry(second.toFloat(), roughness.toFloat())
        }

        val dataSet = LineDataSet(entries, "Vertical roughness (m/s²)").apply {
            color = ContextCompat.getColor(this@TripDetailActivity, R.color.red)
            setDrawCircles(false)
            lineWidth = 2f
            valueTextSize = 0f
            setDrawValues(false)
        }

        roughnessChart.data = LineData(dataSet)
        roughnessChart.invalidate()
    }

    private fun bindLateralChart(worldAccel: List<WorldAccelSample>) {
        Log.d(TAG, "bindLateralChart: worldAccel=${worldAccel.size}")
        if (worldAccel.isEmpty()) {
            lateralChart.clear()
            return
        }

        val bins = downsampleToRmsBins(worldAccel.map { it.timestamp to it.lateral }, tripStart)
        val entries = bins.map { (second, value) ->
            Entry(second.toFloat(), value.toFloat())
        }
        val dataSet = LineDataSet(entries, "Lateral acceleration (m/s²)").apply {
            color = ContextCompat.getColor(this@TripDetailActivity, R.color.teal_700)
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
        }
        lateralChart.data = LineData(dataSet)
        lateralChart.invalidate()
    }

    private fun bindLongitudinalChart(worldAccel: List<WorldAccelSample>) {
        Log.d(TAG, "bindLongitudinalChart: worldAccel=${worldAccel.size}")
        if (worldAccel.isEmpty()) {
            longitudinalChart.clear()
            return
        }

        val bins = downsampleToRmsBins(worldAccel.map { it.timestamp to it.longitudinal }, tripStart)
        val entries = bins.map { (second, value) ->
            Entry(second.toFloat(), value.toFloat())
        }
        val dataSet = LineDataSet(entries, "Longitudinal acceleration (m/s²)").apply {
            color = ContextCompat.getColor(this@TripDetailActivity, R.color.purple_500)
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
        }
        longitudinalChart.data = LineData(dataSet)
        longitudinalChart.invalidate()
    }

    private fun bindYawChart(worldGyro: List<WorldGyroSample>) {
        Log.d(TAG, "bindYawChart: worldGyro=${worldGyro.size}")
        if (worldGyro.isEmpty()) {
            yawChart.clear()
            return
        }

        val bins = downsampleToRmsBins(worldGyro.map { it.timestamp to it.yaw }, tripStart)
        val entries = bins.map { (second, value) ->
            Entry(second.toFloat(), value.toFloat())
        }
        val dataSet = LineDataSet(entries, "Yaw rate (rad/s)").apply {
            color = ContextCompat.getColor(this@TripDetailActivity, R.color.green)
            setDrawCircles(false)
            lineWidth = 2f
            setDrawValues(false)
        }
        yawChart.data = LineData(dataSet)
        yawChart.invalidate()
    }

    private fun bindPhotos(photos: List<TripPhoto>) {
        Log.d(TAG, "bindPhotos: photos=${photos.size}")
        photosContainer.removeAllViews()
        if (photos.isEmpty()) {
            photosContainer.visibility = View.GONE
            return
        }
        photosContainer.visibility = View.VISIBLE
        val size = resources.getDimensionPixelSize(R.dimen.photo_thumbnail_size)
        val margin = resources.getDimensionPixelSize(R.dimen.photo_thumbnail_margin)
        scope.launch {
            for (photo in photos) {
                ensureActive()
                val file = java.io.File(photo.filePath)
                if (!file.exists()) continue
                val bitmap = withContext(Dispatchers.IO) {
                    decodeSampledBitmap(file.absolutePath, size)
                } ?: continue
                ensureActive()

                val photoCard = LinearLayout(this@TripDetailActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(margin, 0, margin, 0)
                    }
                }

                val imageView = ImageView(this@TripDetailActivity).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(size, size)
                }

                val timeText = TextView(this@TripDetailActivity).apply {
                    text = timeFormatter.format(java.util.Date(photo.timestamp))
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@TripDetailActivity, R.color.black))
                }

                photoCard.addView(imageView)
                photoCard.addView(timeText)
                photosContainer.addView(photoCard)
            }
            if (photosContainer.childCount == 0) {
                photosContainer.visibility = View.GONE
            }
        }
    }

    private fun decodeSampledBitmap(path: String, targetSize: Int): android.graphics.Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            val sampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / targetSize)
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }.let { opts ->
                BitmapFactory.decodeFile(path, opts)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode photo $path", e)
            null
        }
    }

    private fun confirmDelete() {
        if (currentQaStatus != TripQaStatus.UNREVIEWED) {
            Toast.makeText(this, "Reviewed research trips are retained; export before controlled deletion.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete trip?")
            .setMessage("This will remove the trip record, photos, and all its stored data.")
            .setPositiveButton("Delete") { _, _ ->
                deleteTrip()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteTrip() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val photos = database.tripDao().getPhotosForTrip(tripId)
                for (photo in photos) {
                    try {
                        java.io.File(photo.filePath).delete()
                    } catch (e: Exception) {
                        Log.e("RoadLog", "Failed to delete photo ${photo.filePath}", e)
                    }
                }
                database.tripDao().deleteTripCascade(tripId)
            }
            finish()
        }
    }
}
