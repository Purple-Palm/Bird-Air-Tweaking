package io.github.purplepalm.birdair.dial

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * DialFace.RING — «Кольцо» (gallery/ring.html, its 480-px grid; the canvas is scaled to it). The 12 lamps are the 12
 * «hours» of a ring (TellTales.IDS 0–5 clockwise from 12, 6–11 counter-clockwise from 11, as the old columns) between the
 * speed track (r 148) and the power arc (r 230, 14 px):
 *  - off: a hairline 1,5 px at r 192 and the place's icon 18 px in #2c3532 (ambient: nothing);
 *  - quiet (TellTales.QUIET: service, weak, ECO): only the icon, 20 px in its colour (service 40 %), never the centre;
 *  - lit: the hairline swells inward from r 213 into a tile with rounded corners (amber 28° × 42 px solid at 74 %, red
 *    34° × 48 px, the brightest, breathing 1 Hz; yellow / blue / green «к сведению»: tinted with a 3-px edge, never
 *    solid), its icon 34 px (red 40) black on solid, in its colour on the others; two neighbours share the 27,5° between
 *    them; solid tiles glow inward and sideways (two fills, ≤ 9 px, none toward the power arc), brighter as they light;
 *  - the centre: the clock (or the StatusLine) and the battery 132×62 on top; under them either «детали» (the range 32 px,
 *    «V · °C · MODE», the power, the limiter's / bulb check's line) or «лампа»: the main lamp — its title 23 px in its
 *    colour, its icon 80 px, the value 42 px + unit 22 px, the detail 16 px — and the details, range and power smaller.
 *    The centre is TellTales.top (the level, then the IDS order, DialLamps.alerts); yellow and «холодно» hold it 5 s,
 *    then only the tile stays; a tap on the watch shows the next lit lamp for 6 s; when more than one is lit, the tile in
 *    the centre is ringed in white. A change of the centre is one 0,4 s crossfade, no dip to black.
 * The judge's rules: appearance ≤ 700 ms (a tile pops in 0,42 s with an overshoot, goes out in 0,3 s); long motion only for
 * red (FaceKit.pulse from its buzz, 55–100 %, on the fill, glow and icon — text never blinks) and the rumble; the ▲
 * «быстро греется» hops on appearance and every 10 s; the solid fill ≤ NIGHT_FILL; NBSP between a number and its unit.
 * Real data: texts are Lamp.title / valueText / detail, shrunk to fit, the detail in the value's place when there is no
 * value, ellipsized past that, every text inside the speed track's circle (or its bottom gap); lamps' history from
 * DialLamps (sinceMs, changedMs, before, gone), the red pulse from DialCues.buzz, the limiter's flash and line from
 * tapAtMs, the trembling dot from the rumble; health.alpha dims the values (not the status line nor the bulb check); the
 * bulb check sweeps a comet round from 12 o'clock lighting each place; ambient: outlines, static, no glow, the burn-in
 * shift. [RingLayout] lays a frame out (pure: the JVM tests check it), [RingPen] paints it, frames only while
 * [RingLayout.wakeAt] says something moves.
 */

/** A tap's choice: lamp [id] in the centre from [atMs] (the state's clock) for [RingLayout.PICK_MS]. */
@Immutable
data class RingPick(val id: String, val atMs: Long)

/** «Кольцо». [nowMs]: a fixed time (shots, tests), null: live — the face's own clock while something moves. */
@Composable
internal fun FaceRing(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) {
    // the pick and the state's time when it was made: a state from before it (the phone's previews start each scenario
    // at 0) drops it; old data (no new state) keeps it
    var tapped by remember { mutableStateOf<Pair<RingPick, Long>?>(null) }
    val arrivedAt = remember(state) { SystemClock.uptimeMillis() }
    val held = tapped?.takeIf { (p, base) -> state.nowMs >= base && state.nowMs <= p.atMs + RingLayout.PICK_MS + RingLayout.XFADE_MS }?.first
    // a tap restarts the face's clock at its moment, so the crossfade starts at once
    val driver = if (held != null && held.atMs > state.nowMs) state.copy(nowMs = held.atMs) else state
    val clock = rememberFaceClock(driver, nowMs) { s, now -> RingLayout.wakeAt(s, now, held) }
    val springs = nowMs == null && state.smooth && !state.ambient
    val power = rememberSpring(state.power.fraction, springs)
    val speed = rememberSpring(state.speed.fraction() ?: 0f, springs)
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val base = LocalFaceTextStyle.current
    val pen = remember { RingPen() }
    val onTap = rememberUpdatedState {
        val at = nowMs ?: maxOf(clock.value, state.nowMs + (SystemClock.uptimeMillis() - arrivedAt))
        RingLayout.next(state, at, held)?.let { tapped = RingPick(it, at) to state.nowMs }
    }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val (dx, dy) = state.burnInDp
        Canvas(
            Modifier.size(side).offset(dx.dp, dy.dp).pointerInput(Unit) {
                // a tap nobody took (the pager takes a swipe, the phone's picker its click), seen last: the next lamp
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    while (true) {
                        val c = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed || (c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                        if (c.uptimeMillis - down.uptimeMillis > viewConfiguration.longPressTimeoutMillis) break
                        if (!c.pressed) { onTap.value(); break }
                    }
                }
            },
        ) {
            val now = clock.value
            val u = FaceKit.unit(size)
            val f = RingLayout.frame(
                state, now, pen.meter(measurer, base, u, this), held,
                powerF = if (springs) power.value else state.power.fraction,
                speedF = if (state.speed.value == null) null else if (springs) speed.value else state.speed.fraction(),
            )
            pen.paint(this, f, state, now, measurer, u)
        }
    }
}

/**
 * «Кольцо» at a moment, laid out on the mock's 480-px grid: what [FaceRing] paints, in order ([Frame.ops]), and what the
 * tests check ([Frame.boxes], [Frame.tiles], [problems]). Pure: text widths come from a [Measure] (the face's
 * TextMeasurer, or [ROUGH] on the JVM). Angles are clock angles (0° at 12 o'clock, clockwise).
 */
object RingLayout {
    /** The width in mock px of [text] at [px] mock px and [weight] (400…700). */
    fun interface Measure {
        fun width(text: String, px: Float, weight: Int): Float
    }

    /** Roboto's advances, roughly and on the wide side (JVM tests have no fonts). */
    val ROUGH: Measure = Measure { text, px, weight ->
        var w = 0f
        for (c in text) w += when {
            c == ' ' || c == NBSP -> 0.26f
            c == ' ' -> 0.18f
            c.isDigit() -> 0.57f
            c in ",.:;·'!|" -> 0.27f
            c in "ШЩЖЮМЫWMшщжюмыwm%" -> 0.86f
            c == '—' || c == '…' -> 0.9f
            c.isUpperCase() -> 0.7f
            c.isLowerCase() -> 0.58f
            else -> 0.62f
        }
        w * px * when { weight >= 700 -> 1.07f; weight >= 600 -> 1.04f; weight >= 500 -> 1.02f; else -> 1f }
    }

    // -- the grid (gallery/ring.html)
    const val C = 240f
    const val POWER_R = 230f
    const val POWER_W = 14f
    const val ZERO_IN = 221f
    const val ZERO_OUT = 240f
    /** The tiles' outer edge (a black 10 px to the power arc's inner edge) and their depth: 42, red 48. */
    const val RING_OUT = 213f
    const val T_TILE = 42f
    const val T_RED = 48f
    const val HAIR_R = 192f
    const val HAIR_W = 1.5f
    /** A tile's half width (°): its own, red's, and what two lit neighbours share (30° apart). */
    const val HALF = 14f
    const val HALF_RED = 17f
    const val SPACE = 27.5f
    const val CORNER = 8f
    const val EDGE = 3f
    const val TILE_ICON = 34f
    const val RED_ICON = 40f
    const val OFF_ICON = 18f
    const val QUIET_ICON = 20f
    const val BIG_ICON = 80f
    val SPEED = SpeedTrack(r = 148f, w = 3f, tick = 5f, dot = 10f, font = 12f, limitHalf = 9f)
    const val BAT_X = 174f
    const val BAT_Y = 138f
    const val BAT_W = 132f
    const val BAT_H = 62f
    /** Texts stay inside the speed track's ticks (r 148 − 5) or in its bottom gap (±[GAP_DEG] round 6 o'clock) inside
     *  the bottom tiles (red's inner edge 165, its pop's overshoot to 162). */
    const val R_TEXT = 143f
    const val R_GAP = 160f
    const val GAP_DEG = 40f
    /** A text line's box: the cap height over the baseline, the descent under it (× px). */
    const val ASC = 0.74f
    const val DESC = 0.22f
    /** The solid fills at most: amber, red at its brightest (the night cap, FaceKit.NIGHT_FILL); red stands out by its
     *  size, its glow and its pulse. */
    const val AMBER_FILL = FaceKit.NIGHT_FILL
    const val RED_FILL = FaceKit.NIGHT_FILL

    // -- timing (all ≤ FaceKit.APPEAR_MS but the red pulse and the rumble, which are the kit's)
    const val GROW_MS = 420L
    const val EXIT_MS = 300L
    /** A tile that turns amber or red glows brighter for this long. */
    const val FLASH_MS = 700L
    /** The centre's crossfade: the old one out in [OUT_MS], the new one in from [IN_FROM_MS] to [XFADE_MS]. */
    const val OUT_MS = 180L
    const val IN_FROM_MS = 100L
    const val XFADE_MS = 400L
    /** Yellow and «холодно» hold the centre this long after they lit or changed; a tap's pick holds it [PICK_MS]. */
    const val HOLD_MS = 5_000L
    const val PICK_MS = 6_000L
    /** The bulb check (TellTales.BULB_CHECK_MS): the comet's head goes round in [BULB_SWEEP_MS], each place pops in
     *  [BULB_POP_MS] as it passes, all fade from [BULB_FADE_MS]; the comet is gone at [COMET_MS]. */
    const val BULB_SWEEP_MS = 950f
    const val BULB_POP_MS = 300f
    const val BULB_FADE_MS = 1_200f
    const val COMET_MS = 1_000f
    /** «Ограничитель · 15,6 mph» under the power for this long after the tap, in and out. */
    const val LINE_MS = 3_000L
    const val LINE_IN_MS = 250L
    const val LINE_OUT_MS = 300L
    /** Charging: a light band runs along the battery's fill when it starts and every [SWEEP_EVERY_MS]. */
    const val SWEEP_MS = 900L
    const val SWEEP_EVERY_MS = 10_000L
    /** The tiles' pop overshoots ~12 %. */
    const val BACK = 1.9f

    private const val NBSP = TellTales.NBSP
    private val BULB = TellTales.bulbCheck().associate { it.id to it.level }
    private val QUIET_ALPHA = mapOf(TellTales.SERVICE to 0.4f, TellTales.WEAK to 0.75f, TellTales.ECO to 0.9f)
    private val ANGLES = FloatArray(TellTales.IDS.size) { i -> (if (i < 6) i else 17 - i) * 30f }
    private val CW = IntArray(ANGLES.size) { i -> ANGLES.indices.first { j -> ANGLES[j] == (ANGLES[i] + 30f) % 360f } }
    private val CCW = IntArray(ANGLES.size) { i -> ANGLES.indices.first { j -> ANGLES[j] == (ANGLES[i] + 330f) % 360f } }

    private val INK = argb(Palette.INK)
    private val INK2 = argb(Palette.INK2)
    private val MUTED = argb(Palette.MUTED)
    private val OFF = argb(Palette.LAMP_OFF)
    private val ON_SOLID = argb(FaceKit.ON_SOLID)
    private val AMB_INK = argb(Palette.AMB_INK)
    private val AMB_DIM = argb(Palette.AMB_DIM)

    /** Clock angle of lamp [id]'s place; null for an id this build does not know (it has no place on the ring). */
    fun angle(id: String): Float? = TellTales.IDS.indexOf(id).takeIf { it >= 0 }?.let { ANGLES[it] }

    fun point(r: Float, deg: Float): Offset = FaceKit.point(r, deg, 1f)

    private fun pxDeg(px: Float, r: Float): Float = px / r * 180f / PI.toFloat()

    /** How far from x = 240 a text line spanning [top]…[bottom] may reach: inside [R_TEXT], or in the bottom gap. */
    fun halfWidth(top: Float, bottom: Float): Float {
        val dy = max(abs(top - C), abs(bottom - C))
        val inner = if (dy < R_TEXT) sqrt(R_TEXT * R_TEXT - dy * dy) else 0f
        val gap = if (top > C && bottom - C < R_GAP) {
            min((top - C) * tan(Math.toRadians(GAP_DEG.toDouble())).toFloat(), sqrt(R_GAP * R_GAP - (bottom - C) * (bottom - C)))
        } else 0f
        return max(inner, gap)
    }

    /** A point where text may be: inside [R_TEXT], or in the bottom gap inside [R_GAP]. */
    fun inside(x: Float, y: Float): Boolean {
        val r = hypot(x - C, y - C)
        if (r <= R_TEXT + 0.01f) return true
        val fromSix = abs(180f - FaceKit.clockDeg(x, y))
        return r <= R_GAP + 0.01f && fromSix <= GAP_DEG + 0.01f
    }

    // -- what a frame holds

    sealed interface Op

    /** The power arc and the speed track (FaceKit's), drawn at [alpha] (the health's dimming). */
    data class Power(val fraction: Float, val alpha: Float) : Op
    data class Speed(val fraction: Float?, val alpha: Float) : Op

    /** An off place's hairline either side of its icon at [deg]. */
    data class Hair(val deg: Float, val alpha: Float) : Op

    /** An annular sector [r0]…[r1], [a0]…[a1] with corners rounded by [cr], filled. */
    data class Sector(val r0: Float, val r1: Float, val a0: Float, val a1: Float, val cr: Float, val colour: Color, val alpha: Float) : Op

    /** An arc on [r] from [a0] to [a1], [width] wide, square ends (the bulb check's comet). */
    data class Arc(val r: Float, val a0: Float, val a1: Float, val width: Float, val colour: Color, val alpha: Float) : Op
    data class Disc(val x: Float, val y: Float, val r: Float, val colour: Color, val alpha: Float) : Op

    /** A lamp icon [px] square around x, y; [outline]: ambient strokes only. */
    data class Icon(
        val id: String, val glyphs: List<Glyph>, val x: Float, val y: Float, val px: Float, val colour: Color, val alpha: Float,
        val weight: Float = 1f, val outline: Boolean = false,
    ) : Op

    /** The ▲ «быстро греется», [s] its half width, its middle at x, y. */
    data class Tri(val x: Float, val y: Float, val s: Float, val colour: Color, val alpha: Float, val outline: Boolean) : Op

    /** The battery (FaceKit's drawBattery at [BAT_X]…), [charge] the light band's place. */
    data class Battery(val pct: Double?, val tone: Color, val text: String, val charge: Float?, val alpha: Float) : Op

    /** One line of text, its baseline at [baseline], fitted and ellipsized already. */
    data class Txt(
        val text: String, val x: Float, val baseline: Float, val px: Float, val weight: Int, val colour: Color, val alpha: Float,
        val anchor: Anchor = Anchor.CENTER,
    ) : Op

    /** Who drew a box: the overlap rules hold within what is visible together. */
    enum class Group { STATUS, BATTERY, DETAILS, LOWER, OUT, IN }

    /** A text's or an icon's box in mock px, at [alpha]. */
    data class Box(val group: Group, val what: String, val l: Float, val t: Float, val r: Float, val b: Float, val alpha: Float) {
        fun overlaps(o: Box): Boolean = l < o.r - 0.5f && o.l < r - 0.5f && t < o.b - 0.5f && o.t < b - 0.5f
        val corners: List<Offset> get() = listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))
    }

    /** A tile's sector (no glow, no ring), [k] its growth. */
    data class TileBox(val id: String, val level: LampLevel, val r0: Float, val r1: Float, val a0: Float, val a1: Float, val k: Float, val bulb: Boolean)

    /** What the centre shows: [tgt] (null: «детали»), [prev] the one before while [tauMs] (since the change) is in the
     *  crossfade; null: steady. */
    data class Centre(val tgt: DialLamp?, val prev: DialLamp?, val tauMs: Long?)

    class Frame(val ops: List<Op>, val centre: Centre, val boxes: List<Box>, val tiles: List<TileBox>)

    // -- the centre: TellTales.top, the holds, a tap's pick, the crossfade

    /** Yellow and «холодно» are news for [HOLD_MS], then only the tile says it. */
    private fun holds(l: DialLamp): Boolean = l.level == LampLevel.YELLOW || l.id == TellTales.COLD

    /** What may take the centre: [DialLamps.alerts] (TellTales.top's order) but a level this build reads as OFF. */
    private fun alerts(s: DialState): List<DialLamp> = s.lamps.alerts.filter { it.level != LampLevel.OFF }

    private fun bulbRunning(s: DialState, at: Long): Boolean = !s.ambient && (s.bulbProgress(at) != null || (s.bulbOn && s.bulbAtMs == null))

    private fun choose(alerts: List<DialLamp>, s: DialState, at: Long, pick: RingPick?): DialLamp? {
        if (bulbRunning(s, at)) return null
        if (pick != null && !s.ambient && at >= pick.atMs && at < pick.atMs + PICK_MS) alerts.firstOrNull { it.id == pick.id }?.let { return it }
        return alerts.firstOrNull { !holds(it) || at - it.changedMs < HOLD_MS }
    }

    /** The lamp in the centre at [at]: [pick] while it holds, else the strongest one lit (TellTales.top) that still holds. */
    fun target(s: DialState, at: Long, pick: RingPick? = null): DialLamp? = choose(alerts(s), s, at, pick)

    /** The lit set before [DialLamps.changedMs] as alerts, with what is known of their times. */
    private fun beforeAlerts(s: DialState): List<DialLamp> {
        val at = s.lamps.changedMs ?: s.nowMs
        return s.lamps.before.filter { it.id !in TellTales.QUIET && it.level != LampLevel.OFF }.map { l ->
            val now = s.lamps[l.id]
            when {
                now != null && now.level == l.level && now.lamp.text == l.text -> now.copy(lamp = l)
                now != null -> DialLamp(l, now.sinceMs, now.sinceMs)
                else -> s.lamps.gone.firstOrNull { it.id == l.id }?.let { DialLamp(l, it.sinceMs) } ?: DialLamp(l, at)
            }
        }.sortedWith(DialLamps.SEVERITY)
    }

    /** The centre at [now]: the target, and the one before while a change (the lit set, a hold's end, a pick, the bulb
     *  check) is under [XFADE_MS] old. */
    fun centreAt(s: DialState, now: Long, pick: RingPick? = null): Centre {
        val tgt = target(s, now, pick)
        if (s.ambient) return Centre(tgt, null, null)
        var best: Long? = null
        var prev: DialLamp? = null
        fun consider(at: Long, before: () -> DialLamp?) {
            if (at > now || now - at >= XFADE_MS) return
            val b = best
            if (b != null && at <= b) return
            val was = before()
            if (was?.id != target(s, at, pick)?.id) { best = at; prev = was }
        }
        s.lamps.changedMs?.let { at -> consider(at) { choose(beforeAlerts(s), s, at - 1, pick) } }
        for (l in alerts(s)) if (holds(l)) (l.changedMs + HOLD_MS).let { at -> consider(at) { target(s, at - 1, pick) } }
        if (pick != null) for (at in listOf(pick.atMs, pick.atMs + PICK_MS)) consider(at) { target(s, at - 1, pick) }
        s.bulbAtMs?.let { b -> for (at in listOf(b, b + TellTales.BULB_CHECK_MS)) consider(at) { target(s, at - 1, pick) } }
        return Centre(tgt, prev, best?.let { now - it })
    }

    /** A tap at [at]: the lamp to show next (the alerts in TellTales.top's order, round), null when nothing is lit. */
    fun next(s: DialState, at: Long, pick: RingPick? = null): String? {
        if (s.ambient || bulbRunning(s, at)) return null
        val alerts = alerts(s)
        if (alerts.isEmpty()) return null
        val i = alerts.indexOfFirst { it.id == target(s, at, pick)?.id }
        return alerts[(i + 1) % alerts.size].id
    }

    /**
     * When the face next moves ([rememberFaceClock]): the kit's (red, the rumble, the bulb check, a lamp's entrance or
     * exit, the tap, the ▲ hops) and this face's own — a hold's end, a pick's start and end, the limiter line's fades,
     * the charging band. Ambient: never.
     */
    fun wakeAt(s: DialState, now: Long, pick: RingPick? = null): Long? {
        if (s.ambient) return null
        var next = FaceKit.wakeAt(s, now, FaceKit.APPEAR_MS)
        if (next == now) return now
        fun window(start: Long, len: Long): Boolean {
            if (now >= start && now < start + len) return true
            if (start > now) next = next?.let { min(it, start) } ?: start
            return false
        }
        for (l in alerts(s)) if (holds(l) && window(l.changedMs + HOLD_MS, XFADE_MS)) return now
        if (pick != null && (window(pick.atMs, XFADE_MS) || window(pick.atMs + PICK_MS, XFADE_MS))) return now
        s.cues.tapAtMs?.let { if (window(it, LINE_IN_MS) || window(it + LINE_MS - LINE_OUT_MS, LINE_OUT_MS)) return now }
        charging(s)?.let { l ->
            val start = l.sinceMs + max(0L, (now - l.sinceMs) / SWEEP_EVERY_MS) * SWEEP_EVERY_MS
            if (window(start, SWEEP_MS)) return now
            window(start + SWEEP_EVERY_MS, SWEEP_MS)
        }
        return next
    }

    private fun charging(s: DialState): DialLamp? =
        s.lamps[TellTales.CHARGING]?.takeIf { it.level == LampLevel.GREEN && s.battery.charging }

    // -- the frame

    /** [s] at [now] with [pick]; [powerF] / [speedF]: the arc's and the dot's places as drawn (the springs). */
    fun frame(
        s: DialState, now: Long, m: Measure, pick: RingPick? = null,
        powerF: Float = s.power.fraction, speedF: Float? = s.speed.fraction(),
    ): Frame {
        val ops = ArrayList<Op>(192)
        val out = Out(m, ops)
        val tiles = ArrayList<TileBox>(12)
        val dim = s.health.alpha
        val centre = centreAt(s, now, pick)
        val fade = fade(centre)
        ops += Power(powerF, dim)
        ring(s, now, looks(s, now), centre, fade, dim, ops, tiles)
        comet(s, now, ops)
        ops += Speed(speedF, dim)
        status(s, out)
        battery(s, now, dim, out)
        centreTexts(s, now, centre, fade, dim, out)
        return Frame(ops, centre, out.boxes, tiles)
    }

    private class Fade(val inA: Float, val outA: Float, val pop: Float)

    private fun fade(c: Centre): Fade {
        val tau = c.tauMs ?: return Fade(1f, 0f, 1f)
        val inA = FaceKit.smoothstep(IN_FROM_MS.toFloat(), XFADE_MS.toFloat(), tau.toFloat())
        return Fade(inA, 1f - FaceKit.smoothstep(0f, OUT_MS.toFloat(), tau.toFloat()), 0.94f + 0.06f * FaceKit.easeOut(inA))
    }

    private fun colour(level: LampLevel, s: DialState): Color = if (s.ambient && s.lowBit) AMB_INK else FaceKit.lamp(level)

    // -- the ring

    /** A place's look now: the level shown (the bulb check's or the lamp's), its growth [k] (overshooting while it pops),
     *  [from] the level before while a change is [q] (0…1) along. */
    private class Look(
        val id: String, val deg: Float, val level: LampLevel, val k: Float, val bulb: Boolean, val lamp: DialLamp?, val gone: Boolean,
        val from: LampLevel?, val q: Float,
    ) {
        val quiet: Boolean get() = id in TellTales.QUIET && !bulb
        val tile: Boolean get() = level != LampLevel.OFF && k > 0.01f && !quiet
    }

    /** The bulb check's ms at [now]; NaN: on without a start (all lit); null: none. */
    private fun bulbMs(s: DialState, now: Long): Float? {
        if (s.ambient) return null
        s.bulbProgress(now)?.let { return it * TellTales.BULB_CHECK_MS }
        return if (s.bulbOn && s.bulbAtMs == null) Float.NaN else null
    }

    /** A place at [deg] in the check at [t] ms: it pops as the head passes it, all fade at the end. */
    private fun bulbK(t: Float, deg: Float): Float {
        if (t >= TellTales.BULB_CHECK_MS) return 0f
        val head = 360f * t / BULB_SWEEP_MS
        val age = (head - (deg - HALF)) / 360f * BULB_SWEEP_MS
        if (age < 0f) return 0f
        return FaceKit.back(age / BULB_POP_MS, BACK) * (1f - FaceKit.smoothstep(BULB_FADE_MS, TellTales.BULB_CHECK_MS.toFloat(), t))
    }

    private fun looks(s: DialState, now: Long): List<Look> {
        val amb = s.ambient
        val bulbT = bulbMs(s, now)
        return TellTales.IDS.mapIndexed { i, id ->
            val deg = ANGLES[i]
            val lit = s.lamps[id]
            val gone = if (lit == null && !amb) s.lamps.gone.firstOrNull { it.id == id } else null
            var level = (lit ?: gone)?.level ?: LampLevel.OFF
            var k = when {
                lit != null -> if (amb) 1f else FaceKit.back((now - lit.sinceMs).toFloat() / GROW_MS, BACK)
                gone != null -> 1f - FaceKit.smoothstep(0f, EXIT_MS.toFloat(), (now - gone.changedMs).toFloat())
                else -> 0f
            }
            var from: LampLevel? = null
            var q = 1f
            val b = lit?.before
            if (!amb && lit != null && b != null && b.level != lit.level && b.level != LampLevel.OFF && now - lit.changedMs < GROW_MS) {
                from = b.level
                q = ((now - lit.changedMs).toFloat() / GROW_MS).coerceAtLeast(0f)
            }
            var bulb = false
            if (bulbT != null) {
                val bk = if (bulbT.isNaN()) 1f else bulbK(bulbT, deg)
                if (bk > 0f) { level = BULB.getValue(id); k = bk; bulb = true; from = null; q = 1f }
            }
            Look(id, deg, level, k, bulb, lit ?: gone, gone != null, from, q)
        }
    }

    private fun half(level: LampLevel): Float = if (level == LampLevel.RED) HALF_RED else HALF
    private fun depth(level: LampLevel): Float = if (level == LampLevel.RED) T_RED else T_TILE
    private fun iconPx(level: LampLevel): Float = if (level == LampLevel.RED) RED_ICON else TILE_ICON

    /** [a] → [b] by a change's progress (the tile's own overshoot). */
    private fun Look.by(f: (LampLevel) -> Float): Float =
        from?.let { FaceKit.lerp(f(it), f(level), FaceKit.back(q, BACK)) } ?: f(level)

    /** A tile's angular halves (ccw, cw): its own, shared with a lit neighbour when both would not fit [SPACE]. */
    private fun halves(looks: List<Look>, i: Int): Pair<Float, Float> {
        val own = looks[i].by(::half)
        fun side(n: Look): Float {
            if (!n.tile) return own
            val theirs = n.by(::half)
            return if (own + theirs <= SPACE) own else SPACE * own / (own + theirs)
        }
        return side(looks[CCW[i]]) to side(looks[CW[i]])
    }

    private fun ring(s: DialState, now: Long, looks: List<Look>, centre: Centre, fade: Fade, dim: Float, ops: MutableList<Op>, tiles: MutableList<TileBox>) {
        val amb = s.ambient
        val halos = ArrayList<Op>(8)
        val marks = ArrayList<Op>(4)
        val body = ArrayList<Op>(64)
        val alarms = looks.count { it.tile && !it.bulb && !it.gone && it.lamp != null }
        for ((i, lk) in looks.withIndex()) {
            val a = lk.deg
            val hair = point(HAIR_R, a)
            val col = colour(lk.level, s)
            val dimA = if (lk.bulb) 1f else dim
            val own = Glyphs.BY_ID.getValue(lk.id)
            val quietLit = lk.quiet && lk.level != LampLevel.OFF && lk.k > 0.01f
            if (!lk.tile && !quietLit) {
                if (amb) continue
                body += Hair(a, dimA)
                body += Icon(lk.id, own, hair.x, hair.y, OFF_ICON, OFF, dimA)
                continue
            }
            val l = lk.lamp
            val glyphs = if (lk.bulb || l == null) own else FaceKit.icon(l.lamp)
            if (lk.quiet) {
                // a standing condition / ECO: its icon in its colour, no tile, no glow
                val k = FaceKit.clamp01(lk.k)
                if (amb) { body += Icon(lk.id, glyphs, hair.x, hair.y, OFF_ICON, col, (if (s.lowBit) 1f else 0.55f) * dimA, outline = true); continue }
                body += Hair(a, dimA)
                if (k < 1f) body += Icon(lk.id, own, hair.x, hair.y, OFF_ICON, OFF, (1f - k) * dimA)
                body += Icon(lk.id, glyphs, hair.x, hair.y, FaceKit.lerp(OFF_ICON, QUIET_ICON, k), col, (QUIET_ALPHA[lk.id] ?: 1f) * k * dimA)
                continue
            }
            // a tile: the hairline swells inward from the outer edge; red is the biggest
            val red = lk.level == LampLevel.RED
            val solid = FaceKit.solid(lk.level)
            val k = lk.k
            val kc = FaceKit.clamp01(k)
            val th = lk.by(::depth)
            val r1 = FaceKit.lerp(HAIR_R + HAIR_W / 2, RING_OUT, kc)
            val r0 = FaceKit.lerp(HAIR_R - HAIR_W / 2, RING_OUT - th, k)
            val (hl, hr) = halves(looks, i)
            val a0 = a - FaceKit.lerp(9f, hl, k)
            val a1 = a + FaceKit.lerp(9f, hr, k)
            val rm = (r0 + r1) / 2
            val cr = min(CORNER, (r1 - r0) / 2)
            tiles += TileBox(lk.id, lk.level, r0, r1, a0, a1, k, lk.bulb)
            val iconR = RING_OUT - th / 2
            val ip = point(FaceKit.lerp(HAIR_R, iconR, kc), a)
            val px = FaceKit.lerp(OFF_ICON, lk.by(::iconPx), kc)
            val br = if (red && !lk.bulb && !amb && l != null && !lk.gone) FaceKit.pulse(FaceKit.redSince(l, s.cues), now) else 1f
            if (amb) {
                body += Sector(r0, r1, a0, a1, cr, col, (if (s.lowBit) 1f else 0.85f) * dimA)
                if (r1 - r0 > 5f) body += Sector(r0 + 2, r1 - 2, a0 + pxDeg(2f, rm), a1 - pxDeg(2f, rm), cr - 2, Color.Black, dimA)
                body += Icon(lk.id, glyphs, ip.x, ip.y, px * 0.85f, col, dimA, outline = true)
                continue
            }
            val sq = if (lk.from != null) FaceKit.smoothstep(0f, 0.6f, lk.q) else 1f
            val was = lk.from
            val wasSolid = was != null && FaceKit.solid(was)
            val shown = if (was != null) lerp(colour(was, s), col, sq) else col
            // the glow: two fills under the tile, inward and sideways only (none toward the power arc)
            val glowShare = when { was == null -> if (solid) 1f else 0f; solid && wasSolid -> 1f; solid -> sq; wasSolid -> 1f - sq; else -> 0f }
            if (glowShare > 0f) {
                var g = 1f
                if (l != null && !lk.bulb && !lk.gone && l.rose) {
                    val age = (now - l.changedMs).toFloat()
                    if (age >= 0f && age < FLASH_MS) g += 1.3f * (1f - FaceKit.smoothstep(100f, FLASH_MS.toFloat(), age))
                }
                g *= br * kc * glowShare * dimA
                halos += Sector(r0 - 4, r1, a0 - pxDeg(4f, rm), a1 + pxDeg(4f, rm), cr + 2, shown, 0.26f * g)
                halos += Sector(r0 - 9, r1, a0 - pxDeg(9f, rm), a1 + pxDeg(9f, rm), cr + 4, shown, 0.1f * g)
            }
            // the lamp the centre is showing, when more than one is lit: a white ring 2,5 px, a black gap 2,5 px
            if (alarms > 1 && !lk.bulb && l != null && !lk.gone) {
                val ma = when (lk.id) { centre.tgt?.id -> fade.inA; centre.prev?.id -> fade.outA; else -> 0f } * dimA
                if (ma > 0.002f) {
                    marks += Sector(r0 - 5, r1 + 3, a0 - pxDeg(5f, rm), a1 + pxDeg(5f, rm), cr + 3, Color.White, ma)
                    marks += Sector(r0 - 2.5f, r1 + 0.5f, a0 - pxDeg(2.5f, rm), a1 + pxDeg(2.5f, rm), cr + 1, Color.Black, ma)
                }
            }
            val fill = if (red) RED_FILL else AMBER_FILL
            val ka = FaceKit.clamp01(k * 1.6f)
            fun tile(solidLook: Boolean, share: Float) {
                if (share <= 0.002f) return
                if (solidLook) {
                    // a solid tile sits on black, so nothing under it shows through; red breathes 55–100 %
                    body += Sector(r0, r1, a0, a1, cr, Color.Black, share * dimA)
                    body += Sector(r0, r1, a0, a1, cr, shown, ka * br * fill * share * dimA)
                } else {
                    // «к сведению»: tinted inside, a 3 px edge in the colour
                    body += Sector(r0, r1, a0, a1, cr, shown, ka * share * dimA)
                    if (r1 - r0 > 2 * EDGE + 0.5f) {
                        val e0 = a0 + pxDeg(EDGE, rm)
                        val e1 = a1 - pxDeg(EDGE, rm)
                        body += Sector(r0 + EDGE, r1 - EDGE, e0, e1, cr - EDGE, Color.Black, share * dimA)
                        body += Sector(r0 + EDGE, r1 - EDGE, e0, e1, cr - EDGE, shown, 0.2f * ka * share * dimA)
                    }
                }
            }
            if (was != null && wasSolid != solid) { tile(wasSolid, 1f - sq); tile(solid, sq) } else tile(solid, 1f)
            val offA = 1f - FaceKit.clamp01(k * 3f)
            if (offA > 0.002f) body += Icon(lk.id, own, hair.x, hair.y, OFF_ICON, OFF, offA * dimA)
            val ink = if (solid) ON_SOLID else shown
            var ix = ip.x
            var iy = ip.y
            if (l != null && l.lamp.rising && !lk.bulb) {
                // ▲ next to the thermometer on the tile, along the tangent
                val rad = Math.toRadians(a.toDouble())
                val tx = cos(rad).toFloat()
                val ty = sin(rad).toFloat()
                ix -= tx * 9
                iy -= ty * 9
                val ta = FaceKit.clamp01((k - 0.4f) / 0.4f) * dimA
                if (ta > 0.002f) body += Tri(ip.x + tx * 17, ip.y + ty * 17 - FaceKit.bob(l.sinceMs, now, 3f), 7f, ink, ta, false)
            }
            val ia = FaceKit.clamp01((k - 0.25f) / 0.45f) * dimA
            if (ia > 0.002f) body += Icon(lk.id, glyphs, ix, iy, px, ink, ia, weight = if (solid) 1.25f else 1.1f)
        }
        ops += halos
        ops += marks
        ops += body
    }

    /** The bulb check's comet: a thin trail fading behind a bright head on the hairline, from 12 o'clock round. */
    private fun comet(s: DialState, now: Long, ops: MutableList<Op>) {
        val t = bulbMs(s, now) ?: return
        if (t.isNaN() || t > COMET_MS) return
        val head = 360f * t / BULB_SWEEP_MS
        for (i in 0 until 10) {
            val b = head - i * 4
            if (b < -HALF) break
            val a = max(b - 4, -HALF)
            if (b - a > 0.05f) ops += Arc(HAIR_R, a, b, 4f - i * 0.3f, Color.White, 0.85f * (1f - i / 10f))
        }
        val h = point(HAIR_R, head)
        ops += Disc(h.x, h.y, 11f, Color.White, 0.22f)
        ops += Disc(h.x, h.y, 5.5f, Color.White, 1f)
    }

    // -- the centre's texts

    /** Lays texts out: fitted to their room and the round edge, shrunk, ellipsized; scaled by a pop around [cx], [cy]. */
    private class Out(val m: Measure, val ops: MutableList<Op>) {
        val boxes = ArrayList<Box>(24)
        var k = 1f
        var cx = C
        var cy = C

        private fun X(v: Float) = cx + (v - cx) * k
        private fun Y(v: Float) = cy + (v - cy) * k

        fun width(t: String, px: Float, weight: Int) = m.width(t, px, weight)

        /** The largest size from [from] down to [min] at which [t] fits [maxW]; [min] if none does. */
        fun fit(t: String, from: Float, min: Float, weight: Int, maxW: Float): Float {
            val w0 = m.width(t, from, weight)
            if (w0 <= maxW || min >= from) return from
            var z = max(min, floor(from * maxW / w0 * 2f) / 2f)
            while (z > min && m.width(t, z, weight) > maxW) z -= 0.5f
            return max(z, min)
        }

        /** [t] cut to fit [maxW] at [px] with «…»; empty when not even that fits. */
        fun ellipsize(t: String, px: Float, weight: Int, maxW: Float): String {
            if (m.width(t, px, weight) <= maxW) return t
            var lo = 0
            var hi = t.length - 1
            var best = ""
            while (lo <= hi) {
                val mid = (lo + hi) / 2
                val c = t.substring(0, mid).trimEnd() + "…"
                if (m.width(c, px, weight) <= maxW) { best = c; lo = mid + 1 } else hi = mid - 1
            }
            return if (best == "…") "" else best
        }

        /** The room for a line at [x] ([anchor]) between [top] and [bottom]: [maxW] and the round edge. */
        fun room(x: Float, top: Float, bottom: Float, anchor: Anchor, maxW: Float): Float {
            val h = halfWidth(top, bottom)
            val edge = when (anchor) {
                Anchor.CENTER -> 2 * (h - abs(x - C))
                Anchor.START -> C + h - x
                Anchor.END -> x - (C - h)
            }
            return min(maxW, edge)
        }

        /** One line; returns its width (mock px, unscaled), 0 when nothing was drawn. */
        fun text(
            group: Group, t: String, x: Float, baseline: Float, px: Float, weight: Int, colour: Color, alpha: Float,
            min: Float = px, maxW: Float = Float.MAX_VALUE, anchor: Anchor = Anchor.CENTER,
        ): Float {
            if (t.isBlank() || alpha <= 0.002f) return 0f
            val room = room(x, baseline - ASC * px, baseline + DESC * px, anchor, maxW)
            if (room < px) return 0f
            val size = fit(t, px, min, weight, room)
            val shown = ellipsize(t, size, weight, room)
            if (shown.isEmpty()) return 0f
            val w = m.width(shown, size, weight)
            val left = when (anchor) { Anchor.CENTER -> x - w / 2; Anchor.START -> x; Anchor.END -> x - w }
            ops += Txt(shown, X(x), Y(baseline), size * k, weight, colour, alpha, anchor)
            boxes += Box(group, shown, X(left), Y(baseline - ASC * size), X(left + w), Y(baseline + DESC * size), alpha)
            return w
        }

        fun icon(group: Group, id: String, glyphs: List<Glyph>, x: Float, y: Float, px: Float, colour: Color, alpha: Float, outline: Boolean) {
            if (alpha <= 0.002f) return
            ops += Icon(id, glyphs, X(x), Y(y), px * k, colour, alpha, outline = outline)
            boxes += Box(group, "icon:$id", X(x - px / 2), Y(y - px / 2), X(x + px / 2), Y(y + px / 2), alpha)
        }

        fun tri(x: Float, y: Float, s: Float, colour: Color, alpha: Float, outline: Boolean) {
            if (alpha > 0.002f) ops += Tri(X(x), Y(y), s * k, colour, alpha, outline)
        }
    }

    /** The clock's line, or the StatusLine in its place (one line, else two), not dimmed with the values. */
    private fun status(s: DialState, out: Out) {
        val amb = s.ambient
        val st = s.status
        if (st == null) {
            if (s.clock.isNotBlank()) out.text(Group.STATUS, s.clock, C, 126f, 17f, 400, if (amb) AMB_DIM else MUTED, 1f)
            return
        }
        val colour = if (amb) AMB_INK else argb(s.health.tone)
        val one = st.replace('\n', ' ')
        val room1 = out.room(C, 126f - ASC * 17f, 126f + DESC * 17f, Anchor.CENTER, Float.MAX_VALUE)
        if (!st.contains('\n') && out.width(one, 13f, 500) <= room1) {
            out.text(Group.STATUS, one, C, 126f, 17f, 500, colour, 1f, min = 13f)
            return
        }
        val lines = if (st.contains('\n')) st.split('\n').take(2) else splitWords(out, one, 15f, 500) ?: listOf(one)
        if (lines.size == 1) { out.text(Group.STATUS, lines[0], C, 126f, 17f, 500, colour, 1f, min = 12f); return }
        // the same size for both lines: the smaller of their fits
        val px = lines.mapIndexed { i, ln ->
            val y = if (i == 0) 115f else 130f
            out.fit(ln, 15f, 11f, 500, out.room(C, y - ASC * 15f, y + DESC * 15f, Anchor.CENTER, Float.MAX_VALUE))
        }.min()
        out.text(Group.STATUS, lines[0], C, 115f, px, 500, colour, 1f)
        out.text(Group.STATUS, lines[1], C, 130f, px, 500, colour, 1f)
    }

    /** [t] in two lines at [px], the break that keeps the wider one narrowest; null for one word. */
    private fun splitWords(out: Out, t: String, px: Float, weight: Int): List<String>? {
        val w = t.split(' ').filter { it.isNotEmpty() }
        if (w.size < 2) return null
        return (1 until w.size).map { i -> listOf(w.subList(0, i).joinToString(" "), w.subList(i, w.size).joinToString(" ")) }
            .minBy { ls -> ls.maxOf { out.width(it, px, weight) } }
    }

    private fun battery(s: DialState, now: Long, dim: Float, out: Out) {
        val b = s.battery
        val charge = if (s.ambient) null else charging(s)?.let { l ->
            val ph = Math.floorMod(now - l.sinceMs, SWEEP_EVERY_MS)
            if (now >= l.sinceMs && ph < SWEEP_MS) ph.toFloat() / SWEEP_MS else null
        }
        out.ops += Battery(b.pct, argb(b.toneArgb), b.text, charge, dim)
        out.boxes += Box(Group.BATTERY, "battery", BAT_X - 2, BAT_Y - 2, BAT_X + BAT_W + 3 + 11 + 1, BAT_Y + BAT_H + 2, dim)
    }

    /** The passing line under the power in «детали»: the bulb check, the limiter's tap. */
    private class Msg(val text: String, val colour: Color, val alpha: Float)

    private fun message(s: DialState, now: Long): Msg? {
        if (s.ambient) return null
        bulbMs(s, now)?.let { t ->
            val a = if (t.isNaN()) 1f else 1f - FaceKit.smoothstep(1_300f, TellTales.BULB_CHECK_MS.toFloat(), t)
            return Msg(Dial.BULB_TEXT, MUTED, a)
        }
        val tap = s.cues.tapAtMs ?: return null
        val dt = (now - tap).toFloat()
        if (dt < 0 || dt > LINE_MS) return null
        val a = FaceKit.smoothstep(0f, LINE_IN_MS.toFloat(), dt) * (1f - FaceKit.smoothstep((LINE_MS - LINE_OUT_MS).toFloat(), LINE_MS.toFloat(), dt))
        val v = s.speed.value?.let { " · " + Fmt.num(it, 1) + NBSP + s.speed.unit } ?: ""
        return Msg("Ограничитель$v", INK2, a)
    }

    private fun centreTexts(s: DialState, now: Long, c: Centre, fade: Fade, dim: Float, out: Out) {
        val amb = s.ambient
        val lampNow = c.tgt != null
        val lampBefore = c.prev != null
        fun mix(now: Boolean, before: Boolean): Float =
            if (c.tauMs == null) (if (now) 1f else 0f) else if (now && before) 1f else if (now) fade.inA else if (before) fade.outA else 0f
        val lampL = mix(lampNow, lampBefore)
        val detL = mix(!lampNow, !lampBefore)
        val pcol = if (amb) AMB_INK else argb(s.power.argb)
        val rcol = if (amb) AMB_INK else if (s.rangeRough) MUTED else INK
        val dcol = if (amb) AMB_DIM else MUTED
        if (detL > 0.002f) {
            val a = detL * dim
            s.range?.let { out.text(Group.DETAILS, it, C, 252f, 32f, if (amb) 400 else 500, rcol, a, min = 24f) }
            out.text(Group.DETAILS, s.details, C, 283f, 19f, 400, dcol, a, min = 16f, maxW = 230f)
            out.text(Group.DETAILS, s.power.text, C, 318f, 20f, 400, pcol, a, min = 15f, maxW = 150f)
            // a passing message (the limiter's tap, the bulb check) under the power line: nothing moves
            message(s, now)?.let { msg -> out.text(Group.DETAILS, msg.text, C, 344f, 18f, 500, msg.colour, detL * msg.alpha, min = 13.5f, maxW = 194f) }
        }
        if (lampL > 0.002f) {
            val a = lampL * dim
            out.text(Group.LOWER, s.details, C, 338f, 17f, 400, dcol, a, min = 15f, maxW = 180f)
            s.range?.let { out.text(Group.LOWER, it, C, 364f, 20f, if (amb) 400 else 500, if (amb) AMB_INK else if (s.rangeRough) MUTED else INK2, a, min = 16f) }
            out.text(Group.LOWER, s.power.text, C, 388f, 18f, 400, pcol, a, min = 14f, maxW = 104f)
        }
        val prev = c.prev
        if (c.tauMs != null && prev != null && prev.id != c.tgt?.id) block(s, now, prev, fade.outA, 1f, Group.OUT, dim, out)
        c.tgt?.let { block(s, now, it, fade.inA, fade.pop, Group.IN, dim, out) }
    }

    /** The value «49 °C» as the number and its unit (split at the last no-break space); no space: the number alone. */
    fun split(value: String): Pair<String, String> {
        val i = value.lastIndexOf(NBSP)
        return if (i <= 0 || i == value.length - 1) value to "" else value.substring(0, i) to value.substring(i + 1)
    }

    /** The lamp block: the title in the lamp's colour, the icon 80 px, the value and the detail (or the detail in the
     *  value's place), at [a], scaled by [pop] around (240, 270). */
    private fun block(s: DialState, now: Long, l: DialLamp, a: Float, pop: Float, group: Group, dim: Float, out: Out) {
        if (a <= 0.002f) return
        val amb = s.ambient
        val lamp = l.lamp
        val col = colour(lamp.level, s)
        val al = a * dim
        out.k = pop
        out.cx = C
        out.cy = 270f
        val title = lamp.title.ifBlank { TellTales.TITLES[lamp.id] ?: lamp.text }
        out.text(group, title, C, 233f, 23f, if (amb) 400 else 600, if (amb) AMB_INK else if (s.lowBit) col else FaceKit.title(lamp.level), al, min = 18f, maxW = 240f)
        val value = lamp.valueText?.takeIf { it.isNotBlank() }
        val detail = lamp.detail?.takeIf { it.isNotBlank() }
        val br = if (lamp.level == LampLevel.RED && !amb) FaceKit.pulse(FaceKit.redSince(l, s.cues), now) else 1f
        out.icon(group, lamp.id, FaceKit.icon(lamp), if (value != null || detail != null) 170f else C, 281f, BIG_ICON, col, al * br, outline = amb)
        val rising = lamp.rising
        val ink = if (amb) AMB_INK else Color.White
        val ink2 = if (amb) AMB_DIM else INK2
        if (value != null) {
            val (num, unit) = split(value)
            val vx = 216f
            val maxW = out.room(vx, 291f - ASC * 42f, 291f + DESC * 42f, Anchor.START, 136f) - (if (rising) 22f else 0f)
            val nw = { k: Float -> out.width(num, 42f * k, 700) }
            val uw = { k: Float -> if (unit.isEmpty()) 0f else out.width(NBSP + unit, 22f * max(k, 0.8f), 500) }
            var k = min(1f, maxW / (nw(1f) + uw(1f))).coerceAtLeast(0.45f)
            while (k > 0.45f && nw(k) + uw(k) > maxW) k -= 0.02f
            val ns = 42f * k
            val us = 22f * max(k, 0.8f)
            val w = out.text(group, num, vx, 291f, ns, if (amb) 400 else 700, ink, al, maxW = maxW, anchor = Anchor.START)
            val ux = vx + w
            val uW = if (unit.isEmpty()) 0f else out.text(group, NBSP + unit, ux, 291f, us, 500, ink2, al, maxW = max(0f, maxW - w), anchor = Anchor.START)
            if (rising) out.tri(ux + uW + 12f, 272f - if (amb) 0f else FaceKit.bob(l.sinceMs, now, 4f), 8f, col, al, amb)
            // the detail may reach past the value to the round edge
            if (detail != null) out.text(group, detail, vx + 1f, 314f, 16f, 400, ink2, al, min = 13f, maxW = 150f, anchor = Anchor.START)
        } else if (detail != null) {
            // no value («Связь рвётся», «BMS молчит»): the detail in its place, one line or two
            val vx = 216f
            val room = out.room(vx, 291f - ASC * 26f, 291f + DESC * 26f, Anchor.START, 136f)
            if (out.width(detail, 20f, 600) <= room || !detail.contains(' ')) {
                out.text(group, detail, vx, 291f, 26f, 600, ink, al, min = 16f, maxW = room, anchor = Anchor.START)
            } else {
                val lines = splitWords(out, detail, 20f, 600) ?: listOf(detail)
                val px = lines.mapIndexed { i, ln ->
                    val y = if (i == 0) 279f else 305f
                    out.fit(ln, 22f, 15f, 600, out.room(vx, y - ASC * 22f, y + DESC * 22f, Anchor.START, 136f))
                }.min()
                out.text(group, lines[0], vx, 279f, px, 600, ink, al, anchor = Anchor.START, maxW = 136f)
                if (lines.size > 1) out.text(group, lines[1], vx, 305f, px, 600, ink, al, anchor = Anchor.START, maxW = 136f)
            }
        }
        out.k = 1f
        out.cy = C
    }

    // -- what the tests check

    /** What is wrong with [f] (empty: nothing): texts outside the round room, visible boxes overlapping, tiles over
     *  each other or out of their band, the centre not TellTales.top's lamp when nothing else may hold it. */
    fun problems(s: DialState, now: Long, f: Frame, pick: RingPick? = null): List<String> {
        val bad = ArrayList<String>()
        for (b in f.boxes) if (b.group != Group.BATTERY) for (p in b.corners) if (!inside(p.x, p.y)) {
            bad += "${b.what} (${b.group}) at ${p.x.toInt()},${p.y.toInt()} is outside"
            break
        }
        val shown = f.boxes.filter { it.alpha > 0.5f }
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val x = shown[i]
            val y = shown[j]
            if (x.overlaps(y)) bad += "${x.what} (${x.group}) overlaps ${y.what} (${y.group})"
        }
        for (t in f.tiles) {
            if (t.r1 > RING_OUT + 0.01f) bad += "${t.id}: out to ${t.r1}"
            if (t.r0 < RING_OUT - T_RED - 4f) bad += "${t.id}: in to ${t.r0}"
            // amber → red overshoots its 3° a little as it pops
            if (t.a1 - t.a0 > 2 * HALF_RED + 1f && t.k <= 1f) bad += "${t.id}: ${t.a1 - t.a0}° wide"
        }
        for (i in f.tiles.indices) for (j in i + 1 until f.tiles.size) {
            val x = f.tiles[i]
            val y = f.tiles[j]
            if (arcsOverlap(x.a0, x.a1, y.a0, y.a1)) bad += "tiles ${x.id} and ${y.id} overlap"
        }
        if (pick == null) {
            val top = s.lamps.top
            val bulb = bulbRunning(s, now)
            val tgt = f.centre.tgt
            when {
                bulb -> if (tgt != null) bad += "the centre shows ${tgt.id} in the bulb check"
                top != null && !holds(top) && tgt?.id != top.id -> bad += "the centre shows ${tgt?.id}, not top ${top.id}"
                top != null && holds(top) && now - top.changedMs < HOLD_MS && tgt?.id != top.id -> bad += "the centre shows ${tgt?.id}, not the new ${top.id}"
                top == null && tgt != null -> bad += "the centre shows ${tgt.id} with nothing lit"
            }
        }
        return bad
    }

    /** Clock-angle intervals [a0]…[a1] and [b0]…[b1] intersect (mod 360). */
    private fun arcsOverlap(a0: Float, a1: Float, b0: Float, b1: Float): Boolean {
        for (shift in floatArrayOf(-360f, 0f, 360f)) if (a0 < b1 + shift && b0 + shift < a1) return true
        return false
    }
}

/** Paints a [RingLayout.Frame]: the paths reused frame to frame, the strokes kept, text widths cached. */
private class RingPen {
    private val pool = ArrayList<Path>()
    private var used = 0
    private val strokes = HashMap<Long, Stroke>()
    private val layer = Paint()
    private val widths = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean = size > 600
    }
    private var measurer: TextMeasurer? = null
    private var base: TextStyle? = null
    private var u = 0f
    private var density = 0f
    private var fontScale = 0f
    private var meter: RingLayout.Measure? = null

    /** The widths in mock px as [m] lays them out in [base] at [u] px per mock px (cached per text and size). */
    fun meter(m: TextMeasurer, base: TextStyle, u: Float, d: Density): RingLayout.Measure {
        val cur = meter
        if (cur != null && m === measurer && base == this.base && u == this.u && d.density == density && d.fontScale == fontScale) return cur
        measurer = m
        this.base = base
        this.u = u
        density = d.density
        fontScale = d.fontScale
        widths.clear()
        return RingLayout.Measure { text, px, weight ->
            widths.getOrPut("$weight|$px|$text") {
                m.measure(text, style(px * u, weight), softWrap = false, maxLines = 1).size.width / u
            }
        }.also { meter = it }
    }

    /** The face's text at [px] canvas px in the watch theme's style, tabular digits. */
    fun style(px: Float, weight: Int): TextStyle = (base ?: TextStyle()).merge(
        TextStyle(fontSize = (px / (density * fontScale)).sp, fontWeight = FontWeight(weight), fontFeatureSettings = "tnum"),
    )

    private fun path(): Path {
        if (used == pool.size) pool += Path()
        return pool[used++].apply { rewind() }
    }

    private fun stroke(width: Float, round: Boolean): Stroke =
        strokes.getOrPut((width * 64).toLong() * 2 + if (round) 1 else 0) { Stroke(width, cap = if (round) StrokeCap.Round else StrokeCap.Butt) }

    private inline fun DrawScope.layered(alpha: Float, block: DrawScope.() -> Unit) {
        if (alpha >= 0.999f) { block(); return }
        drawIntoCanvas { c ->
            layer.alpha = alpha
            c.saveLayer(Rect(Offset.Zero, size), layer)
            block()
            c.restore()
        }
    }

    fun paint(ds: DrawScope, f: RingLayout.Frame, s: DialState, now: Long, m: TextMeasurer, u: Float) = with(ds) {
        used = 0
        val c = center
        for (op in f.ops) when (op) {
            is RingLayout.Power -> layered(op.alpha) {
                drawPowerArc(
                    op.fraction, RingLayout.POWER_R * u, RingLayout.POWER_W * u, s.ambient, s.lowBit,
                    tick = RingLayout.ZERO_IN * u to RingLayout.ZERO_OUT * u, tickW = 3f * u, ambientW = 5f * u, ambientTrackW = 2f * u,
                )
            }
            is RingLayout.Speed -> layered(op.alpha) { drawSpeedTrack(s, RingLayout.SPEED, now, m, op.fraction) }
            is RingLayout.Hair -> {
                val r = RingLayout.HAIR_R * u
                val st = stroke(RingLayout.HAIR_W * u, true)
                val tl = Offset(c.x - r, c.y - r)
                val sz = Size(2 * r, 2 * r)
                val sweep = RingLayout.HALF - 9f
                drawArc(HAIR_COLOUR, op.deg - RingLayout.HALF + 2f - 90f, sweep, false, tl, sz, alpha = op.alpha, style = st)
                drawArc(HAIR_COLOUR, op.deg + 7f - 90f, sweep, false, tl, sz, alpha = op.alpha, style = st)
            }
            is RingLayout.Sector -> {
                val p = path()
                if (sector(p, op, u)) drawPath(p, op.colour, alpha = op.alpha)
            }
            is RingLayout.Arc -> {
                val r = op.r * u
                drawArc(op.colour, op.a0 - 90f, op.a1 - op.a0, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), alpha = op.alpha, style = stroke(op.width * u, false))
            }
            is RingLayout.Disc -> drawCircle(op.colour, op.r * u, Offset(op.x * u, op.y * u), alpha = op.alpha)
            is RingLayout.Icon -> drawGlyph(op.glyphs, Offset(op.x * u, op.y * u), op.px * u, op.colour, ambient = op.outline, weight = op.weight, alpha = op.alpha)
            is RingLayout.Tri -> {
                val p = path()
                val x = op.x * u
                val y = op.y * u
                val k = op.s * u
                p.moveTo(x - k, y + 0.8f * k); p.lineTo(x, y - 0.9f * k); p.lineTo(x + k, y + 0.8f * k); p.close()
                if (op.outline) drawPath(p, op.colour, alpha = op.alpha, style = Stroke(1.5f * u, join = StrokeJoin.Round))
                else drawPath(p, op.colour, alpha = op.alpha)
            }
            is RingLayout.Battery -> drawBattery(
                m, Offset(RingLayout.BAT_X * u, RingLayout.BAT_Y * u), Size(RingLayout.BAT_W * u, RingLayout.BAT_H * u), 4f * u,
                op.pct, op.tone, op.text, 36f * u, s.ambient, s.lowBit, op.charge, op.alpha,
            )
            is RingLayout.Txt -> drawTextAt(m, op.text, style(op.px * u, op.weight), op.x * u, op.baseline * u, op.colour, op.anchor, alpha = op.alpha)
        }
    }

    /**
     * [p] as the sector [o] in canvas px: the outer arc, a corner, the side along the radius, a corner, the inner arc back
     * and the other two corners — the corners' circles tangent to the arcs and the sides (the mock's sector stroked 2·cr
     * with round joins, as one fill). False when too thin to draw.
     */
    private fun DrawScope.sector(p: Path, o: RingLayout.Sector, u: Float): Boolean {
        val r0 = max(0f, o.r0) * u
        val r1 = o.r1 * u
        if (r1 - r0 < 0.05f * u || o.alpha <= 0.002f) return false
        val cr = min(max(o.cr * u, 0.01f * u), (r1 - r0) / 2 - 0.005f * u).coerceAtLeast(0.001f)
        val ro = r1 - cr
        val ri = r0 + cr
        val dO = Math.toDegrees(asin(min(1f, cr / ro)).toDouble()).toFloat()
        val dI = Math.toDegrees(asin(min(1f, cr / ri)).toDouble()).toFloat()
        var a0 = o.a0
        var a1 = o.a1
        if (a1 - a0 < dO + dI + 0.2f) {
            val mid = (a0 + a1) / 2
            a0 = mid - (dO + dI) / 2 - 0.1f
            a1 = mid + (dO + dI) / 2 + 0.1f
        }
        val c = center
        fun at(r: Float, deg: Float): Offset {
            val rad = Math.toRadians(deg.toDouble())
            return Offset(c.x + r * sin(rad).toFloat(), c.y - r * cos(rad).toFloat())
        }
        val start = at(r1, a0 + dO)
        p.moveTo(start.x, start.y)
        p.arcTo(Rect(c, r1), a0 + dO - 90f, a1 - a0 - 2 * dO, false)
        p.arcTo(Rect(at(ro, a1 - dO), cr), a1 - dO - 90f, 90f + dO, false)
        p.arcTo(Rect(at(ri, a1 - dI), cr), a1, 90f - dI, false)
        p.arcTo(Rect(c, r0), a1 - dI - 90f, -(a1 - a0 - 2 * dI), false)
        p.arcTo(Rect(at(ri, a0 + dI), cr), a0 + dI + 90f, 90f - dI, false)
        p.arcTo(Rect(at(ro, a0 + dO), cr), a0 - 180f, 90f + dO, false)
        p.close()
        return true
    }

    private companion object {
        val HAIR_COLOUR = Color(0xFF171C1B)
    }
}
