package com.deskbuddy.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import kotlin.math.sqrt

/** What Desku's face is doing. States show as expressions, not spinners. */
enum class Expression { Dozing, Listening, Thinking, Talking, Muted, Peeking }

private enum class Mouth { None, Smile, Oval, Flat }

/**
 * One expression, measured in the design's own pixels at the body size it was drawn at
 * (e.g. listening is a 240×205 body). Everything scales with the body width.
 */
private class Face(
    val refW: Float, val refH: Float,
    val eyeW: Float, val eyeH: Float, val eyeR: Float, val eyeGap: Float,
    val rimOffset: Float, val rimStroke: Float, val bridgeY: Float,
    val browW: Float, val browH: Float, val browDx: Float, val browDy: Float,
    val highlight: Float = 0f, val hlX: Float = 0f, val hlY: Float = 0f,
    val stacheW: Float = 0f, val stacheH: Float = 0f, val stacheOverlap: Float = 0f,
    val mouth: Mouth = Mouth.None, val mouthW: Float = 0f, val mouthH: Float = 0f,
    val gap: Float = 0f, val marginTop: Float = 0f, val eyesDx: Float = 0f, val eyesDy: Float = 0f,
    val cheekW: Float = 0f, val cheekH: Float = 0f, val cheekX: Float = 0f, val cheekY: Float = 0f, val cheekA: Float = 0f,
)

private val faces = mapOf(
    Expression.Listening to Face(
        240f, 205f, eyeW = 22f, eyeH = 36f, eyeR = 11f, eyeGap = 44f, rimOffset = 7f, rimStroke = 2.5f, bridgeY = 13f,
        browW = 38f, browH = 9f, browDx = -8f, browDy = -19.5f, highlight = 7f, hlX = 5f, hlY = 6f,
        stacheW = 53f, stacheH = 18f, stacheOverlap = 10f, mouth = Mouth.Smile, mouthW = 22f, mouthH = 11f,
        gap = 16f, marginTop = 8f, cheekW = 30f, cheekH = 14f, cheekX = 34f, cheekY = 118f, cheekA = .35f,
    ),
    Expression.Dozing to Face(
        200f, 170f, eyeW = 20f, eyeH = 6f, eyeR = 3f, eyeGap = 38f, rimOffset = 6f, rimStroke = 2.5f, bridgeY = 2f,
        browW = 34f, browH = 8f, browDx = -7f, browDy = -17.5f,
        stacheW = 34f, stacheH = 11f, stacheOverlap = 6f, mouth = Mouth.Smile, mouthW = 14f, mouthH = 5f,
        gap = 16f, marginTop = 10f, cheekW = 24f, cheekH = 12f, cheekX = 32f, cheekY = 96f, cheekA = .3f,
    ),
    Expression.Talking to Face(
        170f, 145f, eyeW = 16f, eyeH = 26f, eyeR = 8f, eyeGap = 32f, rimOffset = 5f, rimStroke = 2f, bridgeY = 9f,
        browW = 28f, browH = 7f, browDx = -6f, browDy = -15f,
        stacheW = 53f, stacheH = 18f, stacheOverlap = 10f, mouth = Mouth.Oval, mouthW = 22f, mouthH = 18f,
        gap = 12f, marginTop = 6f, cheekW = 24f, cheekH = 12f, cheekX = 22f, cheekY = 82f, cheekA = .35f,
    ),
    Expression.Thinking to Face(
        64f, 54f, eyeW = 8f, eyeH = 10f, eyeR = 4f, eyeGap = 12f, rimOffset = 3f, rimStroke = 1.5f, bridgeY = 4f,
        browW = 16f, browH = 3f, browDx = -4f, browDy = -8.5f,
        stacheW = 17f, stacheH = 6f, stacheOverlap = 3f, mouth = Mouth.Flat, mouthW = 7f, mouthH = 3f,
        gap = 6f, eyesDx = 12f, eyesDy = -6f,
    ),
    Expression.Muted to Face(
        64f, 54f, eyeW = 10f, eyeH = 3f, eyeR = 2f, eyeGap = 12f, rimOffset = 3f, rimStroke = 1.5f, bridgeY = 1f,
        browW = 18f, browH = 4f, browDx = -4f, browDy = -9.5f,
        stacheW = 24f, stacheH = 8f, stacheOverlap = 4f, mouth = Mouth.Flat, mouthW = 10f, mouthH = 3f, gap = 8f,
    ),
    // The little Desku in corners: just the eyes and brows.
    Expression.Peeking to Face(
        64f, 54f, eyeW = 8f, eyeH = 12f, eyeR = 4f, eyeGap = 12f, rimOffset = 3f, rimStroke = 1.5f, bridgeY = 4f,
        browW = 16f, browH = 3f, browDx = -4f, browDy = -8.5f,
    ),
)

private data class BodyColors(val light: Color, val mid: Color, val deep: Color, val alpha: Float, val glow: Float)

private fun bodyColors(e: Expression) = when (e) {
    Expression.Dozing -> BodyColors(Color(0xFF86BDF0), Color(0xFF3C80D0), Color(0xFF16509C), .85f, .22f)
    Expression.Muted -> BodyColors(Color(0xFF6A9CC9), Color(0xFF2E649C), Color(0xFF18446B), .9f, 0f)
    Expression.Listening -> BodyColors(Color(0xFF9CCBFF), Color(0xFF4A9BE8), Color(0xFF1C5FB8), 1f, .4f)
    else -> BodyColors(Color(0xFF9CCBFF), Color(0xFF4A9BE8), Color(0xFF1C5FB8), 1f, .35f)
}

/**
 * The drawn Desku from the design handoff: a blue blob with round glasses, bushy white brows and a
 * little mustache. Used when no avatar video is bundled (see Avatar.kt).
 * [width] is the body width; height follows the design's 1.18 : 1 proportion.
 */
@Composable
fun DrawnDesku(
    expression: Expression,
    width: Dp,
    modifier: Modifier = Modifier,
    halo: Boolean = true,
    rings: Boolean = expression == Expression.Listening,
    animate: Boolean = true,
    /**
     * Voice energy 0..1: the user's mic level while listening, Desku's own voice while talking.
     * The aura swells with it, the listening ring follows it and the mouth opens with it.
     */
    level: Float = 0f,
    /** Where the user is (-1 left … 1 right of the screen), from the camera; the eyes follow. */
    lookAt: Float? = null,
    /** True when [level] is Desku's real voice: the mouth then moves only with sound, never on its own. */
    voiceDriven: Boolean = false,
) {
    val t = rememberInfiniteTransition(label = "desku")
    // A blink every few seconds, sometimes a double blink, so he never looks frozen.
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 6400
                1f at 2600; .08f at 2680; 1f at 2780
                1f at 5600; .08f at 5680; 1f at 5760; .08f at 5880; 1f at 5960
            },
        ),
        label = "blink",
    )
    // Eyes drift a little side to side while he's paying attention.
    val gaze by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(3800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gaze")
    val voice by animateFloatAsState(level.coerceIn(0f, 1f), tween(110), label = "voice")
    val follow by animateFloatAsState(lookAt?.coerceIn(-1f, 1f) ?: 0f, tween(600, easing = FastOutSlowInEasing), label = "follow")
    val orbit by t.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "orbit")
    val breathe by t.animateFloat(1f, 1.04f, infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val bobSlow by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bobSlow")
    val bobFast by t.animateFloat(0f, 1f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bobFast")
    val talk by t.animateFloat(.35f, 1f, infiniteRepeatable(tween(200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "talk")
    val ring1 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "ring1")
    val ring2 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing), initialStartOffset = StartOffset(1000)), label = "ring2")
    val dots by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")

    val face = faces.getValue(expression)
    val colors = bodyColors(expression)
    val height = width * (face.refH / face.refW)
    val scale = when {
        !animate -> 1f
        expression == Expression.Dozing -> breathe
        expression == Expression.Listening -> 1f + .035f * voice
        else -> 1f
    }
    val eyesOpen = if (animate && expression in setOf(Expression.Listening, Expression.Talking, Expression.Thinking, Expression.Peeking)) blink else 1f
    val lookX = when {
        !animate -> 0f
        lookAt != null && expression != Expression.Dozing && expression != Expression.Muted -> follow * 4f * (face.refW / 240f)
        expression == Expression.Listening || expression == Expression.Talking -> gaze * 1.5f * (face.refW / 240f)
        else -> 0f
    }
    val mouth = when {
        expression != Expression.Talking || !animate -> 1f
        voice > .03f -> (.3f + .7f * voice).coerceAtMost(1f)
        voiceDriven -> .15f
        else -> talk
    }
    val aura = animate && (expression == Expression.Listening || expression == Expression.Talking)
    val lift = when {
        !animate -> 0f
        expression == Expression.Listening -> bobSlow
        expression == Expression.Talking -> if (voiceDriven) bobFast * voice.coerceAtMost(1f) else bobFast
        else -> 0f
    }

    Canvas(
        modifier
            .size(width, height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = -8f / 240f * size.width * lift
            }
            .semantics { contentDescription = "Desku, ${expression.name.lowercase()}" },
    ) {
        val k = size.width / face.refW
        val w = size.width
        val h = size.height

        if (halo && aura) {
            // Orb-style aura: three soft lobes drifting around him, swelling with voice energy.
            val base = w * .6f * (1f + .28f * voice)
            listOf(
                Triple(Night.Accent, 0f, .24f),
                Triple(Color(0xFF9CCBFF), 2.1f, .16f),
                Triple(Color(0xFF8C7BFF), 4.2f, .14f),
            ).forEach { (color, phase, alpha) ->
                val a = orbit + phase
                val c = Offset(center.x + kotlin.math.cos(a) * w * .06f, center.y + kotlin.math.sin(a) * h * .06f)
                drawCircle(
                    Brush.radialGradient(0f to color.copy(alpha = alpha + .3f * voice), 1f to Color.Transparent, center = c, radius = base),
                    radius = base, center = c,
                )
            }
        } else if (halo && colors.glow > 0f) {
            val r = w / 2f + 30f * k * (face.refW / 240f).coerceAtLeast(.4f)
            drawCircle(
                Brush.radialGradient(
                    0f to Night.Accent.copy(alpha = colors.glow),
                    .7f to Night.Accent.copy(alpha = 0f),
                    center = center, radius = r,
                ),
                radius = r, center = center,
            )
        }
        if (rings && animate) {
            listOf(ring1, ring2).forEach { p ->
                val s = 1f + .6f * p
                scale(s, s, center) {
                    drawOval(Night.Accent.copy(alpha = .55f * (1f - p)), style = Stroke(2f * k))
                }
            }
            // A ring that follows the user's voice: you can see Desku hearing you.
            if (voice > .02f) {
                val s = 1.04f + .32f * voice
                scale(s, s, center) {
                    drawOval(Night.Accent.copy(alpha = .2f + .5f * voice), style = Stroke(3f * k))
                }
            }
        }

        // Body: the design's lopsided blob, border-radius 48% 52% 46% 54% / 58% 56% 44% 42%.
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(0f, 0f, w, h),
                    topLeft = CornerRadius(.48f * w, .58f * h),
                    topRight = CornerRadius(.52f * w, .56f * h),
                    bottomRight = CornerRadius(.46f * w, .44f * h),
                    bottomLeft = CornerRadius(.54f * w, .42f * h),
                ),
            )
        }
        val c = Offset(.35f * w, .30f * h)
        drawPath(
            body,
            Brush.radialGradient(
                0f to colors.light, .6f to colors.mid, 1f to colors.deep,
                center = c, radius = sqrt((.65f * w) * (.65f * w) + (.7f * h) * (.7f * h)),
            ),
            alpha = colors.alpha,
        )

        drawFace(face, k, mouth, eyesOpen, lookX)

        if (expression == Expression.Thinking) {
            // Three dots bobbing above the top-right of his head.
            repeat(3) { i ->
                val phase = ((dots - i * .17f) % 1f + 1f) % 1f
                val wave = if (phase < .5f) phase * 2f else (1f - phase) * 2f
                // Sized for a small Desku; on a big one keep them small and tucked in at his top-right.
                val d = minOf(k, 1.6f * density)
                drawCircle(
                    Night.Accent.copy(alpha = .3f + .7f * wave),
                    radius = 2.5f * d,
                    center = Offset(w * .82f - (2 - i) * 8f * d, h * .06f - 4f * d * wave),
                )
            }
        }
    }
}

private fun DrawScope.drawFace(f: Face, k: Float, mouthOpen: Float, eyesOpen: Float = 1f, lookX: Float = 0f) {
    fun u(v: Float) = v * k
    val hasStache = f.stacheW > 0f
    val colContent = f.eyesDy + f.eyeH + if (hasStache) f.gap + f.stacheH - f.stacheOverlap + f.mouthH else 0f
    val colTop = (f.refH - (colContent + f.marginTop)) / 2f + f.marginTop
    val eyeTop = colTop + f.eyesDy
    val rowW = 2 * f.eyeW + f.eyeGap
    val rowLeft = (f.refW - (rowW + f.eyesDx)) / 2f + f.eyesDx
    val eyes = listOf(rowLeft, rowLeft + f.eyeW + f.eyeGap)

    // Cheeks sit under everything else.
    if (f.cheekA > 0f) {
        drawOval(Night.Cheek.copy(alpha = f.cheekA), Offset(u(f.cheekX), u(f.cheekY)), Size(u(f.cheekW), u(f.cheekH)))
        drawOval(Night.Cheek.copy(alpha = f.cheekA), Offset(u(f.refW - f.cheekX - f.cheekW), u(f.cheekY)), Size(u(f.cheekW), u(f.cheekH)))
    }

    eyes.forEachIndexed { i, x ->
        // Pupils move and blink inside the glasses; the rims stay put.
        val h = (f.eyeH * eyesOpen).coerceAtLeast(f.eyeH.coerceAtMost(2f))
        val top = eyeTop + (f.eyeH - h) / 2f
        drawRoundRect(Night.Ink, Offset(u(x + lookX), u(top)), Size(u(f.eyeW), u(h)), CornerRadius(u(minOf(f.eyeR, h / 2f))))
        if (f.highlight > 0f && eyesOpen > .6f) {
            drawCircle(Night.Highlight, u(f.highlight / 2), Offset(u(x + lookX + f.hlX + f.highlight / 2), u(eyeTop + f.hlY + f.highlight / 2)))
        }
        // Glasses: an outline offset around each eye that follows its corners.
        val inset = f.rimOffset + f.rimStroke / 2
        drawRoundRect(
            Night.Ink,
            Offset(u(x - inset), u(eyeTop - inset)),
            Size(u(f.eyeW + 2 * inset), u(f.eyeH + 2 * inset)),
            CornerRadius(u(f.eyeR + inset)),
            style = Stroke(u(f.rimStroke)),
        )
        // Bushy brows, tilted outward.
        val bx = x + f.browDx
        val by = eyeTop + f.browDy
        rotate(if (i == 0) -10f else 10f, Offset(u(bx + f.browW / 2), u(by + f.browH / 2))) {
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(u(bx), u(by), u(bx + f.browW), u(by + f.browH)),
                        topLeft = CornerRadius(u(f.browW * .5f), u(f.browH * .5f)),
                        topRight = CornerRadius(u(f.browW * .5f), u(f.browH * .5f)),
                        bottomRight = CornerRadius(u(f.browW * .35f), u(f.browH * .35f)),
                        bottomLeft = CornerRadius(u(f.browW * .35f), u(f.browH * .35f)),
                    ),
                )
            }
            drawPath(path, Night.Brow)
        }
    }
    // Bridge between the lenses.
    val bridgeL = eyes[0] + f.eyeW + f.rimOffset + f.rimStroke
    val bridgeR = eyes[1] - f.rimOffset - f.rimStroke
    drawRect(Night.Ink, Offset(u(bridgeL), u(eyeTop + f.bridgeY)), Size(u(bridgeR - bridgeL), u(f.rimStroke)))

    if (!hasStache) return
    val stacheTop = eyeTop + f.eyeH + f.gap
    val mouthTop = stacheTop + f.stacheH - f.stacheOverlap
    val mouthLeft = (f.refW - f.mouthW) / 2f
    when (f.mouth) {
        Mouth.Smile -> drawPath(
            Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(u(mouthLeft), u(mouthTop), u(mouthLeft + f.mouthW), u(mouthTop + f.mouthH)),
                        bottomLeft = CornerRadius(u(f.mouthW / 2), u(f.mouthH)),
                        bottomRight = CornerRadius(u(f.mouthW / 2), u(f.mouthH)),
                    ),
                )
            },
            Night.Ink,
        )
        Mouth.Oval -> translate(0f, 0f) {
            val hOpen = f.mouthH * mouthOpen
            drawOval(Night.Ink, Offset(u(mouthLeft), u(mouthTop)), Size(u(f.mouthW), u(hOpen)))
        }
        Mouth.Flat -> drawRoundRect(Night.Ink, Offset(u(mouthLeft), u(mouthTop)), Size(u(f.mouthW), u(f.mouthH)), CornerRadius(u(2f)))
        Mouth.None -> Unit
    }
    // Mustache over the top of the mouth: 50% 50% 45% 45% / 90% 90% 30% 30%, scaled to fit.
    val sl = (f.refW - f.stacheW) / 2f
    val sw = f.stacheW
    val sh = f.stacheH
    val fit = 1f / 1.2f
    drawPath(
        Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(u(sl), u(stacheTop), u(sl + sw), u(stacheTop + sh)),
                    topLeft = CornerRadius(u(.5f * sw * fit), u(.9f * sh * fit)),
                    topRight = CornerRadius(u(.5f * sw * fit), u(.9f * sh * fit)),
                    bottomRight = CornerRadius(u(.45f * sw * fit), u(.3f * sh * fit)),
                    bottomLeft = CornerRadius(u(.45f * sw * fit), u(.3f * sh * fit)),
                ),
            )
        },
        Night.Brow,
    )
}
