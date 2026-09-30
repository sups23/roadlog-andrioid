package com.example.roadlog

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFlowFixturesTest {
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
    fun isolationGuardRefusesExistingTripsWithoutDeletingThem() = runTest {
        val trip = fixtures.insertTrip(TestFixtures.tripA().copy(studyDateLocal = "2099-01-01"))

        try {
            fixtures.requireIsolatedInstall()
            fail("fixture guard accepted a database containing a trip")
        } catch (expected: IllegalStateException) {
            assertNotNull(fixtures.database.tripDao().getTripById(trip.id))
            assertEquals(1, fixtures.database.tripDao().getAllTripsForExport().size)
        }
    }

    @Test
    fun cleanupRemovesOnlyFixtureOwnedRowsAndReferencedFiles() = runTest {
        val trip = fixtures.insertTrip(TestFixtures.tripA().copy(studyDateLocal = "2099-01-02"))
        val media = fixtures.createMediaFile("fixture-photo.jpg", TestFixtures.generateJpegBytes())
        fixtures.insertPhoto(
            TripPhoto(tripId = trip.id, timestamp = trip.startTimeMs, filePath = media.absolutePath)
        )

        fixtures.cleanupOwnedData()

        assertEquals(null, fixtures.database.tripDao().getTripById(trip.id))
        assertEquals(0, fixtures.database.tripDao().getAllTripsForExport().size)
        assertFalse(media.exists())
    }
}
