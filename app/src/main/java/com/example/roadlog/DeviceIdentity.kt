package com.example.roadlog

import android.content.Context
import java.util.UUID

object DeviceIdentity {
    private const val PREFS = "research_identity"
    private const val DEVICE_ID = "device_id"

    fun get(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = preferences.getString(DEVICE_ID, null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        preferences.edit().putString(DEVICE_ID, generated).apply()
        return generated
    }
}
