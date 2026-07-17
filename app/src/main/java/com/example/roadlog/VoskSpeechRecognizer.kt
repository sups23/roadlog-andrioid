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

class VoskSpeechRecognizer(
    private val context: Context,
    private val grammarJson: String? = null
) {

    interface Callback {
        fun onReady()
        fun onResult(text: String, confidence: Float)
        fun onPartialResult(text: String)
        fun onError(error: String)
    }

    private val sampleRate = 16000
    private val modelPath = "model-en-us"

    private val lifecycleMutex = kotlinx.coroutines.sync.Mutex()
    private val recognizerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var preparedModel: Model? = null
    private var preparedRecognizer: Recognizer? = null
    private var isPrepared = false

    private var sessionGeneration = 0L
    private var sessionRecorder: AudioRecord? = null
    private var sessionRecognizer: Recognizer? = null
    private var sessionCallback: Callback? = null
    private var sessionJob: Job? = null
    @Volatile
    private var sessionListening = false

    private var preparationGeneration = 0L

    init {
        LibVosk.setLogLevel(LogLevel.INFO)
    }

    fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        if (isPrepared) {
            onReady()
            return
        }

        val gen = ++preparationGeneration
        Log.i(TAG, "Unpacking Vosk model from assets (gen=$gen)...")
        recognizerScope.launch {
            StorageService.unpack(
                context,
                modelPath,
                "model",
                { unpackedModel ->
                    if (gen != preparationGeneration) {
                        Log.w(TAG, "Ignoring stale model completion gen=$gen, current=$preparationGeneration")
                        unpackedModel.close()
                        return@unpack
                    }
                    try {
                        val rec = if (grammarJson != null) {
                            Log.i(TAG, "Creating grammar-constrained Vosk recognizer")
                            Recognizer(unpackedModel, sampleRate.toFloat(), grammarJson)
                        } else {
                            Log.i(TAG, "Creating free-form Vosk recognizer")
                            Recognizer(unpackedModel, sampleRate.toFloat())
                        }
                        rec.setWords(true)
                        preparedModel = unpackedModel
                        preparedRecognizer = rec
                        isPrepared = true
                        Log.i(TAG, "Vosk model and recognizer ready")
                        onReady()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create Vosk recognizer", e)
                        unpackedModel.close()
                        onError("Failed to create Vosk recognizer: ${e.message}")
                    }
                },
                { exception ->
                    val msg = "Failed to unpack Vosk model: ${exception.message}"
                    Log.e(TAG, msg, exception)
                    onError(msg)
                }
            )
        }
    }

    fun startListening(callback: Callback) {
        recognizerScope.launch {
            lifecycleMutex.withLock {
                if (!isPrepared || preparedRecognizer == null) {
                    callback.onError("Vosk model not ready")
                    return@withLock
                }

                stopSessionLocked()

                val gen = ++sessionGeneration
                val rec = preparedRecognizer!!

                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufferSize <= 0) {
                    callback.onError("Invalid AudioRecord buffer size: $minBufferSize")
                    return@withLock
                }

                val desiredBufferSize = (sampleRate * 0.2f).toInt()
                val internalBufferSize = maxOf(minBufferSize, desiredBufferSize) * 2
                val readBufferSize = maxOf(minBufferSize, desiredBufferSize)

                val recorder = try {
                    val ar = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        internalBufferSize
                    )
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        ar.release()
                        callback.onError("AudioRecord failed to initialize")
                        return@withLock
                    }
                    ar
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create AudioRecord", e)
                    callback.onError("Failed to create AudioRecord: ${e.message}")
                    return@withLock
                }

                sessionRecorder = recorder
                sessionRecognizer = rec
                sessionCallback = callback
                sessionListening = true

                val localRecorder = recorder
                val localRecognizer = rec
                val localCallback = callback

                recorder.startRecording()
                Log.i(TAG, "AudioRecord started gen=$gen readBuffer=$readBufferSize internal=$internalBufferSize")
                callback.onReady()

                sessionJob = recognizerScope.launch {
                    runRecognitionLoop(gen, localRecorder, localRecognizer, localCallback, readBufferSize)
                }
            }
        }
    }

    fun stop() {
        recognizerScope.launch {
            lifecycleMutex.withLock {
                stopSessionLocked()
                sessionCallback = null
            }
        }
    }

    fun destroy() {
        recognizerScope.launch {
            lifecycleMutex.withLock {
                isPrepared = false
                preparationGeneration++
                stopSessionLocked()
                sessionCallback = null
                try { preparedRecognizer?.close() } catch (_: Exception) {}
                preparedRecognizer = null
                try { preparedModel?.close() } catch (_: Exception) {}
                preparedModel = null
                Log.i(TAG, "Vosk resources destroyed")
            }
        }
    }

    private fun stopSessionLocked() {
        sessionListening = false
        val job = sessionJob
        val recorder = sessionRecorder

        try { recorder?.stop() } catch (_: Exception) {}

        sessionJob = null
        sessionRecorder = null
        sessionRecognizer = null

        try { recorder?.release() } catch (_: Exception) {}
    }

    private suspend fun runRecognitionLoop(
        gen: Long,
        recorder: AudioRecord,
        recognizer: Recognizer,
        callback: Callback,
        bufferSize: Int
    ) {
        val buffer = ShortArray(bufferSize)
        Log.i(TAG, "runRecognitionLoop started gen=$gen")

        var iterations = 0L
        var lastPartial = ""
        var consecutiveReadErrors = 0
        var consecutiveZeroReads = 0
        val maxConsecutiveReadErrors = 5
        val maxConsecutiveZeroReads = 100

        while (sessionListening && sessionGeneration == gen && isActive) {
            try {
                iterations++
                val read = try {
                    recorder.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord read threw exception at iter=$iterations", e)
                    consecutiveReadErrors++
                    if (consecutiveReadErrors >= maxConsecutiveReadErrors) {
                        dispatchCallback(gen) { callback.onError("AudioRecord read failed repeatedly") }
                        break
                    }
                    delay(50)
                    continue
                }

                if (read < 0) {
                    consecutiveReadErrors++
                    consecutiveZeroReads = 0
                    Log.e(TAG, "AudioRecord read error: $read (consecutive=$consecutiveReadErrors)")
                    if (consecutiveReadErrors >= maxConsecutiveReadErrors) {
                        dispatchCallback(gen) { callback.onError("AudioRecord read failed repeatedly") }
                        break
                    }
                    delay(50)
                    continue
                }

                if (read == 0) {
                    consecutiveZeroReads++
                    consecutiveReadErrors = 0
                    if (consecutiveZeroReads >= maxConsecutiveZeroReads) {
                        Log.e(TAG, "Repeated zero-byte reads, stopping recognition loop")
                        break
                    }
                    delay(50)
                    continue
                }

                consecutiveReadErrors = 0
                consecutiveZeroReads = 0

                if (iterations % 25 == 0L) {
                    val level = audioLevel(buffer, read)
                    Log.d(TAG, "gen=$gen iter=$iterations audioLevel=$level read=$read")
                }

                val isEndpoint = recognizer.acceptWaveForm(buffer, read)

                if (isEndpoint) {
                    val resultJson = recognizer.result
                    val text = extractText(resultJson)
                    val confidence = extractConfidence(resultJson)
                    Log.i(TAG, "gen=$gen FINAL result: '$text' confidence=$confidence iter=$iterations")
                    dispatchCallback(gen) { callback.onResult(text, confidence) }
                    lastPartial = ""
                } else {
                    val partialJson = recognizer.partialResult
                    val text = extractPartialText(partialJson)
                    if (text.isNotEmpty() && text != lastPartial) {
                        lastPartial = text
                        dispatchCallback(gen) { callback.onPartialResult(text) }
                    }
                }
            } catch (ce: CancellationException) {
                Log.i(TAG, "Recognition loop cancelled gen=$gen at iter=$iterations")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in recognition loop gen=$gen at iter=$iterations", e)
                dispatchCallback(gen) { callback.onError("Recognition loop error: ${e.message}") }
                break
            }
        }

        Log.i(TAG, "Recognition loop ended gen=$gen after $iterations iterations")
    }

    private fun dispatchCallback(gen: Long, action: () -> Unit) {
        recognizerScope.launch(Dispatchers.Main) {
            if (sessionGeneration == gen) {
                action()
            } else {
                Log.w(TAG, "Ignoring callback from stale session gen=$gen, current=$sessionGeneration")
            }
        }
    }

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

    private fun extractConfidence(hypothesis: String?): Float {
        return try {
            val json = JSONObject(hypothesis ?: "{}")
            val resultArray = json.optJSONArray("result") ?: return 0f
            var minConfidence = Float.MAX_VALUE
            var hasWord = false
            for (i in 0 until resultArray.length()) {
                val wordObj = resultArray.getJSONObject(i)
                val conf = wordObj.optDouble("conf", -1.0).toFloat()
                if (conf >= 0f) {
                    hasWord = true
                    if (conf < minConfidence) minConfidence = conf
                }
            }
            if (hasWord) minConfidence else 0f
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse confidence from result: ${e.message}")
            0f
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
