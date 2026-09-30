package com.example.roadlog

import java.util.Locale

/**
 * Runtime configuration for cause codes, grammar phrases, approved command
 * aliases, and recognizer thresholds.
 *
 * Loaded from `assets/cause_config.json` by [CauseConfigLoader].
 */
data class CauseConfig(
    val confidenceThreshold: Float,
    val fuzzyThreshold: Double,
    val minWordLength: Int,
    val activationPhrases: List<String>,
    val causes: List<CauseDefinition>,
    val version: String = ResearchCodebook.VERSION,
    val rawJson: String = ""
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
        get() = activationPhrases.flatMap { activation ->
            causes.flatMap { cause ->
                cause.phrases.map { phrase -> "$activation $phrase" }
            }
        } + "[unk]"

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
            activationPhrases.flatMap { activation ->
                cause.phrases.map { phrase -> "$activation $phrase" to cause.code }
            }
        }.toMap()

    /** Fail closed if configuration would assign the same spoken token to multiple causes. */
    fun requireUniqueCommandAliases() {
        require(causes.map { it.code }.distinct().size == causes.size) {
            "cause configuration contains duplicate cause codes"
        }
        val duplicateAliases = causes
            .flatMap { cause -> (cause.phrases + cause.variants).map { it to cause.code } }
            .groupBy({ normalizeSpeech(it.first) }, { it.second })
            .filter { (alias, codes) -> alias.isBlank() || codes.distinct().size > 1 }
            .keys
        require(duplicateAliases.isEmpty()) {
            "cause configuration has blank or cross-cause duplicate command aliases: " +
                duplicateAliases.sorted().joinToString()
        }
    }

    fun findActivationPhrase(spoken: String): String? {
        val normalized = normalizeSpeech(spoken)
        return activationPhrases
            .sortedByDescending { it.length }
            .firstOrNull { normalized == it || normalized.startsWith("$it ") }
    }

    companion object {
        fun normalizeSpeech(value: String): String {
            return value.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9\\- ]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
        }
    }
}

enum class CauseCommandRejection {
    MISSING_ACTIVATION,
    EMPTY_COMMAND,
    UNKNOWN_COMMAND,
    BELOW_CONFIDENCE,
    AMBIGUOUS_COMMAND,
    MULTIPLE_CAUSES,
    UNMATCHED_COMMAND
}

data class CauseCommandResult(
    val causeCode: String? = null,
    val rejection: CauseCommandRejection? = null
)

/** Parses one activated command without collapsing an ambiguous command to one cause. */
class CauseCommandParser(private val config: CauseConfig) {
    private val aliasesByCause = config.causes.associate { cause ->
        cause.code to (cause.phrases + cause.variants).map { CauseConfig.normalizeSpeech(it) }.distinct()
    }

    init {
        config.requireUniqueCommandAliases()
    }

    fun parse(spoken: String): CauseCommandResult {
        val normalized = CauseConfig.normalizeSpeech(spoken)
        val activation = config.findActivationPhrase(normalized)
            ?: return CauseCommandResult(rejection = CauseCommandRejection.MISSING_ACTIVATION)
        val command = normalized.removePrefix(activation).trim()
        if (command.isEmpty()) {
            return CauseCommandResult(rejection = CauseCommandRejection.EMPTY_COMMAND)
        }
        if (command == "unk" || command == "[unk]") {
            return CauseCommandResult(rejection = CauseCommandRejection.UNKNOWN_COMMAND)
        }

        val containedCodes = aliasesByCause.filterValues { aliases ->
            aliases.any { alias -> containsWholePhrase(command, alias) }
        }.keys
        if (containedCodes.size > 1) {
            return CauseCommandResult(rejection = CauseCommandRejection.MULTIPLE_CAUSES)
        }

        val exactCodes = aliasesByCause.filterValues { aliases -> command in aliases }.keys
        return when {
            exactCodes.size == 1 -> CauseCommandResult(causeCode = exactCodes.single())
            exactCodes.size > 1 -> CauseCommandResult(rejection = CauseCommandRejection.AMBIGUOUS_COMMAND)
            else -> CauseCommandResult(rejection = CauseCommandRejection.UNMATCHED_COMMAND)
        }
    }

    private fun containsWholePhrase(text: String, phrase: String): Boolean {
        if (phrase.isEmpty()) return false
        return Regex("(^|\\s)${Regex.escape(phrase)}(\\s|$)").containsMatchIn(text)
    }
}

/**
 * Definition of a single cause code.
 *
 * @property code Stable cause identifier used in storage and broadcasts.
 * @property displayName Human-readable long name (used in trip breakdowns).
 * @property shortForm Short text shown on the main-screen label.
 * @property phrases Exact phrases included in the Vosk grammar.
 * @property variants Additional explicitly approved whole-command aliases. The
 *           activated parser accepts these exactly and never fuzzy-resolves them.
 */
data class CauseDefinition(
    val code: String,
    val displayName: String,
    val shortForm: String,
    val phrases: List<String>,
    val variants: List<String>,
    val voiceOnly: Boolean = false,
    val definition: String? = null
)
