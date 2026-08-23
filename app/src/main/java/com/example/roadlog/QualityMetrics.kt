package com.example.roadlog

import kotlin.math.floor

data class StreamQualityMetrics(
    val sampleCount: Int,
    val effectiveFrequencyHz: Double?,
    val medianIntervalMs: Long?,
    val p05IntervalMs: Long?,
    val p95IntervalMs: Long?,
    val longestGapMs: Long?,
    val duplicateTimestampCount: Int,
    val nonMonotonicTimestampCount: Int,
    val firstSampleOffsetMs: Long?,
    val lastSampleOffsetMs: Long?,
    val observedCoveragePercent: Double?
)

data class GpsQualityMetrics(
    val providerCounts: Map<String, Int>,
    val medianAccuracyMeters: Double?,
    val belowFiveMetersPercent: Double?,
    val belowTenMetersPercent: Double?,
    val abovePoorQualityPercent: Double?,
    val invalidSpeedCount: Int,
    val unavailableSpeedCount: Int
)

/** Pure calculations used by trip QA and JVM tests. */
object QualityMetrics {
    fun stream(
        timestampsMs: List<Long>,
        tripStartMs: Long? = null,
        tripEndMs: Long? = null,
        expectedFrequencyHz: Double? = null
    ): StreamQualityMetrics {
        if (timestampsMs.isEmpty()) {
            return StreamQualityMetrics(0, null, null, null, null, null, 0, 0, null, null, null)
        }

        val intervalsInOrder = timestampsMs.zipWithNext { left, right -> right - left }
        val positiveIntervals = intervalsInOrder.filter { it > 0L }.sorted()
        val duplicateCount = intervalsInOrder.count { it == 0L }
        val nonMonotonicCount = intervalsInOrder.count { it < 0L }
        val first = timestampsMs.first()
        val last = timestampsMs.last()
        val spanMs = (timestampsMs.maxOrNull() ?: last) - (timestampsMs.minOrNull() ?: first)
        val effectiveFrequency = if (spanMs > 0L && timestampsMs.size > 1) {
            (timestampsMs.size - 1) * 1000.0 / spanMs
        } else {
            null
        }
        val expectedDurationMs = if (tripStartMs != null && tripEndMs != null && tripEndMs > tripStartMs) {
            tripEndMs - tripStartMs
        } else {
            spanMs
        }
        val expectedCoverage = if (expectedFrequencyHz != null && expectedFrequencyHz > 0.0 && expectedDurationMs > 0L) {
            val expectedSamples = expectedDurationMs / 1000.0 * expectedFrequencyHz
            ((timestampsMs.size / expectedSamples) * 100.0).coerceIn(0.0, 100.0)
        } else {
            null
        }

        return StreamQualityMetrics(
            sampleCount = timestampsMs.size,
            effectiveFrequencyHz = effectiveFrequency,
            medianIntervalMs = percentile(positiveIntervals, 0.50),
            p05IntervalMs = percentile(positiveIntervals, 0.05),
            p95IntervalMs = percentile(positiveIntervals, 0.95),
            longestGapMs = positiveIntervals.maxOrNull(),
            duplicateTimestampCount = duplicateCount,
            nonMonotonicTimestampCount = nonMonotonicCount,
            firstSampleOffsetMs = tripStartMs?.let { first - it },
            lastSampleOffsetMs = tripEndMs?.let { last - it },
            observedCoveragePercent = expectedCoverage
        )
    }

    fun gps(
        rows: List<TripData>,
        poorQualityThresholdMeters: Float = 10f
    ): GpsQualityMetrics {
        val providers = rows.groupingBy { it.provider ?: "UNKNOWN" }.eachCount()
        val accuracies = rows.mapNotNull { it.horizontalAccuracyMeters?.toDouble() }.sorted()
        val validAccuracyCount = accuracies.size.toDouble()
        val invalidSpeedCount = rows.count { it.speedValid == false }
        val unavailableSpeedCount = rows.count { it.speedValid == null || (it.speedValid == true && it.speedKmh == null) }
        return GpsQualityMetrics(
            providerCounts = providers,
            medianAccuracyMeters = percentile(accuracies, 0.50)?.toDouble(),
            belowFiveMetersPercent = percentage(accuracies, validAccuracyCount) { it < 5.0 },
            belowTenMetersPercent = percentage(accuracies, validAccuracyCount) { it < 10.0 },
            abovePoorQualityPercent = percentage(accuracies, validAccuracyCount) { it > poorQualityThresholdMeters },
            invalidSpeedCount = invalidSpeedCount,
            unavailableSpeedCount = unavailableSpeedCount
        )
    }

    private fun percentage(values: List<Double>, count: Double, predicate: (Double) -> Boolean): Double? {
        if (count == 0.0) return null
        return values.count(predicate) * 100.0 / count
    }

    private fun percentile(values: List<Long>, fraction: Double): Long? {
        if (values.isEmpty()) return null
        val index = floor((values.size - 1) * fraction).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }

    private fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val index = floor((values.size - 1) * fraction).toInt().coerceIn(0, values.lastIndex)
        return values[index]
    }
}
