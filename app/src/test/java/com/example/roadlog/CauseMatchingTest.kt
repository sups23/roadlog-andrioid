package com.example.roadlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CauseMatchingTest {
    private val config = CauseConfig(
        confidenceThreshold = 0.6f,
        fuzzyThreshold = 0.85,
        minWordLength = 3,
        activationPhrases = listOf("log"),
        causes = listOf(
            CauseDefinition(
                code = "ROUGH",
                displayName = "ROUGH",
                shortForm = "ROUGH",
                phrases = listOf("pothole"),
                variants = listOf("pothol")
            ),
            CauseDefinition(
                code = ResearchCodebook.UNKNOWN_CODE,
                displayName = ResearchCodebook.UNKNOWN_CODE,
                shortForm = ResearchCodebook.UNKNOWN_CODE,
                phrases = listOf("unknown", "unclassified"),
                variants = emptyList(),
                voiceOnly = false
            )
        )
    )

    @Test
    fun `grammar requires activation and includes the recognizer fallback`() {
        val grammar = GrammarBuilder.buildGrammarJson(config)

        assertTrue(grammar.contains("log pothole"))
        assertTrue(grammar.contains("log unclassified"))
        assertTrue(grammar.contains("[unk]"))
        assertTrue(config.findActivationPhrase("Log pothole") == "log")
        assertNull(config.findActivationPhrase("pothole"))
    }

    @Test
    fun `explicit unclassified phrase maps to its own code`() {
        assertEquals(ResearchCodebook.UNKNOWN_CODE, config.phraseToCauseMap["log unclassified"])
        assertTrue(config.findByCode(ResearchCodebook.UNKNOWN_CODE)?.voiceOnly == false)
    }

    @Test
    fun `activated commands return canonical codes`() {
        val result = CauseCommandParser(config).parse("Log pothol")

        assertEquals("ROUGH", result.causeCode)
        assertNull(result.rejection)
    }

    @Test
    fun `ambiguous command is rejected instead of choosing one cause`() {
        val result = CauseCommandParser(config).parse("log pothole unknown")

        assertNull(result.causeCode)
        assertEquals(CauseCommandRejection.MULTIPLE_CAUSES, result.rejection)
    }

    @Test
    fun `commands must match one complete approved phrase`() {
        val parser = CauseCommandParser(config)

        assertEquals("ROUGH", parser.parse("log pothol").causeCode)
        assertEquals(CauseCommandRejection.UNMATCHED_COMMAND, parser.parse("log pothole maybe").rejection)
        assertEquals(CauseCommandRejection.UNMATCHED_COMMAND, parser.parse("log pothol extra").rejection)
    }

    @Test
    fun `cross-cause duplicate aliases fail configuration closed`() {
        val duplicateConfig = config.copy(
            causes = listOf(
                config.causes[0],
                config.causes[1].copy(phrases = listOf("pothole"))
            )
        )

        try {
            CauseCommandParser(duplicateConfig)
            throw AssertionError("duplicate alias was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("duplicate command aliases"))
        }
    }

    @Test
    fun `new categories use exact commands without mapping legacy ambiguous phrases`() {
        val parser = CauseCommandParser(proposedConfig())
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
            "log side obstruction" to "FRICTION"
        )

        accepted.forEach { (command, code) -> assertEquals(command, code, parser.parse(command).causeCode) }
        listOf(
            "log turning", "log u turn", "log friction", "log parked", "log speed bump",
            "log parked bike and parked car", "log lead turn and queue"
        ).forEach { command ->
            assertNull("$command should not select a cause", parser.parse(command).causeCode)
        }
        assertEquals(
            CauseCommandRejection.MULTIPLE_CAUSES,
            parser.parse("log parked bike and parked car").rejection
        )
    }

    private fun proposedConfig(): CauseConfig {
        val causes = listOf(
            "SIGNAL", "QUEUE", "BUS", "PED", "ROUGH", "CONSTRUCTION", "FRICTION", "TURNING",
            "MARKET", "UNKNOWN", "SLOW_LEAD_VEHICLE", "MERGING", "LEAD_TURN", "CROSSING_TURN",
            "PARKED_BIKE", "PARKED_CAR", "DELIVERY_STOP", "SPEED_BREAKER"
        ).map { code ->
            val phrase = when (code) {
                "FRICTION" -> "side obstruction"
                "TURNING" -> "other turn"
                "SLOW_LEAD_VEHICLE" -> "slow lead"
                "MERGING" -> "merging"
                "LEAD_TURN" -> "lead turn"
                "CROSSING_TURN" -> "crossing turn"
                "PARKED_BIKE" -> "parked bike"
                "PARKED_CAR" -> "parked car"
                "DELIVERY_STOP" -> "delivery stop"
                "SPEED_BREAKER" -> "speed breaker"
                "UNKNOWN" -> "unknown"
                else -> code.lowercase()
            }
            val phrases = if (code == "SLOW_LEAD_VEHICLE") {
                listOf(phrase, "slow lead vehicle")
            } else {
                listOf(phrase)
            }
            CauseDefinition(code, code, code, phrases, emptyList())
        }
        return config.copy(causes = causes)
    }

    @Test
    fun `missing activation and recognizer unknown are rejected`() {
        val parser = CauseCommandParser(config)

        assertEquals(
            CauseCommandRejection.MISSING_ACTIVATION,
            parser.parse("pothole").rejection
        )
        assertEquals(
            CauseCommandRejection.UNKNOWN_COMMAND,
            parser.parse("log [unk]").rejection
        )
    }
}
