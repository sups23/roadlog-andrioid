package com.example.roadlog

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class AudioSegmentInfo(
    val audioId: String,
    val segmentSequence: Int,
    val file: File,
    val startTimeMs: Long,
    val startElapsedRealtimeNanos: Long
)

/**
 * Encodes the PCM frames already read by Vosk into short, independently usable
 * AAC/M4A segments. It deliberately never opens a second AudioRecord.
 */
class TripAudioRecorder(
    private val directory: File,
    private val onSegmentStarted: (AudioSegmentInfo) -> Unit,
    private val onSegmentCompleted: (AudioSegmentInfo, Long, Long, String, String?, String?) -> Unit,
    private val segmentDurationMs: Long = 5 * 60 * 1000L
) : VoskSpeechRecognizer.AudioFrameListener {
    companion object {
        private const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val MIME_TYPE = "audio/mp4"
        private const val BIT_RATE = 32_000
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var activeSegment: AudioSegmentInfo? = null
    private var nextSequence = 0
    private var running = false
    private var encodedSampleCount = 0

    @Synchronized
    fun start(startTimeMs: Long, startElapsedRealtimeNanos: Long) {
        if (running) return
        directory.mkdirs()
        nextSequence = 0
        running = true
        openSegment(startTimeMs, startElapsedRealtimeNanos)
    }

    override fun onAudioFrame(samples: ShortArray, length: Int, elapsedRealtimeNanos: Long) {
        if (length <= 0) return
        synchronized(this) {
            if (!running) return
            val segment = activeSegment ?: return
            val elapsedMs = (elapsedRealtimeNanos - segment.startElapsedRealtimeNanos) / 1_000_000L
            if (elapsedMs >= segmentDurationMs) {
                finishSegment(
                    endTimeMs = segment.startTimeMs + elapsedMs,
                    endElapsedRealtimeNanos = elapsedRealtimeNanos,
                    status = TripAudioStatus.COMPLETE,
                    reason = null
                )
                openSegment(segment.startTimeMs + elapsedMs, elapsedRealtimeNanos)
            }
            queue(samples, length, elapsedRealtimeNanos)
            drain(endOfStream = false)
        }
    }

    @Synchronized
    fun stop(endTimeMs: Long, endElapsedRealtimeNanos: Long, reason: String? = null) {
        if (!running) return
        val status = if (reason == null) TripAudioStatus.COMPLETE else TripAudioStatus.INTERRUPTED
        finishSegment(endTimeMs, endElapsedRealtimeNanos, status, reason)
        running = false
        activeSegment = null
    }

    @Synchronized
    fun fail(endTimeMs: Long, endElapsedRealtimeNanos: Long, reason: String) {
        stop(endTimeMs, endElapsedRealtimeNanos, reason)
    }

    private fun openSegment(startTimeMs: Long, startElapsedRealtimeNanos: Long) {
        val audioId = UUID.randomUUID().toString()
        val file = File(directory, "audio_${audioId}.m4a")
        val segment = AudioSegmentInfo(audioId, nextSequence++, file, startTimeMs, startElapsedRealtimeNanos)
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE_HZ, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE_HZ / 5 * 2)
        }
        try {
            codec = MediaCodec.createEncoderByType("audio/mp4a-latm").also {
                it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                it.start()
            }
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrack = -1
            encodedSampleCount = 0
            activeSegment = segment
            onSegmentStarted(segment)
        } catch (error: Exception) {
            releaseEncoder()
            onSegmentStarted(segment)
            onSegmentCompleted(
                segment,
                startTimeMs,
                startElapsedRealtimeNanos,
                TripAudioStatus.FAILED,
                null,
                error.message ?: "AAC encoder initialization failed"
            )
            activeSegment = null
        }
    }

    private fun queue(samples: ShortArray, length: Int, timestampNanos: Long) {
        val encoder = codec ?: return
        val inputIndex = encoder.dequeueInputBuffer(0)
        if (inputIndex < 0) return
        val input = encoder.getInputBuffer(inputIndex) ?: return
        input.clear()
        val bytes = minOf(length, input.remaining() / 2)
        for (index in 0 until bytes) {
            val value = samples[index].toInt()
            input.put((value and 0xff).toByte())
            input.put((value shr 8 and 0xff).toByte())
        }
        val segment = activeSegment ?: return
        val presentationTimeUs = ((timestampNanos - segment.startElapsedRealtimeNanos) / 1_000L).coerceAtLeast(0L)
        encoder.queueInputBuffer(inputIndex, 0, bytes * 2, presentationTimeUs, 0)
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        val deadline = SystemClock.elapsedRealtime() + if (endOfStream) 2_000L else 0L
        if (endOfStream) {
            var eosQueued = false
            while (!eosQueued && SystemClock.elapsedRealtime() <= deadline) {
                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    encoder.queueInputBuffer(
                        inputIndex,
                        0,
                        0,
                        0L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    eosQueued = true
                } else {
                    drainOutput(encoder, endOfStream = false, timeoutUs = 0L)
                }
            }
            if (!eosQueued) return
        }
        val timeoutUs = if (endOfStream) 10_000L else 0L
        do {
            val reachedEnd = drainOutput(encoder, endOfStream, timeoutUs)
            if (reachedEnd || !endOfStream) return
        } while (SystemClock.elapsedRealtime() <= deadline)
    }

    private fun drainOutput(encoder: MediaCodec, endOfStream: Boolean, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        var outputIndex = encoder.dequeueOutputBuffer(info, timeoutUs)
        while (true) {
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && muxerTrack < 0) {
                muxerTrack = muxer?.addTrack(encoder.outputFormat) ?: -1
                if (muxerTrack >= 0) muxer?.start()
            } else if (outputIndex >= 0) {
                val output = encoder.getOutputBuffer(outputIndex)
                if (output != null && info.size > 0 && muxerTrack >= 0) {
                    output.position(info.offset)
                    output.limit(info.offset + info.size)
                    muxer?.writeSampleData(muxerTrack, output, info)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        encodedSampleCount++
                    }
                }
                val reachedEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                encoder.releaseOutputBuffer(outputIndex, false)
                if (reachedEnd) return true
            } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return false
            } else {
                return false
            }

            outputIndex = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
        }
    }

    private fun finishSegment(
        endTimeMs: Long,
        endElapsedRealtimeNanos: Long,
        status: String,
        reason: String?
    ) {
        val segment = activeSegment ?: return
        if (codec != null) {
            drain(endOfStream = true)
        }
        releaseEncoder()
        val checksum = sha256(segment.file)
        val effectiveStatus = if (status == TripAudioStatus.COMPLETE && encodedSampleCount == 0) {
            TripAudioStatus.FAILED
        } else {
            status
        }
        onSegmentCompleted(
            segment,
            endTimeMs,
            endElapsedRealtimeNanos,
            effectiveStatus,
            checksum,
            reason ?: if (effectiveStatus == TripAudioStatus.FAILED) "audio segment contains no encoded samples" else null
        )
    }

    private fun releaseEncoder() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        muxerTrack = -1
    }

    private fun sha256(file: File): String? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }
}
