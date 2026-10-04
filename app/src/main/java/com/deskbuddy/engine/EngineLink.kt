package com.deskbuddy.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** What the engine says about its voice session. */
data class EngineState(val live: String = "idle", val muted: Boolean = false, val camera: Boolean = true, val thinking: Boolean = false)

/**
 * The phone's connection to the Desku engine (server/, see server/README.md "Device protocol"):
 * one WebSocket carrying mic audio up and Desku's voice down as raw 24 kHz PCM16 binary frames,
 * and JSON for everything else. Reconnects with backoff, since a desk phone runs for days.
 */
class EngineLink(private val scope: CoroutineScope, private val listener: Listener) {

    interface Listener {
        fun onConnected(connected: Boolean)
        fun onState(state: EngineState)
        fun onTranscript(role: String, delta: String)
        fun onDesk(desk: JSONObject)
        fun onCapture(requestId: String, countdownSeconds: Int)
        fun onCheckin(about: String)
        fun onCalling(about: String)
        fun onLink(url: String, label: String)
        fun onError(message: String)
        fun onAudio(pcm: ByteArray)
        /** Desku changed something on the desk: camera_on/off, close_camera, mute_mic, show/hide_memories, end_conversation. */
        fun onControl(action: String)
        /** Desku made or updated a web page for the screen; [url] is absolute. */
        fun onPage(title: String, url: String)
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var url: String = ""
    /** ws://host:port → http://host:port, for the engine's relative page links. */
    private var httpBase: String = ""
    private var wanted = false
    private var reconnect: Job? = null
    private var attempt = 0
    @Volatile var connected = false
        private set

    fun connect(serverUrl: String, token: String) {
        val base = serverUrl.trim().trimEnd('/')
        url = "$base/device?token=${java.net.URLEncoder.encode(token, "UTF-8")}"
        httpBase = base.replaceFirst(Regex("^ws"), "http")
        wanted = true
        open()
    }

    fun disconnect() {
        wanted = false
        reconnect?.cancel()
        socket?.close(1000, "bye")
        socket = null
    }

    private fun open() {
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== socket) return
                attempt = 0
                connected = true
                listener.onConnected(true)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket === socket) listener.onAudio(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                val m = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (m.optString("type")) {
                    "state" -> listener.onState(
                        EngineState(m.optString("live", "idle"), m.optBoolean("muted"), m.optBoolean("camera", true), m.optBoolean("thinking")),
                    )
                    "transcript" -> listener.onTranscript(m.optString("role"), m.optString("delta"))
                    "desk" -> listener.onDesk(m)
                    "capture" -> listener.onCapture(m.optString("requestId"), m.optInt("countdownSeconds", 3))
                    "checkin" -> listener.onCheckin(m.optString("about"))
                    "calling" -> listener.onCalling(m.optString("about"))
                    "link" -> listener.onLink(m.optString("url"), m.optString("label", "Open"))
                    "error" -> listener.onError(m.optString("message"))
                    "control" -> listener.onControl(m.optString("action"))
                    "page" -> listener.onPage(m.optString("title"), httpBase + m.optString("url"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== socket) return
                Log.w(TAG, "engine connection failed (${response?.code}): ${t.message}")
                if (response?.code == 401) listener.onError("The Desku engine refused the device token. Check it in Settings.")
                dropped()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket === socket) dropped()
            }
        })
    }

    private fun dropped() {
        connected = false
        listener.onConnected(false)
        if (!wanted) return
        reconnect?.cancel()
        reconnect = scope.launch {
            delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
            if (wanted) open()
        }
    }

    fun send(type: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject().put("type", type)
        fields.forEach { (k, v) -> o.put(k, v) }
        socket?.send(o.toString())
    }

    fun sendAudio(pcm: ByteArray, length: Int) {
        socket?.send(pcm.toByteString(0, length))
    }

    companion object {
        private const val TAG = "DeskuEngine"
    }
}
