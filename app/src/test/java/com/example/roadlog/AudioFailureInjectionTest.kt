package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AudioFailureInjectionTest {
    @Test
    fun `unknown stored value disables injection`() {
        assertEquals(
            AudioFailureInjectionPoint.NONE,
            AudioFailureInjectionPoint.fromStoredValue("not-a-real-failure")
        )
    }

    @Test
    fun `selected injection fails once at the matching point`() {
        val injector = AudioFailureInjector(AudioFailureInjectionPoint.FRAME_PROCESSING)

        try {
            injector.maybeFail(AudioFailureInjectionPoint.FRAME_PROCESSING)
            fail("Expected the selected failure point to throw")
        } catch (error: IllegalStateException) {
            assertEquals(
                "debug audio failure injection: FRAME_PROCESSING",
                error.message
            )
        }

        injector.maybeFail(AudioFailureInjectionPoint.FRAME_PROCESSING)
    }
}
