package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventMapLocationTest {
    @Test
    fun `recorded event location is preferred`() {
        val event = event(experiencedLatitude = 27.71, experiencedLongitude = 85.32)
        val gps = listOf(gpsPoint(timestamp = 1_000L, latitude = 27.70, longitude = 85.30))

        val location = requireNotNull(EventMapLocationResolver.resolve(event, gps))
        assertEquals(27.71, location.latitude, 0.0)
        assertEquals(85.32, location.longitude, 0.0)
        assertEquals(EventMapLocationSource.RECORDED, location.source)
    }

    @Test
    fun `missing event location uses nearest GPS point`() {
        val event = event(markerTimeMs = 2_000L)
        val gps = listOf(
            gpsPoint(timestamp = 1_000L, latitude = 27.70, longitude = 85.30),
            gpsPoint(timestamp = 2_100L, latitude = 27.71, longitude = 85.31),
            gpsPoint(timestamp = 4_000L, latitude = 27.72, longitude = 85.32)
        )

        val location = requireNotNull(EventMapLocationResolver.resolve(event, gps))
        assertEquals(27.71, location.latitude, 0.0)
        assertEquals(85.31, location.longitude, 0.0)
        assertEquals(EventMapLocationSource.NEAREST_GPS, location.source)
    }

    @Test
    fun `invalid recorded coordinates fall back to nearest GPS point`() {
        val event = event(experiencedLatitude = 91.0, experiencedLongitude = 181.0, markerTimeMs = 2_000L)
        val gps = listOf(gpsPoint(timestamp = 2_000L, latitude = 27.70, longitude = 85.30))

        val location = requireNotNull(EventMapLocationResolver.resolve(event, gps))
        assertEquals(EventMapLocationSource.NEAREST_GPS, location.source)
        assertEquals(27.70, location.latitude, 0.0)
        assertEquals(85.30, location.longitude, 0.0)
    }

    @Test
    fun `event has no map location without valid coordinates or GPS`() {
        val event = event(markerTimeMs = 2_000L)
        val gps = listOf(gpsPoint(timestamp = 2_000L, latitude = null, longitude = null))

        assertNull(EventMapLocationResolver.resolve(event, gps))
    }

    private fun event(
        markerTimeMs: Long = 1_000L,
        experiencedLatitude: Double? = null,
        experiencedLongitude: Double? = null
    ) = TripEvent(
        eventId = "event-1",
        tripId = 1L,
        markerTimeMs = markerTimeMs,
        experiencedLatitude = experiencedLatitude,
        experiencedLongitude = experiencedLongitude
    )

    private fun gpsPoint(timestamp: Long, latitude: Double?, longitude: Double?) = TripData(
        id = timestamp,
        tripId = 1L,
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude
    )
}
