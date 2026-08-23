package com.example.roadlog

import java.util.UUID

/**
 * Typed data classes for in-memory buffers and CSV export.
 */

data class GpsPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float?,
    val sourceElapsedRealtimeNanos: Long? = null,
    val sourceEpochTimeMs: Long? = null,
    val callbackTimeMs: Long? = null,
    val provider: String? = null,
    val horizontalAccuracyMeters: Float? = null,
    val speedValid: Boolean? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val altitudeMeters: Double? = null
)

data class AccelPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val callbackTimeMs: Long? = null,
    val accuracy: Int? = null,
    val sensorType: Int? = null
)

data class GyroPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val callbackTimeMs: Long? = null,
    val accuracy: Int? = null,
    val sensorType: Int? = null
)

data class RotationPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float,
    val callbackTimeMs: Long? = null,
    val accuracy: Int? = null,
    val sensorType: Int? = null
)

data class DelayEvent(
    val timestamp: Long,
    val causeCode: String,
    val eventId: String = UUID.randomUUID().toString(),
    val elapsedRealtimeNanos: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Float? = null,
    val locationAccuracyMeters: Float? = null,
    val locationProvider: String? = null,
    val locationFixTimeMs: Long? = null,
    val locationFixElapsedRealtimeNanos: Long? = null,
    val speedValid: Boolean? = null,
    val provenance: String = EventProvenance.MANUAL_MARKER,
    val transcript: String? = null,
    val recognitionConfidence: Float? = null
)
