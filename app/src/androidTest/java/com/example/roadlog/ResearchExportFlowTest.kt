package com.example.roadlog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.Intents.init
import androidx.test.espresso.intent.Intents.release
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.intent.matcher.IntentMatchers.hasType
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class ResearchExportFlowTest {
    private lateinit var fixtures: DeviceFlowFixtures
    private val testContext: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    @Before
    fun setUp() {
        init()
        TestExportDocumentProvider.clear(testContext)
        runBlocking {
            fixtures = DeviceFlowFixtures(ApplicationProvider.getApplicationContext())
            fixtures.requireIsolatedInstall()
        }
    }

    @After
    fun tearDown() {
        try {
            if (::fixtures.isInitialized) runBlocking { fixtures.cleanupOwnedData() }
        } finally {
            TestExportDocumentProvider.clear(testContext)
            release()
        }
    }

    @Test
    fun restrictedExportIntentWritesValidatedRawEvidenceArchive() = runTest {
        val seeded = seedExportEvidence("restricted")
        respondWithTestDocument()

        launchHistory().use { scenario ->
            onView(withId(R.id.exportRestrictedButton)).perform(click())
            intended(createDocumentIntent("roadlog-restricted-raw-export.zip"))
            awaitCompletedExport(scenario)
        }

        val entries = readEntries(TestExportDocumentProvider.readArchive(testContext))
        assertTrue(entries.keys.containsAll(listOf(
            "manifest.json",
            "trips/trips.csv",
            "events/events.csv",
            "audio/audio_index.csv",
            "media/media_index.csv"
        )))
        val combined = entries.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
        assertTrue(combined.contains("PRIVATE_DEVICE_ID_restricted"))
        assertTrue(combined.contains("PRIVATE_TRIP_NOTE_restricted"))
        assertTrue(combined.contains("PRIVATE_TRANSCRIPT_restricted"))
        assertTrue(combined.contains("27.712345"))
        assertTrue(entries.keys.any { it.startsWith("audio/") })
        assertTrue(entries.keys.any { it.startsWith("photos/") })
        assertTrue(entries.getValue(seeded.audioEntryName).contentEquals(seeded.audioBytes))
    }

    @Test
    fun publicExportIntentOmitsPreciseLocationsTranscriptsAndMedia() = runTest {
        seedExportEvidence("public")
        respondWithTestDocument()

        launchHistory().use { scenario ->
            onView(withId(R.id.exportPublicButton)).perform(click())
            intended(createDocumentIntent("roadlog-public-deidentified-export.zip"))
            awaitCompletedExport(scenario)
        }

        val entries = readEntries(TestExportDocumentProvider.readArchive(testContext))
        val combined = entries.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
        assertTrue(entries.containsKey("manifest.json"))
        assertTrue(entries.containsKey("trips/trips.csv"))
        assertTrue(entries.containsKey("events/events.csv"))
        assertFalse(entries.keys.any { it.startsWith("audio/") })
        assertFalse(entries.keys.any { it.startsWith("photos/") })
        assertFalse(entries.containsKey("audio/audio_index.csv"))
        assertFalse(entries.containsKey("media/media_index.csv"))
        assertFalse(combined.contains("PRIVATE_DEVICE_ID_public"))
        assertFalse(combined.contains("PRIVATE_DRIVER_ID_public"))
        assertFalse(combined.contains("PRIVATE_TRIP_NOTE_public"))
        assertFalse(combined.contains("PRIVATE_QA_NOTE_public"))
        assertFalse(combined.contains("PRIVATE_TRANSCRIPT_public"))
        assertFalse(combined.contains("27.712345"))
        assertFalse(combined.contains("85.323456"))
    }

    private fun awaitCompletedExport(scenario: ActivityScenario<TripHistoryActivity>) {
        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val archive = File.createTempFile("export-verification-", ".zip", targetContext.cacheDir)
        val deadline = SystemClock.uptimeMillis() + 15_000L
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                // ZipFile requires the central directory, so a partially written ZIP
                // cannot be mistaken for a completed export. Content is asserted below.
                val archiveReady = try {
                    archive.writeBytes(TestExportDocumentProvider.readArchive(testContext))
                    ZipFile(archive).use { it.getEntry("manifest.json") != null }
                } catch (_: java.io.IOException) {
                    false
                }
                var controlsReady = false
                scenario.onActivity { activity ->
                    controlsReady = activity.findViewById<Button>(R.id.exportRestrictedButton).isEnabled &&
                        activity.findViewById<Button>(R.id.exportPublicButton).isEnabled
                }
                if (archiveReady && controlsReady) return
                SystemClock.sleep(50L)
            }
            error("Timed out waiting for a completed export ZIP and restored export controls")
        } finally {
            archive.delete()
        }
    }

    private suspend fun seedExportEvidence(suffix: String): SeededEvidence {
        val trip = fixtures.insertTrip(
            TestFixtures.tripA().copy(
                eventCount = 1,
                studyDateLocal = "2099-10-01",
                deviceId = "PRIVATE_DEVICE_ID_$suffix",
                driverId = "PRIVATE_DRIVER_ID_$suffix",
                notes = "PRIVATE_TRIP_NOTE_$suffix",
                qaNotes = "PRIVATE_QA_NOTE_$suffix",
                contextNote = "PRIVATE_CONTEXT_NOTE_$suffix",
                codebookVersion = "4",
                causeConfigJson = """{"version":"4"}"""
            )
        )
        val eventId = "device-flow-export-$suffix"
        fixtures.insertEvent(
            TripEvent(
                eventId = eventId,
                tripId = trip.id,
                markerTimeMs = trip.startTimeMs + 5_000,
                experiencedLatitude = 27.712345,
                experiencedLongitude = 85.323456,
                sourceLatitude = 27.712345,
                sourceLongitude = 85.323456,
                sourceLocationVisible = true,
                provisionalCauseCode = "SIGNAL",
                primaryCauseCode = "SIGNAL",
                confidenceCode = 3,
                trafficState = "MOVING",
                transcript = "PRIVATE_TRANSCRIPT_$suffix",
                recognitionConfidence = 0.98f,
                codebookVersion = "4"
            )
        )
        fixtures.insertData(
            TestFixtures.gpsPointsForTrip(
                trip.id,
                trip.startTimeMs,
                trip.endTimeMs,
                count = 1
            ).map { it.copy(latitude = 27.712345, longitude = 85.323456) }
        )

        val audioBytes = byteArrayOf(0x4d, 0x34, 0x41, 0x20, suffix.length.toByte())
        val audioFile = fixtures.createMediaFile("$suffix.m4a", audioBytes)
        fixtures.insertAudio(
            TripAudio(
                audioId = "device-flow-audio-$suffix",
                tripId = trip.id,
                eventId = eventId,
                startTimeMs = trip.startTimeMs + 4_000,
                endTimeMs = trip.startTimeMs + 6_000,
                filePath = audioFile.absolutePath,
                transcript = "PRIVATE_TRANSCRIPT_$suffix",
                status = TripAudioStatus.COMPLETE
            )
        )
        val photo = fixtures.createMediaFile("$suffix.jpg", TestFixtures.generateJpegBytes())
        fixtures.insertPhoto(
            TripPhoto(
                tripId = trip.id,
                timestamp = trip.startTimeMs + 5_000,
                latitude = 27.712345,
                longitude = 85.323456,
                filePath = photo.absolutePath
            )
        )
        return SeededEvidence(
            audioEntryName = "audio/trip_${trip.id}_${ResearchClock.tripStartDateForAudioFolder(trip.startTimeMs, trip.timeZoneId)}/device-flow-audio-$suffix.m4a",
            audioBytes = audioBytes
        )
    }

    private fun respondWithTestDocument() {
        val resultData = Intent().apply {
            data = TestExportDocumentProvider.documentUri
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(android.app.Instrumentation.ActivityResult(Activity.RESULT_OK, resultData))
    }

    private fun createDocumentIntent(title: String) = allOf(
        hasAction(Intent.ACTION_CREATE_DOCUMENT),
        hasType("application/zip"),
        hasExtra(Intent.EXTRA_TITLE, title)
    )

    private fun launchHistory(): ActivityScenario<TripHistoryActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), TripHistoryActivity::class.java)
        )

    private fun readEntries(archive: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return entries
    }

    private data class SeededEvidence(val audioEntryName: String, val audioBytes: ByteArray)
}
