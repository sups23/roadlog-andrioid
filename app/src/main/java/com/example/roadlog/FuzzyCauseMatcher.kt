package com.example.roadlog

import android.util.Log

/**
 * Maps recognized speech text to a cause code using fuzzy string matching.
 * Handles accent distortions, minor misrecognitions, and phonetic variants.
 */
class FuzzyCauseMatcher {

    data class MatchResult(
        val causeCode: String,
        val matchedWord: String,
        val score: Double
    )

    /**
     * Expanded keyword list. Each cause has multiple keywords including
     * common misheard variants to improve recognition under road noise.
     */
    private val keywordMap: Map<String, List<String>> = mapOf(
        "SIGNAL" to listOf(
            "signal", "signals", "light", "lights", "seegal", "sihgnal",
            "sigmal", "sig nal", "sig nal", "cignal", "cignel"
        ),
        "QUEUE" to listOf(
            "queue", "queues", "line", "lines", "q", "cue", "kyu",
            "kue", "kew", "kyew", "qu", "cu"
        ),
        "BUS" to listOf(
            "bus", "buses", "buss", "bss", "buz", "buzes"
        ),
        "PEDESTRIAN" to listOf(
            "pedestrian", "pedestrians", "ped", "walk", "walking",
            "pedestrien", "padestrian", "pdestrian", "pedy"
        ),
        "ROUGHNESS" to listOf(
            "roughness", "rough", "bump", "bumps", "pothole", "potholes",
            "roufness", "rowghness", "ruffness", "rufness",
            "rougnes", "roufnes", "bamp", "bum"
        ),
        "FRICTION" to listOf(
            "friction", "frictions", "parking", "parked",
            "friktion", "frickshun", "parkin", "parkd", "frikshin"
        ),
        "TURNING" to listOf(
            "turn", "turns", "turning", "u-turn", "u turn",
            "tern", "terning", "torn", "tarning", "uturn"
        ),
        "MARKET" to listOf(
            "market", "markets", "stall", "stalls", "vendor", "vendors",
            "markit", "markat", "stawl", "vendr", "vendur"
        )
    )

    private val allKeywords: List<Pair<String, String>> = keywordMap.flatMap { (cause, words) ->
        words.map { word -> cause to word }
    }

    /**
     * Find the best matching cause for the given spoken text.
     *
     * @param spoken The raw recognized text from Vosk.
     * @param threshold Minimum similarity score (0.0–1.0) to accept a match.
     * @return The best [MatchResult] or null if no match exceeds the threshold.
     */
    fun findBestMatch(spoken: String, threshold: Double = 0.7): MatchResult? {
        val cleaned = spoken.lowercase()
            .replace(Regex("[^a-z0-9\\- ]"), " ")
            .trim()

        if (cleaned.isEmpty()) return null

        val inputWords = cleaned.split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length >= MIN_INPUT_WORD_LENGTH }
        if (inputWords.isEmpty()) return null

        var bestCause = ""
        var bestWord = ""
        var bestScore = 0.0

        for (inputWord in inputWords) {
            for ((causeCode, keyword) in allKeywords) {
                val score = similarity(inputWord, keyword)
                if (score > bestScore) {
                    bestScore = score
                    bestCause = causeCode
                    bestWord = keyword
                }
            }
        }

        Log.d(TAG, "Fuzzy match for '$spoken' → best='$bestWord' cause=$bestCause score=%.2f".format(bestScore))

        return if (bestScore >= threshold) {
            MatchResult(bestCause, bestWord, bestScore)
        } else {
            null
        }
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
        private const val MIN_INPUT_WORD_LENGTH = 3
    }
}
