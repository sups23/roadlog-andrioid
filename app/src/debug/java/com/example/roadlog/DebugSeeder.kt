package com.example.roadlog

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

object DebugSeeder {

    private const val TAG = "RoadLog"
    const val DEMO_MARKER_FILE = "roadlog_demo_seeded"

    data class DemoTripDef(
        val label: String,
        val startOffsetDays: Long,
        val durationMinutes: Long,
        val distanceKm: Double,
        val eventCauses: List<Pair<Double, String>>,
        val gpsCount: Int,
        val sensorCount: Int
    )

    val DEMO_TRIPS = listOf(
        DemoTripDef(
            label = "Normal Commute",
            startOffsetDays = 1,
            durationMinutes = 35,
            distanceKm = 12.3,
            eventCauses = listOf(0.2 to "SIGNAL", 0.5 to "QUEUE", 0.8 to "ROUGH"),
            gpsCount = 500,
            sensorCount = 5000
        ),
        DemoTripDef(
            label = "Market Run",
            startOffsetDays = 2,
            durationMinutes = 22,
            distanceKm = 5.7,
            eventCauses = listOf(0.3 to "MARKET", 0.6 to "PED", 0.9 to "BUS"),
            gpsCount = 350,
            sensorCount = 3500
        ),
        DemoTripDef(
            label = "Dense Highway",
            startOffsetDays = 3,
            durationMinutes = 90,
            distanceKm = 85.0,
            eventCauses = listOf(0.1 to "SIGNAL", 0.3 to "ROUGH", 0.5 to "ROUGH", 0.7 to "TURNING", 0.9 to "FRICTION"),
            gpsCount = 5000,
            sensorCount = 30000
        ),
        DemoTripDef(
            label = "Overlap A",
            startOffsetDays = 4,
            durationMinutes = 60,
            distanceKm = 8.0,
            eventCauses = listOf(0.2 to "SIGNAL", 0.5 to "QUEUE", 0.8 to "BUS"),
            gpsCount = 200,
            sensorCount = 2000
        ),
        DemoTripDef(
            label = "Overlap B",
            startOffsetDays = 4,
            durationMinutes = 30,
            distanceKm = 4.5,
            eventCauses = listOf(0.3 to "ROUGH", 0.7 to "CONSTRUCTION"),
            gpsCount = 150,
            sensorCount = 1500
        )
    )

    suspend fun seedIfNeeded(context: Context): Boolean {
        val markerFile = File(context.filesDir, DEMO_MARKER_FILE)
        if (markerFile.exists()) {
            Log.d(TAG, "Demo data already seeded")
            return false
        }
        seed(context)
        return true
    }

    suspend fun seed(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L

        Log.i(TAG, "Seeding ${DEMO_TRIPS.size} demo trips...")

        for (def in DEMO_TRIPS) {
            val startMs = now - def.startOffsetDays * dayMs
            val endMs = startMs + def.durationMinutes * 60_000L
            val lat = 27.7 + def.startOffsetDays * 0.01
            val lon = 85.3 + def.startOffsetDays * 0.02

            val trip = Trip(
                startTimeMs = startMs,
                endTimeMs = 0,
                startNanoTime = 0,
                distanceMeters = 0.0,
                eventCount = 0,
                gpsPointCount = 0,
                accelPointCount = 0,
                causeBreakdown = "{}",
                createdAt = 0,
                status = TripStatus.RECORDING,
                tripUuid = "debug-${UUID.randomUUID()}",
                notes = "DEBUG_SEED"
            )
            val tripId = withContext(Dispatchers.IO) { db.tripDao().insertTrip(trip) }

            val rows = buildDemoRows(tripId, startMs, endMs, lat, lon, def)
            rows.chunked(500).forEach { chunk ->
                withContext(Dispatchers.IO) { db.tripDao().insertAll(chunk) }
            }

            val events = buildDemoEvents(tripId, startMs, endMs, def)
            events.chunked(100).forEach { chunk ->
                withContext(Dispatchers.IO) { db.tripDao().insertEvents(chunk) }
            }

            val breakdown = org.json.JSONObject().apply {
                def.eventCauses.groupBy { it.second }.forEach { (cause, list) -> put(cause, list.size) }
            }.toString()

            withContext(Dispatchers.IO) {
                db.tripDao().finalizeTrip(
                    tripId = tripId,
                    endTimeMs = endMs,
                    endNanoTime = 0,
                    distanceMeters = def.distanceKm * 1000.0,
                    eventCount = def.eventCauses.size,
                    gpsPointCount = def.gpsCount,
                    accelPointCount = def.sensorCount,
                    causeBreakdown = breakdown,
                    createdAt = now
                )
            }
        }

        File(context.filesDir, DEMO_MARKER_FILE).writeText(now.toString())
        Log.i(TAG, "Seeded ${DEMO_TRIPS.size} demo trips")
    }

    suspend fun clear(context: Context) {
        val db = AppDatabase.getDatabase(context)
        val markerFile = File(context.filesDir, DEMO_MARKER_FILE)
        if (!markerFile.exists()) {
            Log.i(TAG, "No demo seed marker; refusing to clear trips")
            return
        }

        withContext(Dispatchers.IO) {
            val trips = db.tripDao().getAllTripsForExport()
                .filter { it.notes == "DEBUG_SEED" && it.tripUuid.startsWith("debug-") }
            for (trip in trips) {
                db.tripDao().deleteTripWithMediaFiles(trip.id)
            }
        }

        try { markerFile.delete() } catch (_: Exception) {}
        Log.i(TAG, "Cleared seeded demo trips")
    }

    fun isSeeded(context: Context): Boolean {
        return File(context.filesDir, DEMO_MARKER_FILE).exists()
    }

    private fun buildDemoRows(
        tripId: Long, startMs: Long, endMs: Long,
        baseLat: Double, baseLon: Double,
        def: DemoTripDef
    ): List<TripData> {
        val rows = mutableListOf<TripData>()
        val durationMs = endMs - startMs

        for (i in 0 until def.gpsCount) {
            val t = startMs + (i.toLong() * durationMs) / def.gpsCount
            rows.add(
                TripData(
                    tripId = tripId, timestamp = t,
                    latitude = baseLat + kotlin.math.sin(i * 0.001) * 0.02,
                    longitude = baseLon + i * 0.0001,
                    speedKmh = 30f + (i % 15) * 2f,
                    eventCause = null,
                    provider = "gps",
                    horizontalAccuracyMeters = 4f,
                    speedValid = true,
                    sourceType = "LOCATION"
                )
            )
        }

        for (i in 0 until def.sensorCount) {
            val t = startMs + (i.toLong() * durationMs) / def.sensorCount
            rows.add(
                TripData(
                    tripId = tripId, timestamp = t,
                    latitude = null, longitude = null, speedKmh = null,
                    accelX = kotlin.math.sin(i * 0.02).toFloat() * 0.2f,
                    accelY = kotlin.math.cos(i * 0.02).toFloat() * 0.15f,
                    accelZ = 9.8f + kotlin.math.sin(i * 0.03).toFloat() * 0.8f,
                    eventCause = null,
                    rawTimestamp = t * 1_000_000L + i * 20_000L,
                    sensorType = android.hardware.Sensor.TYPE_ACCELEROMETER,
                    sensorAccuracy = android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
                    sourceType = "ACCELEROMETER"
                )
            )
        }

        for ((fraction, cause) in def.eventCauses) {
            val t = startMs + (fraction * durationMs).toLong()
            rows.add(
                TripData(
                    tripId = tripId, timestamp = t,
                    latitude = null, longitude = null, speedKmh = null,
                    eventCause = cause,
                    sourceType = "EVENT"
                )
            )
        }

        return rows
    }

    private fun buildDemoEvents(
        tripId: Long,
        startMs: Long,
        endMs: Long,
        def: DemoTripDef
    ): List<TripEvent> = def.eventCauses.map { (fraction, cause) ->
        val markerTimeMs = startMs + (fraction * (endMs - startMs)).toLong()
        TripEvent(
            eventId = "debug-${UUID.randomUUID()}",
            tripId = tripId,
            markerTimeMs = markerTimeMs,
            provisionalCauseCode = cause,
            primaryCauseCode = cause,
            provenance = EventProvenance.VOICE_RECOGNIZED,
            transcript = "log ${cause.lowercase(Locale.ROOT)}",
            codebookVersion = ResearchCodebook.VERSION,
            createdAt = markerTimeMs
        )
    }
}
