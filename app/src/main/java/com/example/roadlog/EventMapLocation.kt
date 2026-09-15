package com.example.roadlog

import kotlin.math.abs

internal enum class EventMapLocationSource {
    RECORDED,
    NEAREST_GPS
}

internal data class EventMapLocation(
    val latitude: Double,
    val longitude: Double,
    val source: EventMapLocationSource
)

internal object EventMapLocationResolver {
    fun resolve(event: TripEvent, gpsData: List<TripData>): EventMapLocation? {
        if (isValidCoordinate(event.experiencedLatitude, event.experiencedLongitude)) {
            return EventMapLocation(
                latitude = event.experiencedLatitude!!,
                longitude = event.experiencedLongitude!!,
                source = EventMapLocationSource.RECORDED
            )
        }

        return gpsData
            .asSequence()
            .filter { isValidCoordinate(it.latitude, it.longitude) }
            .minWithOrNull(
                compareBy<TripData> {
                    abs(it.timestamp - event.markerTimeMs)
                }.thenBy { it.timestamp }.thenBy { it.id }
            )
            ?.let { point ->
                EventMapLocation(
                    latitude = point.latitude!!,
                    longitude = point.longitude!!,
                    source = EventMapLocationSource.NEAREST_GPS
                )
            }
    }

    private fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean {
        return latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
    }
}
