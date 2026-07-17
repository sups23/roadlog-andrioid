package com.example.roadlog

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
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
    private lateinit var adapter: TripAdapter
    private lateinit var database: AppDatabase

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trip_history)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        recyclerView = findViewById(R.id.tripsRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        database = AppDatabase.getDatabase(this)

        adapter = TripAdapter { trip ->
            val intent = Intent(this, TripDetailActivity::class.java).apply {
                putExtra(EXTRA_TRIP_ID, trip.id)
                putExtra(EXTRA_TRIP_START, trip.startTimeMs)
                putExtra(EXTRA_TRIP_END, trip.endTimeMs)
            }
            startActivity(intent)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        attachSwipeToDelete()
    }

    override fun onResume() {
        super.onResume()
        loadTrips()
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

    companion object {
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_TRIP_START = "trip_start"
        const val EXTRA_TRIP_END = "trip_end"
    }
}
