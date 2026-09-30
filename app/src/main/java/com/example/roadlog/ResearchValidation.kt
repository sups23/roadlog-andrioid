package com.example.roadlog

import java.util.Locale

object ResearchCodebook {
    const val VERSION = ResearchVersions.CODEBOOK_VERSION
    const val LEGACY_V3_VERSION = "3"
    const val UNKNOWN_CODE = "UNKNOWN"

    val v3PrimaryCodes: Set<String> = setOf(
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

    private val v4AdditionalCodes = setOf(
        "SLOW_LEAD_VEHICLE",
        "MERGING",
        "LEAD_TURN",
        "CROSSING_TURN",
        "PARKED_BIKE",
        "PARKED_CAR",
        "DELIVERY_STOP",
        "SPEED_BREAKER"
    )
    val v4ResidualCodes: Set<String> = setOf("TURNING", "FRICTION")

    val primaryCodes: Set<String> = v3PrimaryCodes + v4AdditionalCodes

    /** Codes valid for the given historical codebook, or null when its meaning is unknown. */
    fun primaryCodesForVersion(version: String?): Set<String>? = when (version) {
        LEGACY_V3_VERSION -> v3PrimaryCodes
        VERSION -> primaryCodes
        else -> null
    }

    /** Precise v4 definitions for the new and explicitly bounded residual causes. */
    val v4Definitions: Map<String, String> = mapOf(
        "SLOW_LEAD_VEHICLE" to "One identifiable vehicle ahead directly constrains movement and cannot be passed; do not infer why it is slow.",
        "MERGING" to "Another vehicle enters the rider’s travel path and directly causes slowing or yielding.",
        "LEAD_TURN" to "A vehicle ahead turns out of the rider’s lane into a side road and directly slows the rider.",
        "CROSSING_TURN" to "An oncoming vehicle turns across the rider’s path and directly causes slowing or yielding.",
        "PARKED_BIKE" to "A parked motorcycle or scooter directly obstructs usable road space.",
        "PARKED_CAR" to "A parked car directly obstructs usable road space.",
        "DELIVERY_STOP" to "A vehicle stopped for evidenced delivery, collection, loading, or unloading directly obstructs movement; do not infer purpose from vehicle type or mere presence.",
        "SPEED_BREAKER" to "Crossing a speed breaker is linked to a slowdown satisfying the study’s validated episode rule; ordinary manoeuvring alone is not a validated event.",
        "TURNING" to "An observed other vehicle’s turn or U-turn directly impedes the rider, but does not enter the rider’s path as MERGING, turn out of the lane ahead as LEAD_TURN, or cross the path from oncoming traffic as CROSSING_TURN. The rider’s own planned turn is excluded.",
        "FRICTION" to "An observed lateral road-space obstruction directly impedes movement and cannot be assigned to a more specific cause. Vehicle interactions and road-surface defects are excluded; mere presence is insufficient."
    )
    val v3ResidualDefinitions: Map<String, String> = mapOf(
        "TURNING" to "Legacy v3 voice category for generic ‘turning’, ‘turning vehicle’, and ‘u turn’ commands. Do not reinterpret it as the bounded v4 residual.",
        "FRICTION" to "Legacy v3 voice category that included ‘friction’, ‘parked car’, and ‘side friction’ commands. Do not reinterpret it as the bounded v4 residual."
    )

    val trafficStates: Set<String> = setOf("LIGHT", "MODERATE", "DENSE_MOVING", "QUEUED")

    fun isPrimaryCodeValid(code: String): Boolean = code in primaryCodes

    fun isPrimaryCodeValid(code: String, version: String?): Boolean =
        primaryCodesForVersion(version)?.contains(code) == true
}

object CauseTaxonomyVersions {
    private val knownVersions = setOf(ResearchCodebook.LEGACY_V3_VERSION, ResearchCodebook.VERSION)

    fun isKnown(version: String?): Boolean = version != null && version in knownVersions

    /**
     * Trip and archived-config metadata describe the captured provisional label. If they
     * conflict, keep provenance unknown instead of choosing one. Event metadata is the
     * fallback for unreviewed historical rows without trip/config metadata. When
     * annotations exist, older builds may have overwritten the event's capture version.
     */
    fun provisionalVersion(
        tripVersion: String?,
        configVersion: String?,
        eventVersion: String?,
        hasAnnotationHistory: Boolean = false
    ): String? {
        val recordedEvidence = listOfNotNull(tripVersion, configVersion)
        if (recordedEvidence.any { !isKnown(it) }) return null
        val tripEvidence = recordedEvidence.distinct()
        if (tripEvidence.size > 1) return null
        return tripEvidence.singleOrNull()
            ?: if (hasAnnotationHistory) null else eventVersion?.takeIf(::isKnown)
    }

    /** A present annotation revision is authoritative, even if its version is unknown. */
    fun currentPrimaryVersion(
        hasAnnotation: Boolean,
        annotationVersion: String?,
        provisionalVersion: String?
    ): String? = if (hasAnnotation) annotationVersion?.takeIf(::isKnown) else provisionalVersion
}

data class CauseReviewChoice(val code: String, val codebookVersion: String) {
    val label: String get() = CauseDisplay.versioned(code, codebookVersion)

    override fun toString(): String = label
}

object CauseDisplay {
    fun name(code: String): String = when (code) {
        ResearchCodebook.UNKNOWN_CODE -> "UNKNOWN"
        "SLOW_LEAD_VEHICLE" -> "Slow lead vehicle"
        "MERGING" -> "Merging"
        "LEAD_TURN" -> "Lead turn"
        "CROSSING_TURN" -> "Crossing turn"
        "PARKED_BIKE" -> "Parked bike"
        "PARKED_CAR" -> "Parked car"
        "DELIVERY_STOP" -> "Delivery stop"
        "SPEED_BREAKER" -> "Speed breaker"
        else -> code
    }

    fun versioned(code: String, version: String?): String {
        val versionText = when {
            version == null -> "version uncertain"
            code in ResearchCodebook.v4ResidualCodes -> residualVersion(version)
            else -> "v$version"
        }
        return "${name(code)} ($versionText)"
    }

    fun residualVersion(version: String): String = when (version) {
        ResearchCodebook.LEGACY_V3_VERSION -> "v3 legacy"
        ResearchCodebook.VERSION -> "v$version residual"
        else -> "version uncertain"
    }
}

object CauseReviewChoices {
    /** Historical v3 choices remain available for v3 trips; v4 is an explicit reclassification. */
    fun forVersions(
        captureVersion: String?,
        existingAnnotationVersion: String?
    ): List<CauseReviewChoice> {
        val versions = when {
            captureVersion == ResearchCodebook.VERSION ->
                listOf(ResearchCodebook.VERSION)
            captureVersion == ResearchCodebook.LEGACY_V3_VERSION ||
                existingAnnotationVersion == ResearchCodebook.LEGACY_V3_VERSION ||
                captureVersion == null ->
                listOf(ResearchCodebook.LEGACY_V3_VERSION, ResearchCodebook.VERSION)
            else -> listOf(ResearchCodebook.VERSION)
        }
        return versions.flatMap { version ->
            ResearchCodebook.primaryCodesForVersion(version).orEmpty().sorted().map { code ->
                CauseReviewChoice(code, version)
            }
        }
    }
}

object ResearchVersions {
    const val CODEBOOK_VERSION = "4"
    const val EXPORT_FORMAT_VERSION = "5"
    const val PROTOCOL_VERSION = "3"
    const val SENSOR_PROFILE_VERSION = "1"
    const val ROOM_SCHEMA_VERSION = 9
    const val LEGACY_MIGRATED_CODEBOOK_VERSION = "3"
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

    /** Canonicalizes old aliases while preserving valid v3/v4 codes unknown to the old alias table. */
    fun toCanonicalOrKnownOrUnknown(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return toCanonical(raw)
            ?: raw.uppercase(Locale.ROOT).takeIf { it in ResearchCodebook.primaryCodes }
            ?: ResearchCodebook.UNKNOWN_CODE
    }
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
    val notes: String? = null,
    val codebookVersion: String = ResearchCodebook.VERSION
)

object EventAnnotationValidator {
    fun validate(annotation: EventAnnotation): List<String> {
        val errors = mutableListOf<String>()
        annotation.primaryCauseCode?.let {
            if (!ResearchCodebook.isPrimaryCodeValid(it, annotation.codebookVersion)) {
                errors += "invalid primary cause code for codebook ${annotation.codebookVersion}"
            }
        }
        if (!CauseTaxonomyVersions.isKnown(annotation.codebookVersion)) {
            errors += "unknown cause codebook version"
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
