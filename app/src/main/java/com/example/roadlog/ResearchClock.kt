package com.example.roadlog

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ResearchClock {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val audioFolderDateFormatter = DateTimeFormatter.ofPattern("yyyy_MM_dd", Locale.US)
    private val zone: ZoneId = ZoneId.of(ResearchTime.KATHMANDU_ZONE_ID)

    fun studyDateLocal(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(dateFormatter)

    fun tripStartDateForAudioFolder(epochMs: Long, timeZoneId: String?): String {
        val tripZone = timeZoneId
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: zone
        return Instant.ofEpochMilli(epochMs).atZone(tripZone).toLocalDate()
            .format(audioFolderDateFormatter)
    }
}
