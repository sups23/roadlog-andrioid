package com.example.roadlog

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Waits for model preparation without allowing an absent callback to hang forever. */
internal object VoskPreparationWait {
    suspend fun await(
        isReady: () -> Boolean,
        error: () -> String?,
        stopRequested: () -> Boolean,
        timeoutMs: Long,
        pollIntervalMs: Long = 250L
    ): Boolean = withTimeout(timeoutMs) {
        while (!isReady() && error() == null && !stopRequested()) {
            delay(pollIntervalMs)
        }
        isReady()
    }
}
