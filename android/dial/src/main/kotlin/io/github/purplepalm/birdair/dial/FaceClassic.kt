package io.github.purplepalm.birdair.dial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * DialFace.CLASSIC — v108's dial, moved here unchanged (it was the watch's Gauge.kt): like a car cluster (mock3.html,
 * approved): the power arc along the edge, inside it the thin speed track with the dot («ириска») and the mode's limit,
 * the battery with its fine % in the centre, the range, «V · °C · MODE», the top lamp's line and the power under it; 12
 * tell-tales in two columns along the edges (TellTales.IDS: the right column top to bottom, then the left). Laid out on
 * the 240-unit grid of [Dial], scaled to the screen. It reads the telemetry and the watch's state as v108's GaugeScreen
 * did; [FaceText] stands for Wear's Text (the watch provides its theme's style, [LocalFaceTextStyle]).
 */

/** Design units → dp / sp. Text sizes follow the screen, not the font scale: the dial is a fixed drawing. */
private class Grid(val unit: Dp, val density: Density) {
    fun dp(n: Float): Dp = unit * n
    fun sp(n: Float): TextUnit = with(density) { (unit * n).toSp() }
}

/** Places the child centred horizontally with its vertical centre at [y] of the grid. */
private fun Modifier.centreAt(y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(c.maxWidth, c.maxHeight) {
        p.place((c.maxWidth - p.width) / 2, (c.maxHeight * y / Dial.GRID - p.height / 2f).roundToInt())
    }
}

/** Places the child centred horizontally with its first baseline at [y] of the grid (the mock's text y). */
private fun Modifier.baselineAt(y: Float): Modifier = layout { m, c ->
    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
    val b = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
    layout(c.maxWidth, c.maxHeight) {
        p.place((c.maxWidth - p.width) / 2, (c.maxHeight * y / Dial.GRID - b).roundToInt())
    }
}

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** The red lamps' ~1 Hz ([Blink]): lit first, then dark and lit by turns while [enabled]; lit otherwise. */
@Composable
private fun rememberBlink(enabled: Boolean): State<Boolean> {
    val on = remember { mutableStateOf(true) }
    LaunchedEffect(enabled) {
        on.value = true
        if (!enabled) return@LaunchedEffect
        var since = 0L
        while (true) {
            delay(Blink.HALF_MS)
            since += Blink.HALF_MS
            on.value = Blink.on(since)
        }
    }
    return on
}

/**
 * The state's [DialState.smooth]: the power arc and the speed dot ease (see [rememberSpring]); ambient always jumps (one
 * update a second at most), shows no glow and no blink, outlines only. [nowMs]: a fixed time (shots, previews), the
 * springs jump and the red lamps blink by it; null: live, as v108.
 */
@Composable
fun FaceClassic(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val g = Grid(side / Dial.GRID, LocalDensity.current)
        val amb = state.ambient
        val lowBit = state.lowBit
        val shift = state.burnInDp
        val t = state.telemetry
        val live = state.health.live
        val watts = if (live) PowerView.watts(t, state.fast) else null
        // old power is not shown as power: the arc rests at zero unless the data is live (as on the phone)
        val power = rememberSpring(if (live) Arc.fraction(watts, t.peakW) else 0f, state.smooth && !amb && nowMs == null)
        val speedF = Dial.speedFraction(t.speedKmh, t.units)
        val speed = rememberSpring(speedF ?: 0f, state.smooth && !amb && nowMs == null)
        val lamps = Cluster.lamps(t)
        val bulb = state.bulbOn && !amb
        val shown = if (bulb) TellTales.bulbCheck() else lamps
        val blinks = !amb && !bulb && shown.any { Lamps.blinks(it.level) }
        val blink = if (nowMs == null) rememberBlink(blinks) else rememberUpdatedState(!blinks || Blink.on(nowMs - redSince(state, nowMs)))
        val msg = Dial.message(lamps, bulb)
        Box(Modifier.size(side).offset(shift.first.dp, shift.second.dp)) {
            Box(Modifier.fillMaxSize().alpha(state.health.alpha)) {
                Tracks(
                    TrackState(power, speed, speedF != null, Dial.dotLabel(t.speedKmh, t.units), Dial.limitFraction(t.mode, t.units),
                        Dial.ticks(t.units), state.cues.rumble && live),
                    amb, lowBit, Modifier.fillMaxSize(),
                )
                CentreBattery(t, lamps, amb, lowBit, Modifier.fillMaxSize())
                Lines(t, watts, state.fast, live, msg, amb, g)
            }
            // the check is the watch's own, at full strength even on old data
            LampColumns(shown, blink, amb, lowBit, Modifier.fillMaxSize().alpha(if (bulb) 1f else state.health.alpha))
            StatusLine(state.health, Cluster.statusLabel(state.health, lamps), amb, g, state.clock)
        }
    }
}

/** When the first red lamp lit (its blink starts lit there), else [nowMs]. */
private fun redSince(state: DialState, nowMs: Long): Long =
    state.lamps.lit.filter { it.level == LampLevel.RED }.minOfOrNull { it.sinceMs } ?: nowMs

private class TrackState(
    val power: State<Float>,
    val speed: State<Float>,
    val hasSpeed: Boolean,
    val dot: String?,
    val limit: Float?,
    val ticks: List<Float>,
    val over: Boolean,
)

/** The power arc and the speed track in one canvas; the springs are read while drawing. */
@Composable
private fun Tracks(st: TrackState, ambient: Boolean, lowBit: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.graphicsLayer()) {
        val u = size.minDimension / Dial.GRID
        powerArc(st.power.value, u, ambient, lowBit)
        speedTrack(st, u, ambient, lowBit, measurer)
    }
}

/**
 * The outer arc: the ±135° track, the value from 12 o'clock (traction clockwise, regen counter-clockwise, see
 * [Arc.bar]), the white zero tick on top.
 */
private fun DrawScope.powerArc(f: Float, u: Float, ambient: Boolean, lowBit: Boolean) {
    val r = Dial.POWER_R * u
    val tl = Offset(center.x - r, center.y - r)
    val sz = Size(2 * r, 2 * r)
    val (ts, tw) = Arc.track
    if (ambient) drawArc(argb(Palette.AMB_LINE), ts, tw, false, tl, sz, style = Stroke(1.2f * u, cap = StrokeCap.Round))
    else drawArc(argb(Palette.POWER_TRACK), ts, tw, false, tl, sz, style = Stroke(Dial.POWER_W * u, cap = StrokeCap.Round))
    val w = if (ambient) 3.5f * u else Dial.POWER_W * u
    val colour = argb(if (ambient && lowBit) Palette.AMB_INK else Arc.colorFor(f.toDouble()))
    Arc.bar(f, Math.toDegrees((w / 2 / r).toDouble()).toFloat())?.let { drawPath(barPath(r, w, it), colour) }
    drawLine(
        argb(if (ambient) Palette.AMB_DIM else Palette.INK),
        Offset(center.x, center.y - Dial.ZERO_TICK_OUT * u),
        Offset(center.x, center.y - Dial.ZERO_TICK_IN * u),
        strokeWidth = Dial.ZERO_TICK_W * u,
    )
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

/** A line across the radius at clock angle [deg], from [r1] to [r2] grid units. */
private fun DrawScope.radial(r1: Float, r2: Float, deg: Float, c: Color, width: Float, u: Float) {
    val (x1, y1) = Dial.point(r1, deg)
    val (x2, y2) = Dial.point(r2, deg)
    drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = width * u)
}

/** The thin speed track: ticks, the mode's limit in red, the passed part and the dot with the speed in it. */
private fun DrawScope.speedTrack(st: TrackState, u: Float, ambient: Boolean, lowBit: Boolean, measurer: TextMeasurer) {
    val r = Dial.SPEED_R * u
    val tl = Offset(center.x - r, center.y - r)
    val sz = Size(2 * r, 2 * r)
    val start = Dial.composeDeg(Dial.START_DEG)
    val line = (if (ambient) 1f else Dial.SPEED_W) * u
    drawArc(argb(if (ambient) Palette.AMB_LINE else Palette.SPEED_TRACK), start, Dial.SPAN_DEG, false, tl, sz, style = Stroke(line, cap = StrokeCap.Round))
    if (!ambient) for (f in st.ticks) radial(Dial.SPEED_R - Dial.TICK_HALF, Dial.SPEED_R + Dial.TICK_HALF, Dial.speedDeg(f), argb(Palette.SPEED_TICK), Dial.TICK_W, u)
    st.limit?.let {
        val c = argb(if (ambient) (if (lowBit) Palette.AMB_DIM else Palette.LIMIT) else Palette.LIMIT)
        radial(Dial.SPEED_R - Dial.LIMIT_HALF, Dial.SPEED_R + Dial.LIMIT_HALF, Dial.speedDeg(it), c, Dial.LIMIT_W, u)
    }
    if (!st.hasSpeed) return
    val f = st.speed.value.coerceIn(0f, 1f)
    val sweep = Dial.SPAN_DEG * f
    if (sweep > 0.3f) drawArc(argb(if (ambient) Palette.AMB_DIM else Palette.MUTED), start, sweep, false, tl, sz, style = Stroke(line, cap = StrokeCap.Round))
    val (gx, gy) = Dial.point(Dial.SPEED_R, Dial.speedDeg(f))
    val c = Offset(gx * u, gy * u)
    val dotR = Dial.DOT_R * u
    val ink: Color
    if (ambient) {
        val col = argb(if (st.over && !lowBit) Palette.LAMP_RED else Palette.AMB_INK)
        drawCircle(Color.Black, dotR, c)
        drawCircle(col, dotR, c, style = Stroke(0.8f * u))
        ink = col
    } else {
        // overspeed vibration on: the dot turns red with a halo
        if (st.over) drawCircle(argb(Palette.LAMP_RED).copy(alpha = 0.3f), dotR + 3f * u, c)
        drawCircle(argb(if (st.over) Palette.LAMP_RED else Palette.INK), dotR, c)
        ink = if (st.over) Color.White else Color.Black
    }
    val label = st.dot ?: return
    val layout = measurer.measure(label, TNUM.copy(fontSize = (Dial.DOT_FONT * u).toSp(), fontWeight = FontWeight.Bold))
    // the mock's baseline: 2 units under the dot's centre
    drawText(layout, ink, Offset(c.x - layout.size.width / 2f, c.y + 2f * u - layout.firstBaseline))
}

/** The battery in the centre: body and nub, the fill in the battery's colour, «56,3 %» over it with a dark halo. */
@Composable
private fun CentreBattery(t: Telemetry, lamps: List<Lamp>, ambient: Boolean, lowBit: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val text = Dial.pctText(t)
    val pct = Dial.pct(t)
    val tone = Dial.toneArgb(Dial.batteryTone(t, lamps))
    Canvas(modifier) {
        val u = size.minDimension / Dial.GRID
        val outline = argb(if (ambient) Palette.AMB_DIM else Palette.INK2)
        val body = if (ambient) Stroke(1.2f * u) else Stroke(Dial.BODY_LINE * u)
        drawRoundRect(outline, Offset(Dial.BODY_X * u, Dial.BODY_Y * u), Size(Dial.BODY_W * u, Dial.BODY_H * u), CornerRadius(Dial.BODY_RX * u), style = body)
        drawRoundRect(
            outline, Offset(Dial.NUB_X * u, Dial.NUB_Y * u), Size(Dial.NUB_W * u, Dial.NUB_H * u), CornerRadius(Dial.NUB_RX * u),
            style = if (ambient) Stroke(1f * u) else Fill,
        )
        val fw = Dial.fillWidth(pct)
        if (fw > 0f) {
            val at = Offset(Dial.FILL_X * u, Dial.FILL_Y * u)
            val fs = Size(fw * u, Dial.FILL_H * u)
            val rx = CornerRadius(minOf(Dial.FILL_RX, fw / 2) * u)
            if (ambient) drawRoundRect(argb(if (lowBit) Palette.AMB_INK else tone), at, fs, rx, style = Stroke(1f * u))
            else drawRoundRect(argb(tone), at, fs, rx, alpha = Dial.FILL_ALPHA)
        }
        val layout = pctLayout(measurer, text, u)
        val at = Offset(Dial.PCT_X * u - layout.size.width / 2f, Dial.PCT_Y * u - layout.firstBaseline)
        if (ambient) {
            drawText(layout, argb(Palette.AMB_INK), at, drawStyle = Stroke(0.6f * u))
        } else {
            // the mock's paint-order stroke: the halo first, the fill over it (Fill explicitly: the paint keeps the stroke)
            drawText(layout, Color.Black, at, drawStyle = Stroke(Dial.PCT_HALO * u, join = StrokeJoin.Round))
            drawText(layout, Color.White, at, drawStyle = Fill)
        }
    }
}

/** «56,3 %» at [Dial.PCT_FONT], smaller when it would not fit the battery. */
private fun DrawScope.pctLayout(measurer: TextMeasurer, text: String, u: Float): TextLayoutResult {
    val style = TNUM.copy(fontSize = (Dial.PCT_FONT * u).toSp(), fontWeight = FontWeight.Bold)
    val first = measurer.measure(text, style, softWrap = false, maxLines = 1)
    val max = Dial.PCT_MAX_W * u
    if (first.size.width <= max) return first
    return measurer.measure(text, style.copy(fontSize = (Dial.PCT_FONT * u * max / first.size.width).toSp()), softWrap = false, maxLines = 1)
}

/** Under the battery: the range, «V · °C · MODE», the top lamp's line, the power. */
@Composable
private fun Lines(t: Telemetry, watts: Double?, fast: Boolean, live: Boolean, msg: Dial.Message?, ambient: Boolean, g: Grid) {
    Dial.rangeText(t.range, t.units)?.let {
        FaceText(
            it,
            Modifier.baselineAt(Dial.RANGE_Y).widthIn(max = g.dp(140f)),
            color = argb(if (ambient) Palette.AMB_INK else if (RangeText.rough(t.range)) Palette.MUTED else Palette.INK),
            style = TNUM.copy(fontSize = g.sp(Dial.RANGE_FONT), fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
    val details = Dial.detailsText(t)
    if (details.isNotEmpty()) {
        FaceText(
            details,
            Modifier.baselineAt(Dial.DETAILS_Y).widthIn(max = g.dp(150f)),
            color = argb(if (ambient) Palette.AMB_DIM else Palette.MUTED),
            style = TNUM.copy(fontSize = g.sp(Dial.DETAILS_FONT)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (msg != null) {
        // a long lamp text («Руль на связи, BMS молчит») is set smaller first, ellipsized only past that
        val weight = if (ambient) FontWeight.Normal else FontWeight.SemiBold
        val measurer = rememberTextMeasurer()
        val maxPx = with(LocalDensity.current) { g.dp(Dial.MSG_MAX_W).toPx() }
        // measured in the style FaceText draws it with (the theme's font and spacing)
        val base = LocalFaceTextStyle.current
        val font = remember(msg.text, weight, g.unit, maxPx, base) {
            val style = base.merge(TextStyle(fontSize = g.sp(Dial.MSG_FONT), fontWeight = weight))
            Dial.msgFont(measurer.measure(msg.text, style, softWrap = false, maxLines = 1).size.width.toFloat(), maxPx)
        }
        FaceText(
            msg.text,
            Modifier.baselineAt(Dial.MSG_Y).widthIn(max = g.dp(Dial.MSG_MAX_W)),
            color = argb(if (ambient) Palette.AMB_INK else msg.argb),
            fontSize = g.sp(font),
            fontWeight = weight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val flow = if (live) Flow.of(t, fast) else Flow.NONE
    FaceText(
        Dial.powerText(watts, flow),
        Modifier.baselineAt(if (msg != null) Dial.POWER_Y else Dial.POWER_ALONE_Y),
        color = argb(if (ambient) Palette.AMB_INK else Dial.powerArgb(watts, flow)),
        style = TNUM.copy(fontSize = g.sp(Dial.POWER_FONT)),
        maxLines = 1,
    )
}

/**
 * The 12 tell-tales. Off: dim; lit: their colour on a soft disc; RED ones blink with [blink]; the temperature's ▲ while
 * it climbs fast. Ambient: lit ones only, outlined, steady, no disc.
 */
@Composable
private fun LampColumns(lamps: List<Lamp>, blink: State<Boolean>, ambient: Boolean, lowBit: Boolean, modifier: Modifier) {
    val paths = remember { Glyphs.BY_ID.mapValues { (_, gs) -> gs.map { PathParser().parsePathString(it.d).toPath() } } }
    val byId = lamps.associateBy { it.id }
    Canvas(modifier.graphicsLayer()) {
        val u = size.minDimension / Dial.GRID
        val on = blink.value
        val half = Dial.ICON * u / 2
        val scale = Dial.ICON * u / Glyphs.BOX
        TellTales.IDS.forEachIndexed { k, id ->
            val lamp = byId[id]
            val level = Lamps.shown(lamp?.level ?: LampLevel.OFF, on, ambient)
            if (ambient && level == LampLevel.OFF) return@forEachIndexed
            val (gx, gy) = Dial.lampCentre(k)
            val c = Offset(gx * u, gy * u)
            val colour = argb(if (level == LampLevel.OFF) Palette.LAMP_OFF else if (ambient && lowBit) Palette.AMB_INK else Lamps.argb(level))
            if (!ambient && level != LampLevel.OFF) drawCircle(colour.copy(alpha = Dial.GLOW_ALPHA), Dial.GLOW_R * u, c)
            val glyphs = Glyphs.BY_ID.getValue(id)
            val ps = paths.getValue(id)
            withTransform({ translate(c.x - half, c.y - half); scale(scale, scale, Offset.Zero) }) {
                for (i in glyphs.indices) {
                    val gl = glyphs[i]
                    val style = if (gl.fill && !ambient) Fill else Stroke(
                        width = if (gl.fill) 1.2f else gl.width * (if (ambient) 0.8f else 1f),
                        cap = if (gl.roundCap) StrokeCap.Round else StrokeCap.Butt,
                        join = if (gl.roundJoin) StrokeJoin.Round else StrokeJoin.Miter,
                    )
                    drawPath(ps[i], colour, style = style)
                }
            }
            if (lamp?.rising == true && level != LampLevel.OFF) {
                val tri = Path().apply {
                    Glyphs.RISING.forEachIndexed { j, (dx, dy) ->
                        val x = c.x + dx * u; val y = c.y + dy * u
                        if (j == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(tri, colour, style = if (ambient) Stroke(0.8f * u, join = StrokeJoin.Round) else Fill)
            }
        }
    }
}

/** Top line: what is wrong with the data ([label], see [Cluster.statusLabel]), or the time when all is live. Not
 *  dimmed with the values. */
@Composable
private fun StatusLine(h: Health, label: String?, ambient: Boolean, g: Grid, time: String) {
    if (label != null) {
        FaceText(
            label,
            Modifier.centreAt(Dial.STATUS_Y).widthIn(max = g.dp(Dial.STATUS_MAX_W)),
            color = argb(if (ambient) Palette.AMB_INK else h.tone),
            fontSize = g.sp(Dial.STATUS_FONT),
            lineHeight = g.sp(11.5f),
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    } else {
        FaceText(
            time,
            Modifier.baselineAt(Dial.CLOCK_Y),
            color = argb(if (ambient) Palette.AMB_DIM else Palette.MUTED),
            style = TNUM.copy(fontSize = g.sp(Dial.CLOCK_FONT)),
            maxLines = 1,
        )
    }
}
