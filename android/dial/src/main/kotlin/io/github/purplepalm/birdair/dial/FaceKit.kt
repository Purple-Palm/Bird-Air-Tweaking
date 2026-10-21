package io.github.purplepalm.birdair.dial

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * What the faces share (the galleries' common parts, gallery/{stack,takeover,grow,ring}.html): the mock's 480-px grid,
 * the colours, the lamp glyphs, the power arc, the speed track with its dot, limiter and rumble, the battery block, text
 * fitting, the motion curves, and the face's clock — frames only while something moves. A face is one file
 * (FaceStack.kt …) that draws a [DialState] with these.
 */

internal fun argb(v: Long) = Color(v)

/** The base text style of [FaceText]: the watch provides its theme's (Wear's LocalTextStyle), the phone gets this one. */
val LocalFaceTextStyle = staticCompositionLocalOf { TextStyle(fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 0.01.em) }

/**
 * Wear's Text without Wear: [style] (by default [LocalFaceTextStyle]) merged with the arguments the same way, drawn by
 * BasicText — the classic face's texts come out as v108's.
 */
@Composable
fun FaceText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalFaceTextStyle.current,
) {
    val merged = style.merge(
        color = color.takeOrElse { style.color.takeOrElse { Color.White } },
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign ?: TextAlign.Unspecified,
        lineHeight = lineHeight,
    )
    BasicText(AnnotatedString(text), modifier, merged, onTextLayout = {}, overflow = overflow, softWrap = softWrap, maxLines = maxLines)
}

/**
 * [target] (a fraction of a scale) as it is drawn: with [smooth], eased towards each new value on every frame by a
 * critically damped [Spring] (no overshoot, 95 % in [Spring.SETTLE_MS]); without, it jumps. Read the State inside a draw
 * lambda: then only the canvas redraws per frame, nothing recomposes. Frames run only while it moves.
 */
@Composable
fun rememberSpring(target: Float, smooth: Boolean): State<Float> {
    val spring = remember { Spring(x = target.toDouble()) }
    val shown = remember { mutableFloatStateOf(target) }
    val latest = rememberUpdatedState(target)
    LaunchedEffect(smooth) {
        if (!smooth) {
            snapshotFlow { latest.value }.collect { spring.snap(it.toDouble()); shown.floatValue = it }
            return@LaunchedEffect
        }
        while (true) {
            if (spring.settled && spring.target == latest.value.toDouble()) {
                snapshotFlow { latest.value }.first { it.toDouble() != spring.target }
            }
            var lastNs = -1L
            do {
                withFrameNanos { ns ->
                    val dt = if (lastNs < 0) Spring.FIRST_FRAME_S else ((ns - lastNs) / 1e9).coerceAtMost(Spring.MAX_FRAME_S)
                    lastNs = ns
                    spring.target = latest.value.toDouble()
                    spring.step(dt)
                    shown.floatValue = spring.x.toFloat()
                }
            } while (!(spring.settled && spring.target == latest.value.toDouble()))
        }
    }
    return shown
}

/**
 * A face's clock, on the state's clock ([DialState.nowMs] and every …Ms in it). [nowMs] given (shots, tests): that time,
 * no frames. Null (live): from each new state's [DialState.nowMs] on, advanced by display frames only while
 * [wakeAt] says so — it returns when the face next moves at a time: ≤ the time — now, frames; later — sleep until then
 * (a ▲ hop in 10 s); null — still until the next state. Idle there is no redraw loop; read the value while drawing.
 */
@Composable
fun rememberFaceClock(state: DialState, nowMs: Long?, wakeAt: (DialState, Long) -> Long? = FaceKit::wakeAt): State<Long> {
    if (nowMs != null) return rememberUpdatedState(nowMs)
    val clock = remember { mutableLongStateOf(state.nowMs) }
    val latest = rememberUpdatedState(state)
    val wake = rememberUpdatedState(wakeAt)
    LaunchedEffect(Unit) {
        snapshotFlow { latest.value }.collectLatest { st ->
            var anchorNs = -1L
            val at = { ns: Long -> if (anchorNs < 0) anchorNs = ns; st.nowMs + (ns - anchorNs) / 1_000_000 }
            // «infinite» frames: a red pulse may run for minutes; UI tests pause them (their InfiniteAnimationPolicy)
            withInfiniteAnimationFrameNanos { clock.longValue = at(it) }
            while (true) {
                val w = wake.value(st, clock.longValue) ?: break
                if (w > clock.longValue) delay(w - clock.longValue)
                withInfiniteAnimationFrameNanos { clock.longValue = at(it) }
            }
        }
    }
    return clock
}

/** The galleries' shared numbers and helpers; drawing primitives are the DrawScope extensions below. */
object FaceKit {
    /** The galleries draw on 480 px across (the Ultra's screen, 1 px ≈ 0,5′ at arm's length); [unit] is px per mock px. */
    const val GRID = 480f
    const val C = 240f

    fun unit(size: Size): Float = size.minDimension / GRID

    /** A point at [r] (mock px) and clock angle [deg] (0° at 12 o'clock, clockwise), in px of a canvas [u] px per mock px. */
    fun point(r: Float, deg: Float, u: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(((C + r * sin(a)) * u).toFloat(), ((C - r * cos(a)) * u).toFloat())
    }

    /** Clock angle of a point in mock px. */
    fun clockDeg(x: Float, y: Float): Float = ((Math.toDegrees(kotlin.math.atan2((x - C).toDouble(), (C - y).toDouble())) + 360) % 360).toFloat()

    // -- colours: the mock3 palette ([Palette]), the titles a little lighter where the lamp colour is dark at night

    fun lamp(level: LampLevel): Color = argb(Lamps.argb(level))

    /** Text in a lamp's colour: red and blue lifted (#ff3b30 is the darkest at night), the others as the lamp. */
    fun title(level: LampLevel): Color = when (level) {
        LampLevel.RED -> argb(TITLE_RED)
        LampLevel.BLUE -> argb(TITLE_BLUE)
        LampLevel.GREEN -> argb(TITLE_GREEN)
        else -> lamp(level)
    }

    const val TITLE_RED = 0xFFFF6257
    const val TITLE_BLUE = 0xFF6DB7FF
    const val TITLE_GREEN = 0xFF3ED857
    /** The chip under a «+N» and other dark discs. */
    const val CHIP = 0xFF121816
    /** The icon on a solid chip. */
    const val ON_SOLID = 0xFF0B0E0D

    /** A solid chip's fill at most (night: a full-white-ish disc glares). */
    const val NIGHT_FILL = 0.82f
    /** A pulsing red fill never drops below this share of its cap: the pulse is in the glow, rim and size, and a red
     *  disc at the bottom of its beat does not read as a dark one beside a steady amber. */
    const val RED_FILL_MIN = 0.8f
    /** A glow reaches at most this far past what it lights (mock px). */
    const val GLOW_MAX = 12f

    /** Only amber and red are solid: yellow, blue and green are a tinted disc with a ring, so an info lamp is never brighter than an alarm. */
    fun solid(level: LampLevel): Boolean = level == LampLevel.RED || level == LampLevel.AMBER

    /** The galleries' soft glow: 50 % at the centre, 38 % at 0,78 of [radius], gone at the edge. */
    fun glow(colour: Color, centre: Offset, radius: Float): Brush = Brush.radialGradient(
        0f to colour.copy(alpha = 0.5f), 0.78f to colour.copy(alpha = 0.38f), 1f to colour.copy(alpha = 0f),
        center = centre, radius = radius.coerceAtLeast(0.01f),
    )

    /** The lamp's icon: the crossed battery when the BMS cut the pack or is gone, else its own ([Glyphs]). */
    fun icon(l: Lamp): List<Glyph> = when {
        l.id == TellTales.BATTERY && (l.text == TellTales.T_BMS_CUT || l.text == TellTales.T_NO_BMS) -> Glyphs.BATTERY_CUT
        else -> Glyphs.BY_ID[l.id] ?: Glyphs.BY_ID.getValue(TellTales.SERVICE)
    }

    // -- motion: the galleries' curves (t in 0…1)

    fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    /** Cubic in-out. */
    fun ease(t: Float): Float = clamp01(t).let { if (it < 0.5f) 4 * it * it * it else 1 - (-2 * it + 2).let { k -> k * k * k } / 2 }
    fun easeOut(t: Float): Float = 1 - (1 - clamp01(t)).let { it * it * it }
    /** Overshoots by about 10 % and settles (the chips' bloom). */
    fun back(t: Float, c1: Float = 1.4f): Float = clamp01(t).let { x -> val c3 = c1 + 1; 1 + c3 * (x - 1) * (x - 1) * (x - 1) + c1 * (x - 1) * (x - 1) }
    fun smoothstep(a: Float, b: Float, x: Float): Float = clamp01((x - a) / (b - a)).let { it * it * (3 - 2 * it) }

    /** How far an animation of [durMs] started at [sinceMs] is at [nowMs], 0…1 (1 without a start). */
    fun progress(sinceMs: Long?, nowMs: Long, durMs: Long): Float =
        if (sinceMs == null) 1f else clamp01((nowMs - sinceMs).toFloat() / durMs)

    /** A damped spring's step response at [elapsedMs] (grow's pop: ω 16, ζ 0,45 — a single overshoot, settled in ~0,6 s). */
    fun pop(elapsedMs: Long, omega: Float = 16f, zeta: Float = 0.45f): Float {
        if (elapsedMs <= 0) return 0f
        val t = elapsedMs / 1000f
        if (zeta >= 1f) return 1 - (1 + omega * t) * exp(-omega * t)
        val wd = omega * sqrt(1 - zeta * zeta)
        return 1 - exp(-zeta * omega * t) * (cos(wd * t) + zeta / sqrt(1 - zeta * zeta) * sin(wd * t))
    }

    /** Every face's appearance takes at most this long; long motion is only for red and the rumble. */
    const val APPEAR_MS = 700L
    /** The red pulse: 1 Hz, 55–100 % brightness, from the moment it turned red (with its vibration); text never blinks. */
    const val PULSE_MS = 1000L
    const val PULSE_MIN = 0.55f
    /** The ▲ «быстро греется» hops when it appears and again every [BOB_EVERY_MS], for [BOB_MS]. */
    const val BOB_EVERY_MS = 10_000L
    const val BOB_MS = 900L
    /** A ripple (a buzz, a tap) spreads for this long. */
    const val RIPPLE_MS = 600L
    /** The limiter tap's flash. */
    const val TAP_MS = 700L

    /** A red solid fill's share of its cap at the pulse's brightness [br] ([PULSE_MIN]…1): [RED_FILL_MIN]…1. */
    fun redFill(br: Float): Float = lerp(RED_FILL_MIN, 1f, clamp01((br - PULSE_MIN) / (1 - PULSE_MIN)))

    /** The red pulse's brightness at [nowMs] for a lamp red since [sinceMs]: a cosine from 1 down to [PULSE_MIN] and back. */
    fun pulse(sinceMs: Long, nowMs: Long): Float {
        if (nowMs < sinceMs) return 1f
        val ph = ((nowMs - sinceMs) % PULSE_MS).toFloat() / PULSE_MS
        return PULSE_MIN + (1 - PULSE_MIN) * (0.5f + 0.5f * cos(2 * PI.toFloat() * ph))
    }

    /**
     * When a red lamp's pulse beats from: its last red vibration once it was red (a repeat re-phases it; one up to a beat
     * before counts — the state is built after the motor went, or it was another red's, felt the same), else when it
     * turned red ([DialLamp.levelSinceMs]: a new text at red keeps the beat).
     */
    fun redSince(l: DialLamp, cues: DialCues): Long {
        val turned = l.levelSinceMs
        val b = cues.redBuzzMs
        return if (b != null && b >= turned - PULSE_MS) b else turned
    }

    /** The ▲'s hop, 0…[height] px: on appearance at [sinceMs] and every [BOB_EVERY_MS] after. */
    fun bob(sinceMs: Long, nowMs: Long, height: Float): Float {
        if (nowMs < sinceMs) return 0f
        val ph = (nowMs - sinceMs) % BOB_EVERY_MS
        return if (ph > BOB_MS) 0f else height * abs(sin(PI.toFloat() * ph / (BOB_MS / 2f)))
    }

    /** A ripple's progress 0…1 at [nowMs] from [atMs], null before or after it. */
    fun ripple(atMs: Long?, nowMs: Long, durMs: Long = RIPPLE_MS): Float? {
        if (atMs == null || nowMs < atMs || nowMs - atMs > durMs) return null
        return (nowMs - atMs).toFloat() / durMs
    }

    /**
     * When a face built from these primitives next moves ([rememberFaceClock]): now while a red lamp pulses, the rumble
     * runs, the bulb check runs, a lamp's entrance or exit ([appearMs]), a tap or a buzz ripple; the next ▲ hop; null
     * when all is still. Ambient: never. A face with longer motion passes its own.
     */
    fun wakeAt(s: DialState, nowMs: Long, appearMs: Long = APPEAR_MS): Long? {
        if (s.ambient) return null
        if (s.lamps.anyRed || s.cues.rumble || s.bulbProgress(nowMs) != null) return nowMs
        val recent = listOfNotNull(
            s.lamps.changedMs?.plus(appearMs), s.cues.tapAtMs?.plus(TAP_MS), s.cues.buzz?.atMs?.plus(RIPPLE_MS * 2),
            s.bulbAtMs?.plus(TellTales.BULB_CHECK_MS + appearMs),
        ) + s.lamps.lit.map { it.changedMs + appearMs } + s.lamps.gone.map { it.changedMs + appearMs }
        if (recent.any { it > nowMs }) return nowMs
        val hops = s.lamps.lit.filter { it.lamp.rising }.map { l ->
            val since = l.sinceMs
            val k = ((nowMs - since) / BOB_EVERY_MS).coerceAtLeast(0)
            val start = since + k * BOB_EVERY_MS
            if (nowMs <= start + BOB_MS) maxOf(start, nowMs) else start + BOB_EVERY_MS
        }
        return hops.minOrNull()
    }

    // -- text

    /** A face's text at [px] canvas px (a fixed drawing: the screen, not the font scale), tabular digits. */
    fun DrawScope.style(px: Float, weight: FontWeight = FontWeight.Normal, tnum: Boolean = true): TextStyle =
        TextStyle(fontSize = px.toSp(), fontWeight = weight, fontFeatureSettings = if (tnum) "tnum" else null)

    /** The largest size from [fromPx] down to [toPx] (step 0,5 px) at which [text] fits [maxW] px; [toPx] if none does. */
    fun DrawScope.fitPx(m: TextMeasurer, text: String, maxW: Float, fromPx: Float, toPx: Float, weight: FontWeight = FontWeight.Normal): Float {
        var z = fromPx
        while (z > toPx && m.measure(text, style(z, weight), softWrap = false, maxLines = 1).size.width > maxW) z -= 0.5f
        return z
    }

    /** [text] in two lines at [px], the break that keeps the wider one narrowest and within [maxW]; null when none fits. */
    fun DrawScope.split2(m: TextMeasurer, text: String, maxW: Float, px: Float, weight: FontWeight = FontWeight.Normal): List<String>? {
        val w = text.split(' ')
        var best: Pair<List<String>, Int>? = null
        for (i in 1 until w.size) {
            val a = w.subList(0, i).joinToString(" ")
            val b = w.subList(i, w.size).joinToString(" ")
            val st = style(px, weight)
            val wide = maxOf(m.measure(a, st, softWrap = false, maxLines = 1).size.width, m.measure(b, st, softWrap = false, maxLines = 1).size.width)
            if (wide <= maxW && (best == null || wide < best.second)) best = listOf(a, b) to wide
        }
        return best?.first
    }
}

/** Where [drawTextAt]'s x is: the text's start, centre or end. */
enum class Anchor { START, CENTER, END }

/**
 * [text] in [style] with its first baseline at [baseline] px, anchored at [x]; [halo] px of black under it first (the
 * mock's paint-order stroke), [alpha]. Returns the layout (its size for what comes next).
 */
fun DrawScope.drawTextAt(
    m: TextMeasurer, text: String, style: TextStyle, x: Float, baseline: Float, colour: Color,
    anchor: Anchor = Anchor.CENTER, halo: Float = 0f, alpha: Float = 1f,
): TextLayoutResult {
    val l = m.measure(text, style, softWrap = false, maxLines = 1)
    val left = when (anchor) { Anchor.START -> x; Anchor.CENTER -> x - l.size.width / 2f; Anchor.END -> x - l.size.width }
    val at = Offset(left, baseline - l.firstBaseline)
    if (halo > 0f) drawText(l, Color.Black, at, alpha = alpha, drawStyle = Stroke(halo, join = StrokeJoin.Round))
    drawText(l, colour, at, alpha = alpha, drawStyle = Fill)
    return l
}

private val PATHS = HashMap<String, Path>()

/** A glyph's path, parsed once. */
fun glyphPath(d: String): Path = PATHS.getOrPut(d) { PathParser().parsePathString(d).toPath() }

/**
 * A lamp icon ([Glyphs]: a 24-unit box) [size] px square around [centre]: fills and strokes as mock3; [weight] thickens
 * the strokes (a chip's icon 1,2), ambient draws fills as outlines; [knock] the colour of knock-out strokes.
 */
fun DrawScope.drawGlyph(
    glyphs: List<Glyph>, centre: Offset, size: Float, colour: Color,
    ambient: Boolean = false, weight: Float = 1f, alpha: Float = 1f, knock: Color = Color.Black,
) {
    val k = size / Glyphs.BOX
    withTransform({ translate(centre.x - size / 2, centre.y - size / 2); scale(k, k, Offset.Zero) }) {
        for (g in glyphs) {
            val style = if (g.fill && !ambient) Fill else Stroke(
                width = (if (g.fill) 1.2f else g.width) * (if (ambient) 0.85f else weight),
                cap = if (g.roundCap) StrokeCap.Round else StrokeCap.Butt,
                join = if (g.roundJoin) StrokeJoin.Round else StrokeJoin.Miter,
            )
            drawPath(glyphPath(g.d), if (g.knock) knock else colour, alpha = alpha, style = style)
        }
    }
}

/** The ▲ «быстро греется» at the top right of a chip [chip] px across around [centre], lifted by [bob] px. */
fun DrawScope.drawRising(centre: Offset, chip: Float, colour: Color, ambient: Boolean = false, bob: Float = 0f, alpha: Float = 1f) {
    val s = chip * 0.22f
    val cx = centre.x + chip * 0.37f
    val cy = centre.y - chip * 0.4f - bob
    val p = Path().apply { moveTo(cx - s / 2, cy + s * 0.43f); lineTo(cx, cy - s * 0.43f); lineTo(cx + s / 2, cy + s * 0.43f); close() }
    if (ambient) drawPath(p, colour, alpha = alpha, style = Stroke(1.5f, join = StrokeJoin.Round))
    else {
        drawPath(p, colour, alpha = alpha)
        drawPath(p, Color.Black, alpha = alpha, style = Stroke(2.5f * chip / 104f, join = StrokeJoin.Round))
    }
}

/**
 * The power arc on radius [r] px, [w] px wide: the ±135° track, the value [fraction] from 12 o'clock (+ traction
 * clockwise in [Palette.DRAW], − regen counter-clockwise in [Palette.FLOW]) with a round tip that lands on the value
 * ([Arc.bar]), the white zero tick from [tick] (inner, outer radius px; null: none). Ambient: a hairline track, the
 * value [ambientW] px wide, grey on low-bit.
 */
fun DrawScope.drawPowerArc(
    fraction: Float, r: Float, w: Float, ambient: Boolean = false, lowBit: Boolean = false,
    tick: Pair<Float, Float>? = null, tickW: Float = 3f, ambientW: Float = 4f, ambientTrackW: Float = 2f,
) {
    val tl = Offset(center.x - r, center.y - r)
    val sz = Size(2 * r, 2 * r)
    val (ts, tw) = Arc.track
    drawArc(argb(if (ambient) Palette.AMB_LINE else Palette.POWER_TRACK), ts, tw, false, tl, sz, style = Stroke(if (ambient) ambientTrackW else w, cap = StrokeCap.Round))
    val bw = if (ambient) ambientW else w
    val colour = argb(if (ambient && lowBit) Palette.AMB_INK else Arc.colorFor(fraction.toDouble()))
    Arc.bar(fraction, Math.toDegrees((bw / 2 / r).toDouble()).toFloat())?.let { drawPath(arcBar(r, bw, it), colour) }
    if (tick != null) drawLine(
        argb(if (ambient) Palette.AMB_DIM else Palette.INK),
        Offset(center.x, center.y - tick.second), Offset(center.x, center.y - tick.first), strokeWidth = tickW,
    )
}

/** [b] as an outline [width] wide on radius [r]: square at 12 o'clock, a half-disc tip past the body. */
fun DrawScope.arcBar(r: Float, width: Float, b: Arc.Bar): Path = Path().apply {
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

/**
 * The speed track as the galleries draw it, in mock px (× the canvas's px per mock px): radius [r], line [w], the ticks
 * every 5 units [tick] long either side, the dot [dot] in radius with the speed in it at [font], the limiter's tick
 * [limitHalf] either side (stack: r 195, w 3, dot 9, font 10, limit 9).
 */
data class SpeedTrack(
    val r: Float = 195f,
    val w: Float = 3f,
    val tick: Float = 6f,
    val dot: Float = 9f,
    val font: Float = 10f,
    val limitHalf: Float = 9f,
)

/**
 * The speed track of [s] at [nowMs]: the ±135° line, ticks every 5 of its unit, the passed part, the mode's limit in red
 * — thicker and glowing at or past the tap's point, flashing at the tap ([DialCues.tapAtMs], with a white ring from the
 * dot); the rumble's point in blue; while the rumble runs ([DialCues.rumble]) the dot turns blue and trembles, a blue
 * string trembles from the rumble's point to it and two rings spread. [fraction]: the dot's place (a spring's value),
 * by default the speed's. Ambient: lines only, no motion.
 */
fun DrawScope.drawSpeedTrack(
    s: DialState, spec: SpeedTrack, nowMs: Long, m: TextMeasurer, fraction: Float? = s.speed.fraction(),
) {
    val u = size.minDimension / FaceKit.GRID
    val amb = s.ambient
    val sp = s.speed
    val r = spec.r
    val deg = { f: Float -> Dial.START_DEG + Dial.SPAN_DEG * f.coerceIn(0f, 1f) }
    val pt = { rr: Float, d: Float -> FaceKit.point(rr, d, u) }
    val rp = r * u
    val tl = Offset(center.x - rp, center.y - rp)
    val sz = Size(2 * rp, 2 * rp)
    val start = Dial.composeDeg(Dial.START_DEG)
    drawArc(argb(if (amb) Palette.AMB_LINE else Palette.SPEED_TRACK), start, Dial.SPAN_DEG, false, tl, sz, style = Stroke((if (amb) 1.6f else spec.w) * u, cap = StrokeCap.Round))
    if (!amb) {
        var v = 0.0
        while (v <= sp.max + 1e-9) {
            val d = deg(sp.fraction(v) ?: 0f)
            drawLine(argb(Palette.SPEED_TICK), pt(r - spec.tick, d), pt(r + spec.tick, d), strokeWidth = 2f * u)
            v += Dial.TICK_EVERY
        }
    }
    val live = s.live
    val tapQ = if (amb) null else FaceKit.ripple(s.cues.tapAtMs, nowMs, FaceKit.TAP_MS)
    sp.fraction(sp.limit)?.let { lf ->
        val d = deg(lf)
        val at = live && !amb && sp.atLimiter
        val g = pt(r, d)
        if (at) drawCircle(FaceKit.glow(argb(Palette.LIMIT), g, 15f * u), 15f * u, g, alpha = 0.55f)
        if (tapQ != null) drawCircle(FaceKit.glow(argb(Palette.LIMIT), g, (20f + 10f * tapQ) * u), (20f + 10f * tapQ) * u, g, alpha = 1 - tapQ)
        val flash = if (tapQ != null) 1 - tapQ else 0f
        drawLine(
            argb(if (amb && s.lowBit) Palette.AMB_DIM else Palette.LIMIT), pt(r - spec.limitHalf, d), pt(r + spec.limitHalf, d),
            strokeWidth = ((if (at) 5f else 3f) + 3f * flash) * u, cap = StrokeCap.Round,
        )
    }
    if (!amb) sp.fraction(sp.rumbleAt)?.let { rf ->
        val d = deg(rf)
        drawLine(argb(Palette.LAMP_BLUE), pt(r - spec.tick, d), pt(r + spec.tick, d), strokeWidth = 2f * u, alpha = 0.75f)
    }
    val f = fraction ?: return
    val va = deg(f)
    if (sp.value != null && sp.value > 0.05) drawArc(
        argb(if (amb) Palette.AMB_DIM else Palette.MUTED), start, va - Dial.START_DEG, false, tl, sz,
        style = Stroke((if (amb) 1.6f else spec.w) * u, cap = StrokeCap.Round),
    )
    val rumble = live && !amb && s.cues.rumble
    val blue = argb(Palette.LAMP_BLUE)
    val sec = nowMs / 1000.0
    if (rumble) sp.fraction(sp.rumbleAt)?.let { rf ->
        // a string trembling from the rumble's point to the dot (7 Hz along it)
        val a0 = deg(rf)
        val len = r * Math.toRadians((va - a0).toDouble()).toFloat()
        if (len > 1f) {
            val p = Path()
            var x = 0f
            while (x <= len) {
                val env = minOf(1f, x / 5f, (len - x) / 5f)
                val rr = r + 4.5f * env * sin(2 * PI * x / 9 - sec * 2 * PI * 7).toFloat()
                val q = pt(rr, a0 + Math.toDegrees((x / r).toDouble()).toFloat())
                if (x == 0f) p.moveTo(q.x, q.y) else p.lineTo(q.x, q.y)
                x += 1.5f
            }
            drawPath(p, blue, style = Stroke(2.6f * u, join = StrokeJoin.Round))
        }
    }
    var c = pt(r, va)
    if (rumble) {
        c += Offset((1.6f * sin(sec * 2 * PI * 23)).toFloat() * u, (1.3f * cos(sec * 2 * PI * 19)).toFloat() * u)
        for (i in 0 until 2) {
            val q = ((nowMs / 450.0 + i * 0.5) % 1.0).toFloat()
            drawCircle(blue, (10f + 16f * q) * u, c, alpha = 0.75f * (1 - q), style = Stroke((2.5f - q) * u))
        }
        drawCircle(FaceKit.glow(blue, c, 16f * u), 16f * u, c)
    }
    if (tapQ != null) drawCircle(Color.White, (10f + 30f * FaceKit.easeOut(tapQ)) * u, c, alpha = 0.85f * (1 - tapQ), style = Stroke((3f - 2f * tapQ) * u))
    val label = sp.value?.let { maxOf(0.0, it).let { v -> Math.round(v).toString() } }
    val st = with(FaceKit) { style(spec.font * u, FontWeight.Bold) }
    if (amb) {
        drawCircle(Color.Black, spec.dot * u, c)
        drawCircle(argb(Palette.AMB_INK), spec.dot * u, c, style = Stroke(1.6f * u))
        if (label != null) drawTextAt(m, label, st, c.x, c.y + spec.font * 0.36f * u, argb(Palette.AMB_INK))
    } else {
        drawCircle(if (rumble) blue else argb(Palette.INK), spec.dot * u, c)
        if (label != null) drawTextAt(m, label, st, c.x, c.y + spec.font * 0.36f * u, if (rumble) Color.White else Color.Black)
    }
}

/**
 * The battery block (stack's): the body at [topLeft] [size] (px) with a [line] px outline and its nub, the fill in [tone]
 * for [pct], [text] («56,3 %») over it at [fontPx] (smaller to fit the fill), bold white with a black halo; [charge]: the
 * light band's place 0…1 along the fill (charging, once every 4 s), null: none. Ambient: outlines, the % thin.
 */
fun DrawScope.drawBattery(
    m: TextMeasurer, topLeft: Offset, size: Size, line: Float, pct: Double?, tone: Color, text: String, fontPx: Float,
    ambient: Boolean = false, lowBit: Boolean = false, charge: Float? = null, alpha: Float = 1f,
) {
    val h = size.height
    val w = size.width
    val inset = h * 0.121f
    val fx = topLeft.x + inset
    val fy = topLeft.y + inset
    val fh = h - 2 * inset
    val fwMax = w - 2 * inset
    val fw = if (pct == null || !pct.isFinite()) 0f else maxOf(h * 0.09f, fwMax * pct.coerceIn(0.0, 100.0).toFloat() / 100)
    val rx = h * 0.18f
    val nx = topLeft.x + w + 2f * h / 66f
    val nh = h * 0.42f
    val ny = topLeft.y + (h - nh) / 2
    val nw = h * 0.18f
    val frx = minOf(rx * 0.55f, fw / 2)
    val toneShown = if (ambient && lowBit) argb(Palette.AMB_INK) else tone
    if (ambient) {
        val dim = argb(Palette.AMB_DIM)
        drawRoundRect(dim, topLeft, size, CornerRadius(rx), alpha = alpha, style = Stroke(2f * h / 66f))
        drawRoundRect(dim, Offset(nx, ny), Size(nw, nh), CornerRadius(nw / 3), alpha = alpha, style = Stroke(1.6f * h / 66f))
        if (fw > 0f) drawRoundRect(toneShown, Offset(fx, fy), Size(fw, fh), CornerRadius(frx), alpha = alpha, style = Stroke(1.6f * h / 66f))
    } else {
        val ink2 = argb(Palette.INK2)
        drawRoundRect(ink2, topLeft, size, CornerRadius(rx), alpha = alpha, style = Stroke(line))
        drawRoundRect(ink2, Offset(nx, ny), Size(nw, nh), CornerRadius(nw / 3), alpha = alpha)
        if (fw > 0f) {
            drawRoundRect(toneShown, Offset(fx, fy), Size(fw, fh), CornerRadius(frx), alpha = 0.9f * alpha)
            if (charge != null && charge in 0f..1f) {
                val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(fx, fy, fx + fw, fy + fh, CornerRadius(frx))) }
                val k = h / 66f
                val bx = fx - 24 * k + (fw + 24 * k) * charge
                clipPath(clip) {
                    val band = Path().apply { moveTo(bx, fy + fh); relativeLineTo(10 * k, -fh); relativeLineTo(14 * k, 0f); relativeLineTo(-10 * k, fh); close() }
                    drawPath(band, Color.White, alpha = 0.28f * alpha)
                }
            }
        }
    }
    val maxW = fwMax + 6 * h / 66f
    val first = m.measure(text, with(FaceKit) { style(fontPx, FontWeight.Bold) }, softWrap = false, maxLines = 1)
    val px = if (first.size.width > maxW) fontPx * maxW / first.size.width else fontPx
    val tx = topLeft.x + w / 2
    val ty = topLeft.y + h / 2 + px * 0.36f
    if (ambient) drawTextAt(m, text, with(FaceKit) { style(px) }, tx, ty, argb(Palette.AMB_INK), halo = px / 7, alpha = alpha)
    else drawTextAt(m, text, with(FaceKit) { style(px, FontWeight.Bold) }, tx, ty, Color.White, halo = px / 8, alpha = alpha)
}
