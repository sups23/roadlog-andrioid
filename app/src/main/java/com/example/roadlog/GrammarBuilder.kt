package com.example.roadlog

import org.json.JSONArray

/**
 * Builds the grammar JSON used to constrain the Vosk recognizer.
 *
 * A grammar-constrained recognizer is far more accurate than the generic
 * free-form English recognizer when the vocabulary is small and known. By
 * limiting Vosk's search space to the exact phrases the user is expected to
 * speak, the decoder cannot hallucinate arbitrary English words under road
 * noise, wind, or microphone vibration. It can only output one of the listed
 * phrases (or a phonetically close variant that is still within the grammar).
 *
 * The phrases come from [CauseConfig.allGrammarPhrases], which is loaded from
 * `assets/cause_config.json`. Edit that file to add, remove, or change phrases.
 */
object GrammarBuilder {

    /**
     * Returns the grammar as a JSON array string suitable for Vosk's
     * [org.vosk.Recognizer](Model, float, String) constructor.
     *
     * Example output: ["signal","traffic signal","bus",...]
     */
    fun buildGrammarJson(config: CauseConfig): String {
        return JSONArray(config.allGrammarPhrases).toString()
    }
}
