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
            causeBreakdown = "{\"SIGNAL\":1,\"QUEUE\":1,\"BUS\":1,\"POTHOLE\":1}",
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
    fun `abandoned drafts can be cleaned up`() = runTest {
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

        for (trip in abandoned) {
            db.tripDao().deleteTripDataForTrip(trip.id)
            db.tripDao().deleteTrip(trip.id)
        }

        assertEquals(0, db.tripDao().getAbandonedTrips().size)
        assertEquals(0, db.tripDao().getAllTrips().size)
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
}
