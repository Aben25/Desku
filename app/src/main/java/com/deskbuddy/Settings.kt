package com.deskbuddy

import android.content.Context
import com.deskbuddy.brain.BrainSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Prefs(
    val apiKey: String,
    val model: String,
    val wakeWordEnabled: Boolean,
    val frontCamera: Boolean,
    val micMuted: Boolean,
    val cameraOn: Boolean,
    /** Desku engine (server/), e.g. ws://192.168.1.20:8787. Blank = talk to Claude from the phone. */
    val serverUrl: String,
    val deviceToken: String,
)

/** App settings in private SharedPreferences. The API key never leaves this phone except to call Claude. */
class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    val current: Prefs get() = _prefs.value

    fun brainSettings() = BrainSettings(apiKey = current.apiKey, model = current.model)

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_prefs.value)
        sp.edit()
            .putString("apiKey", next.apiKey.trim())
            .putString("model", next.model.trim().ifBlank { BrainSettings.DEFAULT_MODEL })
            .putBoolean("wakeWordEnabled", next.wakeWordEnabled)
            .putBoolean("frontCamera", next.frontCamera)
            .putBoolean("micMuted", next.micMuted)
            .putBoolean("cameraOn", next.cameraOn)
            .putString("serverUrl", next.serverUrl.trim())
            .putString("deviceToken", next.deviceToken.trim())
            .apply()
        _prefs.value = read()
    }

    private fun read() = Prefs(
        apiKey = sp.getString("apiKey", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_API_KEY,
        model = sp.getString("model", null) ?: BrainSettings.DEFAULT_MODEL,
        wakeWordEnabled = sp.getBoolean("wakeWordEnabled", true),
        // A phone on a desk stand faces you, so the selfie camera sees what you hold up.
        frontCamera = sp.getBoolean("frontCamera", true),
        micMuted = sp.getBoolean("micMuted", false),
        cameraOn = sp.getBoolean("cameraOn", true),
        serverUrl = sp.getString("serverUrl", null) ?: BuildConfig.DEFAULT_SERVER_URL,
        deviceToken = sp.getString("deviceToken", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.DEFAULT_DEVICE_TOKEN,
    )
}
