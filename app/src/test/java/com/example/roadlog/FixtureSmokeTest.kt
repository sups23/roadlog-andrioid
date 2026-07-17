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
}
