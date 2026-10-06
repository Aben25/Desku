package com.deskbuddy.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Full-duplex audio for a GPT-Live voice session: the mic and the speaker are both open while
 * Desku talks, like a speakerphone call. That only works with echo cancellation, so the mic uses
 * the VOICE_COMMUNICATION source (the phone's call-grade AEC) with the platform echo canceller
 * attached, and playback goes through the communication stream routed to the loudspeaker.
 * Both sides are raw mono PCM16 at 24 kHz, which is what GPT-Live speaks.
 */
class LiveAudio(context: Context, private val scope: CoroutineScope) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var micJob: Job? = null
    private var track: AudioTrack? = null
    private var playJob: Job? = null
    private var queue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var playheadEnd = 0L

    /** True while Desku's voice is coming out of the speaker. */
    val speaking: Boolean get() = SystemClock.elapsedRealtime() < playheadEnd

    fun start() {
        routeToSpeaker(true)
        startPlayback()
    }

    fun stop() {
        stopMic()
        stopPlayback()
        routeToSpeaker(false)
    }

    @SuppressLint("MissingPermission") // checked by the caller before starting a session
    fun startMic(onChunk: (ByteArray, Int, Float) -> Unit) {
        if (micJob?.isActive == true) return
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 4))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "mic unavailable")
            rec?.release()
            return
        }
        val fx = mutableListOf<android.media.audiofx.AudioEffect>()
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; fx += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; fx += it }
        record = rec
        // The loop owns the recorder: it stops and releases it itself, so stopMic() can never
        // free it in the middle of a read() on this thread (that crashed the app).
        micJob = scope.launch(Dispatchers.IO) {
            try {
                rec.startRecording()
                val buf = ByteArray(CHUNK)
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT: the mic went away
                    if (n > 0) onChunk(buf, n, level(buf, n))
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) Log.w(TAG, "mic loop stopped", e)
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                fx.forEach { runCatching { it.release() } }
            }
        }
    }

    fun stopMic() {
        micJob?.cancel() // the loop releases the recorder on its way out
        micJob = null
        record = null
    }

    /** The user talked over Desku: drop everything queued and stop the speaker right now. */
    fun flush() {
        while (queue.tryReceive().isSuccess) { /* discard */ }
        track?.let { t -> runCatching { t.pause(); t.flush(); t.play() } }
        playheadEnd = 0
    }

    fun play(pcm: ByteArray) {
        val ms = pcm.size / BYTES_PER_MS
        val now = SystemClock.elapsedRealtime()
        playheadEnd = maxOf(playheadEnd, now) + ms
        queue.trySend(pcm)
    }

    private fun startPlayback() {
        if (playJob?.isActive == true) return
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, CHUNK * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        queue = Channel(Channel.UNLIMITED)
        val q = queue
        // Same rule as the mic: the loop that writes to the track is the one that releases it.
        playJob = scope.launch(Dispatchers.IO) {
            try {
                t.play()
                for (pcm in q) {
                    if (t.write(pcm, 0, pcm.size) < 0) break
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) Log.w(TAG, "playback loop stopped", e)
            } finally {
                runCatching { t.pause(); t.flush(); t.stop() }
                runCatching { t.release() }
            }
        }
    }

    private fun stopPlayback() {
        queue.close()
        playJob?.cancel()
        playJob = null
        track = null
        playheadEnd = 0
    }

    private fun routeToSpeaker(on: Boolean) {
        runCatching {
            if (on) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { audioManager.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice()
                else @Suppress("DEPRECATION") { audioManager.isSpeakerphoneOn = false }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure { Log.w(TAG, "audio routing failed", it) }
    }

    private fun level(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toDouble()
            sum += s * s
            i += 2
        }
        val rms = sqrt(sum / maxOf(1, n / 2)) / 32768.0
        return (rms * 6).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        private const val TAG = "LiveAudio"
        const val RATE = 24_000
        private const val BYTES_PER_MS = RATE * 2 / 1000
        /** 100 ms of audio per frame sent up. */
        private const val CHUNK = RATE * 2 / 10
    }
}
