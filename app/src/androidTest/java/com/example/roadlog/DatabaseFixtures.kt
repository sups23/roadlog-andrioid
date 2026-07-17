package com.example.roadlog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File

object DatabaseFixtures {

    fun createInMemoryDb(): AppDatabase {
        return Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    fun createTestFileDb(context: Context): AppDatabase {
        val dbFile = File(context.filesDir, "test_roadlog.db")
        if (dbFile.exists()) dbFile.delete()
        return Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath).build()
    }

    fun writeJpegFile(dir: File, name: String): File {
        dir.mkdirs()
        val file = File(dir, name)
        val bytes = TestFixtures.generateJpegBytes()
        file.writeBytes(bytes)
        return file
    }

    /**
     * Insert a complete trip (summary + all child rows + photos) and return its generated ID.
     */
    suspend fun insertCompleteTrip(
        db: AppDatabase,
        trip: Trip,
        photoDir: File
    ): Long {
        val tripId = db.tripDao().insertTrip(trip)
        val rows = TestFixtures.allRowsForTrip(tripId, trip.startTimeMs, trip.endTimeMs)
        rows.chunked(500).forEach { chunk ->
            db.tripDao().insertAll(chunk)
        }
        val photos = TestFixtures.photosForTrip(tripId, trip.startTimeMs, trip.endTimeMs, 4, photoDir.absolutePath)
        photos.forEach { photo ->
            writeJpegFile(File(photo.filePath).parentFile!!, File(photo.filePath).name)
            db.tripDao().insertPhoto(photo)
        }
        return tripId
    }
}
