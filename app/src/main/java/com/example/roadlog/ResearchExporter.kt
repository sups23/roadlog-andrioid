package com.example.roadlog

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class ResearchExportMode(
    val archiveLabel: String,
    val defaultFileName: String
) {
    RESTRICTED_RAW("RESTRICTED_RAW", "roadlog-restricted-raw-export.zip"),
    PUBLIC_DEIDENTIFIED("PUBLIC_DEIDENTIFIED", "roadlog-public-deidentified-export.zip")
}

data class ResearchExportResult(
    val mode: ResearchExportMode,
    val tripCount: Int,
    val rowCount: Long,
    val eventCount: Int,
    val photoCount: Int,
    val annotationCount: Int,
    val audioCount: Int,
    val outputBytes: Long
)

/**
 * Creates one study-level archive from locally collected trips. It is intentionally
 * not part of stop/finalization: completed trips remain in Room until the researcher
 * chooses to export the collection.
 */
object ResearchExporter {
    suspend fun exportToUri(
        context: Context,
        destination: Uri,
        tripIds: Set<Long>? = null,
        includeIncomplete: Boolean = false,
        mode: ResearchExportMode = ResearchExportMode.RESTRICTED_RAW
    ): ResearchExportResult = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val allTrips = database.tripDao().getAllTripsForExport()
        val selectedTrips = allTrips.filter { trip ->
            (includeIncomplete || trip.status == TripStatus.COMPLETED) &&
                (tripIds == null || trip.id in tripIds)
        }
        val temporary = File(context.cacheDir, "research_export_${System.currentTimeMillis()}.zip")
        try {
            val deviceId = if (mode == ResearchExportMode.RESTRICTED_RAW) DeviceIdentity.get(context) else ""
            val result = writeArchive(database, selectedTrips, temporary, deviceId, mode)
            validateArchive(temporary, result)
            context.contentResolver.openOutputStream(destination)?.use { output ->
                FileInputStream(temporary).use { input -> input.copyTo(output) }
            } ?: error("Could not open export destination")
            result
        } finally {
            temporary.delete()
        }
    }

    internal suspend fun writeArchive(
        database: AppDatabase,
        trips: List<Trip>,
        output: File,
        deviceId: String,
        mode: ResearchExportMode
    ): ResearchExportResult {
        val restricted = mode == ResearchExportMode.RESTRICTED_RAW
        val checksums = linkedMapOf<String, String>()
        var rowCount = 0L
        var eventCount = 0
        var photoCount = 0
        var annotationCount = 0
        var audioCount = 0
        var gpsSampleCount = 0L
        var accelerometerSampleCount = 0L
        var gyroscopeSampleCount = 0L
        var orientationSampleCount = 0L

        ZipOutputStream(FileOutputStream(output)).use { zip ->
            fun textEntry(name: String, text: String) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                checksums[name] = sha256(bytes)
            }

            val tripsById = trips.associateBy { it.id }
            val configPaths = trips.associate { trip ->
                trip.id to "metadata/cause_config/trip_${trip.tripUuid}.json"
            }
            configPaths.forEach { (tripId, path) ->
                val config = tripsById.getValue(tripId).causeConfigJson
                    ?.takeIf { it.isNotBlank() }
                    ?: "{}"
                textEntry(path, config)
            }

            if (restricted) {
                val tripsCsv = StringBuilder(
                    "id,trip_uuid,start_time_ms,end_time_ms,start_nano_time,end_nano_time," +
                        "study_date_local,time_zone_id,distance_meters,event_count,gps_point_count," +
                        "accel_point_count,status,session_id,corridor_id,direction,observation_period," +
                        "device_id,driver_id,vehicle_id,vehicle_type,vehicle_make,vehicle_model,vehicle_year," +
                        "weather,road_wetness,validity_status,qa_status,qa_notes,route_diversion,non_traffic_stop," +
                        "context_note,context_collected_at_ms,recording_interruption," +
                        "gps_interruption,sensor_interruption,partial_traversal,coverage_end_time_ms," +
                        "coverage_end_latitude,coverage_end_longitude,continuation_of_trip_uuid,last_write_time_ms," +
                        "write_failure_count,dropped_sample_count,interruption_reason,sensor_profile_version," +
                        "export_format_version,protocol_version,cause_config_path,exclusion_code,codebook_version," +
                        "app_version,schema_version,notes\n"
                )
                trips.forEach { trip ->
                    tripsCsv.appendLine(
                        csvRow(
                            listOf(
                                trip.id,
                                trip.tripUuid,
                                trip.startTimeMs,
                                trip.endTimeMs,
                                trip.startNanoTime,
                                trip.endNanoTime,
                                trip.studyDateLocal,
                                trip.timeZoneId,
                                trip.distanceMeters,
                                trip.eventCount,
                                trip.gpsPointCount,
                                trip.accelPointCount,
                                trip.status,
                                trip.sessionId,
                                trip.corridorId,
                                trip.direction,
                                trip.observationPeriod,
                                trip.deviceId,
                                trip.driverId,
                                trip.vehicleId,
                                trip.vehicleType,
                                trip.vehicleMake,
                                trip.vehicleModel,
                                trip.vehicleYear,
                                trip.weather,
                                trip.roadWetness,
                                trip.validityStatus,
                                trip.qaStatus,
                                trip.qaNotes,
                                trip.routeDiversion,
                                trip.nonTrafficStop,
                                trip.contextNote,
                                trip.contextCollectedAtMs,
                                trip.recordingInterruption,
                                trip.gpsInterruption,
                                trip.sensorInterruption,
                                trip.partialTraversal,
                                trip.coverageEndTimeMs,
                                trip.coverageEndLatitude,
                                trip.coverageEndLongitude,
                                trip.continuationOfTripUuid,
                                trip.lastWriteTimeMs,
                                trip.writeFailureCount,
                                trip.droppedSampleCount,
                                trip.interruptionReason,
                                trip.sensorProfileVersion,
                                trip.exportFormatVersion,
                                trip.protocolVersion,
                                configPaths[trip.id],
                                trip.exclusionCode,
                                trip.codebookVersion,
                                trip.appVersion,
                                trip.schemaVersion,
                                trip.notes
                            )
                        )
                    )
                }
                textEntry("trips/trips.csv", tripsCsv.toString())
            } else {
                val tripsCsv = StringBuilder(
                    "trip_uuid,duration_ms,distance_meters,event_count,gps_point_count,accel_point_count," +
                        "status,direction,observation_period,vehicle_type,vehicle_make,vehicle_model,vehicle_year," +
                        "weather,road_wetness,validity_status,qa_status,route_diversion,non_traffic_stop,context_collected," +
                        "recording_interruption,gps_interruption,sensor_interruption,partial_traversal," +
                        "exclusion_code,sensor_profile_version,export_format_version,protocol_version," +
                        "cause_config_path,codebook_version,app_version,schema_version\n"
                )
                trips.forEach { trip ->
                    tripsCsv.appendLine(
                        csvRow(
                            listOf(
                                trip.tripUuid,
                                (trip.endTimeMs - trip.startTimeMs).coerceAtLeast(0L),
                                trip.distanceMeters,
                                trip.eventCount,
                                trip.gpsPointCount,
                                trip.accelPointCount,
                                trip.status,
                                trip.direction,
                                trip.observationPeriod,
                                trip.vehicleType,
                                trip.vehicleMake,
                                trip.vehicleModel,
                                trip.vehicleYear,
                                trip.weather,
                                trip.roadWetness,
                                trip.validityStatus,
                                trip.qaStatus,
                                trip.routeDiversion,
                                trip.nonTrafficStop,
                                trip.contextCollectedAtMs != null,
                                trip.recordingInterruption,
                                trip.gpsInterruption,
                                trip.sensorInterruption,
                                trip.partialTraversal,
                                trip.exclusionCode,
                                trip.sensorProfileVersion,
                                trip.exportFormatVersion,
                                trip.protocolVersion,
                                configPaths[trip.id],
                                trip.codebookVersion,
                                trip.appVersion,
                                trip.schemaVersion
                            )
                        )
                    )
                }
                textEntry("trips/trips.csv", tripsCsv.toString())
            }

            val selectedEvents = trips.flatMap { database.tripDao().getTripEvents(it.id) }
            val selectedEventIds = selectedEvents.map { it.eventId }.toSet()
            val annotations = database.tripDao().getAllAnnotationsForExport()
                .filter { annotation -> annotation.eventId in selectedEventIds }
            val latestAnnotationByEvent = annotations.groupBy { it.eventId }
                .mapValues { (_, revisions) -> revisions.maxByOrNull { it.annotationVersion } }
            eventCount = selectedEvents.size
            if (restricted) {
                val eventsCsv = StringBuilder(
                    "event_id,trip_id,trip_uuid,marker_time_ms,marker_elapsed_realtime_nanos," +
                        "experienced_latitude,experienced_longitude,source_latitude,source_longitude," +
                        "source_location_visible,location_accuracy_meters,location_provider,location_fix_time_ms," +
                        "location_fix_elapsed_realtime_nanos,location_fix_age_ms,speed_valid,speed_kmh," +
                        "provisional_cause_code,primary_cause_code,confidence_code,traffic_state,status,provenance," +
                        "transcript,recognition_confidence,codebook_version,provisional_codebook_version," +
                        "primary_codebook_version,notes\n"
                )
                selectedEvents.forEach { event ->
                    val trip = tripsById[event.tripId]
                    val latestAnnotation = latestAnnotationByEvent[event.eventId]
                    val provisionalVersion = eventProvisionalCodebookVersion(
                        trip, event, hasAnnotationHistory = latestAnnotation != null
                    )
                    val primaryVersion = CauseTaxonomyVersions.currentPrimaryVersion(
                        hasAnnotation = latestAnnotation != null,
                        annotationVersion = latestAnnotation?.codebookVersion,
                        provisionalVersion = provisionalVersion
                    )
                    eventsCsv.appendLine(
                        csvRow(
                            listOf(
                                event.eventId,
                                event.tripId,
                                trip?.tripUuid,
                                event.markerTimeMs,
                                event.markerElapsedRealtimeNanos,
                                event.experiencedLatitude,
                                event.experiencedLongitude,
                                event.sourceLatitude,
                                event.sourceLongitude,
                                event.sourceLocationVisible,
                                event.locationAccuracyMeters,
                                event.locationProvider,
                                event.locationFixTimeMs,
                                event.locationFixElapsedRealtimeNanos,
                                event.locationFixAgeMs,
                                event.speedValid,
                                event.speedKmh,
                                event.provisionalCauseCode,
                                latestAnnotation?.primaryCauseCode ?: event.primaryCauseCode,
                                event.confidenceCode,
                                event.trafficState,
                                event.status,
                                event.provenance,
                                event.transcript,
                                event.recognitionConfidence,
                                event.codebookVersion,
                                provisionalVersion,
                                primaryVersion,
                                event.notes
                            )
                        )
                    )
                }
                textEntry("events/events.csv", eventsCsv.toString())
            } else {
                val eventsCsv = StringBuilder(
                    "event_id,trip_uuid,marker_elapsed_ms,source_location_present,speed_valid,speed_kmh," +
                        "provisional_cause_code,primary_cause_code,confidence_code,traffic_state,status,provenance," +
                        "recognition_confidence,codebook_version,provisional_codebook_version," +
                        "primary_codebook_version\n"
                )
                selectedEvents.forEach { event ->
                    val trip = tripsById[event.tripId]
                    val latestAnnotation = latestAnnotationByEvent[event.eventId]
                    val provisionalVersion = eventProvisionalCodebookVersion(
                        trip, event, hasAnnotationHistory = latestAnnotation != null
                    )
                    val primaryVersion = CauseTaxonomyVersions.currentPrimaryVersion(
                        hasAnnotation = latestAnnotation != null,
                        annotationVersion = latestAnnotation?.codebookVersion,
                        provisionalVersion = provisionalVersion
                    )
                    eventsCsv.appendLine(
                        csvRow(
                            listOf(
                                event.eventId,
                                trip?.tripUuid,
                                trip?.let { event.markerTimeMs - it.startTimeMs },
                                event.sourceLocationVisible == true,
                                event.speedValid,
                                event.speedKmh,
                                event.provisionalCauseCode,
                                latestAnnotation?.primaryCauseCode ?: event.primaryCauseCode,
                                event.confidenceCode,
                                event.trafficState,
                                event.status,
                                event.provenance,
                                event.recognitionConfidence,
                                event.codebookVersion,
                                provisionalVersion,
                                primaryVersion
                            )
                        )
                    )
                }
                textEntry("events/events.csv", eventsCsv.toString())
            }

            annotationCount = annotations.size
            if (restricted) {
                val annotationsCsv = StringBuilder(
                    "annotation_id,event_id,annotation_version,annotation_timestamp_ms,reviewer_id," +
                        "primary_cause,traffic_state,confidence,notes,codebook_version,supersedes_annotation_id\n"
                )
                annotations.forEach { annotation ->
                    annotationsCsv.appendLine(
                        csvRow(
                            listOf(
                                annotation.annotationId,
                                annotation.eventId,
                                annotation.annotationVersion,
                                annotation.annotationTimestampMs,
                                annotation.reviewerId,
                                annotation.primaryCauseCode,
                                annotation.trafficState,
                                annotation.confidenceCode,
                                annotation.notes,
                                annotation.codebookVersion,
                                annotation.supersedesAnnotationId
                            )
                        )
                    )
                }
                textEntry("events/annotations.csv", annotationsCsv.toString())
            } else {
                val annotationsCsv = StringBuilder(
                    "annotation_id,event_id,annotation_version,primary_cause,traffic_state,confidence,codebook_version\n"
                )
                annotations.forEach { annotation ->
                    annotationsCsv.appendLine(
                        csvRow(
                            listOf(
                                annotation.annotationId,
                                annotation.eventId,
                                annotation.annotationVersion,
                                annotation.primaryCauseCode,
                                annotation.trafficState,
                                annotation.confidenceCode,
                                annotation.codebookVersion
                            )
                        )
                    )
                }
                textEntry("events/annotations.csv", annotationsCsv.toString())
            }

            val mediaFiles = mutableListOf<Pair<String, File>>()
            if (restricted) {
                val mediaIndex = StringBuilder(
                    "capture_id,trip_id,trip_uuid,event_id,timestamp,request_time_ms,capture_time_ms," +
                        "request_elapsed_realtime_nanos,capture_elapsed_realtime_nanos,latitude,longitude," +
                        "location_accuracy_meters,location_provider,location_fix_time_ms,mime_type,file_size_bytes," +
                        "sha256,width,height,usability_status,privacy_status,file_present,archive_path\n"
                )
                trips.forEach { trip ->
                    val photos = database.tripDao().getPhotosForTrip(trip.id)
                    photoCount += photos.size
                    photos.forEach { photo ->
                        val file = photo.filePath.takeIf { it.isNotBlank() }?.let(::File)
                        val present = file?.isFile == true
                        val archivePath = if (present) {
                            val name = "photos/${photo.captureId.ifBlank { photo.id.toString() }}.jpg"
                            mediaFiles += name to file!!
                            name
                        } else {
                            ""
                        }
                        mediaIndex.appendLine(
                            csvRow(
                                listOf(
                                    photo.captureId,
                                    photo.tripId,
                                    tripsById[photo.tripId]?.tripUuid,
                                    photo.eventId,
                                    photo.timestamp,
                                    photo.requestTimeMs,
                                    photo.captureTimeMs,
                                    photo.requestElapsedRealtimeNanos,
                                    photo.captureElapsedRealtimeNanos,
                                    photo.latitude,
                                    photo.longitude,
                                    photo.locationAccuracyMeters,
                                    photo.locationProvider,
                                    photo.locationFixTimeMs,
                                    photo.mimeType,
                                    photo.fileSizeBytes,
                                    photo.sha256,
                                    photo.width,
                                    photo.height,
                                    photo.usabilityStatus,
                                    photo.privacyStatus,
                                    present,
                                    archivePath
                                )
                            )
                        )
                    }
                }
                textEntry("media/media_index.csv", mediaIndex.toString())

                val audioIndex = StringBuilder(
                    "audio_id,trip_id,trip_uuid,event_id,start_time_ms,end_time_ms," +
                        "start_elapsed_realtime_nanos,end_elapsed_realtime_nanos,segment_sequence,mime_type,codec," +
                        "sample_rate_hz,channel_count,file_size_bytes,sha256,transcript,recognition_confidence,status," +
                        "final_audio_status,interruption_reason,failure_type,audio_completeness," +
                        "file_present,file_usable,archive_path\n"
                )
                trips.forEach { trip ->
                    database.tripDao().getAudioForTrip(trip.id).forEach { audio ->
                        audioCount++
                        val evidence = reconcileAudioEvidence(database, audio)
                        val file = audio.filePath.takeIf { it.isNotBlank() }?.let(::File)
                        val archivePath = if (evidence.filePresent) {
                            val name = audioArchivePath(trip, audio)
                            mediaFiles += name to file!!
                            name
                        } else {
                            ""
                        }
                        audioIndex.appendLine(
                            csvRow(audioInventoryFields(evidence.audio, trip.tripUuid, evidence, archivePath))
                        )
                    }
                }
                textEntry("audio/audio_index.csv", audioIndex.toString())
            }
            mediaFiles.forEach { (name, file) -> addFileEntry(zip, checksums, name, file) }

            val audioEvidenceByTrip = trips.associate { trip ->
                trip.id to database.tripDao().getAudioForTrip(trip.id)
                    .map { audio -> reconcileAudioEvidence(database, audio) }
            }
            val audioQualityByTrip = audioEvidenceByTrip.mapValues { (_, evidence) ->
                AudioEvidence.summarizeInventory(evidence)
            }

            trips.forEach { trip ->
                var afterId = 0L
                val entryName = "sensors/trip_${if (restricted) trip.id else trip.tripUuid}.csv"
                val digest = MessageDigest.getInstance("SHA-256")
                var publicSampleIndex = 0L
                zip.putNextEntry(ZipEntry(entryName))
                fun writeSensorChunk(text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    digest.update(bytes)
                    zip.write(bytes)
                }
                if (restricted) {
                    writeSensorChunk(
                        "row_id,timestamp_ms,trip_id,trip_uuid,latitude,longitude,speed_kmh,event_cause," +
                            "raw_timestamp,source_timestamp_nanos,callback_time_ms,source_elapsed_realtime_nanos," +
                            "source_epoch_time_ms,provider,horizontal_accuracy_meters,speed_valid,speed_accuracy_mps," +
                            "bearing_degrees,bearing_accuracy_degrees,altitude_meters,accel_x,accel_y,accel_z," +
                            "gyro_x,gyro_y,gyro_z,rot_x,rot_y,rot_z,rot_w,sensor_type,sensor_accuracy,source_type\n"
                    )
                } else {
                    writeSensorChunk(
                        "sample_index,trip_uuid,elapsed_ms,speed_kmh,event_cause,accel_x,accel_y,accel_z," +
                            "gyro_x,gyro_y,gyro_z,rot_x,rot_y,rot_z,rot_w,sensor_type,sensor_accuracy,source_type\n"
                    )
                }
                while (true) {
                    val page = database.tripDao().getDataForTripPage(trip.id, afterId, 500)
                    if (page.isEmpty()) break
                    page.forEach { row ->
                        rowCount++
                        when (row.sourceType) {
                            "LOCATION" -> gpsSampleCount++
                            "ACCELEROMETER" -> accelerometerSampleCount++
                            "GYROSCOPE" -> gyroscopeSampleCount++
                            "ROTATION" -> orientationSampleCount++
                        }
                        if (restricted) {
                            writeSensorChunk(
                                csvRow(
                                    listOf(
                                        row.id,
                                        row.timestamp,
                                        row.tripId,
                                        trip.tripUuid,
                                        row.latitude,
                                        row.longitude,
                                        row.speedKmh,
                                        CauseCodeMigration.toCanonicalOrKnownOrUnknown(row.eventCause),
                                        row.rawTimestamp,
                                        row.sourceTimestampNanos,
                                        row.callbackTimeMs,
                                        row.sourceElapsedRealtimeNanos,
                                        row.sourceEpochTimeMs,
                                        row.provider,
                                        row.horizontalAccuracyMeters,
                                        row.speedValid,
                                        row.speedAccuracyMps,
                                        row.bearingDegrees,
                                        row.bearingAccuracyDegrees,
                                        row.altitudeMeters,
                                        row.accelX,
                                        row.accelY,
                                        row.accelZ,
                                        row.gyroX,
                                        row.gyroY,
                                        row.gyroZ,
                                        row.rotX,
                                        row.rotY,
                                        row.rotZ,
                                        row.rotW,
                                        row.sensorType,
                                        row.sensorAccuracy,
                                        row.sourceType
                                    )
                                ) + "\n"
                            )
                        } else {
                            writeSensorChunk(
                                csvRow(
                                    listOf(
                                        publicSampleIndex++,
                                        trip.tripUuid,
                                        row.timestamp - trip.startTimeMs,
                                        row.speedKmh,
                                        row.eventCause,
                                        row.accelX,
                                        row.accelY,
                                        row.accelZ,
                                        row.gyroX,
                                        row.gyroY,
                                        row.gyroZ,
                                        row.rotX,
                                        row.rotY,
                                        row.rotZ,
                                        row.rotW,
                                        row.sensorType,
                                        row.sensorAccuracy,
                                        row.sourceType
                                    )
                                ) + "\n"
                            )
                        }
                    }
                    afterId = page.last().id
                }
                zip.closeEntry()
                checksums[entryName] = digest.digest().toHex()
            }

            val qualityJson = StringBuilder("{\"trips\":[")
            database.tripDao().getAllTripQuality()
                .filter { quality -> quality.tripId in tripsById }
                .forEachIndexed { index, quality ->
                    if (index > 0) qualityJson.append(",")
                    val audioQuality = audioQualityByTrip[quality.tripId]
                    qualityJson.append(
                        JSONObject().apply {
                            if (restricted) {
                                put("tripId", quality.tripId)
                            } else {
                                put("tripUuid", tripsById[quality.tripId]?.tripUuid)
                            }
                            put("gpsSampleCount", quality.gpsSampleCount)
                            put("gpsAvailabilityPercent", quality.gpsAvailabilityPercent)
                            put("medianGpsAccuracyMeters", quality.medianGpsAccuracyMeters)
                            put("accelSampleCount", quality.accelSampleCount)
                            put("accelEffectiveHz", quality.accelEffectiveHz)
                            put("gyroSampleCount", quality.gyroSampleCount)
                            put("gyroEffectiveHz", quality.gyroEffectiveHz)
                            put("medianIntervalMs", quality.medianIntervalMs)
                            put("p05IntervalMs", quality.p05IntervalMs)
                            put("p95IntervalMs", quality.p95IntervalMs)
                            put("longestGapMs", quality.longestGapMs)
                            put("accelMedianIntervalMs", quality.accelMedianIntervalMs)
                            put("accelLongestGapMs", quality.accelLongestGapMs)
                            put("gyroMedianIntervalMs", quality.gyroMedianIntervalMs)
                            put("gyroLongestGapMs", quality.gyroLongestGapMs)
                            put("rotationMedianIntervalMs", quality.rotationMedianIntervalMs)
                            put("rotationLongestGapMs", quality.rotationLongestGapMs)
                            put("gpsLongestGapMs", quality.gpsLongestGapMs)
                            put("timeToFirstGpsFixMs", quality.timeToFirstGpsFixMs)
                            put("voiceEventCount", quality.voiceEventCount)
                            if (restricted) {
                                put("photoCount", quality.photoCount)
                            }
                            put("audioStatus", audioQuality?.status ?: "NO_AUDIO")
                            put("audioCompleteness", audioQuality?.completeness ?: "NO_AUDIO")
                            put("audioSegmentCount", audioQuality?.segmentCount ?: 0)
                            put("audioCompleteSegmentCount", audioQuality?.completeSegmentCount ?: 0)
                            put("audioIncompleteSegmentCount", audioQuality?.incompleteSegmentCount ?: 0)
                            put("audioFailureCount", audioQuality?.failureCount ?: 0)
                            put("audioMissingFileCount", audioQuality?.missingFileCount ?: 0)
                            put("audioFailureTypes", JSONObject().apply {
                                audioQuality?.failureTypeCounts?.forEach { (type, count) -> put(type, count) }
                            })
                            put("audioWarnings", JSONArray(audioQuality?.warningMessages ?: emptyList<String>()))
                            if (restricted) {
                                put("audioInterruptionReasons", JSONArray(audioQuality?.interruptionReasons ?: emptyList<String>()))
                            }
                            put("gpsProviderCounts", JSONObject(quality.gpsProviderJson))
                            put("gpsBelowFivePercent", quality.gpsBelowFivePercent)
                            put("gpsBelowTenPercent", quality.gpsBelowTenPercent)
                            put("gpsAbovePoorQualityPercent", quality.gpsAbovePoorQualityPercent)
                            put("invalidSpeedCount", quality.invalidSpeedCount)
                            put("unavailableSpeedCount", quality.unavailableSpeedCount)
                            put("duplicateTimestampCount", quality.duplicateTimestampCount)
                            put("nonMonotonicTimestampCount", quality.nonMonotonicTimestampCount)
                            put("interruptionCount", quality.interruptionCount)
                            put("storageFailureCount", quality.storageFailureCount)
                            put("droppedSampleCount", quality.droppedSampleCount)
                            put("warnings", JSONArray(quality.warningsJson))
                            put("completeness", quality.completeness)
                        }.toString()
                    )
                }
            qualityJson.append("]}")
            textEntry("qa/trips.json", qualityJson.toString())

            if (restricted) {
                val sensorsCsv = StringBuilder(
                    "metadata_id,trip_id,trip_uuid,sensor_type,sensor_name,vendor,version,resolution," +
                        "maximum_range,selected_profile,registration_result,sensor_profile_version,requested_period_us\n"
                )
                database.tripDao().getAllSensorMetadataForExport()
                    .filter { metadata -> metadata.tripId in tripsById }
                    .forEach { metadata ->
                        sensorsCsv.appendLine(
                            csvRow(
                                listOf(
                                    metadata.metadataId,
                                    metadata.tripId,
                                    tripsById[metadata.tripId]?.tripUuid,
                                    metadata.sensorType,
                                    metadata.sensorName,
                                    metadata.vendor,
                                    metadata.version,
                                    metadata.resolution,
                                    metadata.maximumRange,
                                    metadata.selectedProfile,
                                    metadata.registrationResult,
                                    metadata.sensorProfileVersion,
                                    metadata.requestedPeriodUs
                                )
                            )
                        )
                    }
                textEntry("metadata/sensors.csv", sensorsCsv.toString())
            } else {
                val sensorsCsv = StringBuilder(
                    "trip_uuid,sensor_type,selected_profile,registration_result,sensor_profile_version,requested_period_us\n"
                )
                database.tripDao().getAllSensorMetadataForExport()
                    .filter { metadata -> metadata.tripId in tripsById }
                    .forEach { metadata ->
                        sensorsCsv.appendLine(
                            csvRow(
                                listOf(
                                    tripsById[metadata.tripId]?.tripUuid,
                                    metadata.sensorType,
                                    metadata.selectedProfile,
                                    metadata.registrationResult,
                                    metadata.sensorProfileVersion,
                                    metadata.requestedPeriodUs
                                )
                            )
                        )
                    }
                textEntry("metadata/sensors.csv", sensorsCsv.toString())
            }

            if (restricted) {
                val revisionsCsv = StringBuilder(
                    "revision_id,trip_id,event_id,field_name,original_value,current_value,editor," +
                        "revision_time_ms,reason,revision_type,audio_id,failure_type,final_audio_status," +
                        "interruption_reason,file_present,file_usable,audio_completeness\n"
                )
                database.tripDao().getAllAuditRevisionsForExport()
                    .filter { revision -> revision.tripId in tripsById || revision.eventId in selectedEventIds }
                    .forEach { revision ->
                        val audioValue = revision.currentValue?.let { value ->
                            runCatching { JSONObject(value) }.getOrNull()
                                ?.takeIf { it.has("audio_id") }
                        }
                        revisionsCsv.appendLine(
                            csvRow(
                                listOf(
                                    revision.revisionId,
                                    revision.tripId,
                                    revision.eventId,
                                    revision.fieldName,
                                    revision.originalValue,
                                    revision.currentValue,
                                    revision.editor,
                                    revision.revisionTimeMs,
                                    revision.reason,
                                    revision.revisionType,
                                    audioValue?.optString("audio_id")?.takeIf { it.isNotBlank() },
                                    audioValue?.optString("failure_type")?.takeIf { it.isNotBlank() },
                                    audioValue?.optString("final_audio_status")?.takeIf { it.isNotBlank() },
                                    audioValue?.optString("interruption_reason")?.takeIf { it.isNotBlank() },
                                    audioValue?.let { if (it.has("file_present")) it.optBoolean("file_present") else null },
                                    audioValue?.let { if (it.has("file_usable")) it.optBoolean("file_usable") else null },
                                    audioValue?.optString("audio_completeness")?.takeIf { it.isNotBlank() }
                                )
                            )
                        )
                    }
                textEntry("audit/revisions.csv", revisionsCsv.toString())
            }

            textEntry(
                "metadata/device.json",
                if (restricted) {
                    JSONObject().apply {
                        put("device_id", deviceId)
                        put("manufacturer", Build.MANUFACTURER)
                        put("device_model", Build.MODEL)
                        put("android_version", Build.VERSION.RELEASE)
                        put("sdk_int", Build.VERSION.SDK_INT)
                        put("app_version", BuildConfig.VERSION_NAME)
                        put("room_schema_version", ResearchVersions.ROOM_SCHEMA_VERSION)
                        put("session_id", ResearchStudy.SESSION_ID)
                        put("corridor_id", ResearchStudy.CORRIDOR_ID)
                        put("sensor_profile_version", ResearchVersions.SENSOR_PROFILE_VERSION)
                    }.toString()
                } else {
                    JSONObject().apply {
                        put("export_mode", mode.archiveLabel)
                        put("app_version", BuildConfig.VERSION_NAME)
                        put("room_schema_version", ResearchVersions.ROOM_SCHEMA_VERSION)
                        put("sensor_profile_version", ResearchVersions.SENSOR_PROFILE_VERSION)
                    }.toString()
                }
            )
            textEntry(
                "metadata/codebook.json",
                JSONObject().apply {
                    put("version", ResearchCodebook.VERSION)
                    put("primary_codes", JSONArray(ResearchCodebook.primaryCodes.toList().sorted()))
                    put("definitions", JSONObject().apply {
                        ResearchCodebook.v4Definitions.forEach { (code, definition) -> put(code, definition) }
                    })
                    put("definition_scope", "The listed definitions add v4 causes and bound TURNING/FRICTION; remaining v4 causes retain their v3 meanings.")
                    put("unchanged_from_v3", JSONArray(
                        (ResearchCodebook.v3PrimaryCodes - ResearchCodebook.v4ResidualCodes).toList().sorted()
                    ))
                    put("version_specific_codebooks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("version", ResearchCodebook.LEGACY_V3_VERSION)
                            put("primary_codes", JSONArray(ResearchCodebook.v3PrimaryCodes.toList().sorted()))
                            put("definitions", JSONObject().apply {
                                ResearchCodebook.v3ResidualDefinitions.forEach { (code, definition) -> put(code, definition) }
                            })
                            put("definitions_reference", "Other v3 meanings are unchanged; per-trip cause_config_path preserves the original runtime grammar when present. Missing historical provenance remains unknown.")
                        })
                        put(JSONObject().apply {
                            put("version", ResearchCodebook.VERSION)
                            put("primary_codes", JSONArray(ResearchCodebook.primaryCodes.toList().sorted()))
                            put("definitions", JSONObject().apply {
                                ResearchCodebook.v4Definitions.forEach { (code, definition) -> put(code, definition) }
                            })
                            put("unchanged_from_v3", JSONArray(
                                (ResearchCodebook.v3PrimaryCodes - ResearchCodebook.v4ResidualCodes).toList().sorted()
                            ))
                        })
                    })
                    put("traffic_states", JSONArray(ResearchCodebook.trafficStates.toList().sorted()))
                    put("weather", JSONArray(TripWeather.values))
                    put("road_wetness", JSONArray(RoadWetness.values))
                    put("non_traffic_stop", JSONArray(NonTrafficStop.values))
                }.toString()
            )
            textEntry(
                "metadata/schema.json",
                JSONObject().apply {
                    put("room_schema_version", ResearchVersions.ROOM_SCHEMA_VERSION)
                    put("export_format_version", ResearchVersions.EXPORT_FORMAT_VERSION)
                    put("protocol_version", ResearchVersions.PROTOCOL_VERSION)
                    put("mode", mode.archiveLabel)
                    put("raw_fields_authoritative", restricted)
                     put("timestamp_units", JSONObject().apply {
                         put("epoch", "milliseconds")
                         put("elapsed_realtime", "nanoseconds")
                         put("sensor", "nanoseconds")
                     })
                     put("trip_context_fields", JSONArray(listOf(
                         "driver_id", "vehicle_id", "vehicle_type", "vehicle_make", "vehicle_model",
                         "vehicle_year", "weather", "road_wetness", "route_diversion",
                         "non_traffic_stop", "context_note", "context_collected_at_ms"
                     )))
                     put("cause_version_fields", JSONObject().apply {
                         put("codebook_version", "Existing event field retained for compatibility; older reviewed rows may reflect the prior review behavior.")
                         put("provisional_codebook_version", "Version resolved from consistent trip/config metadata; falls back to event metadata only when no annotation history exists. Null means uncertain.")
                         put("primary_codebook_version", "Latest annotation revision version, or provisional version when no review exists; null means uncertain.")
                     })
                  }.toString()
            )

            val codebookVersionsPresent = buildSet<String> {
                trips.forEach { trip ->
                    trip.codebookVersion?.takeIf { CauseTaxonomyVersions.isKnown(it) }?.let { add(it) }
                }
                selectedEvents.forEach { event ->
                    val revision = latestAnnotationByEvent[event.eventId]
                    val provisionalVersion = eventProvisionalCodebookVersion(
                        tripsById[event.tripId], event, hasAnnotationHistory = revision != null
                    )
                    provisionalVersion?.let { add(it) }
                    CauseTaxonomyVersions.currentPrimaryVersion(
                        hasAnnotation = revision != null,
                        annotationVersion = revision?.codebookVersion,
                        provisionalVersion = provisionalVersion
                    )?.let { add(it) }
                }
                annotations.forEach { annotation ->
                    annotation.codebookVersion.takeIf { CauseTaxonomyVersions.isKnown(it) }?.let { add(it) }
                }
            }
            val manifest = JSONObject().apply {
                put("export_mode", mode.archiveLabel)
                put("export_format_version", ResearchVersions.EXPORT_FORMAT_VERSION)
                put("protocol_version", ResearchVersions.PROTOCOL_VERSION)
                put("export_timestamp_ms", System.currentTimeMillis())
                put("app_version", BuildConfig.VERSION_NAME)
                put("room_schema_version", ResearchVersions.ROOM_SCHEMA_VERSION)
                put("session_id", ResearchStudy.SESSION_ID)
                put("corridor_id", ResearchStudy.CORRIDOR_ID)
                put("codebook_version", ResearchCodebook.VERSION)
                put("codebook_versions_present", JSONArray(codebookVersionsPresent.toList().sorted()))
                put("sensor_profile_version", ResearchVersions.SENSOR_PROFILE_VERSION)
                put("contains_precise_gps", restricted)
                put("contains_audio", restricted)
                put("contains_transcripts", restricted)
                put("contains_device_identity", restricted)
                put("contains_legacy_photos", restricted && photoCount > 0)
                put("trip_count", trips.size)
                put("row_count", rowCount)
                put("event_count", eventCount)
                put("photo_count", photoCount)
                put("annotation_count", annotationCount)
                put("audio_segment_count", audioCount)
                put("gps_sample_count", gpsSampleCount)
                put("accelerometer_sample_count", accelerometerSampleCount)
                put("gyroscope_sample_count", gyroscopeSampleCount)
                put("orientation_sample_count", orientationSampleCount)
                put("include_incomplete", trips.any { it.status != TripStatus.COMPLETED })
                put(
                    "completeness",
                    if (trips.all { it.status == TripStatus.COMPLETED }) "COMPLETE" else "INCOMPLETE_TRIPS_INCLUDED"
                )
            }.toString()
            textEntry("manifest.json", manifest)

            textEntry(
                "checksums.sha256",
                checksums.entries.joinToString("\n") { (name, checksum) -> "$checksum  $name" } + "\n"
            )
        }

        return ResearchExportResult(
            mode = mode,
            tripCount = trips.size,
            rowCount = rowCount,
            eventCount = eventCount,
            photoCount = photoCount,
            annotationCount = annotationCount,
            audioCount = audioCount,
            outputBytes = output.length()
        )
    }

    internal fun audioArchivePath(trip: Trip, audio: TripAudio): String =
        "audio/trip_${trip.id}_" +
            "${ResearchClock.tripStartDateForAudioFolder(trip.startTimeMs, trip.timeZoneId)}/${audio.audioId}.m4a"

    private fun eventProvisionalCodebookVersion(
        trip: Trip?,
        event: TripEvent,
        hasAnnotationHistory: Boolean = false
    ): String? {
        val configVersion = trip?.causeConfigJson?.let { raw ->
            runCatching { JSONObject(raw).optString("version").takeIf { it.isNotBlank() } }.getOrNull()
        }
        return CauseTaxonomyVersions.provisionalVersion(
            tripVersion = trip?.codebookVersion,
            configVersion = configVersion,
            eventVersion = event.codebookVersion,
            hasAnnotationHistory = hasAnnotationHistory
        )
    }

    private fun addFileEntry(
        zip: ZipOutputStream,
        checksums: MutableMap<String, String>,
        name: String,
        file: File
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(name))
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                zip.write(buffer, 0, count)
            }
        }
        zip.closeEntry()
        checksums[name] = digest.digest().toHex()
    }

    internal fun validateArchive(file: File, result: ResearchExportResult) {
        ZipFile(file).use { zip ->
            require(zip.getEntry("manifest.json") != null) { "export manifest is missing" }
            require(zip.getEntry("trips/trips.csv") != null) { "trip export is missing" }
            require(zip.getEntry("events/events.csv") != null) { "event export is missing" }
            val checksums = zip.getInputStream(zip.getEntry("checksums.sha256") ?: error("checksums are missing"))
                .bufferedReader(Charsets.UTF_8)
                .useLines { lines ->
                    lines.filter { it.isNotBlank() }.associate { line ->
                        val separator = line.indexOf("  ")
                        require(separator > 0) { "invalid checksum line" }
                        line.substring(0, separator) to line.substring(separator + 2)
                    }
                }
            checksums.forEach { (expected, entryName) ->
                val entry = zip.getEntry(entryName) ?: error("checksum entry missing: $entryName")
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                require(digest.digest().toHex() == expected) { "checksum mismatch: $entryName" }
            }
            val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                .bufferedReader(Charsets.UTF_8)
                .use { JSONObject(it.readText()) }
            require(manifest.getInt("trip_count") == result.tripCount) { "manifest trip count mismatch" }
            require(manifest.getInt("event_count") == result.eventCount) { "manifest event count mismatch" }
            require(manifest.getString("export_mode") == result.mode.archiveLabel) { "manifest mode mismatch" }
            if (result.mode == ResearchExportMode.PUBLIC_DEIDENTIFIED) {
                require(zip.entries().asSequence().none { entry ->
                    entry.name.startsWith("audio/") || entry.name.startsWith("photos/")
                }) { "public archive contains restricted media" }
            }
        }
    }

    private fun csvRow(values: List<Any?>): String = values.joinToString(",") { value ->
        val text = value?.toString() ?: ""
        "\"${text.replace("\"", "\"\"")}\""
    }

    internal fun audioInventoryFields(
        audio: TripAudio,
        tripUuid: String,
        evidence: AudioInventoryEvidence = AudioEvidence.inventory(audio),
        archivePath: String = ""
    ): List<Any?> = listOf(
        audio.audioId,
        audio.tripId,
        tripUuid,
        audio.eventId,
        audio.startTimeMs,
        audio.endTimeMs,
        audio.startElapsedRealtimeNanos,
        audio.endElapsedRealtimeNanos,
        audio.segmentSequence,
        audio.mimeType,
        audio.codec,
        audio.sampleRateHz,
        audio.channelCount,
        audio.fileSizeBytes,
        audio.sha256,
        audio.transcript,
        audio.recognitionConfidence,
        evidence.finalAudioStatus,
        evidence.finalAudioStatus,
        evidence.reason,
        evidence.failureTypes.joinToString(";"),
        evidence.completeness,
        evidence.filePresent,
        evidence.fileUsable,
        archivePath
    )

    private suspend fun reconcileAudioEvidence(
        database: AppDatabase,
        audio: TripAudio
    ): AudioInventoryEvidence {
        var current = audio
        val originalStatus = current.status
        var evidence = AudioEvidence.inventory(current)
        val normalizedReason = AudioEvidence.normalizedReason(evidence)
        val normalizedStatus = AudioEvidence.finalStatus(current.status, evidence.failureTypes)
        if (normalizedStatus != current.status || normalizedReason != current.interruptionReason) {
            if (database.tripDao().updateAudioStatus(
                    audioId = current.audioId,
                    status = normalizedStatus,
                    interruptionReason = normalizedReason
                ) == 1
            ) {
                current = current.copy(
                    status = normalizedStatus,
                    interruptionReason = normalizedReason
                )
                evidence = AudioEvidence.inventory(current)
            }
        }
        if (evidence.failureTypes.isNotEmpty()) {
            database.tripDao().insertAuditRevisionIfAbsent(
                AudioEvidence.auditRevision(evidence, originalStatus = originalStatus)
            )
        }
        return evidence
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
