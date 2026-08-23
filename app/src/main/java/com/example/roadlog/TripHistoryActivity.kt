package com.example.roadlog

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import java.io.File

class TripHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var exportAllButton: Button
    private lateinit var reviewIncompleteButton: Button
    private lateinit var adapter: TripAdapter
    private lateinit var database: AppDatabase

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val exportRequestCode = 4101
    private var exportIncludeIncomplete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.tripsRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        exportAllButton = findViewById(R.id.exportAllButton)
        reviewIncompleteButton = findViewById(R.id.reviewIncompleteButton)
        database = AppDatabase.getDatabase(this)

        exportAllButton.setOnClickListener {
            exportIncludeIncomplete = false
            openExportDocument()
        }
        reviewIncompleteButton.setOnClickListener {
            scope.launch {
                val incomplete = withContext(Dispatchers.IO) { database.tripDao().getIncompleteTrips() }
                if (incomplete.isEmpty()) {
                    Toast.makeText(this@TripHistoryActivity, "No interrupted trips", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val labels = incomplete.map { trip ->
                    "Trip ${trip.id} · ${trip.direction ?: "direction?"} · ${trip.observationPeriod ?: "period?"} · ${trip.interruptionReason ?: "incomplete"}"
                }.toTypedArray()
                AlertDialog.Builder(this@TripHistoryActivity)
                    .setTitle("Interrupted trips preserved")
                    .setItems(labels) { _, which -> openTrip(incomplete[which]) }
                    .setPositiveButton("Export including them") { _, _ ->
                        exportIncludeIncomplete = true
                        openExportDocument()
                    }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }

        adapter = TripAdapter { trip ->
            openTrip(trip)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        attachSwipeToDelete()
    }

    private fun openExportDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "roadlog-research-export.zip")
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            exportRequestCode
        )
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
    }

    @Deprecated("Use Activity Result APIs when this screen is modernized")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != exportRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        exportAllButton.isEnabled = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ResearchExporter.exportToUri(
                        context = this@TripHistoryActivity,
                        destination = uri,
                        includeIncomplete = exportIncludeIncomplete
                    )
                }
                Toast.makeText(
                    this@TripHistoryActivity,
                    "Exported ${result.tripCount} trips, ${result.eventCount} events, ${result.audioCount} audio segments",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e("RoadLog", "Research export failed", e)
                Toast.makeText(this@TripHistoryActivity, "Export failed; collected data was unchanged", Toast.LENGTH_LONG).show()
            } finally {
                exportAllButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    internal fun loadTrips() {
        scope.launch {
            val trips = withContext(Dispatchers.IO) {
                database.tripDao().getAllTrips()
            }
            adapter.setTrips(trips)
            emptyText.visibility = if (trips.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun attachSwipeToDelete() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val trip = adapter.getTripAt(position)

                if (trip.qaStatus != TripQaStatus.UNREVIEWED) {
                    Toast.makeText(
                        this@TripHistoryActivity,
                        "Reviewed research trips are retained; export before any controlled deletion.",
                        Toast.LENGTH_LONG
                    ).show()
                    adapter.notifyItemChanged(position)
                    return
                }

                AlertDialog.Builder(this@TripHistoryActivity)
                    .setTitle("Delete trip?")
                    .setMessage("This will remove the trip record, photos, and all its stored data.")
                    .setPositiveButton("Delete") { _, _ ->
                        deleteTrip(trip)
                    }
                    .setNegativeButton("Cancel") { _, _ ->
                        adapter.notifyItemChanged(position)
                    }
                    .setOnCancelListener {
                        adapter.notifyItemChanged(position)
                    }
                    .show()
            }
        }

        ItemTouchHelper(swipeCallback).attachToRecyclerView(recyclerView)
    }

    private fun deleteTrip(trip: Trip) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val photos = database.tripDao().getPhotosForTrip(trip.id)
                for (photo in photos) {
                    try {
                        File(photo.filePath).delete()
                    } catch (e: Exception) {
                        Log.e("RoadLog", "Failed to delete photo ${photo.filePath}", e)
                    }
                }
                database.tripDao().deleteTripCascade(trip.id)
            }
            loadTrips()
        }
    }

    private fun openTrip(trip: Trip) {
        startActivity(Intent(this, TripDetailActivity::class.java).apply {
            putExtra(EXTRA_TRIP_ID, trip.id)
            putExtra(EXTRA_TRIP_START, trip.startTimeMs)
            putExtra(EXTRA_TRIP_END, trip.endTimeMs)
        })
    }

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_TRIP_START = "trip_start"
        const val EXTRA_TRIP_END = "trip_end"
    }
}
