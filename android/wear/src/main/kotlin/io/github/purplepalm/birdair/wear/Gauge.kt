package io.github.purplepalm.birdair.wear

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeTextDefaults
import io.github.purplepalm.birdair.core.Telemetry
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The gauge, laid out on a 240-unit round design grid scaled to the screen (≈ 240 dp on the 480 px
 * Galaxy Watch Ultra): the power arc along the edge, the speed in the middle, battery and mode under
 * it, the power value in the gap at the bottom where the arc ends (±135° from 12 o'clock).
 */

/** Design units → dp / sp. Text sizes follow the screen, not the font scale: the gauge is a fixed drawing. */
private class Grid(val unit: Dp, val density: Density) {
    fun dp(n: Float): Dp = unit * n
    fun sp(n: Float): TextUnit = with(density) { (unit * n).toSp() }
}

/** Places the child centred horizontally with its vertical centre at [y] of 240 units. */
private fun Modifier.centreAt(y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        p.place((c.maxWidth - p.width) / 2, (c.maxHeight * y / 240f - p.height / 2f).roundToInt())
    }
}

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun GaugeScreen(s: Screen, ambient: AmbientState?) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val g = Grid(side / 240f, LocalDensity.current)
        val amb = ambient != null
        val shift = if (ambient?.burnIn == true) BurnIn.offset(ambient.tick) else 0f to 0f
        val t = s.t
        val live = s.health.live
        // old power is not shown as power: the arc rests at zero unless the data is live (as on the phone)
        val frac by animateFloatAsState(
            if (live) Arc.fraction(t.powerW, t.peakW) else 0f,
            if (amb) snap() else tween(150, easing = LinearOutSlowInEasing),
            label = "power",
        )
        Box(Modifier.size(side).offset(shift.first.dp, shift.second.dp)) {
            Box(Modifier.fillMaxSize().alpha(s.health.alpha)) {
                PowerArc(frac, amb, Modifier.fillMaxSize())
                Speed(t, amb, g)
                Text(
                    Fmt.speedUnit(t.units),
                    Modifier.centreAt(146f),
                    color = argb(if (amb) Palette.AMB_DIM else Palette.INK2),
                    fontSize = g.sp(14f),
                )
                BatteryAndMode(t, amb, g, Modifier.centreAt(172f))
                Power(t, live, amb, g)
            }
            StatusLine(s.health, amb, g, Modifier.centreAt(45f))
        }
    }
}

/** The arc: faint ±135° track, ticks at 0, ±50 % and the ends, then the value from 12 o'clock (see [Arc.bar]). */
@Composable
fun PowerArc(fraction: Float, ambient: Boolean, modifier: Modifier = Modifier) {
    val colour = argb(Arc.colorFor(fraction.toDouble()))
    Canvas(modifier) {
        val u = size.minDimension / 240f
        val band = BAND * u
        val r = size.minDimension / 2 - EDGE * u - band / 2
        val tl = Offset(center.x - r, center.y - r)
        val sz = Size(2 * r, 2 * r)
        val (ts, tw) = Arc.track
        if (ambient) drawArc(argb(Palette.AMB_LINE), ts, tw, false, tl, sz, style = Stroke(1.2f * u, cap = StrokeCap.Round))
        else drawArc(argb(Palette.HAIR), ts, tw, false, tl, sz, style = Stroke(band, cap = StrokeCap.Round))
        ticks(r - band / 2, u, ambient)
        val w = if (ambient) 3.5f * u else band
        Arc.bar(fraction, Math.toDegrees((w / 2 / r).toDouble()).toFloat())?.let { b ->
            // a soft halo under the bar, then the bar; one filled outline each, so the halo has no darker overlap
            if (!ambient) drawPath(barPath(r, w + 9f * u, b), colour.copy(alpha = 0.18f))
            drawPath(barPath(r, w, b), colour)
        }
    }
}

/** [b] as an outline [width] wide on radius [r]: square at 12 o'clock, a half-disc tip past the body. */
private fun DrawScope.barPath(r: Float, width: Float, b: Arc.Bar): Path = Path().apply {
    val ro = r + width / 2
    val z = Math.toRadians(Arc.ZERO_DEG.toDouble())
    moveTo(center.x + ro * cos(z).toFloat(), center.y + ro * sin(z).toFloat())
    if (b.body != 0f) arcTo(Rect(center, ro), Arc.ZERO_DEG, b.body, false)
    val e = Arc.ZERO_DEG + b.body
    val ea = Math.toRadians(e.toDouble())
    val tip = Offset(center.x + r * cos(ea).toFloat(), center.y + r * sin(ea).toFloat())
    arcTo(Rect(tip, width / 2), e, 180f * b.dir, false)
    if (b.body != 0f) arcTo(Rect(center, r - width / 2), e, -b.body, false)
    close()
}

private const val EDGE = 3f
private const val BAND = 13f
private val TICKS = floatArrayOf(0f, 67.5f, -67.5f, 135f, -135f)

private fun DrawScope.ticks(inner: Float, u: Float, ambient: Boolean) {
    for (deg in TICKS) {
        val zero = deg == 0f
        val a = Math.toRadians((Arc.ZERO_DEG + deg).toDouble())
        val r1 = inner - (if (zero) 9f else 6f) * u
        val r2 = inner - 2.5f * u
        val c = if (ambient) argb(Palette.AMB_DIM) else argb(if (zero) Palette.INK2 else Palette.BASE)
        drawLine(
            c,
            Offset(center.x + r1 * cos(a).toFloat(), center.y + r1 * sin(a).toFloat()),
            Offset(center.x + r2 * cos(a).toFloat(), center.y + r2 * sin(a).toFloat()),
            strokeWidth = (if (zero) 2f else 1.4f) * u,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun Speed(t: Telemetry, ambient: Boolean, g: Grid) {
    val (whole, tail) = Fmt.speedParts(t.speedKmh, t.units)
    val text = buildAnnotatedString {
        append(whole)
        withStyle(SpanStyle(fontSize = g.sp(32f), fontWeight = if (ambient) FontWeight.Light else FontWeight.Normal)) { append(tail) }
    }
    Text(
        text,
        Modifier.centreAt(100f),
        color = argb(if (ambient) Palette.AMB_INK else Palette.INK),
        style = TNUM.copy(
            fontSize = g.sp(88f),
            fontWeight = if (ambient) FontWeight.Light else FontWeight.Medium,
            drawStyle = if (ambient) Stroke(width = with(g.density) { g.dp(1.3f).toPx() }) else null,
        ),
        maxLines = 1,
        softWrap = false,
    )
}

@Composable
private fun BatteryAndMode(t: Telemetry, ambient: Boolean, g: Grid, modifier: Modifier) {
    val level = Battery.level(t)
    val mode = Modes.name(t.mode)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BatteryBar(level?.pct, ambient, Modifier.size(g.dp(27f), g.dp(12f)))
        Spacer(Modifier.width(g.dp(5f)))
        Text(
            Fmt.pct(level),
            color = argb(if (ambient) Palette.AMB_INK else Battery.tone(level?.pct).ink),
            style = TNUM.copy(fontSize = g.sp(17f), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        if (mode != null) {
            Spacer(Modifier.width(g.dp(9f)))
            Box(
                Modifier
                    .border(g.dp(1.6f), argb(if (ambient) Palette.AMB_DIM else Modes.argb(t.mode)), RoundedCornerShape(50))
                    .padding(horizontal = g.dp(7f), vertical = g.dp(1.5f)),
            ) {
                Text(
                    mode,
                    color = argb(if (ambient) Palette.AMB_INK else Palette.INK),
                    fontSize = g.sp(12.5f),
                    fontWeight = if (ambient) FontWeight.Normal else FontWeight.Bold,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Outline with a nub; the fill in the tone colour (green / amber / red), a thin line only in ambient. */
@Composable
private fun BatteryBar(pct: Double?, ambient: Boolean, modifier: Modifier) {
    val tone = argb(Battery.tone(pct).argb)
    Canvas(modifier) {
        val u = size.height / 12f
        val nub = 2f * u
        val w = size.width - nub
        val line = 1.3f * u
        val outline = argb(if (ambient) Palette.AMB_DIM else Palette.MUTED)
        drawRoundRect(outline, Offset(line / 2, line / 2), Size(w - line, size.height - line), CornerRadius(2.5f * u), style = Stroke(line))
        drawRoundRect(outline, Offset(w, size.height * 0.32f), Size(nub, size.height * 0.36f), CornerRadius(u))
        val f = ((pct ?: 0.0) / 100).coerceIn(0.0, 1.0).toFloat()
        if (pct == null) return@Canvas
        val inset = 2.6f * u
        // a sliver stays visible down to 0 %: the red of an empty battery must show
        val fw = maxOf((w - 2 * inset) * f, 1.6f * u)
        if (ambient) {
            drawLine(argb(Palette.AMB_INK), Offset(inset, size.height / 2), Offset(inset + fw, size.height / 2), strokeWidth = 1.5f * u, cap = StrokeCap.Round)
        } else {
            drawRoundRect(tone, Offset(inset, inset), Size(fw, size.height - 2 * inset), CornerRadius(1.2f * u))
        }
    }
}

@Composable
private fun Power(t: Telemetry, live: Boolean, ambient: Boolean, g: Grid) {
    val p = if (live) t.powerW else null
    val flow = if (live) Flow.of(t) else Flow.NONE
    Text(
        Fmt.watts(p),
        Modifier.centreAt(198f),
        color = argb(if (ambient) Palette.AMB_INK else if (p == null || p == 0.0) Palette.INK2 else Arc.colorFor(p)),
        style = TNUM.copy(fontSize = g.sp(21f), fontWeight = if (ambient) FontWeight.Normal else FontWeight.Bold),
        maxLines = 1,
    )
    Text(
        flow.label,
        Modifier.centreAt(217f),
        color = argb(if (ambient) Palette.AMB_DIM else flow.tone),
        fontSize = g.sp(11.5f),
        letterSpacing = 0.4.sp,
        maxLines = 1,
    )
}

/** Top line: what is wrong with the data, or the time when all is live. Not dimmed with the values. */
@Composable
private fun StatusLine(h: Health, ambient: Boolean, g: Grid, modifier: Modifier) {
    val label = h.gaugeLabel
    if (label != null) {
        Text(
            label,
            modifier.widthIn(max = g.dp(140f)),
            color = argb(if (ambient) Palette.AMB_INK else h.tone),
            fontSize = g.sp(12.5f),
            lineHeight = g.sp(15f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    } else {
        val time = TimeTextDefaults.timeSource(TimeTextDefaults.timeFormat()).currentTime
        Text(
            time,
            modifier,
            color = argb(if (ambient) Palette.AMB_DIM else Palette.MUTED),
            style = TNUM.copy(fontSize = g.sp(14f), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
