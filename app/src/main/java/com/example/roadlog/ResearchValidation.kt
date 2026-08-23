package com.example.roadlog

object ResearchCodebook {
    const val VERSION = "2"
    const val UNCLASSIFIED_CODE = "UNK"

    val primaryCodes: Set<String> = setOf(
        "SIG", "QUE", "BUS", "PED", "PRK", "TRN", "ENC", "RDS", "INC", UNCLASSIFIED_CODE
    )

    val trafficStates: Set<String> = setOf("LIGHT", "MODERATE", "DENSE_MOVING", "QUEUED")

    fun isPrimaryCodeValid(code: String): Boolean = code in primaryCodes
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
    const val MANUAL_MARKER = "MANUAL_MARKER"
    const val VOICE_RECOGNIZED = "VOICE_RECOGNIZED"
    const val AUTO_DETECTED = "AUTO_DETECTED"
    const val MANUAL_AND_AUTO = "MANUAL_AND_AUTO"
    const val REVIEW_CREATED = "REVIEW_CREATED"

    val values = setOf(MANUAL_MARKER, VOICE_RECOGNIZED, AUTO_DETECTED, MANUAL_AND_AUTO, REVIEW_CREATED)
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
    val secondaryCauseCodes: List<String>,
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
        if (annotation.secondaryCauseCodes.size > 2) errors += "at most two secondary causes are allowed"
        if (annotation.secondaryCauseCodes.distinct().size != annotation.secondaryCauseCodes.size) {
            errors += "secondary causes must be distinct"
        }
        if (annotation.primaryCauseCode != null && annotation.primaryCauseCode in annotation.secondaryCauseCodes) {
            errors += "primary cause cannot also be secondary"
        }
        if (annotation.secondaryCauseCodes.any { !ResearchCodebook.isPrimaryCodeValid(it) }) {
            errors += "invalid secondary cause code"
        }
        if (annotation.primaryCauseCode == ResearchCodebook.UNCLASSIFIED_CODE &&
            annotation.secondaryCauseCodes.any { it != ResearchCodebook.UNCLASSIFIED_CODE }
        ) {
            errors += "UNCLASSIFIED should not have confident secondary causes"
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
