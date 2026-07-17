package com.example.roadlog

import org.json.JSONObject

object TestFixtures {

    val BASE_TIME_MS = 1721200000000L
    val HOUR_MS = 3_600_000L

    // --- Trips with overlapping time ranges (for deletion isolation tests) ---

    fun tripA(): Trip = Trip(
        id = 0,
        startTimeMs = BASE_TIME_MS,
        endTimeMs = BASE_TIME_MS + HOUR_MS,
        startNanoTime = 0,
        endNanoTime = 0,
        distanceMeters = 12_300.0,
        eventCount = 3,
        gpsPointCount = 100,
        accelPointCount = 200,
        causeBreakdown = JSONObject().apply {
            put("SIGNAL", 1)
            put("QUEUE", 1)
            put("ROUGHNESS", 1)
        }.toString(),
        createdAt = BASE_TIME_MS
    )

    fun tripB(): Trip = Trip(
        id = 0,
        startTimeMs = BASE_TIME_MS + HOUR_MS / 2,
        endTimeMs = BASE_TIME_MS + HOUR_MS + HOUR_MS / 2,
        startNanoTime = 0,
        endNanoTime = 0,
        distanceMeters = 8_700.0,
        eventCount = 2,
        gpsPointCount = 80,
        accelPointCount = 160,
        causeBreakdown = JSONObject().apply {
            put("BUS", 1)
            put("POTHOLE", 1)
        }.toString(),
        createdAt = BASE_TIME_MS + HOUR_MS / 2
    )

    /** GPS point tile aligned east-west at lat=27.7 */
    fun gpsPointsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        val baseLat = 27.7
        val baseLon = 85.3
        val lonStep = 0.01 / count
        return (0 until count).map { i ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                latitude = baseLat + i * 0.00001,
                longitude = baseLon + i * lonStep,
                speedKmh = 30f + (i % 10) * 2f,
                eventCause = null
            )
        }
    }

    fun eventRowsForTrip(tripId: Long, startMs: Long, endMs: Long): List<TripData> {
        val stepMs = (endMs - startMs) / 4
        val causes = listOf("SIGNAL", "QUEUE", "BUS", "POTHOLE")
        return causes.mapIndexed { i, cause ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                eventCause = cause
            )
        }
    }

    fun accelRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { i ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                accelX = (i % 3 - 1).toFloat() * 0.1f,
                accelY = (i % 5 - 2).toFloat() * 0.2f,
                accelZ = 9.8f + (i % 7 - 3).toFloat() * 0.5f,
                rawTimestamp = (startMs + (i + 1) * stepMs) * 1_000_000L
            )
        }
    }

    fun gyroRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { i ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                gyroX = (i % 4 - 2).toFloat() * 0.05f,
                gyroY = (i % 3 - 1).toFloat() * 0.03f,
                gyroZ = (i % 5 - 2).toFloat() * 0.01f,
                rawTimestamp = (startMs + (i + 1) * stepMs) * 1_000_000L
            )
        }
    }

    fun rotationRowsForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int): List<TripData> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { i ->
            val angle = i * 0.01f
            TripData(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                rotX = kotlin.math.sin(angle) * 0.1f,
                rotY = kotlin.math.cos(angle) * 0.1f,
                rotZ = 0f,
                rotW = 1f,
                rawTimestamp = (startMs + (i + 1) * stepMs) * 1_000_000L
            )
        }
    }

    fun photosForTrip(tripId: Long, startMs: Long, endMs: Long, count: Int, photoDir: String): List<TripPhoto> {
        val stepMs = (endMs - startMs) / (count + 1)
        return (0 until count).map { i ->
            TripPhoto(
                tripId = tripId,
                timestamp = startMs + (i + 1) * stepMs,
                latitude = 27.7 + i * 0.0001,
                longitude = 85.3 + i * 0.0001,
                filePath = "$photoDir/photo_${tripId}_$i.jpg"
            )
        }
    }

    /** All data rows for a complete trip (GPS, events, accel, gyro, rotation). */
    fun allRowsForTrip(tripId: Long, startMs: Long, endMs: Long): List<TripData> {
        return gpsPointsForTrip(tripId, startMs, endMs, 100) +
            eventRowsForTrip(tripId, startMs, endMs) +
            accelRowsForTrip(tripId, startMs, endMs, 200) +
            gyroRowsForTrip(tripId, startMs, endMs, 200) +
            rotationRowsForTrip(tripId, startMs, endMs, 200)
    }

    /** Large dense trace: 10,000 GPS points, 50,000 accel/gyro/rotation rows each. */
    fun largeTrip(): Trip = Trip(
        id = 0,
        startTimeMs = BASE_TIME_MS - 7 * 24 * HOUR_MS,
        endTimeMs = BASE_TIME_MS - 7 * 24 * HOUR_MS + 2 * HOUR_MS,
        startNanoTime = 0,
        endNanoTime = 0,
        distanceMeters = 85_000.0,
        eventCount = 12,
        gpsPointCount = 10_000,
        accelPointCount = 50_000,
        causeBreakdown = JSONObject().apply {
            put("SIGNAL", 3)
            put("QUEUE", 2)
            put("POTHOLE", 2)
            put("ROUGHNESS", 2)
            put("BUS", 1)
            put("TURNING", 1)
            put("MARKET", 1)
        }.toString(),
        createdAt = BASE_TIME_MS - 7 * 24 * HOUR_MS
    )

    fun largeGpsRows(tripId: Long, startMs: Long, endMs: Long, count: Int = 10_000): List<TripData> {
        val stepMs = (endMs - startMs) / count
        return (0 until count).map { i ->
            val t = startMs + i * stepMs
            TripData(
                tripId = tripId,
                timestamp = t,
                latitude = 27.7 + kotlin.math.sin(i * 0.001) * 0.05,
                longitude = 85.3 + i * 0.0002,
                speedKmh = 40f + (i % 15) * 1.5f,
                eventCause = null
            )
        }
    }

    fun largeAccelRows(tripId: Long, startMs: Long, endMs: Long, count: Int = 50_000): List<TripData> {
        val stepNs = ((endMs - startMs) * 1_000_000L) / count
        return (0 until count).map { i ->
            TripData(
                tripId = tripId,
                timestamp = startMs + (i * stepNs) / 1_000_000,
                accelX = kotlin.math.sin(i * 0.02).toFloat() * 0.3f,
                accelY = kotlin.math.cos(i * 0.02).toFloat() * 0.2f,
                accelZ = 9.8f + kotlin.math.sin(i * 0.05).toFloat() * 1.5f,
                rawTimestamp = (startMs * 1_000_000) + i * stepNs
            )
        }
    }

    // --- Minimal valid JPEG bytes (2×2 gray) ---
    private val JPEG_HEADER = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), // SOI + APP0
        0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43, 0x00 // DQT
    )

    private val JPEG_DQT = byteArrayOf(
        0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07,
        0x07, 0x07, 0x09, 0x09, 0x08, 0x0A, 0x0C, 0x14,
        0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13,
        0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A,
        0x1C, 0x1C, 0x20, 0x2E, 0x27, 0x20, 0x22, 0x2C,
        0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C, 0x30,
        0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D,
        0x38, 0x32, 0x3C, 0x2E, 0x33, 0x34, 0x32
    )

    private val JPEG_SOF_BODY = byteArrayOf(
        0x00, 0x08, 0x00, 0x02, 0x00, 0x02, 0x03, 0x01,
        0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01
    )

    private val JPEG_SOS_BODY = byteArrayOf(0x03, 0x01, 0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3F, 0x00)

    fun generateJpegBytes(): ByteArray {
        val sosHeader = byteArrayOf(
            0xFF.toByte(), 0xDA.toByte(),
            ((JPEG_SOS_BODY.size shr 8) and 0xFF).toByte(),
            (JPEG_SOS_BODY.size and 0xFF).toByte()
        )
        val ecs = ByteArray(12) { i ->
            if (i < 6) 0x55 else 0xAA.toByte()
        }
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        return JPEG_HEADER +
            JPEG_DQT +
            byteArrayOf(
                0xFF.toByte(), 0xC0.toByte(),
                ((JPEG_SOF_BODY.size shr 8) and 0xFF).toByte(),
                (JPEG_SOF_BODY.size and 0xFF).toByte()
            ) +
            JPEG_SOF_BODY +
            sosHeader + JPEG_SOS_BODY +
            ecs + eoi
    }
}
