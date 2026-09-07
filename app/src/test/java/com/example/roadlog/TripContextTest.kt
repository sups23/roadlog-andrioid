package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripContextTest {
    private fun validContext() = TripContext(
        driverId = "DRIVER_01",
        vehicleId = "VEHICLE_01",
        vehicleType = ResearchVehicleProfile.TYPE,
        vehicleMake = ResearchVehicleProfile.MAKE,
        vehicleModel = ResearchVehicleProfile.MODEL,
        vehicleYear = ResearchVehicleProfile.YEAR,
        weather = TripWeather.CLEAR,
        roadWetness = RoadWetness.DRY,
        nonTrafficStop = NonTrafficStop.NONE
    )

    @Test
    fun `valid context has no errors`() {
        assertTrue(TripContextValidator.validate(validContext()).isEmpty())
    }

    @Test
    fun `required fields prevent start`() {
        val errors = TripContextValidator.validate(validContext().copy(driverId = "", weather = null))
        assertTrue(errors.any { it.contains("driver ID") })
        assertTrue(errors.any { it.contains("weather") })
    }

    @Test
    fun `controlled values are canonical`() {
        assertEquals(listOf("CLEAR", "CLOUDY", "LIGHT_RAIN", "HEAVY_RAIN", "OTHER"), TripWeather.values)
        assertTrue(TripContextValidator.validate(validContext().copy(weather = "clear")).isNotEmpty())
        assertFalse(TripExclusion.values.contains(TripWeather.OTHER))
        assertFalse(ResearchCodebook.primaryCodes.contains(TripExclusion.INCIDENT_OR_BREAKDOWN))
    }
}
