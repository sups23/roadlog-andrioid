package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioEvidenceTest {
    @Test
    fun `encoder initialization failure is never complete`() {
        val evidence = AudioEvidence.inventory(
            audio(
                id = "encoder-init",
                status = TripAudioStatus.FAILED,
                reason = TripAudioFailureType.reason(
                    TripAudioFailureType.ENCODER_INITIALIZATION,
                    "AAC encoder unavailable"
                )
            ),
            filePresent = false,
            fileUsable = false
        )

        assertEquals(TripAudioStatus.FAILED, evidence.finalAudioStatus)
        assertTrue(TripAudioFailureType.ENCODER_INITIALIZATION in evidence.failureTypes)
        assertTrue(evidence.reason!!.contains("AAC encoder unavailable"))
    }

    @Test
    fun `archival frame processing failure is persisted without stopping unrelated collection`() {
        val evidence = AudioEvidence.inventory(
            audio(
                id = "frame-processing",
                status = TripAudioStatus.FAILED,
                reason = TripAudioFailureType.reason(
                    TripAudioFailureType.FRAME_PROCESSING,
                    "input buffer rejected"
                )
            ),
            filePresent = true,
            fileUsable = true
        )

        assertEquals(TripAudioStatus.FAILED, evidence.finalAudioStatus)
        assertTrue(TripAudioFailureType.FRAME_PROCESSING in evidence.failureTypes)
        assertTrue(evidence.completeness != TripAudioCompleteness.PRESENT_COMPLETE)
    }

    @Test
    fun `segment finalization failure is distinct from frame processing`() {
        val evidence = AudioEvidence.inventory(
            audio(
                id = "segment-finalization",
                status = TripAudioStatus.FAILED,
                reason = TripAudioFailureType.reason(
                    TripAudioFailureType.SEGMENT_FINALIZATION,
                    "EOS was not drained"
                )
            ),
            filePresent = true,
            fileUsable = true
        )

        assertEquals(TripAudioStatus.FAILED, evidence.finalAudioStatus)
        assertEquals(
            listOf(TripAudioFailureType.SEGMENT_FINALIZATION),
            evidence.failureTypes
        )
    }

    @Test
    fun `service interruption remains interrupted with a specific reason`() {
        val evidence = AudioEvidence.inventory(
            audio(
                id = "service-interruption",
                status = TripAudioStatus.INTERRUPTED,
                reason = TripAudioFailureType.reason(
                    TripAudioFailureType.INTERRUPTION,
                    "SERVICE_DESTROYED"
                )
            ),
            filePresent = true,
            fileUsable = true
        )

        assertEquals(TripAudioStatus.INTERRUPTED, evidence.finalAudioStatus)
        assertTrue(evidence.reason!!.contains("SERVICE_DESTROYED"))
        assertEquals(TripAudioCompleteness.PRESENT_INCOMPLETE, evidence.completeness)
    }

    @Test
    fun `missing expected file reconciles a persisted complete status`() {
        val evidence = AudioEvidence.inventory(
            audio(id = "missing-complete", status = TripAudioStatus.COMPLETE),
            filePresent = false,
            fileUsable = false
        )

        assertEquals(TripAudioStatus.FAILED, evidence.finalAudioStatus)
        assertEquals(TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE, evidence.completeness)
        assertTrue(evidence.reason!!.contains("expected audio file is not present"))
        val audit = AudioEvidence.auditRevision(evidence, originalStatus = TripAudioStatus.COMPLETE)
        assertEquals(TripAudioStatus.COMPLETE, audit.originalValue)
        assertTrue(audit.currentValue!!.contains("MISSING_EXPECTED_AUDIO_FILE"))
    }

    @Test
    fun `audio failures are classified in inventory and trip quality`() {
        val initialization = audio(
            id = "init",
            status = TripAudioStatus.FAILED,
            reason = TripAudioFailureType.reason(
                TripAudioFailureType.ENCODER_INITIALIZATION,
                "codec unavailable"
            )
        )
        val frame = audio(
            id = "frame",
            status = TripAudioStatus.FAILED,
            reason = TripAudioFailureType.reason(
                TripAudioFailureType.FRAME_PROCESSING,
                "input buffer rejected"
            )
        )
        val finalization = audio(
            id = "finalization",
            status = TripAudioStatus.FAILED,
            reason = TripAudioFailureType.reason(
                TripAudioFailureType.SEGMENT_FINALIZATION,
                "EOS was not drained"
            )
        )
        val interrupted = audio(
            id = "interrupted",
            status = TripAudioStatus.INTERRUPTED,
            reason = TripAudioFailureType.reason(
                TripAudioFailureType.INTERRUPTION,
                "SERVICE_DESTROYED"
            )
        )
        val missing = audio(id = "missing", status = TripAudioStatus.COMPLETE)
        val complete = audio(id = "complete", status = TripAudioStatus.COMPLETE)

        val summary = AudioEvidence.summarizeInventory(
            listOf(
                AudioEvidence.inventory(initialization, filePresent = false, fileUsable = false),
                AudioEvidence.inventory(frame, filePresent = true, fileUsable = true),
                AudioEvidence.inventory(finalization, filePresent = true, fileUsable = true),
                AudioEvidence.inventory(interrupted, filePresent = true, fileUsable = true),
                AudioEvidence.inventory(missing, filePresent = false, fileUsable = false),
                AudioEvidence.inventory(complete, filePresent = true, fileUsable = true)
            )
        )

        assertEquals(6, summary.segmentCount)
        assertEquals(1, summary.completeSegmentCount)
        assertEquals(5, summary.incompleteSegmentCount)
        assertEquals(5, summary.failureCount)
        assertEquals(2, summary.missingFileCount)
        assertEquals(1, summary.failureTypeCounts[TripAudioFailureType.ENCODER_INITIALIZATION])
        assertEquals(1, summary.failureTypeCounts[TripAudioFailureType.FRAME_PROCESSING])
        assertEquals(1, summary.failureTypeCounts[TripAudioFailureType.SEGMENT_FINALIZATION])
        assertEquals(1, summary.failureTypeCounts[TripAudioFailureType.INTERRUPTION])
        assertEquals(2, summary.failureTypeCounts[TripAudioFailureType.MISSING_FILE])
        assertTrue(summary.warningMessages.all { "status=" in it && "reason=" in it })
        assertEquals(
            TripAudioStatus.INTERRUPTED,
            AudioEvidence.inventory(interrupted, filePresent = true).finalAudioStatus
        )
        assertEquals(
            TripAudioStatus.FAILED,
            AudioEvidence.inventory(interrupted, filePresent = false).finalAudioStatus
        )
        assertEquals(
            TripAudioStatus.FAILED,
            AudioEvidence.inventory(
                audio(id = "pending", status = TripAudioStatus.PENDING),
                filePresent = true
            ).finalAudioStatus
        )
    }

    @Test
    fun `audit and audio inventory export retain failure fields`() {
        val audio = audio(
            id = "frame-audit",
            status = TripAudioStatus.FAILED,
            reason = TripAudioFailureType.reason(
                TripAudioFailureType.FRAME_PROCESSING,
                "encoder rejected frame"
            )
        )
        val evidence = AudioEvidence.inventory(audio, filePresent = true, fileUsable = true)
        val audit = AudioEvidence.auditRevision(evidence, revisionTimeMs = 1234L)
        val auditValue = audit.currentValue!!
        val exportFields = ResearchExporter.audioInventoryFields(audio, "trip-uuid", evidence)

        assertTrue(auditValue.contains("\"failure_type\":\"${TripAudioFailureType.FRAME_PROCESSING}\""))
        assertEquals(TripAudioStatus.FAILED, audit.originalValue)
        assertTrue(auditValue.contains("\"final_audio_status\":\"${TripAudioStatus.FAILED}\""))
        assertTrue(auditValue.contains("encoder rejected frame"))
        assertEquals(TripAudioFailureType.FRAME_PROCESSING, exportFields[20])
        assertEquals(TripAudioStatus.FAILED, exportFields[18])
        assertEquals(TripAudioCompleteness.PRESENT_INCOMPLETE, exportFields[21])
        assertEquals(true, exportFields[22])
        assertEquals(true, exportFields[23])
    }

    private fun audio(id: String, status: String, reason: String? = null): TripAudio = TripAudio(
        audioId = id,
        tripId = 1L,
        startTimeMs = 1000L,
        endTimeMs = 2000L,
        segmentSequence = 0,
        filePath = "/unavailable/$id.m4a",
        status = status,
        interruptionReason = reason
    )
}
