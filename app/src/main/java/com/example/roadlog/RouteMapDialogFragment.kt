package com.example.roadlog

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.sqrt
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class RouteMapDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "RoadLog"
        private var pendingWorldAccel: List<WorldAccelSample>? = null
        private var pendingWorldGyro: List<WorldGyroSample>? = null
        private const val ARG_TRIP_ID = "trip_id"
        private const val ARG_TRIP_START_MS = "trip_start_ms"
        private const val PLAYBACK_UPDATE_MS = 250L
        private const val EVENT_HIGHLIGHT_DURATION_MS = 3_000L
        private const val EVENT_SEEK_MATCH_WINDOW_MS = 1_500L

        fun show(
            activity: AppCompatActivity,
            tripId: Long,
            tripStartMs: Long,
            worldAccel: List<WorldAccelSample>,
            worldGyro: List<WorldGyroSample>
        ) {
            pendingWorldAccel = worldAccel
            pendingWorldGyro = worldGyro
            RouteMapDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_TRIP_ID, tripId)
                    putLong(ARG_TRIP_START_MS, tripStartMs)
                }
            }.show(activity.supportFragmentManager, "route_map")
        }
    }

    private lateinit var mapView: MapView
    private var activeParam = ""
    private var geoPoints: List<GeoPoint> = emptyList()
    private var gpsData: List<TripData> = emptyList()
    private var events: List<TripEvent> = emptyList()
    private var trip: Trip? = null
    private var latestAnnotationsByEvent: Map<String, EventAnnotationRevision> = emptyMap()
    private var worldAccel: List<WorldAccelSample> = emptyList()
    private var worldGyro: List<WorldGyroSample> = emptyList()
    private var tripStartMs = 0L
    private var eventsVisible = false
    private var audioSegments: List<TripAudio> = emptyList()
    private var audioPlayer: MediaPlayer? = null
    private var audioPrepared = false
    private var audioCompleted = false
    private var playingAudioIndex = -1
    private var playAllAudio = false
    private var audioPlaylistCompleted = false
    private var pendingAudioPositionMs = 0L
    private var followPlayback = true
    private var playbackMarker: Marker? = null
    private val eventMarkers = linkedMapOf<String, Marker>()
    private var highlightedEventId: String? = null
    private var highlightedEventUntilMs: Long? = null
    private var lastPlaybackTripTimeMs: Long? = null

    private lateinit var playbackStatusText: TextView
    private lateinit var playbackEventText: TextView
    private lateinit var playbackElapsedText: TextView
    private lateinit var playbackDurationText: TextView
    private lateinit var playbackProgressSeekBar: SeekBar
    private lateinit var playbackPreviousButton: Button
    private lateinit var playbackPlayPauseButton: Button
    private lateinit var playbackNextButton: Button
    private lateinit var playbackPlayAllButton: Button
    private lateinit var playbackFollowButton: Button

    private val playbackProgressHandler = Handler(Looper.getMainLooper())
    private val playbackProgressRunnable = object : Runnable {
        override fun run() {
            updatePlaybackPosition()
            if (isAudioPlaying()) {
                playbackProgressHandler.postDelayed(this, PLAYBACK_UPDATE_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        MapTileConfiguration.initialize(requireContext())

        mapView = MapView(requireContext()).apply {
            setTileSource(MapTileConfiguration.tileSource)
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

        val eventsButton = Button(requireContext()).apply {
            text = "Events"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(8, 4, 8, 4)
            alpha = 0.65f
            contentDescription = "Show event markers"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = 4; bottomMargin = 4 }
            setOnClickListener {
                eventsVisible = !eventsVisible
                isSelected = eventsVisible
                alpha = if (eventsVisible) 1f else 0.65f
                contentDescription = if (eventsVisible) {
                    "Hide event markers"
                } else {
                    "Show event markers"
                }
                redrawMap(zoomToContent = false)
            }
        }
        row2.addView(eventsButton)

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

        playbackStatusText = TextView(requireContext()).apply {
            text = "Loading recordings..."
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        playbackEventText = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        playbackElapsedText = TextView(requireContext()).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        playbackDurationText = TextView(requireContext()).apply {
            text = "00:00"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(52, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        playbackProgressSeekBar = SeekBar(requireContext()).apply {
            max = 1
            progress = 0
            isEnabled = false
            contentDescription = "Audio playback progress"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        playbackProgressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                playbackElapsedText.text = formatDuration(progress.toLong())
                previewPlaybackPosition(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekPlaybackTo(seekBar?.progress?.toLong() ?: 0L)
            }
        })

        fun playbackButton(label: String): Button = Button(requireContext()).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 2)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        playbackPreviousButton = playbackButton("Prev").apply {
            setOnClickListener { playPreviousAudioSegment() }
        }
        playbackPlayPauseButton = playbackButton("Play").apply {
            setOnClickListener { toggleAudioPlayback() }
        }
        playbackNextButton = playbackButton("Next").apply {
            setOnClickListener { playNextAudioSegment() }
        }
        playbackFollowButton = playbackButton("Follow").apply {
            isSelected = true
            setOnClickListener {
                followPlayback = !followPlayback
                isSelected = followPlayback
                alpha = if (followPlayback) 1f else 0.65f
                if (followPlayback) currentPlaybackTripTimeMs()?.let(::updatePlaybackMapPosition)
            }
        }
        playbackPlayAllButton = Button(requireContext()).apply {
            text = "Play all segments"
            textSize = 11f
            setTextColor(Color.WHITE)
            setOnClickListener { togglePlayAllAudio() }
        }

        val progressRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playbackElapsedText)
            addView(playbackProgressSeekBar)
            addView(playbackDurationText)
        }
        val playbackControlsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(playbackPreviousButton)
            addView(playbackPlayPauseButton)
            addView(playbackNextButton)
            addView(playbackFollowButton)
        }

        val playbackPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(playbackStatusText)
            addView(playbackEventText)
            addView(progressRow)
            addView(playbackControlsRow)
            addView(playbackPlayAllButton)
        }

        val bottomBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
            }
            setPadding(16, 16, 16, 32)
            addView(playbackPanel)
        }

        val attribution = TextView(requireContext()).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            setPadding(12, 8, 12, 8)
            textSize = 11f
            setTextColor(Color.WHITE)
        }
        MapTileConfiguration.configureAttributionView(attribution)
        attribution.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            bottomMargin = 88
            leftMargin = 8
        }

        val backButton = Button(requireContext()).apply {
            text = "Back"
            setOnClickListener { dismiss() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bottomBar.addView(backButton)

        val frame = FrameLayout(requireContext()).apply {
            addView(mapView)
            addView(paramBar)
            addView(closeButton)
            addView(bottomBar)
            addView(attribution)
        }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val params = attribution.layoutParams as FrameLayout.LayoutParams
            val bottomMargin = bottomBar.height + 8
            if (params.bottomMargin != bottomMargin) {
                params.bottomMargin = bottomMargin
                attribution.layoutParams = params
            }
        }
        return frame
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        worldAccel = pendingWorldAccel ?: emptyList()
        worldGyro = pendingWorldGyro ?: emptyList()
        pendingWorldAccel = null
        pendingWorldGyro = null
        val tripId = requireArguments().getLong(ARG_TRIP_ID)
        tripStartMs = requireArguments().getLong(ARG_TRIP_START_MS)
        lifecycleScope.launch {
            val dao = AppDatabase.getDatabase(requireContext()).tripDao()
            gpsData = withContext(Dispatchers.IO) {
                dao.getGpsForMap(tripId)
            }
            events = withContext(Dispatchers.IO) {
                dao.getTripEvents(tripId)
            }
            trip = withContext(Dispatchers.IO) { dao.getTripById(tripId) }
            latestAnnotationsByEvent = withContext(Dispatchers.IO) {
                dao.getAnnotationsForTrip(tripId).groupBy { it.eventId }
                    .mapNotNull { (eventId, revisions) ->
                        revisions.maxByOrNull { it.annotationVersion }?.let { eventId to it }
                    }.toMap()
            }
            audioSegments = withContext(Dispatchers.IO) {
                dao.getAudioForTrip(tripId)
            }
            geoPoints = gpsData.map { GeoPoint(it.latitude!!, it.longitude!!) }
            if (geoPoints.isEmpty()) {
                Log.w(TAG, "No valid GPS points for trip $tripId")
                TextView(requireContext()).apply {
                    text = "No GPS route was recorded for this trip"
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(190, 0, 0, 0))
                    setPadding(32, 20, 32, 20)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.CENTER }
                }.also { (view as ViewGroup).addView(it) }
            }
            bindAudioSegments()
            selectParameter("")
        }
    }

    private fun bindAudioSegments() {
        val hasPlayableAudio = audioSegments.indices.any(::isAudioFileAvailable)
        playbackStatusText.text = when {
            audioSegments.isEmpty() -> "No audio recordings saved"
            hasPlayableAudio -> "Select Play to listen and follow the route"
            else -> "No playable audio recordings"
        }
        playbackElapsedText.text = formatDuration(0L)
        playbackDurationText.text = formatDuration(0L)
        playbackProgressSeekBar.max = 1
        playbackProgressSeekBar.progress = 0
        updatePlaybackControls()
    }

    private fun playAudioSegment(index: Int, startPositionMs: Long = 0L) {
        if (index !in audioSegments.indices || !isAudioFileAvailable(index)) return

        if (playingAudioIndex == index && audioPlayer != null && audioPrepared) {
            seekPlaybackTo(startPositionMs)
            if (!isAudioPlaying()) startAudioPlayback()
            return
        }

        val continuePlaylist = playAllAudio
        releaseMapAudioPlayer()
        playAllAudio = continuePlaylist
        audioPlaylistCompleted = false
        playingAudioIndex = index
        pendingAudioPositionMs = startPositionMs.coerceAtLeast(0L)
        lastPlaybackTripTimeMs = null
        updatePlaybackControls()
        updatePlaybackMapPosition(
            AudioPlaybackTimeline.tripTimeMs(audioSegments[index], pendingAudioPositionMs),
            recenter = false,
            detectEvent = false
        )

        val player = MediaPlayer()
        audioPlayer = player
        try {
            player.setDataSource(File(audioSegments[index].filePath).absolutePath)
            player.setOnPreparedListener { preparedPlayer ->
                if (audioPlayer !== preparedPlayer || playingAudioIndex != index) {
                    preparedPlayer.release()
                    return@setOnPreparedListener
                }
                audioPrepared = true
                val durationMs = safePlayerDuration(preparedPlayer).toLong()
                val maxPositionMs = (durationMs - 1L).coerceAtLeast(0L)
                val targetPositionMs = pendingAudioPositionMs.coerceIn(0L, maxPositionMs)
                if (durationMs > 0L) preparedPlayer.seekTo(targetPositionMs.toInt())
                audioCompleted = false
                updatePlaybackPosition()
                updatePlaybackControls()
                startAudioPlayback()
            }
            player.setOnCompletionListener {
                playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
                audioCompleted = true
                updatePlaybackPosition()
                if (playAllAudio) {
                    val nextIndex = findNextPlayableAudioIndex(index + 1)
                    if (nextIndex != null) {
                        playAudioSegment(nextIndex)
                    } else {
                        playAllAudio = false
                        audioPlaylistCompleted = true
                        updatePlaybackControls()
                    }
                } else {
                    updatePlaybackControls()
                }
            }
            player.setOnErrorListener { _, _, _ ->
                val continueAfterError = playAllAudio
                val nextIndex = findNextPlayableAudioIndex(index + 1)
                playAllAudio = false
                releaseMapAudioPlayer()
                playbackStatusText.text = "Could not play segment ${index + 1}"
                if (continueAfterError && nextIndex != null) {
                    playAllAudio = true
                    playAudioSegment(nextIndex)
                } else {
                    updatePlaybackControls()
                }
                true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start map audio playback", error)
            if (audioPlayer === player) releaseMapAudioPlayer()
            playbackStatusText.text = "Could not play segment ${index + 1}"
            updatePlaybackControls()
        }
    }

    private fun toggleAudioPlayback() {
        if (audioPlayer == null) {
            val index = if (playingAudioIndex in audioSegments.indices) {
                playingAudioIndex.takeIf(::isAudioFileAvailable)
            } else {
                findNextPlayableAudioIndex(0)
            }
            if (index != null) playAudioSegment(index)
            return
        }
        if (!audioPrepared) return
        if (isAudioPlaying()) {
            try {
                audioPlayer?.pause()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Could not pause map audio", error)
            }
            playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        } else {
            startAudioPlayback()
        }
        updatePlaybackControls()
    }

    private fun startAudioPlayback() {
        val player = audioPlayer ?: return
        if (!audioPrepared || !isResumed) return
        try {
            if (audioCompleted) {
                player.seekTo(0)
                audioCompleted = false
                lastPlaybackTripTimeMs = null
            }
            player.start()
            schedulePlaybackProgress()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not start map audio", error)
        }
        updatePlaybackControls()
    }

    private fun playPreviousAudioSegment() {
        if (playingAudioIndex !in audioSegments.indices) return
        if (currentAudioPosition() > 3_000L) {
            seekPlaybackTo(0L)
            return
        }
        findPreviousPlayableAudioIndex(playingAudioIndex - 1)?.let { playAudioSegment(it) }
    }

    private fun playNextAudioSegment() {
        val nextIndex = findNextPlayableAudioIndex(
            if (playingAudioIndex >= 0) playingAudioIndex + 1 else 0
        )
        if (nextIndex == null) {
            playAllAudio = false
            updatePlaybackControls()
        } else {
            playAudioSegment(nextIndex)
        }
    }

    private fun togglePlayAllAudio() {
        val firstPlayableIndex = findNextPlayableAudioIndex(0)
        if (firstPlayableIndex == null) return
        if (playAllAudio) {
            playAllAudio = false
            releaseMapAudioPlayer()
            return
        }

        playAllAudio = true
        val index = if (!audioPlaylistCompleted && playingAudioIndex in audioSegments.indices) {
            playingAudioIndex.takeIf(::isAudioFileAvailable)
        } else {
            firstPlayableIndex
        }
        audioPlaylistCompleted = false
        if (index == null) {
            playAllAudio = false
            updatePlaybackControls()
        } else if (audioPlayer != null && playingAudioIndex == index && audioPrepared) {
            startAudioPlayback()
        } else {
            playAudioSegment(index)
        }
    }

    private fun seekPlaybackTo(positionMs: Long) {
        val player = audioPlayer ?: return
        if (!audioPrepared) return
        try {
            val durationMs = safePlayerDuration(player).toLong()
            val targetPositionMs = positionMs.coerceIn(0L, durationMs)
            player.seekTo(targetPositionMs.toInt())
            audioCompleted = durationMs > 0L && targetPositionMs >= durationMs
            lastPlaybackTripTimeMs = null
            updatePlaybackPosition()
            updatePlaybackControls()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Could not seek map audio", error)
        }
    }

    private fun previewPlaybackPosition(positionMs: Long) {
        val segment = audioSegments.getOrNull(playingAudioIndex) ?: return
        updatePlaybackMapPosition(
            AudioPlaybackTimeline.tripTimeMs(segment, positionMs),
            recenter = followPlayback,
            detectEvent = false
        )
    }

    private fun findNextPlayableAudioIndex(startIndex: Int): Int? {
        return (startIndex.coerceAtLeast(0) until audioSegments.size)
            .firstOrNull(::isAudioFileAvailable)
    }

    private fun findPreviousPlayableAudioIndex(startIndex: Int): Int? {
        if (audioSegments.isEmpty()) return null
        return (startIndex.coerceAtMost(audioSegments.lastIndex) downTo 0)
            .firstOrNull(::isAudioFileAvailable)
    }

    private fun isAudioFileAvailable(index: Int): Boolean {
        return audioSegments.getOrNull(index)?.let(::isAudioFileAvailable) == true
    }

    private fun isAudioFileAvailable(segment: TripAudio): Boolean {
        val file = File(segment.filePath)
        return file.isFile && file.length() > 0L
    }

    private fun schedulePlaybackProgress() {
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        playbackProgressHandler.post(playbackProgressRunnable)
    }

    private fun updatePlaybackPosition() {
        val tripTimeMs = currentPlaybackTripTimeMs() ?: return
        updatePlaybackMapPosition(tripTimeMs)
        updatePlaybackControls()
    }

    private fun currentPlaybackTripTimeMs(): Long? {
        val segment = audioSegments.getOrNull(playingAudioIndex) ?: return null
        if (audioPlayer == null) return null
        return AudioPlaybackTimeline.tripTimeMs(segment, currentAudioPosition())
    }

    private fun updatePlaybackMapPosition(
        tripTimeMs: Long,
        recenter: Boolean = followPlayback,
        detectEvent: Boolean = true
    ) {
        val coordinate = PlaybackMapPositionResolver.resolve(gpsData, tripTimeMs)
        if (coordinate == null) {
            playbackMarker?.setVisible(false)
        } else {
            val point = GeoPoint(coordinate.latitude, coordinate.longitude)
            val marker = playbackMarker ?: Marker(mapView).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setTextLabelBackgroundColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark)
                )
                setTextLabelForegroundColor(Color.WHITE)
                setTextIcon("NOW")
                title = "Now playing"
            }.also {
                playbackMarker = it
                mapView.overlays.add(it)
            }
            marker.position = point
            marker.setVisible(true)
            marker.snippet = "Trip ${formatTripElapsed(tripTimeMs)}"
            if (recenter) mapView.controller.setCenter(point)
        }
        if (detectEvent) updateEventHighlight(tripTimeMs)
        mapView.invalidate()
    }

    private fun updateEventHighlight(tripTimeMs: Long) {
        val previousTimeMs = lastPlaybackTripTimeMs
        if (previousTimeMs != null && tripTimeMs < previousTimeMs) {
            clearEventHighlight()
        }

        val event = when {
            previousTimeMs != null && tripTimeMs >= previousTimeMs -> {
                events.filter { it.markerTimeMs > previousTimeMs && it.markerTimeMs <= tripTimeMs }
                    .maxWithOrNull(compareBy<TripEvent> { it.markerTimeMs }.thenBy { it.eventId })
            }
            else -> {
                events.minWithOrNull(
                    compareBy<TripEvent> {
                        PlaybackMapPositionResolver.nearestTimestampDistance(it.markerTimeMs, tripTimeMs)
                    }.thenBy { it.markerTimeMs }.thenBy { it.eventId }
                )?.takeIf {
                    PlaybackMapPositionResolver.nearestTimestampDistance(it.markerTimeMs, tripTimeMs) <=
                        EVENT_SEEK_MATCH_WINDOW_MS
                }
            }
        }
        if (event != null) {
            highlightEvent(event)
        } else if (highlightedEventId != null && highlightedEventUntilMs?.let { tripTimeMs > it } == true) {
            clearEventHighlight()
        }
        lastPlaybackTripTimeMs = tripTimeMs
    }

    private fun highlightEvent(event: TripEvent) {
        if (highlightedEventId != event.eventId) {
            highlightedEventId?.let { eventMarkers[it]?.closeInfoWindow() }
        }
        highlightedEventId = event.eventId
        highlightedEventUntilMs = event.markerTimeMs + EVENT_HIGHLIGHT_DURATION_MS
        val eventIndex = events.indexOfFirst { it.eventId == event.eventId } + 1
        val cause = displayEventCause(event)
        playbackEventText.text = "Event $eventIndex: $cause at ${formatTripElapsed(event.markerTimeMs)}"
        playbackEventText.visibility = View.VISIBLE
        refreshEventMarkerStyles()
        if (eventsVisible) eventMarkers[event.eventId]?.showInfoWindow()
    }

    private fun clearEventHighlight() {
        highlightedEventId?.let { eventMarkers[it]?.closeInfoWindow() }
        highlightedEventId = null
        highlightedEventUntilMs = null
        playbackEventText.text = ""
        playbackEventText.visibility = View.GONE
        refreshEventMarkerStyles()
    }

    private fun displayEventCause(event: TripEvent): String {
        val revision = latestAnnotationsByEvent[event.eventId]
        val code = revision?.primaryCauseCode ?: event.primaryCauseCode ?: event.provisionalCauseCode
            ?: return "UNANNOTATED"
        val sourceTrip = trip
        val configVersion = sourceTrip?.causeConfigJson?.let { raw ->
            runCatching {
                org.json.JSONObject(raw).optString("version").takeIf { it.isNotBlank() }
            }.getOrNull()
        }
        val provisionalVersion = CauseTaxonomyVersions.provisionalVersion(
            tripVersion = sourceTrip?.codebookVersion,
            configVersion = configVersion,
            eventVersion = event.codebookVersion,
            hasAnnotationHistory = revision != null
        )
        val currentVersion = CauseTaxonomyVersions.currentPrimaryVersion(
            hasAnnotation = revision != null,
            annotationVersion = revision?.codebookVersion,
            provisionalVersion = provisionalVersion
        )
        return CauseDisplay.versioned(code, currentVersion)
    }

    private fun seekToEvent(event: TripEvent) {
        highlightEvent(event)
        val index = AudioPlaybackTimeline.findSegmentIndex(
            eventTimeMs = event.markerTimeMs,
            segments = audioSegments,
            isPlayable = { segment -> isAudioFileAvailable(segment) }
        )
        if (index == null) {
            playbackEventText.text = "Event has no playable recording"
            return
        }
        val segment = audioSegments[index]
        val positionMs = AudioPlaybackTimeline.seekPositionMs(segment, event.markerTimeMs)
        playAllAudio = false
        audioPlaylistCompleted = false
        playAudioSegment(index, positionMs)
    }

    private fun updatePlaybackControls() {
        if (!::playbackStatusText.isInitialized) return
        if (audioSegments.isEmpty()) {
            playbackStatusText.text = "No audio recordings saved"
            playbackElapsedText.text = formatDuration(0L)
            playbackDurationText.text = formatDuration(0L)
            playbackProgressSeekBar.max = 1
            playbackProgressSeekBar.progress = 0
            playbackProgressSeekBar.isEnabled = false
            playbackPreviousButton.isEnabled = false
            playbackPlayPauseButton.isEnabled = false
            playbackNextButton.isEnabled = false
            playbackPlayAllButton.isEnabled = false
            return
        }

        val selected = playingAudioIndex in audioSegments.indices
        val segment = audioSegments.getOrNull(playingAudioIndex)
        val tripTimeMs = currentPlaybackTripTimeMs() ?: segment?.startTimeMs
        if (selected && segment != null && tripTimeMs != null) {
            val previousPlayableIndex = findPreviousPlayableAudioIndex(playingAudioIndex - 1)
            val gap = previousPlayableIndex?.let {
                AudioPlaybackTimeline.gapBefore(audioSegments[it], segment)
            }
            val unavailableSkipped = previousPlayableIndex != null &&
                previousPlayableIndex < playingAudioIndex - 1
            playbackStatusText.text = buildString {
                append("Segment ${playingAudioIndex + 1} of ${audioSegments.size}")
                append(" | Trip ${formatTripElapsed(tripTimeMs)}")
                gap?.let { append(" | gap before ${formatDuration(it)}") }
                if (unavailableSkipped) append(" | unavailable segment skipped")
            }
        } else {
            playbackStatusText.text = if (audioSegments.indices.any(::isAudioFileAvailable)) {
                "Select Play to listen and follow the route"
            } else {
                "No playable audio recordings"
            }
        }

        val durationMs = if (audioPrepared && audioPlayer != null) {
            safePlayerDuration(audioPlayer!!).toLong()
        } else {
            segment?.endTimeMs?.let { (it - segment.startTimeMs).coerceAtLeast(0L) } ?: 0L
        }
        val positionMs = if (audioPrepared) currentAudioPosition().toLong().coerceIn(0L, durationMs) else 0L
        playbackProgressSeekBar.max = durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
        playbackProgressSeekBar.progress = positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        playbackProgressSeekBar.isEnabled = audioPrepared
        playbackElapsedText.text = formatDuration(positionMs)
        playbackDurationText.text = formatDuration(durationMs)
        playbackPreviousButton.isEnabled = selected && (
            positionMs > 3_000L || findPreviousPlayableAudioIndex(playingAudioIndex - 1) != null
            )
        playbackPlayPauseButton.isEnabled = audioSegments.indices.any(::isAudioFileAvailable)
        playbackPlayPauseButton.text = when {
            isAudioPlaying() -> "Pause"
            audioCompleted -> "Replay"
            else -> "Play"
        }
        playbackNextButton.isEnabled = selected &&
            findNextPlayableAudioIndex(playingAudioIndex + 1) != null
        playbackPlayAllButton.isEnabled = audioSegments.indices.any(::isAudioFileAvailable)
        playbackPlayAllButton.text = if (playAllAudio) "Stop playlist" else "Play all segments"
    }

    private fun isAudioPlaying(): Boolean {
        return try {
            audioPlayer?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun currentAudioPosition(): Long {
        return try {
            audioPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: IllegalStateException) {
            0L
        }
    }

    private fun safePlayerDuration(player: MediaPlayer): Int {
        return try {
            player.duration.coerceAtLeast(0)
        } catch (_: IllegalStateException) {
            0
        }
    }

    private fun releaseMapAudioPlayer(updateControls: Boolean = true) {
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
        audioPlayer?.setOnCompletionListener(null)
        audioPlayer?.setOnPreparedListener(null)
        audioPlayer?.setOnErrorListener(null)
        audioPlayer?.release()
        audioPlayer = null
        audioPrepared = false
        audioCompleted = false
        audioPlaylistCompleted = false
        playingAudioIndex = -1
        pendingAudioPositionMs = 0L
        lastPlaybackTripTimeMs = null
        playbackMarker?.let { marker ->
            marker.closeInfoWindow()
            if (::mapView.isInitialized) mapView.overlays.remove(marker)
        }
        playbackMarker = null
        if (updateControls) {
            updatePlaybackControls()
        }
    }

    private fun selectParameter(param: String) {
        activeParam = param
        redrawMap(zoomToContent = true)
    }

    private fun redrawMap(zoomToContent: Boolean) {
        mapView.overlays.clear()
        eventMarkers.clear()
        playbackMarker = null

        if (geoPoints.isNotEmpty()) {
            if (activeParam.isEmpty()) {
                val route = Polyline().apply {
                    outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.teal_700)
                    outlinePaint.strokeWidth = 8f
                    setPoints(geoPoints)
                }
                mapView.overlays.add(route)
            } else {
                val segmentValues = computeSegmentValues(activeParam)
                if (segmentValues.isEmpty()) {
                    val route = Polyline().apply {
                        outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.teal_700)
                        outlinePaint.strokeWidth = 8f
                        setPoints(geoPoints)
                    }
                    mapView.overlays.add(route)
                } else {
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
            }

            addMarkers()
        }

        val eventPoints = if (eventsVisible) addEventMarkers() else emptyList()
        if (zoomToContent) zoomToContent(eventPoints)
        currentPlaybackTripTimeMs()?.let { tripTimeMs ->
            updatePlaybackMapPosition(tripTimeMs, recenter = false, detectEvent = false)
        }
        mapView.invalidate()
    }

    private fun computeSegmentValues(param: String): List<Double> {
        if (gpsData.size < 2) return emptyList()
        val values = MutableList(gpsData.size - 1) { 0.0 }

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

    private fun addEventMarkers(): List<GeoPoint> {
        val markerPoints = mutableListOf<GeoPoint>()
        events.forEachIndexed { index, event ->
            val location = EventMapLocationResolver.resolve(event, gpsData) ?: return@forEachIndexed
            val point = GeoPoint(location.latitude, location.longitude)
            val cause = displayEventCause(event)
            val source = when (location.source) {
                EventMapLocationSource.RECORDED -> "recorded location"
                EventMapLocationSource.NEAREST_GPS -> "nearest GPS point"
            }
            val marker = Marker(mapView).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Event ${index + 1}: $cause"
                snippet = "${formatTripElapsed(event.markerTimeMs)} | ${event.status} | $source | tap to play"
                setTextIcon((index + 1).toString())
                setOnMarkerClickListener(object : Marker.OnMarkerClickListener {
                    override fun onMarkerClick(marker: Marker, mapView: MapView): Boolean {
                        seekToEvent(event)
                        marker.showInfoWindow()
                        return true
                    }
                })
            }
            eventMarkers[event.eventId] = marker
            mapView.overlays.add(marker)
            markerPoints += point
        }
        refreshEventMarkerStyles()
        return markerPoints
    }

    private fun eventMarkerColor(event: TripEvent): Int = when (event.status) {
        EventStatus.ANNOTATED -> android.R.color.holo_green_light
        EventStatus.UNUSABLE -> android.R.color.holo_red_light
        else -> android.R.color.holo_orange_light
    }

    private fun refreshEventMarkerStyles() {
        eventMarkers.forEach { (eventId, marker) ->
            val event = events.firstOrNull { it.eventId == eventId } ?: return@forEach
            val colorRes = if (eventId == highlightedEventId) {
                android.R.color.holo_blue_dark
            } else {
                eventMarkerColor(event)
            }
            marker.setTextLabelBackgroundColor(ContextCompat.getColor(requireContext(), colorRes))
            marker.setTextLabelForegroundColor(Color.WHITE)
            marker.setTextIcon(events.indexOfFirst { it.eventId == eventId }.plus(1).toString())
        }
    }

    private fun formatTripElapsed(tripTimeMs: Long): String {
        if (tripStartMs <= 0L) return "time $tripTimeMs"
        return formatDuration((tripTimeMs - tripStartMs).coerceAtLeast(0L))
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
        return if (totalSeconds >= 3_600L) {
            String.format(
                Locale.getDefault(),
                "%d:%02d:%02d",
                totalSeconds / 3_600L,
                (totalSeconds / 60L) % 60L,
                totalSeconds % 60L
            )
        } else {
            String.format(
                Locale.getDefault(),
                "%02d:%02d",
                totalSeconds / 60L,
                totalSeconds % 60L
            )
        }
    }

    private fun zoomToContent(eventPoints: List<GeoPoint>) {
        val points = geoPoints + eventPoints
        if (points.size > 1) {
            val boundingBox = BoundingBox.fromGeoPoints(points)
            mapView.post {
                mapView.zoomToBoundingBox(boundingBox, false, 64)
            }
        } else if (points.size == 1) {
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(points.first())
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
        if (isAudioPlaying()) {
            try {
                audioPlayer?.pause()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Could not pause map audio for lifecycle change", error)
            }
            playbackProgressHandler.removeCallbacks(playbackProgressRunnable)
            updatePlaybackControls()
        }
        mapView.onPause()
    }

    override fun onDestroyView() {
        releaseMapAudioPlayer(updateControls = false)
        super.onDestroyView()
        pendingWorldAccel = null
        pendingWorldGyro = null
        eventMarkers.clear()
    }
}
