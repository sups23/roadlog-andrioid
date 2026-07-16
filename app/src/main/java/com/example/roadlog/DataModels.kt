package com.example.roadlog

/**
 * Typed data classes for in-memory buffers and CSV export.
 */

data class GpsPoint(
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float
)

data class AccelPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class GyroPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float
)

data class RotationPoint(
    val timestampNano: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val w: Float
)

data class DelayEvent(
    val timestamp: Long,
    val causeCode: String
)

enum class CauseCode(val displayName: String) {
    SIGNAL("Signal"),
    QUEUE("Queue"),
    BUS("Bus"),
    PEDESTRIAN("Pedestrian"),
    ROUGHNESS("Roughness"),
    POTHOLE("Pothole"),
    SPEED_BREAKER("Speed Breaker"),
    CONSTRUCTION("Construction"),
    FRICTION("Friction"),
    TURNING("Turning"),
    MARKET("Market")
}
