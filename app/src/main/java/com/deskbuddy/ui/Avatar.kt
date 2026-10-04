package com.deskbuddy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.OptIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Desku's face. When `assets/avatar/` holds the rendered loops of the user's own face
 * (scripts/make-avatar.sh), Desku is that video avatar; otherwise it falls back to the drawn
 * blue Desku from the design. Same signature either way, so screens don't care which.
 */
@Composable
fun Desku(
    expression: Expression,
    width: Dp,
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    rings: Boolean = expression == Expression.Listening,
    animate: Boolean = true,
    level: Float = 0f,
    lookAt: Float? = null,
    voiceDriven: Boolean = false,
) {
    val context = LocalContext.current
    if (!AvatarVideo.available(context)) {
        DrawnDesku(expression, width, modifier, halo, rings, animate, level, lookAt, voiceDriven)
        return
    }
    // Keep the design's footprint (width × 0.854) so layouts don't shift; the face is a circle
    // as tall as that box.
    val height = width * (170f / 200f)
    val t = rememberInfiniteTransition(label = "avatar")
    val ring1 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "r1")
    val ring2 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), initialStartOffset = StartOffset(1000)), label = "r2")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val small = width < 100.dp
    val muted = expression == Expression.Muted
    val rim = when (expression) {
        Expression.Listening, Expression.Talking -> Night.Accent
        Expression.Thinking -> Night.Accent.copy(alpha = .6f)
        Expression.Muted -> Night.Off
        else -> Night.Line
    }

    Box(
        modifier.size(width, height).semantics { contentDescription = "Desku, ${expression.name.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        if (halo && !muted) {
            val glow = when (expression) {
                Expression.Listening -> .4f
                Expression.Talking -> .3f + .15f * pulse
                Expression.Dozing -> .18f
                else -> .3f
            }
            Canvas(Modifier.size(height * 1.35f)) {
                drawCircle(Brush.radialGradient(listOf(Night.Accent.copy(alpha = glow), Color.Transparent)), radius = size.minDimension / 2)
            }
        }
        if (rings && animate) {
            Canvas(Modifier.size(height)) {
                listOf(ring1, ring2).forEach { p ->
                    drawCircle(
                        Night.Accent.copy(alpha = .55f * (1f - p)),
                        radius = size.minDimension / 2 * (1f + .45f * p),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
        val face = Modifier
            .size(height)
            .clip(CircleShape)
            .border(if (small) 1.5.dp else 3.dp, rim, CircleShape)
            .background(Night.Chip)
        if (small || muted || !animate) {
            // Little faces and the muted face are stills; muted is grey, like eyes closed.
            AvatarVideo.poster(context)?.let { bmp ->
                Image(
                    bmp.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    colorFilter = if (muted) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                    modifier = face.graphicsLayer { alpha = if (muted) .7f else 1f },
                )
            }
        } else {
            Box(face) { AvatarVideoView(AvatarVideo.clipFor(expression), Modifier.fillMaxSize()) }
        }
    }
}

/** One looping ExoPlayer for the whole app, switching between the bundled clips. */
@OptIn(UnstableApi::class)
private object AvatarVideo {
    enum class Clip(val file: String) { Idle("idle.mp4"), Listening("listening.mp4"), Talking("talking.mp4"), Thinking("thinking.mp4") }

    private var checked: Boolean? = null
    private var posterBitmap: Bitmap? = null
    private var player: ExoPlayer? = null
    private var current: Clip? = null
    private var users = 0

    fun available(context: Context): Boolean = checked ?: runCatching {
        val files = context.assets.list("avatar").orEmpty().toSet()
        Clip.entries.all { it.file in files } && "poster.jpg" in files
    }.getOrDefault(false).also { checked = it }

    fun poster(context: Context): Bitmap? = posterBitmap ?: runCatching {
        context.assets.open("avatar/poster.jpg").use { BitmapFactory.decodeStream(it) }
    }.getOrNull().also { posterBitmap = it }

    fun clipFor(e: Expression) = when (e) {
        Expression.Listening -> Clip.Listening
        Expression.Talking -> Clip.Talking
        Expression.Thinking -> Clip.Thinking
        else -> Clip.Idle
    }

    fun acquire(context: Context): ExoPlayer {
        users++
        return player ?: ExoPlayer.Builder(context.applicationContext).build().apply {
            setMediaItems(Clip.entries.map { MediaItem.fromUri("asset:///avatar/${it.file}") })
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            playWhenReady = true
            prepare()
        }.also { player = it }
    }

    fun release() {
        users--
        if (users <= 0) {
            users = 0
            player?.pause()
        }
    }

    fun show(clip: Clip) {
        val p = player ?: return
        if (current != clip) {
            current = clip
            p.seekToDefaultPosition(clip.ordinal)
        }
        p.play()
    }

    fun pause() = player?.pause()
    fun resume() = player?.play()
}

@Composable
private fun AvatarVideoView(clip: AvatarVideo.Clip, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember { AvatarVideo.acquire(context) }
    var view by remember { mutableStateOf<TextureView?>(null) }
    var firstFrame by remember { mutableStateOf(false) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> AvatarVideo.pause()
                Lifecycle.Event.ON_START -> AvatarVideo.resume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
        }
        player.addListener(listener)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            view?.let { player.clearVideoTextureView(it) }
            AvatarVideo.release()
        }
    }
    LaunchedEffect(clip) { AvatarVideo.show(clip) }

    Box(modifier) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    // Clip on the view itself: Compose clipping doesn't reach into AndroidView.
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
                    }
                    clipToOutline = true
                    player.setVideoTextureView(this)
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // The still covers the gap before the first frame so the face never flashes black.
        if (!firstFrame) {
            AvatarVideo.poster(context)?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
    }
}
