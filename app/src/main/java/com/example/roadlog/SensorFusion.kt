package com.example.roadlog

import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * World-frame sensor samples derived from raw accelerometer/gyroscope data
 * and the rotation-vector sensor.
 *
 * The game-rotation vector gives a world frame where Z is aligned with gravity.
 * X and Y are in the horizontal plane, fixed to the phone’s orientation at the
 * moment the sensor started. For a phone lying flat with its top pointing
 * forward, X ≈ lateral and Y ≈ longitudinal.
 */

data class WorldAccelSample(
    val timestamp: Long,
    val vertical: Float,
    val lateral: Float,
    val longitudinal: Float
)

data class WorldGyroSample(
    val timestamp: Long,
    val yaw: Float
)

private const val GRAVITY = 9.80665f

/**
 * Convert raw accelerometer rows into world-frame linear acceleration.
 * For each accel sample the nearest rotation-vector sample is used.
 */
fun computeWorldAccel(
    accelData: List<TripData>,
    rotationData: List<TripData>
): List<WorldAccelSample> {
    if (rotationData.isEmpty()) {
        // No orientation data: assume phone is flat and only vertical (raw Z) is useful.
        return accelData.map {
            WorldAccelSample(
                timestamp = it.timestamp,
                vertical = (it.accelZ ?: 0f) - GRAVITY,
                lateral = it.accelX ?: 0f,
                longitudinal = it.accelY ?: 0f
            )
        }
    }

    val result = mutableListOf<WorldAccelSample>()
    var rotIndex = 0
    val rotationMatrix = FloatArray(9)

    for (accel in accelData) {
        rotIndex = findNearestIndex(rotationData, accel.timestamp, rotIndex)
        val rot = rotationData[rotIndex]
        val rotVec = floatArrayOf(rot.rotX ?: 0f, rot.rotY ?: 0f, rot.rotZ ?: 0f, rot.rotW ?: 1f)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotVec)

        val ax = accel.accelX ?: 0f
        val ay = accel.accelY ?: 0f
        val az = accel.accelZ ?: 0f

        // Column-major multiplication: world = R * device
        val worldX = rotationMatrix[0] * ax + rotationMatrix[3] * ay + rotationMatrix[6] * az
        val worldY = rotationMatrix[1] * ax + rotationMatrix[4] * ay + rotationMatrix[7] * az
        val worldZ = rotationMatrix[2] * ax + rotationMatrix[5] * ay + rotationMatrix[8] * az

        result.add(
            WorldAccelSample(
                timestamp = accel.timestamp,
                vertical = worldZ - GRAVITY,
                lateral = worldX,
                longitudinal = worldY
            )
        )
    }

    return result
}

/**
 * Convert raw gyroscope rows into world-frame yaw rate.
 */
fun computeWorldGyro(
    gyroData: List<TripData>,
    rotationData: List<TripData>
): List<WorldGyroSample> {
    if (rotationData.isEmpty()) {
        return gyroData.map {
            WorldGyroSample(
                timestamp = it.timestamp,
                yaw = it.gyroZ ?: 0f
            )
        }
    }

    val result = mutableListOf<WorldGyroSample>()
    var rotIndex = 0
    val rotationMatrix = FloatArray(9)

    for (gyro in gyroData) {
        rotIndex = findNearestIndex(rotationData, gyro.timestamp, rotIndex)
        val rot = rotationData[rotIndex]
        val rotVec = floatArrayOf(rot.rotX ?: 0f, rot.rotY ?: 0f, rot.rotZ ?: 0f, rot.rotW ?: 1f)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotVec)

        val gx = gyro.gyroX ?: 0f
        val gy = gyro.gyroY ?: 0f
        val gz = gyro.gyroZ ?: 0f

        val worldZ = rotationMatrix[2] * gx + rotationMatrix[5] * gy + rotationMatrix[8] * gz

        result.add(
            WorldGyroSample(
                timestamp = gyro.timestamp,
                yaw = worldZ
            )
        )
    }

    return result
}

/**
 * Group samples into 1-second bins and compute RMS per bin.
 * Returns a sorted map of elapsed-second -> RMS value.
 */
fun downsampleToRmsBins(
    samples: List<Pair<Long, Float>>,
    tripStartMs: Long
): Map<Long, Double> {
    if (samples.isEmpty()) return emptyMap()
    val grouped = samples.groupBy { (it.first - tripStartMs) / 1000L }
    return grouped.mapValues { (_, values) ->
        val meanSq = values.map { it.second * it.second }.average()
        sqrt(meanSq)
    }.toSortedMap()
}

/**
 * Find the index of the rotation sample whose timestamp is closest to [timeMs].
 * [startIndex] is a hint for the two-pointer search; both lists are sorted.
 */
private fun findNearestIndex(data: List<TripData>, timeMs: Long, startIndex: Int): Int {
    var index = startIndex.coerceIn(0, data.size - 1)
    while (index < data.size - 1 && data[index + 1].timestamp < timeMs) {
        index++
    }
    while (index > 0 && data[index].timestamp > timeMs && data[index - 1].timestamp >= timeMs) {
        index--
    }
    if (index < data.size - 1) {
        val diffCurrent = kotlin.math.abs(data[index].timestamp - timeMs)
        val diffNext = kotlin.math.abs(data[index + 1].timestamp - timeMs)
        if (diffNext < diffCurrent) {
            index++
        }
    }
    return index
}
