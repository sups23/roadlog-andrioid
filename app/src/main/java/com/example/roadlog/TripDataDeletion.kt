package com.example.roadlog

import java.io.File

data class MediaDeletionReport(
    val attemptedFiles: Int,
    val deletedFiles: Int,
    val missingFiles: Int,
    val failures: List<String>
) {
    val isComplete: Boolean
        get() = missingFiles == 0 && failures.isEmpty()
}

/** Deletes media before the owning Room rows while retaining a report of failures. */
suspend fun TripDao.deleteTripWithMediaFiles(tripId: Long): MediaDeletionReport {
    val paths = buildList {
        addAll(getPhotosForTrip(tripId).map { it.filePath })
        addAll(getAudioForTrip(tripId).map { it.filePath })
    }.filter { it.isNotBlank() }

    var deleted = 0
    var missing = 0
    val failures = mutableListOf<String>()
    paths.distinct().forEach { path ->
        val file = File(path)
        when {
            !file.exists() -> missing++
            file.delete() && !file.exists() -> deleted++
            else -> failures += path
        }
    }

    // Remove database ownership even when a file could not be removed, so the
    // report identifies the possible orphan instead of leaving stale metadata.
    deleteTripCascade(tripId)
    return MediaDeletionReport(
        attemptedFiles = paths.distinct().size,
        deletedFiles = deleted,
        missingFiles = missing,
        failures = failures
    )
}
