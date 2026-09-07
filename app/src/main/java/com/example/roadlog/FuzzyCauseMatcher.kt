package com.example.roadlog

import android.util.Log

/**
 * Maps recognized speech text to a cause code using fuzzy string matching.
 * Handles accent distortions, minor misrecognitions, and phonetic variants.
 *
 * The grammar-constrained Vosk recognizer can only output phrases that are
 * explicitly listed in the grammar. That means the text passed here is usually
 * already one of the expected phrases, but the fuzzy matcher still provides a
 * robust fallback for partial results and any remaining audio-noise distortion.
 *
 * All keywords come from [CauseConfig]; edit `assets/cause_config.json` to
 * customize the mapping.
 */
class FuzzyCauseMatcher(private val config: CauseConfig) {

    data class MatchResult(
        val causeCode: String,
        val matchedWord: String,
        val score: Double
    )

    private val allKeywords: List<Pair<String, String>> = config.keywordMap.flatMap { (cause, words) ->
        words.map { word -> cause to word }
    }

    /**
     * Find the best matching cause for the given spoken text.
     *
     * @param spoken The raw recognized text from Vosk.
     * @return The best [MatchResult] or null if no match exceeds the configured
     *         fuzzy threshold.
     */
    fun findBestMatch(spoken: String): MatchResult? {
        return findBestMatches(spoken).firstOrNull()
    }

    /** Returns the best match for each cause in deterministic score order. */
    fun findBestMatches(spoken: String): List<MatchResult> {
        val cleaned = CauseConfig.normalizeSpeech(spoken)

        if (cleaned.isEmpty()) return emptyList()

        val matches = allKeywords
            .groupBy { it.first }
            .map { (causeCode, keywords) ->
                keywords
                    .map { (_, keyword) -> keyword to similarity(cleaned, keyword) }
                    .maxWithOrNull(compareBy<Pair<String, Double>> { it.second }.thenBy { it.first })
                    ?.let { (keyword, score) -> MatchResult(causeCode, keyword, score) }
            }
            .filterNotNull()
            .sortedWith(compareByDescending<MatchResult> { it.score }.thenBy { it.causeCode })

        matches.firstOrNull()?.let { best ->
            Log.d(TAG, "Fuzzy match for '$spoken' -> best='${best.matchedWord}' cause=${best.causeCode} score=%.2f".format(best.score))
        }
        return matches
    }

    /**
     * Normalized Levenshtein similarity: 1.0 = identical, 0.0 = completely different.
     */
    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        val distance = levenshtein(a, b)
        return 1.0 - (distance.toDouble() / maxLen)
    }

    /**
     * Standard Levenshtein distance.
     */
    private fun levenshtein(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m

        var prev = IntArray(n + 1) { it }
        var curr = IntArray(n + 1)

        for (i in 1..m) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,      // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return prev[n]
    }

    companion object {
        private const val TAG = "RoadLog"
    }
}
