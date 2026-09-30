package com.example.roadlog

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSpinnerText
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripReviewFlowTest {
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
    fun reviewCanExplicitlyReclassifyV3TurningWithoutReplacingCaptureEvidence() = runTest {
        val (trip, event) = createReviewEvent(
            day = "2099-09-01",
            codebookVersion = "3",
            cause = "TURNING",
            configJson = """{"version":"3"}"""
        )

        launchDetail(trip).use {
            DeviceFlowUi.awaitTextInView("provisional TURNING (v3 legacy)")
            onView(withText(containsString("provisional TURNING (v3 legacy)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Primary cause and codebook version")
            onView(primaryCauseSpinner()).perform(click())
            onData(hasToString("TURNING (v4 residual)")).inRoot(isPlatformPopup()).perform(click())
            onView(primaryCauseSpinner()).check(matches(withSpinnerText("TURNING (v4 residual)")))
            onView(withText("Save")).perform(click())
            DeviceFlowUi.awaitTextInView("TURNING (v4 residual) · provisional TURNING (v3 legacy)")
            onView(withText(containsString("provisional TURNING (v3 legacy)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Primary cause and codebook version")
            onView(primaryCauseSpinner()).check(matches(withSpinnerText("TURNING (v4 residual)")))
            onView(withText("Cancel")).perform(click())
            DeviceFlowUi.pressBack()
        }

        val persisted = fixtures.database.tripDao().getTripEvent(event.eventId)!!
        val revisions = fixtures.database.tripDao().getAnnotationsForEvent(event.eventId)
        assertEquals("3", persisted.codebookVersion)
        assertEquals("TURNING", persisted.provisionalCauseCode)
        assertEquals("log turning", persisted.transcript)
        assertEquals(event.markerTimeMs, persisted.markerTimeMs)
        assertEquals(event.recognitionConfidence, persisted.recognitionConfidence)
        assertEquals(1, revisions.size)
        assertEquals("TURNING", revisions.single().primaryCauseCode)
        assertEquals("4", revisions.single().codebookVersion)
    }

    @Test
    fun v4ReviewOffersNewCauseAndExcludesV3Interpretation() = runTest {
        val (trip, event) = createReviewEvent(
            day = "2099-09-02",
            codebookVersion = "4",
            cause = "SPEED_BREAKER",
            configJson = """{"version":"4"}"""
        )

        launchDetail(trip).use {
            DeviceFlowUi.awaitTextInView("provisional Speed breaker (v4)")
            onView(withText(containsString("provisional Speed breaker (v4)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Primary cause and codebook version")
            onView(primaryCauseSpinner()).perform(click())
            onView(withText("TURNING (v3 legacy)")).inRoot(isPlatformPopup()).check(doesNotExist())
            onData(hasToString("Merging (v4)")).inRoot(isPlatformPopup()).perform(click())
            onView(withText("Save")).perform(click())
            DeviceFlowUi.awaitTextInView("Merging (v4)")
            onView(withText(containsString("provisional Speed breaker (v4)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Primary cause and codebook version")
            onView(primaryCauseSpinner()).check(matches(withSpinnerText("Merging (v4)")))
            onView(withText("Cancel")).perform(click())
            DeviceFlowUi.pressBack()
        }

        val persisted = fixtures.database.tripDao().getTripEvent(event.eventId)!!
        val revision = fixtures.database.tripDao().getAnnotationsForEvent(event.eventId).single()
        assertEquals("SPEED_BREAKER", persisted.provisionalCauseCode)
        assertEquals("4", persisted.codebookVersion)
        assertEquals("MERGING", persisted.primaryCauseCode)
        assertEquals("4", revision.codebookVersion)
        assertEquals("MERGING", revision.primaryCauseCode)
    }

    @Test
    fun unknownCaptureVersionRemainsUncertainAfterAVersionedReview() = runTest {
        val (trip, event) = createReviewEvent(
            day = "2099-09-03",
            codebookVersion = null,
            cause = "UNKNOWN",
            configJson = null
        )

        launchDetail(trip).use {
            DeviceFlowUi.awaitTextInView("provisional UNKNOWN (version uncertain)")
            onView(withText(containsString("provisional UNKNOWN (version uncertain)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Original cause version is uncertain")
            onView(primaryCauseSpinner()).perform(click())
            onData(hasToString("Parked car (v4)")).inRoot(isPlatformPopup()).perform(click())
            onView(withText("Save")).perform(click())
            DeviceFlowUi.awaitTextInView("Parked car (v4)")
            onView(withText(containsString("provisional UNKNOWN (version uncertain)"))).perform(scrollTo(), click())
            DeviceFlowUi.awaitTextContains("Original cause version is uncertain")
            onView(primaryCauseSpinner()).check(matches(withSpinnerText("Parked car (v4)")))
            onView(withText("Cancel")).perform(click())
            DeviceFlowUi.pressBack()
        }

        val persisted = fixtures.database.tripDao().getTripEvent(event.eventId)!!
        val revision = fixtures.database.tripDao().getAnnotationsForEvent(event.eventId).single()
        assertNull(trip.codebookVersion)
        assertNull(trip.causeConfigJson)
        assertNull(persisted.codebookVersion)
        assertEquals("UNKNOWN", persisted.provisionalCauseCode)
        assertEquals("PARKED_CAR", revision.primaryCauseCode)
        assertEquals("4", revision.codebookVersion)
    }

    @Test
    fun qaAndIncidentExclusionControlsPersistSelectedDecisions() = runTest {
        val trip = fixtures.insertTrip(
            TestFixtures.tripA().copy(
                studyDateLocal = "2099-09-04",
                qaStatus = TripQaStatus.UNREVIEWED
            )
        )

        launchDetail(trip).use {
            DeviceFlowUi.awaitViewLaidOut(R.id.qaValidButton)
            onView(withId(R.id.qaValidButton)).perform(scrollTo(), click())
            DeviceFlowUi.awaitViewTextContains(R.id.detailResearchText, "QA: VALID")
            assertEquals(TripQaStatus.VALID, fixtures.database.tripDao().getTripById(trip.id)?.qaStatus)

            onView(withId(R.id.incidentExclusionCheckBox)).perform(scrollTo(), click())
            onView(withId(R.id.saveExclusionButton)).perform(scrollTo(), click())
            DeviceFlowUi.awaitViewTextContains(R.id.detailResearchText, "Excluded from analysis: INCIDENT_OR_BREAKDOWN")
            assertEquals(
                TripExclusion.INCIDENT_OR_BREAKDOWN,
                fixtures.database.tripDao().getTripById(trip.id)?.exclusionCode
            )
        }
    }

    private suspend fun createReviewEvent(
        day: String,
        codebookVersion: String?,
        cause: String,
        configJson: String?
    ): Pair<Trip, TripEvent> {
        val trip = fixtures.insertTrip(
            TestFixtures.tripA().copy(
                eventCount = 1,
                causeBreakdown = """{"$cause":1}""",
                studyDateLocal = day,
                codebookVersion = codebookVersion,
                causeConfigJson = configJson
            )
        )
        val event = TripEvent(
            eventId = "device-flow-$day-${cause.lowercase()}",
            tripId = trip.id,
            markerTimeMs = trip.startTimeMs + 5_000,
            provisionalCauseCode = cause,
            primaryCauseCode = cause,
            transcript = if (cause == "TURNING") "log turning" else "log ${cause.lowercase()}",
            recognitionConfidence = 0.92f,
            codebookVersion = codebookVersion
        )
        fixtures.insertEvent(event)
        return trip to event
    }

    private fun launchDetail(trip: Trip): ActivityScenario<TripDetailActivity> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ActivityScenario.launch(
            Intent(context, TripDetailActivity::class.java).apply {
                putExtra(TripHistoryActivity.EXTRA_TRIP_ID, trip.id)
                putExtra(TripHistoryActivity.EXTRA_TRIP_START, trip.startTimeMs)
                putExtra(TripHistoryActivity.EXTRA_TRIP_END, trip.endTimeMs)
            }
        )
    }

    private fun primaryCauseSpinner(): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("cause-version review spinner")
        }

        override fun matchesSafely(view: View): Boolean {
            if (view !is Spinner) return false
            val parent = view.parent as? ViewGroup ?: return false
            // All three spinners share the form parent. Match only the one after
            // the cause label (possibly separated by the uncertain-version note).
            for (index in parent.indexOfChild(view) - 1 downTo 0) {
                val sibling = parent.getChildAt(index)
                if (sibling is Spinner) return false
                if (sibling is TextView && sibling.text.toString() == "Primary cause and codebook version") {
                    return true
                }
            }
            return false
        }
    }
}
