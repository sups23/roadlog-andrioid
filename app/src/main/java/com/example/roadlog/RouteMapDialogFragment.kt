package com.example.roadlog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import kotlin.math.sqrt
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
        private var pendingWorldAccel: List<WorldAccelSample>? = null
        private var pendingWorldGyro: List<WorldGyroSample>? = null

        fun show(activity: AppCompatActivity, gpsData: List<TripData>, worldAccel: List<WorldAccelSample>, worldGyro: List<WorldGyroSample>) {
            pendingGpsData = gpsData
            pendingWorldAccel = worldAccel
            pendingWorldGyro = worldGyro
            RouteMapDialogFragment().show(activity.supportFragmentManager, "route_map")
        }
    }

    private lateinit var mapView: MapView
    private var activeParam = ""
    private var geoPoints: List<GeoPoint> = emptyList()
    private var gpsData: List<TripData> = emptyList()
    private var worldAccel: List<WorldAccelSample> = emptyList()
    private var worldGyro: List<WorldGyroSample> = emptyList()

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

        val paramBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(8, 8, 8, 8)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                topMargin = 48
                leftMargin = 8
            }
        }

        val paramLabels = listOf("Spd", "Rgh", "Lat", "Lng", "Yaw")
        val paramKeys = listOf("speed", "roughness", "lateral", "longitudinal", "yaw")
        val row1 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val row2 = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (i in paramLabels.indices) {
            val btn = Button(requireContext()).apply {
                text = paramLabels[i]
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding(8, 4, 8, 4)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 4; bottomMargin = 4 }
                setOnClickListener { selectParameter(paramKeys[i]) }
            }
            if (i < 3) row1.addView(btn) else row2.addView(btn)
        }

        val noneBtn = Button(requireContext()).apply {
            text = "None"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(8, 4, 8, 4)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { selectParameter("") }
        }
        row2.addView(noneBtn)

        paramBar.addView(row1)
        paramBar.addView(row2)

        val bottomBar = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            setPadding(16, 16, 16, 32)
        }

        val backButton = Button(requireContext()).apply {
            text = "Back"
            setOnClickListener { dismiss() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bottomBar.addView(backButton)

        val frame = FrameLayout(requireContext()).apply {
            addView(mapView)
            addView(paramBar)
            addView(closeButton)
            addView(bottomBar)
        }
        return frame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gpsData = pendingGpsData ?: emptyList()
        worldAccel = pendingWorldAccel ?: emptyList()
        worldGyro = pendingWorldGyro ?: emptyList()
        pendingGpsData = null
        pendingWorldAccel = null
        pendingWorldGyro = null
        geoPoints = gpsData.map { GeoPoint(it.latitude ?: 0.0, it.longitude ?: 0.0) }
        selectParameter("")
    }

    private fun selectParameter(param: String) {
        activeParam = param
        mapView.overlays.clear()
        if (gpsData.isEmpty()) return

        if (activeParam.isEmpty()) {
            val route = Polyline().apply {
                outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.teal_700)
                outlinePaint.strokeWidth = 8f
                setPoints(geoPoints)
            }
            mapView.overlays.add(route)
        } else {
            val segmentValues = computeSegmentValues(activeParam)
            val maxVal = segmentValues.maxOrNull() ?: 0.0
            val minVal = 0.0

            var groupColor = paramColor(segmentValues.firstOrNull() ?: 0.0, minVal, maxVal)
            var groupStart = 0
            for (i in 1 until segmentValues.size) {
                val color = paramColor(segmentValues[i], minVal, maxVal)
                if (color != groupColor) {
                    val segment = Polyline().apply {
                        outlinePaint.color = groupColor
                        outlinePaint.strokeWidth = 8f
                        setPoints(geoPoints.subList(groupStart, i + 1))
                    }
                    mapView.overlays.add(segment)
                    groupStart = i
                    groupColor = color
                }
            }
            val finalSeg = Polyline().apply {
                outlinePaint.color = groupColor
                outlinePaint.strokeWidth = 8f
                setPoints(geoPoints.subList(groupStart, geoPoints.size))
            }
            mapView.overlays.add(finalSeg)
        }

        addMarkers()
        zoomToRoute()
        mapView.invalidate()
    }

    private fun computeSegmentValues(param: String): List<Double> {
        val values = MutableList(gpsData.size - 1) { 0.0 }
        if (gpsData.size < 2) return values

        val sums = DoubleArray(gpsData.size - 1)
        val counts = IntArray(gpsData.size - 1)
        var segIdx = 0

        when (param) {
            "speed" -> {
                for (i in values.indices) {
                    values[i] = (gpsData[i].speedKmh ?: 0f).toDouble()
                }
                return values
            }
            "roughness", "lateral", "longitudinal" -> {
                for (sample in worldAccel) {
                    while (segIdx < gpsData.size - 2 && sample.timestamp > gpsData[segIdx + 1].timestamp) segIdx++
                    if (sample.timestamp in gpsData[segIdx].timestamp..gpsData[segIdx + 1].timestamp) {
                        val v = when (param) {
                            "roughness" -> sample.vertical.toDouble()
                            "lateral" -> sample.lateral.toDouble()
                            else -> sample.longitudinal.toDouble()
                        }
                        sums[segIdx] += v * v
                        counts[segIdx]++
                    }
                }
            }
            "yaw" -> {
                for (sample in worldGyro) {
                    while (segIdx < gpsData.size - 2 && sample.timestamp > gpsData[segIdx + 1].timestamp) segIdx++
                    if (sample.timestamp in gpsData[segIdx].timestamp..gpsData[segIdx + 1].timestamp) {
                        val y = sample.yaw.toDouble()
                        sums[segIdx] += y * y
                        counts[segIdx]++
                    }
                }
            }
        }

        for (i in values.indices) {
            values[i] = if (counts[i] > 0) sqrt(sums[i] / counts[i]) else 0.0
        }
        return values
    }

    private fun paramColor(value: Double, min: Double, max: Double): Int {
        if (max <= min) return ContextCompat.getColor(requireContext(), R.color.green)
        val fraction = ((value - min) / (max - min)).toFloat().coerceIn(0f, 1f)
        val green = ContextCompat.getColor(requireContext(), R.color.green)
        val red = ContextCompat.getColor(requireContext(), R.color.red)
        return android.animation.ArgbEvaluator().evaluate(fraction, green, red) as Int
    }

    private fun addMarkers() {
        val startMarker = Marker(mapView).apply {
            position = geoPoints.first()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Start"
            icon = getMarkerDrawable(android.R.color.holo_green_dark)
        }
        val endMarker = Marker(mapView).apply {
            position = geoPoints.last()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "End"
            icon = getMarkerDrawable(android.R.color.holo_red_dark)
        }
        mapView.overlays.add(startMarker)
        mapView.overlays.add(endMarker)
    }

    private fun zoomToRoute() {
        if (geoPoints.size > 1) {
            val boundingBox = BoundingBox.fromGeoPoints(geoPoints)
            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, false, 64)
            }
        } else if (geoPoints.size == 1) {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(geoPoints.first())
        }
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
        pendingWorldAccel = null
        pendingWorldGyro = null
    }
}
