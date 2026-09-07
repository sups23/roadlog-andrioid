package com.example.roadlog

import android.content.Context

/** Debug-only preparation fault injection storage; production never enables it. */
object VoskPreparationInjection {
    private const val PREFS = "roadlog_debug_injections"
    private const val SUPPRESS_NEXT_CALLBACK = "suppress_next_vosk_callback"

    fun setSuppressNextCallback(context: Context, enabled: Boolean = true) {
        if (!BuildConfig.DEBUG) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SUPPRESS_NEXT_CALLBACK, enabled)
            .apply()
    }

    fun consumeNext(context: Context): Boolean {
        if (!BuildConfig.DEBUG) return false
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(SUPPRESS_NEXT_CALLBACK, false)) return false
        preferences.edit().remove(SUPPRESS_NEXT_CALLBACK).apply()
        return true
    }
}
