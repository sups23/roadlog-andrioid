package com.example.roadlog

import android.content.Context
import org.json.JSONObject

/**
 * Loads [CauseConfig] from `assets/cause_config.json`.
 */
object CauseConfigLoader {

    private const val CONFIG_FILE = "cause_config.json"

    fun load(context: Context): CauseConfig {
        val rawJson = context.assets.open(CONFIG_FILE)
            .bufferedReader()
            .use { it.readText() }
        return parse(JSONObject(rawJson), rawJson)
    }

    private fun parse(root: JSONObject, rawJson: String = ""): CauseConfig {
        val causesArray = root.getJSONArray("causes")
        val causes = (0 until causesArray.length()).map { i ->
            parseCause(causesArray.getJSONObject(i))
        }

        val config = CauseConfig(
            confidenceThreshold = root.optDouble("confidenceThreshold", 0.6).toFloat(),
            fuzzyThreshold = root.optDouble("fuzzyThreshold", 0.85),
            minWordLength = root.optInt("minWordLength", 3),
            activationPhrases = parseStringArray(
                root.optJSONArray("activationPhrases") ?: org.json.JSONArray().apply {
                    put("log")
                }
            ),
            causes = causes,
            version = root.optString("version", ResearchCodebook.VERSION),
            rawJson = rawJson
        )
        config.requireUniqueCommandAliases()
        if (config.version == ResearchCodebook.VERSION) {
            require(config.causes.map { it.code }.toSet() == ResearchCodebook.primaryCodes) {
                "cause configuration codes do not match codebook version ${config.version}"
            }
            ResearchCodebook.v4Definitions.forEach { (code, definition) ->
                require(config.findByCode(code)?.definition == definition) {
                    "cause definition for $code does not match codebook version ${config.version}"
                }
            }
        }
        return config
    }

    private fun parseCause(obj: JSONObject): CauseDefinition {
        return CauseDefinition(
            code = obj.getString("code"),
            displayName = obj.getString("displayName"),
            shortForm = obj.getString("shortForm"),
            phrases = parseStringArray(obj.getJSONArray("phrases")),
            variants = parseStringArray(obj.optJSONArray("variants") ?: org.json.JSONArray()),
            voiceOnly = obj.optBoolean("voiceOnly", false),
            definition = obj.optString("definition").takeIf { it.isNotBlank() }
        )
    }

    private fun parseStringArray(array: org.json.JSONArray): List<String> {
        return (0 until array.length()).map { i -> array.getString(i) }
    }
}
