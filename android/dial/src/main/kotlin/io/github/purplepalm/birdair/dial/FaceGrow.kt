package io.github.purplepalm.birdair.dial

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * DialFace.GROW — «Растущие лампы» (gallery/grow.html): the 12 lamps live on their places as dim 8 px dots (r 175); a lit
 * one grows on its place into a big disc touching r 183 from inside — red 126 px, amber 108, yellow / blue / green 104
 * (the second ×0,8, the third ×0,68; the rest mini badges 42 px, bare icons 30, pips 14) with a capsule tablet (as the
 * mock: Lamp.valueText where the number is the news — °C, V, speed, A — a word for traction, consumption, over-voltage,
 * the link and the BMS: GrowLayout.of); red and amber solid (amber at 80 %, red at the night cap, pulsing 1 Hz with
 * the red buzz: the glow 55–100 %, the fill no lower than FaceKit.RED_FILL_MIN, ±6 %, a ripple), yellow / blue / green a
 * dark disc with a ring in their colour, so an info lamp
 * is never brighter than an alarm; quiet ones (service, weak, ECO) 28 px icons. The pop: a spring with one overshoot
 * and one ring. The centre: the battery, range, «V · °C · MODE» as one column, the top lamp's line and the power, kept
 * clear of the discs (FaceGrowLayout.kt); the motion in closed form (FaceGrowMotion.kt), so a shot at any ms is the live
 * frame. Ambient: outlines, no glow, no motion.
 */

/** The state's [DialState.smooth]: the power arc and the speed dot ease ([rememberSpring]); [nowMs]: a fixed time (shots). */
@Composable
internal fun FaceGrow(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val painter = remember(measurer) { GrowPainter(measurer) }
    val clock = rememberFaceClock(state, nowMs, GrowMotion::wakeAt)
    val eased = state.smooth && !state.ambient && nowMs == null
    val power = rememberSpring(state.power.fraction, eased)
    val speed = rememberSpring(state.speed.fraction() ?: 0f, eased)
    val (dx, dy) = state.burnInDp
    Canvas(modifier.fillMaxSize().offset(dx.dp, dy.dp)) {
        painter.draw(this, state, clock.value, if (state.speed.value == null) null else speed.value, power.value)
    }
}

/** Draws [GrowScene]'s frames: text measured once per text and size, glyph paths parsed once, the glows' brushes kept. */
private class GrowPainter(private val tm: TextMeasurer) {
    private var u = 1f
    private var scope: DrawScope? = null
    private val widths = HashMap<String, Double>()
    private val brushes = HashMap<Long, Brush>()
    private val layer = Paint()
    val scene = GrowScene { text, px, weight ->
        widths.getOrPut("$weight|$px|$text") {
            val st = scope!!.font(px.toFloat() * u, weight)
            tm.measure(text, st, softWrap = false, maxLines = 1).size.width / u.toDouble()
        }
    }

    fun draw(ds: DrawScope, s: DialState, now: Long, speedF: Float?, powerF: Float) = with(ds) {
        val side = size.minDimension
        val nu = side / FaceKit.GRID
        if (nu != u) { u = nu; widths.clear() }
        scope = this
        val f = scene.frame(s, now, speedF, powerF)
        translate((size.width - side) / 2, (size.height - side) / 2) {
            // the values and lamps dim with the data's health (the bulb check is the watch's own: full); the status line does not
            val alpha = if (f.bulb) 1f else s.health.alpha
            if (alpha < 0.999f) {
                layer.alpha = alpha
                drawIntoCanvas { it.saveLayer(Rect(0f, 0f, side, side), layer) }
            }
            powerArc(f.power, s.ambient, s.lowBit)
            speedTrack(s, f, now)
            rows(s, f)
            ring(f, s.ambient, s.lowBit)
            badges(f, s.ambient, s.lowBit)
            speedDot(s, f, now)
            if (alpha < 0.999f) drawIntoCanvas { it.restore() }
            status(s, f)
        }
    }

    // ---------------- helpers (mock px → canvas px: × u)

    private fun DrawScope.font(px: Float, weight: Int): TextStyle = with(FaceKit) { style(px, FontWeight(weight)) }
    private fun pt(r: Float, deg: Float): Offset = FaceKit.point(r, deg, u)
    private fun at(x: Float, y: Float) = Offset(x * u, y * u)

    /** [text] at [px] × [s], centred at [cx] within [lx]…[rx] (mock px), smaller (to 0,6) then ellipsized to fit; its baseline at [y]. */
    private fun DrawScope.fitText(
        text: String, px: Float, weight: Int, s: Float, cx: Float, y: Float, lx: Float, rx: Float, colour: Color, alpha: Float = 1f, halo: Float = 0f,
    ) {
        if (text.isEmpty() || alpha <= 0.01f) return
        val room = max(rx - lx, 1f) * u
        var size = px * s * u
        var l = tm.measure(text, font(size, weight), softWrap = false, maxLines = 1)
        if (l.size.width > room) {
            size = max(size * room / l.size.width * 0.98f, px * 0.6f * u)
            l = tm.measure(text, font(size, weight), overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1, constraints = Constraints(maxWidth = room.toInt()))
        }
        val w = l.size.width.toFloat()
        val x = (cx * u).coerceIn(lx * u + w / 2, max(lx * u + w / 2, rx * u - w / 2))
        val tl = Offset(x - w / 2, y * u - l.firstBaseline)
        if (halo > 0f) drawText(l, Color.Black, tl, alpha = alpha, drawStyle = Stroke(halo, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawText(l, colour, tl, alpha = alpha)
    }

    /** A soft halo [inner] → [outer] px (mock) around [c] in [colour] at [alpha]: one brush per colour and ratio, scaled. */
    private fun DrawScope.halo(c: Offset, inner: Float, outer: Float, colour: Color, alpha: Float) {
        if (outer <= 0f || alpha <= 0.005f) return
        val k = (inner / outer).coerceIn(0f, 0.98f)
        val key = (colour.value.toLong() xor (Math.round(k * 64).toLong() shl 1))
        val brush = brushes.getOrPut(key) {
            Brush.radialGradient(0f to colour, k to colour, 1f to colour.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
        }
        withTransform({ translate(c.x, c.y); scale(outer * u, outer * u, Offset.Zero) }) { drawCircle(brush, 1f, Offset.Zero, alpha = alpha) }
    }

    private fun lampColour(level: LampLevel, amb: Boolean, lowBit: Boolean): Color =
        if (amb && lowBit) argb(Palette.AMB_INK) else if (level == LampLevel.OFF) argb(Palette.LAMP_OFF) else FaceKit.lamp(level)

    private fun textColour(level: LampLevel, amb: Boolean, lowBit: Boolean): Color = if (amb && lowBit) argb(Palette.AMB_INK) else FaceKit.title(level)

    // ---------------- the power arc: r 222, 20 px, zero at 12 o'clock, the round tip landing on the value

    private fun DrawScope.powerArc(f: Float, amb: Boolean, lowBit: Boolean) {
        val r = 222f * u
        val tl = Offset(FaceKit.C * u - r, FaceKit.C * u - r)
        val sz = Size(2 * r, 2 * r)
        drawArc(argb(if (amb) AMB_TRACK else Palette.POWER_TRACK), -225f, 270f, false, tl, sz, style = Stroke((if (amb) 2f else 20f) * u, cap = StrokeCap.Round))
        val sweep = f.coerceIn(-1f, 1f) * Arc.MAX_DEG
        if (kotlin.math.abs(sweep) > 0.4f) {
            val w = (if (amb) 7f else 20f) * u
            val col = argb(if (amb && lowBit) Palette.AMB_INK else if (sweep > 0) Palette.DRAW else Palette.FLOW)
            val a = if (amb) 0.75f else 1f
            val dir = if (sweep > 0) 1f else -1f
            val cap = Math.toDegrees((w / 2 / r).toDouble()).toFloat()
            val body = max(0f, kotlin.math.abs(sweep) - cap)
            if (body > 0f) drawArc(col, -90f, dir * (body + 0.15f), false, tl, sz, alpha = a, style = Stroke(w, cap = StrokeCap.Butt))
            val e = -90f + dir * body
            val ea = Math.toRadians(e.toDouble())
            val tip = Offset(FaceKit.C * u + r * cos(ea).toFloat(), FaceKit.C * u + r * sin(ea).toFloat())
            drawArc(col, e, 180f * dir, true, Offset(tip.x - w / 2, tip.y - w / 2), Size(w, w), alpha = a)
        }
        drawLine(argb(if (amb) AMB_DIM2 else Palette.INK), at(FaceKit.C, 6f), at(FaceKit.C, 34f), strokeWidth = 3f * u)
    }

    // ---------------- the speed track: r 195, ticks every 5, the mode's limit, the passed part, red past the limit

    private fun deg(f: Float) = Dial.START_DEG + Dial.SPAN_DEG * f.coerceIn(0f, 1f)

    private fun DrawScope.radial(r1: Float, r2: Float, d: Float, colour: Color, w: Float) = drawLine(colour, pt(r1, d), pt(r2, d), strokeWidth = w * u)

    private fun DrawScope.arc(r: Float, from: Float, to: Float, colour: Color, w: Float, cap: StrokeCap) {
        val rr = r * u
        drawArc(colour, Dial.composeDeg(from), to - from, false, Offset(FaceKit.C * u - rr, FaceKit.C * u - rr), Size(2 * rr, 2 * rr), style = Stroke(w * u, cap = cap))
    }

    private fun DrawScope.speedTrack(s: DialState, f: GrowFrame, now: Long) {
        val amb = s.ambient
        val sp = s.speed
        arc(RS, -135f, 135f, argb(if (amb) AMB_TRACK else Palette.SPEED_TRACK), if (amb) 1.5f else 3f, StrokeCap.Round)
        if (!amb) {
            var v = 0.0
            while (v <= sp.max + 1e-9) { radial(RS - 6, RS + 6, deg(sp.fraction(v) ?: 0f), argb(Palette.SPEED_TICK), 2f); v += Dial.TICK_EVERY }
        }
        val lf = sp.fraction(sp.limit)
        if (lf != null) {
            val la = deg(lf)
            val atLim = f.atLimit
            val flare = f.tapS?.let { max(0f, 1 - it * 1000f / GrowMotion.TAP_FLARE_MS) } ?: 0f
            val lw = (if (atLim) 5f else 3f) + 3f * flare
            if (flare > 0f || atLim) halo(pt(RS, la), 0f, 16f, argb(Palette.LAMP_RED), (0.3f + 0.5f * flare).coerceIn(0f, 0.8f))
            val ext = if (atLim) 3f else 0f
            val col = if (amb) argb(if (s.lowBit) Palette.AMB_DIM else AMB_LIMIT) else argb(if (atLim) LIMIT_HOT else Palette.LIMIT)
            radial(RS - 9 - ext - 3 * flare, RS + 9 + ext + 3 * flare, la, col, lw)
        }
        val v = f.speed ?: return
        val va = deg(v)
        if (va > -134.5f) arc(RS, -135f, va, argb(if (amb) Palette.SPEED_TICK else Palette.MUTED), if (amb) 1.5f else 3f, StrokeCap.Round)
        if (!amb && lf != null && v > lf) arc(RS, deg(lf), va, argb(Palette.LAMP_RED), 5f, StrokeCap.Butt)
    }

    private fun DrawScope.speedDot(s: DialState, f: GrowFrame, now: Long) {
        val v = f.speed ?: return
        val amb = s.ambient
        val va = deg(v)
        var c = pt(RS, va)
        val red = argb(Palette.LAMP_RED)
        if (f.rumble) {
            // the dot trembles, rings at 4 Hz: the watch rumbles
            val t = now / 1000.0
            c += Offset(
                (1.8 * sin(t * 2 * PI * 23) * sin(t * 2 * PI * 3.1 + 1)).toFloat() * u,
                (1.8 * cos(t * 2 * PI * 19) * sin(t * 2 * PI * 2.3)).toFloat() * u,
            )
            for (i in 0 until 3) {
                val ph = ((t * 4 + i / 3.0) % 1.0).toFloat()
                drawCircle(red, (11f + 22f * ph) * u, c, alpha = 0.7f * (1 - ph), style = Stroke(2.5f * u))
            }
            halo(c, 6f, 18f, red, 0.45f)
        }
        val tap = f.tapS
        if (tap != null && tap * 1000 < GrowMotion.TAP_RING_MS) {
            val k = tap * 1000 / GrowMotion.TAP_RING_MS
            drawCircle(Color.White, (10f + 30f * FaceKit.easeOut(k)) * u, c, alpha = 0.9f * (1 - k), style = Stroke(3f * u))
        }
        val glow = if (tap != null && tap * 1000 < GrowMotion.TAP_GLOW_MS) 2f * (1 - tap * 1000 / GrowMotion.TAP_GLOW_MS) else 0f
        val r = (9f + glow) * u
        if (amb) {
            drawCircle(Color.Black, r, c)
            drawCircle(argb(Palette.INK2), r, c, style = Stroke(1.5f * u))
        } else drawCircle(if (f.rumble) red else argb(Palette.INK), r, c)
        val label = f.speedLabel ?: return
        drawTextAt(tm, label, font(10f * u, 700), c.x, c.y + 3.6f * u, if (amb) argb(Palette.INK2) else if (f.rumble) Color.White else Color.Black)
    }

    // ---------------- the centre

    private fun DrawScope.rows(s: DialState, f: GrowFrame) {
        val amb = s.ambient
        f.clock?.let { r ->
            if (r.a > 0.01f) fitText(f.clockText, GrowLayout.CLOCK_PX.toFloat(), 400, r.s, r.cx, r.y, r.lx, r.rx, argb(if (amb) AMB_DIM2 else Palette.MUTED), FaceKit.clamp01(r.a))
        }
        val b = f.bat
        withTransform({ translate(b.cx * u, b.y * u); scale(b.s, b.s, Offset.Zero) }) {
            drawBattery(
                tm, Offset(-78f * u, -33f * u), Size(140f * u, 66f * u), 4f * u, s.battery.pct, argb(s.battery.toneArgb), s.battery.text,
                GrowLayout.PCT_PX.toFloat() * u, amb, s.lowBit, f.band,
            )
        }
        f.range?.let { r ->
            s.range?.let { fitText(it, GrowLayout.RANGE_PX.toFloat(), 500, r.s, r.cx, r.y, r.lx, r.rx, argb(if (amb) Palette.INK2 else if (s.rangeRough) Palette.MUTED else Palette.INK)) }
        }
        f.det?.let { r -> fitText(f.detText, GrowLayout.DET_PX.toFloat(), 400, r.s, r.cx, r.y, r.lx, r.rx, argb(if (amb) AMB_DIM2 else Palette.MUTED)) }
        val m = f.msgText
        val mr = f.msg
        if (m != null && mr != null && mr.a > 0.01f) {
            val col = if (amb && s.lowBit) argb(Palette.AMB_INK) else argb(m.argb)
            fitText(m.text, GrowLayout.MSG_PX.toFloat(), if (amb) 400 else 600, mr.s, mr.cx, mr.y, mr.lx, mr.rx, col, FaceKit.clamp01(mr.a) * (if (amb) 0.85f else 1f))
        }
        val p = f.pow
        fitText(s.power.text, GrowLayout.POW_PX.toFloat(), 400, p.s, p.cx, p.y, p.lx, p.rx, argb(if (amb) AMB_DIM2 else s.power.argb))
    }

    /** Where the clock is: what is wrong with the data, not dimmed. */
    private fun DrawScope.status(s: DialState, f: GrowFrame) {
        val px = (if (f.status.size > 1) GrowLayout.STATUS2_PX else GrowLayout.STATUS_PX).toFloat()
        for ((r, line) in f.status) fitText(line, px, 500, r.s, r.cx, r.y, r.lx, r.rx, argb(if (s.ambient) Palette.AMB_INK else s.health.tone))
    }

    // ---------------- the ring: off dots, quiet icons

    private fun DrawScope.ring(f: GrowFrame, amb: Boolean, lowBit: Boolean) {
        for (sp in f.sprites) {
            if (sp.lit && sp.d > 12f) continue
            val big = FaceKit.clamp01((sp.d - 9f) / 18f)
            val dotA = FaceKit.clamp01(1 - sp.q * 1.4f - big) * (if (sp.hide) 0f else 1f)
            if (!amb && dotA > 0.01f && sp.d < 30f) drawCircle(argb(Palette.LAMP_OFF), max(1.5f, min(sp.d, 8f) / 2) * u, at(sp.x, sp.y), alpha = dotA)
            val q = sp.q.coerceIn(0f, 1.3f)
            if (q > 0.02f && !sp.hide) {
                val lv = if (sp.level == LampLevel.OFF) LampLevel.AMBER else sp.level
                val standing = sp.id == TellTales.SERVICE || sp.id == TellTales.WEAK
                val a = FaceKit.clamp01(q) * (if (amb) 0.6f else if (standing) 0.62f else 1f)
                drawGlyph(glyphs(sp.lamp, sp.id), at(sp.x, sp.y), GrowLayout.QUIET_D.toFloat() * q * u, lampColour(lv, amb, lowBit), ambient = amb, alpha = a)
            }
        }
    }

    // ---------------- the badges: solid (red, amber) or tinted (yellow, blue, green) discs, their icons and tablets

    private fun DrawScope.badges(f: GrowFrame, amb: Boolean, lowBit: Boolean) {
        val list = f.sprites.filter { !it.hide && it.d > 9.5f && (it.lit || it.d > it.dTarget + 1) }.sortedByDescending { it.rank }
        for (sp in list) badge(sp, amb, lowBit)
        for (sp in list) plate(sp, amb, lowBit)          // the tablets over every disc
    }

    private fun DrawScope.badge(sp: GrowSprite, amb: Boolean, lowBit: Boolean) {
        val lv = sp.level
        val col = lampColour(lv, amb, lowBit)
        val solid = FaceKit.solid(lv)
        val c = at(sp.x, sp.y)
        var d = max(0f, sp.d)
        var bright = 1f
        var glowA = if (solid) 0.34f else 0.16f
        val k = sp.pulse
        if (k != null && !amb) {
            d *= 1 + 0.06f * k
            bright = FaceKit.PULSE_MIN + (1 - FaceKit.PULSE_MIN) * k
            glowA = 0.18f + 0.32f * k
            sp.ripple?.let { ph -> drawCircle(col, (d / 2 + 2 + 14 * FaceKit.easeOut(ph)) * u, c, alpha = 0.45f * (1 - ph), style = Stroke(2.5f * u)) }
        }
        if (sp.pip) {
            // a pip: this place is lit too, its badge had no room — the colour only, ringed in black over the neighbour
            drawCircle(Color.Black, (d / 2 + 3) * u, c)
            if (amb) drawCircle(col, d / 2 * u, c, style = Stroke(2f * u)) else drawCircle(col, d / 2 * u, c)
            return
        }
        val plate = sp.plate?.takeIf { sp.mode == GrowMode.LOUD }
        val iconDy = if (plate != null) (if (plate.below) -0.1f else 0.1f) * d else 0f
        val ic = Offset(c.x, c.y + iconDy * u)
        val isz = d * GrowLayout.ICON_K.toFloat() * u
        val gl = glyphs(sp.lamp, sp.id)
        val rising = sp.lamp?.rising == true && sp.mode != GrowMode.CHECK
        if (amb) {
            drawCircle(col, (d / 2 - 1.5f) * u, c, style = Stroke(2.5f * u))
            drawGlyph(if (rising) withRise(gl) else gl, ic, isz, col, ambient = true)
            return
        }
        halo(c, d / 2 * 0.92f, d / 2 + if (sp.mode == GrowMode.CHECK) 6f else 10f, col, glowA)
        // the pop: one ring flies out as it lights (with the vibration's tap)
        sp.pop?.let { p -> drawCircle(col, (d / 2 + 4 + 28 * FaceKit.easeOut(p)) * u, c, alpha = 0.7f * (1 - p), style = Stroke((4 * (1 - p) + 1) * u)) }
        val bg: Color
        if (solid) {
            // red's beat is in its glow, size and ripple; its fill stays a red, not a dark disc beside a steady amber
            bg = shade(col, if (lv == LampLevel.RED) FaceKit.NIGHT_FILL * FaceKit.redFill(bright) else AMBER_FILL)
            drawCircle(bg, d / 2 * u, c)
        } else {
            bg = shade(col, 0.2f)
            drawCircle(bg, d / 2 * u, c)
            val rw = (d * 0.045f).coerceIn(2.5f, 5f)
            drawCircle(col, (d / 2 - rw / 2) * u, c, style = Stroke(rw * u))
        }
        val ia = FaceKit.clamp01((d - 20) / 22)
        if (ia <= 0f) return
        val ink = if (solid) argb(FaceKit.ON_SOLID) else col
        drawGlyph(if (rising) withRise(gl) else gl, ic, isz, ink, weight = if (solid) 1.15f else 1.1f, alpha = ia, knock = bg)
        // «быстро греется»: a second ▲ rises from the first and fades, when it lights and every 10 s
        sp.ghost?.let { ph -> drawGlyph(RISE_ONLY, Offset(ic.x, ic.y - 5f * ph * isz / Glyphs.BOX), isz, ink, alpha = ia * (1 - ph)) }
    }

    private fun DrawScope.plate(sp: GrowSprite, amb: Boolean, lowBit: Boolean) {
        val pl = sp.plate ?: return
        val text = sp.plateText ?: return
        if (sp.mode != GrowMode.LOUD || sp.dTarget <= 0f) return
        val k = sp.d / sp.dTarget
        val a = FaceKit.clamp01((k - 0.6f) / 0.3f)
        if (a <= 0f) return
        val lv = sp.level
        val cx = sp.x + sp.plateDx * k
        val h = pl.h.toFloat()
        val w = pl.w.toFloat()
        val y0 = if (pl.below) sp.y + sp.d / 2 + 6 - h else sp.y - sp.d / 2 - 6
        val tl = at(cx - w / 2, y0)
        drawRoundRect(Color.Black, tl, Size(w * u, h * u), CornerRadius(h / 2 * u), alpha = a)
        drawRoundRect(lampColour(lv, amb, lowBit), tl, Size(w * u, h * u), CornerRadius(h / 2 * u), alpha = a * (if (amb) 0.6f else 1f), style = Stroke((if (amb) 1.5f else 2.5f) * u))
        val px = pl.px.toFloat()
        fitText(text, px, if (amb) 500 else 700, 1f, cx, y0 + h / 2 + px * 0.36f, cx - w / 2 + px * 0.3f, cx + w / 2 - px * 0.3f, textColour(lv, amb, lowBit), a * (if (amb) 0.85f else 1f))
    }

    private fun shade(c: Color, k: Float) = Color(c.red * k, c.green * k, c.blue * k, 1f)

    companion object {
        /** The speed track's radius. */
        const val RS = 195f
        /** Amber's solid fill (red's is [FaceKit.NIGHT_FILL]). */
        const val AMBER_FILL = 0.8f
        // the mock's ambient greys and the limit tick at the limiter
        const val AMB_TRACK = 0xFF26302D
        const val AMB_DIM2 = 0xFF6D7672
        const val AMB_LIMIT = 0xFFAA3333
        const val LIMIT_HOT = 0xFFFF4D42

        private const val BAT_BODY = "M5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"
        private const val BAT_NUB = "M20.5 11a1 1 0 0 1 2 0v2a1 1 0 0 1-2 0z"
        private const val STRIKE = "M3.5 20.5L20.5 3.5"
        /** «BMS отключает батарею» / «Нет связи с BMS»: the battery struck through, the stroke cut out of the body (grow's). */
        val BMS: List<Glyph> = listOf(
            Glyph(BAT_BODY), Glyph(BAT_NUB, fill = true), Glyph(STRIKE, width = 5.4f, roundCap = true, knock = true), Glyph(STRIKE, width = 2.4f, roundCap = true),
        )
        /** The ▲ «быстро греется» at the icon's top right (24-unit box). */
        private val RISE = Glyph("M16.6 9.6l3.2-5.4 3.2 5.4z", fill = true)
        private val RISE_ONLY = listOf(RISE)
        private val ROSE = HashMap<List<Glyph>, List<Glyph>>()

        fun withRise(g: List<Glyph>): List<Glyph> = ROSE.getOrPut(g) { g + RISE }

        /** Grow's icons: the battery with its last division, the struck one for the BMS, a turtle for «Мало тяги». */
        fun glyphs(l: Lamp?, id: String): List<Glyph> = when {
            id == TellTales.BATTERY && (l?.text == TellTales.T_BMS_CUT || l?.text == TellTales.T_NO_BMS) -> BMS
            id == TellTales.BATTERY -> Glyphs.BATTERY_LOW
            id == TellTales.TRACTION -> Glyphs.TURTLE
            else -> Glyphs.BY_ID[id] ?: Glyphs.BY_ID.getValue(TellTales.SERVICE)
        }
    }
}
