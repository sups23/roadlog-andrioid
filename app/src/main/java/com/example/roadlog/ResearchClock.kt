package com.example.roadlog

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ResearchClock {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val zone: ZoneId = ZoneId.of(ResearchTime.KATHMANDU_ZONE_ID)

    fun studyDateLocal(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(dateFormatter)
}
