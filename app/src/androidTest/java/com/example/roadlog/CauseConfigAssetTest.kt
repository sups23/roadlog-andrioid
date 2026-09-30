package com.example.roadlog

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CauseConfigAssetTest {
    @Test
    fun shippedV4AssetMatchesTheCodebookAndExactCommandContract() {
        val config = CauseConfigLoader.load(
            ApplicationProvider.getApplicationContext<android.content.Context>()
        )
        val parser = CauseCommandParser(config)

        assertEquals("4", config.version)
        assertEquals(ResearchCodebook.primaryCodes, config.causes.map { it.code }.toSet())
        assertTrue(config.allGrammarPhrases.contains("log slow lead"))
        assertTrue(config.allGrammarPhrases.contains("log slow lead vehicle"))
        assertTrue(config.allGrammarPhrases.contains("log speed breaker"))

        val accepted = mapOf(
            "log slow lead" to "SLOW_LEAD_VEHICLE",
            "log slow lead vehicle" to "SLOW_LEAD_VEHICLE",
            "log merging" to "MERGING",
            "log lead turn" to "LEAD_TURN",
            "log crossing turn" to "CROSSING_TURN",
            "log parked bike" to "PARKED_BIKE",
            "log parked car" to "PARKED_CAR",
            "log delivery stop" to "DELIVERY_STOP",
            "log speed breaker" to "SPEED_BREAKER",
            "log other turn" to "TURNING",
            "log side obstruction" to "FRICTION",
            "log unknown" to "UNKNOWN"
        )
        accepted.forEach { (command, code) -> assertEquals(code, parser.parse(command).causeCode) }

        assertNull(parser.parse("log speed bump").causeCode)
        assertNull(parser.parse("log turning").causeCode)
        assertNull(parser.parse("log friction").causeCode)
        assertNull(parser.parse("log parked").causeCode)
        assertEquals(
            CauseCommandRejection.MULTIPLE_CAUSES,
            parser.parse("log parked bike and parked car").rejection
        )
    }
}
