package io.github.purplepalm.birdair.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.phone.CriticalSpring
import kotlinx.coroutines.flow.first

/** Tabular figures: numbers that change 4 times a second do not jitter. */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

/** The big readouts: tabular, with the font's extra line padding trimmed. */
val Big = Tnum.copy(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

/** A card: surface, hairline border, 12 dp corners (the page's .card). */
@Composable
fun Panel(title: String? = null, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalBal.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = c.surface,
        border = BorderStroke(1.dp, c.hair),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) Text(title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

enum class Tone { NEUTRAL, GOOD, WARN, CRIT, DRAW, FLOW }

@Composable
fun toneColor(t: Tone): Color {
    val c = LocalBal.current
    return when (t) {
        Tone.NEUTRAL -> c.muted
        Tone.GOOD -> c.good
        Tone.WARN -> c.warn
        Tone.CRIT -> c.crit
        Tone.DRAW -> c.draw
        Tone.FLOW -> c.flow
    }
}

/** A status pill with a dot. */
@Composable
fun Pill(text: String, tone: Tone) {
    val c = LocalBal.current
    val dot = toneColor(tone)
    Row(
        Modifier
            .border(1.dp, if (tone == Tone.NEUTRAL) c.hair else dot.copy(alpha = 0.55f), CircleShape)
            .background(if (tone == Tone.NEUTRAL) c.surface else dot.copy(alpha = 0.12f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Label above, value and unit below. */
@Composable
fun Metric(label: String, value: String, unit: String = "", modifier: Modifier = Modifier, valueSize: TextUnit = 22.sp, color: Color? = null) {
    val c = LocalBal.current
    Column(modifier) {
        Text(label, color = c.muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color ?: c.ink, fontSize = valueSize, fontWeight = FontWeight.SemiBold, style = Tnum)
            if (unit.isNotEmpty()) Text(" $unit", color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** A message line under a control; red when [err]. */
@Composable
fun Msg(text: String, err: Boolean = false) {
    if (text.isEmpty()) return
    val c = LocalBal.current
    Text(text, color = if (err) c.critInk else c.ink2, fontSize = 13.sp)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    val c = LocalBal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = c.ink, fontSize = 15.sp)
            if (hint != null) Text(hint, color = c.muted, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.flow, checkedBorderColor = c.flow,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.well, uncheckedBorderColor = c.base,
            ),
        )
    }
}

/**
 * [target] through a [CriticalSpring] stepped on every display frame while [smooth] (settles in ~110 ms, never
 * overshoots); without it the value is the target itself and jumps with the data. Read the returned state inside a
 * draw lambda: then only the drawing repeats per frame, nothing recomposes. The frame loop sleeps once settled.
 */
@Composable
fun rememberSpring(target: Float, smooth: Boolean): State<Float> {
    val goal = rememberUpdatedState(target)
    val out = remember { mutableFloatStateOf(target) }
    LaunchedEffect(smooth) {
        if (!smooth) return@LaunchedEffect
        out.floatValue = goal.value
        val s = CriticalSpring(goal.value.toDouble())
        var last = Long.MIN_VALUE
        while (true) {
            val g = goal.value.toDouble()
            if (s.settled(g)) {
                out.floatValue = g.toFloat()
                last = Long.MIN_VALUE
                snapshotFlow { goal.value }.first { it.toDouble() != g }
                continue
            }
            withFrameNanos { now ->
                val dt = if (last == Long.MIN_VALUE) CriticalSpring.FIRST_FRAME_S else (now - last) / 1e9
                last = now
                s.step(goal.value.toDouble(), dt)
                out.floatValue = s.value.toFloat()
            }
        }
    }
    return if (smooth) out else goal
}

/**
 * THE power bar: centre zero, traction to the right in --draw, regen to the left in --flow, [frac] = P / scale
 * in −1…1 (read while drawing: [rememberSpring] moves it per display frame).
 */
@Composable
fun PowerBar(frac: State<Float>, live: Boolean, modifier: Modifier = Modifier, height: Dp = 52.dp) {
    val c = LocalBal.current
    val draw = if (live) c.draw else c.draw.copy(alpha = 0.35f)
    val flow = if (live) c.flow else c.flow.copy(alpha = 0.35f)
    Canvas(modifier.fillMaxWidth().height(height).semantics { contentDescription = "Полоса мощности" }) {
        val a = frac.value.coerceIn(-1f, 1f)
        val w = size.width
        val h = size.height
        val r = CornerRadius(minOf(10.dp.toPx(), h / 2))
        val track = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, r)) }
        val cx = w / 2
        clipPath(track) {
            drawRect(c.well)
            for (q in floatArrayOf(0.25f, 0.75f)) drawLine(c.hair, Offset(w * q, 0f), Offset(w * q, h), 1.dp.toPx())
            if (a > 0f) drawRect(draw, topLeft = Offset(cx, 0f), size = Size(cx * a, h))
            else if (a < 0f) drawRect(flow, topLeft = Offset(cx + cx * a, 0f), size = Size(-cx * a, h))
        }
        drawRoundRect(c.hair, cornerRadius = r, style = Stroke(1.dp.toPx()))
        drawLine(c.ink, Offset(cx, 0f), Offset(cx, h), 2.dp.toPx())
    }
}

/** Battery level bar: green, amber under 30 %, red under 15 %. */
@Composable
fun LevelBar(pct: Double?, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val p = ((pct ?: 0.0) / 100).toFloat().coerceIn(0f, 1f)
    val color = when {
        pct == null -> c.base
        pct < 15 -> c.crit
        pct < 30 -> c.warn
        else -> c.good
    }
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(c.well, cornerRadius = r)
        if (p > 0f) drawRoundRect(color, size = Size(size.width * p, size.height), cornerRadius = r)
        drawRoundRect(c.hair, cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}
