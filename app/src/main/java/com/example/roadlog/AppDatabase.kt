package com.example.roadlog

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Room database for persistent trip backup and history viewing.
 */

@Entity(
    tableName = "trip_data",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["tripId", "timestamp"])
    ]
)
data class TripData(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long = 0,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: Float? = null,
    val accelX: Float? = null,
    val accelY: Float? = null,
    val accelZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val rotX: Float? = null,
    val rotY: Float? = null,
    val rotZ: Float? = null,
    val rotW: Float? = null,
    val eventCause: String? = null,
    val rawTimestamp: Long? = null,
    val sourceTimestampNanos: Long? = null,
    val callbackTimeMs: Long? = null,
    val sourceElapsedRealtimeNanos: Long? = null,
    val sourceEpochTimeMs: Long? = null,
    val provider: String? = null,
    val horizontalAccuracyMeters: Float? = null,
    val speedValid: Boolean? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDegrees: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val altitudeMeters: Double? = null,
    val sensorType: Int? = null,
    val sensorAccuracy: Int? = null,
    val sourceType: String? = null
)

object TripStatus {
    const val COMPLETED = 0
    const val RECORDING = 1
    const val ABORTED = 2
    const val ABANDONED = ABORTED
    const val INVALID = 3
    const val RECOVERABLE = 4
}

@Entity(tableName = "trips", indices = [Index(value = ["tripUuid"], unique = true)])
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val startNanoTime: Long = 0,
    val endNanoTime: Long = 0,
    val distanceMeters: Double,
    val eventCount: Int,
    val gpsPointCount: Int,
    val accelPointCount: Int,
    val causeBreakdown: String,
    val createdAt: Long,
    val status: Int = TripStatus.COMPLETED,
    @ColumnInfo(defaultValue = "''") val tripUuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "'STUDY_SESSION'") val sessionId: String = ResearchStudy.SESSION_ID,
    @ColumnInfo(defaultValue = "'STUDY_CORRIDOR'") val corridorId: String = ResearchStudy.CORRIDOR_ID,
    val direction: String? = null,
    val observationPeriod: String? = null,
    val deviceId: String? = null,
    val validityStatus: String? = null,
    val notes: String? = null,
    @ColumnInfo(defaultValue = "0") val lastWriteTimeMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val writeFailureCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val droppedSampleCount: Int = 0,
    val interruptionReason: String? = null,
    val codebookVersion: String? = null,
    val appVersion: String? = null,
    val schemaVersion: Int? = null,
    val studyDateLocal: String? = null,
    val timeZoneId: String? = null,
    @ColumnInfo(defaultValue = "UNREVIEWED") val qaStatus: String = TripQaStatus.UNREVIEWED,
    val qaNotes: String? = null,
    @ColumnInfo(defaultValue = "0") val routeDiversion: Boolean = false,
    @ColumnInfo(defaultValue = "0") val recordingInterruption: Boolean = false,
    @ColumnInfo(defaultValue = "0") val gpsInterruption: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sensorInterruption: Boolean = false,
    @ColumnInfo(defaultValue = "0") val partialTraversal: Boolean = false,
    val coverageEndTimeMs: Long? = null,
    val coverageEndLatitude: Double? = null,
    val coverageEndLongitude: Double? = null,
    val continuationOfTripUuid: String? = null,
    val sensorProfileVersion: String? = null
)

@Entity(
    tableName = "trip_photos",
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["captureId"], unique = true),
        Index(value = ["eventId"])
    ]
)
data class TripPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val filePath: String,
    @ColumnInfo(defaultValue = "''") val captureId: String = UUID.randomUUID().toString(),
    val eventId: String? = null,
    val requestTimeMs: Long? = null,
    val captureTimeMs: Long? = null,
    val requestElapsedRealtimeNanos: Long? = null,
    val captureElapsedRealtimeNanos: Long? = null,
    val locationAccuracyMeters: Float? = null,
    val locationProvider: String? = null,
    val locationFixTimeMs: Long? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val usabilityStatus: String? = null,
    val privacyStatus: String? = null
)

@Entity(
    tableName = "trip_events",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId", "markerTimeMs"])]
)
data class TripEvent(
    @PrimaryKey val eventId: String,
    val tripId: Long,
    val markerTimeMs: Long,
    val markerElapsedRealtimeNanos: Long? = null,
    val experiencedLatitude: Double? = null,
    val experiencedLongitude: Double? = null,
    val sourceLatitude: Double? = null,
    val sourceLongitude: Double? = null,
    val sourceLocationVisible: Boolean? = null,
    val locationAccuracyMeters: Float? = null,
    val locationProvider: String? = null,
    val locationFixTimeMs: Long? = null,
    val locationFixElapsedRealtimeNanos: Long? = null,
    val locationFixAgeMs: Long? = null,
    val speedValid: Boolean? = null,
    val speedKmh: Float? = null,
    val primaryCauseCode: String? = null,
    val confidenceCode: Int? = null,
    val trafficState: String? = null,
    val status: String = EventStatus.PENDING,
    val provenance: String = EventProvenance.MANUAL_MARKER,
    val transcript: String? = null,
    val recognitionConfidence: Float? = null,
    val codebookVersion: String? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "event_secondary_causes",
    primaryKeys = ["eventId", "causeCode"],
    foreignKeys = [
        ForeignKey(
            entity = TripEvent::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["eventId"])]
)
data class EventSecondaryCause(
    val eventId: String,
    val causeCode: String,
    val codebookVersion: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "sensor_metadata",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId"])]
)
data class SensorMetadata(
    @PrimaryKey val metadataId: String,
    val tripId: Long,
    val sensorType: Int,
    val sensorName: String? = null,
    val vendor: String? = null,
    val version: String? = null,
    val resolution: Float? = null,
    val maximumRange: Float? = null,
    val selectedProfile: String? = null,
    val registrationResult: String? = null,
    val sensorProfileVersion: String? = null,
    val requestedPeriodUs: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "trip_quality",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TripQuality(
    @PrimaryKey val tripId: Long,
    val gpsSampleCount: Int = 0,
    val gpsAvailabilityPercent: Double? = null,
    val medianGpsAccuracyMeters: Double? = null,
    val accelSampleCount: Int = 0,
    val accelEffectiveHz: Double? = null,
    val gyroSampleCount: Int = 0,
    val gyroEffectiveHz: Double? = null,
    val rotationSampleCount: Int = 0,
    val rotationEffectiveHz: Double? = null,
    val medianIntervalMs: Long? = null,
    val p05IntervalMs: Long? = null,
    val p95IntervalMs: Long? = null,
    val longestGapMs: Long? = null,
    val accelMedianIntervalMs: Long? = null,
    val accelLongestGapMs: Long? = null,
    val gyroMedianIntervalMs: Long? = null,
    val gyroLongestGapMs: Long? = null,
    val rotationMedianIntervalMs: Long? = null,
    val rotationLongestGapMs: Long? = null,
    val gpsLongestGapMs: Long? = null,
    val timeToFirstGpsFixMs: Long? = null,
    val manualEventMarkerCount: Int = 0,
    val photoCount: Int = 0,
    val audioSegmentCount: Int = 0,
    val gpsProviderJson: String = "{}",
    val gpsBelowFivePercent: Double? = null,
    val gpsBelowTenPercent: Double? = null,
    val gpsAbovePoorQualityPercent: Double? = null,
    val invalidSpeedCount: Int = 0,
    val unavailableSpeedCount: Int = 0,
    val duplicateTimestampCount: Int = 0,
    val nonMonotonicTimestampCount: Int = 0,
    val interruptionCount: Int = 0,
    val storageFailureCount: Int = 0,
    val droppedSampleCount: Int = 0,
    val warningsJson: String = "[]",
    val completeness: String = "UNKNOWN",
    val generatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "trip_audio",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TripEvent::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index(value = ["tripId"]), Index(value = ["eventId"])]
)
data class TripAudio(
    @PrimaryKey val audioId: String,
    val tripId: Long,
    val eventId: String? = null,
    val startTimeMs: Long,
    val endTimeMs: Long? = null,
    val startElapsedRealtimeNanos: Long? = null,
    val endElapsedRealtimeNanos: Long? = null,
    val segmentSequence: Int = 0,
    val filePath: String,
    val mimeType: String = "audio/mp4",
    val codec: String? = "AAC-LC",
    val sampleRateHz: Int? = 16_000,
    val channelCount: Int? = 1,
    val fileSizeBytes: Long? = null,
    val sha256: String? = null,
    val interruptionReason: String? = null,
    val transcript: String? = null,
    val recognitionConfidence: Float? = null,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "audit_revisions",
    foreignKeys = [
        ForeignKey(
            entity = Trip::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TripEvent::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tripId"]), Index(value = ["eventId"])]
)
data class AuditRevision(
    @PrimaryKey val revisionId: String,
    val tripId: Long? = null,
    val eventId: String? = null,
    val fieldName: String,
    val originalValue: String? = null,
    val currentValue: String? = null,
    val editor: String? = null,
    val revisionTimeMs: Long,
    val reason: String? = null,
    val revisionType: String
)

@Entity(
    tableName = "event_annotations",
    foreignKeys = [
        ForeignKey(
            entity = TripEvent::class,
            parentColumns = ["eventId"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["eventId", "annotationVersion"], unique = true)]
)
data class EventAnnotationRevision(
    @PrimaryKey val annotationId: String,
    val eventId: String,
    val annotationVersion: Int,
    val annotationTimestampMs: Long,
    val reviewerId: String? = null,
    val primaryCauseCode: String,
    val secondaryCause1: String? = null,
    val secondaryCause2: String? = null,
    val trafficState: String? = null,
    val confidenceCode: Int? = null,
    val notes: String? = null,
    val codebookVersion: String,
    val supersedesAnnotationId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface TripDao {
    @Insert
    suspend fun insertAll(rows: List<TripData>)

    @Insert
    suspend fun insertEvents(events: List<TripEvent>)

    @Transaction
    suspend fun insertBatch(rows: List<TripData>, events: List<TripEvent>) {
        rows.chunked(500).forEach { chunk -> insertAll(chunk) }
        events.chunked(100).forEach { chunk -> insertEvents(chunk) }
    }

    @Insert
    suspend fun insertTrip(trip: Trip): Long

    @Query("SELECT * FROM trip_data ORDER BY timestamp")
    suspend fun getAll(): List<TripData>

    @Query("DELETE FROM trip_data")
    suspend fun deleteAll()

    @Query("SELECT * FROM trips WHERE status != ${TripStatus.RECORDING} ORDER BY startTimeMs DESC")
    suspend fun getAllTrips(): List<Trip>

    @Query("SELECT * FROM trips ORDER BY startTimeMs ASC")
    suspend fun getAllTripsForExport(): List<Trip>

    @Query("SELECT * FROM trips WHERE id = :tripId LIMIT 1")
    suspend fun getTripById(tripId: Long): Trip?

    @Query("SELECT id FROM trips WHERE startTimeMs = :startMs LIMIT 1")
    suspend fun getTripIdByStartTime(startMs: Long): Long?

    @Query("SELECT * FROM trip_data WHERE timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp")
    suspend fun getGpsForTimeRange(fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE timestamp BETWEEN :fromMs AND :toMs AND eventCause IS NOT NULL ORDER BY timestamp")
    suspend fun getEventsForTimeRange(fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp")
    suspend fun getGpsForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND eventCause IS NOT NULL ORDER BY timestamp")
    suspend fun getEventsForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND accelZ IS NOT NULL ORDER BY timestamp")
    suspend fun getAccelForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND gyroX IS NOT NULL ORDER BY timestamp")
    suspend fun getGyroForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND rotW IS NOT NULL ORDER BY timestamp")
    suspend fun getRotationForTrip(tripId: Long, fromMs: Long, toMs: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getGpsForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND accelZ IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getAccelForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND gyroX IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getGyroForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND timestamp BETWEEN :fromMs AND :toMs AND rotW IS NOT NULL ORDER BY timestamp LIMIT :limit")
    suspend fun getRotationForTripCapped(tripId: Long, fromMs: Long, toMs: Long, limit: Int): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId ORDER BY timestamp, id")
    suspend fun getAllDataForTrip(tripId: Long): List<TripData>

    @Query("SELECT * FROM trip_data WHERE tripId = :tripId AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun getDataForTripPage(tripId: Long, afterId: Long, limit: Int): List<TripData>

    @Query("SELECT COUNT(*) FROM trip_data WHERE tripId = :tripId")
    suspend fun countDataForTrip(tripId: Long): Int

    @Query("SELECT COUNT(*) FROM trip_events WHERE tripId = :tripId")
    suspend fun countEventsForTrip(tripId: Long): Int

    @Query("SELECT COUNT(*) FROM trip_events WHERE tripId = :tripId AND provenance = 'MANUAL_MARKER'")
    suspend fun countManualEventsForTrip(tripId: Long): Int

    @Query("SELECT COUNT(*) FROM trip_photos WHERE tripId = :tripId")
    suspend fun countPhotosForTrip(tripId: Long): Int

    @Query("SELECT COUNT(*) FROM trip_audio WHERE tripId = :tripId")
    suspend fun countAudioForTrip(tripId: Long): Int

    @Query("SELECT * FROM trip_events WHERE tripId = :tripId ORDER BY markerTimeMs, eventId")
    suspend fun getTripEvents(tripId: Long): List<TripEvent>

    @Query("SELECT * FROM trip_events WHERE eventId = :eventId LIMIT 1")
    suspend fun getTripEvent(eventId: String): TripEvent?

    @Query("SELECT * FROM event_annotations WHERE eventId = :eventId ORDER BY annotationVersion")
    suspend fun getAnnotationsForEvent(eventId: String): List<EventAnnotationRevision>

    @Query("SELECT * FROM event_annotations ORDER BY annotationTimestampMs, annotationId")
    suspend fun getAllAnnotationsForExport(): List<EventAnnotationRevision>

    @Query("SELECT COALESCE(MAX(annotationVersion), 0) FROM event_annotations WHERE eventId = :eventId")
    suspend fun getLatestAnnotationVersion(eventId: String): Int

    @Query("SELECT * FROM event_secondary_causes WHERE eventId = :eventId ORDER BY createdAt, causeCode")
    suspend fun getSecondaryCausesForEvent(eventId: String): List<EventSecondaryCause>

    @Query("SELECT * FROM event_secondary_causes ORDER BY eventId, createdAt, causeCode")
    suspend fun getAllSecondaryCausesForExport(): List<EventSecondaryCause>

    @Query("UPDATE trip_events SET primaryCauseCode = :primaryCauseCode, confidenceCode = :confidenceCode, trafficState = :trafficState, status = 'ANNOTATED', notes = :notes, codebookVersion = :codebookVersion WHERE eventId = :eventId")
    suspend fun updateEventAnnotation(
        eventId: String,
        primaryCauseCode: String,
        confidenceCode: Int?,
        trafficState: String?,
        notes: String?,
        codebookVersion: String
    ): Int

    @Query("UPDATE trip_events SET sourceLatitude = :latitude, sourceLongitude = :longitude, sourceLocationVisible = :visible WHERE eventId = :eventId")
    suspend fun updateEventSourceLocation(
        eventId: String,
        latitude: Double?,
        longitude: Double?,
        visible: Boolean
    ): Int

    @Query("DELETE FROM event_secondary_causes WHERE eventId = :eventId")
    suspend fun deleteSecondaryCauses(eventId: String)

    @Transaction
    suspend fun annotateEvent(
        eventId: String,
        annotation: EventAnnotation,
        notes: String? = null
    ) {
        val primaryCause = requireNotNull(annotation.primaryCauseCode) {
            "an annotated event requires one primary cause"
        }
        EventAnnotationValidator.requireValid(annotation)
        check(
            updateEventAnnotation(
                eventId = eventId,
                primaryCauseCode = primaryCause,
                confidenceCode = annotation.confidenceCode,
                trafficState = annotation.trafficState,
                notes = notes,
                codebookVersion = ResearchCodebook.VERSION
            ) == 1
        ) { "event $eventId does not exist" }
        val previous = getAnnotationsForEvent(eventId).lastOrNull()
        val version = getLatestAnnotationVersion(eventId) + 1
        insertAnnotationRevision(
            EventAnnotationRevision(
                annotationId = UUID.randomUUID().toString(),
                eventId = eventId,
                annotationVersion = version,
                annotationTimestampMs = annotation.annotationTimestampMs,
                reviewerId = annotation.reviewerId,
                primaryCauseCode = primaryCause,
                secondaryCause1 = annotation.secondaryCauseCodes.getOrNull(0),
                secondaryCause2 = annotation.secondaryCauseCodes.getOrNull(1),
                trafficState = annotation.trafficState,
                confidenceCode = annotation.confidenceCode,
                notes = notes ?: annotation.notes,
                codebookVersion = ResearchCodebook.VERSION,
                supersedesAnnotationId = previous?.annotationId
            )
        )
        deleteSecondaryCauses(eventId)
        insertSecondaryCauses(annotation.secondaryCauseCodes.map { cause ->
            EventSecondaryCause(eventId = eventId, causeCode = cause, codebookVersion = ResearchCodebook.VERSION)
        })
    }

    @Transaction
    suspend fun updateEventSourceLocationWithAudit(
        eventId: String,
        latitude: Double?,
        longitude: Double?,
        visible: Boolean,
        editor: String? = null,
        reason: String? = null
    ) {
        val event = getTripEvent(eventId) ?: error("event $eventId does not exist")
        insertAuditRevision(
            AuditRevision(
                revisionId = UUID.randomUUID().toString(),
                tripId = event.tripId,
                eventId = eventId,
                fieldName = "source_location",
                originalValue = listOf(event.sourceLatitude, event.sourceLongitude, event.sourceLocationVisible).joinToString(","),
                currentValue = listOf(latitude, longitude, visible).joinToString(","),
                editor = editor,
                revisionTimeMs = System.currentTimeMillis(),
                reason = reason,
                revisionType = "SOURCE_LOCATION_CORRECTION"
            )
        )
        check(updateEventSourceLocation(eventId, latitude, longitude, visible) == 1)
    }

    @Query("SELECT * FROM trip_quality ORDER BY tripId")
    suspend fun getAllTripQuality(): List<TripQuality>

    @Query("SELECT * FROM sensor_metadata ORDER BY tripId, sensorType")
    suspend fun getAllSensorMetadataForExport(): List<SensorMetadata>

    @Query("SELECT * FROM audit_revisions ORDER BY revisionTimeMs, revisionId")
    suspend fun getAllAuditRevisionsForExport(): List<AuditRevision>

    @Query("SELECT * FROM trip_audio ORDER BY startTimeMs, audioId")
    suspend fun getAllAudioForExport(): List<TripAudio>

    @Query("SELECT * FROM trip_audio WHERE tripId = :tripId ORDER BY startTimeMs, audioId")
    suspend fun getAudioForTrip(tripId: Long): List<TripAudio>

    @Query("SELECT * FROM trip_audio WHERE audioId = :audioId LIMIT 1")
    suspend fun getAudioById(audioId: String): TripAudio?

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: Long)

    @Transaction
    suspend fun deleteTripCascade(tripId: Long) {
        deletePhotosForTrip(tripId)
        deleteTripDataForTrip(tripId)
        deleteSensorMetadataForTrip(tripId)
        deleteAudioForTrip(tripId)
        deleteAuditRevisionsForTrip(tripId)
        deleteTrip(tripId)
    }

    @Transaction
    suspend fun finalizeTrip(
        tripId: Long,
        endTimeMs: Long,
        endNanoTime: Long,
        distanceMeters: Double,
        eventCount: Int,
        gpsPointCount: Int,
        accelPointCount: Int,
        causeBreakdown: String,
        createdAt: Long
    ) {
        val updated = updateTripSummary(
            tripId,
            endTimeMs,
            endNanoTime,
            distanceMeters,
            eventCount,
            gpsPointCount,
            accelPointCount,
            causeBreakdown,
            createdAt
        )
        check(updated == 1) { "Trip $tripId is not an active recording" }
        val completed = markTripCompleted(tripId)
        check(completed == 1) { "Trip $tripId could not be completed" }
    }

    @Query("""
        UPDATE trips SET
            endTimeMs = :endTimeMs,
            endNanoTime = :endNanoTime,
            distanceMeters = :distanceMeters,
            eventCount = :eventCount,
            gpsPointCount = :gpsPointCount,
            accelPointCount = :accelPointCount,
            causeBreakdown = :causeBreakdown,
            createdAt = :createdAt
         WHERE id = :tripId AND status = ${TripStatus.RECORDING}
    """)
    suspend fun updateTripSummary(
        tripId: Long,
        endTimeMs: Long,
        endNanoTime: Long,
        distanceMeters: Double,
        eventCount: Int,
        gpsPointCount: Int,
        accelPointCount: Int,
        causeBreakdown: String,
        createdAt: Long
    ): Int

    @Query("UPDATE trips SET status = ${TripStatus.COMPLETED}, validityStatus = 'COLLECTED', interruptionReason = NULL WHERE id = :tripId AND status = ${TripStatus.RECORDING}")
    suspend fun markTripCompleted(tripId: Long): Int

    @Query("UPDATE trips SET qaStatus = :qaStatus, qaNotes = :qaNotes WHERE id = :tripId")
    suspend fun updateTripQa(tripId: Long, qaStatus: String, qaNotes: String?): Int

    @Query("SELECT * FROM trips WHERE status = ${TripStatus.RECORDING}")
    suspend fun getAbandonedTrips(): List<Trip>

    @Query("SELECT * FROM trips WHERE status != ${TripStatus.COMPLETED} ORDER BY startTimeMs ASC")
    suspend fun getIncompleteTrips(): List<Trip>

    @Query("UPDATE trips SET status = :status, endTimeMs = :endTimeMs, endNanoTime = :endNanoTime, interruptionReason = :reason, lastWriteTimeMs = :lastWriteTimeMs, writeFailureCount = :writeFailureCount, droppedSampleCount = :droppedSampleCount, qaStatus = 'UNREVIEWED', recordingInterruption = 1, gpsInterruption = :gpsInterruption, sensorInterruption = :sensorInterruption, partialTraversal = 1, coverageEndTimeMs = :endTimeMs, coverageEndLatitude = :coverageEndLatitude, coverageEndLongitude = :coverageEndLongitude WHERE id = :tripId AND status = ${TripStatus.RECORDING}")
    suspend fun markTripInterrupted(
        tripId: Long,
        status: Int,
        endTimeMs: Long,
        endNanoTime: Long,
        reason: String,
        lastWriteTimeMs: Long,
        writeFailureCount: Int = 0,
        droppedSampleCount: Int = 0,
        coverageEndLatitude: Double? = null,
        coverageEndLongitude: Double? = null,
        gpsInterruption: Boolean = false,
        sensorInterruption: Boolean = false
    ): Int

    @Query("UPDATE trips SET lastWriteTimeMs = :lastWriteTimeMs, writeFailureCount = :writeFailureCount, droppedSampleCount = :droppedSampleCount WHERE id = :tripId AND status = ${TripStatus.RECORDING}")
    suspend fun updateTripHealth(
        tripId: Long,
        lastWriteTimeMs: Long,
        writeFailureCount: Int,
        droppedSampleCount: Int
    ): Int

    @Query("DELETE FROM trip_data WHERE tripId = :tripId")
    suspend fun deleteTripDataForTrip(tripId: Long)

    @Query("DELETE FROM sensor_metadata WHERE tripId = :tripId")
    suspend fun deleteSensorMetadataForTrip(tripId: Long)

    @Query("DELETE FROM trip_audio WHERE tripId = :tripId")
    suspend fun deleteAudioForTrip(tripId: Long)

    @Query("DELETE FROM audit_revisions WHERE tripId = :tripId")
    suspend fun deleteAuditRevisionsForTrip(tripId: Long)

    @Insert
    suspend fun insertPhoto(photo: TripPhoto): Long

    @Query("SELECT * FROM trip_photos WHERE tripId = :tripId ORDER BY timestamp ASC")
    suspend fun getPhotosForTrip(tripId: Long): List<TripPhoto>

    @Query("SELECT * FROM trip_photos WHERE captureId = :captureId LIMIT 1")
    suspend fun getPhotoByCaptureId(captureId: String): TripPhoto?

    @Query("SELECT * FROM trip_photos ORDER BY timestamp ASC")
    suspend fun getAllPhotosForExport(): List<TripPhoto>

    @Query("DELETE FROM trip_photos WHERE tripId = :tripId")
    suspend fun deletePhotosForTrip(tripId: Long)

    @Update
    suspend fun updatePhoto(photo: TripPhoto)

    @Query("UPDATE trip_photos SET filePath = :filePath, captureTimeMs = :captureTimeMs, captureElapsedRealtimeNanos = :captureElapsedRealtimeNanos, fileSizeBytes = :fileSizeBytes, sha256 = :sha256, width = :width, height = :height, usabilityStatus = :usabilityStatus WHERE captureId = :captureId")
    suspend fun completePhoto(
        captureId: String,
        filePath: String,
        captureTimeMs: Long,
        captureElapsedRealtimeNanos: Long?,
        fileSizeBytes: Long,
        sha256: String?,
        width: Int?,
        height: Int?,
        usabilityStatus: String
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTripQuality(quality: TripQuality)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSensorMetadata(metadata: List<SensorMetadata>)

    @Query("UPDATE sensor_metadata SET registrationResult = :result WHERE tripId = :tripId AND sensorType = :sensorType")
    suspend fun updateSensorRegistration(tripId: Long, sensorType: Int, result: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudio(audio: TripAudio)

    @Query("UPDATE trip_audio SET endTimeMs = :endTimeMs, endElapsedRealtimeNanos = :endElapsedRealtimeNanos, fileSizeBytes = :fileSizeBytes, sha256 = :sha256, status = :status, interruptionReason = :interruptionReason WHERE audioId = :audioId")
    suspend fun completeAudio(
        audioId: String,
        endTimeMs: Long,
        endElapsedRealtimeNanos: Long?,
        fileSizeBytes: Long?,
        sha256: String?,
        status: String,
        interruptionReason: String?
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSecondaryCauses(causes: List<EventSecondaryCause>)

    @Insert
    suspend fun insertAuditRevision(revision: AuditRevision)

    @Insert
    suspend fun insertAnnotationRevision(annotation: EventAnnotationRevision)
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                startTimeMs INTEGER NOT NULL,
                endTimeMs INTEGER NOT NULL,
                distanceMeters REAL NOT NULL,
                eventCount INTEGER NOT NULL,
                gpsPointCount INTEGER NOT NULL,
                accelPointCount INTEGER NOT NULL,
                causeBreakdown TEXT NOT NULL,
                createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_data ADD COLUMN tripId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rawTimestamp INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN startNanoTime INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN endNanoTime INTEGER NOT NULL DEFAULT 0")

        backfillLegacyAccelData(db)
    }

    /**
     * Best-effort backfill for accelerometer rows recorded before the v3 schema.
     *
     * Old rows were stored with raw System.nanoTime() in the `timestamp` column and
     * had no trip association. Because the old LoggerService inserted accelerometer
     * rows before GPS rows for each trip, we can locate an old trip's accel block as
     * the accel rows whose `id` lies between the previous trip's GPS block and the
     * current trip's first GPS row.
     */
    private fun backfillLegacyAccelData(db: SupportSQLiteDatabase) {
        // Load all existing trips sorted by start time.
        val trips = mutableListOf<TripBounds>()
        db.query("SELECT id, startTimeMs, endTimeMs FROM trips ORDER BY startTimeMs ASC").use { cursor ->
            while (cursor.moveToNext()) {
                trips.add(
                    TripBounds(
                        tripId = cursor.getLong(0),
                        startMs = cursor.getLong(1),
                        endMs = cursor.getLong(2)
                    )
                )
            }
        }
        if (trips.isEmpty()) return

        // Load all old GPS rows once. Their `id` order matches insertion order.
        val gpsRows = mutableListOf<GpsRow>()
        db.query(
            "SELECT id, timestamp FROM trip_data WHERE tripId = 0 AND latitude IS NOT NULL AND longitude IS NOT NULL ORDER BY id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                gpsRows.add(GpsRow(id = cursor.getLong(0), timestamp = cursor.getLong(1)))
            }
        }
        if (gpsRows.isEmpty()) return

        // Assign GPS rows to trips by timestamp to obtain per-trip id bounds.
        var tripIndex = 0
        for (gps in gpsRows) {
            while (tripIndex < trips.size && gps.timestamp > trips[tripIndex].endMs) {
                tripIndex++
            }
            if (tripIndex >= trips.size) break
            val bounds = trips[tripIndex]
            if (gps.timestamp in bounds.startMs..bounds.endMs) {
                db.execSQL(
                    "UPDATE trip_data SET tripId = ? WHERE id = ?",
                    arrayOf<Any?>(bounds.tripId, gps.id)
                )
                if (bounds.minGpsId == null || gps.id < bounds.minGpsId!!) {
                    bounds.minGpsId = gps.id
                }
                if (bounds.maxGpsId == null || gps.id > bounds.maxGpsId!!) {
                    bounds.maxGpsId = gps.id
                }
            }
        }

        // Convert the accel block for each trip from nanotime to wall-clock ms.
        var previousMaxId: Long = 0
        for (bounds in trips) {
            val minId = bounds.minGpsId ?: continue
            val lowerId = previousMaxId + 1
            val upperId = minId - 1
            if (lowerId > upperId) {
                previousMaxId = bounds.maxGpsId ?: minId
                continue
            }

            val nanoRange = db.accelNanoRange(lowerId, upperId)
            if (nanoRange == null) {
                previousMaxId = bounds.maxGpsId ?: minId
                continue
            }
            val (startNano, endNano) = nanoRange

            db.execSQL(
                "UPDATE trip_data SET rawTimestamp = timestamp, tripId = ?, timestamp = ? + ((timestamp - ?) / 1000000) WHERE tripId = 0 AND accelZ IS NOT NULL AND id >= ? AND id <= ?",
                arrayOf<Any?>(
                    bounds.tripId,
                    bounds.startMs,
                    startNano,
                    lowerId,
                    upperId
                )
            )

            db.execSQL(
                "UPDATE trips SET startNanoTime = ?, endNanoTime = ? WHERE id = ?",
                arrayOf<Any?>(startNano, endNano, bounds.tripId)
            )

            previousMaxId = bounds.maxGpsId ?: minId
        }

        // Legacy event rows had no ownership either. Their timestamps are wall-clock
        // values, so assign only rows that fall unambiguously inside a trip window.
        for (trip in trips) {
            db.execSQL(
                "UPDATE trip_data SET tripId = ? WHERE tripId = 0 AND eventCause IS NOT NULL AND timestamp BETWEEN ? AND ?",
                arrayOf<Any?>(trip.tripId, trip.startMs, trip.endMs)
            )
        }
    }

    private fun SupportSQLiteDatabase.accelNanoRange(lowerId: Long, upperId: Long): Pair<Long, Long>? {
        query(
            "SELECT MIN(timestamp), MAX(timestamp) FROM trip_data WHERE tripId = 0 AND accelZ IS NOT NULL AND id >= ? AND id <= ?",
            arrayOf<Any?>(lowerId, upperId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0) && !cursor.isNull(1)) {
                return cursor.getLong(0) to cursor.getLong(1)
            }
        }
        return null
    }

}

private data class TripBounds(
    val tripId: Long,
    val startMs: Long,
    val endMs: Long,
    var minGpsId: Long? = null,
    var maxGpsId: Long? = null
)

private data class GpsRow(val id: Long, val timestamp: Long)

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trip_data ADD COLUMN accelX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN accelY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN gyroZ REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotX REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotY REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotZ REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN rotW REAL")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_photos (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                tripId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                latitude REAL,
                longitude REAL,
                filePath TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_photos_tripId ON trip_photos(tripId)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE trips ADD COLUMN status INTEGER NOT NULL DEFAULT ${TripStatus.COMPLETED}")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_data_tripId ON trip_data(tripId)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v7 is the pre-pilot research schema. Keep legacy samples and add the
        // finalized trip contract, including static study identifiers.
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sourceTimestampNanos INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN callbackTimeMs INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sourceElapsedRealtimeNanos INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sourceEpochTimeMs INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN provider TEXT")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN horizontalAccuracyMeters REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN speedValid INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN speedAccuracyMps REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN bearingDegrees REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN bearingAccuracyDegrees REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN altitudeMeters REAL")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sensorType INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sensorAccuracy INTEGER")
        db.execSQL("ALTER TABLE trip_data ADD COLUMN sourceType TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_data_tripId_timestamp ON trip_data(tripId, timestamp)")

        db.execSQL("ALTER TABLE trips ADD COLUMN tripUuid TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE trips ADD COLUMN sessionId TEXT NOT NULL DEFAULT '${ResearchStudy.SESSION_ID}'")
        db.execSQL("ALTER TABLE trips ADD COLUMN corridorId TEXT NOT NULL DEFAULT '${ResearchStudy.CORRIDOR_ID}'")
        db.execSQL("ALTER TABLE trips ADD COLUMN direction TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN observationPeriod TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN deviceId TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN validityStatus TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN notes TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN lastWriteTimeMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN writeFailureCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN droppedSampleCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN interruptionReason TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN codebookVersion TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN appVersion TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN schemaVersion INTEGER")
        db.execSQL("UPDATE trips SET tripUuid = lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) WHERE tripUuid = ''")
        db.execSQL("ALTER TABLE trips ADD COLUMN studyDateLocal TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN timeZoneId TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN qaStatus TEXT NOT NULL DEFAULT 'UNREVIEWED'")
        db.execSQL("ALTER TABLE trips ADD COLUMN qaNotes TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN routeDiversion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN recordingInterruption INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN gpsInterruption INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN sensorInterruption INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN partialTraversal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE trips ADD COLUMN coverageEndTimeMs INTEGER")
        db.execSQL("ALTER TABLE trips ADD COLUMN coverageEndLatitude REAL")
        db.execSQL("ALTER TABLE trips ADD COLUMN coverageEndLongitude REAL")
        db.execSQL("ALTER TABLE trips ADD COLUMN continuationOfTripUuid TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN sensorProfileVersion TEXT")
        db.execSQL("DROP INDEX IF EXISTS index_trips_tripUuid")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trips_tripUuid ON trips(tripUuid)")

        db.execSQL("ALTER TABLE trip_photos ADD COLUMN captureId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN eventId TEXT")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN requestTimeMs INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN captureTimeMs INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN requestElapsedRealtimeNanos INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN captureElapsedRealtimeNanos INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN locationAccuracyMeters REAL")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN locationProvider TEXT")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN locationFixTimeMs INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN mimeType TEXT")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN fileSizeBytes INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN sha256 TEXT")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN width INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN height INTEGER")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN usabilityStatus TEXT")
        db.execSQL("ALTER TABLE trip_photos ADD COLUMN privacyStatus TEXT")
        db.execSQL("UPDATE trip_photos SET captureId = lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6))) WHERE captureId = ''")
        db.execSQL("DROP INDEX IF EXISTS index_trip_photos_captureId")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_trip_photos_captureId ON trip_photos(captureId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_photos_eventId ON trip_photos(eventId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_events (
                eventId TEXT NOT NULL,
                tripId INTEGER NOT NULL,
                markerTimeMs INTEGER NOT NULL,
                markerElapsedRealtimeNanos INTEGER,
                experiencedLatitude REAL,
                experiencedLongitude REAL,
                sourceLatitude REAL,
                sourceLongitude REAL,
                sourceLocationVisible INTEGER,
                locationAccuracyMeters REAL,
                locationProvider TEXT,
                locationFixTimeMs INTEGER,
                locationFixElapsedRealtimeNanos INTEGER,
                locationFixAgeMs INTEGER,
                speedValid INTEGER,
                speedKmh REAL,
                primaryCauseCode TEXT,
                confidenceCode INTEGER,
                trafficState TEXT,
                status TEXT NOT NULL,
                provenance TEXT NOT NULL,
                transcript TEXT,
                recognitionConfidence REAL,
                codebookVersion TEXT,
                notes TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(eventId),
                FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_events_tripId_markerTimeMs ON trip_events(tripId, markerTimeMs)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS event_secondary_causes (
                eventId TEXT NOT NULL,
                causeCode TEXT NOT NULL,
                codebookVersion TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(eventId, causeCode),
                FOREIGN KEY(eventId) REFERENCES trip_events(eventId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_event_secondary_causes_eventId ON event_secondary_causes(eventId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sensor_metadata (
                metadataId TEXT NOT NULL,
                tripId INTEGER NOT NULL,
                sensorType INTEGER NOT NULL,
                sensorName TEXT,
                vendor TEXT,
                version TEXT,
                resolution REAL,
                maximumRange REAL,
                selectedProfile TEXT,
                registrationResult TEXT,
                sensorProfileVersion TEXT,
                requestedPeriodUs INTEGER,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(metadataId),
                FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sensor_metadata_tripId ON sensor_metadata(tripId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_quality (
                tripId INTEGER NOT NULL,
                gpsSampleCount INTEGER NOT NULL,
                gpsAvailabilityPercent REAL,
                medianGpsAccuracyMeters REAL,
                accelSampleCount INTEGER NOT NULL,
                accelEffectiveHz REAL,
                gyroSampleCount INTEGER NOT NULL,
                gyroEffectiveHz REAL,
                rotationSampleCount INTEGER NOT NULL,
                rotationEffectiveHz REAL,
                medianIntervalMs INTEGER,
                p05IntervalMs INTEGER,
                p95IntervalMs INTEGER,
                longestGapMs INTEGER,
                accelMedianIntervalMs INTEGER,
                accelLongestGapMs INTEGER,
                gyroMedianIntervalMs INTEGER,
                gyroLongestGapMs INTEGER,
                rotationMedianIntervalMs INTEGER,
                rotationLongestGapMs INTEGER,
                gpsLongestGapMs INTEGER,
                timeToFirstGpsFixMs INTEGER,
                manualEventMarkerCount INTEGER NOT NULL,
                photoCount INTEGER NOT NULL,
                audioSegmentCount INTEGER NOT NULL,
                gpsProviderJson TEXT NOT NULL,
                gpsBelowFivePercent REAL,
                gpsBelowTenPercent REAL,
                gpsAbovePoorQualityPercent REAL,
                invalidSpeedCount INTEGER NOT NULL,
                unavailableSpeedCount INTEGER NOT NULL,
                duplicateTimestampCount INTEGER NOT NULL,
                nonMonotonicTimestampCount INTEGER NOT NULL,
                interruptionCount INTEGER NOT NULL,
                storageFailureCount INTEGER NOT NULL,
                droppedSampleCount INTEGER NOT NULL,
                warningsJson TEXT NOT NULL,
                completeness TEXT NOT NULL,
                generatedAt INTEGER NOT NULL,
                PRIMARY KEY(tripId),
                FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS trip_audio (
                audioId TEXT NOT NULL,
                tripId INTEGER NOT NULL,
                eventId TEXT,
                startTimeMs INTEGER NOT NULL,
                endTimeMs INTEGER,
                startElapsedRealtimeNanos INTEGER,
                endElapsedRealtimeNanos INTEGER,
                segmentSequence INTEGER NOT NULL,
                filePath TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                codec TEXT,
                sampleRateHz INTEGER,
                channelCount INTEGER,
                fileSizeBytes INTEGER,
                sha256 TEXT,
                interruptionReason TEXT,
                transcript TEXT,
                recognitionConfidence REAL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(audioId),
                FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(eventId) REFERENCES trip_events(eventId) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_audio_tripId ON trip_audio(tripId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_audio_eventId ON trip_audio(eventId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS event_annotations (
                annotationId TEXT NOT NULL,
                eventId TEXT NOT NULL,
                annotationVersion INTEGER NOT NULL,
                annotationTimestampMs INTEGER NOT NULL,
                reviewerId TEXT,
                primaryCauseCode TEXT NOT NULL,
                secondaryCause1 TEXT,
                secondaryCause2 TEXT,
                trafficState TEXT,
                confidenceCode INTEGER,
                notes TEXT,
                codebookVersion TEXT NOT NULL,
                supersedesAnnotationId TEXT,
                createdAt INTEGER NOT NULL,
                PRIMARY KEY(annotationId),
                FOREIGN KEY(eventId) REFERENCES trip_events(eventId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_event_annotations_eventId_annotationVersion ON event_annotations(eventId, annotationVersion)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS audit_revisions (
                revisionId TEXT NOT NULL,
                tripId INTEGER,
                eventId TEXT,
                fieldName TEXT NOT NULL,
                originalValue TEXT,
                currentValue TEXT,
                editor TEXT,
                revisionTimeMs INTEGER NOT NULL,
                reason TEXT,
                revisionType TEXT NOT NULL,
                PRIMARY KEY(revisionId),
                FOREIGN KEY(tripId) REFERENCES trips(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(eventId) REFERENCES trip_events(eventId) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_revisions_tripId ON audit_revisions(tripId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_revisions_eventId ON audit_revisions(eventId)")
    }
}

@Database(
    entities = [
        TripData::class,
        Trip::class,
        TripPhoto::class,
        TripEvent::class,
        EventSecondaryCause::class,
        SensorMetadata::class,
        TripQuality::class,
        TripAudio::class,
        AuditRevision::class,
        EventAnnotationRevision::class
    ],
    version = 7
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "roadlog_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
