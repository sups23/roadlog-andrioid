package com.example.roadlog

import android.content.Context

enum class AudioFailureInjectionPoint {
    NONE,
    ENCODER_INITIALIZATION,
    FRAME_PROCESSING,
    SEGMENT_FINALIZATION;

    companion object {
        fun fromStoredValue(value: String?): AudioFailureInjectionPoint =
            values().firstOrNull { it.name == value } ?: NONE
    }
}

class AudioFailureInjector(private val point: AudioFailureInjectionPoint) {
    private var triggered = false

    @Synchronized
    fun maybeFail(at: AudioFailureInjectionPoint) {
        if (triggered || point != at || point == AudioFailureInjectionPoint.NONE) return
        triggered = true
        throw IllegalStateException("debug audio failure injection: ${at.name}")
    }
}

object AudioFailureInjectionConfig {
    private const val PREFS_NAME = "roadlog_debug_audio"
    private const val NEXT_FAILURE_KEY = "next_failure"

    fun setNext(context: Context, point: AudioFailureInjectionPoint) {
        if (!BuildConfig.DEBUG) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(NEXT_FAILURE_KEY, point.name)
            .apply()
    }

    fun consumeNext(context: Context): AudioFailureInjectionPoint {
        if (!BuildConfig.DEBUG) return AudioFailureInjectionPoint.NONE
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val point = AudioFailureInjectionPoint.fromStoredValue(
            preferences.getString(NEXT_FAILURE_KEY, null)
        )
        preferences.edit().remove(NEXT_FAILURE_KEY).apply()
        return point
    }
}
