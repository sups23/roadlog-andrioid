package com.example.roadlog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class TripAdapter(
    private val onTripClick: (Trip) -> Unit
) : RecyclerView.Adapter<TripAdapter.TripViewHolder>() {

    private var trips: List<Trip> = emptyList()

    fun setTrips(newTrips: List<Trip>) {
        trips = newTrips
        notifyDataSetChanged()
    }

    fun getTripAt(position: Int): Trip = trips[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TripViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trip, parent, false)
        return TripViewHolder(view)
    }

    override fun onBindViewHolder(holder: TripViewHolder, position: Int) {
        holder.bind(trips[position])
    }

    override fun getItemCount(): Int = trips.size

    inner class TripViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dateText: TextView = itemView.findViewById(R.id.tripDateText)
        private val durationText: TextView = itemView.findViewById(R.id.tripDurationText)
        private val distanceText: TextView = itemView.findViewById(R.id.tripDistanceText)
        private val eventsText: TextView = itemView.findViewById(R.id.tripEventsText)
        private val breakdownText: TextView = itemView.findViewById(R.id.tripBreakdownText)

        private val dateFormatter = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

        fun bind(trip: Trip) {
            itemView.setOnClickListener { onTripClick(trip) }

            dateText.text = dateFormatter.format(Date(trip.startTimeMs))
            durationText.text = formatDuration(trip.startTimeMs, trip.endTimeMs)
            distanceText.text = formatDistance(trip.distanceMeters)
            eventsText.text = "${trip.eventCount} events"
            breakdownText.text = formatBreakdown(trip.causeBreakdown)
        }

        private fun formatDuration(startMs: Long, endMs: Long): String {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(endMs - startMs)
            val hours = minutes / 60
            val remainingMinutes = minutes % 60
            return if (hours > 0) {
                "${hours}h ${remainingMinutes}m"
            } else {
                "${minutes} min"
            }
        }

        private fun formatDistance(meters: Double): String {
            val km = meters / 1000.0
            return if (km >= 1.0) String.format("%.1f km", km) else String.format("%.2f km", km)
        }

        private fun formatBreakdown(breakdownJson: String): String {
            return try {
                val json = JSONObject(breakdownJson)
                val keys = json.keys().asSequence().toList()
                if (keys.isEmpty()) return "No causes recorded"
                keys.joinToString(" · ") { key ->
                    "$key×${json.getInt(key)}"
                }
            } catch (e: Exception) {
                "No causes recorded"
            }
        }
    }
}
