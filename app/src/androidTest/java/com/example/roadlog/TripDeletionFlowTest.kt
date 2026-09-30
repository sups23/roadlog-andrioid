package com.example.roadlog

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripDeletionFlowTest {
    private lateinit var fixtures: DeviceFlowFixtures

    @Before
    fun setUp() = runBlocking {
        fixtures = DeviceFlowFixtures(ApplicationProvider.getApplicationContext())
        fixtures.requireIsolatedInstall()
    }

    @After
    fun tearDown() = runBlocking {
        if (::fixtures.isInitialized) fixtures.cleanupOwnedData()
    }

    @Test
    fun cancelPreservesTripThenConfirmationDeletesOnlySelectedTripAndMedia() = runTest {
        val target = insertTripWithPhoto("2099-11-01")
        val protected = insertTripWithPhoto("2099-11-02")

        launchHistory().use {
            swipeTrip("2099-11-01")
            DeviceFlowUi.awaitTextContains("Delete trip?")
            onView(withText("Cancel")).perform(click())
            DeviceFlowUi.awaitTextContains("2099-11-01")
            assertNotNull(fixtures.database.tripDao().getTripById(target.trip.id))
            assertTrue(target.photo.exists())
            assertTrue(target.audio.exists())

            swipeTrip("2099-11-01")
            DeviceFlowUi.awaitTextContains("Delete trip?")
            onView(withText("Delete")).perform(click())
            DeviceFlowUi.awaitTextGone("2099-11-01")

            assertNull(fixtures.database.tripDao().getTripById(target.trip.id))
            assertFalse(target.photo.exists())
            assertFalse(target.audio.exists())
            assertNotNull(fixtures.database.tripDao().getTripById(protected.trip.id))
            assertTrue(protected.photo.exists())
            assertTrue(protected.audio.exists())
            assertEquals(1, fixtures.database.tripDao().getAllTripsForExport().size)
            assertEquals(1, fixtures.database.tripDao().getPhotosForTrip(protected.trip.id).size)
            assertEquals(1, fixtures.database.tripDao().getAudioForTrip(protected.trip.id).size)
        }
    }

    @Test
    fun reviewedTripCannotBeDeletedByHistorySwipe() = runTest {
        val reviewed = insertTripWithPhoto("2099-11-03", qaStatus = TripQaStatus.VALID)

        launchHistory().use {
            swipeTrip("2099-11-03")
            DeviceFlowUi.awaitTextContains("2099-11-03")
        }

        assertNotNull(fixtures.database.tripDao().getTripById(reviewed.trip.id))
        assertTrue(reviewed.photo.exists())
        assertTrue(reviewed.audio.exists())
        assertEquals(1, fixtures.database.tripDao().getPhotosForTrip(reviewed.trip.id).size)
        assertEquals(1, fixtures.database.tripDao().getAudioForTrip(reviewed.trip.id).size)
    }

    private suspend fun insertTripWithPhoto(day: String, qaStatus: String = TripQaStatus.UNREVIEWED): OwnedTrip {
        val trip = fixtures.insertTrip(
            TestFixtures.tripA().copy(
                studyDateLocal = day,
                qaStatus = qaStatus,
                direction = ResearchDirection.A_TO_B,
                observationPeriod = ObservationPeriod.MORNING
            )
        )
        val photo = fixtures.createMediaFile("$day.jpg", TestFixtures.generateJpegBytes())
        fixtures.insertPhoto(
            TripPhoto(
                tripId = trip.id,
                timestamp = trip.startTimeMs + 1_000,
                filePath = photo.absolutePath
            )
        )
        val audio = fixtures.createMediaFile("$day.m4a", byteArrayOf(0x4d, 0x34, 0x41, 0x20))
        fixtures.insertAudio(
            TripAudio(
                audioId = "device-flow-delete-$day",
                tripId = trip.id,
                startTimeMs = trip.startTimeMs + 2_000,
                endTimeMs = trip.startTimeMs + 3_000,
                filePath = audio.absolutePath,
                status = TripAudioStatus.COMPLETE
            )
        )
        return OwnedTrip(trip, photo, audio)
    }

    private fun swipeTrip(studyDate: String) {
        onView(withId(R.id.tripsRecyclerView)).perform(
            RecyclerViewActions.actionOnItem<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                hasDescendant(withText(containsString(studyDate))),
                swipeLeft()
            )
        )
    }

    private fun launchHistory(): ActivityScenario<TripHistoryActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), TripHistoryActivity::class.java)
        )

    private data class OwnedTrip(val trip: Trip, val photo: java.io.File, val audio: java.io.File)
}
