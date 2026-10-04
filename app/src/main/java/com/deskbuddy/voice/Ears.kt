package com.deskbuddy.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One utterance at a time through Android's speech recognizer. On most phones this is Google's
 * recognizer, which may send the audio of *this utterance* to Google to transcribe. It only runs
 * after the wake phrase or a tap on the mic button, never in the background.
 */
class Ears(private val context: Context) {

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Level(val level: Float) : Event
        data object NothingHeard : Event
        data class Failed(val message: String) : Event
    }

    private var recognizer: SpeechRecognizer? = null

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Main thread only. Each call gets a fresh recognizer, which old phones handle best. */
    fun listen(onEvent: (Event) -> Unit) {
        cancel()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        var finished = false
        fun finish(e: Event) {
            if (finished) return
            finished = true
            onEvent(e)
            if (recognizer === r) {
                r.destroy()
                recognizer = null
            }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2..10 dB on most recognizers.
                onEvent(Event.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                finish(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_CLIENT -> Event.NothingHeard
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Event.Failed("I need microphone permission to hear you.")
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                        SpeechRecognizer.ERROR_SERVER -> Event.Failed("Speech recognition couldn't reach the internet.")
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Event.Failed("The speech recognizer is busy. Try again.")
                        else -> Event.Failed("Speech recognition failed (error $error).")
                    },
                )
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                finish(if (text.isEmpty()) Event.NothingHeard else Event.Final(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank() && !finished) onEvent(Event.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A thinking pause shouldn't end the turn too early (a hint; not every recognizer honors it).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        r.startListening(intent)
    }

    /** Stop recording and deliver whatever was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Drop the current utterance without a result. */
    fun cancel() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }
}
