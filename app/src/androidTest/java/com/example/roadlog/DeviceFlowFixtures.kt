package com.example.roadlog

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Owns only rows/files explicitly created by an Activity-flow instrumentation test.
 * Tests must call [requireIsolatedInstall] before launching UI that reads all trips.
 */
internal class DeviceFlowFixtures(context: Context) {
    val database: AppDatabase = AppDatabase.getDatabase(context.applicationContext)
    private val ownedTripIds = linkedSetOf<Long>()
    private val ownedFiles = linkedSetOf<File>()
    private val mediaRoot = File(context.cacheDir, "device-flow-tests/${UUID.randomUUID()}")

    suspend fun requireIsolatedInstall() {
        val existingTrips = database.tripDao().getAllTripsForExport()
        check(existingTrips.isEmpty()) {
            "Refusing Activity-flow test: app database has ${existingTrips.size} trip(s). " +
                "Run these tests on a dedicated, empty test install."
        }
    }

    suspend fun insertTrip(trip: Trip): Trip {
        val testTrip = trip.copy(
            id = 0,
            tripUuid = "device-flow-${UUID.randomUUID()}"
        )
        val id = database.tripDao().insertTrip(testTrip)
        ownedTripIds += id
        return checkNotNull(database.tripDao().getTripById(id))
    }

    suspend fun insertEvent(event: TripEvent) {
        requireOwnedTrip(event.tripId)
        database.tripDao().insertEvents(listOf(event))
    }

    suspend fun insertData(rows: List<TripData>) {
        require(rows.all { it.tripId in ownedTripIds }) { "sensor rows must belong to fixture trips" }
        database.tripDao().insertAll(rows)
    }

    suspend fun insertPhoto(photo: TripPhoto) {
        requireOwnedTrip(photo.tripId)
        requireOwnedFilePath(photo.filePath)
        database.tripDao().insertPhoto(photo)
    }

    suspend fun insertAudio(audio: TripAudio) {
        requireOwnedTrip(audio.tripId)
        requireOwnedFilePath(audio.filePath)
        database.tripDao().insertAudio(audio)
    }

    fun createMediaFile(name: String, bytes: ByteArray = byteArrayOf(0x52, 0x4c)): File {
        require(name.isNotBlank() && File(name).name == name) { "media name must be a single safe path component" }
        check(mediaRoot.mkdirs() || mediaRoot.isDirectory) { "could not create fixture media directory" }
        val file = File(mediaRoot, name)
        check(!file.exists()) { "fixture media file already exists" }
        check(file.createNewFile()) { "could not create fixture media file" }
        file.writeBytes(bytes)
        ownedFiles += file.canonicalFile
        return file.canonicalFile
    }

    /** Removes only the trip IDs and exact files this fixture created. */
    suspend fun cleanupOwnedData() {
        var cleanupFailure: Throwable? = null
        ownedTripIds.toList().forEach { tripId ->
            try {
                database.tripDao().deleteTripWithMediaFiles(tripId)
                ownedTripIds.remove(tripId)
            } catch (failure: Throwable) {
                if (cleanupFailure == null) cleanupFailure = failure
            }
        }
        ownedFiles.toList().forEach { file ->
            if (!file.exists() || file.delete()) {
                ownedFiles.remove(file)
            } else if (cleanupFailure == null) {
                cleanupFailure = IllegalStateException("could not remove fixture-owned file ${file.path}")
            }
        }
        if (mediaRoot.isDirectory && mediaRoot.list()?.isEmpty() == true && !mediaRoot.delete() && cleanupFailure == null) {
            cleanupFailure = IllegalStateException("could not remove empty fixture directory ${mediaRoot.path}")
        }
        cleanupFailure?.let { throw it }
    }

    private fun requireOwnedTrip(tripId: Long) {
        require(tripId in ownedTripIds) { "trip $tripId is not owned by this test fixture" }
    }

    private fun requireOwnedFilePath(path: String) {
        val file = File(path).canonicalFile
        require(file in ownedFiles) { "media file is not owned by this test fixture: $path" }
    }
}
