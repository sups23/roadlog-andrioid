package com.example.roadlog

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Offline speech recognition using Vosk with a direct AudioRecord loop.
 *
 * Mirrors Vosk's SpeechService recognizer thread: a single Recognizer is fed
 * continuous audio, and we rely on Vosk's internal utterance segmentation
 * (no explicit reset between utterances).
 */
class VoskSpeechRecognizer(private val context: Context) {

    interface Callback {
        fun onReady()
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(error: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var callback: Callback? = null
    private var isReady = false
    private var isListening = false

    private val sampleRate = 16000
    private val modelPath = "model-en-us"

    private val recognizerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recognitionJob: Job? = null

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
    }

    /**
     * Unpacks the model from assets and loads it. Calls [onReady] when done.
     */
    fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        if (isReady) {
            onReady()
            return
        }

        Log.i(TAG, "Unpacking Vosk model from assets...")
        StorageService.unpack(
            context,
            modelPath,
            "model",
            { unpackedModel ->
                model = unpackedModel
                try {
                    recognizer = Recognizer(unpackedModel, sampleRate.toFloat())
                    isReady = true
                    Log.i(TAG, "Vosk model and recognizer ready")
                    onReady()
                } catch (e: Exception) {
                    val msg = "Failed to create Vosk recognizer: ${e.message}"
                    Log.e(TAG, msg, e)
                    onError(msg)
                }
            },
            { exception ->
                val msg = "Failed to unpack Vosk model: ${exception.message}"
                Log.e(TAG, msg, exception)
                onError(msg)
            }
        )
    }

    /**
     * Starts listening to the microphone.
     */
    fun startListening(callback: Callback) {
        this.callback = callback

        if (!isReady) {
            callback.onError("Vosk model not ready")
            return
        }

        // Stop any previous loop / recording so we never run two loops concurrently.
        stopInternal()

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                callback.onError("Invalid AudioRecord buffer size: $minBufferSize")
                return
            }

            // Match Vosk SpeechService: ~200ms read chunks, double for AudioRecord internal buffer.
            val bufferSize = (sampleRate * 0.2f).toInt()
            val internalBufferSize = bufferSize * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                internalBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("AudioRecord failed to initialize")
                return
            }

            isListening = true
            audioRecord?.startRecording()
            Log.i(TAG, "AudioRecord started, readBuffer=$bufferSize, internalBuffer=$internalBufferSize, recordingState=${audioRecord?.recordingState}")
            callback.onReady()

            recognitionJob = recognizerScope.launch {
                runRecognitionLoop(bufferSize)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk listening", e)
            callback.onError("Failed to start listening: ${e.message}")
            isListening = false
        }
    }

    /**
     * Stops listening and releases the audio recorder.
     */
    fun stop() {
        stopInternal()
        callback = null
    }

    private fun stopInternal() {
        isListening = false
        recognitionJob?.cancel()
        recognitionJob = null
        stopAudioRecord()
    }

    /**
     * Releases the loaded model and recognizer. Call when permanently done.
     */
    fun destroy() {
        stopInternal()
        recognizerScope.cancel()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        isReady = false
        callback = null
    }

    private suspend fun runRecognitionLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)
        val currentRecognizer = recognizer ?: run {
            callback?.onError("Recognizer not available")
            return
        }

        Log.i(TAG, "runRecognitionLoop started: recognizer=$currentRecognizer, bufferSize=$bufferSize")

        var iterations = 0L
        var lastPartial = ""
        var consecutiveReadErrors = 0
        val maxConsecutiveReadErrors = 5

        withContext(Dispatchers.IO) {
            while (isListening && isActive) {
                try {
                    iterations++
                    val recordingState = audioRecord?.recordingState
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1

                    Log.v(TAG, "iter=$iterations read=$read recState=$recordingState isListening=$isListening")

                    if (read < 0) {
                        consecutiveReadErrors++
                        Log.e(TAG, "AudioRecord read error: $read (consecutive=$consecutiveReadErrors)")
                        if (consecutiveReadErrors >= maxConsecutiveReadErrors) {
                            callback?.onError("AudioRecord read failed repeatedly")
                            break
                        }
                        delay(50)
                        continue
                    }
                    if (read == 0) {
                        Log.v(TAG, "iter=$iterations read=0, skipping")
                        continue
                    }
                    consecutiveReadErrors = 0

                    // Periodically log audio level so we can verify the mic is still delivering audio.
                    if (iterations % 25 == 0L) {
                        val level = audioLevel(buffer, read)
                        Log.d(TAG, "iter=$iterations audioLevel=$level read=$read")
                    }

                    val isEndpoint = currentRecognizer.acceptWaveForm(buffer, read)
                    Log.v(TAG, "iter=$iterations acceptWaveForm endpoint=$isEndpoint")

                    if (isEndpoint) {
                        val resultJson = currentRecognizer.result
                        val text = extractText(resultJson)
                        Log.i(TAG, "Vosk FINAL result: raw=$resultJson | text='$text' | iter=$iterations")
                        callback?.onResult(text)
                        lastPartial = ""
                    } else {
                        val partialJson = currentRecognizer.partialResult
                        val text = extractPartialText(partialJson)
                        Log.v(TAG, "Vosk PARTIAL result: raw=$partialJson | text='$text' | iter=$iterations")
                        if (text.isNotEmpty() && text != lastPartial) {
                            lastPartial = text
                            Log.d(TAG, "Vosk partial changed: '$text'")
                            callback?.onPartialResult(text)
                        }
                    }
                } catch (ce: CancellationException) {
                    Log.i(TAG, "Recognition loop cancelled at iter=$iterations")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in recognition loop at iter=$iterations", e)
                    callback?.onError("Recognition loop error: ${e.message}")
                    break
                }
            }
        }

        isListening = false
        stopAudioRecord()
        Log.i(TAG, "Recognition loop ended after $iterations iterations")
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    /**
     * Rough audio energy level (RMS) for diagnostics. 0 = silence, higher = louder.
     */
    private fun audioLevel(buffer: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum / length)
    }

    private fun extractText(hypothesis: String?): String {
        return try {
            JSONObject(hypothesis ?: "{}").optString("text", "").trim()
        } catch (e: Exception) {
            hypothesis?.trim() ?: ""
        }
    }

    private fun extractPartialText(hypothesis: String?): String {
        return try {
            JSONObject(hypothesis ?: "{}").optString("partial", "").trim()
        } catch (e: Exception) {
            hypothesis?.trim() ?: ""
        }
    }

    companion object {
        private const val TAG = "RoadLog"
    }
}
