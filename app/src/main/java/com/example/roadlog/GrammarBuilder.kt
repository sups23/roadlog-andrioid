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
 * The phrase list below is intentionally easy to extend: add a new string to
 * the [phrases] list and, if it should map to a cause, add a matching keyword
 * in [FuzzyCauseMatcher].
 */
object GrammarBuilder {

    /**
     * All phrases the recognizer is allowed to produce.
     *
     * Each entry must be lowercase and contain only letters, numbers, spaces,
     * and hyphens. Multi-word phrases are allowed; Vosk treats each string as
     * one allowed utterance alternative.
     */
    val phrases = listOf(
        // SIGNAL
        "signal",
        "traffic signal",
        "light",
        "red light",

        // QUEUE
        "queue",
        "line",
        "traffic jam",
        "congestion",

        // BUS
        "bus",
        "microbus",
        "minibus",

        // PEDESTRIAN
        "pedestrian",
        "walking",

        // ROUGHNESS (general rough road)
        "roughness",
        "rough road",
        "bump",

        // POTHOLE
        "pothole",
        "pot hole",

        // SPEED_BREAKER
        "speed breaker",
        "speed bump",

        // CONSTRUCTION
        "construction",
        "road work",

        // FRICTION
        "friction",
        "parking",
        "parked car",
        "side friction",

        // TURNING
        "turning",
        "turning vehicle",
        "u turn",

        // MARKET
        "market",
        "street vendor",
        "stall",
        "vendors"
    )

    /**
     * Returns the grammar as a JSON array string suitable for Vosk's
     * [org.vosk.Recognizer](Model, float, String) constructor.
     *
     * Example output: ["signal","traffic signal","bus",...]
     */
    fun buildGrammarJson(): String {
        return JSONArray(phrases).toString()
    }
}
