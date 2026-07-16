package com.example.roadlog

/**
 * Runtime configuration for cause codes, grammar phrases, fuzzy-match variants,
 * and recognition thresholds.
 *
 * Loaded from `assets/cause_config.json` by [CauseConfigLoader].
 */
data class CauseConfig(
    val confidenceThreshold: Float,
    val fuzzyThreshold: Double,
    val minWordLength: Int,
    val causes: List<CauseDefinition>
) {

    /**
     * Look up a cause definition by its code.
     */
    fun findByCode(code: String): CauseDefinition? {
        return causes.firstOrNull { it.code == code }
    }

    /**
     * All phrases Vosk should be allowed to recognize.
     */
    val allGrammarPhrases: List<String>
        get() = causes.flatMap { it.phrases }

    /**
     * Build a map from every phrase and variant to its cause code.
     */
    val keywordMap: Map<String, List<String>>
        get() = causes.associate { cause ->
            cause.code to (cause.phrases + cause.variants)
        }

    /**
     * Build a direct lookup from exact phrase to cause code.
     */
    val phraseToCauseMap: Map<String, String>
        get() = causes.flatMap { cause ->
            cause.phrases.map { phrase -> phrase to cause.code }
        }.toMap()
}

/**
 * Definition of a single cause code.
 *
 * @property code Stable cause identifier used in storage and broadcasts.
 * @property displayName Human-readable long name (used in trip breakdowns).
 * @property shortForm Short text shown on the main-screen label.
 * @property phrases Exact phrases included in the Vosk grammar.
 * @property variants Additional misheard/pronunciation variants used only by
 *           the fuzzy matcher as a fallback.
 */
data class CauseDefinition(
    val code: String,
    val displayName: String,
    val shortForm: String,
    val phrases: List<String>,
    val variants: List<String>
)
