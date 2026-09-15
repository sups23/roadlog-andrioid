package com.example.roadlog

internal data class PlaybackMapCoordinate(
    val latitude: Double,
    val longitude: Double
)

internal object AudioPlaybackTimeline {
    const val EVENT_SEEK_LEAD_IN_MS = 5_000L

    fun tripTimeMs(segment: TripAudio, playerPositionMs: Long): Long {
        val timestamp = segment.startTimeMs + playerPositionMs.coerceAtLeast(0L)
        val endTimeMs = segment.endTimeMs
        return if (endTimeMs != null && endTimeMs >= segment.startTimeMs) {
            timestamp.coerceAtMost(endTimeMs)
        } else {
            timestamp
        }
    }

    fun seekPositionMs(
        segment: TripAudio,
        eventTimeMs: Long,
        leadInMs: Long = EVENT_SEEK_LEAD_IN_MS,
        playerDurationMs: Long? = null
    ): Long {
        val position = (eventTimeMs - segment.startTimeMs - leadInMs).coerceAtLeast(0L)
        return playerDurationMs?.let { position.coerceAtMost(it.coerceAtLeast(0L)) } ?: position
    }

    fun gapBefore(previous: TripAudio?, current: TripAudio): Long? {
        val previousEnd = previous?.endTimeMs ?: return null
        return (current.startTimeMs - previousEnd).takeIf { it > 0L }
    }

    fun findSegmentIndex(
        eventTimeMs: Long,
        segments: List<TripAudio>,
        isPlayable: (TripAudio) -> Boolean
    ): Int? {
        val playable = segments.withIndex().filter { isPlayable(it.value) }
        if (playable.isEmpty()) return null

        playable.firstOrNull { (_, segment) ->
            val endTimeMs = segment.endTimeMs
            eventTimeMs >= segment.startTimeMs &&
                (endTimeMs == null || eventTimeMs <= endTimeMs)
        }?.let { return it.index }

        return playable.minWithOrNull(
            compareBy<IndexedValue<TripAudio>> {
                distanceToSegment(eventTimeMs, it.value)
            }.thenBy { it.value.startTimeMs }.thenBy { it.index }
        )?.index
    }

    private fun distanceToSegment(eventTimeMs: Long, segment: TripAudio): Long {
        val endTimeMs = segment.endTimeMs
        return when {
            eventTimeMs < segment.startTimeMs -> segment.startTimeMs - eventTimeMs
            endTimeMs != null && eventTimeMs > endTimeMs -> eventTimeMs - endTimeMs
            else -> 0L
        }
    }
}

internal object PlaybackMapPositionResolver {
    /** GPS rows are expected in timestamp order, as returned by the map DAO query. */
    fun resolve(gpsData: List<TripData>, timestampMs: Long): PlaybackMapCoordinate? {
        val validPoints = gpsData.filter { point ->
            val latitude = point.latitude
            val longitude = point.longitude
            latitude != null && longitude != null &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0
        }
        if (validPoints.isEmpty()) return null

        val afterIndex = validPoints.indexOfFirst { it.timestamp >= timestampMs }
        if (afterIndex == 0) return coordinate(validPoints.first())
        if (afterIndex < 0) return coordinate(validPoints.last())

        val before = validPoints[afterIndex - 1]
        val after = validPoints[afterIndex]
        if (after.timestamp == before.timestamp || after.timestamp == timestampMs) {
            return coordinate(after)
        }

        val fraction = (timestampMs - before.timestamp).toDouble() /
            (after.timestamp - before.timestamp).toDouble()
        return PlaybackMapCoordinate(
            latitude = before.latitude!! + (after.latitude!! - before.latitude) * fraction,
            longitude = before.longitude!! + (after.longitude!! - before.longitude) * fraction
        )
    }

    fun nearestTimestampDistance(firstTimestampMs: Long, secondTimestampMs: Long): Long {
        return if (firstTimestampMs >= secondTimestampMs) {
            firstTimestampMs - secondTimestampMs
        } else {
            secondTimestampMs - firstTimestampMs
        }
    }

    private fun coordinate(point: TripData): PlaybackMapCoordinate {
        return PlaybackMapCoordinate(point.latitude!!, point.longitude!!)
    }
}
