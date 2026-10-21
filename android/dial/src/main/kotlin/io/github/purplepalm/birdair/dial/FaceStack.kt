package io.github.purplepalm.birdair.dial

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * DialFace.STACK — «Крупные лампы», the default (gallery/stack.html with stack2's motion; the owner: «видно издалека
 * хорошо, это главное», a little more and better motion). Off lamps are not drawn: 12 dots in the power arc's bottom gap.
 * The main lamp (DialLamps.top) is a chip 104 px (red 116) at (118, 272) with its title / valueText / detail right of it
 * up to x 402; the next ones 76 px in the «smile» (156,390 · 240,408 · 324,390), more in «+N» 56 px; the quiet ones
 * 30–36 px icons by the battery; the battery 140×66 → 124×54 → 112×48 as lamps come. A new lamp flies out of its dot
 * along the arc as a bead and blooms on a spring (amber and red land harder); the buzz lands with it (the watch plays
 * it [Stack.LAND_MS] after the message while the flight is seen, [StackStage]) with a ring per motor pulse; a red one
 * breathes 1 Hz in phase with its buzz,
 * text never; a lamp going out drains back into its dot, a red one leaves a green ✓. [StackScene] lays a frame out;
 * this file draws it on the 480-px grid scaled to the screen. Frames run only while something moves
 * ([StackTimeline.wakeAt]); ambient is still, outlines only.
 */

@Composable
internal fun FaceStack(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) = StackFace(state, modifier, nowMs)

/**
 * «Крупные лампы» of [state]. [nowMs]: a fixed time on the state's clock (shots, tests), null: live. [calm]: the reduced
 * variant (no flight, springs, rolls or breathing; the buzz count kept as still rings); null: the system's «Remove
 * animations» (animator duration scale 0).
 */
@Composable
fun StackFace(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null, calm: Boolean? = null) {
    val tl = remember { StackTimeline() }
    tl.feed(state)
    val reduced = calm ?: rememberNoAnimations()
    val stage = LocalStackStage.current
    if (stage != null && nowMs == null) {
        val still = reduced || state.ambient
        SideEffect { stage.show(tl, still) }
        DisposableEffect(stage, tl) { onDispose { stage.hide(tl) } }
    }
    val clock = rememberFaceClock(state, nowMs) { s, now -> tl.wakeAt(s, now, reduced) }
    val springs = nowMs == null && state.smooth && !state.ambient
    val power = rememberSpring(state.power.fraction, springs)
    val speedF = state.speed.fraction()
    val speed = rememberSpring(speedF ?: 0f, springs)
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val base = LocalFaceTextStyle.current
    val density = LocalDensity.current
    val ink = remember(measurer, base, density) { StackInk(measurer, base, density) }
    Canvas(modifier.fillMaxSize()) {
        val sc = StackScene.build(state, tl, clock.value, reduced, ink, power.value, if (speedF == null) null else speed.value)
        ink.draw(this, sc, state)
    }
}

/** The system's «Remove animations» (Wear: Settings → Accessibility): the calmer variant. */
@Composable
private fun rememberNoAnimations(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: RuntimeException) { false }
    }
}

/**
 * «Крупные лампы» as the watch's gauge draws it, so a lamp's buzz can go as its chip lands: the face shows its timeline here
 * while composed under [LocalStackStage] (the gauge page only, not a picker's preview). Main thread.
 */
class StackStage {
    private var tl: StackTimeline? = null
    private var still = true

    /** The face draws [tl] now; [still]: calm or ambient, no flight. */
    fun show(tl: StackTimeline, still: Boolean) {
        this.tl = tl
        this.still = still
    }

    /** The face drawing [tl] left (another face, the gauge page gone). */
    fun hide(tl: StackTimeline) {
        if (this.tl === tl) this.tl = null
    }

    /**
     * How long a buzz that came at [nowMs] with its lamp's change waits: [Stack.LAND_MS] when its chip flies in at once
     * (the face is drawn, not calm or ambient, and no change before it nor the startup holds it back), else 0 — the motor
     * goes now and the face draws its rings as the chip lands.
     */
    fun landingMs(nowMs: Long): Long {
        val t = tl ?: return 0
        if (still) return 0
        val at = t.startsAt(nowMs) ?: return 0
        return if (at <= nowMs) Stack.LAND_MS else 0
    }
}

/** The watch gauge's [StackStage]; null elsewhere (the phone, previews, shots). */
val LocalStackStage = staticCompositionLocalOf<StackStage?> { null }

/**
 * The face's text and paint: layouts measured once per text and size (0,5-px steps, drawn scaled to the exact size)
 * in the watch theme's font, the glows as one unit brush per colour, the paths reused frame to frame.
 */
internal class StackInk(private val m: TextMeasurer, base: TextStyle, private val density: Density) : TextWidth {
    private val base = base.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified)
    private val layouts = object : LinkedHashMap<String, TextLayoutResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextLayoutResult>?) = size > 160
    }
    private val glows = HashMap<Long, Brush>()
    private val tapGlow = Brush.radialGradient(0f to argb(Palette.LIMIT).copy(alpha = 0.8f), 1f to argb(Palette.LIMIT).copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    private val shimmer = Brush.horizontalGradient(0f to Color.White.copy(alpha = 0f), 0.5f to Color.White.copy(alpha = 0.34f), 1f to Color.White.copy(alpha = 0f), startX = 0f, endX = 1f)
    private val string = Path()
    private val pie = Path()
    private val check = Path()
    private val band = Path()
    private val fill = Path()
    private val strokes = HashMap<Long, Stroke>()

    private fun quant(px: Float): Float = max(1f, (px * 2).roundToInt() / 2f)

    /** [text] laid out at [px] rounded to 0,5 px (the caller scales the rest). */
    fun layout(text: String, px: Float, weight: Int, tnum: Boolean): TextLayoutResult {
        val q = quant(px)
        val key = "$weight|$tnum|$q|$text"
        return layouts.getOrPut(key) {
            val sp = with(density) { q.toSp() }
            m.measure(text, base.merge(TextStyle(fontSize = sp, fontWeight = FontWeight(weight), fontFeatureSettings = if (tnum) "tnum" else null)), softWrap = false, maxLines = 1)
        }
    }

    override fun width(text: String, px: Float, weight: Int, tnum: Boolean): Float {
        if (text.isEmpty()) return 0f
        val l = layout(text, px, weight, tnum)
        return (l.getLineRight(0) - l.getLineLeft(0)) * px / quant(px)
    }

    private fun stroke(w: Float, cap: StrokeCap = StrokeCap.Butt): Stroke {
        val key = (w * 100).roundToInt().toLong() * 4 + cap.hashCode().toLong().and(3)
        return strokes.getOrPut(key) { Stroke(w, cap = cap, join = StrokeJoin.Round) }
    }

    private fun glow(argb: Long): Brush = glows.getOrPut(argb) {
        val c = argb(argb)
        Brush.radialGradient(0f to c.copy(alpha = 0.5f), 0.78f to c.copy(alpha = 0.38f), 1f to c.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    }

    private fun DrawScope.disc(brush: Brush, x: Float, y: Float, r: Float, alpha: Float) {
        if (r <= 0f || alpha <= 0.003f) return
        withTransform({ translate(x, y); scale(r, r, Offset.Zero) }) { drawCircle(brush, 1f, Offset.Zero, alpha.coerceIn(0f, 1f)) }
    }

    /** An arc on radius [r] from clock angle [a0] to [a1] (degrees), [w] wide. */
    private fun DrawScope.arc(r: Float, a0: Float, a1: Float, argb: Long, w: Float, cap: StrokeCap, alpha: Float) {
        if (abs(a1 - a0) < 0.05f) return
        drawArc(argb(argb), a0 - 90f, a1 - a0, false, Offset(StackGeo.C - r, StackGeo.C - r), Size(2 * r, 2 * r), alpha.coerceIn(0f, 1f), stroke(w, cap))
    }

    private fun DrawScope.radial(r0: Float, r1: Float, deg: Float, argb: Long, w: Float, alpha: Float = 1f, cap: StrokeCap = StrokeCap.Butt) =
        drawLine(argb(argb), Offset(StackGeo.px(r0, deg), StackGeo.py(r0, deg)), Offset(StackGeo.px(r1, deg), StackGeo.py(r1, deg)), w, cap, alpha = alpha.coerceIn(0f, 1f))

    /** A text with its baseline at [baseline], anchored at [x]: measured at 0,5-px steps, scaled to [px]. */
    private fun DrawScope.text(
        str: String, x: Float, baseline: Float, px: Float, weight: Int, tnum: Boolean, argb: Long, alpha: Float,
        anchor: Anchor = Anchor.CENTER, halo: Float = 0f,
    ) {
        if (str.isEmpty() || alpha <= 0.003f || px < 1f) return
        val l = layout(str, px, weight, tnum)
        val k = px / quant(px)
        val w = (l.getLineRight(0) - l.getLineLeft(0)) * k
        val left = when (anchor) { Anchor.START -> x; Anchor.CENTER -> x - w / 2; Anchor.END -> x - w } - l.getLineLeft(0) * k
        val a = alpha.coerceIn(0f, 1f)
        withTransform({ translate(left, baseline - l.firstBaseline * k); scale(k, k, Offset.Zero) }) {
            if (halo > 0f) drawText(l, Color.Black, Offset.Zero, a, drawStyle = Stroke(halo / k, join = StrokeJoin.Round))
            drawText(l, argb(argb), Offset.Zero, a, drawStyle = Fill)
        }
    }

    /**
     * [el] with its changed glyphs rolling like an odometer (each slides out, its new one in from the other side, up when
     * the value rose, 300 ms FOSI, right to left 40 ms apart, clipped to the line); a different length rolls the whole.
     */
    private fun DrawScope.textEl(el: TextEl, a0: Float, xOverride: Float? = null) {
        val x = xOverride ?: el.x
        val roll = el.roll
        if (roll == null || roll.from == el.text) { text(el.text, x, el.baseline, el.px, el.weight, el.tnum, el.argb, el.alpha * a0, el.anchor, el.halo); return }
        val to = el.text
        val from = roll.from
        val h = el.px * 0.95f
        val dir = roll.dir
        clipRect(-10f, el.baseline - el.px * 0.8f, 500f, el.baseline + el.px * 0.1f) {
            if (from.length != to.length) {
                val p = Ez.fosi(roll.e.toFloat() / Stack.ROLL)
                text(from, x, el.baseline - dir * h * p, el.px, el.weight, el.tnum, el.argb, el.alpha * a0, el.anchor, el.halo)
                text(to, x, el.baseline + dir * h * (1 - p), el.px, el.weight, el.tnum, el.argb, el.alpha * a0, el.anchor, el.halo)
                return@clipRect
            }
            val changed = (to.length - 1 downTo 0).filter { from[it] != to[it] }
            val w = width(to, el.px, el.weight, el.tnum)
            val x0 = when (el.anchor) { Anchor.START -> x; Anchor.CENTER -> x - w / 2; Anchor.END -> x - w }
            for (i in to.indices) {
                val gx = x0 + width(to.substring(0, i), el.px, el.weight, el.tnum)
                val r = changed.indexOf(i)
                val p = if (r < 0) 1f else Ez.fosi((roll.e - r * Stack.ROLL_STAGGER).toFloat() / Stack.ROLL)
                if (p >= 1f) { text(to[i].toString(), gx, el.baseline, el.px, el.weight, el.tnum, el.argb, el.alpha * a0, Anchor.START, el.halo); continue }
                text(from[i].toString(), gx, el.baseline - dir * h * p, el.px, el.weight, el.tnum, el.argb, el.alpha * a0, Anchor.START, el.halo)
                text(to[i].toString(), gx, el.baseline + dir * h * (1 - p), el.px, el.weight, el.tnum, el.argb, el.alpha * a0, Anchor.START, el.halo)
            }
        }
    }

    /** The whole frame, on the 480-px grid scaled to the canvas (and moved by the burn-in shift). */
    fun draw(ds: DrawScope, sc: StackScene, s: DialState) = with(ds) {
        val u = size.minDimension / FaceKit.GRID
        val ox = (size.width - FaceKit.GRID * u) / 2 + s.burnInDp.first.dp.toPx()
        val oy = (size.height - FaceKit.GRID * u) / 2 + s.burnInDp.second.dp.toPx()
        withTransform({ translate(ox, oy); scale(u, u, Offset.Zero) }) { frame(sc) }
    }

    private fun DrawScope.frame(sc: StackScene) {
        val amb = sc.ambient
        val lowBit = sc.lowBit
        val a = sc.face * sc.values
        // the startup is the watch's own: its sweep, wave and dots at full strength even on old data
        val aTrack = if (sc.sweep) sc.face else a
        // the power arc: the ±135° track, the value from 12 o'clock with a round tip on it, the zero tick
        val rp = StackGeo.RP
        val pa = sc.powerDeg
        if (amb) {
            arc(rp, -135f, 135f, Palette.AMB_LINE, 2f, StrokeCap.Round, a)
            if (abs(pa) > 0.5f) arc(rp, min(0f, pa), max(0f, pa), if (lowBit) Palette.AMB_INK else if (pa > 0) Palette.DRAW else Palette.FLOW, 4f, StrokeCap.Round, a)
        } else {
            arc(rp, -135f, 135f, Palette.POWER_TRACK, 20f, StrokeCap.Round, aTrack)
            if (abs(pa) > 0.5f) {
                val c = if (pa > 0) Palette.DRAW else Palette.FLOW
                arc(rp, min(0f, pa), max(0f, pa), c, 20f, StrokeCap.Butt, aTrack)
                drawCircle(argb(c), 10f, Offset(StackGeo.px(rp, pa), StackGeo.py(rp, pa)), aTrack)
            }
        }
        drawLine(argb(if (amb) Palette.AMB_DIM else Palette.INK), Offset(240f, 6f), Offset(240f, 34f), 3f, alpha = aTrack)
        track(sc.track, amb, lowBit, aTrack)
        val aDots = if (sc.dotsFull) sc.face else a
        for (d in sc.dots) {
            if (d.outline) drawCircle(argb(d.argb), d.r, Offset(d.x, d.y), d.alpha * a, style = stroke(1.4f))
            else drawCircle(argb(d.argb), d.r, Offset(d.x, d.y), (d.alpha * aDots).coerceIn(0f, 1f))
        }
        val ca = a * sc.centre
        battery(sc.battery, amb, ca)
        for (l in sc.lines) textEl(l, ca)
        val aLamps = if (sc.wave) sc.face else a
        for (list in listOf(sc.stand, sc.sec, sc.hero, sc.fx, sc.text)) for (el in list) element(el, amb, lowBit, aLamps)
        for (l in sc.status) textEl(l, sc.face)
    }

    private fun DrawScope.element(el: El, amb: Boolean, lowBit: Boolean, a: Float) {
        when (el) {
            is ChipEl -> {
                val p = el.pie
                if (p != null && p < 0.999f) {
                    pie.reset()
                    pie.moveTo(el.x, el.y)
                    pie.lineTo(el.x, el.y - el.pieR)
                    pie.arcTo(Rect(Offset(el.x, el.y), el.pieR), -90f, 360f * p, false)
                    pie.close()
                    clipPath(pie) { chip(el, amb, lowBit, a) }
                } else chip(el, amb, lowBit, a)
            }
            is DiscEl -> drawCircle(argb(el.argb), el.r, Offset(el.x, el.y), (el.alpha * a).coerceIn(0f, 1f))
            is RingEl -> if (el.w > 0f && el.r > 0f) drawCircle(argb(el.argb), el.r, Offset(el.x, el.y), (el.alpha * a).coerceIn(0f, 1f), style = stroke(el.w))
            is RiseEl -> drawRising(Offset(el.x, el.y), el.d, if (amb && lowBit) argb(Palette.AMB_INK) else FaceKit.lamp(el.level), amb, el.bob, a)
            is CheckEl -> checkMark(el, a)
            is PlusEl -> plus(el, amb, a)
            is TextEl -> textEl(el, a)
        }
    }

    /** A lamp chip (stack's chip()): a bead in flight, a standing icon, the main one solid (amber / red), the others rings. */
    private fun DrawScope.chip(c: ChipEl, amb: Boolean, lowBit: Boolean, a0: Float) {
        val alpha = (c.alpha * a0).coerceIn(0f, 1f)
        if (c.d < 1f || alpha < 0.01f) return
        val lv = c.lamp.level
        val col = Lamps.argb(lv)
        val at = Offset(c.x, c.y)
        val icon = FaceKit.icon(c.lamp)
        if (c.d <= 20f && !amb) {
            disc(glow(col), c.x, c.y, c.d / 2 + 7, alpha)
            drawCircle(argb(col), c.d / 2, at, alpha)
            return
        }
        if (c.kind == Kind.STAND) {
            drawGlyph(icon, at, c.d, argb(if (amb && lowBit) Palette.AMB_INK else col), amb, 1.1f, alpha * if (c.lamp.id == TellTales.ECO) 0.95f else 0.55f)
            return
        }
        if (amb) {
            val ink = argb(if (lowBit) Palette.AMB_INK else col)
            drawCircle(ink, max(0f, c.d / 2 - 1.5f), at, alpha, style = stroke(if (c.kind == Kind.HERO) 2.5f else 2f))
            if (c.d > 20f) drawGlyph(icon, at, c.d * 0.58f, ink, ambient = true, alpha = alpha)
            return
        }
        val solid = lv == LampLevel.RED || (c.kind == Kind.HERO && lv == LampLevel.AMBER)
        val b = c.bright
        disc(glow(col), c.x, c.y, c.d / 2 + if (c.kind == Kind.HERO) 12f else 8f, alpha * if (solid) 0.35f + 0.65f * b else 0.7f)
        if (solid) {
            drawCircle(argb(col), c.d / 2, at, (alpha * FaceKit.NIGHT_FILL * b).coerceIn(0f, 1f))
            drawGlyph(icon, at, c.d * 0.6f, argb(FaceKit.ON_SOLID), weight = 1.25f, alpha = alpha)
        } else {
            drawCircle(argb(col), max(0f, c.d / 2 - 2), at, alpha * 0.18f)
            drawCircle(argb(col), max(0f, c.d / 2 - 2), at, alpha, style = stroke(if (c.kind == Kind.HERO) 4f else 3f))
            drawGlyph(icon, at, c.d * 0.6f, argb(col), weight = 1.2f, alpha = alpha)
        }
    }

    /** The green ✓ (M5 12.5 l4.5 4.5 L19 7.5 in a 24 box), drawn on to [CheckEl.trim] over a dark under-stroke. */
    private fun DrawScope.checkMark(c: CheckEl, a0: Float) {
        val k = c.size / 24f
        val x0 = c.x - c.size / 2
        val y0 = c.y - c.size / 2
        val p0 = Offset(x0 + 5 * k, y0 + 12.5f * k)
        val p1 = Offset(x0 + 9.5f * k, y0 + 17 * k)
        val p2 = Offset(x0 + 19 * k, y0 + 7.5f * k)
        val l1 = (p1 - p0).getDistance()
        val l2 = (p2 - p1).getDistance()
        val len = (l1 + l2) * c.trim.coerceIn(0f, 1f)
        if (len <= 0.01f) return
        check.reset()
        check.moveTo(p0.x, p0.y)
        if (len <= l1) check.lineTo(p0.x + (p1.x - p0.x) * len / l1, p0.y + (p1.y - p0.y) * len / l1)
        else {
            check.lineTo(p1.x, p1.y)
            val q = (len - l1) / l2
            check.lineTo(p1.x + (p2.x - p1.x) * q, p1.y + (p2.y - p1.y) * q)
        }
        val a = (c.alpha * a0).coerceIn(0f, 1f)
        drawPath(check, Color.Black, a * 0.6f, Stroke(12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(check, argb(Palette.LAMP_GREEN), a, Stroke(7.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.plus(p: PlusEl, amb: Boolean, a0: Float) {
        val a = (p.alpha * a0).coerceIn(0f, 1f)
        val col = argb(Lamps.argb(p.level))
        if (amb) drawCircle(col, max(0f, p.d / 2 - 1), Offset(p.x, p.y), a, style = stroke(2f))
        else {
            drawCircle(argb(FaceKit.CHIP), p.d / 2, Offset(p.x, p.y), a)
            drawCircle(col, p.d / 2, Offset(p.x, p.y), a, style = stroke(3f))
        }
        val sc = p.d / StackGeo.PLUS_D
        textEl(TextEl(p.text, p.x - 1, p.y + 10 * sc, 28 * sc, 700, if (amb) Palette.AMB_INK else Palette.INK, 1f, tnum = true, roll = p.roll), a)
    }

    /** The speed track: ticks, the limit (thicker at the tap's point, flashing with the tap), the rumble's point, the dot. */
    private fun DrawScope.track(tr: TrackEl, amb: Boolean, lowBit: Boolean, a: Float) {
        val r = StackGeo.RS
        arc(r, -135f, 135f, if (amb) Palette.AMB_LINE else Palette.SPEED_TRACK, if (amb) 1.6f else 3f, StrokeCap.Round, a)
        for (d in tr.ticks) radial(r - 6, r + 6, d, Palette.SPEED_TICK, 2f, a)
        tr.limitDeg?.let { d ->
            val g = Offset(StackGeo.px(r, d), StackGeo.py(r, d))
            if (tr.atLimiter) disc(tapGlow, g.x, g.y, 15f, 0.55f * a)
            tr.tapGlow?.let { q -> disc(tapGlow, g.x, g.y, 20 + 10 * Ez.losi(q), (1 - Ez.fosi(q)) * a) }
            radial(r - 9, r + 9, d, if (amb && lowBit) Palette.AMB_DIM else Palette.LIMIT, tr.limitW, a, StrokeCap.Round)
        }
        tr.rumbleDeg?.let { radial(r - 6, r + 6, it, Palette.LAMP_BLUE, 2f, 0.75f * a) }
        tr.passedDeg?.let { arc(r, -135f, it, if (amb) Palette.AMB_DIM else Palette.MUTED, if (amb) 1.6f else 3f, StrokeCap.Round, a) }
        val env = tr.stringEnv
        val a0 = tr.rumbleDeg
        val va = tr.passedDeg
        if (env != null && a0 != null && va != null) {
            // the rumble: a blue string from its point to the dot trembles with the motor
            val len = r * Math.toRadians((va - a0).toDouble()).toFloat()
            if (len > 1f) {
                string.reset()
                var x = 0f
                val ph = tr.stringT / 1000f * 2 * PI.toFloat() * 7
                while (x <= len) {
                    val en = minOf(1f, x / 5f, (len - x) / 5f)
                    val rr = r + 4.5f * env * en * sin(2 * PI.toFloat() * x / 9 - ph)
                    val d = a0 + Math.toDegrees((x / r).toDouble()).toFloat()
                    if (x == 0f) string.moveTo(StackGeo.px(rr, d), StackGeo.py(rr, d)) else string.lineTo(StackGeo.px(rr, d), StackGeo.py(rr, d))
                    x += 1.5f
                }
                drawPath(string, argb(Palette.LAMP_BLUE), a, stroke(2.6f))
            }
        }
        val label = tr.label ?: return
        for (w in tr.waves) element(w, amb, lowBit, a)
        tr.calmRing?.let { element(it, amb, lowBit, a) }
        if (tr.glow) disc(glow(Palette.LAMP_BLUE), tr.dotX, tr.dotY, 16f, a)
        tr.tapRing?.let { element(it, amb, lowBit, a) }
        val at = Offset(tr.dotX, tr.dotY)
        if (amb) {
            drawCircle(Color.Black, 9f, at, a)
            drawCircle(argb(Palette.AMB_INK), 9f, at, a, style = stroke(1.6f))
            text(label, tr.dotX, tr.dotY + 3.6f, 10f, 700, true, Palette.AMB_INK, a)
        } else {
            drawCircle(argb(if (tr.blue) Palette.LAMP_BLUE else Palette.INK), tr.dotR, at, a)
            text(label, tr.dotX, tr.dotY + 3.6f, 10f, 700, true, if (tr.blue) 0xFFFFFFFF else 0xFF000000, a)
        }
    }

    /** The battery (stack's): body and nub, the fill in its tone, the charging light, the % over it with a dark halo. */
    private fun DrawScope.battery(be: BatteryEl, amb: Boolean, a0: Float) {
        val b = be.b
        val a = (be.alpha * a0).coerceIn(0f, 1f)
        val inset = b.h * 0.121f
        val fx = b.x + inset
        val fy = b.y + inset
        val fh = b.h - 2 * inset
        val fwMax = b.w - 2 * inset
        val pct = be.pct?.takeIf { it.isFinite() }
        val fw = if (pct == null) 0f else max(b.h * 0.09f, fwMax * (pct / 100).coerceIn(0.0, 1.0).toFloat())
        val rx = b.h * 0.18f
        val frx = min(rx * 0.55f, fw / 2)
        val nx = b.x + b.w + 2
        val nh = b.h * 0.42f
        val ny = b.y + (b.h - nh) / 2
        val nw = b.h * 0.18f
        if (amb) {
            drawRoundRect(argb(Palette.AMB_DIM), Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(rx), stroke(2f), a)
            drawRoundRect(argb(Palette.AMB_DIM), Offset(nx, ny), Size(nw, nh), CornerRadius(nw / 3), stroke(1.6f), a)
            if (fw > 0) drawRoundRect(argb(be.tone), Offset(fx, fy), Size(fw, fh), CornerRadius(frx), stroke(1.6f), a)
        } else {
            drawRoundRect(argb(Palette.INK2), Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(rx), stroke(b.line), a)
            drawRoundRect(argb(Palette.INK2), Offset(nx, ny), Size(nw, nh), CornerRadius(nw / 3), alpha = a)
            if (fw > 0) {
                drawRoundRect(argb(be.tone), Offset(fx, fy), Size(fw, fh), CornerRadius(frx), alpha = 0.9f * a)
                val q = be.charge
                if (q != null) {
                    // charging: a soft light band runs along the fill once every 4 s
                    val bw = min(34f, fw * 0.6f)
                    val bx = fx - bw + (fw + bw) * Ez.fosi(q)
                    val sk = fh * 0.36f
                    fill.reset()
                    fill.addRoundRect(RoundRect(fx, fy, fx + fw, fy + fh, CornerRadius(frx)))
                    band.reset()
                    val span = sk + bw
                    band.moveTo(0f, 1f); band.lineTo(sk / span, 0f); band.lineTo(1f, 0f); band.lineTo(bw / span, 1f); band.close()
                    clipPath(fill) {
                        withTransform({ translate(bx, fy); scale(span, fh, Offset.Zero) }) { drawPath(band, shimmer, a) }
                    }
                }
            }
        }
        var size = b.font
        val maxW = fwMax + 6
        val w = width(be.text, size, 700, true)
        if (w > maxW) size *= maxW / w
        val tx = b.x + b.w / 2
        val ty = b.y + b.h / 2 + size * 0.36f
        if (amb) text(be.text, tx, ty, size, 400, true, Palette.AMB_INK, a, halo = size / 7)
        else textEl(TextEl(be.text, tx, ty, size, 700, 0xFFFFFFFF, 1f, Anchor.CENTER, true, size / 8, be.roll), a)
    }
}
