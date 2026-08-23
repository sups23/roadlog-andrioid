package com.example.roadlog

import org.json.JSONObject

/** Android-test-local fixtures; instrumentation tests cannot depend on src/test classes. */
object TestFixtures {
    const val BASE_TIME_MS = 1_721_200_000_000L
    const val HOUR_MS = 3_600_000L

    fun tripA() = Trip(
        startTimeMs = BASE_TIME_MS,
        endTimeMs = BASE_TIME_MS + HOUR_MS,
        distanceMeters = 12_300.0,
        eventCount = 3,
        gpsPointCount = 100,
        accelPointCount = 200,
        causeBreakdown = JSONObject().put("SIG", 1).toString(),
        createdAt = BASE_TIME_MS
    )

    fun tripB() = Trip(
        startTimeMs = BASE_TIME_MS + HOUR_MS / 2,
        endTimeMs = BASE_TIME_MS + HOUR_MS + HOUR_MS / 2,
        distanceMeters = 8_700.0,
        eventCount = 2,
        gpsPointCount = 80,
        accelPointCount = 160,
        causeBreakdown = JSONObject().put("BUS", 1).toString(),
        createdAt = BASE_TIME_MS + HOUR_MS / 2
    )

    fun gpsPointsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { index ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (index + 1) * stepMs,
                latitude = 27.7 + index * 0.00001,
                longitude = 85.3 + index * 0.00001,
                speedKmh = 30f,
                eventCause = null,
                provider = "gps",
                horizontalAccuracyMeters = 4f,
                sourceType = "LOCATION"
            )
        }
    }

    fun eventRowsForTrip(tripId: Long, startMs: Long, endMs: Long): List<TripData> {
        val stepMs = (endMs - startMs) / 4
        return listOf("SIG", "QUE", "BUS", "RDS").mapIndexed { index, cause ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (index + 1) * stepMs,
                latitude = 27.7,
                longitude = 85.3,
                speedKmh = 5f,
                eventCause = cause,
                sourceType = "EVENT"
            )
        }
    }

    fun accelRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { index ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (index + 1) * stepMs,
                latitude = null,
                longitude = null,
                speedKmh = null,
                accelX = 0.1f,
                accelY = 0.2f,
                accelZ = 9.8f,
                eventCause = null,
                rawTimestamp = (startMs + (index + 1) * stepMs) * 1_000_000L,
                sourceType = "ACCELEROMETER"
            )
        }
    }

    fun gyroRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> =
        accelRowsForTrip(tripId, startMs, endMs, count).map { it.copy(accelX = null, accelY = null, accelZ = null, gyroX = 0.1f, gyroY = 0.1f, gyroZ = 0.1f, sourceType = "GYROSCOPE") }

    fun rotationRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> =
        accelRowsForTrip(tripId, startMs, endMs, count).map { it.copy(accelX = null, accelY = null, accelZ = null, rotX = 0f, rotY = 0f, rotZ = 0f, rotW = 1f, sourceType = "ROTATION") }

    fun allRowsForTrip(tripId: Long, startMs: Long, endMs: Long): List<TripData> =
        gpsPointsForTrip(tripId, startMs, endMs, 100) +
            eventRowsForTrip(tripId, startMs, endMs) +
            accelRowsForTrip(tripId, startMs, endMs, 200) +
            gyroRowsForTrip(tripId, startMs, endMs, 200) +
            rotationRowsForTrip(tripId, startMs, endMs, 200)

    fun photosForTrip(
        tripId: Long,
        startMs: Long,
        endMs: Long,
        count: Int,
        photoDir: String
    ): List<TripPhoto> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { index ->
            TripPhoto(
                tripId = tripId,
                timestamp = startMs + (index + 1) * stepMs,
                latitude = 27.7,
                longitude = 85.3,
                filePath = "$photoDir/photo_${tripId}_$index.jpg"
            )
        }
    }

    fun largeTrip() = tripA().copy(
        startTimeMs = BASE_TIME_MS - 7 * 24 * HOUR_MS,
        endTimeMs = BASE_TIME_MS - 7 * 24 * HOUR_MS + 2 * HOUR_MS
    )

    fun largeGpsRows(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> =
        gpsPointsForTrip(tripId, startMs, endMs, count)

    fun generateJpegBytes(): ByteArray = JpegGenerator.bytes()
}
