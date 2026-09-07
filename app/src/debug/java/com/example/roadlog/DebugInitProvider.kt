package com.example.roadlog

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return false
        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is TripHistoryActivity) return
                activity.window.decorView.post {
                    val debugButtons = activity.findViewById<View>(R.id.debugButtons) ?: return@post
                    debugButtons.visibility = View.VISIBLE
                    activity.findViewById<Button>(R.id.seedDemoButton)?.setOnClickListener {
                        scope.launch {
                            val seeded = withContext(Dispatchers.IO) {
                                DebugSeeder.seedIfNeeded(activity)
                            }
                            if (seeded) {
                                Toast.makeText(activity, "Seeded demo trips", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(activity, "Demo data already present", Toast.LENGTH_SHORT).show()
                            }
                            activity.loadTrips()
                        }
                    }
                    activity.findViewById<Button>(R.id.clearDemoButton)?.setOnClickListener {
                        scope.launch {
                            if (!DebugSeeder.isSeeded(activity)) {
                                Toast.makeText(activity, "No demo data to clear", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            withContext(Dispatchers.IO) {
                                DebugSeeder.clear(activity)
                            }
                            Toast.makeText(activity, "Demo data cleared", Toast.LENGTH_SHORT).show()
                            activity.loadTrips()
                        }
                    }
                    activity.findViewById<Button>(R.id.configureAudioFailureButton)?.setOnClickListener {
                        val points = arrayOf(
                            "Clear injection",
                            "Fail encoder initialization",
                            "Fail archival frame processing",
                            "Fail segment finalization"
                        )
                        AlertDialog.Builder(activity)
                            .setTitle("Inject on next trip")
                            .setItems(points) { _, which ->
                                val point = when (which) {
                                    1 -> AudioFailureInjectionPoint.ENCODER_INITIALIZATION
                                    2 -> AudioFailureInjectionPoint.FRAME_PROCESSING
                                    3 -> AudioFailureInjectionPoint.SEGMENT_FINALIZATION
                                    else -> AudioFailureInjectionPoint.NONE
                                }
                                AudioFailureInjectionConfig.setNext(activity, point)
                                val message = if (point == AudioFailureInjectionPoint.NONE) {
                                    "Audio failure injection cleared"
                                } else {
                                    "Next trip will fail at ${point.name.lowercase().replace('_', ' ')}"
                                }
                                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
