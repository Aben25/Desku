package com.deskbuddy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskbuddy.R

/** Design 1a "Nightlight": dark and cool, Desku glows like a nightlight. Values from the handoff. */
object Night {
    val Bg = Color(0xFF14171C)
    val Text = Color(0xFFEEF2F7)
    val Sub = Color(0xFF8C9BAB)
    val Chip = Color(0xFF1A1F27)
    val ChipText = Color(0xFFABBCCC)
    val Raised = Color(0xFF222A35)
    val Line = Color(0xFF33404F)
    val Dash = Color(0xFF3F4F63)
    val Accent = Color(0xFF4A9BE8)
    val Ink = Color(0xFF08182A)
    val Warn = Color(0xFFE07B5C)
    val Green = Color(0xFF7FC99A)
    val Off = Color(0xFF46505C)
    val Track = Color(0xFF2B3440)
    val Knob = Color(0xFF8C9BAB)
    val Brow = Color(0xFFEEF1F5)
    val Highlight = Color(0xFFEEF6FF)
    val Cheek = Color(0xFFE06E50)
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    R.font.figtree,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Figtree = FontFamily(figtree(300), figtree(400), figtree(500), figtree(600), figtree(700))
val DmMono = FontFamily(Font(R.font.dm_mono_regular, FontWeight.Normal), Font(R.font.dm_mono_medium, FontWeight.Medium))

fun fig(size: TextUnit, weight: Int = 400, color: Color = Night.Text, spacing: TextUnit = TextUnit.Unspecified, line: TextUnit = TextUnit.Unspecified) =
    TextStyle(fontFamily = Figtree, fontWeight = FontWeight(weight), fontSize = size, color = color, letterSpacing = spacing, lineHeight = line)

fun mono(size: TextUnit = 12.sp, color: Color = Night.Sub, weight: Int = 400) =
    TextStyle(fontFamily = DmMono, fontWeight = FontWeight(weight), fontSize = size, color = color)

// ------------------------------------------------------------------ glyphs

/** Rounded camera body with a lens dot (26×19 in the design). */
@Composable
fun CameraGlyph(color: Color, width: Dp = 26.dp, height: Dp = 19.dp, stroke: Dp = 2.5.dp, dot: Dp = 7.dp) {
    Canvas(Modifier.size(width, height)) {
        val s = stroke.toPx()
        drawRoundRect(color, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), CornerRadius(5.dp.toPx() - s / 2), style = Stroke(s))
        drawCircle(color, dot.toPx() / 2, center)
    }
}

/** Three bars: the memories list. */
@Composable
fun ListGlyph(color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(22.dp, 22.dp, 14.dp).forEach { w -> Box(Modifier.size(w, 3.dp).background(color, RoundedCornerShape(2.dp))) }
    }
}

/** Microphone: capsule, cradle and stem. */
@Composable
fun MicGlyph(color: Color, muted: Boolean = false, scale: Float = 1f) {
    Canvas(Modifier.size(28.dp * scale, 34.dp * scale)) {
        val u = size.width / 28f
        drawRoundRect(color, Offset(8 * u, 0f), Size(12 * u, 21 * u), CornerRadius(6 * u))
        val st = Stroke(2.6f * u)
        drawArc(color, 0f, 180f, false, Offset(3 * u, 8 * u), Size(22 * u, 18 * u), style = st)
        drawLine(color, Offset(14 * u, 26 * u), Offset(14 * u, 32 * u), 2.6f * u)
        drawLine(color, Offset(8 * u, 32.5f * u), Offset(20 * u, 32.5f * u), 2.6f * u)
        if (muted) drawLine(color, Offset(2 * u, 2 * u), Offset(26 * u, 32 * u), 2.8f * u)
    }
}

// ------------------------------------------------------------------ small parts

/** "● Mic on" style status chip; tappable where it is a privacy switch. */
@Composable
fun StatusChip(dot: Color, label: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .background(Night.Chip, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Box(Modifier.width(7.dp))
        Text(label, style = fig(14.sp, color = Night.ChipText))
    }
}

/** Pill button: filled accent (primary) or outlined. */
@Composable
fun Pill(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    size: TextUnit = 18.sp,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (primary) Night.Accent else Color.Transparent,
        border = if (primary) null else BorderStroke(1.5.dp, Night.Line),
        modifier = modifier.height(height),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            leading?.let { it(); Box(Modifier.width(10.dp)) }
            Text(label, style = fig(size, if (primary) 600 else 500, if (primary) Night.Ink else Night.Text), maxLines = 1)
        }
    }
}

/** The design's 54×32 switch. */
@Composable
fun NightSwitch(on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val track = when {
        !enabled -> Night.Chip
        on -> Night.Accent
        else -> Night.Track
    }
    Box(
        Modifier
            .size(54.dp, 32.dp)
            .background(track, RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            Modifier
                .padding(start = if (on) 26.dp else 4.dp, top = 4.dp)
                .size(24.dp)
                .background(if (!enabled) Night.Dash else if (on) Night.Text else Night.Knob, CircleShape),
        )
    }
}

/** Dashed-outline mono pill: "“Hey Desku” wake phrase · on-device". */
@Composable
fun DashedPill(text: String, color: Color = Night.Sub) {
    Box(
        Modifier
            .dashedBorder(Night.Dash, 999.dp)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(text, style = mono(12.sp, color)) }
}

fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = drawBehind {
    val w = width.toPx()
    val r = minOf(radius.toPx(), size.height / 2)
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(r),
        style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}
