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
                code = "POTHOLE",
                displayName = "POTHOLE",
                shortForm = "POTHL",
                phrases = listOf("pothole"),
                variants = listOf("pothol")
            ),
            CauseDefinition(
                code = "UNCLASSIFIED",
                displayName = "Other",
                shortForm = "OTHER",
                phrases = listOf("unclassified"),
                variants = emptyList(),
                voiceOnly = true
            )
        )
    )

    @Test
    fun `grammar requires activation and allows unknown speech`() {
        val grammar = GrammarBuilder.buildGrammarJson(config)

        assertTrue(grammar.contains("log pothole"))
        assertTrue(grammar.contains("log unclassified"))
        assertTrue(grammar.contains("[unk]"))
        assertTrue(config.findActivationPhrase("Log pothole") == "log")
        assertNull(config.findActivationPhrase("pothole"))
    }

    @Test
    fun `explicit unclassified phrase maps to its own code`() {
        assertEquals("UNCLASSIFIED", config.phraseToCauseMap["log unclassified"])
    }
}
