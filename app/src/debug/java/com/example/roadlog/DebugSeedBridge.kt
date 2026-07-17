package com.example.roadlog

import android.view.View
import android.widget.Button
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DebugSeedBridge {

    fun setup(activity: TripHistoryActivity, scope: CoroutineScope, onComplete: () -> Unit) {
        val debugButtons = activity.findViewById<View>(R.id.debugButtons)
        debugButtons.visibility = View.VISIBLE
        activity.findViewById<Button>(R.id.seedDemoButton).setOnClickListener {
            scope.launch {
                val seeded = withContext(Dispatchers.IO) {
                    DebugSeeder.seedIfNeeded(activity)
                }
                if (seeded) {
                    Toast.makeText(activity, "Seeded demo trips", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Demo data already present", Toast.LENGTH_SHORT).show()
                }
                onComplete()
            }
        }
        activity.findViewById<Button>(R.id.clearDemoButton).setOnClickListener {
            scope.launch {
                if (!DebugSeeder.isSeeded(activity)) {
                    Toast.makeText(activity, "No demo data to clear", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    DebugSeeder.clear(activity)
                }
                Toast.makeText(activity, "Demo data cleared", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }
}
