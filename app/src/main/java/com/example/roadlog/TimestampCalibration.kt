package com.example.roadlog

data class ClockAnchor(
    val wallTimeMs: Long,
    val elapsedRealtimeNanos: Long
)

object TimestampCalibration {
    fun elapsedToWallTimeMs(anchor: ClockAnchor, elapsedRealtimeNanos: Long): Long {
        return anchor.wallTimeMs + (elapsedRealtimeNanos - anchor.elapsedRealtimeNanos) / 1_000_000L
    }

    fun wallToElapsedRealtimeNanos(anchor: ClockAnchor, wallTimeMs: Long): Long {
        return anchor.elapsedRealtimeNanos + (wallTimeMs - anchor.wallTimeMs) * 1_000_000L
    }
}
