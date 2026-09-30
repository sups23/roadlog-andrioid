package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class ResearchQualityTest {
    @Test
    fun `stream metrics report gaps duplicates and ordering`() {
        val metrics = QualityMetrics.stream(
            timestampsMs = listOf(1000L, 1100L, 1100L, 900L, 1400L),
            tripStartMs = 1000L,
            tripEndMs = 1500L
        )

        assertEquals(5, metrics.sampleCount)
        assertEquals(1, metrics.duplicateTimestampCount)
        assertEquals(1, metrics.nonMonotonicTimestampCount)
        assertEquals(500L, metrics.longestGapMs)
        assertEquals(0L, metrics.firstSampleOffsetMs)
        assertEquals(-100L, metrics.lastSampleOffsetMs)
    }

    @Test
    fun `stream coverage uses the full trip duration rather than observed span`() {
        val metrics = QualityMetrics.stream(
            timestampsMs = listOf(1000L, 2000L),
            tripStartMs = 1000L,
            tripEndMs = 11_000L,
            expectedFrequencyHz = 1.0
        )

        assertEquals(20.0, metrics.observedCoveragePercent ?: -1.0, 0.001)
    }

    @Test
    fun `event annotation enforces canonical primary and confidence rules`() {
        val errors = EventAnnotationValidator.validate(
            EventAnnotation(
                primaryCauseCode = "SIG",
                confidenceCode = 4,
                trafficState = "UNKNOWN"
            )
        )

        assertTrue(errors.size >= 3)
    }

    @Test
    fun `gps metrics preserve provider and speed provenance`() {
        val rows = listOf(
            TripData(
                tripId = 1L,
                timestamp = 1000L,
                latitude = 1.0,
                longitude = 2.0,
                speedKmh = 20f,
                eventCause = null,
                provider = "gps",
                horizontalAccuracyMeters = 4f,
                speedValid = true
            ),
            TripData(
                tripId = 1L,
                timestamp = 2000L,
                latitude = 1.1,
                longitude = 2.1,
                speedKmh = null,
                eventCause = null,
                provider = "network",
                horizontalAccuracyMeters = 12f,
                speedValid = false
            )
        )

        val metrics = QualityMetrics.gps(rows)
        assertEquals(mapOf("gps" to 1, "network" to 1), metrics.providerCounts)
        assertEquals(1, metrics.invalidSpeedCount)
        assertEquals(0, metrics.unavailableSpeedCount)
        assertEquals(50.0, metrics.belowFiveMetersPercent ?: -1.0, 0.001)
    }

    @Test
    fun `recording state permits completion only during finalization`() {
        assertTrue(RecordingStateMachine.canStart(RecordingState.IDLE))
        assertTrue(!RecordingStateMachine.canStart(RecordingState.RECORDING))
        assertTrue(RecordingStateMachine.canComplete(RecordingState.FINALIZING))
        assertTrue(!RecordingStateMachine.canComplete(RecordingState.RECORDING))
    }

    @Test
    fun `clock calibration converts monotonic sensor time without losing milliseconds`() {
        val anchor = ClockAnchor(wallTimeMs = 1_000_000L, elapsedRealtimeNanos = 8_000_000_000L)
        assertEquals(1_000_123L, TimestampCalibration.elapsedToWallTimeMs(anchor, 8_123_456_789L))
        assertEquals(8_123_000_000L, TimestampCalibration.wallToElapsedRealtimeNanos(anchor, 1_000_123L))
    }

    @Test
    fun `trip start requires canonical direction and period`() {
        val errors = TripStartValidator.validate(
            TripStartConfiguration(
                direction = "inbound",
                observationPeriod = "afternoon"
            )
        )
        assertEquals(2, errors.size)
        assertTrue(TripStartValidator.validate(
            TripStartConfiguration(
                direction = ResearchDirection.A_TO_B,
                observationPeriod = ObservationPeriod.OFF_PEAK
            )
        ).isEmpty())
    }

    @Test
    fun `new trips default to the static study identifiers`() {
        val trip = TestFixtures.tripA()
        assertEquals(ResearchStudy.SESSION_ID, trip.sessionId)
        assertEquals(ResearchStudy.CORRIDOR_ID, trip.corridorId)
    }

    @Test
    fun `unknown cause is a valid explicit review value`() {
        val errors = EventAnnotationValidator.validate(
            EventAnnotation(
                primaryCauseCode = ResearchCodebook.UNKNOWN_CODE,
                confidenceCode = 3,
                trafficState = "QUEUED"
            )
        )
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `cause taxonomy provenance prefers trip metadata and preserves uncertainty`() {
        assertEquals("3", CauseTaxonomyVersions.provisionalVersion("3", null, "4"))
        assertEquals("4", CauseTaxonomyVersions.provisionalVersion(null, "4", "3"))
        assertEquals(null, CauseTaxonomyVersions.provisionalVersion("3", "4", "3"))
        assertEquals(null, CauseTaxonomyVersions.provisionalVersion("5", null, "4"))
        assertEquals(null, CauseTaxonomyVersions.provisionalVersion(null, null, "4", hasAnnotationHistory = true))
        assertEquals("4", CauseTaxonomyVersions.provisionalVersion(null, null, "4"))
        assertEquals("3", CauseTaxonomyVersions.currentPrimaryVersion(true, "3", "4"))
        assertEquals(null, CauseTaxonomyVersions.currentPrimaryVersion(true, null, "4"))
        assertEquals("4", CauseTaxonomyVersions.currentPrimaryVersion(false, null, "4"))
    }

    @Test
    fun `review choices keep v3 residual meaning separate and allow explicit v4 reclassification`() {
        val v3Choices = CauseReviewChoices.forVersions("3", null)
        assertTrue(CauseReviewChoice("TURNING", "3") in v3Choices)
        assertTrue(CauseReviewChoice("TURNING", "4") in v3Choices)
        assertTrue(CauseReviewChoice("SPEED_BREAKER", "4") in v3Choices)
        assertFalse(CauseReviewChoice("SPEED_BREAKER", "3") in v3Choices)
        assertTrue(CauseReviewChoice("TURNING", "3").label.contains("v3 legacy"))
        assertTrue(CauseReviewChoice("TURNING", "4").label.contains("v4 residual"))

        val v4Choices = CauseReviewChoices.forVersions("4", "3")
        assertTrue(CauseReviewChoice("TURNING", "4") in v4Choices)
        assertFalse(CauseReviewChoice("TURNING", "3") in v4Choices)
        assertTrue(CauseReviewChoices.forVersions(null, "3").any { it.codebookVersion == "3" })
        assertTrue(CauseReviewChoices.forVersions(null, "4").any { it.codebookVersion == "4" })
        assertTrue(CauseReviewChoices.forVersions(null, null).any { it.codebookVersion == "3" })
        assertTrue(CauseReviewChoices.forVersions(null, null).any { it.codebookVersion == "4" })
    }

    @Test
    fun `annotation validation checks cause against selected codebook`() {
        assertTrue(
            EventAnnotationValidator.validate(
                EventAnnotation(
                    primaryCauseCode = "SPEED_BREAKER",
                    confidenceCode = null,
                    trafficState = null,
                    codebookVersion = "3"
                )
            ).contains("invalid primary cause code for codebook 3")
        )
        assertTrue(
            EventAnnotationValidator.validate(
                EventAnnotation(
                    primaryCauseCode = "TURNING",
                    confidenceCode = null,
                    trafficState = null,
                    codebookVersion = "99"
                )
            ).contains("unknown cause codebook version")
        )
        assertTrue(
            EventAnnotationValidator.validate(
                EventAnnotation(
                    primaryCauseCode = "UNKNOWN",
                    confidenceCode = null,
                    trafficState = null,
                    codebookVersion = "3"
                )
            ).isEmpty()
        )
    }

    @Test
    fun `event provenance includes the active voice input method`() {
        assertTrue(EventProvenance.VOICE_RECOGNIZED in EventProvenance.values)
    }

    @Test
    fun `study date uses Kathmandu local timezone`() {
        assertEquals("2024-07-17", ResearchClock.studyDateLocal(1_721_203_200_000L))
    }

    @Test
    fun `audio folder date uses trip timezone and zero padded format`() {
        val startTimeMs = Instant.parse("2024-01-01T23:30:00Z").toEpochMilli()

        assertEquals("2024_01_02", ResearchClock.tripStartDateForAudioFolder(startTimeMs, "Asia/Kathmandu"))
        assertEquals("2024_01_01", ResearchClock.tripStartDateForAudioFolder(startTimeMs, "UTC"))
    }

    @Test
    fun `audio folder date falls back to study timezone when trip timezone is absent or invalid`() {
        val startTimeMs = Instant.parse("2024-01-01T23:30:00Z").toEpochMilli()

        assertEquals("2024_01_02", ResearchClock.tripStartDateForAudioFolder(startTimeMs, null))
        assertEquals("2024_01_02", ResearchClock.tripStartDateForAudioFolder(startTimeMs, "not/a-time-zone"))
    }

    @Test
    fun `audio archive path groups by trip id and start date`() {
        val trip = TestFixtures.tripA().copy(
            id = 42L,
            startTimeMs = Instant.parse("2024-01-01T23:30:00Z").toEpochMilli(),
            timeZoneId = "UTC"
        )
        val audio = TripAudio(
            audioId = "segment-one",
            tripId = trip.id,
            startTimeMs = trip.startTimeMs,
            filePath = "segment-one.m4a"
        )

        assertEquals(
            "audio/trip_42_2024_01_01/segment-one.m4a",
            ResearchExporter.audioArchivePath(trip, audio)
        )
    }
}
