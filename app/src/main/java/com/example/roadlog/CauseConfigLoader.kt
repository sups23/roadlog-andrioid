package com.example.roadlog

import android.content.Context
import org.json.JSONObject

/**
 * Loads [CauseConfig] from `assets/cause_config.json`.
 */
object CauseConfigLoader {

    private const val CONFIG_FILE = "cause_config.json"

    fun load(context: Context): CauseConfig {
        val json = context.assets.open(CONFIG_FILE)
            .bufferedReader()
            .use { it.readText() }
        return parse(JSONObject(json))
    }

    private fun parse(root: JSONObject): CauseConfig {
        val causesArray = root.getJSONArray("causes")
        val causes = (0 until causesArray.length()).map { i ->
            parseCause(causesArray.getJSONObject(i))
        }

        return CauseConfig(
            confidenceThreshold = root.optDouble("confidenceThreshold", 0.6).toFloat(),
            fuzzyThreshold = root.optDouble("fuzzyThreshold", 0.85),
            minWordLength = root.optInt("minWordLength", 3),
            activationPhrases = parseStringArray(
                root.optJSONArray("activationPhrases") ?: org.json.JSONArray().apply {
                    put("log")
                }
            ),
            causes = causes
        )
    }

    private fun parseCause(obj: JSONObject): CauseDefinition {
        return CauseDefinition(
            code = obj.getString("code"),
            displayName = obj.getString("displayName"),
            shortForm = obj.getString("shortForm"),
            phrases = parseStringArray(obj.getJSONArray("phrases")),
            variants = parseStringArray(obj.optJSONArray("variants") ?: org.json.JSONArray()),
            voiceOnly = obj.optBoolean("voiceOnly", false)
        )
    }

    private fun parseStringArray(array: org.json.JSONArray): List<String> {
        return (0 until array.length()).map { i -> array.getString(i) }
    }
}
