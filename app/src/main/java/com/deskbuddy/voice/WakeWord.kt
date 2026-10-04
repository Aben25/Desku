package com.deskbuddy.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Background wake-phrase listening, kept deliberately separate from conversation listening.
 *
 * Vosk runs fully on the phone with a grammar that only knows the wake phrase and a few
 * sound-alike decoys. "Desku" isn't an English word, so the phrase is spelled as the
 * dictionary-word sequences the model actually hears for it ("hey desk who", "hey desk coo"…). Audio is processed in memory and dropped; nothing is recorded, saved, or
 * sent anywhere. Only after the phrase is heard does [onWake] hand the microphone to [Ears]
 * for one utterance.
 */
class WakeWord(private val context: Context) {

    enum class Status { NotInstalled, Loading, Ready, Listening, Failed }

    private val _status = MutableStateFlow(Status.Loading)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var model: Model? = null
    private var service: SpeechService? = null
    private var onWake: (() -> Unit)? = null
    private var wantListening = false

    /** Unpacks the bundled model once (first launch takes a few seconds). */
    fun prepare() {
        val bundled = runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)
        if (!bundled) {
            _status.value = Status.NotInstalled
            return
        }
        _status.value = Status.Loading
        StorageService.unpack(
            context, ASSET_DIR, "model",
            { m ->
                model = m
                _status.value = Status.Ready
                if (wantListening) onWake?.let { start(it) }
            },
            { e ->
                Log.e(TAG, "wake model unpack failed", e)
                _status.value = Status.Failed
            },
        )
    }

    /** Main thread. Safe to call repeatedly; does nothing until the model is ready. */
    fun start(onWake: () -> Unit) {
        this.onWake = onWake
        wantListening = true
        val m = model ?: return
        if (service != null) return
        try {
            val grammar = JSONArray(VARIANTS + DECOYS + "[unk]").toString()
            val s = SpeechService(Recognizer(m, SAMPLE_RATE, grammar), SAMPLE_RATE)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String) = check(hypothesis, "text")
                override fun onError(exception: Exception) {
                    Log.e(TAG, "wake listener error", exception)
                    stop()
                    _status.value = Status.Failed
                }
                override fun onTimeout() {}
            })
            service = s
            _status.value = Status.Listening
        } catch (e: Exception) {
            Log.e(TAG, "wake listener failed to start", e)
            _status.value = Status.Failed
        }
    }

    private fun check(hypothesis: String, field: String) {
        if (heard(hypothesis, field)) {
            val callback = onWake
            stop() // release the microphone before the conversation recognizer takes it
            callback?.invoke()
        }
    }

    /** Main thread. Releases the microphone. */
    fun stop() {
        wantListening = false
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        if (_status.value == Status.Listening) _status.value = Status.Ready
    }

    fun shutdown() {
        stop()
        model?.close()
        model = null
    }

    companion object {
        private const val TAG = "WakeWord"
        const val ASSET_DIR = "model-en-us"
        private const val SAMPLE_RATE = 16_000f

        /** How "Hey Desku" comes out of the small English model. Keep in sync with scripts/check-wake-word.sh. */
        val VARIANTS = listOf(
            "hey desk you", "hey desk who", "hey desk coo", "hey desk two",
            "hey desk do", "hey desk clue", "hey desk school", "hey desk cool",
        )

        /** Words that sound close to the phrase, so near-misses resolve to them instead. */
        private val DECOYS = listOf(
            "hey", "desk", "hey desk", "hey just", "hey this", "school", "cool", "discuss", "hey there",
            "you", "hey do you", "thank you", "hey buddy", "the desk", "desktop", "hey dude",
        )

        /** Vosk reports {"partial": "..."} while listening and {"text": "..."} at the end of a phrase. */
        fun heard(json: String, field: String): Boolean {
            val said = runCatching { JSONObject(json).optString(field) }.getOrDefault("").trim()
            return VARIANTS.any { v -> said == v || said.startsWith("$v ") || said.endsWith(" $v") || said.contains(" $v ") }
        }
    }
}
