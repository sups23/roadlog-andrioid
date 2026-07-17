package com.example.roadlog

import org.junit.Assert.*
import org.junit.Test

class FixtureSmokeTest {

    @Test
    fun `fixtures produce deterministic data`() {
        val tripA = TestFixtures.tripA()
        val tripB = TestFixtures.tripB()

        assertEquals(3, tripA.eventCount)
        assertEquals(2, tripB.eventCount)
        assertTrue(tripA.startTimeMs < tripB.startTimeMs)
        assertTrue(tripA.endTimeMs > tripB.startTimeMs)
    }

    @Test
    fun `gps points have correct tripId and range`() {
        val rows = TestFixtures.gpsPointsForTrip(42L, 1000L, 5000L, 10)
        assertEquals(10, rows.size)
        rows.forEach { row ->
            assertEquals(42L, row.tripId)
            assertNotNull(row.latitude)
            assertNotNull(row.longitude)
            assertNotNull(row.speedKmh)
            assertNull(row.eventCause)
            assertTrue(row.timestamp in 1001L..5000L)
        }
    }

    @Test
    fun `event rows have cause data`() {
        val rows = TestFixtures.eventRowsForTrip(1L, 0L, 4000L)
        assertEquals(4, rows.size)
        rows.forEach { row ->
            assertNotNull(row.eventCause)
            assertEquals(1L, row.tripId)
        }
    }

    @Test
    fun `accel rows have sensor data`() {
        val rows = TestFixtures.accelRowsForTrip(1L, 0L, 10_000L, 50)
        assertEquals(50, rows.size)
        rows.forEach { row ->
            assertNotNull(row.accelX)
            assertNotNull(row.accelY)
            assertNotNull(row.accelZ)
            assertNotNull(row.rawTimestamp)
            assertNull(row.latitude)
            assertEquals(1L, row.tripId)
        }
    }

    @Test
    fun `jpeg bytes produce valid JPEG header`() {
        val bytes = TestFixtures.generateJpegBytes()
        assertTrue(bytes.isNotEmpty())
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
        assertEquals(0xFF.toByte(), bytes[bytes.size - 2])
        assertEquals(0xD9.toByte(), bytes[bytes.size - 1])
    }

    @Test
    fun `large trace has required count`() {
        val trip = TestFixtures.largeTrip()
        assertEquals(10_000, trip.gpsPointCount)
        assertEquals(50_000, trip.accelPointCount)

        val gps = TestFixtures.largeGpsRows(1L, trip.startTimeMs, trip.endTimeMs)
        assertEquals(10_000, gps.size)

        val accel = TestFixtures.largeAccelRows(1L, trip.startTimeMs, trip.endTimeMs)
        assertEquals(50_000, accel.size)
    }

    @Test
    fun `overlapping trips share timestamp range`() {
        val a = TestFixtures.tripA()
        val b = TestFixtures.tripB()
        assertTrue(b.startTimeMs in a.startTimeMs..a.endTimeMs)
        assertTrue(a.startTimeMs < b.startTimeMs)
        assertTrue(a.endTimeMs < b.endTimeMs)
    }

    @Test
    fun `fixture trips default to completed status`() {
        assertEquals(TripStatus.COMPLETED, TestFixtures.tripA().status)
        assertEquals(TripStatus.COMPLETED, TestFixtures.tripB().status)
        assertEquals(TripStatus.COMPLETED, TestFixtures.largeTrip().status)
    }

    @Test
    fun `trip status constants are distinct`() {
        assertNotEquals(TripStatus.COMPLETED, TripStatus.RECORDING)
        assertNotEquals(TripStatus.COMPLETED, TripStatus.ABANDONED)
        assertNotEquals(TripStatus.RECORDING, TripStatus.ABANDONED)
    }

    @Test
    fun `recording trip can be created with explicit status`() {
        val recording = Trip(
            startTimeMs = 1000L,
            endTimeMs = 0,
            distanceMeters = 0.0,
            eventCount = 0,
            gpsPointCount = 0,
            accelPointCount = 0,
            causeBreakdown = "{}",
            createdAt = 0,
            status = TripStatus.RECORDING
        )
        assertEquals(TripStatus.RECORDING, recording.status)
    }

    @Test
    fun `downsampleToCount returns empty for empty input`() {
        assertEquals(0, downsampleToCount(emptyList<String>(), 10).size)
    }

    @Test
    fun `downsampleToCount returns empty for zero or negative maxCount`() {
        val items = listOf(1, 2, 3, 4, 5)
        assertEquals(0, downsampleToCount(items, 0).size)
        assertEquals(0, downsampleToCount(items, -1).size)
    }

    @Test
    fun `downsampleToCount returns first item for maxCount 1`() {
        val items = listOf(1, 2, 3)
        assertEquals(listOf(1), downsampleToCount(items, 1))
    }

    @Test
    fun `downsampleToCount returns first and last for maxCount 2`() {
        val items = listOf(1, 2, 3, 4, 5)
        assertEquals(listOf(1, 5), downsampleToCount(items, 2))
    }

    @Test
    fun `downsampleToCount returns all items when fewer than maxCount`() {
        val items = listOf(1, 2, 3)
        assertEquals(items, downsampleToCount(items, 10))
    }

    @Test
    fun `downsampleToCount produces evenly spaced output`() {
        val items = (0..99).toList()
        val result = downsampleToCount(items, 10)
        assertEquals(10, result.size)
        assertEquals(0, result.first())
        assertEquals(99, result.last())
    }
}
