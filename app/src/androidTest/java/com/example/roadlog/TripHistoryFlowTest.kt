package com.example.roadlog

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripHistoryFlowTest {
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
    fun historyListsCompletedTripAndOpensItsDetail() = runTest {
        val trip = fixtures.insertTrip(
            TestFixtures.tripA().copy(
                studyDateLocal = "2099-08-01",
                direction = ResearchDirection.A_TO_B,
                observationPeriod = ObservationPeriod.MORNING
            )
        )
        val rowLabel = "2099-08-01 · A_TO_B · MORNING"

        launchHistory().use {
            DeviceFlowUi.awaitTextContains(rowLabel)
            onView(withId(R.id.tripsRecyclerView))
                .check(matches(hasDescendant(withText(containsString(rowLabel)))))
            onView(withId(R.id.tripsRecyclerView)).perform(
                RecyclerViewActions.actionOnItem<androidx.recyclerview.widget.RecyclerView.ViewHolder>(
                    hasDescendant(withText(containsString(rowLabel))),
                    click()
                )
            )
            onView(withId(R.id.detailResearchText))
                .check(matches(withText(containsString("Study date: 2099-08-01"))))
            DeviceFlowUi.pressBack()
        }

        assertNotNull(fixtures.database.tripDao().getTripById(trip.id))
    }

    @Test
    fun interruptedTripDialogCanBeDismissedOrOpensTheSelectedTrip() = runTest {
        val trip = fixtures.insertTrip(
            TestFixtures.tripB().copy(
                status = TripStatus.RECOVERABLE,
                studyDateLocal = "2099-08-02",
                direction = ResearchDirection.B_TO_A,
                observationPeriod = ObservationPeriod.EVENING,
                interruptionReason = "DEVICE_FLOW_TEST_INTERRUPTION",
                recordingInterruption = true,
                partialTraversal = true
            )
        )

        launchHistory().use {
            onView(withId(R.id.reviewIncompleteButton)).perform(click())
            DeviceFlowUi.awaitTextContains("Trip ${trip.id}")
            onView(withText(containsString("Trip ${trip.id}"))).check(matches(isDisplayed()))
            DeviceFlowUi.clickTextContains("Close")
            assertNotNull(fixtures.database.tripDao().getTripById(trip.id))

            onView(withId(R.id.reviewIncompleteButton)).perform(click())
            DeviceFlowUi.clickTextContains("Trip ${trip.id}")
            DeviceFlowUi.awaitViewTextContains(R.id.detailResearchText, "Interruption: DEVICE_FLOW_TEST_INTERRUPTION")
            onView(withId(R.id.detailResearchText))
                .check(matches(withText(containsString("Interruption: DEVICE_FLOW_TEST_INTERRUPTION"))))
            DeviceFlowUi.pressBack()
        }

        assertNotNull(fixtures.database.tripDao().getTripById(trip.id))
    }

    @Test
    fun emptyHistoryDisplaysNoTripsState() {
        launchHistory().use {
            DeviceFlowUi.awaitTextContains("No trips recorded yet.")
            onView(withId(R.id.emptyText)).check(matches(isDisplayed()))
            onView(withText("No trips recorded yet.")).check(matches(isDisplayed()))
        }
    }

    private fun launchHistory(): ActivityScenario<TripHistoryActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), TripHistoryActivity::class.java)
        )
}
