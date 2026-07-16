package com.example.roadlog

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class RouteMapDialogFragment : DialogFragment() {

    companion object {
        private var pendingGpsData: List<TripData>? = null

        fun show(activity: AppCompatActivity, gpsData: List<TripData>) {
            pendingGpsData = gpsData
            RouteMapDialogFragment().show(activity.supportFragmentManager, "route_map")
        }
    }

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("osmdroid", Context.MODE_PRIVATE))

        mapView = MapView(requireContext()).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setTilesScaledToDpi(true)
            setUseDataConnection(true)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(19.0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val closeButton = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            setOnClickListener { dismiss() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                topMargin = 48
                rightMargin = 16
            }
            val tint = ContextCompat.getColor(requireContext(), android.R.color.white)
            setColorFilter(tint)
        }

        val frame = FrameLayout(requireContext()).apply {
            addView(mapView)
            addView(closeButton)
        }
        return frame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val gpsData = pendingGpsData ?: emptyList()
        pendingGpsData = null
        bindRoute(gpsData)
    }

    private fun bindRoute(gpsData: List<TripData>) {
        mapView.overlays.clear()
        if (gpsData.isEmpty()) return

        val points = gpsData.map { GeoPoint(it.latitude ?: 0.0, it.longitude ?: 0.0) }

        val route = Polyline().apply {
            outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.teal_700)
            outlinePaint.strokeWidth = 8f
            setPoints(points)
        }
        mapView.overlays.add(route)

        val startMarker = Marker(mapView).apply {
            position = points.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
            icon = getMarkerDrawable(android.R.color.holo_green_dark)
        }
        val endMarker = Marker(mapView).apply {
            position = points.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "End"
            icon = getMarkerDrawable(android.R.color.holo_red_dark)
        }
        mapView.overlays.add(startMarker)
        mapView.overlays.add(endMarker)

        if (points.size > 1) {
            val boundingBox = BoundingBox.fromGeoPoints(points)
            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, false, 64)
            }
        } else if (points.size == 1) {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(points.first())
        }

        mapView.invalidate()
    }

    private fun getMarkerDrawable(colorRes: Int): Drawable? {
        return try {
            val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_marker_circle)
            drawable?.setTint(ContextCompat.getColor(requireContext(), colorRes))
            drawable
        } catch (e: Exception) {
            null
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingGpsData = null
    }
}
