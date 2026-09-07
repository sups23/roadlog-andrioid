package com.example.roadlog

import java.util.Locale

object ResearchCodebook {
    const val VERSION = ResearchVersions.CODEBOOK_VERSION
    const val UNKNOWN_CODE = "UNKNOWN"

    val primaryCodes: Set<String> = setOf(
        "SIGNAL",
        "QUEUE",
        "BUS",
        "PED",
        "ROUGH",
        "CONSTRUCTION",
        "FRICTION",
        "TURNING",
        "MARKET",
        UNKNOWN_CODE
    )

    val trafficStates: Set<String> = setOf("LIGHT", "MODERATE", "DENSE_MOVING", "QUEUED")

    fun isPrimaryCodeValid(code: String): Boolean = code in primaryCodes
}

object ResearchVersions {
    const val CODEBOOK_VERSION = "3"
    const val EXPORT_FORMAT_VERSION = "3"
    const val PROTOCOL_VERSION = "2"
    const val SENSOR_PROFILE_VERSION = "1"
    const val ROOM_SCHEMA_VERSION = 9
}

object ResearchVehicleProfile {
    const val TYPE = "MOTORCYCLE"
    const val MAKE = "Benelli"
    const val MODEL = "TNT 150i"
    const val YEAR = 2020
}

object ResearchTripDefaults {
    const val DRIVER_ID = "DRIVER_01"
    const val VEHICLE_ID = "VEHICLE_01"
}

object TripWeather {
    const val CLEAR = "CLEAR"
    const val CLOUDY = "CLOUDY"
    const val LIGHT_RAIN = "LIGHT_RAIN"
    const val HEAVY_RAIN = "HEAVY_RAIN"
    const val OTHER = "OTHER"
    val values = listOf(CLEAR, CLOUDY, LIGHT_RAIN, HEAVY_RAIN, OTHER)
}

object RoadWetness {
    const val DRY = "DRY"
    const val DAMP = "DAMP"
    const val WET = "WET"
    const val STANDING_WATER = "STANDING_WATER"
    const val UNKNOWN = "UNKNOWN"
    val values = listOf(DRY, DAMP, WET, STANDING_WATER, UNKNOWN)
}

object NonTrafficStop {
    const val NONE = "NONE"
    const val PERSONAL = "PERSONAL"
    const val FUEL_OR_MAINTENANCE = "FUEL_OR_MAINTENANCE"
    const val RESEARCH_SETUP = "RESEARCH_SETUP"
    const val POLICE_OR_ADMINISTRATIVE = "POLICE_OR_ADMINISTRATIVE"
    const val OTHER = "OTHER"
    val values = listOf(NONE, PERSONAL, FUEL_OR_MAINTENANCE, RESEARCH_SETUP, POLICE_OR_ADMINISTRATIVE, OTHER)
}

data class TripContext(
    val driverId: String?,
    val vehicleId: String?,
    val vehicleType: String?,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleYear: Int?,
    val weather: String?,
    val roadWetness: String?,
    val routeDiversion: Boolean = false,
    val nonTrafficStop: String = NonTrafficStop.NONE,
    val contextNote: String? = null
)

object TripContextValidator {
    fun validate(context: TripContext): List<String> = buildList {
        if (context.driverId.isNullOrBlank()) add("driver ID is required")
        if (context.vehicleId.isNullOrBlank()) add("vehicle ID is required")
        if (context.vehicleType.isNullOrBlank()) add("vehicle type is required")
        if (context.vehicleMake.isNullOrBlank()) add("vehicle make is required")
        if (context.vehicleModel.isNullOrBlank()) add("vehicle model is required")
        val year = context.vehicleYear
        if (year == null || year !in 1886..2200) add("vehicle year must be valid")
        if (context.weather !in TripWeather.values) add("weather must be selected")
        if (context.roadWetness !in RoadWetness.values) add("road wetness must be selected")
        if (context.nonTrafficStop !in NonTrafficStop.values) add("non-traffic stop is invalid")
        if (context.contextNote.orEmpty().length > 500) add("context note must be 500 characters or fewer")
    }
}

object TripExclusion {
    const val INCIDENT_OR_BREAKDOWN = "INCIDENT_OR_BREAKDOWN"

    val values = setOf(INCIDENT_OR_BREAKDOWN)
}

object ResearchQualityThresholds {
    const val EVENT_LOCATION_STALE_WARNING_MS = 5_000L
}

/** Maps values written by pre-v8 builds to the v3 canonical analytical vocabulary. */
object CauseCodeMigration {
    val legacyToCanonical: Map<String, String> = mapOf(
        "SIG" to "SIGNAL",
        "SIGNAL" to "SIGNAL",
        "QUE" to "QUEUE",
        "QUEUE" to "QUEUE",
        "BUS" to "BUS",
        "PED" to "PED",
        "PEDESTRIAN" to "PED",
        "RDS" to "ROUGH",
        "ROUGH" to "ROUGH",
        "ROUGHNESS" to "ROUGH",
        "POTHOLE" to "ROUGH",
        "INC" to "CONSTRUCTION",
        "CONSTRUCTION" to "CONSTRUCTION",
        "PRK" to "FRICTION",
        "FRICTION" to "FRICTION",
        "TRN" to "TURNING",
        "TURNING" to "TURNING",
        "ENC" to "MARKET",
        "MARKET" to "MARKET",
        "UNK" to ResearchCodebook.UNKNOWN_CODE,
        "UNCLASSIFIED" to ResearchCodebook.UNKNOWN_CODE,
        "UNKNOWN" to ResearchCodebook.UNKNOWN_CODE
    )

    fun toCanonical(value: String?): String? {
        val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
        return legacyToCanonical[normalized]
    }

    fun toCanonicalOrUnknown(value: String?): String =
        toCanonical(value) ?: ResearchCodebook.UNKNOWN_CODE
}

object ResearchDirection {
    const val A_TO_B = "A_TO_B"
    const val B_TO_A = "B_TO_A"

    val values = setOf(A_TO_B, B_TO_A)
}

object ObservationPeriod {
    const val MORNING = "MORNING"
    const val OFF_PEAK = "OFF_PEAK"
    const val EVENING = "EVENING"

    val values = setOf(MORNING, OFF_PEAK, EVENING)
}

object ResearchTime {
    const val KATHMANDU_ZONE_ID = "Asia/Kathmandu"
}

object ResearchStudy {
    const val SESSION_ID = "STUDY_SESSION"
    const val CORRIDOR_ID = "STUDY_CORRIDOR"
}

object TripQaStatus {
    const val UNREVIEWED = "UNREVIEWED"
    const val VALID = "VALID"
    const val VALID_WITH_WARNINGS = "VALID_WITH_WARNINGS"
    const val INVALID = "INVALID"

    val values = setOf(UNREVIEWED, VALID, VALID_WITH_WARNINGS, INVALID)
}

object EventProvenance {
    const val VOICE_RECOGNIZED = "VOICE_RECOGNIZED"
    const val LEGACY_IMPORTED = "LEGACY_IMPORTED"

    val values = setOf(VOICE_RECOGNIZED, LEGACY_IMPORTED)
}

object EventStatus {
    const val PENDING = "PENDING"
    const val ANNOTATED = "ANNOTATED"
    const val UNUSABLE = "UNUSABLE"
}

object TripAudioStatus {
    const val PENDING = "PENDING"
    const val COMPLETE = "COMPLETE"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
}

data class TripStartConfiguration(
    val direction: String?,
    val observationPeriod: String?
)

object TripStartValidator {
    fun validate(configuration: TripStartConfiguration): List<String> {
        val errors = mutableListOf<String>()
        if (configuration.direction !in ResearchDirection.values) errors += "direction must be A_TO_B or B_TO_A"
        if (configuration.observationPeriod !in ObservationPeriod.values) {
            errors += "period must be MORNING, OFF_PEAK, or EVENING"
        }
        return errors
    }

    fun requireValid(configuration: TripStartConfiguration) {
        val errors = validate(configuration)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}

data class EventAnnotation(
    val primaryCauseCode: String?,
    val confidenceCode: Int?,
    val trafficState: String?,
    val reviewerId: String? = null,
    val annotationTimestampMs: Long = System.currentTimeMillis(),
    val notes: String? = null
)

object EventAnnotationValidator {
    fun validate(annotation: EventAnnotation): List<String> {
        val errors = mutableListOf<String>()
        annotation.primaryCauseCode?.let {
            if (!ResearchCodebook.isPrimaryCodeValid(it)) errors += "invalid primary cause code"
        }
        annotation.confidenceCode?.let {
            if (it !in 0..3) errors += "confidence code must be 0, 1, 2, or 3"
        }
        annotation.trafficState?.let {
            if (it !in ResearchCodebook.trafficStates) errors += "invalid traffic state"
        }
        return errors
    }

    fun requireValid(annotation: EventAnnotation) {
        val errors = validate(annotation)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
