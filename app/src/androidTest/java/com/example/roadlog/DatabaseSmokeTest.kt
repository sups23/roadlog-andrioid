package com.example.roadlog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {

    private lateinit var db: AppDatabase
    private lateinit var photoDir: File

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        val cacheDir = ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir
        photoDir = File(cacheDir, "test_photos")
        photoDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        photoDir.deleteRecursively()
    }

    @Test
    fun `open and insert trip`() = runTest {
        val trip = TestFixtures.tripA()
        val tripId = db.tripDao().insertTrip(trip)
        assertTrue(tripId > 0)

        val loaded = db.tripDao().getTripById(tripId)
        assertNotNull(loaded)
        assertEquals(trip.startTimeMs, loaded!!.startTimeMs)
        assertEquals(trip.endTimeMs, loaded.endTimeMs)
    }

    @Test
    fun `trip context persists and post-trip correction is audited`() = runTest {
        val tripId = db.tripDao().insertTrip(
            TestFixtures.tripA().copy(
                driverId = "DRIVER_01",
                vehicleId = "VEHICLE_01",
                vehicleType = ResearchVehicleProfile.TYPE,
                vehicleMake = ResearchVehicleProfile.MAKE,
                vehicleModel = ResearchVehicleProfile.MODEL,
                vehicleYear = ResearchVehicleProfile.YEAR,
                weather = TripWeather.CLEAR,
                roadWetness = RoadWetness.DRY,
                routeDiversion = false,
                nonTrafficStop = NonTrafficStop.NONE,
                contextCollectedAtMs = 1000L
            )
        )

        db.tripDao().correctTripContext(
            tripId = tripId,
            routeDiversion = true,
            nonTrafficStop = NonTrafficStop.RESEARCH_SETUP,
            contextNote = "Confirmed after parking",
            reason = "post-trip review"
        )

        val corrected = db.tripDao().getTripById(tripId)!!
        assertTrue(corrected.routeDiversion)
        assertEquals(NonTrafficStop.RESEARCH_SETUP, corrected.nonTrafficStop)
        assertEquals("Confirmed after parking", corrected.contextNote)
        val revisions = db.tripDao().getAllAuditRevisionsForExport()
        assertEquals(1, revisions.size)
        assertEquals("TRIP_CONTEXT_CORRECTION", revisions.single().revisionType)
    }

    @Test
    fun `insert and verify all row types`() = runTest {
        val trip = TestFixtures.tripA()
        val tripId = db.tripDao().insertTrip(trip)

        val rows = TestFixtures.allRowsForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        db.tripDao().insertAll(rows)

        val gpsRows = db.tripDao().getGpsForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertTrue(gpsRows.isNotEmpty())
        gpsRows.forEach {
            assertNotNull(it.latitude)
            assertNotNull(it.longitude)
        }

        val eventRows = db.tripDao().getEventsForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertEquals(4, eventRows.size)

        val accelRows = db.tripDao().getAccelForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertTrue(accelRows.isNotEmpty())

        val gyroRows = db.tripDao().getGyroForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertTrue(gyroRows.isNotEmpty())

        val rotRows = db.tripDao().getRotationForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertTrue(rotRows.isNotEmpty())
    }

    @Test
    fun `photos insert and retrieve`() = runTest {
        val trip = TestFixtures.tripA()
        val tripId = db.tripDao().insertTrip(trip)

        DatabaseFixtures.writeJpegFile(photoDir, "test_photo.jpg")
        val photoPath = File(photoDir, "test_photo.jpg").absolutePath
        val photo = TripPhoto(
            tripId = tripId,
            timestamp = trip.startTimeMs + 1000,
            latitude = 27.7,
            longitude = 85.3,
            filePath = photoPath
        )
        db.tripDao().insertPhoto(photo)

        val photos = db.tripDao().getPhotosForTrip(tripId)
        assertEquals(1, photos.size)
        assertEquals(photoPath, photos[0].filePath)
    }

    @Test
    fun `large trace insert and verify counts`() = runTest {
        val trip = TestFixtures.largeTrip()
        val tripId = db.tripDao().insertTrip(trip)

        val gpsRows = TestFixtures.largeGpsRows(tripId, trip.startTimeMs, trip.endTimeMs, 1000)
        gpsRows.chunked(500).forEach { db.tripDao().insertAll(it) }

        val loadedGps = db.tripDao().getGpsForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        assertEquals(1000, loadedGps.size)
    }

    @Test
    fun `two overlapping trips can coexist`() = runTest {
        val trip1 = TestFixtures.tripA()
        val trip2 = TestFixtures.tripB()

        val id1 = db.tripDao().insertTrip(trip1)
        val id2 = db.tripDao().insertTrip(trip2)

        val rows1 = TestFixtures.gpsPointsForTrip(id1, trip1.startTimeMs, trip1.endTimeMs, 10)
        val rows2 = TestFixtures.gpsPointsForTrip(id2, trip2.startTimeMs, trip2.endTimeMs, 10)
        db.tripDao().insertAll(rows1 + rows2)

        val loaded1 = db.tripDao().getGpsForTrip(id1, trip1.startTimeMs, trip1.endTimeMs)
        val loaded2 = db.tripDao().getGpsForTrip(id2, trip2.startTimeMs, trip2.endTimeMs)
        assertEquals(10, loaded1.size)
        assertEquals(10, loaded2.size)

        val tripIds1 = loaded1.map { it.tripId }.toSet()
        val tripIds2 = loaded2.map { it.tripId }.toSet()
        assertFalse(tripIds1.intersect(tripIds2).isNotEmpty())
    }

    @Test
    fun `repeated identical research metadata remains independent`() = runTest {
        val trips = (1..3).map {
            TestFixtures.tripA().copy(
                tripUuid = "trip-$it",
                sessionId = ResearchStudy.SESSION_ID,
                corridorId = ResearchStudy.CORRIDOR_ID,
                direction = ResearchDirection.A_TO_B,
                observationPeriod = ObservationPeriod.MORNING,
                studyDateLocal = "2024-07-17",
                timeZoneId = ResearchTime.KATHMANDU_ZONE_ID
            )
        }
        val ids = trips.map { db.tripDao().insertTrip(it) }
        assertEquals(3, ids.toSet().size)
        ids.forEachIndexed { index, id ->
            db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id, trips[index].startTimeMs, trips[index].endTimeMs, 2))
        }
        ids.forEach { id ->
            assertEquals(2, db.tripDao().getGpsForTrip(id, TestFixtures.BASE_TIME_MS, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS).size)
        }
    }

    @Test
    fun `annotation revisions preserve earlier values`() = runTest {
        val tripId = db.tripDao().insertTrip(TestFixtures.tripA())
        val eventId = "event-1"
        db.tripDao().insertEvents(listOf(TripEvent(eventId = eventId, tripId = tripId, markerTimeMs = TestFixtures.BASE_TIME_MS)))
        db.tripDao().annotateEvent(
            eventId,
            EventAnnotation(
                primaryCauseCode = "SIGNAL",
                confidenceCode = 2,
                trafficState = "QUEUED"
            )
        )
        db.tripDao().annotateEvent(
            eventId,
            EventAnnotation(
                primaryCauseCode = "QUEUE",
                confidenceCode = 3,
                trafficState = "DENSE_MOVING"
            )
        )
        val revisions = db.tripDao().getAnnotationsForEvent(eventId)
        assertEquals(2, revisions.size)
        assertEquals("SIGNAL", revisions[0].primaryCauseCode)
        assertEquals("QUEUE", revisions[1].primaryCauseCode)
        assertEquals("QUEUE", db.tripDao().getTripEvent(eventId)!!.primaryCauseCode)
    }

    @Test
    fun `legacy trip zero rows do not contaminate normal trip queries`() = runTest {
        val tripId = db.tripDao().insertTrip(TestFixtures.tripA())
        db.tripDao().insertAll(
            listOf(
                TripData(
                    tripId = 0L,
                    timestamp = TestFixtures.BASE_TIME_MS + 100,
                    latitude = 1.0,
                    longitude = 2.0,
                    speedKmh = 10f,
                    eventCause = null
                ),
                TripData(
                    tripId = tripId,
                    timestamp = TestFixtures.BASE_TIME_MS + 100,
                    latitude = 3.0,
                    longitude = 4.0,
                    speedKmh = 20f,
                    eventCause = null
                )
            )
        )

        val rows = db.tripDao().getGpsForTrip(
            tripId,
            TestFixtures.BASE_TIME_MS,
            TestFixtures.BASE_TIME_MS + 1000
        )
        assertEquals(1, rows.size)
        assertEquals(tripId, rows.single().tripId)
    }

    @Test
    fun `completed trips appear in history`() = runTest {
        db.tripDao().insertTrip(TestFixtures.tripA())
        db.tripDao().insertTrip(TestFixtures.tripB())

        val all = db.tripDao().getAllTrips()
        assertEquals(2, all.size)
        assertTrue(all[0].startTimeMs >= all[1].startTimeMs)
        all.forEach { assertEquals(TripStatus.COMPLETED, it.status) }
    }

    @Test
    fun `draft trip excluded from history`() = runTest {
        val draft = Trip(
            startTimeMs = TestFixtures.BASE_TIME_MS,
            endTimeMs = 0,
            distanceMeters = 0.0,
            eventCount = 0,
            gpsPointCount = 0,
            accelPointCount = 0,
            causeBreakdown = "{}",
            createdAt = 0,
            status = TripStatus.RECORDING
        )
        db.tripDao().insertTrip(draft)

        val all = db.tripDao().getAllTrips()
        assertEquals(0, all.size)
    }

    @Test
    fun `draft trip visible by ID even when excluded from history`() = runTest {
        val draft = Trip(
            startTimeMs = TestFixtures.BASE_TIME_MS,
            endTimeMs = 0,
            distanceMeters = 0.0,
            eventCount = 0,
            gpsPointCount = 0,
            accelPointCount = 0,
            causeBreakdown = "{}",
            createdAt = 0,
            status = TripStatus.RECORDING
        )
        val id = db.tripDao().insertTrip(draft)
        val loaded = db.tripDao().getTripById(id)
        assertNotNull(loaded)
        assertEquals(TripStatus.RECORDING, loaded!!.status)
    }

    @Test
    fun `finalize trip marks completed and writes summary fields`() = runTest {
        val draft = Trip(
            startTimeMs = TestFixtures.BASE_TIME_MS,
            endTimeMs = 0,
            startNanoTime = 1000,
            distanceMeters = 0.0,
            eventCount = 0,
            gpsPointCount = 0,
            accelPointCount = 0,
            causeBreakdown = "{}",
            createdAt = 0,
            status = TripStatus.RECORDING
        )
        val id = db.tripDao().insertTrip(draft)

        val rows = TestFixtures.allRowsForTrip(id, TestFixtures.BASE_TIME_MS, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS)
        db.tripDao().insertAll(rows)

        db.tripDao().finalizeTrip(
            tripId = id,
            endTimeMs = TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS,
            endNanoTime = 2000,
            distanceMeters = 12300.0,
            eventCount = 4,
            gpsPointCount = 100,
            accelPointCount = 200,
            causeBreakdown = "{\"SIGNAL\":1,\"QUEUE\":1,\"BUS\":1,\"ROUGH\":1}",
            createdAt = TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS
        )

        val finalized = db.tripDao().getTripById(id)!!
        assertEquals(TripStatus.COMPLETED, finalized.status)
        assertEquals(12300.0, finalized.distanceMeters, 0.01)
        assertEquals(4, finalized.eventCount)
        assertEquals(100, finalized.gpsPointCount)
        assertEquals(200, finalized.accelPointCount)

        val history = db.tripDao().getAllTrips()
        assertEquals(1, history.size)
    }

    @Test
    fun `unfinished drafts are preserved for recovery`() = runTest {
        val draft1 = Trip(
            startTimeMs = TestFixtures.BASE_TIME_MS,
            endTimeMs = 0,
            distanceMeters = 0.0, eventCount = 0, gpsPointCount = 0, accelPointCount = 0,
            causeBreakdown = "{}", createdAt = 0, status = TripStatus.RECORDING
        )
        val id1 = db.tripDao().insertTrip(draft1)
        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id1, TestFixtures.BASE_TIME_MS, TestFixtures.BASE_TIME_MS + 1000, 5))

        val draft2 = Trip(
            startTimeMs = TestFixtures.BASE_TIME_MS + 10000,
            endTimeMs = 0,
            distanceMeters = 0.0, eventCount = 0, gpsPointCount = 0, accelPointCount = 0,
            causeBreakdown = "{}", createdAt = 0, status = TripStatus.RECORDING
        )
        val id2 = db.tripDao().insertTrip(draft2)
        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id2, TestFixtures.BASE_TIME_MS + 10000, TestFixtures.BASE_TIME_MS + 11000, 5))

        val abandoned = db.tripDao().getAbandonedTrips()
        assertEquals(2, abandoned.size)

        abandoned.forEach { trip ->
            db.tripDao().markTripInterrupted(
                tripId = trip.id,
                status = TripStatus.RECOVERABLE,
                endTimeMs = TestFixtures.BASE_TIME_MS + 20_000,
                endNanoTime = 20_000,
                reason = "TEST_INTERRUPTION",
                lastWriteTimeMs = TestFixtures.BASE_TIME_MS
            )
        }

        assertEquals(0, db.tripDao().getAbandonedTrips().size)
        assertEquals(2, db.tripDao().getIncompleteTrips().size)
        assertNotNull(db.tripDao().getTripById(id1))
        assertNotNull(db.tripDao().getTripById(id2))
    }

    @Test
    fun `deleting one overlapping trip preserves the other`() = runTest {
        val trip1 = TestFixtures.tripA()
        val trip2 = TestFixtures.tripB()

        val id1 = db.tripDao().insertTrip(trip1)
        val id2 = db.tripDao().insertTrip(trip2)

        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id1, trip1.startTimeMs, trip1.endTimeMs, 10))
        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id2, trip2.startTimeMs, trip2.endTimeMs, 10))

        val photo1 = TripPhoto(tripId = id1, timestamp = trip1.startTimeMs + 1000, filePath = "/tmp/p1.jpg")
        val photo2 = TripPhoto(tripId = id2, timestamp = trip2.startTimeMs + 1000, filePath = "/tmp/p2.jpg")
        db.tripDao().insertPhoto(photo1)
        db.tripDao().insertPhoto(photo2)

        db.tripDao().deleteTripCascade(id1)

        assertEquals(0, db.tripDao().getPhotosForTrip(id1).size)
        assertEquals(0, db.tripDao().getGpsForTrip(id1, trip1.startTimeMs, trip1.endTimeMs).size)
        assertNull(db.tripDao().getTripById(id1))

        assertNotNull(db.tripDao().getTripById(id2))
        assertEquals(1, db.tripDao().getPhotosForTrip(id2).size)
        assertEquals(10, db.tripDao().getGpsForTrip(id2, trip2.startTimeMs, trip2.endTimeMs).size)
        assertEquals(1, db.tripDao().getAllTrips().size)
    }

    @Test
    fun `deleteTripDataForTrip removes only that trips data`() = runTest {
        val id1 = db.tripDao().insertTrip(TestFixtures.tripA())
        val id2 = db.tripDao().insertTrip(TestFixtures.tripB())

        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id1, TestFixtures.BASE_TIME_MS, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS, 5))
        db.tripDao().insertAll(TestFixtures.gpsPointsForTrip(id2, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS / 2, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS + TestFixtures.HOUR_MS / 2, 5))

        db.tripDao().deleteTripDataForTrip(id1)

        assertEquals(0, db.tripDao().getGpsForTrip(id1, TestFixtures.BASE_TIME_MS, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS).size)
        assertEquals(5, db.tripDao().getGpsForTrip(id2, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS / 2, TestFixtures.BASE_TIME_MS + TestFixtures.HOUR_MS + TestFixtures.HOUR_MS / 2).size)
    }

    @Test
    fun `deleteTripWithMediaFiles removes database rows and media files`() = runTest {
        val trip = TestFixtures.tripA()
        val tripId = db.tripDao().insertTrip(trip)
        val photoFile = File(photoDir, "purge-photo.jpg").apply { writeBytes(TestFixtures.generateJpegBytes()) }
        val audioFile = File(photoDir, "purge-audio.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        db.tripDao().insertPhoto(
            TripPhoto(tripId = tripId, timestamp = trip.startTimeMs, filePath = photoFile.absolutePath)
        )
        db.tripDao().insertAudio(
            TripAudio(
                audioId = "audio-purge",
                tripId = tripId,
                startTimeMs = trip.startTimeMs,
                filePath = audioFile.absolutePath
            )
        )

        val report = db.tripDao().deleteTripWithMediaFiles(tripId)

        assertEquals(2, report.attemptedFiles)
        assertEquals(2, report.deletedFiles)
        assertTrue(report.failures.isEmpty())
        assertFalse(photoFile.exists())
        assertFalse(audioFile.exists())
        assertNull(db.tripDao().getTripById(tripId))
    }

    @Test
    fun `deleteTripWithMediaFiles reports missing media`() = runTest {
        val tripId = db.tripDao().insertTrip(TestFixtures.tripA())
        db.tripDao().insertPhoto(
            TripPhoto(
                tripId = tripId,
                timestamp = TestFixtures.BASE_TIME_MS,
                filePath = File(photoDir, "does-not-exist.jpg").absolutePath
            )
        )

        val report = db.tripDao().deleteTripWithMediaFiles(tripId)

        assertEquals(1, report.attemptedFiles)
        assertEquals(1, report.missingFiles)
        assertFalse(report.isComplete)
        assertNull(db.tripDao().getTripById(tripId))
    }

    @Test
    fun `archival audio failure metadata and audit evidence persist`() = runTest {
        val tripId = db.tripDao().insertTrip(TestFixtures.tripA())
        val audio = TripAudio(
            audioId = "audio-frame-failure",
            tripId = tripId,
            startTimeMs = TestFixtures.BASE_TIME_MS,
            endTimeMs = TestFixtures.BASE_TIME_MS + 1000L,
            filePath = File(photoDir, "missing-audio.m4a").absolutePath,
            status = TripAudioStatus.FAILED,
            interruptionReason = TripAudioFailureType.reason(
                TripAudioFailureType.FRAME_PROCESSING,
                "encoder rejected frame"
            )
        )
        db.tripDao().insertAudio(audio)

        val evidence = AudioEvidence.inventory(audio, filePresent = false, fileUsable = false)
        db.tripDao().insertAuditRevisionIfAbsent(
            AudioEvidence.auditRevision(evidence, revisionTimeMs = 1234L)
        )

        val loadedAudio = db.tripDao().getAudioById(audio.audioId)!!
        val loadedAudit = db.tripDao().getAllAuditRevisionsForExport().single()
        assertEquals(TripAudioStatus.FAILED, loadedAudio.status)
        assertTrue(loadedAudio.interruptionReason!!.contains(TripAudioFailureType.FRAME_PROCESSING))
        assertTrue(loadedAudio.interruptionReason!!.contains("encoder rejected frame"))
        assertEquals("AUDIO_FAILURE", loadedAudit.revisionType)
        assertTrue(loadedAudit.currentValue!!.contains("FRAME_PROCESSING_FAILURE"))
        assertTrue(loadedAudit.currentValue!!.contains("final_audio_status"))

        val qualitySummary = AudioEvidence.summarizeInventory(listOf(evidence))
        db.tripDao().upsertTripQuality(
            TripQuality(
                tripId = tripId,
                audioSegmentCount = qualitySummary.segmentCount,
                warningsJson = org.json.JSONArray(qualitySummary.warningMessages).toString(),
                completeness = "COMPLETE_WITH_WARNINGS"
            )
        )
        val loadedQuality = db.tripDao().getAllTripQuality().single()
        assertEquals(1, loadedQuality.audioSegmentCount)
        assertTrue(loadedQuality.warningsJson.contains(TripAudioFailureType.FRAME_PROCESSING))
    }

    @Test
    fun `research archive retains archival audio failure evidence`() = runTest {
        val trip = TestFixtures.tripA()
        val tripId = db.tripDao().insertTrip(trip)
        val persistedTrip = db.tripDao().getTripById(tripId)!!
        val audio = TripAudio(
            audioId = "audio-export-failure",
            tripId = tripId,
            startTimeMs = trip.startTimeMs,
            endTimeMs = trip.startTimeMs + 1000L,
            filePath = File(photoDir, "not-created.m4a").absolutePath,
            status = TripAudioStatus.FAILED,
            interruptionReason = TripAudioFailureType.reason(
                TripAudioFailureType.FRAME_PROCESSING,
                "encoder rejected frame"
            )
        )
        db.tripDao().insertAudio(audio)
        db.tripDao().upsertTripQuality(TripQuality(tripId = tripId))

        val archive = File(photoDir, "research-export.zip")
        val result = ResearchExporter.writeArchive(
            database = db,
            trips = listOf(persistedTrip),
            output = archive,
            deviceId = "test-device",
            mode = ResearchExportMode.RESTRICTED_RAW
        )
        ResearchExporter.validateArchive(archive, result)

        ZipFile(archive).use { zip ->
            val audioIndex = zip.getInputStream(zip.getEntry("audio/audio_index.csv"))
                .bufferedReader()
                .use { it.readText() }
            val quality = zip.getInputStream(zip.getEntry("qa/trips.json"))
                .bufferedReader()
                .use { it.readText() }
            val audit = zip.getInputStream(zip.getEntry("audit/revisions.csv"))
                .bufferedReader()
                .use { it.readText() }

            assertTrue(audioIndex.contains("FRAME_PROCESSING_FAILURE"))
            assertTrue(audioIndex.contains(TripAudioCompleteness.MISSING_EXPECTED_AUDIO_FILE))
            assertTrue(audioIndex.contains("\"false\",\"false\""))
            assertTrue(quality.contains("audioFailureCount"))
            assertTrue(audit.contains("AUDIO_FAILURE"))
            assertTrue(audit.contains("FRAME_PROCESSING_FAILURE"))
        }
    }

    @Test
    fun `recoverable trip has incomplete quality evidence and interruption warning`() = runTest {
        val trip = TestFixtures.tripA().copy(status = TripStatus.RECORDING)
        val tripId = db.tripDao().insertTrip(trip)
        db.tripDao().markTripInterrupted(
            tripId = tripId,
            status = TripStatus.RECOVERABLE,
            endTimeMs = trip.startTimeMs + 5_000L,
            endNanoTime = 5_000L,
            reason = "SERVICE_DESTROYED",
            lastWriteTimeMs = trip.startTimeMs
        )
        val warning = "trip is recoverable/interrupted and must not be treated as a completed valid trip"
        db.tripDao().upsertTripQuality(
            TripQuality(
                tripId = tripId,
                warningsJson = org.json.JSONArray().put(warning).toString(),
                completeness = "INCOMPLETE"
            )
        )

        val recovered = db.tripDao().getTripById(tripId)!!
        val quality = db.tripDao().getAllTripQuality().single()
        assertEquals(TripStatus.RECOVERABLE, recovered.status)
        assertEquals("SERVICE_DESTROYED", recovered.interruptionReason)
        assertEquals("INCOMPLETE", quality.completeness)
        assertTrue(quality.warningsJson.contains("completed valid trip"))
    }

    @Test
    fun `preparation timeout is preserved in trip and restricted export`() = runTest {
        val draft = TestFixtures.tripA().copy(
            status = TripStatus.RECORDING,
            driverId = "DRIVER_01",
            vehicleId = "VEHICLE_01",
            vehicleType = ResearchVehicleProfile.TYPE,
            vehicleMake = ResearchVehicleProfile.MAKE,
            vehicleModel = ResearchVehicleProfile.MODEL,
            vehicleYear = ResearchVehicleProfile.YEAR,
            weather = TripWeather.CLOUDY,
            roadWetness = RoadWetness.DAMP,
            nonTrafficStop = NonTrafficStop.NONE,
            contextCollectedAtMs = 1_000L
        )
        val tripId = db.tripDao().insertTrip(draft)
        db.tripDao().markTripInterrupted(
            tripId = tripId,
            status = TripStatus.RECOVERABLE,
            endTimeMs = draft.startTimeMs + 120_000L,
            endNanoTime = 120_000L,
            reason = "PREPARATION_TIMEOUT: model not ready within 120000 ms",
            lastWriteTimeMs = draft.startTimeMs
        )
        db.tripDao().upsertTripQuality(
            TripQuality(
                tripId = tripId,
                warningsJson = "[\"trip interruption reason: PREPARATION_TIMEOUT\"]",
                completeness = "INCOMPLETE"
            )
        )

        val persisted = db.tripDao().getTripById(tripId)!!
        assertEquals(TripStatus.RECOVERABLE, persisted.status)
        assertEquals(
            "PREPARATION_TIMEOUT: model not ready within 120000 ms",
            persisted.interruptionReason
        )

        val archive = File(photoDir, "preparation-timeout-export.zip")
        val result = ResearchExporter.writeArchive(
            database = db,
            trips = listOf(persisted),
            output = archive,
            deviceId = "test-device",
            mode = ResearchExportMode.RESTRICTED_RAW
        )
        ResearchExporter.validateArchive(archive, result)

        ZipFile(archive).use { zip ->
            val tripsCsv = zip.getInputStream(zip.getEntry("trips/trips.csv"))
                .bufferedReader()
                .use { it.readText() }
            val qualityJson = zip.getInputStream(zip.getEntry("qa/trips.json"))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(tripsCsv.contains("PREPARATION_TIMEOUT"))
            assertTrue(tripsCsv.contains("DRIVER_01"))
            assertTrue(tripsCsv.contains("CLOUDY"))
            assertTrue(tripsCsv.contains("DAMP"))
            assertTrue(qualityJson.contains("INCOMPLETE"))
        }

        val publicArchive = File(photoDir, "preparation-timeout-public-export.zip")
        val publicResult = ResearchExporter.writeArchive(
            database = db,
            trips = listOf(persisted),
            output = publicArchive,
            deviceId = "test-device",
            mode = ResearchExportMode.PUBLIC_DEIDENTIFIED
        )
        ResearchExporter.validateArchive(publicArchive, publicResult)
        ZipFile(publicArchive).use { zip ->
            val tripsCsv = zip.getInputStream(zip.getEntry("trips/trips.csv"))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(tripsCsv.contains("CLOUDY"))
            assertFalse(tripsCsv.contains("DRIVER_01"))
            assertFalse(tripsCsv.contains("VEHICLE_01"))
        }
    }

}
