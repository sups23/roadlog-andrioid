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
    fun `all trips show in history`() = runTest {
        db.tripDao().insertTrip(TestFixtures.tripA())
        db.tripDao().insertTrip(TestFixtures.tripB())

        val all = db.tripDao().getAllTrips()
        assertEquals(2, all.size)
        assertTrue(all[0].startTimeMs >= all[1].startTimeMs)
    }
}
