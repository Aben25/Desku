package com.deskbuddy.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech out of the phone's speaker, plus the small chimes. */
class Voice(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tones = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS
        if (ok) {
            tts.language = Locale.getDefault().takeIf { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) { pending.remove(utteranceId)?.complete(Unit) }
                override fun onStop(utteranceId: String, interrupted: Boolean) { pending.remove(utteranceId)?.complete(Unit) }
            })
        }
        ready.complete(ok)
    }

    /** Queue [text] and suspend until it has been spoken (or [stop] was called). */
    suspend fun say(text: String) {
        val done = enqueue(text) ?: return
        // A stuck engine must not wedge the conversation: allow ~150 ms per character.
        withTimeoutOrNull(5_000L + text.length * 150L) { done.await() }
    }

    /** Queue [text] without waiting (used for "hold it up to the camera" while the countdown runs). */
    suspend fun sayAndContinue(text: String) {
        enqueue(text)
    }

    private suspend fun enqueue(text: String): CompletableDeferred<Unit>? {
        if (text.isBlank() || !ready.await()) return null
        val chunks = text.chunked(TextToSpeech.getMaxSpeechInputLength() - 1)
        var last: CompletableDeferred<Unit>? = null
        chunks.forEach { chunk ->
            val id = UUID.randomUUID().toString()
            val done = CompletableDeferred<Unit>()
            pending[id] = done
            if (tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) {
                pending.remove(id)
                done.complete(Unit)
            }
            last = done
        }
        return last
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    /** Short rising blip: "I'm listening." */
    fun chimeListening() { tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }

    /** Gentle double beep for a finished focus timer. */
    fun chimeTimer() { tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 600) }

    fun shutdown() {
        stop()
        tts.shutdown()
        tones?.release()
    }
}
