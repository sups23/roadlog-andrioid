package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMapTimelineTest {
    @Test
    fun `player offset maps to trip timestamp`() {
        val segment = audioSegment(startTimeMs = 10_000L)
        val boundedSegment = audioSegment(startTimeMs = 10_000L, endTimeMs = 12_000L)

        assertEquals(12_500L, AudioPlaybackTimeline.tripTimeMs(segment, 2_500L))
        assertEquals(10_000L, AudioPlaybackTimeline.tripTimeMs(segment, -1L))
        assertEquals(12_000L, AudioPlaybackTimeline.tripTimeMs(boundedSegment, 5_000L))
    }

    @Test
    fun `GPS position is interpolated between surrounding samples`() {
        val gps = listOf(
            gpsPoint(timestamp = 1_000L, latitude = 27.70, longitude = 85.30),
            gpsPoint(timestamp = 3_000L, latitude = 27.72, longitude = 85.34)
        )

        val position = requireNotNull(PlaybackMapPositionResolver.resolve(gps, 2_000L))
        assertEquals(27.71, position.latitude, 0.000001)
        assertEquals(85.32, position.longitude, 0.000001)
    }

    @Test
    fun `GPS position clamps to route endpoints outside the route`() {
        val gps = listOf(
            gpsPoint(timestamp = 1_000L, latitude = 27.70, longitude = 85.30),
            gpsPoint(timestamp = 3_000L, latitude = 27.72, longitude = 85.34)
        )

        val before = requireNotNull(PlaybackMapPositionResolver.resolve(gps, 0L))
        val after = requireNotNull(PlaybackMapPositionResolver.resolve(gps, 5_000L))
        assertEquals(27.70, before.latitude, 0.0)
        assertEquals(85.34, after.longitude, 0.0)
    }

    @Test
    fun `event seek starts five seconds before event within segment`() {
        val segment = audioSegment(startTimeMs = 10_000L)

        assertEquals(
            5_000L,
            AudioPlaybackTimeline.seekPositionMs(segment, eventTimeMs = 20_000L)
        )
    }

    @Test
    fun `event seek clamps to segment start and duration`() {
        val segment = audioSegment(startTimeMs = 10_000L, endTimeMs = 20_000L)

        assertEquals(0L, AudioPlaybackTimeline.seekPositionMs(segment, eventTimeMs = 11_000L))
        assertEquals(
            9_999L,
            AudioPlaybackTimeline.seekPositionMs(
                segment,
                eventTimeMs = 30_000L,
                playerDurationMs = 9_999L
            )
        )
    }

    @Test
    fun `segment gaps are reported and event segment selection prefers containing segment`() {
        val first = audioSegment(startTimeMs = 0L, endTimeMs = 10_000L)
        val second = audioSegment(startTimeMs = 15_000L, endTimeMs = 20_000L)

        assertEquals(5_000L, AudioPlaybackTimeline.gapBefore(first, second))
        assertEquals(
            1,
            requireNotNull(AudioPlaybackTimeline.findSegmentIndex(17_000L, listOf(first, second)) { true })
        )
        assertNull(AudioPlaybackTimeline.gapBefore(null, second))
    }

    private fun audioSegment(startTimeMs: Long, endTimeMs: Long? = null) = TripAudio(
        audioId = "audio-$startTimeMs",
        tripId = 1L,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        filePath = "unused.m4a"
    )

    private fun gpsPoint(timestamp: Long, latitude: Double, longitude: Double) = TripData(
        id = timestamp,
        tripId = 1L,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude
    )
}
