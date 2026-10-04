package com.deskbuddy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.sin

/**
 * A sound-reactive glow along the bottom of the screen, after Libraries.dev's VoiceBeam
 * ("mobile" preset): colourful lobes that rise and bloom with the voice, and gather into a beam
 * that sweeps side to side while Desku is thinking.
 *
 * [level] is 0..1 (your mic while you talk, Desku's voice while he talks). Fast attack, slow
 * release, like an audio meter, so it breathes instead of twitching.
 */
@Composable
fun VoiceBeam(level: Float, processing: Boolean, modifier: Modifier = Modifier, colors: List<Color> = OCEAN) {
    val env = remember { Animatable(0f) }
    LaunchedEffect(level) {
        val target = level.coerceIn(0f, 1f)
        env.animateTo(target, tween(if (target > env.value) 70 else 420))
    }
    val busy = remember { Animatable(0f) }
    LaunchedEffect(processing) { busy.animateTo(if (processing) 1f else 0f, tween(500)) }

    val t = rememberInfiniteTransition(label = "beam")
    val flow by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "flow")
    val sweep by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sweep")

    Canvas(modifier) {
        val e = env.value
        val b = busy.value
        if (e < .01f && b < .01f) return@Canvas
        val w = size.width
        val h = size.height
        val n = colors.size
        colors.forEachIndexed { i, c ->
            // Lobes spread across the bottom, the middle ones reaching highest.
            val pos = (i + .5f) / n
            val centerWeight = 1f - kotlin.math.abs(pos - .5f) * 1.4f
            val wobble = .5f + .5f * sin(flow + i * 1.7f)
            val reach = h * (.18f + .82f * e * (.55f + .45f * wobble)) * (.6f + .4f * centerWeight)
            // While thinking, the lobes gather toward a point sweeping along the bottom.
            val beamX = w * (.5f + .38f * sin(sweep))
            val x = w * pos * (1f - b) + beamX * b + w * .03f * sin(flow * .7f + i)
            val radius = maxOf(reach, w * .22f * (1f - b) + w * .14f * b)
            val alpha = (.15f + .55f * e) * (.55f + .45f * centerWeight) + .35f * b
            drawCircle(
                Brush.radialGradient(
                    0f to c.copy(alpha = alpha.coerceAtMost(.85f)),
                    1f to Color.Transparent,
                    center = Offset(x, h + radius * .25f),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(x, h + radius * .25f),
            )
        }
    }
}

/** Desku's palette: sky blue in the middle, violet and teal toward the edges. */
private val OCEAN = listOf(
    Color(0xFF5ED3C3),
    Color(0xFF8C7BFF),
    Color(0xFF4A9BE8),
    Color(0xFF9CCBFF),
    Color(0xFF4A9BE8),
    Color(0xFF8C7BFF),
    Color(0xFF5ED3C3),
)
