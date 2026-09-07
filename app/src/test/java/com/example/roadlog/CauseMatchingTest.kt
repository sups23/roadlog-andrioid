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
        assertEquals(CauseCommandRejection.AMBIGUOUS_COMMAND, result.rejection)
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
