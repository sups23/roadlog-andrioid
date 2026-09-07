package com.example.roadlog

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskPreparationWaitTest {
    @Test
    fun `invalidated preparation callbacks cannot become current`() {
        val generations = VoskPreparationGeneration()
        val first = generations.begin()

        generations.invalidate()
        val second = generations.begin()

        assertFalse(generations.isCurrent(first))
        assertTrue(generations.isCurrent(second))
    }

    @Test
    fun `never completing preparation is bounded by timeout`() = runTest {
        var ready = false

        try {
            VoskPreparationWait.await(
                isReady = { ready },
                error = { null },
                stopRequested = { false },
                timeoutMs = 1_000L,
                pollIntervalMs = 250L
            )
            throw AssertionError("preparation wait should time out")
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            assertFalse(ready)
        }
    }

    @Test
    fun `stop request ends preparation without being treated as timeout`() = runTest {
        var stopRequested = false
        val wait = async {
            VoskPreparationWait.await(
                isReady = { false },
                error = { null },
                stopRequested = { stopRequested },
                timeoutMs = 120_000L,
                pollIntervalMs = 250L
            )
        }

        runCurrent()
        stopRequested = true
        advanceUntilIdle()

        assertTrue(wait.await() == false)
    }
}
