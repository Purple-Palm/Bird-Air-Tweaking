package io.github.purplepalm.birdair.dial

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/*
 * DialFace.TAKEOVER — «Захват экрана» (gallery/takeover.html, its 480-px grid; the canvas is scaled to it). By severity:
 *  - calm: the usual cluster — the power arc (r 210, 16 px), the speed track (r 188) with its pill, the battery 140×66 with
 *    its % in the centre, the range, «V · °C · MODE» and the power; 12 lamps in two columns (r 158): off 26 px, lit
 *    green / blue 34 px on a soft disc, the standing ones (service, weak) dim and small;
 *  - yellow (temp): grows where it is into a tinted circle 68 px (46 px while the centre is busy), one ring as it lights,
 *    its text on the line, the ▲ hops on appearance and every 10 s; never solid;
 *  - amber: a dashed segment of the bezel by its lamp (r 230–240, 3,4° dashes on a 5° lattice, 7 px of black to the power
 *    arc) and the icon flies out of its column into the centre, 80 px with its title and value (two: 72 px each; more:
 *    «+N», the rest lit 40 px in their columns); the battery rises to 124×48, the range and «V · °C» stay under the slot;
 *    blue / green (overspeed, charging, cold) take the slot only while nothing amber or red is lit;
 *  - red: the takeover — the icon 150 px in the centre, the title in 38-px capitals (two lines if need be), the value with
 *    the detail and the range under it (the battery: the range first; the detail alone under that when there is room),
 *    «12,4 %» 32 px at the top, the other lamp 52 px at the side, the whole bezel a ring pulsing 1 Hz; 8 s after it
 *    turned red (or after a repeat red buzz) the calm red layout: the amber one with the red lamp leading (80 px, the
 *    other 62 px).
 * The judge's notes, fixed here: the red pulse is FaceKit.pulse — 1 Hz (not the 1,47 Hz noted on the mock), a smooth
 * cosine 55–100 % from the last red buzz, on the icons' halos, the bezel and the ring, never on text; every appearance
 * ≤ 700 ms; only red and the rumble move for long; yellow / blue / green never solid; the battery fill at 80 %; NBSP
 * between a number and its unit.
 * Real data, not the demo: the slot's sets come from DialLamps (now and [DialLamps.before] at [DialLamps.changedMs], in
 * TellTales.top's order), the takeover from the red lamps' history and the buzz ([DialCues.buzz]), the limiter's ring,
 * flash and line from [DialCues.tapAtMs], the comets from [DialCues.rumble]; texts are Lamp.title / valueText / detail,
 * shrunk to fit, split into two lines where there is room, ellipsized past that, every line inside the speed track's
 * circle. [TakeoverLayout] lays a frame out (pure: the JVM tests check it), [FaceTakeover] paints it; frames run only
 * while [TakeoverLayout.wakeAt] says something moves. Ambient: outlines, static, no glow, the burn-in shift.
 */

/** «Захват экрана». [nowMs]: a fixed time (shots, tests), null: live — the face's own clock while something moves. */
@Composable
internal fun FaceTakeover(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) {
    val clock = rememberFaceClock(state, nowMs, TakeoverLayout::wakeAt)
    val springs = nowMs == null && state.smooth && !state.ambient
    val power = rememberSpring(state.power.fraction, springs)
    val speed = rememberSpring(state.speed.fraction() ?: 0f, springs)
    val measurer = rememberTextMeasurer(cacheSize = 128)
    val base = LocalFaceTextStyle.current
    val pen = remember { TakeoverPen() }
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val (dx, dy) = state.burnInDp
        Canvas(Modifier.size(side).offset(dx.dp, dy.dp)) {
            val meter = pen.meter(measurer, base, this)
            val f = TakeoverLayout.frame(
                state, clock.value, meter,
                powerF = if (springs) power.value else state.power.fraction,
                speedF = if (state.speed.value == null) null else if (springs) speed.value else state.speed.fraction(),
            )
            val u = FaceKit.unit(size)
            withTransform({ scale(u, u, Offset.Zero) }) { pen.paint(this, f, meter) }
        }
    }
}

/**
 * «Захват экрана» at a moment, laid out on the mock's 480-px grid: what [FaceTakeover] paints, in the order it paints it,
 * and what the tests check ([boxes], [problems]). Pure: text widths come from a [Measure] (the face's TextMeasurer, or
 * [ROUGH] on the JVM).
 */
object TakeoverLayout {
    /** The width in mock px of [text] at [px] mock px and [weight] (400…800). */
    fun interface Measure {
        fun width(text: String, px: Float, weight: Int): Float
    }

    /** Roboto's advances, roughly and on the wide side (JVM tests have no fonts). */
    val ROUGH: Measure = Measure { text, px, weight ->
        var w = 0f
        for (c in text) w += when {
            c == ' ' || c == ' ' -> 0.26f
            c == ' ' -> 0.18f
            c.isDigit() -> 0.57f
            c in ",.:;·'!|" -> 0.27f
            c in "ШЩЖЮМЫWMшщжюмыwm%" -> 0.86f
            c == '—' -> 1f
            c.isUpperCase() -> 0.7f
            c.isLowerCase() -> 0.58f
            else -> 0.62f
        }
        w * px * when { weight >= 800 -> 1.1f; weight >= 700 -> 1.07f; weight >= 600 -> 1.04f; else -> 1f }
    }

    // -- the grid (gallery/takeover.html)
    const val C = 240f
    const val R_PWR = 210f
    const val W_PWR = 16f
    const val R_SPD = 188f
    const val R_LAMP = 158f
    const val OFF_PX = 26f
    const val LIT_PX = 34f
    /** Amber / red lit in their column (the overflow of «+N», a lamp pushed out of the slot). */
    const val STRONG_PX = 40f
    const val YEL_PX = 68f
    const val YEL_BUSY_PX = 46f
    const val BIG_PX = 150f
    const val SIDE_PX = 52f
    const val SLOT_Y = 194f
    const val TAKE_X = 240f
    const val TAKE_Y = 206f
    /** Texts stay inside the speed track (its inner edge 186,5); in the bottom gap (past ±135°) inside the screen. */
    const val TEXT_R = 186f
    const val GAP_R = 236f

    // -- timing (all ≤ FaceKit.APPEAR_MS but the red takeover's hold)
    const val LIT_MS = 420L
    const val OUT_MS = 380L
    const val SLOT_MS = 560L
    const val TAKE_MS = 650L
    const val TAKE_HOLD_MS = 8_000L
    const val RING_MS = 500L
    const val YELLOW_RING_MS = 600L
    const val TAP_RING_MS = 600L
    const val LIMITER_LINE_MS = 3_000L
    const val BULB_STEP_MS = 55L
    const val BULB_RISE_MS = 160L
    const val BULB_FADE_MS = 260L
    const val SHIMMER_EVERY_MS = 4_000L
    const val SHIMMER_MS = 1_400L
    /** A red buzz this much before the lamp's red (one message apart) still starts its pulse. */
    const val BUZZ_EARLY_MS = 1_000L
    /** The ▲'s hop, mock px. */
    const val HOP_PX = 5f

    /** The bulb check lights the lamps round the dial: the right column down, then the left one up. */
    private val BULB_ORDER = listOf(0, 1, 2, 3, 4, 5, 11, 10, 9, 8, 7, 6)
    private val BULB_LEVELS = TellTales.bulbCheck().associate { it.id to it.level }
    /** Standing conditions: dim and small in their column (ECO, a state, is lit like the others). */
    private val DIM = TellTales.QUIET - TellTales.ECO
    private val RU = Locale.forLanguageTag("ru")
    private const val NBSP = ' '

    private val INK = Color(Palette.INK)
    private val INK2 = Color(Palette.INK2)
    private val MUTED = Color(Palette.MUTED)
    private val OFF = Color(Palette.LAMP_OFF)
    private val RED = Color(Palette.LAMP_RED)
    private val YELLOW = Color(Palette.LAMP_YELLOW)
    internal val AMB_INK = Color(Palette.AMB_INK)
    internal val AMB_DIM = Color(Palette.AMB_DIM)
    internal val AMB_LINE = Color(Palette.AMB_LINE)

    // -- what a frame holds

    /** Who drew it (the tests' overlap rules). */
    enum class Role { SOCKET, COLUMN, YELLOW, SLOT, TAKEOVER, LINE, STATUS, MORE, BATTERY, RIPPLE, BULB }

    sealed interface Op

    /** A lamp's bezel segment: dashes on the 5° lattice around [mid], clipped to [lo]…[hi] (clock degrees). */
    data class Seg(val lo: Float, val hi: Float, val mid: Float, val colour: Color, val alpha: Float) : Op

    /** The red takeover's whole ring. */
    data class Ring(val alpha: Float) : Op

    /** The limiter tap's white flash on the bezel at [deg]. */
    data class Flash(val deg: Float, val k: Float) : Op

    /** The rumble's three blue comets running counter-clockwise, the first one's head at [headDeg]. */
    data class Comets(val k: Float, val headDeg: Float) : Op

    data class Power(val fraction: Float) : Op

    /**
     * The speed track: [fraction] the dot (null: no speed), [label] in it, [pill] one decimal near the limit; [limitDeg]
     * the mode's limit, [hot] at the tap's point, [tickFlash] the tap; [ticks] every 5 units; [zoneDeg] the rumble's point
     * to the end, [zoneK] lit by the rumble; [over] the rumble runs: the dot red, trembling by [tremble], two rings.
     */
    data class Speed(
        val fraction: Float?, val label: String?, val pill: Boolean, val ticks: List<Float>, val limitDeg: Float?,
        val hot: Boolean, val tickFlash: Float, val zoneDeg: Float?, val zoneK: Float, val over: Boolean,
        val tremble: Offset, val ringPhase: Float,
    ) : Op

    /** A soft disc (the galleries' glowDisc). */
    data class Glow(val x: Float, val y: Float, val r: Float, val colour: Color, val alpha: Float) : Op

    /** A ring [width] wide, filled at [fill] of [alpha] (the yellow circle; ripples: no fill). */
    data class Circle(
        val id: String, val x: Float, val y: Float, val r: Float, val colour: Color, val alpha: Float, val width: Float,
        val fill: Float, val role: Role,
    ) : Op

    /** The ▲ «быстро греется», [size] wide, its centre at x, y. */
    data class Tri(val id: String, val x: Float, val y: Float, val size: Float, val colour: Color, val alpha: Float) : Op

    /**
     * A lamp icon [px] square at x, y: [weight] thins big strokes; [halo] the soft halo's strength ([blur] px), [outline]
     * px: ambient strokes only; [cut]: the crossed battery's slash knocks out what is under it.
     */
    data class Icon(
        val id: String, val glyphs: List<Glyph>, val x: Float, val y: Float, val px: Float, val colour: Color,
        val alpha: Float, val halo: Float, val blur: Float, val weight: Float, val outline: Float, val cut: Boolean,
        val role: Role,
    ) : Op

    enum class Anchor { START, CENTER, END }

    /** One line of text, its baseline at [baseline]; wider than [maxW] it is ellipsized. [halo]: px of black under it. */
    data class Txt(
        val text: String, val x: Float, val baseline: Float, val px: Float, val weight: Int, val colour: Color,
        val alpha: Float, val maxW: Float, val role: Role, val id: String = "", val anchor: Anchor = Anchor.CENTER,
        val halo: Float = 0f, val outline: Boolean = false,
    ) : Op

    data class Part(val text: String, val colour: Color, val weight: Int)

    /** «V · °C · MODE» with the values a lamp is about in its colour, centred at x. */
    data class Parts(val parts: List<Part>, val x: Float, val baseline: Float, val px: Float, val alpha: Float) : Op

    /** The battery: the body, its nub, the fill for [pct] in [tone] (80 %), [text] at tx / ty; [shimmer] the charge band. */
    data class Battery(
        val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, val line: Float, val pad: Float,
        val pct: Double?, val tone: Color, val text: String, val tx: Float, val ty: Float, val px: Float, val halo: Float,
        val shimmer: Float?,
    ) : Op

    /**
     * A frame: [dim] dimmed by [alpha] (the data's health), [top] not (the status line, the bulb check). [main]: the
     * lamp in the centre's first place (null: none), [takeover] 0…1, [slot] the lamps in the centre, [more] the «+N»,
     * [line] the lamp line's text (a yellow lamp, the limiter, the bulb check), [settled]: nothing in transition.
     */
    class Frame(
        val ambient: Boolean, val lowBit: Boolean, val alpha: Float, val dim: List<Op>, val top: List<Op>,
        val main: String?, val takeover: Float, val slot: List<String>, val more: Int, val line: String?, val settled: Boolean,
    ) {
        val ops: List<Op> get() = dim + top
    }

    // -- geometry and curves

    private fun index(id: String) = TellTales.IDS.indexOf(id)
    fun lampDeg(id: String): Float = index(id).let { if (it < 0) 0f else Dial.lampDeg(it) }
    /** +1: the right column (IDS 0–5), −1: the left one. */
    private fun side(id: String): Int = if (index(id) in 0 until 6) 1 else -1
    private fun pt(r: Float, deg: Float): Offset = FaceKit.point(r, deg, 1f)
    /** A lamp's place in its column; bigger lamps sit a little further in. */
    fun lampPos(id: String, px: Float): Offset = pt(R_LAMP - max(0f, px - OFF_PX) * 0.35f, lampDeg(id))
    private fun spdDeg(f: Float) = Dial.START_DEG + Dial.SPAN_DEG * f.coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun clamp(x: Float) = x.coerceIn(0f, 1f)
    private fun ease(t: Float) = FaceKit.ease(t)
    private fun easeOut(t: Float) = FaceKit.easeOut(t)
    /** The mock's back-out (c1 1,7): the lamps' bloom overshoots a little. */
    private fun back(t: Float) = FaceKit.back(t, 1.7f)
    private fun prog(since: Long?, now: Long, dur: Long) = FaceKit.progress(since, now, dur)

    /** Big icons draw thinner strokes (the mock's glyph()). */
    private fun weightFor(px: Float) = if (px >= 130f) 0.8f else if (px >= 70f) 0.9f else 1f

    /** When a lamp took its level (from off, or a change of level); a change of its text only keeps the earlier time. */
    private fun levelAt(l: DialLamp) = if (l.before == null || l.before.level != l.level) l.changedMs else l.sinceMs

    /** When it became amber or red (its bezel segment's entrance). */
    private fun strongAt(l: DialLamp) = if (l.before == null || l.before.level < LampLevel.AMBER) l.changedMs else l.sinceMs

    /** When it turned red (FaceKit.redSince's reading); a lamp that went out only knows when it lit. */
    private fun turned(l: DialLamp, gone: Boolean = false) =
        if (gone || l.before?.level == LampLevel.RED) l.sinceMs else l.changedMs

    /** The ▲ hops from when the lamp lit, or from when it started rising. */
    private fun hopFrom(l: DialLamp) = if (l.before != null && !l.before.rising && l.lamp.rising) l.changedMs else l.sinceMs

    private val ORDER = compareByDescending<Lamp> { it.level.ordinal }.thenBy { index(it.id) }

    /**
     * The lamps the centre's slot holds, [TellTales.top]'s order: the amber and red ones; while there is none, the blue and
     * green ones (overspeed, charging, cold). Yellow grows in place, the standing ones and ECO stay in their columns.
     */
    fun slotLamps(lamps: List<Lamp>): List<Lamp> {
        val alerts = lamps.filter { it.id !in TellTales.QUIET && it.level != LampLevel.OFF && it.level != LampLevel.YELLOW }.sortedWith(ORDER)
        val strong = alerts.filter { it.level >= LampLevel.AMBER }
        return strong.ifEmpty { alerts }
    }

    // -- text fitting

    /** The largest size from [px] down to [minPx] at which [text] fits [maxW]; [minPx] if none does (it is then ellipsized). */
    private fun fit(m: Measure, text: String, px: Float, minPx: Float, maxW: Float, weight: Int): Float {
        val w = m.width(text, px, weight)
        if (w <= maxW) return px
        var z = max(minPx, px * maxW / w * 0.99f)
        var i = 0
        while (z > minPx && m.width(text, z, weight) > maxW && i++ < 12) z = max(minPx, z - 0.5f)
        return z
    }

    private fun fits(m: Measure, text: String, px: Float, maxW: Float, weight: Int) = m.width(text, px, weight) <= maxW

    /** [text] in two lines at [px], the break that keeps the wider one narrowest, both within [maxW]; null: none fits. */
    private fun split2(m: Measure, text: String, px: Float, maxW: Float, weight: Int): List<String>? {
        val w = text.split(' ').filter { it.isNotEmpty() }
        var best: List<String>? = null
        var bestW = Float.MAX_VALUE
        for (i in 1 until w.size) {
            val a = w.subList(0, i).joinToString(" ")
            val b = w.subList(i, w.size).joinToString(" ")
            val wide = max(m.width(a, px, weight), m.width(b, px, weight))
            if (wide <= maxW && wide < bestW) { best = listOf(a, b); bestW = wide }
        }
        return best
    }

    /** The first of [options] that fits [maxW] at ≥ [minPx] (its size), else the first one ellipsized at [minPx]. */
    private fun firstFit(m: Measure, options: List<String>, px: Float, minPx: Float, maxW: Float, weight: Int): Pair<String, Float>? {
        val list = options.filter { it.isNotBlank() }.distinct()
        for (o in list) {
            val z = fit(m, o, px, minPx, maxW, weight)
            if (fits(m, o, z, maxW, weight)) return o to z
        }
        return list.firstOrNull()?.let { it to minPx }
    }

    private fun title(l: Lamp) = l.title.ifBlank { TellTales.TITLES[l.id] ?: l.text.ifBlank { l.id } }

    /** One line of a lamp's words: [title] in its colour, else the value in ink. */
    data class Line(val text: String, val y: Float, val px: Float, val title: Boolean, val maxW: Float)

    data class Words(val lines: List<Line>) {
        /** The title took two lines (the lines under the slot move down a little). */
        val tall: Boolean get() = lines.count { it.title } > 1
        val texts: List<String> get() = lines.map { it.text }
    }

    /**
     * A lamp's words in the slot: [st] 1 (alone) — the title 26 px at 260, the value (with the detail when it fits) 22 px
     * at 284; 2 (one of two) — the title 22 px at 254 (shrunk to 17, else two lines at 19), the value 20 px under it.
     */
    private fun words(l: Lamp, st: Int, tw: Float, m: Measure): Words {
        val t = title(l)
        val v = l.valueText?.takeIf { it.isNotBlank() }
        val d = l.detail?.takeIf { it.isNotBlank() }
        if (st == 1) {
            val tpx = fit(m, t, 26f, 19f, tw, 700)
            val opts = listOfNotNull(if (v != null && d != null) "$v · $d" else null, v, d)
            val value = firstFit(m, opts, 22f, 18f, tw, 500)
            return Words(listOfNotNull(Line(t, 260f, tpx, true, tw), value?.let { Line(it.first, 284f, it.second, false, tw) }))
        }
        val value = firstFit(m, listOfNotNull(v, d), 20f, 15f, tw, 500)
        val one = fit(m, t, 22f, 17f, tw, 700)
        if (fits(m, t, one, tw, 700)) return Words(listOfNotNull(Line(t, 254f, one, true, tw), value?.let { Line(it.first, 276f, it.second, false, tw) }))
        val two = split2(m, t, 19f, tw, 700) ?: split2(m, t, 17f, tw, 700)
        if (two == null) return Words(listOfNotNull(Line(t, 254f, 17f, true, tw), value?.let { Line(it.first, 276f, it.second, false, tw) }))
        val px = if (split2(m, t, 19f, tw, 700) != null) 19f else 17f
        return Words(listOfNotNull(
            Line(two[0], 250f, px, true, tw), Line(two[1], 250f + px * 1.05f, px, true, tw),
            value?.let { Line(it.first, 250f + px * 2.15f, it.second, false, tw) },
        ))
    }

    /** A place in the slot: the lamp, its icon's centre and size, [st] its words' style in [tw]. */
    private class Place(val lamp: Lamp, val x: Float, val y: Float, val px: Float, val st: Int, val tw: Float, val words: Words)

    /**
     * Places for ≤ 2 lamps: one 80 px in the middle; two equal 72 px, or a red lead 80 px + the other 62 px. Two lamps of
     * different columns stand on their columns' sides, of one column by priority left to right; a red lead stands on its
     * column's side and the other opposite, so the takeover's flight never crosses it.
     */
    private fun arrange(list: List<Lamp>, m: Measure): List<Place> {
        if (list.isEmpty()) return emptyList()
        if (list.size == 1) return listOf(Place(list[0], C, SLOT_Y, 80f, 1, 250f, words(list[0], 1, 250f, m)))
        val a = list[0]
        val b = list[1]
        val sa = side(a.id)
        val sb = side(b.id)
        if (a.level == LampLevel.RED && b.level != LampLevel.RED) return listOf(
            Place(a, C + sa * 60f, SLOT_Y, 80f, 2, 140f, words(a, 2, 140f, m)),
            Place(b, C - sa * 72f, SLOT_Y, 62f, 2, 120f, words(b, 2, 120f, m)),
        )
        val aLeft = if (sa == sb) true else sa < sb
        return listOf(
            Place(a, if (aLeft) 170f else 310f, SLOT_Y, 72f, 2, 132f, words(a, 2, 132f, m)),
            Place(b, if (aLeft) 310f else 170f, SLOT_Y, 72f, 2, 132f, words(b, 2, 132f, m)),
        )
    }

    private data class P3(val x: Float, val y: Float, val px: Float)

    /** A quadratic path from [a] to [b] bending through (a.x, b.y): out of the column first, then in — round the battery. */
    private fun bz(a: P3, b: P3, k: Float): P3 {
        val u = 1 - k
        return P3(u * u * a.x + 2 * u * k * a.x + k * k * b.x, u * u * a.y + 2 * u * k * b.y + k * k * b.y, lerp(a.px, b.px, k))
    }

    private fun column(id: String, px: Float = LIT_PX) = lampPos(id, px).let { P3(it.x, it.y, px) }

    private fun plusX(l: List<Place>) = if (l.size == 2) (l[0].x + l[0].px / 2 + l[1].x - l[1].px / 2) / 2 else C

    /** The red takeover now: the lead lamp, its progress (eased) and its pulse's start. */
    private class Take(val lamp: Lamp, val k: Float, val pulseFrom: Long)

    /** The takeover's progress at [now] from window starts (each holds [TAKE_HOLD_MS]; overlapping ones merge). */
    private fun takeK(starts: List<Long>, now: Long, back: Long?, amb: Boolean): Float {
        var s0 = Long.MIN_VALUE
        var e0 = Long.MIN_VALUE
        var any = false
        for (st in starts.sorted()) {
            if (st > now) break
            if (any && st < e0) e0 = st + TAKE_HOLD_MS else { s0 = st; e0 = st + TAKE_HOLD_MS; any = true }
        }
        if (!any) return 0f
        if (amb) return if (now < e0) 1f else 0f
        var k = if (now < e0) prog(s0, now, TAKE_MS) else 1 - prog(e0, now, TAKE_MS)
        // a bulb check holds it back; it comes in after
        if (back != null && back > s0 && back < e0 + TAKE_MS) k = if (now < back) 0f else min(k, prog(back, now, TAKE_MS))
        return ease(k)
    }

    private fun take(s: DialState, now: Long, back: Long?, amb: Boolean): Take? {
        val buzz = s.cues.buzz?.takeIf { it.level == LampLevel.RED }
        val lead = s.lamps.top?.takeIf { it.level == LampLevel.RED }
        if (lead != null) {
            val from = s.lamps.lit.filter { it.level == LampLevel.RED }.maxOf { turned(it) }
            val b = buzz?.atMs?.takeIf { it >= from - BUZZ_EARLY_MS }
            val pulse = buzz?.atMs?.takeIf { it >= turned(lead) - BUZZ_EARLY_MS } ?: FaceKit.redSince(lead, s.cues)
            return Take(lead.lamp, takeK(listOfNotNull(from, b), now, back, amb), pulse)
        }
        // the red lamp went out: the takeover it had then folds back as the slot lets it go
        if (amb) return null
        val g = s.lamps.gone.filter { it.level == LampLevel.RED }.maxByOrNull { it.changedMs } ?: return null
        val at = g.changedMs
        val from = turned(g, gone = true)
        val b = buzz?.atMs?.takeIf { it >= from - BUZZ_EARLY_MS && it <= at }
        val k = takeK(listOfNotNull(from, b), at, back, false) * (1 - ease(prog(at, now, TAKE_MS)))
        return if (k <= 0.001f) null else Take(g.lamp, k, b ?: from)
    }

    /** The bulb check's place in its sweep for the lamp of IDS [i], 0…1. */
    private fun bulbK(i: Int, at: Long?, now: Long): Float {
        if (at == null) return 0f
        val back = at + TellTales.BULB_CHECK_MS
        if (now >= back + BULB_FADE_MS) return 0f
        return prog(at + BULB_ORDER.indexOf(i) * BULB_STEP_MS, now, BULB_RISE_MS) * (1 - prog(back, now, BULB_FADE_MS))
    }

    // -- the frame

    /**
     * [s] at [now] (on the state's clock). [powerF] / [speedF]: the arc's and the dot's places as drawn (the springs'
     * values; null speed: no dot).
     */
    fun frame(s: DialState, now: Long, m: Measure, powerF: Float = s.power.fraction, speedF: Float? = s.speed.fraction()): Frame {
        val amb = s.ambient
        val lowBit = amb && s.lowBit
        val live = s.live
        val dim = ArrayList<Op>(72)
        val top = ArrayList<Op>(32)
        fun lampCol(l: LampLevel) = if (lowBit) AMB_INK else FaceKit.lamp(l)
        fun titleCol(l: LampLevel) = if (lowBit) AMB_INK else if (amb) FaceKit.lamp(l) else FaceKit.title(l)

        // the bulb check: at start and on each reconnection; the real lamps come back at its end ([back])
        val bulbAt = if (amb) null else s.bulbAtMs?.takeIf { now >= it && now < it + TellTales.BULB_CHECK_MS + BULB_FADE_MS }
        val back = bulbAt?.plus(TellTales.BULB_CHECK_MS)
        val bulbRun = back != null && now < back
        val appear = { t: Long -> if (back != null) max(t, back) else t }

        // the slot: the set before the last change and now, animated over SLOT_MS from it
        val lit = s.lamps.lit
        val after = if (bulbRun) emptyList() else slotLamps(lit.map { it.lamp })
        var te: Long? = s.lamps.changedMs
        var beforeSet = slotLamps(s.lamps.before)
        if (back != null && (te == null || back > te)) { te = back; beforeSet = emptyList() }
        val a = if (amb || te == null) 1f else prog(te, now, SLOT_MS)
        val ea = if (amb) 1f else ease(a)
        val lb = arrange(beforeSet.take(2), m)
        val la = arrange(after.take(2), m)
        // the ring where a lamp lit goes with its buzz when that came with it
        val ringAt = te?.let { t -> s.cues.buzz?.atMs?.takeIf { it >= t && it - t < 1_000 } ?: t }

        class Shown(val w: Words, val x: Float, val tw: Float, val a: Float)
        class Item(val lamp: Lamp, val x: Float, val y: Float, val px: Float, val alpha: Float, val texts: List<Shown>, val leaving: Boolean)

        val items = ArrayList<Item>(4)
        val ripples = ArrayList<Op>(4)
        fun ripple(id: String, at: Long?, x: Float, y: Float, colour: Color, r0: Float, dr: Float, a0: Float, dur: Long) {
            if (amb) return
            val r = FaceKit.ripple(at, now, dur) ?: return
            if (r >= 1f) return
            ripples += Circle(id, x, y, r0 + dr * easeOut(r), colour, a0 * (1 - r), 3f, 0f, Role.RIPPLE)
        }
        for (pa in la) {
            val pb = lb.firstOrNull { it.lamp.id == pa.lamp.id }
            if (pb != null) {
                val same = pb.words.texts == pa.words.texts
                val texts = if (same) listOf(Shown(Words(pa.words.lines.mapIndexed { i, l ->
                    val o = pb.words.lines[i]
                    l.copy(y = lerp(o.y, l.y, ea), px = lerp(o.px, l.px, ea), maxW = lerp(o.maxW, l.maxW, ea))
                }), lerp(pb.x, pa.x, ea), lerp(pb.tw, pa.tw, ea), 1f))
                else listOf(Shown(pb.words, pb.x, pb.tw, clamp(1 - a / 0.2f)), Shown(pa.words, pa.x, pa.tw, clamp((a - 0.78f) / 0.22f)))
                items += Item(pa.lamp, lerp(pb.x, pa.x, ea), lerp(pb.y, pa.y, ea), lerp(pb.px, pa.px, ea), 1f, texts, false)
            } else {
                val q = if (amb) P3(pa.x, pa.y, pa.px) else bz(column(pa.lamp.id), P3(pa.x, pa.y, pa.px), ea)
                items += Item(pa.lamp, q.x, q.y, q.px, if (amb) 1f else clamp(a * 3), listOf(Shown(pa.words, pa.x, pa.tw, if (amb) 1f else clamp((a - 0.78f) / 0.22f))), false)
                val c = column(pa.lamp.id)
                ripple(pa.lamp.id, ringAt, c.x, c.y, lampCol(pa.lamp.level), 16f, 30f, 0.7f, RING_MS)
            }
        }
        // lit at the change but not in the slot (the «+N»): their ring in the column
        for (l in after.drop(2)) {
            val dl = s.lamps[l.id] ?: continue
            if (dl.changedMs != s.lamps.changedMs || !dl.rose) continue
            val c = column(l.id)
            ripple(l.id, ringAt, c.x, c.y, lampCol(l.level), 16f, 30f, 0.7f, RING_MS)
        }
        // back to its column: out (lamp off) or no longer amber / red (cooled to yellow, a blue one pushed out) — fades as it
        // lands, the column takes it from there; pushed out by a stronger one and still amber / red — lands lit, 40 px
        val landing = HashSet<String>()
        if (a < 1f) for (pb in lb) {
            if (la.any { it.lamp.id == pb.lamp.id }) continue
            val now0 = lit.firstOrNull { it.id == pb.lamp.id }
            val strongStill = now0 != null && now0.level >= LampLevel.AMBER
            if (now0 != null && !strongStill) landing += pb.lamp.id
            val home = column(pb.lamp.id, if (strongStill) STRONG_PX else LIT_PX)
            val q = bz(home, P3(pb.x, pb.y, pb.px), 1 - ease(clamp(a / 0.7f)))
            items += Item(pb.lamp, q.x, q.y, q.px, if (strongStill) 1f else 1 - clamp((a - 0.5f) / 0.2f), listOf(Shown(pb.words, pb.x, pb.tw, clamp(1 - a / 0.2f))), true)
        }
        val landAt = te?.plus(SLOT_MS / 2)
        val inSlot = items.map { it.lamp.id }.toSet()
        // the battery moves back only once the icons are out of its way
        val mk = when {
            lb.isNotEmpty() && la.isNotEmpty() -> 1f
            la.isNotEmpty() -> ea
            lb.isNotEmpty() -> if (amb) 0f else 1 - ease(clamp((a - 0.35f) / 0.65f))
            else -> 0f
        }
        val tallY = lerp(if (lb.any { it.words.tall }) 10f else 0f, if (la.any { it.words.tall }) 10f else 0f, ea)
        val moreB = max(0, beforeSet.size - 2)
        val moreA = max(0, after.size - 2)

        // the takeover
        val tko = if (bulbRun) null else take(s, now, back, amb)
        val tk = tko?.k ?: 0f
        val br = if (amb || bulbRun || tko == null) 1f else FaceKit.pulse(tko.pulseFrom, now)
        val colA = 1 - tk

        // -- the bezel: segments of the amber and red lamps, the red ring, the comets, the tap's flash
        val strong = lit.filter { it.level >= LampLevel.AMBER && it.id !in TellTales.QUIET && index(it.id) >= 0 }
        val strongIds = strong.map { it.id }.toSet()
        if (!bulbRun) {
            for (l in strong) {
                val k = if (amb) 1f else easeOut(prog(appear(strongAt(l)), now, LIT_MS))
                if (k <= 0f) continue
                seg(dim, l.id, strongIds, k, lampCol(l.level), k * (if (l.level == LampLevel.RED) br else 1f) * (1 - tk))
            }
            if (!amb) for (g in s.lamps.gone) {
                if (g.level < LampLevel.AMBER || g.id in TellTales.QUIET || index(g.id) < 0) continue
                val k = 1 - prog(g.changedMs, now, OUT_MS)
                if (k > 0f) seg(dim, g.id, strongIds, 1f, lampCol(g.level), k * (1 - tk))
            }
        }
        if (tk > 0f && !amb) dim += Ring(tk * br)
        val over = live && !amb && s.cues.rumble
        val zk = if (over) prog(s.cues.rumbleSinceMs ?: now, now, LIT_MS) else 0f
        if (over) dim += Comets(zk, -((now * 0.13) % 360.0).toFloat())
        val sp = s.speed
        val f = speedF?.takeIf { sp.value != null }?.coerceIn(0f, 1f)
        val tap = if (amb || !live) null else s.cues.tapAtMs?.takeIf { now >= it }
        val after0 = tap?.let { now - it }
        val dotDeg = f?.let { spdDeg(it) }
        if (after0 != null && after0 <= 520 && dotDeg != null) dim += Flash(dotDeg, 1 - easeOut(after0 / 520f))

        // -- the power arc and the speed track
        dim += Power(powerF)
        val mph = sp.unit == "mph"
        val v = sp.value
        val near = v != null && sp.limit != null && v >= sp.limit - (if (mph) 1.0 else Fmt.MILE)
        val hot = live && v != null && (sp.tapAt?.let { v >= it } ?: (sp.limit != null && v >= sp.limit * CuePoints.TAP_MPH / CuePoints.FLY_LIMIT_MPH))
        val ticks = ArrayList<Float>(8)
        var tv = 0.0
        while (tv <= sp.max + 1e-9) { ticks += spdDeg((tv / sp.max).toFloat()); tv += Dial.TICK_EVERY }
        val tremble = if (over) Offset((1.4 * sin(now / 1000.0 * 2 * PI * 23)).toFloat(), (1.1 * cos(now / 1000.0 * 2 * PI * 19)).toFloat()) else Offset.Zero
        dim += Speed(
            fraction = f,
            label = v?.let { if (near) Fmt.num(max(0.0, it), 1) else max(0.0, it).roundToLong().toString() },
            pill = near, ticks = ticks,
            limitDeg = sp.fraction(sp.limit)?.let { spdDeg(it) },
            hot = hot,
            tickFlash = if (after0 != null && after0 <= 750) clamp(1 - (after0 - 150) / 600f) else 0f,
            zoneDeg = sp.fraction(sp.rumbleAt)?.let { spdDeg(it) }, zoneK = zk, over = over, tremble = tremble,
            ringPhase = if (over) Math.floorMod(now, 420L) / 420f else 0f,
        )

        // -- the columns
        var yellowCorner = false
        for ((i, id) in TellTales.IDS.withIndex()) {
            val dl = s.lamps[id]
            val gone = if (dl == null) s.lamps.gone.firstOrNull { it.id == id } else null
            val l = dl ?: gone
            val bk = if (bulbAt != null) bulbK(i, bulbAt, now) else 0f
            val k = when {
                id in inSlot && id !in landing -> 0f
                dl != null -> if (amb) 1f else prog(appear(levelAt(dl)).let { t -> if (id in landing && landAt != null) max(t, landAt) else t }, now, LIT_MS)
                gone != null && !amb -> 1 - prog(gone.changedMs, now, OUT_MS)
                else -> 0f
            }
            if (amb && k <= 0f) continue
            val level = l?.level ?: LampLevel.OFF
            val dimLamp = id in DIM
            val deg = Dial.lampDeg(i)
            if (l != null && level == LampLevel.YELLOW && !dimLamp) {
                // grows where it is into a tinted circle; never solid
                val big = lerp(YEL_PX, YEL_BUSY_PX, mk)
                val g = if (amb) k else back(k)
                val d = lerp(OFF_PX, big, g)
                val p = pt(lerp(R_LAMP, lerp(142f, 152f, mk), clamp(g)), deg)
                if (!amb && k < 1f) dim += Icon(id, Glyphs.BY_ID.getValue(id), pt(R_LAMP, deg).x, pt(R_LAMP, deg).y, OFF_PX, OFF, colA * (1 - k) * (1 - bk), 0f, 0f, 1f, 0f, false, Role.SOCKET)
                if (k > 0f) {
                    val al = colA * clamp(k * 2)
                    val col = lampCol(level)
                    dim += Circle(id, p.x, p.y, d / 2, col, al * (if (amb) 0.6f else 0.75f), if (amb) 1.4f else 2.5f, if (amb) 0f else 0.16f / 0.75f, Role.YELLOW)
                    dim += Icon(id, FaceKit.icon(l.lamp), p.x, p.y, d * 0.7f, col, al, 0f, 0f, 1f, if (amb) 1.6f else 0f, false, Role.YELLOW)
                    if (l.lamp.rising) {
                        val hop = if (amb || dl == null) 0f else FaceKit.bob(hopFrom(dl), now, HOP_PX)
                        dim += Tri(id, p.x - side(id) * d * 0.3f, p.y - d / 2 - 6f - hop, max(10f, d * 0.24f), col, al)
                    }
                    if (i % 6 == 0 && al > 0.05f) yellowCorner = true
                    if (dl != null) ripple(id, appear(levelAt(dl)), pt(142f, deg).x, pt(142f, deg).y, col, 26f, 34f, 0.7f, YELLOW_RING_MS)
                }
                continue
            }
            val strongLamp = level >= LampLevel.AMBER
            val tgt = if (l != null && !dimLamp) (if (strongLamp) STRONG_PX else LIT_PX) else OFF_PX
            val grow = if (l != null && !dimLamp) (if (amb) k else back(k)) else 0f
            val px = OFF_PX + (tgt - OFF_PX) * grow
            val p = lampPos(id, max(OFF_PX, px))
            if (!amb && k < 1f) dim += Icon(id, Glyphs.BY_ID.getValue(id), p.x, p.y, OFF_PX, OFF, colA * (1 - k) * (1 - bk), 0f, 0f, 1f, 0f, false, Role.SOCKET)
            if (k > 0f && l != null) {
                val col = lampCol(level)
                val pulse = if (level == LampLevel.RED) br else 1f
                if (!amb && !dimLamp) dim += Glow(p.x, p.y, px * 0.9f, col, colA * k * pulse * 0.32f)
                val al = colA * k * (if (dimLamp) (if (amb) 0.7f else 0.5f) else 1f)
                dim += Icon(id, FaceKit.icon(l.lamp), p.x, p.y, px, col, al, 0f, 0f, 1f, if (amb) 1.6f else 0f, false, Role.COLUMN)
                if (l.lamp.rising && !dimLamp) {
                    val hop = if (amb || dl == null) 0f else FaceKit.bob(hopFrom(dl), now, HOP_PX * 0.6f)
                    dim += Tri(id, p.x - side(id) * px * 0.55f, p.y - px * 0.45f - hop, max(8f, px * 0.26f), col, al)
                }
            }
        }
        // the bulb check, undimmed: every lamp in its strongest colour round the dial, with its segment
        if (bulbAt != null) for ((i, id) in TellTales.IDS.withIndex()) {
            val k = bulbK(i, bulbAt, now)
            if (k <= 0.002f) continue
            val col = FaceKit.lamp(BULB_LEVELS.getValue(id))
            val deg = Dial.lampDeg(i)
            val kk = easeOut(k)
            top += Seg(deg - 9.5f * kk, deg + 9.5f * kk, deg, col, k)
            val px = OFF_PX + (LIT_PX - OFF_PX) * back(k)
            val p = lampPos(id, px)
            top += Glow(p.x, p.y, px * 0.9f, col, k * 0.32f)
            top += Icon(id, Glyphs.BY_ID.getValue(id), p.x, p.y, px, col, k, 0f, 0f, 1f, 0f, false, Role.BULB)
        }
        dim += ripples

        // -- the lines: the range, «V · °C · MODE», the lamp line, the power
        val topLamp = s.lamps.top
        val lineA = 1 - tk
        var line: Pair<String, Color>? = null
        var lineK = 0f
        if (bulbAt != null) {
            line = Dial.BULB_TEXT to MUTED
            lineK = prog(bulbAt, now, 150) * (1 - prog(back, now, BULB_FADE_MS))
        } else if (tap != null && now - tap < LIMITER_LINE_MS + 300) {
            line = ("Ограничитель" + (v?.let { " · " + Fmt.num(it, 1) + NBSP + sp.unit } ?: "")) to (if (amb) AMB_INK else INK)
            lineK = prog(tap, now, 150) * (1 - prog(tap + LIMITER_LINE_MS, now, 300))
        } else if (topLamp != null && topLamp.level == LampLevel.YELLOW && topLamp.lamp.text.isNotBlank()) {
            line = topLamp.lamp.text to titleCol(LampLevel.YELLOW)
            lineK = if (amb) 1f else prog(appear(levelAt(topLamp)), now, LIT_MS)
        }
        val busy = clamp(mk * 2)
        val lineAlpha = if (line == null) 0f else clamp((lineK - 0.45f) / 0.55f) * (1 - busy)
        val lineShift = if (line == null) 0f else ease(clamp(lineK / 0.6f)) * (1 - mk)
        // the centred lines keep clear of what the columns show at their height (a lamp lit 40 px low in its column)
        val obstacles = (dim + top).mapNotNull { op ->
            when (op) {
                is Icon -> if (op.alpha >= 0.05f && (op.role == Role.SOCKET || op.role == Role.COLUMN || op.role == Role.YELLOW || op.role == Role.BULB))
                    floatArrayOf(op.x, op.y, op.px * 0.375f, op.px * 0.375f) else null
                is Circle -> if (op.role == Role.YELLOW && op.alpha >= 0.05f) (op.r + op.width / 2).let { floatArrayOf(op.x, op.y, it, it) } else null
                is Tri -> if (op.alpha >= 0.05f) floatArrayOf(op.x, op.y, op.size / 2, op.size * 0.43f) else null
                else -> null
            }
        }
        fun room(baseline: Float, px: Float, want: Float): Float {
            val t = baseline - 0.74f * px - 1.5f
            val b = baseline + 0.2f * px + 1.5f
            var half = want / 2
            for (o in obstacles) if (o[1] + o[3] > t && o[1] - o[3] < b) half = min(half, abs(o[0] - C) - o[2] - 3f)
            return max(40f, 2 * half)
        }
        s.range?.let {
            val y = lerp(258f, 312f + tallY, mk)
            val px0 = lerp(26f, 24f, mk)
            val w = room(y, px0, 220f)
            dim += Txt(it, C, y, fit(m, it, px0, px0 * 0.75f, w, 500), 500, if (amb) AMB_INK else if (s.rangeRough) MUTED else INK, lineA, w, Role.LINE)
        }
        val dy = lerp(286f, 333f + tallY, mk)
        val dpx = lerp(19f, 17f, mk)
        details(s, m, dy, dpx, room(dy, dpx, lerp(236f, 200f, mk)), lineA, amb, lowBit)?.let { dim += it }
        if (line != null && lineAlpha * lineA > 0.002f) {
            val w = room(322f, 22f, 226f)
            dim += Txt(line.first, C, 322f, fit(m, line.first, 22f, 15f, w, 600), 600, line.second, lineAlpha * lineA, w, Role.LINE)
        }
        val powerY = lerp(lerp(lerp(326f, 352f, lineShift), 355f + tallY, mk), 416f, tk)
        val ppx = lerp(lerp(20f, 18f, mk), 18f, tk)
        val pw = lerp(room(powerY, ppx, 200f), 200f, tk)
        dim += Txt(s.power.text, C, powerY, fit(m, s.power.text, ppx, ppx * 0.75f, pw, 400), 400, if (amb) AMB_INK else Color(s.power.argb), 1f, pw, Role.LINE)

        // -- the battery: 140×66 calm, 124×48 with the slot, a small icon beside «12,4 %» in the takeover
        dim += battery(s, m, now, mk, tk, amb, lowBit)

        // -- the slot
        for (item in items) {
            val lead = tko != null && tk > 0f && item.lamp.id == tko.lamp.id
            val col = lampCol(item.lamp.level)
            val red = item.lamp.level == LampLevel.RED
            var x = item.x
            var y = item.y
            var px = item.px
            if (tko != null && tk > 0f && !lead) {
                // during the takeover the other lamp waits at the side opposite the red lamp's column
                val sx = C - side(tko.lamp.id) * 128f
                x = lerp(x, sx, tk); y = lerp(y, TAKE_Y, tk); px = lerp(px, SIDE_PX, tk)
            }
            if (!lead) {
                val pulse = if (red) br else 1f
                dim += Icon(item.lamp.id, FaceKit.icon(item.lamp), x, y, px, col, item.alpha, if (amb) 0f else 0.7f * pulse, 10f, weightFor(px),
                    if (amb) 2.4f else 0f, FaceKit.icon(item.lamp) === Glyphs.BATTERY_CUT, Role.SLOT)
                if (item.lamp.rising) {
                    val dl = s.lamps[item.lamp.id]
                    val hop = if (amb || dl == null) 0f else FaceKit.bob(hopFrom(dl), now, HOP_PX)
                    dim += Tri(item.lamp.id, x + side(item.lamp.id) * px * 0.42f, y - px * 0.42f - hop, max(10f, px * 0.2f), col, item.alpha)
                }
            }
            val wa = if (tko != null && tk > 0f) clamp(1 - tk * 4) else 1f
            for (sh in item.texts) {
                val al = sh.a * wa * item.alpha
                if (al <= 0.002f) continue
                for (ln in sh.w.lines) dim += Txt(
                    ln.text, sh.x, ln.y, ln.px, if (ln.title) (if (amb) 500 else 700) else 500,
                    if (ln.title) titleCol(item.lamp.level) else if (amb) AMB_INK else INK, al, sh.tw, Role.SLOT, item.lamp.id,
                )
            }
            if (tko != null && tk > 0f && !lead && !item.leaving) {
                val sa = clamp((tk - 0.75f) / 0.25f)
                if (sa > 0f) {
                    val opts = listOfNotNull(item.lamp.valueText, item.lamp.detail, title(item.lamp))
                    firstFit(m, opts, 20f, 14f, 96f, 700)?.let { (t, z) ->
                        dim += Txt(t, x, y + 50f, z, 700, titleCol(item.lamp.level), sa * item.alpha, 96f, Role.SLOT, item.lamp.id)
                    }
                }
            }
        }
        val more = if (moreA > 0) Triple(moreA, if (moreB == moreA) 1f else clamp((a - 0.5f) / 0.5f), plusX(la))
            else if (moreB > 0) Triple(moreB, 1 - clamp(a / 0.3f), plusX(lb)) else null
        if (more != null && more.second * (1 - tk) > 0.002f) {
            dim += Txt("+" + more.first, more.third, SLOT_Y + 8f, 22f, 700, if (amb) AMB_INK else INK2, more.second * (1 - tk), 60f, Role.MORE)
        }

        // -- the red takeover: the icon flies from its place in the slot to the centre, its words come in at the end
        if (tko != null && tk > 0f) {
            val held = items.firstOrNull { it.lamp.id == tko.lamp.id }
            val from = if (held != null) P3(held.x, held.y, held.px) else column(tko.lamp.id)
            val q = if (amb) P3(TAKE_X, TAKE_Y, BIG_PX) else bz(from, P3(TAKE_X, TAKE_Y, BIG_PX), tk)
            val col = lampCol(tko.lamp.level)
            val glyphs = FaceKit.icon(tko.lamp)
            dim += Icon(tko.lamp.id, glyphs, q.x, q.y, q.px, col, 1f, if (amb) 0f else 0.75f * br, 12f, weightFor(q.px),
                if (amb) 3f else 0f, glyphs === Glyphs.BATTERY_CUT, Role.TAKEOVER)
            val ta = if (amb) 1f else clamp((tk - 0.75f) / 0.25f)
            if (ta > 0f) {
                val (big, bpx) = bigLines(title(tko.lamp), m)
                big.forEachIndexed { j, t ->
                    dim += Txt(t, C, 318f + 39f * j, bpx, 800, titleCol(tko.lamp.level), ta, if (j == 0) 318f else 270f, Role.TAKEOVER, tko.lamp.id)
                }
                val opts = bigValues(tko.lamp, s.range)
                val vy = 318f + 39f * big.size - 6f
                val shown = firstFit(m, opts, 24f, 18f, 246f, 500)?.also { (t, z) ->
                    dim += Txt(t, C, vy, z, 500, if (amb) AMB_INK else INK, ta, 246f, Role.TAKEOVER, tko.lamp.id)
                }
                // one line of title leaves room for the detail under the values
                val d = tko.lamp.detail?.takeIf { it.isNotBlank() }
                if (big.size == 1 && d != null && shown != null && !shown.first.contains(d)) {
                    dim += Txt(d, C, vy + 28f, fit(m, d, 20f, 15f, 220f, 500), 500, if (amb) AMB_DIM else INK2, ta, 220f, Role.TAKEOVER, tko.lamp.id)
                }
            }
        }

        // -- the top line: what is wrong with the data, else the time (undimmed)
        status(s, m, top, mk, tk, amb, yellowCorner)

        val slotIds = la.map { it.lamp.id }
        val settled = (amb || ((te == null || now - te >= SLOT_MS + RING_MS) && lit.all { now - appear(it.changedMs) >= LIT_MS + 300 } &&
            s.lamps.gone.all { now - it.changedMs >= TAKE_MS + 50 } && (tk == 0f || tk >= 0.999f) && bulbAt == null &&
            (tap == null || now - tap >= LIMITER_LINE_MS + 300)))
        return Frame(
            amb, lowBit, s.health.alpha, dim, top,
            main = slotIds.firstOrNull() ?: topLamp?.takeIf { it.level == LampLevel.YELLOW && !bulbRun }?.id,
            takeover = tk, slot = slotIds, more = moreA, line = line?.first?.takeIf { lineAlpha > 0.05f }, settled = settled,
        )
    }

    /** A lamp's bezel segment: ±12,5° alone (5 dashes), ±7,5° towards a lit neighbour in its column (a clear break). */
    private fun seg(out: MutableList<Op>, id: String, on: Set<String>, k: Float, colour: Color, alpha: Float) {
        val i = index(id)
        val a = Dial.lampDeg(i)
        val ids = TellTales.IDS
        val prev = i % 6 > 0 && ids[i - 1] in on
        val next = i % 6 < 5 && ids[i + 1] in on
        val far = 12.5f
        val near = 7.5f
        val lo: Float
        val hi: Float
        if (i < 6) { lo = a - (if (prev) near else far); hi = a + (if (next) near else far) }
        else { hi = a + (if (prev) near else far); lo = a - (if (next) near else far) }
        out += Seg(a + (lo - a) * k, a + (hi - a) * k, a, colour, alpha)
    }

    /** The title in 38-px capitals: one line, two (the second ≤ 270 px), smaller, ellipsized — in that order. */
    private fun bigLines(t: String, m: Measure): Pair<List<String>, Float> {
        val up = t.uppercase(RU)
        if (fits(m, up, 38f, 318f, 800)) return listOf(up) to 38f
        if (fits(m, up, 34f, 318f, 800)) return listOf(up) to fit(m, up, 38f, 34f, 318f, 800)
        for (px in floatArrayOf(38f, 34f, 30f)) split2(m, up, px, 270f, 800)?.let { return it to px }
        return listOf(up) to fit(m, up, 30f, 24f, 318f, 800)
    }

    /**
     * Under the takeover's title: the value, the detail and the range (hidden in the takeover), what fits; for the
     * battery the range before the detail («31,5 В · ≈ 0,4 mi»: how far it still goes).
     */
    private fun bigValues(l: Lamp, range: String?): List<String> {
        val v = l.valueText?.takeIf { it.isNotBlank() }
        val d = l.detail?.takeIf { it.isNotBlank() }
        val r = range?.takeIf { it.isNotBlank() }
        fun j(vararg p: String?) = p.filterNotNull().joinToString(" · ")
        val order = if (l.id == TellTales.BATTERY) listOf(j(v, r, d), j(v, r), j(v, d), j(v), j(r), j(d))
            else listOf(j(v, d, r), j(v, d), j(v, r), j(v), j(d), j(r))
        return order.filter { it.isNotEmpty() }
    }

    /** «V · °C · MODE», the voltage in the battery / over-voltage lamp's colour, the temperature in the temperature lamp's. */
    private fun details(s: DialState, m: Measure, y: Float, px0: Float, maxW: Float, alpha: Float, amb: Boolean, lowBit: Boolean): Parts? {
        if (s.details.isBlank() || alpha <= 0.002f) return null
        val hiV = listOfNotNull(s.lamps[TellTales.BATTERY], s.lamps[TellTales.OVERVOLT]).filter { it.level >= LampLevel.YELLOW }.maxOfOrNull { it.level }
        val hiT = (s.lamps[TellTales.TEMP] ?: s.lamps[TellTales.COLD])?.level
        val sep = if (amb) AMB_DIM else MUTED
        val parts = ArrayList<Part>(5)
        for ((i, p) in s.details.split(" · ").withIndex()) {
            if (i > 0) parts += Part(" · ", sep, 400)
            val hi = when { p.endsWith("В") -> hiV; p.endsWith("°C") -> hiT; else -> null }
            parts += if (hi == null) Part(p, sep, 400)
            else Part(p, if (lowBit || amb) AMB_INK else FaceKit.title(hi), if (amb) 400 else 700)
        }
        var px = px0
        val tot = parts.sumOf { m.width(it.text, px, it.weight).toDouble() }.toFloat()
        if (tot > maxW) px *= maxW / tot * 0.99f
        return Parts(parts, C, y, px, alpha)
    }

    private fun battery(s: DialState, m: Measure, now: Long, mk: Float, tk: Float, amb: Boolean, lowBit: Boolean): Battery {
        val b = s.battery
        val tw = m.width(b.text, 32f, 700)
        val takeX = C - (44f + 6f + 12f + tw) / 2
        fun g(calm: Float, med: Float, take: Float) = lerp(lerp(calm, med, mk), take, tk)
        val x = g(162f, 172f, takeX)
        val y = g(156f, 94f, 92f)
        val w = g(140f, 124f, 44f)
        val h = g(66f, 48f, 24f)
        val pad = g(8f, 6f, 4.5f)
        val font = g(40f, 32f, 32f)
        val tx = lerp(lerp(232f, 234f, mk), takeX + 44f + 18f + tw / 2, tk)
        val ty = lerp(lerp(156f + 33f + 40f * 0.36f, 94f + 24f + 32f * 0.36f, mk), 92f + 12f + 32f * 0.36f, tk)
        val maxW = lerp(w - 2 * pad + 8f, 400f, tk)
        var px = font
        val tww = m.width(b.text, px, 700)
        if (tww > maxW) px *= maxW / tww
        val tone = if (lowBit) AMB_INK else Color(b.toneArgb)
        val shimmer = if (amb || !b.charging) null else {
            val p = Math.floorMod(now, SHIMMER_EVERY_MS).toFloat() / SHIMMER_MS
            p.takeIf { it < 1f }
        }
        return Battery(x, y, w, h, g(12f, 10f, 5f), g(4f, 3.5f, 2.5f), pad, b.pct, tone, b.text, tx, ty, px, px * 0.14f * (1 - tk), shimmer)
    }

    /**
     * The status line where the time is (y 118 calm, 86 with the slot, over «12,4 %» in the takeover), one or two lines in
     * the health's colour; all live: the time, gone in the takeover. [corner]: a yellow circle at a column's top narrows it.
     */
    private fun status(s: DialState, m: Measure, out: MutableList<Op>, mk: Float, tk: Float, amb: Boolean, corner: Boolean) {
        val label = s.status
        if (label == null) {
            if (s.clock.isNotBlank() && tk < 0.999f) out += Txt(
                s.clock, C, lerp(118f, 86f, mk), lerp(20f, 17f, mk), 400, if (amb) AMB_DIM else MUTED, 1 - tk, 140f, Role.STATUS,
            )
            return
        }
        val col = if (amb) AMB_INK else Color(s.health.tone)
        val f1 = lerp(lerp(19f, 16f, mk), 15f, tk)
        val y1 = lerp(lerp(118f, 86f, mk), 80f, tk)
        val f2 = lerp(lerp(18f, 14f, mk), 13f, tk)
        val last = lerp(lerp(120f, 89f, mk), 87f, tk)
        // the top line is the nearest to the round edge: no wider than the speed track's chord at its cap height
        val want = lerp(lerp(if (corner) 106f else 170f, 160f, mk), 140f, tk)
        fun chord(top: Float) = min(want, 2 * kotlin.math.sqrt(max(0f, (TEXT_R - 3f) * (TEXT_R - 3f) - (C - top) * (C - top))))
        val w1 = chord(y1 - 0.74f * f1)
        val first2 = last - 1.15f * f2
        val w2 = chord(first2 - 0.74f * f2)
        val given = label.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        var lines: List<String>? = null
        if (given.size == 1) {
            val z = fit(m, given[0], f1, 13f, w1, 500)
            if (fits(m, given[0], z, w1, 500) && z >= 14f) { out += Txt(given[0], C, y1, z, 500, col, 1f, w1, Role.STATUS); return }
            lines = split2(m, given[0], 14f, w2, 500)
            if (lines == null) { out += Txt(given[0], C, y1, 13f, 500, col, 1f, w1, Role.STATUS); return }
        }
        val two = (lines ?: given).take(2)
        if (two.size == 1) { out += Txt(two[0], C, y1, fit(m, two[0], f1, 12f, w1, 500), 500, col, 1f, w1, Role.STATUS); return }
        two.forEachIndexed { j, t ->
            out += Txt(t, C, if (j == 0) first2 else last, fit(m, t, f2, 12f, w2, 500), 500, col, 1f, w2, Role.STATUS)
        }
    }

    /**
     * When the face next moves ([rememberFaceClock]): now while a red lamp pulses or the rumble runs, while anything is in
     * transition (≤ 700 ms after a change, the limiter's line 3,3 s, the bulb check); then the next ▲ hop or charging band;
     * null when all is still. Ambient: never.
     */
    fun wakeAt(s: DialState, now: Long): Long? {
        if (s.ambient) return null
        if (s.lamps.anyRed || (s.cues.rumble && s.live)) return now
        var busy = false
        fun until(t: Long?) { if (t != null && t > now) busy = true }
        until(s.lamps.changedMs?.plus(SLOT_MS + RING_MS + 100))
        for (l in s.lamps.lit) until(l.changedMs + FaceKit.APPEAR_MS)
        for (l in s.lamps.gone) until(l.changedMs + TAKE_MS + 100)
        until(s.cues.tapAtMs?.plus(LIMITER_LINE_MS + 400))
        until(s.cues.buzz?.atMs?.plus(RING_MS + 100))
        until(s.bulbAtMs?.plus(TellTales.BULB_CHECK_MS + BULB_FADE_MS + SLOT_MS + RING_MS + 100))
        if (busy) return now
        var next: Long? = null
        fun at(t: Long) { if (next == null || t < next!!) next = t }
        for (l in s.lamps.lit) if (l.lamp.rising) {
            val since = hopFrom(l)
            val k = ((now - since) / FaceKit.BOB_EVERY_MS).coerceAtLeast(0)
            val start = since + k * FaceKit.BOB_EVERY_MS
            at(if (now < since) since else if (now <= start + FaceKit.BOB_MS) now else start + FaceKit.BOB_EVERY_MS)
        }
        if (s.battery.charging) {
            val t0 = now - Math.floorMod(now, SHIMMER_EVERY_MS)
            at(if (now - t0 < SHIMMER_MS) now else t0 + SHIMMER_EVERY_MS)
        }
        return next
    }

    // -- for the tests

    /** Something drawn, as a box in mock px: [text] / icon, [owner] the lamp (or «battery»), [role]. */
    data class Box(
        val what: String, val owner: String, val role: Role, val text: Boolean, val l: Float, val t: Float, val r: Float, val b: Float,
        /** A circle in its square. */
        val round: Boolean = false,
    ) {
        fun overlaps(o: Box, slack: Float = 0.5f): Boolean {
            if (!(l < o.r - slack && o.l < r - slack && t < o.b - slack && o.t < b - slack)) return false
            val c = if (round) this else if (o.round) o else return true
            val q = if (c === this) o else this
            val cx = (c.l + c.r) / 2
            val cy = (c.t + c.b) / 2
            val dx = cx - cx.coerceIn(q.l, q.r)
            val dy = cy - cy.coerceIn(q.t, q.b)
            val rad = (c.r - c.l) / 2 - slack
            return dx * dx + dy * dy < rad * rad
        }
    }

    /** The boxes of what [f] shows (alpha ≥ 0,05): texts (cap height to descender), icons (their glyphs' 18-unit core), circles, the battery. */
    fun boxes(f: Frame, m: Measure): List<Box> {
        val out = ArrayList<Box>()
        for (op in f.ops) when (op) {
            is Txt -> if (op.alpha >= 0.05f) {
                val w = min(m.width(op.text, op.px, op.weight), op.maxW)
                val l = when (op.anchor) { Anchor.START -> op.x; Anchor.CENTER -> op.x - w / 2; Anchor.END -> op.x - w }
                out += Box(op.text, op.id.ifEmpty { op.role.name }, op.role, true, l, op.baseline - 0.74f * op.px, l + w, op.baseline + 0.2f * op.px)
            }
            is Parts -> if (op.alpha >= 0.05f) {
                val w = op.parts.sumOf { m.width(it.text, op.px, it.weight).toDouble() }.toFloat()
                out += Box(op.parts.joinToString("") { it.text }, "details", Role.LINE, true, op.x - w / 2, op.baseline - 0.74f * op.px, op.x + w / 2, op.baseline + 0.2f * op.px)
            }
            is Icon -> if (op.alpha >= 0.05f) {
                val h = op.px * 0.375f
                out += Box("icon " + op.id, op.id, op.role, false, op.x - h, op.y - h, op.x + h, op.y + h)
            }
            is Circle -> if (op.alpha >= 0.05f && op.role == Role.YELLOW) {
                val r = op.r + op.width / 2
                out += Box("circle " + op.id, op.id, op.role, false, op.x - r, op.y - r, op.x + r, op.y + r, round = true)
            }
            is Tri -> if (op.alpha >= 0.05f) out += Box("▲ " + op.id, op.id, Role.YELLOW, false, op.x - op.size / 2, op.y - op.size * 0.43f, op.x + op.size / 2, op.y + op.size * 0.43f)
            is Battery -> {
                val nw = max(4f, op.w * 0.086f)
                val nh = op.h * 0.42f
                val nx = op.x + op.w + op.line / 2 + 1f
                out += Box("battery", "battery", Role.BATTERY, false, op.x - op.line / 2, op.y - op.line / 2, op.x + op.w + op.line / 2, op.y + op.h + op.line / 2)
                out += Box("battery nub", "battery", Role.BATTERY, false, nx, op.y + (op.h - nh) / 2, nx + nw, op.y + (op.h + nh) / 2)
                val w = min(m.width(op.text, op.px, 700), 400f)
                out += Box(op.text, "battery", Role.BATTERY, true, op.tx - w / 2, op.ty - 0.74f * op.px, op.tx + w / 2, op.ty + 0.2f * op.px)
            }
            else -> {}
        }
        return out
    }

    /**
     * What is wrong with [f]: anything past the speed track (texts; icons past the power arc) or, past ±135°, past the
     * screen; when [settled], any two things of different owners that overlap (a lamp's circle, icon and ▲ are one owner;
     * the % sits in the battery). Empty: fine.
     */
    fun problems(f: Frame, m: Measure, settled: Boolean = f.settled): List<String> {
        val out = ArrayList<String>()
        val bs = boxes(f, m)
        for (b in bs) {
            for ((x, y) in listOf(b.l to b.t, b.r to b.t, b.l to b.b, b.r to b.b)) {
                val r = hypot(x - C, y - C)
                val deg = FaceKit.clockDeg(x, y)
                val gap = deg > 135f && deg < 225f
                val lim = if (gap) GAP_R else if (b.text) TEXT_R else 202f
                if (r > lim) { out += "«${b.what}» past the edge (r ${r.roundToInt()} at ${deg.roundToInt()}°)"; break }
            }
        }
        if (settled) for (i in bs.indices) for (j in i + 1 until bs.size) {
            val p = bs[i]
            val q = bs[j]
            if (p.owner == q.owner) continue
            if (p.role == Role.SOCKET && q.role == Role.SOCKET) continue
            if (p.overlaps(q)) out += "«${p.what}» × «${q.what}»"
        }
        return out
    }
}

/** The face's TextMeasurer in mock px (the canvas is scaled to the grid), styles kept. */
private class TakeoverMeter(private val pen: TakeoverPen) : TakeoverLayout.Measure {
    lateinit var tm: TextMeasurer

    fun style(px: Float, weight: Int): TextStyle = pen.style(px, weight)

    fun layout(text: String, px: Float, weight: Int, maxW: Float): TextLayoutResult = tm.measure(
        text, style(px, weight), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
        constraints = Constraints(maxWidth = ceil(max(1f, maxW)).toInt()),
    )

    override fun width(text: String, px: Float, weight: Int): Float =
        tm.measure(text, style(px, weight), softWrap = false, maxLines = 1).size.width.toFloat()
}

/** Paints a [TakeoverLayout.Frame] on the mock's grid; keeps what it can (brushes, paints, styles) between frames. */
private class TakeoverPen {
    private val styles = HashMap<Int, TextStyle>()
    private var base: TextStyle? = null
    private var density = 0f
    private var fontScale = 0f
    private val meter = TakeoverMeter(this)
    private lateinit var d: Density

    fun meter(tm: TextMeasurer, base: TextStyle, d: Density): TakeoverMeter {
        if (base != this.base || d.density != density || d.fontScale != fontScale) {
            styles.clear(); this.base = base; density = d.density; fontScale = d.fontScale
        }
        this.d = d
        meter.tm = tm
        return meter
    }

    /** [px] mock px (= canvas px before the grid's scale), tabular digits, the face's base style. */
    fun style(px: Float, weight: Int): TextStyle {
        val key = (px * 4).roundToInt() * 16 + weight / 100
        return styles.getOrPut(key) {
            (base ?: TextStyle()).merge(TextStyle(fontSize = with(d) { px.toSp() }, fontWeight = FontWeight(weight), fontFeatureSettings = "tnum"))
        }
    }

    private val halos = HashMap<Long, Brush>()
    private val glows = HashMap<Long, Brush>()
    private val sweeps = HashMap<Long, Brush>()
    private val layerPaint = Paint()
    private val tri = Path()
    private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val blurs = HashMap<Int, BlurMaskFilter>()
    private val shimmer = Brush.horizontalGradient(0f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = 0.55f), 1f to Color.White.copy(alpha = 0f), startX = 0f, endX = 68f)

    private val c = TakeoverLayout.C
    private val centre = Offset(c, c)
    private val blue = Color(Palette.LAMP_BLUE)

    /** An inner halo of a bezel band: clear at [r0], [a] of [colour] at [r1]. */
    private fun halo(colour: Color, r0: Float, r1: Float, a: Float): Brush =
        halos.getOrPut(colour.value.toLong() * 31 + (r0 * 8).toLong() * 7919 + (r1 * 8).toLong() * 104729 + (a * 100).toLong()) {
            Brush.radialGradient(0f to colour.copy(alpha = 0f), r0 / r1 to colour.copy(alpha = 0f), 1f to colour.copy(alpha = a), center = centre, radius = r1)
        }

    /** The galleries' glowDisc on a unit circle at the origin (drawn translated and scaled). */
    private fun glow(colour: Color): Brush = glows.getOrPut(colour.value.toLong()) {
        Brush.radialGradient(0f to colour, 0.5f to colour.copy(alpha = colour.alpha * 0.45f), 1f to colour.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    }

    /** Three comets' tails, a sweep from the head (3 o'clock, turned into place) clockwise. */
    private fun comets(colour: Color, a: Float): Brush = sweeps.getOrPut(colour.value.toLong() * 31 + (a * 100).toLong()) {
        val tail = 64f / 360f
        val stops = ArrayList<Pair<Float, Color>>()
        for (k in 0 until 3) {
            val f = k / 3f
            if (k > 0) stops += (f - 0.0008f) to colour.copy(alpha = 0f)
            stops += f to colour.copy(alpha = a)
            stops += (f + tail * 0.35f) to colour.copy(alpha = a * 0.45f)
            stops += (f + tail) to colour.copy(alpha = 0f)
        }
        stops += 1f to colour.copy(alpha = 0f)
        Brush.sweepGradient(*stops.toTypedArray(), center = centre)
    }

    fun paint(ds: DrawScope, f: TakeoverLayout.Frame, m: TakeoverMeter) = with(ds) {
        if (f.alpha < 0.999f) {
            layerPaint.alpha = f.alpha
            drawContext.canvas.saveLayer(Rect(0f, 0f, 2 * c, 2 * c), layerPaint)
            for (op in f.dim) op(op, f, m)
            drawContext.canvas.restore()
        } else for (op in f.dim) op(op, f, m)
        for (op in f.top) op(op, f, m)
    }

    private fun DrawScope.op(op: TakeoverLayout.Op, f: TakeoverLayout.Frame, m: TakeoverMeter) {
        val amb = f.ambient
        when (op) {
            is TakeoverLayout.Seg -> seg(op, amb)
            is TakeoverLayout.Ring -> {
                val red = Color(Palette.LAMP_RED)
                arc(236f, -180f, 180f, red, op.alpha, 8f)
                arcBrush(227f, -180f, 180f, halo(red, 222f, 232f, 0.5f), op.alpha, 10f)
            }
            is TakeoverLayout.Comets -> {
                arc(235f, -180f, 180f, blue, 0.2f * op.k, 10f)
                for ((w, r, a, col) in listOf(Quad(10f, 235f, 0.55f, blue), Quad(4f, 238f, 1f, COMET))) {
                    val brush = comets(col, a)
                    rotate(op.headDeg - 90f, centre) { drawCircle(brush, r, centre, alpha = op.k, style = Stroke(w)) }
                }
                for (k in 0 until 3) glowAt(FaceKit.point(236f, op.headDeg + k * 120f, 1f), 9f, COMET_HEAD, 0.9f * op.k)
            }
            is TakeoverLayout.Flash -> {
                if (amb) arc(237f, op.deg - 14f, op.deg + 14f, Color.White, 0.8f * op.k, 3f)
                else {
                    arc(235f, op.deg - 14f, op.deg + 14f, Color.White, op.k, 10f)
                    arcBrush(227.5f, op.deg - 14f, op.deg + 14f, halo(Color.White, 225f, 230f, 0.4f), op.k, 5f)
                }
            }
            is TakeoverLayout.Power -> power(op.fraction, f)
            is TakeoverLayout.Speed -> speed(op, f, m)
            is TakeoverLayout.Glow -> glowAt(Offset(op.x, op.y), op.r, op.colour, op.alpha)
            is TakeoverLayout.Circle -> {
                if (op.alpha <= 0.002f) return
                val at = Offset(op.x, op.y)
                if (op.fill > 0f) drawCircle(op.colour, op.r, at, alpha = (op.alpha * op.fill).coerceIn(0f, 1f))
                drawCircle(op.colour, op.r, at, alpha = op.alpha.coerceIn(0f, 1f), style = Stroke(op.width))
            }
            is TakeoverLayout.Tri -> {
                if (op.alpha <= 0.002f) return
                val s = op.size
                tri.reset()
                tri.moveTo(op.x - s / 2, op.y + s * 0.43f); tri.lineTo(op.x, op.y - s * 0.43f); tri.lineTo(op.x + s / 2, op.y + s * 0.43f); tri.close()
                if (amb) drawPath(tri, op.colour, op.alpha.coerceIn(0f, 1f), Stroke(1.5f, join = StrokeJoin.Round))
                else drawPath(tri, op.colour, op.alpha.coerceIn(0f, 1f))
            }
            is TakeoverLayout.Icon -> icon(op)
            is TakeoverLayout.Txt -> text(op, m)
            is TakeoverLayout.Parts -> parts(op, m)
            is TakeoverLayout.Battery -> battery(op, amb, m)
        }
    }

    private data class Quad(val w: Float, val r: Float, val a: Float, val col: Color)

    private fun DrawScope.arc(r: Float, from: Float, to: Float, colour: Color, alpha: Float, w: Float, cap: StrokeCap = StrokeCap.Butt) {
        if (to - from < 0.05f || alpha <= 0.002f) return
        drawArc(colour, from - 90f, to - from, false, Offset(c - r, c - r), Size(2 * r, 2 * r), alpha.coerceIn(0f, 1f), Stroke(w, cap = cap))
    }

    private fun DrawScope.arcBrush(r: Float, from: Float, to: Float, brush: Brush, alpha: Float, w: Float) {
        if (to - from < 0.05f || alpha <= 0.002f) return
        drawArc(brush, from - 90f, to - from, false, Offset(c - r, c - r), Size(2 * r, 2 * r), alpha.coerceIn(0f, 1f), Stroke(w))
    }

    private fun DrawScope.radial(r1: Float, r2: Float, deg: Float, colour: Color, w: Float, alpha: Float = 1f) {
        drawLine(colour, FaceKit.point(r1, deg, 1f), FaceKit.point(r2, deg, 1f), w, alpha = alpha.coerceIn(0f, 1f))
    }

    private fun DrawScope.glowAt(at: Offset, r: Float, colour: Color, alpha: Float) {
        if (alpha <= 0.002f || r <= 0f) return
        val brush = glow(colour)
        withTransform({ translate(at.x, at.y); scale(r, r, Offset.Zero) }) { drawCircle(brush, 1f, Offset.Zero, alpha = alpha.coerceIn(0f, 1f)) }
    }

    /** Dashes r 230–240 on the 5° lattice around the lamp (3,4° dash, 1,6° gap), the outer ones 70 %; a 5 px halo inwards. */
    private fun DrawScope.seg(s: TakeoverLayout.Seg, amb: Boolean) {
        if (s.alpha <= 0.002f || s.hi - s.lo < 0.2f) return
        for (j in -4..4) {
            val mid = s.mid + 5f * j
            val d0 = max(s.lo, mid - 1.7f)
            val d1 = min(s.hi, mid + 1.7f)
            if (d1 - d0 < 0.15f) continue
            val a = s.alpha * (if (abs(j) >= 2) 0.7f else 1f)
            if (amb) { arc(237f, d0, d1, s.colour, 0.8f * a, 3f); continue }
            arc(235f, d0, d1, s.colour, a, 10f)
            arcBrush(227.5f, d0, d1, halo(s.colour, 225f, 230f, 0.45f), a, 5f)
        }
    }

    private fun DrawScope.power(fr0: Float, f: TakeoverLayout.Frame) {
        val amb = f.ambient
        val fr = fr0.coerceIn(-1f, 1f)
        val ang = fr * Arc.MAX_DEG
        if (amb) arc(TakeoverLayout.R_PWR, -135f, 135f, TakeoverLayout.AMB_LINE, 1f, 2f, StrokeCap.Round)
        else arc(TakeoverLayout.R_PWR, -135f, 135f, Color(Palette.POWER_TRACK), 1f, TakeoverLayout.W_PWR, StrokeCap.Round)
        if (abs(ang) > 0.4f) {
            val col = if (f.lowBit) TakeoverLayout.AMB_INK else Color(if (fr > 0) Palette.DRAW else Palette.FLOW)
            val w = if (amb) 6f else TakeoverLayout.W_PWR
            arc(TakeoverLayout.R_PWR, min(0f, ang), max(0f, ang), col, 1f, w)
            drawCircle(col, w / 2, FaceKit.point(TakeoverLayout.R_PWR, ang, 1f))
        }
        radial(TakeoverLayout.R_PWR - 12f, TakeoverLayout.R_PWR + 12f, 0f, if (amb) TakeoverLayout.AMB_DIM else Color(Palette.INK), 3f)
    }

    private fun DrawScope.speed(s: TakeoverLayout.Speed, f: TakeoverLayout.Frame, m: TakeoverMeter) {
        val amb = f.ambient
        val r = TakeoverLayout.R_SPD
        arc(r, -135f, 135f, if (amb) TakeoverLayout.AMB_LINE else Color(Palette.SPEED_TRACK), 1f, if (amb) 1.5f else 3f, StrokeCap.Round)
        if (!amb) {
            s.zoneDeg?.let { z ->
                if (s.zoneK > 0f) arc(r, z, 135f, blue, 0.35f + 0.55f * s.zoneK, 3f + 2f * s.zoneK) else arc(r, z, 135f, ZONE, 1f, 3f)
            }
            for (d in s.ticks) radial(r - 6f, r + 6f, d, Color(Palette.SPEED_TICK), 2f)
        }
        s.limitDeg?.let { ld ->
            val tf = s.tickFlash
            if (tf > 0f && !amb) glowAt(FaceKit.point(r, ld, 1f), 26f, Color(Palette.LIMIT), 0.5f * tf)
            val hk = max(if (s.hot) 1f else 0f, tf)
            val col = if (amb) (if (s.hot && !f.lowBit) Color(Palette.LIMIT) else TakeoverLayout.AMB_DIM) else if (hk > 0f) LIMIT_HOT else Color(Palette.LIMIT)
            radial(r - 9f - 3f * hk, r + 9f + 3f * hk, ld, col, 3f + 1.5f * hk)
        }
        val fr = s.fraction ?: return
        val va = Dial.START_DEG + Dial.SPAN_DEG * fr
        if (va > -134.6f) arc(r, -135f, va, if (amb) TakeoverLayout.AMB_DIM else Color(Palette.MUTED), 1f, if (amb) 1.5f else 3f, StrokeCap.Round)
        val dot = FaceKit.point(r, va, 1f) + s.tremble
        val red = Color(Palette.LAMP_RED)
        if (s.over) {
            glowAt(dot, 24f, red, 0.4f)
            for (j in 0 until 2) {
                val ph = (s.ringPhase + j * 0.5f) % 1f
                drawCircle(red, 13f + 16f * ph, dot, alpha = 0.65f * (1 - ph), style = Stroke(2.5f))
            }
        }
        val label = s.label
        val lay = label?.let { m.layout(it, 13f, 700, 80f) }
        val pw = if (s.pill && lay != null) max(22f, lay.size.width + 12f) else 22f
        val tl = Offset(dot.x - pw / 2, dot.y - 11f)
        if (amb) {
            drawRoundRect(Color.Black, tl, Size(pw, 22f), CornerRadius(11f))
            drawRoundRect(TakeoverLayout.AMB_INK, tl, Size(pw, 22f), CornerRadius(11f), style = Stroke(1.6f))
        } else drawRoundRect(if (s.over) red else Color(Palette.INK), tl, Size(pw, 22f), CornerRadius(11f))
        if (lay != null) drawText(lay, if (amb) TakeoverLayout.AMB_INK else if (s.over) Color.White else Color.Black,
            Offset(dot.x - lay.size.width / 2f, dot.y + 4.6f - lay.firstBaseline))
    }

    private fun DrawScope.icon(op: TakeoverLayout.Icon) {
        if (op.alpha <= 0.002f || op.px <= 0.5f) return
        val k = op.px / Glyphs.BOX
        val a = op.alpha.coerceIn(0f, 1f)
        if (op.halo > 0.002f && op.outline <= 0f) halo(op, k)
        withTransform({ translate(op.x - op.px / 2, op.y - op.px / 2); scale(k, k, Offset.Zero) }) {
            op.glyphs.forEachIndexed { i, g ->
                val p = glyphPath(g.d)
                val cap = if (g.roundCap) StrokeCap.Round else StrokeCap.Butt
                val join = if (g.roundJoin) StrokeJoin.Round else StrokeJoin.Miter
                if (op.outline > 0f) {
                    drawPath(p, op.colour, a, Stroke(op.outline / k, cap = cap, join = join))
                    return@forEachIndexed
                }
                val col = if (g.knock) Color.Black else op.colour
                if (op.cut && i == op.glyphs.lastIndex) drawPath(p, Color.Black, a, Stroke(g.width * op.weight + 2.6f, cap = StrokeCap.Round))
                if (g.fill) drawPath(p, col, a, Fill) else drawPath(p, col, a, Stroke(g.width * op.weight, cap = cap, join = join))
            }
        }
    }

    /** A soft halo of ≤ [TakeoverLayout.Icon.blur] px round the strokes (the mock's shadowBlur), not a disc. */
    private fun DrawScope.halo(op: TakeoverLayout.Icon, k: Float) {
        val key = (op.blur * 0.8f / k * 8f).roundToInt().coerceIn(1, 800)
        haloPaint.maskFilter = blurs.getOrPut(key) { BlurMaskFilter(key / 8f, BlurMaskFilter.Blur.NORMAL) }
        haloPaint.color = op.colour.copy(alpha = (op.halo * op.alpha).coerceIn(0f, 1f)).toArgb()
        drawIntoCanvas { cv ->
            val nc = cv.nativeCanvas
            nc.save()
            nc.translate(op.x - op.px / 2, op.y - op.px / 2)
            nc.scale(k, k)
            for (g in op.glyphs) {
                if (g.knock) continue
                haloPaint.style = if (g.fill) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
                haloPaint.strokeWidth = g.width * op.weight
                haloPaint.strokeCap = if (g.roundCap) android.graphics.Paint.Cap.ROUND else android.graphics.Paint.Cap.BUTT
                haloPaint.strokeJoin = if (g.roundJoin) android.graphics.Paint.Join.ROUND else android.graphics.Paint.Join.MITER
                nc.drawPath(glyphPath(g.d).asAndroidPath(), haloPaint)
            }
            nc.restore()
        }
    }

    private fun DrawScope.text(op: TakeoverLayout.Txt, m: TakeoverMeter) {
        if (op.alpha <= 0.002f || op.text.isEmpty()) return
        val l = m.layout(op.text, op.px, op.weight, op.maxW)
        val w = l.size.width.toFloat()
        val left = when (op.anchor) { TakeoverLayout.Anchor.START -> op.x; TakeoverLayout.Anchor.CENTER -> op.x - w / 2; TakeoverLayout.Anchor.END -> op.x - w }
        val at = Offset(left, op.baseline - l.firstBaseline)
        val a = op.alpha.coerceIn(0f, 1f)
        if (op.halo > 0f) drawText(l, Color.Black, at, alpha = a, drawStyle = Stroke(op.halo, join = StrokeJoin.Round))
        drawText(l, op.colour, at, alpha = a, drawStyle = if (op.outline) Stroke(1f) else Fill)
    }

    private fun DrawScope.parts(op: TakeoverLayout.Parts, m: TakeoverMeter) {
        if (op.alpha <= 0.002f) return
        val ls = op.parts.map { m.layout(it.text, op.px, it.weight, 480f) }
        var x = op.x - ls.sumOf { it.size.width } / 2f
        val a = op.alpha.coerceIn(0f, 1f)
        for ((i, l) in ls.withIndex()) {
            drawText(l, op.parts[i].colour, Offset(x, op.baseline - l.firstBaseline), alpha = a)
            x += l.size.width
        }
    }

    private fun DrawScope.battery(b: TakeoverLayout.Battery, amb: Boolean, m: TakeoverMeter) {
        val outline = if (amb) TakeoverLayout.AMB_DIM else Color(Palette.INK2)
        drawRoundRect(outline, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(b.rx), style = Stroke(if (amb) 1.6f else b.line))
        val nw = max(4f, b.w * 0.086f)
        val nh = b.h * 0.42f
        val nubAt = Offset(b.x + b.w + b.line / 2 + 1f, b.y + (b.h - nh) / 2)
        if (amb) drawRoundRect(outline, nubAt, Size(nw, nh), CornerRadius(min(4f, nw / 2)), style = Stroke(1.2f))
        else drawRoundRect(outline, nubAt, Size(nw, nh), CornerRadius(min(4f, nw / 2)))
        val fwMax = b.w - 2 * b.pad
        val fh = b.h - 2 * b.pad
        val pct = b.pct?.takeIf { it.isFinite() }
        if (pct != null) {
            val fw = max(3f, fwMax * (pct.coerceIn(0.0, 100.0) / 100).toFloat())
            val fx = b.x + b.pad
            val fy = b.y + b.pad
            val rr = CornerRadius(min(b.rx * 0.6f, fw / 2))
            if (amb) drawRoundRect(b.tone, Offset(fx, fy), Size(fw, fh), rr, style = Stroke(1.2f))
            else {
                // a solid fill at night: 80 %
                drawRoundRect(b.tone, Offset(fx, fy), Size(fw, fh), rr, alpha = 0.8f)
                b.shimmer?.let { p ->
                    val bx = fx - 40f + (fw + 80f) * p
                    clipRect(fx, fy, fx + fw, fy + fh) {
                        withTransform({ translate(bx - 34f, 0f) }) { drawRect(shimmer, Offset(0f, fy), Size(68f, fh), alpha = 0.8f) }
                    }
                }
            }
        }
        val l = m.layout(b.text, b.px, 700, 420f)
        val at = Offset(b.tx - l.size.width / 2f, b.ty - l.firstBaseline)
        if (amb) drawText(l, TakeoverLayout.AMB_INK, at, drawStyle = Stroke(1f))
        else {
            if (b.halo > 0.05f) drawText(l, Color.Black, at, drawStyle = Stroke(b.halo, join = StrokeJoin.Round))
            drawText(l, Color.White, at, drawStyle = Fill)
        }
    }

    private companion object {
        val LIMIT_HOT = Color(0xFFFF5A6E)
        val ZONE = Color(0xFF1B3550)
        val COMET = Color(0xFFA8D4FF)
        val COMET_HEAD = Color(0xFFCFE7FF)
    }
}
