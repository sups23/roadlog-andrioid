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

class TripHistoryActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var exportRestrictedButton: Button
    private lateinit var exportPublicButton: Button
    private lateinit var reviewIncompleteButton: Button
    private lateinit var adapter: TripAdapter
    private lateinit var database: AppDatabase

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val exportRequestCode = 4101
    private var exportIncludeIncomplete = false
    private var exportMode = ResearchExportMode.RESTRICTED_RAW

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.tripsRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        exportRestrictedButton = findViewById(R.id.exportRestrictedButton)
        exportPublicButton = findViewById(R.id.exportPublicButton)
        reviewIncompleteButton = findViewById(R.id.reviewIncompleteButton)
        database = AppDatabase.getDatabase(this)

        exportRestrictedButton.setOnClickListener { beginExport(ResearchExportMode.RESTRICTED_RAW, false) }
        exportPublicButton.setOnClickListener { beginExport(ResearchExportMode.PUBLIC_DEIDENTIFIED, false) }
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
                        chooseExportMode(includeIncomplete = true)
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

    private fun beginExport(mode: ResearchExportMode, includeIncomplete: Boolean) {
        exportMode = mode
        exportIncludeIncomplete = includeIncomplete
        openExportDocument()
    }

    private fun chooseExportMode(includeIncomplete: Boolean) {
        val labels = arrayOf(
            "${ResearchExportMode.RESTRICTED_RAW.archiveLabel}: raw GPS, audio, device metadata",
            "${ResearchExportMode.PUBLIC_DEIDENTIFIED.archiveLabel}: de-identified, no raw GPS/audio"
        )
        AlertDialog.Builder(this)
            .setTitle("Choose export mode")
            .setItems(labels) { _, which ->
                beginExport(
                    if (which == 0) ResearchExportMode.RESTRICTED_RAW else ResearchExportMode.PUBLIC_DEIDENTIFIED,
                    includeIncomplete
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openExportDocument() {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, exportMode.defaultFileName)
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
        exportRestrictedButton.isEnabled = false
        exportPublicButton.isEnabled = false
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    ResearchExporter.exportToUri(
                        context = this@TripHistoryActivity,
                        destination = uri,
                        includeIncomplete = exportIncludeIncomplete,
                        mode = exportMode
                    )
                }
                Toast.makeText(
                    this@TripHistoryActivity,
                    "${result.mode.archiveLabel} exported: ${result.tripCount} trips, ${result.eventCount} events",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e("RoadLog", "Research export failed", e)
                Toast.makeText(this@TripHistoryActivity, "Export failed; collected data was unchanged", Toast.LENGTH_LONG).show()
            } finally {
                exportRestrictedButton.isEnabled = true
                exportPublicButton.isEnabled = true
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
                    .setMessage("This removes the trip record, audio, legacy photos, and all its stored data.")
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
            val report = withContext(Dispatchers.IO) {
                database.tripDao().deleteTripWithMediaFiles(trip.id)
            }
            if (!report.isComplete || report.missingFiles > 0) {
                Toast.makeText(
                    this@TripHistoryActivity,
                    "Trip deleted; media cleanup: ${report.deletedFiles} removed, ${report.missingFiles} missing, ${report.failures.size} failed",
                    Toast.LENGTH_LONG
                ).show()
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
