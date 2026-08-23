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

data class ResearchExportResult(
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
        includeIncomplete: Boolean = false
    ): ResearchExportResult = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context)
        val allTrips = database.tripDao().getAllTripsForExport()
        val selectedTrips = allTrips.filter { trip ->
            (includeIncomplete || trip.status == TripStatus.COMPLETED) &&
                (tripIds == null || trip.id in tripIds)
        }
        val temporary = File(context.cacheDir, "research_export_${System.currentTimeMillis()}.zip")
        try {
            val result = writeArchive(database, selectedTrips, temporary, DeviceIdentity.get(context))
            validateArchive(temporary, result)
            context.contentResolver.openOutputStream(destination)?.use { output ->
                FileInputStream(temporary).use { input -> input.copyTo(output) }
            } ?: error("Could not open export destination")
            result
        } finally {
            temporary.delete()
        }
    }

    private suspend fun writeArchive(
        database: AppDatabase,
        trips: List<Trip>,
        output: File,
        deviceId: String
    ): ResearchExportResult {
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

            val tripsCsv = StringBuilder("id,trip_uuid,start_time_ms,end_time_ms,start_nano_time,end_nano_time,study_date_local,time_zone_id,distance_meters,event_count,gps_point_count,accel_point_count,status,session_id,corridor_id,direction,observation_period,device_id,validity_status,qa_status,qa_notes,route_diversion,recording_interruption,gps_interruption,sensor_interruption,partial_traversal,coverage_end_time_ms,coverage_end_latitude,coverage_end_longitude,continuation_of_trip_uuid,last_write_time_ms,write_failure_count,dropped_sample_count,interruption_reason,sensor_profile_version,codebook_version,app_version,schema_version,notes\n")
            trips.forEach { trip ->
                tripsCsv.appendLine(csvRow(listOf(trip.id, trip.tripUuid, trip.startTimeMs, trip.endTimeMs, trip.startNanoTime, trip.endNanoTime, trip.studyDateLocal, trip.timeZoneId, trip.distanceMeters, trip.eventCount, trip.gpsPointCount, trip.accelPointCount, trip.status, trip.sessionId, trip.corridorId, trip.direction, trip.observationPeriod, trip.deviceId, trip.validityStatus, trip.qaStatus, trip.qaNotes, trip.routeDiversion, trip.recordingInterruption, trip.gpsInterruption, trip.sensorInterruption, trip.partialTraversal, trip.coverageEndTimeMs, trip.coverageEndLatitude, trip.coverageEndLongitude, trip.continuationOfTripUuid, trip.lastWriteTimeMs, trip.writeFailureCount, trip.droppedSampleCount, trip.interruptionReason, trip.sensorProfileVersion, trip.codebookVersion, trip.appVersion, trip.schemaVersion, trip.notes)))
            }
            textEntry("trips/trips.csv", tripsCsv.toString())

            val tripsById = trips.associateBy { it.id }
            val selectedEvents = mutableListOf<TripEvent>()
            for (trip in trips) {
                selectedEvents += database.tripDao().getTripEvents(trip.id)
            }
            val selectedEventIds = selectedEvents.map { it.eventId }.toSet()
            val eventsCsv = StringBuilder("event_id,trip_id,trip_uuid,marker_time_ms,marker_elapsed_realtime_nanos,experienced_latitude,experienced_longitude,source_latitude,source_longitude,source_location_visible,location_accuracy_meters,location_provider,location_fix_time_ms,location_fix_elapsed_realtime_nanos,location_fix_age_ms,speed_valid,speed_kmh,primary_cause_code,confidence_code,traffic_state,status,provenance,transcript,recognition_confidence,codebook_version,notes\n")
            trips.forEach { trip ->
                val events = database.tripDao().getTripEvents(trip.id)
                eventCount += events.size
                events.forEach { event ->
                    eventsCsv.appendLine(csvRow(listOf(event.eventId, event.tripId, tripsById[event.tripId]?.tripUuid, event.markerTimeMs, event.markerElapsedRealtimeNanos, event.experiencedLatitude, event.experiencedLongitude, event.sourceLatitude, event.sourceLongitude, event.sourceLocationVisible, event.locationAccuracyMeters, event.locationProvider, event.locationFixTimeMs, event.locationFixElapsedRealtimeNanos, event.locationFixAgeMs, event.speedValid, event.speedKmh, event.primaryCauseCode, event.confidenceCode, event.trafficState, event.status, event.provenance, event.transcript, event.recognitionConfidence, event.codebookVersion, event.notes)))
                }
            }
            textEntry("events/events.csv", eventsCsv.toString())

            val annotationsCsv = StringBuilder("annotation_id,event_id,annotation_version,annotation_timestamp_ms,reviewer_id,primary_cause,secondary_cause_1,secondary_cause_2,traffic_state,confidence,notes,codebook_version,supersedes_annotation_id\n")
            database.tripDao().getAllAnnotationsForExport()
                .filter { annotation -> annotation.eventId in selectedEventIds }
                .forEach { annotation ->
                    annotationCount++
                    annotationsCsv.appendLine(csvRow(listOf(annotation.annotationId, annotation.eventId, annotation.annotationVersion, annotation.annotationTimestampMs, annotation.reviewerId, annotation.primaryCauseCode, annotation.secondaryCause1, annotation.secondaryCause2, annotation.trafficState, annotation.confidenceCode, annotation.notes, annotation.codebookVersion, annotation.supersedesAnnotationId)))
                }
            textEntry("events/annotations.csv", annotationsCsv.toString())

            val secondaryCsv = StringBuilder("event_id,cause_code,codebook_version,created_at_ms\n")
            database.tripDao().getAllSecondaryCausesForExport().filter { cause -> cause.eventId in selectedEventIds }.forEach { cause ->
                secondaryCsv.appendLine(csvRow(listOf(cause.eventId, cause.causeCode, cause.codebookVersion, cause.createdAt)))
            }
            textEntry("events/secondary_causes.csv", secondaryCsv.toString())

            val mediaFiles = mutableListOf<Pair<String, File>>()
            val mediaIndex = StringBuilder("capture_id,trip_id,trip_uuid,event_id,timestamp,request_time_ms,capture_time_ms,request_elapsed_realtime_nanos,capture_elapsed_realtime_nanos,latitude,longitude,location_accuracy_meters,location_provider,location_fix_time_ms,mime_type,file_size_bytes,sha256,width,height,usability_status,privacy_status,archive_path\n")
            trips.forEach { trip ->
                val photos = database.tripDao().getPhotosForTrip(trip.id)
                photoCount += photos.size
                photos.forEach { photo ->
                    val file = photo.filePath.takeIf { it.isNotBlank() }?.let(::File)
                    val archivePath = if (file?.isFile == true) {
                        val name = "photos/${photo.captureId.ifBlank { photo.id.toString() }}.jpg"
                        mediaFiles += name to file
                        name
                    } else {
                        ""
                    }
                    mediaIndex.appendLine(csvRow(listOf(photo.captureId, photo.tripId, tripsById[photo.tripId]?.tripUuid, photo.eventId, photo.timestamp, photo.requestTimeMs, photo.captureTimeMs, photo.requestElapsedRealtimeNanos, photo.captureElapsedRealtimeNanos, photo.latitude, photo.longitude, photo.locationAccuracyMeters, photo.locationProvider, photo.locationFixTimeMs, photo.mimeType, photo.fileSizeBytes, photo.sha256, photo.width, photo.height, photo.usabilityStatus, photo.privacyStatus, archivePath)))
                }
            }
            val audioIndex = StringBuilder("audio_id,trip_id,trip_uuid,event_id,start_time_ms,end_time_ms,start_elapsed_realtime_nanos,end_elapsed_realtime_nanos,segment_sequence,mime_type,codec,sample_rate_hz,channel_count,file_size_bytes,sha256,transcript,recognition_confidence,status,interruption_reason,archive_path\n")
            trips.forEach { trip ->
                database.tripDao().getAudioForTrip(trip.id).forEach { audio ->
                    audioCount++
                    val file = audio.filePath.takeIf { it.isNotBlank() }?.let(::File)
                    if (audio.status == TripAudioStatus.COMPLETE && file?.isFile != true) {
                        error("completed audio segment is missing: ${audio.audioId}")
                    }
                    val archivePath = if (file?.isFile == true) {
                        val name = "audio/${audio.audioId}.m4a"
                        mediaFiles += name to file
                        name
                    } else {
                        ""
                    }
                    audioIndex.appendLine(csvRow(listOf(audio.audioId, audio.tripId, trip.tripUuid, audio.eventId, audio.startTimeMs, audio.endTimeMs, audio.startElapsedRealtimeNanos, audio.endElapsedRealtimeNanos, audio.segmentSequence, audio.mimeType, audio.codec, audio.sampleRateHz, audio.channelCount, audio.fileSizeBytes, audio.sha256, audio.transcript, audio.recognitionConfidence, audio.status, audio.interruptionReason, archivePath)))
                }
            }
            textEntry("media/media_index.csv", mediaIndex.toString())
            textEntry("audio/audio_index.csv", audioIndex.toString())
            mediaFiles.forEach { (name, file) -> addFileEntry(zip, checksums, name, file) }

            trips.forEach { trip ->
                var afterId = 0L
                val entryName = "sensors/trip_${trip.id}.csv"
                val digest = MessageDigest.getInstance("SHA-256")
                zip.putNextEntry(ZipEntry(entryName))
                fun writeSensorChunk(text: String) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    digest.update(bytes)
                    zip.write(bytes)
                }
                writeSensorChunk("row_id,timestamp_ms,trip_id,trip_uuid,latitude,longitude,speed_kmh,event_cause,raw_timestamp,source_timestamp_nanos,callback_time_ms,source_elapsed_realtime_nanos,source_epoch_time_ms,provider,horizontal_accuracy_meters,speed_valid,speed_accuracy_mps,bearing_degrees,bearing_accuracy_degrees,altitude_meters,accel_x,accel_y,accel_z,gyro_x,gyro_y,gyro_z,rot_x,rot_y,rot_z,rot_w,sensor_type,sensor_accuracy,source_type\n")
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
                        writeSensorChunk(csvRow(listOf(row.id, row.timestamp, row.tripId, trip.tripUuid, row.latitude, row.longitude, row.speedKmh, row.eventCause, row.rawTimestamp, row.sourceTimestampNanos, row.callbackTimeMs, row.sourceElapsedRealtimeNanos, row.sourceEpochTimeMs, row.provider, row.horizontalAccuracyMeters, row.speedValid, row.speedAccuracyMps, row.bearingDegrees, row.bearingAccuracyDegrees, row.altitudeMeters, row.accelX, row.accelY, row.accelZ, row.gyroX, row.gyroY, row.gyroZ, row.rotX, row.rotY, row.rotZ, row.rotW, row.sensorType, row.sensorAccuracy, row.sourceType)) + "\n")
                    }
                    afterId = page.last().id
                }
                zip.closeEntry()
                checksums[entryName] = digest.digest().toHex()
            }

            val qualityJson = StringBuilder("{\"trips\":[")
            database.tripDao().getAllTripQuality().filter { quality -> trips.any { it.id == quality.tripId } }.forEachIndexed { index, quality ->
                    if (index > 0) qualityJson.append(",")
                    qualityJson.append(JSONObject().apply {
                        put("tripId", quality.tripId)
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
                        put("manualEventMarkerCount", quality.manualEventMarkerCount)
                        put("photoCount", quality.photoCount)
                        put("audioSegmentCount", quality.audioSegmentCount)
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
                    }.toString())
            }
            qualityJson.append("]}")
            textEntry("qa/trips.json", qualityJson.toString())

            val sensorsCsv = StringBuilder("metadata_id,trip_id,trip_uuid,sensor_type,sensor_name,vendor,version,resolution,maximum_range,selected_profile,registration_result,sensor_profile_version,requested_period_us\n")
            database.tripDao().getAllSensorMetadataForExport().filter { metadata -> metadata.tripId in tripsById }.forEach { metadata ->
                sensorsCsv.appendLine(csvRow(listOf(metadata.metadataId, metadata.tripId, tripsById[metadata.tripId]?.tripUuid, metadata.sensorType, metadata.sensorName, metadata.vendor, metadata.version, metadata.resolution, metadata.maximumRange, metadata.selectedProfile, metadata.registrationResult, metadata.sensorProfileVersion, metadata.requestedPeriodUs)))
            }
            textEntry("metadata/sensors.csv", sensorsCsv.toString())

            val revisionsCsv = StringBuilder("revision_id,trip_id,event_id,field_name,original_value,current_value,editor,revision_time_ms,reason,revision_type\n")
            database.tripDao().getAllAuditRevisionsForExport()
                .filter { revision -> revision.tripId in tripsById || revision.eventId in selectedEventIds }
                .forEach { revision ->
                revisionsCsv.appendLine(csvRow(listOf(revision.revisionId, revision.tripId, revision.eventId, revision.fieldName, revision.originalValue, revision.currentValue, revision.editor, revision.revisionTimeMs, revision.reason, revision.revisionType)))
            }
            textEntry("audit/revisions.csv", revisionsCsv.toString())

            textEntry("metadata/device.json", JSONObject().apply {
                put("device_id", deviceId)
                put("manufacturer", Build.MANUFACTURER)
                put("device_model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("app_version", BuildConfig.VERSION_NAME)
                put("room_schema_version", 7)
                put("session_id", ResearchStudy.SESSION_ID)
                put("corridor_id", ResearchStudy.CORRIDOR_ID)
                put("sensor_profile_version", "1")
            }.toString())
            textEntry("metadata/codebook.json", JSONObject().apply {
                put("version", ResearchCodebook.VERSION)
                put("primary_codes", JSONArray(ResearchCodebook.primaryCodes.toList().sorted()))
                put("traffic_states", JSONArray(ResearchCodebook.trafficStates.toList().sorted()))
            }.toString())
            textEntry("metadata/schema.json", "{\"room_schema_version\":7,\"timestamp_units\":{\"epoch\":\"milliseconds\",\"elapsed_realtime\":\"nanoseconds\",\"sensor\":\"nanoseconds\"},\"raw_fields_authoritative\":true}")

            val manifest = JSONObject().apply {
                put("export_timestamp_ms", System.currentTimeMillis())
                put("app_version", BuildConfig.VERSION_NAME)
                put("room_schema_version", 7)
                put("session_id", ResearchStudy.SESSION_ID)
                put("corridor_id", ResearchStudy.CORRIDOR_ID)
                put("codebook_version", ResearchCodebook.VERSION)
                put("sensor_profile_version", "1")
                put("timestamp_clock_sources", JSONObject().apply {
                    put("epoch", "Unix epoch milliseconds")
                    put("elapsed_realtime", "Android elapsedRealtimeNanos")
                    put("sensor", "SensorEvent.timestamp nanoseconds")
                })
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
                put("completeness", if (trips.all { it.status == TripStatus.COMPLETED }) "COMPLETE" else "INCOMPLETE_TRIPS_INCLUDED")
            }.toString()
            textEntry("manifest.json", manifest)

            textEntry(
                "checksums.sha256",
                checksums.entries.joinToString("\n") { (name, checksum) -> "$checksum  $name" } + "\n"
            )
        }

        return ResearchExportResult(
            tripCount = trips.size,
            rowCount = rowCount,
            eventCount = eventCount,
            photoCount = photoCount,
            annotationCount = annotationCount,
            audioCount = audioCount,
            outputBytes = output.length()
        )
    }

    private fun addFileEntry(
        zip: ZipOutputStream,
        checksums: MutableMap<String, String>,
        name: String,
        file: File
    ): String {
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
        return name
    }

    private fun validateArchive(file: File, result: ResearchExportResult) {
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
            val manifest = zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            require(manifest.getInt("trip_count") == result.tripCount) { "manifest trip count mismatch" }
            require(manifest.getInt("event_count") == result.eventCount) { "manifest event count mismatch" }
        }
    }

    private fun csvRow(values: List<Any?>): String = values.joinToString(",") { value ->
        val text = value?.toString() ?: ""
        "\"${text.replace("\"", "\"\"")}\""
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
