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
    const val EXPORT_FORMAT_VERSION = "2"
    const val PROTOCOL_VERSION = "1"
    const val SENSOR_PROFILE_VERSION = "1"
    const val ROOM_SCHEMA_VERSION = 8
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
