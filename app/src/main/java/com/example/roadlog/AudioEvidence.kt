package com.example.roadlog

import java.io.File

object TripAudioFailureType {
    const val ENCODER_INITIALIZATION = "ENCODER_INITIALIZATION_FAILURE"
    const val FRAME_PROCESSING = "FRAME_PROCESSING_FAILURE"
    const val SEGMENT_FINALIZATION = "SEGMENT_FINALIZATION_FAILURE"
    const val MISSING_FILE = "MISSING_EXPECTED_AUDIO_FILE"
    const val INTERRUPTION = "AUDIO_INTERRUPTION"
    const val UNKNOWN = "AUDIO_FAILURE"

    val known = listOf(
        ENCODER_INITIALIZATION,
        FRAME_PROCESSING,
        SEGMENT_FINALIZATION,
        MISSING_FILE,
        INTERRUPTION,
        UNKNOWN
    )

    fun reason(type: String, detail: String?): String {
        return "$type: ${detail?.takeIf { it.isNotBlank() } ?: "unspecified"}"
    }

    fun appendReason(existing: String?, type: String, detail: String?): String {
        if (existing?.contains(type) == true) return existing
        val addition = reason(type, detail)
        return if (existing.isNullOrBlank()) addition else "$existing; $addition"
    }

    fun typesFromReason(reason: String?): List<String> {
        return known.filter { reason?.contains(it) == true }
    }
}

object TripAudioCompleteness {
    const val PRESENT_COMPLETE = "PRESENT_COMPLETE"
    const val PRESENT_INCOMPLETE = "PRESENT_INCOMPLETE"
    const val MISSING_EXPECTED_AUDIO_FILE = "MISSING_EXPECTED_AUDIO_FILE"
}

data class AudioInventoryEvidence(
    val audio: TripAudio,
    val filePresent: Boolean,
    val fileUsable: Boolean,
    val failureTypes: List<String>,
    val reason: String?,
    val completeness: String
) {
    val finalAudioStatus: String
        get() = AudioEvidence.finalStatus(audio.status, failureTypes)
}

data class AudioQualitySummary(
    val status: String,
    val segmentCount: Int,
    val completeSegmentCount: Int,
    val incompleteSegmentCount: Int,
    val failureCount: Int,
    val missingFileCount: Int,
    val interruptionCount: Int,
    val failureTypeCounts: Map<String, Int>,
    val interruptionReasons: List<String>,
    val warningMessages: List<String>
) {
    val completeness: String
        get() = when {
            segmentCount == 0 -> "NO_AUDIO"
            status == TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE ->
                TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE
            incompleteSegmentCount > 0 -> TripAudioCompleteness.PRESENT_INCOMPLETE
            else -> TripAudioCompleteness.PRESENT_COMPLETE
        }
}

object AudioEvidence {
    fun finalStatus(status: String, failureTypes: List<String>): String {
        return when {
            failureTypes.isEmpty() -> status
            status == TripAudioStatus.INTERRUPTED &&
                failureTypes.all { it == TripAudioFailureType.INTERRUPTION } ->
                TripAudioStatus.INTERRUPTED
            else -> TripAudioStatus.FAILED
        }
    }

    fun inventory(audio: TripAudio): AudioInventoryEvidence {
        val file = audio.filePath.takeIf { it.isNotBlank() }?.let(::File)
        val present = file?.isFile == true
        return inventory(
            audio = audio,
            filePresent = present,
            fileUsable = present && (file?.length() ?: 0L) > 0L
        )
    }

    fun inventory(
        audio: TripAudio,
        filePresent: Boolean,
        fileUsable: Boolean = filePresent
    ): AudioInventoryEvidence {
        val types = linkedSetOf<String>()
        types += TripAudioFailureType.typesFromReason(audio.interruptionReason)

        when (audio.status) {
            TripAudioStatus.FAILED -> if (types.isEmpty()) types += TripAudioFailureType.UNKNOWN
            TripAudioStatus.INTERRUPTED -> types += TripAudioFailureType.INTERRUPTION
            TripAudioStatus.PENDING -> types += TripAudioFailureType.SEGMENT_FINALIZATION
        }

        if (!filePresent) {
            types += TripAudioFailureType.MISSING_FILE
        } else if (!fileUsable && audio.status == TripAudioStatus.COMPLETE) {
            types += TripAudioFailureType.SEGMENT_FINALIZATION
        }

        if (!audio.interruptionReason.isNullOrBlank() && types.isEmpty()) {
            types += TripAudioFailureType.UNKNOWN
        }

        val failureTypes = types.toList()
        val reason = normalizedReason(audio, failureTypes, filePresent, fileUsable)
        val complete = audio.status == TripAudioStatus.COMPLETE &&
            fileUsable &&
            failureTypes.isEmpty()
        val completeness = when {
            !filePresent -> TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE
            complete -> TripAudioCompleteness.PRESENT_COMPLETE
            else -> TripAudioCompleteness.PRESENT_INCOMPLETE
        }
        return AudioInventoryEvidence(
            audio = audio,
            filePresent = filePresent,
            fileUsable = fileUsable,
            failureTypes = failureTypes,
            reason = reason,
            completeness = completeness
        )
    }

    fun summarize(audioSegments: List<TripAudio>): AudioQualitySummary {
        return summarizeInventory(audioSegments.map(::inventory))
    }

    fun summarizeInventory(inventories: List<AudioInventoryEvidence>): AudioQualitySummary {
        val completeCount = inventories.count {
            it.completeness == TripAudioCompleteness.PRESENT_COMPLETE
        }
        val incompleteCount = inventories.size - completeCount
        val failures = inventories.filter { it.failureTypes.isNotEmpty() }
        val failureTypeCounts = linkedMapOf<String, Int>()
        failures.flatMap { it.failureTypes }.forEach { type ->
            failureTypeCounts[type] = (failureTypeCounts[type] ?: 0) + 1
        }
        val missingFileCount = inventories.count {
            TripAudioFailureType.MISSING_FILE in it.failureTypes
        }
        val interruptionCount = inventories.count {
            TripAudioFailureType.INTERRUPTION in it.failureTypes
        }
        val status = when {
            inventories.isEmpty() -> "NO_AUDIO"
            missingFileCount > 0 -> TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE
            failures.any { TripAudioFailureType.INTERRUPTION !in it.failureTypes } -> "INCOMPLETE"
            interruptionCount > 0 -> "INTERRUPTED"
            else -> "COMPLETE"
        }
        val warningMessages = failures.flatMap { evidence ->
            evidence.failureTypes.map { type ->
                "audio segment ${evidence.audio.segmentSequence} $type " +
                    "status=${evidence.finalAudioStatus} reason=${evidence.reason ?: "unspecified"} " +
                    "filePresent=${evidence.filePresent}"
            }
        }
        val interruptionReasons = inventories
            .filter { TripAudioFailureType.INTERRUPTION in it.failureTypes }
            .mapNotNull { it.reason }
            .distinct()
        return AudioQualitySummary(
            status = status,
            segmentCount = inventories.size,
            completeSegmentCount = completeCount,
            incompleteSegmentCount = incompleteCount,
            failureCount = failures.size,
            missingFileCount = missingFileCount,
            interruptionCount = interruptionCount,
            failureTypeCounts = failureTypeCounts,
            interruptionReasons = interruptionReasons,
            warningMessages = warningMessages
        )
    }

    fun normalizedReason(
        evidence: AudioInventoryEvidence
    ): String? {
        return normalizedReason(
            audio = evidence.audio,
            failureTypes = evidence.failureTypes,
            filePresent = evidence.filePresent,
            fileUsable = evidence.fileUsable
        )
    }

    fun auditRevision(
        evidence: AudioInventoryEvidence,
        revisionTimeMs: Long = System.currentTimeMillis(),
        originalStatus: String = evidence.audio.status
    ): AuditRevision {
        val typeKey = evidence.failureTypes.joinToString("_").ifBlank { TripAudioFailureType.UNKNOWN }
        return AuditRevision(
            revisionId = "audio_failure_${evidence.audio.audioId}_$typeKey",
            tripId = evidence.audio.tripId,
            fieldName = "trip_audio.status",
            originalValue = originalStatus,
            currentValue = auditValue(evidence),
            editor = "LoggerService",
            revisionTimeMs = revisionTimeMs,
            reason = evidence.reason,
            revisionType = "AUDIO_FAILURE"
        )
    }

    fun auditValue(evidence: AudioInventoryEvidence): String {
        val audio = evidence.audio
        return buildString {
            append('{')
            append("\"audio_id\":").append(jsonString(audio.audioId))
            append(",\"failure_type\":").append(jsonString(evidence.failureTypes.joinToString(";")))
            append(",\"failure_types\":")
                .append(evidence.failureTypes.joinToString(",", prefix = "[", postfix = "]", transform = ::jsonString))
            append(",\"final_audio_status\":").append(jsonString(evidence.finalAudioStatus))
            append(",\"interruption_reason\":").append(jsonNullableString(evidence.reason))
            append(",\"audio_completeness\":").append(jsonString(evidence.completeness))
            append(",\"file_present\":").append(evidence.filePresent)
            append(",\"file_usable\":").append(evidence.fileUsable)
            append(",\"file_size_bytes\":").append(audio.fileSizeBytes ?: "null")
            append(",\"sha256\":").append(jsonNullableString(audio.sha256))
            append('}')
        }
    }

    private fun normalizedReason(
        audio: TripAudio,
        failureTypes: List<String>,
        filePresent: Boolean,
        fileUsable: Boolean
    ): String? {
        var reason = audio.interruptionReason?.takeIf { it.isNotBlank() }
        failureTypes.forEach { type ->
            val detail = when (type) {
                TripAudioFailureType.MISSING_FILE -> "expected audio file is not present"
                TripAudioFailureType.SEGMENT_FINALIZATION -> if (!filePresent) {
                    "audio segment was not finalized"
                } else if (!fileUsable) {
                    "audio file is empty"
                } else {
                    "audio segment finalization failed"
                }
                TripAudioFailureType.INTERRUPTION -> "audio capture was interrupted"
                TripAudioFailureType.ENCODER_INITIALIZATION -> "AAC encoder initialization failed"
                TripAudioFailureType.FRAME_PROCESSING -> "archival audio frame processing failed"
                else -> "audio status is ${audio.status}"
            }
            reason = TripAudioFailureType.appendReason(reason, type, detail)
        }
        return reason
    }

    private fun jsonNullableString(value: String?): String = value?.let(::jsonString) ?: "null"

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
