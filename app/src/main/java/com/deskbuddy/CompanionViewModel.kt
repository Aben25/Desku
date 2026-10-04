package com.deskbuddy

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deskbuddy.brain.Brain
import com.deskbuddy.brain.BrainException
import com.deskbuddy.brain.BrainHost
import com.deskbuddy.brain.CardItem
import com.deskbuddy.brain.Choice
import com.deskbuddy.brain.LookResult
import com.deskbuddy.brain.ScreenCard
import com.deskbuddy.brain.Turn
import com.deskbuddy.camera.LookRequest
import com.deskbuddy.engine.EngineLink
import com.deskbuddy.engine.EngineState
import com.deskbuddy.engine.LiveAudio
import com.deskbuddy.memory.Memory
import com.deskbuddy.memory.MemoryStore
import com.deskbuddy.voice.Ears
import com.deskbuddy.voice.Voice
import com.deskbuddy.voice.WakeWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

enum class Mode { Idle, Listening, Thinking, Speaking, Looking }

data class FocusTimer(val task: String, val minutes: Int, val endsAt: Long)

/** A finished focus timer waiting for the user's answer. */
data class CheckIn(val task: String, val minutes: Int)

/** A page Desku put on the screen (served by the engine), shown in-app over everything else. */
data class DeskPage(val title: String, val url: String)

data class UiState(
    val mode: Mode = Mode.Idle,
    /** Live transcript while listening. */
    val heard: String = "",
    /** The user's last finished words ("You asked: …"). */
    val asked: String = "",
    /** What Desku said last, and when. */
    val said: String = "",
    val saidAt: Long = 0L,
    /** The list and tap targets that came with the last reply. */
    val card: ScreenCard? = null,
    /** "Remembered: …" from the last reply, for the banner. */
    val remembered: String? = null,
    /** Mic loudness 0..1 while listening. */
    val level: Float = 0f,
    /** Honest one-liners: errors, missing pieces. */
    val notice: String? = null,
    /** Camera session: open from the countdown until the user moves on; snapshot once taken. */
    val cameraOpen: Boolean = false,
    val countdown: Int = 0,
    val snapshot: ByteArray? = null,
    val checkIn: CheckIn? = null,
    val memoriesOpen: Boolean = false,
    val micPermission: Boolean = false,
    val cameraPermission: Boolean = false,
    val speechAvailable: Boolean = true,
    /** Dim the screen after a while of nothing happening. */
    val ambient: Boolean = false,
    /** Loudness 0..1 of Desku's own voice while it plays (engine mode), for the face and aura. */
    val voiceLevel: Float = 0f,
    /** The page Desku is showing, if any. A new page replaces it; Close removes it. */
    val page: DeskPage? = null,
)

class CompanionViewModel(app: Application) : AndroidViewModel(app), BrainHost {

    val settings = Settings(app)
    val memory = MemoryStore(File(app.filesDir, "memories.json"))
    private val ears = Ears(app)
    private val voice = Voice(app)
    val wake = WakeWord(app)
    /** The always-on, never-shown camera: presence on the phone, instant photos when asked. */
    val eyes = com.deskbuddy.camera.AlwaysOnEyes(app)
    private val brain = Brain(settings = settings::brainSettings, memory = memory, host = this)

    private val _ui = MutableStateFlow(UiState(speechAvailable = ears.available()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _look = MutableStateFlow<LookRequest?>(null)
    val look: StateFlow<LookRequest?> = _look.asStateFlow()

    private val _timer = MutableStateFlow<FocusTimer?>(null)
    val timer: StateFlow<FocusTimer?> = _timer.asStateFlow()

    // ---- engine mode: a Desku engine (server/) does the listening and talking over GPT-Live ----

    private val engineMemories = MutableStateFlow<List<Memory>>(emptyList())
    private val audio = LiveAudio(app, viewModelScope)
    private val link = EngineLink(viewModelScope, EngineEvents())
    private var engineState = EngineState()
    @Volatile private var audioRunning = false
    private var lastRole = ""
    private var lastFocusMinutes = 25
    private var sentPresence: Boolean? = null
    private val engineOn: Boolean get() = settings.current.serverUrl.isNotBlank()

    /** Saved memories: the engine's when it's in use, otherwise the ones kept on this phone. */
    val memories: StateFlow<List<Memory>> = combine(settings.prefs, memory.memories, engineMemories) { p, local, remote ->
        if (p.serverUrl.isNotBlank()) remote else local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, memory.memories.value)

    private var turnJob: Job? = null
    private var timerJob: Job? = null
    private var debugPhoto: File? = null
    private var lastActivity = System.currentTimeMillis()

    init {
        connectEngine()
        viewModelScope.launch {
            // Tell the engine when someone is at the desk. "Here" goes out at once; "away" only
            // after 15 s without a face, so a missed frame or a glance away doesn't count.
            eyes.presence.map { it.present }.distinctUntilChanged().collectLatest { present ->
                if (!present) delay(15_000)
                sentPresence = present
                if (engineOn && link.connected) link.send("presence", "present" to present)
            }
        }
        viewModelScope.launch {
            // Keep the orb honest during a voice session: speaking while Desku's audio plays.
            while (true) {
                delay(60)
                if (audioRunning) refreshEngineMode()
            }
        }
        wake.prepare()
        viewModelScope.launch {
            // Start wake listening as soon as the model is unpacked, if we're idle.
            wake.status.collect { if (it == WakeWord.Status.Ready && _ui.value.mode == Mode.Idle) goIdle() }
        }
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                val quietFor = System.currentTimeMillis() - lastActivity
                val s = _ui.value
                if (s.mode == Mode.Idle && quietFor > SESSION_SCREEN_MS) {
                    // Let the conversation screens fall back to the clock after a while.
                    if (s.cameraOpen || s.card != null || s.memoriesOpen) {
                        _ui.update { it.copy(cameraOpen = false, snapshot = null, card = null, memoriesOpen = false) }
                    }
                }
                if (quietFor > AMBIENT_AFTER_MS && s.mode == Mode.Idle && !s.ambient && s.checkIn == null) {
                    _ui.update { it.copy(ambient = true) }
                }
            }
        }
    }

    // ---- permissions & lifecycle ----

    fun onPermissions(mic: Boolean, camera: Boolean) {
        _ui.update { it.copy(micPermission = mic, cameraPermission = camera, speechAvailable = ears.available()) }
        if (_ui.value.mode == Mode.Idle) goIdle()
    }

    /** Activity stopped (screen off, another app on top): release the mic entirely. */
    fun onBackground() {
        if (engineOn && engineState.live != "idle") link.send("stop")
        ears.cancel()
        wake.stop()
        voice.stop()
        if (_ui.value.mode == Mode.Listening || _ui.value.mode == Mode.Speaking) setMode(Mode.Idle)
    }

    fun onForeground() {
        touch()
        if (_ui.value.mode == Mode.Idle) goIdle()
    }

    fun touch() {
        lastActivity = System.currentTimeMillis()
        if (_ui.value.ambient) _ui.update { it.copy(ambient = false) }
    }

    // ---- buttons ----

    fun onMicButton() {
        touch()
        if (settings.current.micMuted) {
            notice("The mic is muted. Turn it on in Memories & privacy.")
            return
        }
        if (engineOn) {
            if (engineState.live == "idle") startListening() else link.send("stop")
            return
        }
        when (_ui.value.mode) {
            Mode.Listening -> ears.stop()
            Mode.Speaking -> {
                voice.stop()
                turnJob?.cancel()
                startListening()
            }
            Mode.Thinking -> {
                // Stop: drop this turn (the brain rolls its history back).
                turnJob?.cancel()
                goIdle()
            }
            Mode.Idle -> {
                closeCamera()
                _ui.update { it.copy(checkIn = null) }
                startListening()
            }
            Mode.Looking -> Unit
        }
    }

    fun onShowButton() {
        touch()
        if (!settings.current.cameraOn) {
            notice("The camera is off. Turn it on in Memories & privacy.")
            return
        }
        if (_ui.value.mode == Mode.Thinking || _ui.value.mode == Mode.Looking) return
        if (engineOn) return sayFromScreen("Please look at what I'm holding up to the camera.")
        ears.cancel()
        voice.stop()
        turnJob?.cancel()
        turnJob = viewModelScope.launch {
            when (val result = look("what you want to show me")) {
                is LookResult.Photo -> runTurn(Turn.Spoken("", photo = result.jpeg))
                is LookResult.Unavailable -> {
                    notice("No photo: ${result.reason}.")
                    goIdle()
                }
            }
        }
    }

    /** A tap on one of Desku's buttons. */
    fun onChoice(choice: Choice) {
        touch()
        when (choice.action) {
            Choice.Action.CAMERA -> onShowButton()
            Choice.Action.LISTEN -> {
                voice.stop()
                turnJob?.cancel()
                _ui.update { it.copy(card = null) }
                startListening()
            }
            Choice.Action.SAY -> sayFromScreen(choice.label)
        }
    }

    /** Tapping a list item picks it. */
    fun onItem(item: CardItem) = sayFromScreen("Let's go with “${item.text}”.")

    /** Check-in buttons, which answer the timer's question. */
    fun onCheckInAnswer(answer: String) = sayFromScreen(answer)

    private fun sayFromScreen(words: String) {
        ears.cancel()
        voice.stop()
        turnJob?.cancel()
        _ui.update { it.copy(cameraOpen = false, snapshot = null, card = null, checkIn = null, asked = words, heard = "") }
        if (engineOn) {
            if (!link.connected) return notice(ENGINE_UNREACHABLE)
            link.send("say", "text" to words)
            return
        }
        turnJob = viewModelScope.launch { runTurn(Turn.Spoken(words)) }
    }

    fun closeCamera() {
        _look.value?.result?.complete(LookResult.Unavailable("the user closed the camera"))
        _ui.update { it.copy(cameraOpen = false, snapshot = null, countdown = 0, card = if (it.snapshot != null) null else it.card) }
    }

    fun onCountdown(n: Int) = _ui.update { it.copy(countdown = n) }

    fun closePage() {
        touch()
        _ui.update { it.copy(page = null) }
    }

    fun openMemories() {
        touch()
        _ui.update { it.copy(memoriesOpen = true) }
    }

    fun closeMemories() {
        touch()
        _ui.update { it.copy(memoriesOpen = false) }
    }

    fun toggleMute() {
        touch()
        settings.update { it.copy(micMuted = !it.micMuted) }
        if (engineOn) {
            link.send(if (settings.current.micMuted) "mute" else "unmute")
            if (settings.current.micMuted) audio.stopMic() else if (audioRunning) startEngineMic()
            if (settings.current.micMuted) _ui.update { it.copy(level = 0f) }
        }
        if (settings.current.micMuted) {
            ears.cancel()
            wake.stop()
            if (_ui.value.mode == Mode.Listening) setMode(Mode.Idle)
        } else if (_ui.value.mode == Mode.Idle) {
            goIdle()
        }
        notice(null)
    }

    fun toggleCamera() {
        touch()
        settings.update { it.copy(cameraOn = !it.cameraOn) }
        if (engineOn) link.send("camera", "enabled" to settings.current.cameraOn)
        if (!settings.current.cameraOn) closeCamera()
        notice(null)
    }

    fun toggleWake() {
        touch()
        settings.update { it.copy(wakeWordEnabled = !it.wakeWordEnabled) }
        if (!settings.current.wakeWordEnabled) wake.stop() else if (_ui.value.mode == Mode.Idle) goIdle()
    }

    fun deleteMemory(id: String) {
        touch()
        if (engineOn) link.send("forget", "id" to id) else memory.delete(id)
    }

    fun clearMemories() {
        touch()
        if (engineOn) link.send("forget_all") else memory.clear()
    }

    fun stopTimer() {
        touch()
        if (engineOn) sayFromScreen("Cancel my focus check-in, please.") else cancelFocusTimer()
    }

    fun saveSettings(
        apiKey: String, model: String, wakeWordEnabled: Boolean, frontCamera: Boolean,
        serverUrl: String = settings.current.serverUrl, deviceToken: String = settings.current.deviceToken,
    ) {
        touch()
        settings.update {
            it.copy(apiKey = apiKey, model = model, wakeWordEnabled = wakeWordEnabled, frontCamera = frontCamera, serverUrl = serverUrl, deviceToken = deviceToken)
        }
        connectEngine()
        brain.endConversation()
        if (!wakeWordEnabled) wake.stop()
        if (_ui.value.mode == Mode.Idle) goIdle()
        notice(null)
    }

    fun newConversation() {
        brain.endConversation()
        _ui.update { it.copy(heard = "", asked = "", said = "", card = null, remembered = null) }
    }

    // ---- the conversation loop ----

    private fun startListening(followUp: Boolean = false) {
        val s = _ui.value
        if (settings.current.micMuted) return goIdle()
        if (!s.micPermission) {
            notice("I need microphone permission to hear you.")
            return goIdle()
        }
        if (engineOn) {
            // The engine's voice session does the listening; just ask it to open.
            if (!link.connected) return notice(ENGINE_UNREACHABLE)
            if (engineState.live == "idle") {
                if (!followUp) voice.chimeListening()
                _ui.update { it.copy(heard = "", notice = null, ambient = false, checkIn = null) }
                link.send("start")
            }
            return
        }
        if (!s.speechAvailable) {
            notice("This phone has no speech recognizer. Install or enable the Google app to talk to me.")
            return goIdle()
        }
        wake.stop()
        if (!followUp) voice.chimeListening()
        _ui.update { it.copy(mode = Mode.Listening, heard = "", level = 0f, notice = null, ambient = false) }
        ears.listen { event ->
            when (event) {
                is Ears.Event.Partial -> _ui.update { it.copy(heard = event.text) }
                is Ears.Event.Level -> _ui.update { it.copy(level = event.level) }
                is Ears.Event.Final -> {
                    // A new spoken turn moves on from the last screen's photo and buttons.
                    _ui.update { it.copy(heard = "", asked = event.text, level = 0f, card = null, checkIn = null, cameraOpen = false, snapshot = null) }
                    turnJob = viewModelScope.launch { runTurn(Turn.Spoken(event.text)) }
                }
                Ears.Event.NothingHeard -> {
                    if (!followUp) notice("I didn't catch that.")
                    goIdle()
                }
                is Ears.Event.Failed -> {
                    notice(event.message)
                    goIdle()
                }
            }
        }
    }

    private suspend fun runTurn(turn: Turn) {
        touch()
        setMode(Mode.Thinking)
        try {
            val reply = brain.respond(turn)
            val remembered = reply.actions.firstOrNull { it.startsWith("Remembered: ") }?.removePrefix("Remembered: ")
            _ui.update { it.copy(card = reply.card, remembered = remembered) }
            debugLog("REPLY ${reply.speech} | actions=${reply.actions} | card=${reply.card}")
            speak(reply.speech)
            debugLog("SPOKE")
            // Leave the mic open briefly for a follow-up, like a real conversation.
            if (settings.current.micMuted) goIdle() else withContext(Dispatchers.Main) { startListening(followUp = true) }
        } catch (e: BrainException) {
            debugLog("ERROR ${e.userMessage} (${e.cause})")
            _ui.update { it.copy(card = null, remembered = null) }
            speak(e.userMessage)
            debugLog("SPOKE")
            goIdle()
        }
    }

    private suspend fun speak(text: String) {
        withContext(Dispatchers.Main) { wake.stop() }
        _ui.update { it.copy(mode = Mode.Speaking, said = text, saidAt = System.currentTimeMillis()) }
        voice.say(text)
    }

    private fun goIdle() {
        viewModelScope.launch(Dispatchers.Main) {
            setMode(Mode.Idle)
            val p = settings.current
            if (audioRunning) return@launch // the voice session holds the mic
            if (p.wakeWordEnabled && !p.micMuted && _ui.value.micPermission) wake.start { onWakePhrase() }
        }
    }

    fun onWakePhrase() {
        touch()
        if (_ui.value.mode == Mode.Idle) {
            _ui.update { it.copy(memoriesOpen = false) }
            startListening()
        }
    }

    private fun setMode(mode: Mode) = _ui.update { it.copy(mode = mode, level = 0f) }

    private fun notice(text: String?) = _ui.update { it.copy(notice = text) }

    // ---- BrainHost: what Claude may ask of the phone ----

    override fun cameraOn(): Boolean = settings.current.cameraOn && _ui.value.cameraPermission

    override suspend fun look(reason: String): LookResult {
        if (!settings.current.cameraOn) return LookResult.Unavailable("the camera is turned off with the privacy switch")
        if (!_ui.value.cameraPermission) return LookResult.Unavailable("camera permission wasn't granted")
        // The always-on camera answers instantly: no countdown, no preview, just the photo.
        if (eyes.running.value && debugPhoto == null) {
            _ui.update { it.copy(cameraOpen = true, snapshot = null, card = null, memoriesOpen = false, countdown = 0) }
            val jpeg = eyes.snapshot()
            return if (jpeg != null) {
                _ui.update { it.copy(snapshot = jpeg) }
                LookResult.Photo(jpeg)
            } else {
                _ui.update { it.copy(cameraOpen = false) }
                LookResult.Unavailable("the camera didn't return a photo")
            }
        }
        _ui.update { it.copy(cameraOpen = true, snapshot = null, card = null, memoriesOpen = false, countdown = 3) }
        debugPhoto?.let { f ->
            debugPhoto = null
            val bytes = f.readBytes()
            _ui.update { it.copy(snapshot = bytes, countdown = 0) }
            return LookResult.Photo(bytes)
        }
        val request = LookRequest(reason)
        _look.value = request
        setMode(Mode.Looking)
        val result = withTimeoutOrNull(25_000) { request.result.await() }
            ?: LookResult.Unavailable("the camera didn't respond")
        _look.value = null
        when (result) {
            is LookResult.Photo -> _ui.update { it.copy(snapshot = result.jpeg, countdown = 0) }
            is LookResult.Unavailable -> _ui.update { it.copy(cameraOpen = false, countdown = 0) }
        }
        setMode(Mode.Thinking)
        return result
    }

    override suspend fun sayBeforeTool(words: String) {
        _ui.update { it.copy(said = words, saidAt = System.currentTimeMillis()) }
        voice.sayAndContinue(words)
    }

    override fun timerStatus(): String? = _timer.value?.let { t ->
        val left = ((t.endsAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        "focus timer: $left min left of ${t.minutes} on '${t.task}'"
    }

    override fun startFocusTimer(minutes: Int, task: String): String {
        val timer = FocusTimer(task, minutes, System.currentTimeMillis() + minutes * 60_000L)
        startTimer(timer)
        return "Timer started: $minutes minutes on '$task'. The phone will chime and you'll get an automatic note when it ends."
    }

    override fun cancelFocusTimer(): String {
        val had = _timer.value
        timerJob?.cancel()
        _timer.value = null
        return if (had != null) "Cancelled the timer for '${had.task}'." else "No timer was running."
    }

    private fun startTimer(timer: FocusTimer) {
        timerJob?.cancel()
        _timer.value = timer
        timerJob = viewModelScope.launch {
            delay((timer.endsAt - System.currentTimeMillis()).coerceAtLeast(0))
            _timer.value = null
            touch()
            voice.chimeTimer()
            // Let whatever is happening finish, then check in.
            while (_ui.value.mode == Mode.Thinking || _ui.value.mode == Mode.Speaking || _ui.value.mode == Mode.Looking) delay(300)
            ears.cancel()
            _ui.update { it.copy(checkIn = CheckIn(timer.task, timer.minutes), cameraOpen = false, snapshot = null, card = null, memoriesOpen = false) }
            try {
                setMode(Mode.Thinking)
                val reply = brain.respond(Turn.Event("Focus timer finished: ${timer.minutes} minutes on '${timer.task}'."))
                speak(reply.speech)
            } catch (e: BrainException) {
                speak("How's ${timer.task} going?")
            }
            if (settings.current.micMuted) goIdle() else startListening(followUp = true)
        }
    }

    // ---- debug hooks (debug builds only; see MainActivity) ----

    fun debugSay(text: String) {
        touch()
        if (engineOn) return sayFromScreen(text)
        ears.cancel()
        voice.stop()
        _ui.update { it.copy(heard = "", asked = text, card = null, checkIn = null) }
        turnJob?.cancel()
        turnJob = viewModelScope.launch { runTurn(Turn.Spoken(text)) }
    }

    fun debugPhoto(file: File) {
        debugPhoto = file
        notice("Next photo will be ${file.name} (debug).")
    }

    fun debugTimer(seconds: Int, task: String) {
        startTimer(FocusTimer(task, maxOf(1, seconds / 60), System.currentTimeMillis() + seconds * 1000L))
    }

    /** Show a screen state without a brain: a reply (optionally with a card JSON) or a live transcript. */
    fun debugScreen(mode: String, text: String, cardJson: String?, asked: String?, photo: File?) {
        touch()
        val card = cardJson?.let { parseDebugCard(it) }
        when (mode) {
            "listening" -> _ui.update { it.copy(mode = Mode.Listening, heard = text, card = null, checkIn = null, cameraOpen = false) }
            "thinking" -> _ui.update { it.copy(mode = Mode.Thinking, asked = text) }
            "camera" -> _ui.update {
                it.copy(mode = Mode.Speaking, cameraOpen = true, snapshot = photo?.readBytes(), said = text, saidAt = System.currentTimeMillis(), card = card)
            }
            "checkin" -> _ui.update {
                it.copy(
                    mode = Mode.Speaking, checkIn = CheckIn(text.ifBlank { "the Q3 deck" }, 45), said = asked ?: "How's the deck going?",
                    saidAt = System.currentTimeMillis(), cameraOpen = false, snapshot = null, card = null, memoriesOpen = false,
                )
            }
            "memories" -> _ui.update { it.copy(memoriesOpen = true, cameraOpen = false, checkIn = null) }
            "page" -> _ui.update { it.copy(page = DeskPage(text.ifBlank { "Page" }, asked ?: "http://localhost:8787/")) }
            "idle" -> _ui.update { UiState(micPermission = it.micPermission, cameraPermission = it.cameraPermission, speechAvailable = it.speechAvailable) }
            else -> _ui.update {
                it.copy(mode = Mode.Speaking, said = text, saidAt = System.currentTimeMillis(), card = card, asked = asked ?: it.asked, cameraOpen = false, snapshot = null)
            }
        }
    }

    private fun parseDebugCard(json: String): ScreenCard? = runCatching {
        val o = JSONObject(json)
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { CardItem(it.getString("text"), it.optString("note")) } } }.orEmpty()
        val choices = o.optJSONArray("choices")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).let { Choice(it.getString("label"), Choice.Action.valueOf(it.optString("action", "say").uppercase())) }
            }
        }.orEmpty()
        ScreenCard(o.optString("heading"), items, o.optInt("highlight", -1), choices)
    }.getOrNull()

    /** Turn transcripts go to logcat only in debug builds (scripts/demo.sh waits on them). */
    private fun debugLog(line: String) {
        if (BuildConfig.DEBUG) Log.i("DeskBuddy", line)
    }

    override fun onCleared() {
        eyes.shutdown()
        link.disconnect()
        audio.stop()
        ears.cancel()
        wake.shutdown()
        voice.shutdown()
    }

    // ---- engine mode internals ----

    private fun connectEngine() {
        link.disconnect()
        val p = settings.current
        if (p.serverUrl.isBlank()) return
        link.connect(p.serverUrl, p.deviceToken)
    }

    private fun startEngineMic() {
        if (settings.current.micMuted || !_ui.value.micPermission) return
        audio.startMic { buf, n, level ->
            if (engineState.live == "open" && !settings.current.micMuted) link.sendAudio(buf, n)
            // Always pass the mic level on: the voice glow reacts to you even while Desku talks.
            _ui.update { it.copy(level = level) }
        }
    }

    private var lastSpeakingAt = 0L
    private var voicePlayhead = 0L
    private val voiceSchedule = ArrayDeque<Triple<Long, Long, Float>>()

    private fun refreshEngineMode() {
        // Loudness of whatever is playing right now; "talking" means audible speech, not just
        // a playing stream (it can carry silence).
        val nowMs = SystemClock.elapsedRealtime()
        val heard = synchronized(voiceSchedule) {
            while (voiceSchedule.isNotEmpty() && voiceSchedule.first().second < nowMs) voiceSchedule.removeFirst()
            voiceSchedule.firstOrNull()?.takeIf { it.first <= nowMs }?.third ?: 0f
        }
        val smoothed = _ui.value.voiceLevel * .45f + heard * .55f
        if (kotlin.math.abs(smoothed - _ui.value.voiceLevel) > .01f) _ui.update { it.copy(voiceLevel = if (smoothed < .02f) 0f else smoothed) }
        if (audio.speaking && heard > .04f) lastSpeakingAt = nowMs
        // Streamed audio arrives with small gaps; don't flip back to listening (and redraw the
        // whole screen) until Desku has really been quiet for a moment.
        val talking = SystemClock.elapsedRealtime() - lastSpeakingAt < SPEAKING_HOLD_MS
        val mode = when {
            _ui.value.mode == Mode.Looking -> Mode.Looking
            talking -> Mode.Speaking
            engineState.thinking -> Mode.Thinking
            else -> Mode.Listening
        }
        if (_ui.value.mode != mode) _ui.update { it.copy(mode = mode) }
    }

    private inner class EngineEvents : EngineLink.Listener {
        override fun onConnected(connected: Boolean) {
            if (connected) {
                notice(null)
                link.send(if (settings.current.micMuted) "mute" else "unmute")
                link.send("camera", "enabled" to settings.current.cameraOn)
                sentPresence?.let { link.send("presence", "present" to it) }
            } else {
                if (audioRunning) onState(EngineState())
                notice("Reconnecting to the Desku engine…")
            }
        }

        override fun onState(state: EngineState) {
            engineState = state
            viewModelScope.launch(Dispatchers.Main) {
                if (state.live != "idle" && !audioRunning) {
                    // A voice session opened (Talk, the wake phrase, or a check-in from the engine).
                    wake.stop()
                    ears.cancel()
                    voice.stop()
                    audioRunning = true
                    lastRole = ""
                    audio.start()
                    startEngineMic()
                    touch()
                } else if (state.live == "idle" && audioRunning) {
                    audioRunning = false
                    audio.stop()
                    goIdle()
                }
                if (audioRunning) refreshEngineMode()
            }
        }

        override fun onTranscript(role: String, delta: String) {
            touch()
            if (role != lastRole) {
                lastRole = role
                if (role == "user") _ui.update { it.copy(heard = delta.trimStart(), card = null) }
                else _ui.update { it.copy(asked = it.heard.ifBlank { it.asked }, heard = "", said = delta.trimStart(), saidAt = System.currentTimeMillis()) }
            } else if (role == "user") {
                _ui.update { it.copy(heard = it.heard + delta) }
            } else {
                _ui.update { it.copy(said = it.said + delta) }
            }
        }

        override fun onDesk(desk: JSONObject) {
            val list = desk.optJSONArray("memories")
            engineMemories.value = (0 until (list?.length() ?: 0)).map { i ->
                list!!.getJSONObject(i).let { Memory(it.getString("id"), it.getString("text"), it.optLong("createdAt")) }
            }
            // The engine's focus check-in drives the timer chip.
            val focus = desk.optJSONObject("focus")
            val checkins = desk.optJSONArray("checkins")
            val next = (0 until (checkins?.length() ?: 0)).map { checkins!!.getJSONObject(it) }
                .filter { it.optString("about").startsWith(FOCUS_PREFIX) }
                .minByOrNull { it.optLong("dueAt") }
            _timer.value = if (focus != null && next != null) {
                val minutes = ((next.optLong("dueAt") - focus.optLong("startedAt")) / 60_000L).toInt().coerceAtLeast(1)
                lastFocusMinutes = minutes
                FocusTimer(next.optString("about").removePrefix(FOCUS_PREFIX), minutes, next.optLong("dueAt"))
            } else null
        }

        override fun onCapture(requestId: String, countdownSeconds: Int) {
            viewModelScope.launch {
                when (val r = look("Desku asked to look")) {
                    is LookResult.Photo -> {
                        link.send("photo", "requestId" to requestId, "image" to android.util.Base64.encodeToString(r.jpeg, android.util.Base64.NO_WRAP))
                        // A glance, not a photo session: show what Desku saw briefly, then close.
                        delay(GLANCE_SHOW_MS)
                        if (_ui.value.snapshot === r.jpeg) closeCamera()
                    }
                    is LookResult.Unavailable -> link.send("photo_error", "requestId" to requestId, "reason" to r.reason)
                }
            }
        }

        override fun onCheckin(about: String) {
            touch()
            voice.chimeTimer()
            _ui.update {
                it.copy(checkIn = CheckIn(about.removePrefix(FOCUS_PREFIX), lastFocusMinutes), cameraOpen = false, snapshot = null, card = null, memoriesOpen = false, ambient = false)
            }
        }

        override fun onCalling(about: String) = notice("You weren't at the desk, so I'm calling your phone.")

        override fun onLink(url: String, label: String) {
            // Plain text isn't tappable on the kiosk, so open the link right away to approve it here.
            notice("$label: opening the browser… Approve it there, then come back and ask again.")
            runCatching {
                getApplication<Application>().startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { notice("$label: $url") }
        }

        override fun onError(message: String) = notice(message)

        override fun onAudio(pcm: ByteArray) {
            if (audioRunning) {
                audio.play(pcm)
                // Loudness of this chunk of Desku's voice (16-bit little-endian PCM), smoothed.
                var sum = 0.0
                var n = 0
                var i = 0
                while (i + 1 < pcm.size) {
                    val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                    sum += v * v
                    n++
                    i += 8 // every 4th sample is plenty
                }
                val rms = if (n == 0) 0.0 else kotlin.math.sqrt(sum / n) / 32768.0
                val level = (rms * 5).toFloat().coerceIn(0f, 1f)
                // Chunks arrive ahead of playback: file this one's loudness under the time it
                // will actually be heard, so the mouth moves with the sound, not the network.
                val now = SystemClock.elapsedRealtime()
                val start = maxOf(voicePlayhead, now)
                voicePlayhead = start + pcm.size / PLAYBACK_BYTES_PER_MS
                synchronized(voiceSchedule) { voiceSchedule.addLast(Triple(start, voicePlayhead, level)) }
            }
        }

        override fun onPage(title: String, url: String) {
            touch()
            // Shown in-app (the phone has no browser-friendly internet; the engine serves the page).
            _ui.update { it.copy(page = DeskPage(title, url), memoriesOpen = false, ambient = false) }
        }

        override fun onControl(action: String) {
            viewModelScope.launch(Dispatchers.Main) {
                touch()
                when (action) {
                    "camera_on" -> settings.update { it.copy(cameraOn = true) }
                    "camera_off" -> {
                        settings.update { it.copy(cameraOn = false) }
                        closeCamera()
                    }
                    "close_camera" -> closeCamera()
                    "mute_mic" -> {
                        settings.update { it.copy(micMuted = true) }
                        audio.stopMic()
                        notice("Desku muted the mic. Tap the mic switch to turn it back on.")
                    }
                    "show_memories" -> openMemories()
                    "hide_memories" -> closeMemories()
                    // The engine closes the voice session itself once Desku's goodbye is spoken.
                    "end_conversation" -> Unit
                }
            }
        }
    }

    companion object {
        private const val ENGINE_UNREACHABLE = "I can't reach the Desku engine. Check the server address in Settings."
        private const val FOCUS_PREFIX = "Focus check-in: "
        private const val GLANCE_SHOW_MS = 3_500L
        private const val AMBIENT_AFTER_MS = 3 * 60_000L
        private const val SPEAKING_HOLD_MS = 500L
        /** Engine audio is 24 kHz mono 16-bit (LiveAudio's playback format). */
        private const val PLAYBACK_BYTES_PER_MS = 48
        private const val SESSION_SCREEN_MS = 90_000L
    }
}
