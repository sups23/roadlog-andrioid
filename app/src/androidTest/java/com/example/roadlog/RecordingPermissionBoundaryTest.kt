package com.example.roadlog

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

/** Runs on an empty, permission-denied install; never explicitly sends ACTION_START. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RecordingPermissionBoundaryTest {
    private lateinit var fixtures: DeviceFlowFixtures
    private lateinit var targetContext: Context
    private val requiredPermissions = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    )
    private val originallyGrantedPermissions = linkedSetOf<String>()

    @Before
    fun setUp() {
        targetContext = ApplicationProvider.getApplicationContext()
        fixtures = DeviceFlowFixtures(targetContext)
        requiredPermissions.forEach { permission ->
            if (targetContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                originallyGrantedPermissions += permission
            }
        }
        runBlocking { fixtures.requireIsolatedInstall() }

        // Revoking our own UID's granted permissions can terminate instrumentation.
        // Establish the denied baseline outside the test process on a dedicated install.
        requiredPermissions.forEach { permission ->
            assertEquals(
                "Run permission tests on an empty install with $permission denied; reset permissions outside instrumentation",
                PackageManager.PERMISSION_DENIED,
                targetContext.checkSelfPermission(permission)
            )
        }
    }

    @After
    fun tearDown() {
        try {
            if (::targetContext.isInitialized && isLoggerServiceRunning()) {
                targetContext.startService(Intent(targetContext, LoggerService::class.java).apply {
                    action = LoggerService.ACTION_STOP
                })
                val deadline = SystemClock.uptimeMillis() + 10_000L
                while (isLoggerServiceRunning() && SystemClock.uptimeMillis() < deadline) {
                    SystemClock.sleep(50L)
                }
                assertFalse("Unexpected recorder must stop before fixture cleanup", isLoggerServiceRunning())
            }
            if (::fixtures.isInitialized) runBlocking { fixtures.cleanupOwnedData() }
        } finally {
            // Never revoke from this process. Newly granted test permissions remain
            // until the dedicated install is reset/uninstalled outside instrumentation.
            originallyGrantedPermissions.forEach { permission ->
                runShell("pm grant ${targetContext.packageName} $permission")
            }
        }
    }

    @Test
    fun deniedLocationAndMicrophonePermissionsDoNotCreateATripOrStartLogger() {
        assertFalse("LoggerService must be idle before this boundary test", isLoggerServiceRunning())

        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue(
                "startup permission request should be visible",
                DeviceFlowUi.clickPermissionDenyIfVisible()
            )
            // Android may present the requested permissions in one grouped dialog or two.
            DeviceFlowUi.clickPermissionDenyIfVisible(timeoutMs = 2_000L)
            DeviceFlowUi.clickTextIfVisible("Skip", timeoutMs = 2_000L)

            selectSpinnerOption(R.id.weatherSpinner, TripWeather.CLEAR)
            selectSpinnerOption(R.id.roadWetnessSpinner, RoadWetness.DRY)
            selectSpinnerOption(R.id.directionSpinner, ResearchDirection.A_TO_B)
            selectSpinnerOption(R.id.observationPeriodSpinner, "Morning peak")

            onView(withId(R.id.startButton)).perform(scrollTo())
                .check(matches(isEnabled()))
                .perform(click())

            assertTrue(
                "record-start permission request should be visible",
                DeviceFlowUi.clickPermissionDenyIfVisible()
            )
            DeviceFlowUi.clickPermissionDenyIfVisible(timeoutMs = 2_000L)
            // Toasts are transient and may disappear while the second permission
            // dialog is checked. Synchronize with the Activity's stable UI instead.
            onView(withId(R.id.startButton)).check(matches(isEnabled()))
            requiredPermissions.forEach { permission ->
                assertEquals(
                    "$permission must remain denied after the start request",
                    PackageManager.PERMISSION_DENIED,
                    targetContext.checkSelfPermission(permission)
                )
            }

            assertFalse(isLoggerServiceRunning())
            assertTrue(runBlocking { fixtures.database.tripDao().getAllTripsForExport().isEmpty() })
            onView(withId(R.id.stopButton)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
        }
    }

    private fun selectSpinnerOption(spinnerId: Int, optionText: String) {
        onView(withId(spinnerId)).perform(scrollTo(), click())
        DeviceFlowUi.clickTextContains(optionText)
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun locationApproximateAloneCannotStartRecording() {
        assertFalse(isLoggerServiceRunning())
        // This test runs after the all-denied boundary test and leaves grants in
        // place, avoiding an instrumentation-killing revoke during teardown.
        runShell("pm grant ${targetContext.packageName} ${Manifest.permission.ACCESS_COARSE_LOCATION}")
        runShell("pm grant ${targetContext.packageName} ${Manifest.permission.RECORD_AUDIO}")
        assertEquals(PackageManager.PERMISSION_GRANTED, targetContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED, targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        ActivityScenario.launch(MainActivity::class.java).use {
            DeviceFlowUi.clickPermissionDenyIfVisible()
            DeviceFlowUi.clickPermissionDenyIfVisible(timeoutMs = 2_000L)
            DeviceFlowUi.clickTextIfVisible("Skip", timeoutMs = 2_000L)

            assertEquals(PackageManager.PERMISSION_GRANTED, targetContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
            assertEquals(PackageManager.PERMISSION_DENIED, targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))

            selectSpinnerOption(R.id.weatherSpinner, TripWeather.CLEAR)
            selectSpinnerOption(R.id.roadWetnessSpinner, RoadWetness.DRY)
            selectSpinnerOption(R.id.directionSpinner, ResearchDirection.A_TO_B)
            selectSpinnerOption(R.id.observationPeriodSpinner, "Morning peak")
            onView(withId(R.id.startButton)).perform(scrollTo(), click())
            // A previously denied permission may be rejected without a new dialog.
            DeviceFlowUi.clickPermissionDenyIfVisible(timeoutMs = 2_000L)
            onView(withId(R.id.startButton)).check(matches(isEnabled()))
            assertEquals(PackageManager.PERMISSION_DENIED, targetContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertFalse(isLoggerServiceRunning())
            assertTrue(runBlocking { fixtures.database.tripDao().getAllTripsForExport().isEmpty() })
            onView(withId(R.id.stopButton)).check(matches(org.hamcrest.Matchers.not(isEnabled())))
        }
    }

    private fun isLoggerServiceRunning(): Boolean {
        val activityManager = targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == LoggerService::class.java.name }
    }

    private fun runShell(command: String) {
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }
}
