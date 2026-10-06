package com.deskbuddy.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.deskbuddy.camera.Presence
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskbuddy.CompanionViewModel
import com.deskbuddy.FocusTimer
import com.deskbuddy.Mode
import com.deskbuddy.Prefs
import com.deskbuddy.UiState
import com.deskbuddy.brain.CardItem
import com.deskbuddy.brain.Choice
import com.deskbuddy.brain.ScreenCard
import com.deskbuddy.camera.CameraFeed
import com.deskbuddy.memory.Memory
import com.deskbuddy.voice.WakeWord
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class Screen { Idle, Conversation, Camera, CheckIn, Memories }

/** "Camera on" while the hidden camera runs, so it's said on every screen; null when it's off. */
private val LocalCameraBadge = compositionLocalOf<String?> { null }

/** The mic switch, reachable from every screen's top row: one tap mutes, one tap unmutes. */
private class MicControl(val muted: Boolean, val toggle: () -> Unit)
private val LocalMic = compositionLocalOf<MicControl?> { null }

/** One app, six screens (design 1a Nightlight): the screen follows what Desku is doing. */
@Composable
fun DeskuApp(vm: CompanionViewModel, onPin: () -> Unit, kiosk: KioskHooks? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.settings.prefs.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val timer by vm.timer.collectAsStateWithLifecycle()
    val look by vm.look.collectAsStateWithLifecycle()
    val wake by vm.wake.status.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }
    // In the locked-down kiosk, settings sit behind the admin PIN.
    val locked = kiosk?.active?.invoke() == true
    val openSettings = { if (locked) showAdmin = true else showSettings = true }
    val adminAction = rememberUpdatedState { if (kiosk != null && locked) showAdmin = true else showSettings = true }
    // Stable across recompositions (the clock ticks every second), so a 5 s hold isn't reset.
    val onAdmin = remember { { adminAction.value() } }
    val now = rememberNow()

    val presence by vm.eyes.presence.collectAsStateWithLifecycle()
    val eyesOn by vm.eyes.running.collectAsStateWithLifecycle()
    val mode = rememberSteadyMode(ui.mode)

    // The always-on camera runs while Desku is on screen and the camera switch is on.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(prefs.cameraOn, ui.cameraPermission, prefs.frontCamera, owner) {
        if (prefs.cameraOn && ui.cameraPermission) vm.eyes.start(owner, prefs.frontCamera) else vm.eyes.stop()
        onDispose { vm.eyes.stop() }
    }

    val recentReply = ui.said.isNotBlank() && now - ui.saidAt < 60_000
    val screen = when {
        ui.memoriesOpen -> Screen.Memories
        ui.cameraOpen -> Screen.Camera
        ui.checkIn != null -> Screen.CheckIn
        // Listening, thinking and talking share one screen, so nothing jumps between turns.
        mode == Mode.Listening || mode == Mode.Thinking || mode == Mode.Speaking || recentReply -> Screen.Conversation
        else -> Screen.Idle
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (ui.ambient) Color.Black else Night.Bg)
            .adminHold(onAdmin)
            .windowInsetsPadding(WindowInsets.displayCutout),
    ) {
        // Voice glow along the bottom, behind everything: your voice while you talk, Desku's
        // while he talks, a sweeping beam while he thinks.
        VoiceBeam(
            level = if (prefs.micMuted) ui.voiceLevel else maxOf(ui.level, ui.voiceLevel),
            processing = mode == Mode.Thinking,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp),
        )
        CompositionLocalProvider(
            LocalCameraBadge provides if (eyesOn) (if (presence.present) "Camera on · sees you" else "Camera on") else null,
            LocalMic provides MicControl(prefs.micMuted, vm::toggleMute),
        ) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = { fadeIn(tween(280, delayMillis = 60)) togetherWith fadeOut(tween(180)) },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.Idle -> IdleScreen(ui, prefs, wake, timer, memories.size, now, presence, eyesOn, vm, onSettings = openSettings)
                Screen.Conversation -> ConversationScreen(ui, mode, prefs, memories.size, now, presence, vm)
                Screen.Camera -> CameraScreen(ui, prefs, look, vm)
                Screen.CheckIn -> CheckInScreen(ui, memories.lastOrNull(), now, vm)
                Screen.Memories -> MemoriesScreen(ui, prefs, wake, memories, eyesOn, vm, onSettings = openSettings)
            }
        }
        }
        ui.page?.let { page -> PageOverlay(page, ui, mode, vm::closePage) }
    }

    if (showAdmin && kiosk != null) {
        AdminDialog(kiosk, onDismiss = { showAdmin = false }, onOpenSettings = { showAdmin = false; showSettings = true })
    }
    if (showSettings) {
        SettingsDialog(
            prefs = prefs,
            wake = wake,
            onDismiss = { showSettings = false },
            onSave = { key, model, wakeOn, front, server, token ->
                vm.saveSettings(key, model, wakeOn, front, server, token)
                showSettings = false
            },
            onPin = { showSettings = false; onPin() },
            onNewConversation = { vm.newConversation(); showSettings = false },
        )
    }
}

@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The mode as shown. Streamed speech has tiny gaps and the mic flips on and off between words;
 * leaving "talking" waits a beat so the screen doesn't flicker. Everything else shows at once.
 */
@Composable
private fun rememberSteadyMode(mode: Mode): Mode {
    var shown by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (shown == Mode.Speaking && mode != Mode.Speaking) delay(450)
        if (shown == Mode.Listening && mode == Mode.Idle) delay(300)
        shown = mode
    }
    return shown
}

/**
 * Hidden admin gesture: hold the top edge of the screen (the status row) for 5 seconds. It only
 * watches touches and never consumes them, so the chips and buttons up there keep working.
 */
private fun Modifier.adminHold(onAdmin: () -> Unit): Modifier = pointerInput(Unit) {
    val edge = 120.dp.toPx()
    val slop = 40.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.y > edge) return@awaitEachGesture
        val letGo = withTimeoutOrNull(5_000) {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.changes.none { it.pressed }) break
                if (e.changes.any { (it.position - down.position).getDistance() > slop }) break
            }
            true
        }
        if (letGo == null) onAdmin()
    }
}

private fun clock(t: Long) = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date(t))
private fun longDate(t: Long) = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date(t))

// =================================================================== 01 Idle

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IdleScreen(
    ui: UiState, prefs: Prefs, wake: WakeWord.Status, timer: FocusTimer?, memoryCount: Int, now: Long,
    presence: Presence, eyesOn: Boolean, vm: CompanionViewModel, onSettings: () -> Unit,
) {
    val hasBrain = prefs.apiKey.isNotBlank() || prefs.serverUrl.isNotBlank()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = onSettings)) {
                Text(clock(now), style = fig(52.sp, 300, spacing = (-1.5).sp, line = 52.sp))
                Spacer(Modifier.height(8.dp))
                Text(longDate(now), style = fig(16.sp, color = Night.Sub))
            }
            Column(Modifier.padding(top = 4.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicChip(prefs, vm::toggleMute)
                CameraChip(prefs, ui, eyesOn, presence, vm::toggleCamera)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Dozing when nobody's there; awake and watching you when you are.
            Desku(
                when {
                    prefs.micMuted -> Expression.Muted
                    presence.present -> Expression.Listening
                    else -> Expression.Dozing
                },
                200.dp,
                rings = false,
                lookAt = if (presence.present) presence.x else null,
            )
            Spacer(Modifier.height(32.dp))
            Text(
                when {
                    !hasBrain -> "Desku needs a brain"
                    prefs.micMuted -> "Desku can't hear you"
                    presence.present -> "Hi! Tap to talk to Desku"
                    else -> "Tap to talk to Desku"
                },
                style = fig(24.sp, 500), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            when {
                !hasBrain -> Text("Long-press the clock to add an API key.", style = fig(16.sp, color = Night.Sub), textAlign = TextAlign.Center)
                else -> DashedPill(wakeLabel(prefs, wake, ui))
            }
            timer?.let {
                Spacer(Modifier.height(14.dp))
                FocusPill(it, now, vm::stopTimer)
            }
            ui.notice?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = fig(16.sp, color = Night.ChipText), textAlign = TextAlign.Center)
            }
        }
        Dock(ui, prefs, memoryCount, vm)
    }
}

private fun wakeLabel(prefs: Prefs, wake: WakeWord.Status, ui: UiState) = when {
    prefs.micMuted -> "Mic muted · nothing is listening"
    !ui.micPermission -> "Microphone permission needed"
    !prefs.wakeWordEnabled -> "“Hey Desku” wake phrase · off"
    wake == WakeWord.Status.Listening -> "“Hey Desku” wake phrase · on-device"
    wake == WakeWord.Status.Loading -> "“Hey Desku” wake phrase · getting ready"
    wake == WakeWord.Status.NotInstalled -> "“Hey Desku” wake phrase · not installed"
    wake == WakeWord.Status.Failed -> "“Hey Desku” wake phrase · unavailable"
    else -> "“Hey Desku” wake phrase · paused"
}

@Composable
private fun MicChip(prefs: Prefs, onClick: () -> Unit) =
    if (prefs.micMuted) StatusChip(Night.Warn, "Mic muted", onClick) else StatusChip(Night.Green, "Mic on", onClick)

@Composable
private fun CameraChip(prefs: Prefs, ui: UiState, eyesOn: Boolean, presence: Presence, onClick: () -> Unit) = when {
    !prefs.cameraOn -> StatusChip(Night.Warn, "Camera off", onClick)
    eyesOn && presence.present -> StatusChip(Night.Accent, "Camera on · sees you", onClick)
    eyesOn || (ui.cameraOpen && ui.snapshot == null) -> StatusChip(Night.Accent, "Camera on", onClick)
    else -> StatusChip(Night.Off, "Camera idle", onClick)
}

@Composable
private fun FocusPill(timer: FocusTimer, now: Long, onStop: () -> Unit) {
    val left = ((timer.endsAt - now) / 1000).coerceAtLeast(0)
    Row(
        Modifier.background(Night.Chip, CircleShape).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Night.Accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            "Focusing on ${timer.task} · %d:%02d".format(left / 60, left % 60),
            style = fig(15.sp, color = Night.ChipText), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 260.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
            Text("✕", style = fig(14.sp, color = Night.Sub))
        }
    }
}

// =================================================================== 02/03 Conversation (listening, thinking, talking)

/**
 * One layout for the whole conversation. Desku stays put and only his face changes; your words
 * and his reply live in fixed slots at a fixed size, so streaming text and turn changes never
 * make the screen jump.
 */
@Composable
private fun ConversationScreen(ui: UiState, mode: Mode, prefs: Prefs, memoryCount: Int, now: Long, presence: Presence, vm: CompanionViewModel) {
    val speaking = mode == Mode.Speaking
    val thinking = mode == Mode.Thinking
    val listening = mode == Mode.Listening
    Column(Modifier.fillMaxSize()) {
        StatusRow(now) {
            Crossfade(mode, label = "status") { m ->
                when (m) {
                    Mode.Speaking -> WaveLabel("Desku is talking")
                    Mode.Thinking -> DotsLabel("Thinking")
                    Mode.Listening -> BlinkLabel("Listening")
                    else -> Spacer(Modifier.height(20.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
            Desku(
                when {
                    speaking -> Expression.Talking
                    thinking -> Expression.Thinking
                    else -> Expression.Listening
                },
                210.dp,
                rings = listening,
                level = when {
                    speaking -> ui.voiceLevel
                    listening -> ui.level
                    else -> 0f
                },
                lookAt = if (presence.present) presence.x else null,
                voiceDriven = prefs.serverUrl.isNotBlank(),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ui.remembered?.let { RememberedCard(it, System.currentTimeMillis()) }
            // What Desku is hearing, live as the words arrive (even if you talk over him).
            val live = ui.heard.isNotBlank()
            Row(verticalAlignment = Alignment.Top) {
                if (live) {
                    Text("Hearing  ", style = mono(13.sp, Night.Accent, 500), modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    when {
                        live -> "\u201C${ui.heard.takeLast(160)}\u201D"
                        ui.asked.isNotBlank() -> "\u201C${ui.asked}\u201D"
                        else -> " "
                    },
                    style = fig(18.sp, if (live) 500 else 400, if (live) Night.Text else Night.Sub, line = 25.sp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            val reply = when {
                ui.said.isNotBlank() -> ui.said
                listening -> "Go ahead, I'm listening."
                else -> ""
            }
            FollowingText(
                reply,
                fig(26.sp, 500, if (ui.said.isBlank()) Night.Sub else Night.Text, spacing = (-.3).sp, line = 34.sp),
                Modifier.weight(1f).fillMaxWidth(),
            )
            ui.card?.let { card ->
                if (card.items.isNotEmpty()) ItemList(card, compact = true, onItem = vm::onItem)
                if (card.choices.isNotEmpty()) ChoiceChips(card.choices, vm::onChoice)
            }
        }
        ui.notice?.let { Text(it, style = fig(15.sp, color = Night.ChipText), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) }
        Dock(ui.copy(mode = mode), prefs, memoryCount, vm)
    }
}

/** Text that keeps its newest line in view as it grows (Desku's reply streaming in). */
@Composable
private fun FollowingText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Column(modifier.verticalScroll(scroll)) { Text(text, style = style) }
}

@Composable
private fun ChoiceChips(choices: List<Choice>, onChoice: (Choice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        choices.forEachIndexed { i, c ->
            Pill(
                c.label, primary = i == 0, onClick = { onChoice(c) }, height = 52.dp, size = 17.sp,
                leading = if (c.action == Choice.Action.CAMERA) ({ CameraGlyph(if (i == 0) Night.Ink else Night.Text, 20.dp, 15.dp, 2.5.dp, 5.dp) }) else null,
            )
        }
    }
}

@Composable
private fun RememberedCard(text: String, at: Long) {
    Row(
        Modifier.fillMaxWidth().background(Night.Chip, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Desku(Expression.Peeking, 40.dp, halo = false, animate = false, modifier = Modifier.padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text("REMEMBERED · ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at)).uppercase()}", style = mono())
            Spacer(Modifier.height(4.dp))
            Text(text, style = fig(17.sp, line = 23.sp))
        }
    }
}

// =================================================================== 04 Camera read

@Composable
private fun CameraScreen(ui: UiState, prefs: Prefs, look: com.deskbuddy.camera.LookRequest?, vm: CompanionViewModel) {
    val card = ui.card
    val live = look != null && ui.snapshot == null
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .fillMaxWidth()
                .height(if ((card?.items?.size ?: 0) > 4) 280.dp else 360.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF1E242A), Color(0xFF191F24)))),
        ) {
            when {
                live -> CameraFeed(look!!, prefs.frontCamera, vm::onCountdown, Modifier.fillMaxSize())
                ui.snapshot != null -> {
                    val bmp = remember(ui.snapshot) { BitmapFactory.decodeByteArray(ui.snapshot, 0, ui.snapshot.size)?.asImageBitmap() }
                    bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                }
            }
            // Guide frame.
            Box(
                Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 60.dp, bottom = 36.dp)
                    .border(2.dp, Night.Accent.copy(alpha = .7f), RoundedCornerShape(16.dp)),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    Modifier.background(Color.Black.copy(alpha = .45f), CircleShape).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val camOn = live || LocalCameraBadge.current != null
                    Box(Modifier.size(8.dp).background(if (camOn) Night.Accent else Night.Off, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(if (live) "Desku is looking" else if (camOn) "Camera on" else "Camera off", style = fig(14.sp))
                }
                Box(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(onClick = vm::closeCamera)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) { Text("Close", style = fig(14.sp)) }
            }
            if (live && ui.countdown > 0) {
                Text("${ui.countdown}", style = fig(96.sp, 600), modifier = Modifier.align(Alignment.Center))
            }
            Desku(Expression.Peeking, 64.dp, halo = false, animate = false, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 22.dp))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 32.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when {
                        live -> "Hold it up inside the frame"
                        card?.heading?.isNotBlank() == true -> card.heading
                        ui.mode == Mode.Thinking -> "Reading it…"
                        else -> ""
                    },
                    style = fig(16.sp, color = Night.Sub), modifier = Modifier.weight(1f),
                )
                Text(if (live) "camera on" else if (ui.snapshot != null) "snapshot · not saved" else "", style = mono())
            }
            if (card != null && card.items.isNotEmpty()) ItemList(card, compact = false, onItem = vm::onItem)
            val answered = ui.snapshot != null && ui.mode != Mode.Thinking && ui.said.isNotBlank()
            if (answered) {
                Text(ui.said, style = fig(20.sp, 500, line = 27.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ui.snapshot != null && ui.mode != Mode.Thinking) {
            val choices = card?.choices.orEmpty()
            Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (choices.isEmpty()) {
                    Pill("Show again", primary = true, onClick = vm::onShowButton, modifier = Modifier.weight(1f))
                    Pill("Done", primary = false, onClick = vm::closeCamera, modifier = Modifier.weight(1f))
                } else {
                    choices.take(2).forEachIndexed { i, c ->
                        Pill(c.label, primary = i == 0, onClick = { vm.onChoice(c) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        } else {
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun ItemList(card: ScreenCard, compact: Boolean, onItem: (CardItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        card.items.take(if (compact) 4 else 6).forEachIndexed { i, item ->
            val selected = i == card.highlight
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (selected) Night.Raised else Night.Chip)
                    .then(if (selected) Modifier.border(1.5.dp, Night.Accent, RoundedCornerShape(16.dp)) else Modifier)
                    .clickable { onItem(item) }
                    .padding(horizontal = 16.dp, vertical = if (selected) 14.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Box(Modifier.size(22.dp).background(Night.Accent, CircleShape))
                } else {
                    Box(Modifier.size(22.dp).border(2.dp, Night.Dash, CircleShape))
                }
                Spacer(Modifier.width(14.dp))
                Text(item.text, style = fig(18.sp, if (selected) 500 else 400), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.note.isNotBlank()) Text(item.note, style = fig(13.sp, 600, if (selected) Night.Accent else Night.Sub))
            }
        }
    }
}

// =================================================================== 05 Remembered + timer check-in

@Composable
private fun CheckInScreen(ui: UiState, latest: Memory?, now: Long, vm: CompanionViewModel) {
    val check = ui.checkIn ?: return
    Column(Modifier.fillMaxSize()) {
        StatusRow(now) { Text("Check-in", style = fig(16.sp, color = Night.Sub)) }
        latest?.let {
            Box(Modifier.padding(start = 32.dp, end = 32.dp, top = 28.dp)) { RememberedCard(it.text, it.createdAt) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Night.Accent, radius = size.minDimension / 2 - 5.dp.toPx(), style = Stroke(10.dp.toPx()))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${check.minutes}:00", style = fig(44.sp, 300, spacing = (-1).sp, line = 44.sp))
                    Spacer(Modifier.height(4.dp))
                    Text("minutes in", style = fig(14.sp, color = Night.Sub))
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                if (ui.mode == Mode.Thinking || ui.said.isBlank()) "How's ${check.task} going?" else ui.said,
                style = fig(30.sp, 500, spacing = (-.3).sp, line = 37.sp), textAlign = TextAlign.Center,
                maxLines = 4, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Text("I'll be quiet till you answer.", style = fig(16.sp, color = Night.Sub), textAlign = TextAlign.Center)
        }
        Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Done — what's next?", primary = true, onClick = { vm.onCheckInAnswer("Done — what's next?") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("10 more minutes", primary = false, onClick = { vm.onCheckInAnswer("10 more minutes, please.") }, modifier = Modifier.weight(1f), height = 60.dp, size = 17.sp)
                Pill("Switch task", primary = false, onClick = { vm.onCheckInAnswer("I want to switch task.") }, modifier = Modifier.weight(1f), height = 60.dp, size = 17.sp)
            }
        }
    }
}

// =================================================================== 06 Memories & privacy

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoriesScreen(
    ui: UiState, prefs: Prefs, wake: WakeWord.Status, allMemories: List<Memory>, eyesOn: Boolean, vm: CompanionViewModel, onSettings: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    // Forget waits five seconds so a stray tap can be undone; leaving the screen commits it.
    var pendingForget by remember { mutableStateOf<Memory?>(null) }
    LaunchedEffect(pendingForget) {
        val m = pendingForget ?: return@LaunchedEffect
        delay(5_000)
        vm.deleteMemory(m.id)
        pendingForget = null
    }
    DisposableEffect(Unit) { onDispose { pendingForget?.let { vm.deleteMemory(it.id) } } }
    val memories = allMemories.filter { it.id != pendingForget?.id }
    fun forget(m: Memory) {
        pendingForget?.let { vm.deleteMemory(it.id) }
        pendingForget = m
    }
    BackHandler(onBack = vm::closeMemories)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Memories", style = fig(34.sp, 600, spacing = (-.5).sp))
                Spacer(Modifier.height(4.dp))
                Text(
                    when (memories.size) {
                        0 -> "Nothing you asked me to keep"
                        1 -> "1 thing you asked me to keep"
                        else -> "${memories.size} things you asked me to keep"
                    },
                    style = fig(16.sp, color = Night.Sub),
                )
            }
            Column(
                Modifier.combinedClickable(onClick = vm::closeMemories, onLongClick = onSettings),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Desku(if (prefs.micMuted) Expression.Muted else Expression.Dozing, 64.dp, halo = false)
                Spacer(Modifier.height(4.dp))
                Text("Done", style = mono(12.sp, Night.ChipText, 500))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 8.dp),
        ) {
            if (memories.isEmpty()) {
                item {
                    Text(
                        "Say “remember that…” and I'll keep it here. I never save anything you didn't ask me to.",
                        style = fig(17.sp, color = Night.Sub, line = 24.sp),
                    )
                }
            }
            items(memories.reversed(), key = { it.id }) { m ->
                Row(
                    Modifier.fillMaxWidth().background(Night.Chip, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(m.text, style = fig(17.sp, line = 23.sp))
                        Spacer(Modifier.height(6.dp))
                        Text(memoryDate(m.createdAt), style = mono())
                    }
                    Spacer(Modifier.width(14.dp))
                    Surface(onClick = { forget(m) }, shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.5.dp, Night.Line)) {
                        Text("Forget", style = fig(15.sp, 500, Night.ChipText), modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        }
        Column(Modifier.padding(horizontal = 32.dp).fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Night.Raised))
            Spacer(Modifier.height(6.dp))
            PrivacyRow(
                "Microphone",
                if (prefs.micMuted) "Muted — Desku can't hear you" else "On — listens after “Hey Desku” or Talk",
                if (prefs.micMuted) Night.Warn else Night.Sub,
                on = !prefs.micMuted, onClick = vm::toggleMute,
            )
            PrivacyRow(
                "Camera",
                when {
                    !prefs.cameraOn -> "Off — Desku can't see you"
                    eyesOn -> "On — Desku can see you. Nothing is recorded; a photo is sent only when he needs to look."
                    else -> "On — starting…"
                },
                Night.Sub, on = prefs.cameraOn, onClick = vm::toggleCamera, divider = true,
            )
            val wakeAvailable = wake != WakeWord.Status.NotInstalled && wake != WakeWord.Status.Failed
            PrivacyRow(
                "Wake phrase “Hey Desku”",
                when {
                    !wakeAvailable -> "Not available on this phone"
                    prefs.wakeWordEnabled -> "Heard on this phone only, never recorded"
                    else -> "Off — tap Talk instead"
                },
                Night.Sub, on = prefs.wakeWordEnabled && wakeAvailable, enabled = wakeAvailable, onClick = vm::toggleWake, divider = true,
            )
        }
        pendingForget?.let { m ->
            Row(
                Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp).fillMaxWidth()
                    .background(Night.Raised, RoundedCornerShape(16.dp)).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Memory forgotten", style = fig(16.sp, color = Night.Text), modifier = Modifier.weight(1f))
                TextButton(onClick = { pendingForget = null }) { Text("Undo", style = fig(16.sp, 600, Night.Accent)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 12.dp, bottom = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ambient chatter is never recorded or saved.", style = fig(14.sp, color = Night.Sub, line = 20.sp), modifier = Modifier.weight(1f).widthIn(max = 220.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "Forget everything", style = fig(16.sp, 600, Night.Warn), softWrap = false,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = memories.isNotEmpty()) { confirmClear = true }.padding(4.dp),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Forget everything?", style = fig(20.sp, 600)) },
            text = { Text("This deletes every memory Desku was asked to keep. It can't be undone.", style = fig(16.sp, color = Night.Sub)) },
            confirmButton = { TextButton(onClick = { vm.clearMemories(); confirmClear = false }) { Text("Forget everything", style = fig(16.sp, 600, Night.Warn)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep them", style = fig(16.sp, 500)) } },
            containerColor = Night.Raised,
        )
    }
}

@Composable
private fun PrivacyRow(title: String, detail: String, detailColor: Color, on: Boolean, onClick: () -> Unit, enabled: Boolean = true, divider: Boolean = false) {
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Night.Raised))
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = fig(18.sp, 500, if (enabled) Night.Text else Night.Sub))
                Spacer(Modifier.height(2.dp))
                Text(detail, style = fig(14.sp, color = detailColor))
            }
            NightSwitch(on, enabled, onClick)
        }
    }
}

private fun memoryDate(at: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(then, today) -> "TODAY · " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(at)).uppercase()
        same(then, yesterday) -> "YESTERDAY"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(at)).uppercase()
    }
}

// =================================================================== shared

@Composable
private fun StatusRow(now: Long, right: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        val mic = LocalMic.current
        if (mic != null) {
            Row(
                Modifier.clip(CircleShape).background(if (mic.muted) Night.Warn.copy(alpha = .18f) else Night.Chip)
                    .clickable(onClick = mic.toggle).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicGlyph(if (mic.muted) Night.Warn else Night.ChipText, muted = mic.muted, scale = .5f)
                Spacer(Modifier.width(6.dp))
                Text(if (mic.muted) "Muted" else "Mic on", style = fig(14.sp, 500, if (mic.muted) Night.Warn else Night.ChipText))
            }
        } else {
            Text(clock(now), style = fig(16.sp, color = Night.Sub))
        }
        LocalCameraBadge.current?.let { badge ->
            Row(
                Modifier.background(Night.Chip, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(Night.Accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(badge, style = mono(11.sp, Night.ChipText))
            }
        }
        right()
    }
}

@Composable
private fun BlinkLabel(text: String) {
    val t = rememberInfiniteTransition(label = "blink")
    val a by t.animateFloat(1f, 0f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blinkA")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).alpha(a).background(Night.Accent, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, style = fig(16.sp, 500, Night.Accent))
    }
}

@Composable
private fun WaveLabel(text: String) {
    val t = rememberInfiniteTransition(label = "wave")
    val bars = listOf(8f, 14f, 10f).mapIndexed { i, h ->
        val s by t.animateFloat(.35f, 1f, infiniteRepeatable(tween(450, delayMillis = 0), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 150)), label = "bar$i")
        h * s
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.height(14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            bars.forEach { h -> Box(Modifier.size(3.dp, h.dp).background(Night.Accent, RoundedCornerShape(2.dp))) }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = fig(16.sp, 500, Night.Accent))
    }
}

@Composable
private fun DotsLabel(text: String) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { i ->
                val p by t.animateFloat(.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 200)), label = "d$i")
                Box(Modifier.offset(y = (-4 * (p - .3f) / .7f).dp).size(5.dp).alpha(p).background(Night.Accent, CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, style = fig(16.sp, 500, Night.Accent))
    }
}

/** Round dock: camera · Talk · memories (with count). */
@Composable
private fun Dock(ui: UiState, prefs: Prefs, memoryCount: Int, vm: CompanionViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, bottom = 40.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(Night.Chip).clickable(onClick = vm::onShowButton),
            contentAlignment = Alignment.Center,
        ) { CameraGlyph(if (prefs.cameraOn) Night.ChipText else Night.Off) }

        val stop = ui.mode == Mode.Listening || ui.mode == Mode.Thinking
        when {
            prefs.micMuted -> Box(
                Modifier.size(92.dp).clip(CircleShape).background(Night.Track).clickable(onClick = vm::onMicButton),
                contentAlignment = Alignment.Center,
            ) { MicGlyph(Night.Knob, muted = true) }
            stop -> Box(
                Modifier.size(92.dp).clip(CircleShape).background(Night.Raised).border(2.dp, Night.Accent, CircleShape).clickable(onClick = vm::onMicButton),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(26.dp).background(Night.Accent, RoundedCornerShape(6.dp))) }
            else -> Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                // Soft glow under the main button.
                Canvas(Modifier.size(130.dp)) {
                    drawCircle(Brush.radialGradient(listOf(Night.Accent.copy(alpha = .35f), Color.Transparent), center = center + Offset(0f, 12.dp.toPx()), radius = size.minDimension / 2), radius = size.minDimension / 2, center = center + Offset(0f, 12.dp.toPx()))
                }
                Box(
                    Modifier.size(92.dp).clip(CircleShape).background(Night.Accent).clickable(onClick = vm::onMicButton),
                    contentAlignment = Alignment.Center,
                ) { MicGlyph(Night.Ink) }
            }
        }

        Box(Modifier.size(68.dp)) {
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(Night.Chip).clickable(onClick = vm::openMemories),
                contentAlignment = Alignment.Center,
            ) { ListGlyph(Night.ChipText) }
            if (memoryCount > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).heightIn(min = 24.dp).widthIn(min = 24.dp)
                        .background(Night.Accent, RoundedCornerShape(12.dp)).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("$memoryCount", style = fig(13.sp, 700, Night.Ink)) }
            }
        }
    }
}
