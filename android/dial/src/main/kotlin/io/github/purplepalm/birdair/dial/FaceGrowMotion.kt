package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/*
 * «Растущие лампы» in motion, pure: what is on screen at a moment ([GrowScene.frame]) from the state and two layouts — the
 * one before the last change (the lamps', the bulb check's start or end) and the one now — moved between by the mock's
 * springs in closed form (FaceKit.pop: growth ω 16 ζ 0,45 with one overshoot, places ω 11 ζ 0,9, rows ω 10 ζ 0,95), so a
 * frame at any time is the same in a shot and live. FaceGrow.kt draws the frames.
 */

/** How a lamp is on screen: an off dot, a quiet icon, a big badge, a mini badge (or pip), its bulb-check disc. */
enum class GrowMode { OFF, QUIET, LOUD, MINI, CHECK }

/**
 * One lamp at a moment, mock px: [d] the disc now (springing towards [dTarget]), [q] the quiet icon's scale, [hide] under a
 * badge; [plate] the target's tablet ([plateText]), it fades in as the disc grows; [pop] the lit ring's progress 0…1,
 * [pulse] red's brightness phase (1 bright … 0 dim) and [ripple] its ring, [ghost] the second ▲'s rise, null when none.
 */
class GrowSprite(
    val id: String, val mode: GrowMode, val level: LampLevel, val lamp: Lamp?,
    val x: Float, val y: Float, val d: Float, val dTarget: Float, val q: Float,
    val hide: Boolean, val pip: Boolean, val rank: Int,
    val plate: GrowLayout.Plate?, val plateText: String?, val plateDx: Float,
    val pop: Float?, val pulse: Float?, val ripple: Float?, val ghost: Float?,
) {
    val lit: Boolean get() = mode == GrowMode.LOUD || mode == GrowMode.MINI || mode == GrowMode.CHECK
}

/** A centre row: its centre, baseline (the battery: its centre), scale and alpha; [lx]…[rx] the room it has (a long text ellipsizes there). */
class GrowRow(val cx: Float, val y: Float, val s: Float, val a: Float, val lx: Float, val rx: Float)

/** The text line: [argb] its colour (the lamp's, lifted; grey for «проверка ламп»). */
data class GrowMessage(val text: String, val argb: Long)

/**
 * A frame: the 12 [sprites], the rows (null: not shown), the speed dot's place [speed] (0…1, null: no speed) and its
 * [speedLabel], the power arc's [power]; [tapS] seconds since the limiter tap (≤ 3), [rumble], [atLimit] (at or past the
 * tap's point), [band] the charging band's place 0…1.
 */
class GrowFrame(
    val sprites: List<GrowSprite>,
    val clock: GrowRow?, val clockText: String, val status: List<Pair<GrowRow, String>>,
    val bat: GrowRow, val range: GrowRow?, val det: GrowRow?, val detText: String, val msg: GrowRow?, val msgText: GrowMessage?,
    val pow: GrowRow,
    val speed: Float?, val speedLabel: String?, val power: Float,
    val bulb: Boolean, val tapS: Float?, val rumble: Boolean, val atLimit: Boolean, val band: Float?,
)

/** The state → frames, the layouts cached (one per screen; [m] measures in mock px). Not thread-safe. */
class GrowScene(private val m: GrowMeasure) {
    private val plans = object : LinkedHashMap<GrowLayout.Input, GrowLayout.Plan>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GrowLayout.Input, GrowLayout.Plan>?) = size > 8
    }
    /** What a state's frames share (until the next state, or the next change in it): the two layouts, their lines, the tablets' texts. */
    private class Memo(
        val state: DialState, val key: Long, val from: GrowLayout.Plan, val to: GrowLayout.Plan,
        val msgFrom: GrowMessage?, val msgTo: GrowMessage?, val plates: Map<String, List<String>>,
    )
    private var memo: Memo? = null

    fun plan(input: GrowLayout.Input): GrowLayout.Plan = plans.getOrPut(input) { GrowLayout.plan(input, m) }

    /**
     * The layout's input for [s] with [lamps] lit, [bulb]: «проверка ламп» on the text line. Texts are measured with their
     * digits as 0 (the faces draw tabular digits): a new value of the same length is not a new layout.
     */
    fun input(s: DialState, lamps: List<Lamp>, bulb: Boolean): GrowLayout.Input {
        val msg = message(lamps, bulb)
        val det = s.details
        val status = lines(s.status)
        val statusPx = if (status.size > 1) GrowLayout.STATUS2_PX else GrowLayout.STATUS_PX
        return GrowLayout.Input(
            lamps.map { l -> GrowLayout.of(l).let { it.copy(plates = it.plates.map(::zeros)) } },
            GrowLayout.Need(
                range = s.range?.let { w(it, GrowLayout.RANGE_PX, 500) } ?: 0.0,
                det = if (det.isEmpty()) 0.0 else w(det, GrowLayout.DET_PX, 400),
                detShort = if (det.isEmpty()) 0.0 else w(short(det), GrowLayout.DET_PX, 400),
                msg = msg?.let { w(it.text, GrowLayout.MSG_PX, 600) } ?: 0.0,
                pow = w(s.power.text, GrowLayout.POW_PX, 400),
                clock = if (s.status == null && s.clock.isNotBlank()) w(s.clock, GrowLayout.CLOCK_PX, 400) else 0.0,
                status = status.map { w(it, statusPx, 500) },
            ),
        )
    }

    private fun w(text: String, px: Double, weight: Int) = m.width(zeros(text), px, weight)

    /** The frame at [now] (the state's clock); [speedF] / [powerF] the springs' values (the speed's and the arc's share). */
    fun frame(s: DialState, now: Long, speedF: Float?, powerF: Float): GrowFrame {
        val amb = s.ambient
        val b0 = s.bulbAtMs?.takeIf { !amb }
        val bulbEnd = b0?.plus(TellTales.BULB_CHECK_MS)
        val bulbNow = b0 != null && now >= b0 && now < bulbEnd!!
        // the last change at or before now: the lamps, the bulb check's start, its end
        val lampsAt = if (amb) null else s.lamps.changedMs?.takeIf { it <= now }
        val at = listOfNotNull(lampsAt, b0?.takeIf { it <= now }, bulbEnd?.takeIf { it <= now }).maxOrNull()
        val key = (at ?: Long.MIN_VALUE / 4) * 2 + (if (bulbNow) 1 else 0)
        val mm = memo?.takeIf { it.state === s && it.key == key } ?: run {
            val lit = known(s.lamps.lit.map { it.lamp })
            val to = plan(input(s, lit, bulbNow))
            val beforeLamps = if (at != null && at == lampsAt) known(s.lamps.before) else lit
            val beforeBulb = when {
                at == null -> bulbNow
                at == lampsAt -> b0 != null && at - 1 >= b0 && at - 1 < bulbEnd!!
                at == b0 -> false
                else -> true
            }
            val from = if (at == null) to else plan(input(s, beforeLamps, beforeBulb))
            Memo(s, key, from, to, message(beforeLamps, beforeBulb), message(lit, bulbNow), lit.associate { it.id to GrowLayout.of(it).plates }).also { memo = it }
        }
        val from = mm.from
        val to = mm.to
        val t = if (at == null) Long.MAX_VALUE else now - at

        val sprites = ArrayList<GrowSprite>(TellTales.IDS.size)
        for (id in TellTales.IDS) {
            val base = sprite(s, id, from, to, t, now, amb, mm.plates)
            sprites += if (b0 != null) bulb(base, id, now - b0) else base
        }

        // the rows
        val pr = spring(t, 10f, 0.95f)
        val r0 = from.rows
        val r1 = to.rows
        fun row(f0: GrowLayout.Fit?, f1: GrowLayout.Fit?, y0: Double, y1: Double, a0: Double, a1: Double): GrowRow {
            val a = f0 ?: f1!!
            val b = f1 ?: f0!!
            return GrowRow(lerp(a.cx, b.cx, pr), lerp(y0, y1, pr), lerp(a.s, b.s, pr), lerp(a0, a1, pr), (b.lo - b.ws / 2).toFloat(), (b.hi + b.ws / 2).toFloat())
        }
        val pow = row(r0.pow, r1.pow, r0.powY, r1.powY, 1.0, 1.0)
        val msg = if (r0.msg == null && r1.msg == null) null else {
            val r = row(r0.msg, r1.msg, r0.msgY, r1.msgY, if (r0.msg != null) 1.0 else 0.0, if (r1.msg != null) 1.0 else 0.0)
            // the line waits for the power to reach its new place before it fades in
            val wait = if (r1.msg != null) FaceKit.clamp01(1 - abs(pow.y - r1.powY.toFloat()) / 8f) else 1f
            GrowRow(r.cx, r.y, r.s, r.a * wait, r.lx, r.rx)
        }
        val msgText = mm.msgTo ?: mm.msgFrom?.takeIf { (msg?.a ?: 0f) > 0.15f }
        val status = lines(s.status)
        val statusRows = status.mapIndexedNotNull { i, line ->
            val f = r1.status.getOrNull(i) ?: return@mapIndexedNotNull null
            val y = if (status.size > 1) (if (i == 0) 106f else 127f) else 122f
            GrowRow(f.cx.toFloat(), y, f.s.toFloat(), 1f, (f.lo - f.ws / 2).toFloat(), (f.hi + f.ws / 2).toFloat()) to line
        }
        val clock = if (s.status != null || s.clock.isBlank()) null else row(r0.clock, r1.clock, 122.0, 122.0, r0.clockA, r1.clockA)

        // the speed and the power: the bulb check sweeps them up and back
        var speed = speedF
        var power = powerF
        var label = s.speed.value?.let { Math.round(maxOf(0.0, it)).toString() }
        if (bulbNow) {
            val ph = (now - b0!!) / 1000f
            val k = if (ph < 0.6f) FaceKit.ease(ph / 0.6f) else if (ph < 0.9f) 1f else 1 - FaceKit.ease((ph - 0.9f) / 0.6f)
            val sp = if (ph < 0.9f) k else FaceKit.lerp(speedF ?: 0f, 1f, k)
            speed = sp
            power = if (ph < 0.9f) k else FaceKit.lerp(powerF, 1f, k)
            label = Math.round(sp * s.speed.max).toString()
        }
        val tap = if (amb) null else s.cues.tapAtMs?.let { now - it }?.takeIf { it in 0..3_000 }?.let { it / 1000f }
        val band = if (amb || !s.battery.charging) null else {
            val since = s.lamps[TellTales.CHARGING]?.sinceMs ?: s.nowMs
            val ph = Math.floorMod(now - since, GrowMotion.BAND_EVERY_MS)
            if (ph < GrowMotion.BAND_MS) ph.toFloat() / GrowMotion.BAND_MS else null
        }
        return GrowFrame(
            sprites,
            clock, s.clock, statusRows,
            row(r0.bat, r1.bat, 193.0, 193.0, 1.0, 1.0),
            if (s.range == null) null else row(r0.range, r1.range, 262.0, 262.0, 1.0, 1.0),
            if (s.details.isEmpty()) null else row(r0.det, r1.det, 290.0, 290.0, 1.0, 1.0),
            if (r1.detShort) short(s.details) else s.details,
            msg, msgText, pow,
            speed, label, power,
            bulbNow, tap, !amb && s.live && s.cues.rumble, !amb && s.live && s.speed.atLimiter, band,
        )
    }

    /** [id] between its place in [from] and in [to], [t] ms after the change. */
    private fun sprite(
        s: DialState, id: String, from: GrowLayout.Plan, to: GrowLayout.Plan, t: Long, now: Long, amb: Boolean, plates: Map<String, List<String>>,
    ): GrowSprite {
        val a = slot(from, id)
        val b = slot(to, id)
        val pPos = spring(t, 11f, 0.9f)
        val pD = spring(t, 16f, if (b.d > a.d + 0.5) 0.45f else 1f)
        val pQ = spring(t, 16f, 0.5f)
        val dl = s.lamps[id]
        val lamp = dl?.lamp ?: s.lamps.before.firstOrNull { it.id == id }
        val level = (if (b.mode != GrowMode.OFF) b.level else null) ?: (if (a.mode != GrowMode.OFF) a.level else null) ?: LampLevel.OFF
        val plateText = b.plate?.let { p -> plates[id]?.getOrNull(p.index) }
        var pop: Float? = null
        var pulse: Float? = null
        var ripple: Float? = null
        var ghost: Float? = null
        if (!amb && dl != null && (b.mode == GrowMode.LOUD || b.mode == GrowMode.MINI)) {
            val since = now - GrowMotion.popAt(dl)
            if (since in 0 until GrowMotion.POP_MS) pop = since.toFloat() / GrowMotion.POP_MS
            if (b.mode == GrowMode.LOUD && dl.level == LampLevel.RED) {
                // 1 Hz from the red vibration (or from when it turned red): bright on the beat
                val red = now - FaceKit.redSince(dl, s.cues)
                val ph = if (red < 0) 0f else (red % FaceKit.PULSE_MS).toFloat() / FaceKit.PULSE_MS
                pulse = 0.5f + 0.5f * cos(2 * Math.PI.toFloat() * ph)
                if (red > FaceKit.PULSE_MS / 2) ripple = ph
            }
            if (dl.lamp.rising) {
                val ph = Math.floorMod(now - dl.sinceMs, GrowMotion.GHOST_EVERY_MS)
                if (ph < GrowMotion.GHOST_MS) ghost = ph.toFloat() / GrowMotion.GHOST_MS
            }
        }
        return GrowSprite(
            id, b.mode, level, lamp,
            lerp(a.x, b.x, pPos), lerp(a.y, b.y, pPos), lerp(a.d, b.d, pD), b.d.toFloat(), lerp(a.q, b.q, pQ),
            b.hide, b.pip, b.rank, b.plate, plateText, b.plate?.let { (it.cx - b.x).toFloat() } ?: 0f, pop, pulse, ripple, ghost,
        )
    }

    /** The bulb check over [base], [ms] after it started: each place lights from the top down, a 44 px disc at r 158, and goes back. */
    private fun bulb(base: GrowSprite, id: String, ms: Long): GrowSprite {
        val j = ((abs(GrowLayout.HOME.getValue(id)) - 40) / 20).roundToInt()
        val on = GrowMotion.bulbOn(j)
        val off = GrowMotion.bulbOff(j)
        if (ms < on || ms >= off + GrowMotion.SETTLE_MS) return base
        val (cx, cy) = GrowLayout.point(GrowLayout.CHECK_R, GrowLayout.HOME.getValue(id))
        val level = BULB.getValue(id)
        val d0 = GrowLayout.CHECK_D
        if (ms < off) {
            val k = ms - on
            val pPos = spring(k, 11f, 0.9f)
            val pD = spring(k, 16f, if (d0 > base.d + 0.5) 0.45f else 1f)
            return GrowSprite(
                id, GrowMode.CHECK, level, BULB_LAMPS.getValue(id),
                lerp(base.x.toDouble(), cx, pPos), lerp(base.y.toDouble(), cy, pPos), lerp(base.d.toDouble(), d0, pD), d0.toFloat(),
                lerp(base.q.toDouble(), 0.0, spring(k, 16f, 0.5f)), false, false, 9, null, null, 0f, null, null, null, null,
            )
        }
        val k = ms - off
        val pPos = spring(k, 11f, 0.9f)
        val pD = spring(k, 16f, if (base.dTarget > d0 + 0.5) 0.45f else 1f)
        return GrowSprite(
            id, base.mode, if (base.mode == GrowMode.OFF) level else base.level, base.lamp ?: BULB_LAMPS.getValue(id),
            lerp(cx, base.x.toDouble(), pPos), lerp(cy, base.y.toDouble(), pPos), lerp(d0, base.d.toDouble(), pD), base.dTarget,
            lerp(0.0, base.q.toDouble(), spring(k, 16f, 0.5f)), base.hide, base.pip, base.rank, base.plate, base.plateText, base.plateDx,
            null, base.pulse, base.ripple, base.ghost,
        )
    }

    private class Slot(
        val mode: GrowMode, val level: LampLevel, val x: Double, val y: Double, val d: Double, val q: Double,
        val hide: Boolean, val pip: Boolean, val rank: Int, val plate: GrowLayout.Plate?,
    )

    private fun slot(p: GrowLayout.Plan, id: String): Slot {
        p.badge(id)?.let { return Slot(GrowMode.LOUD, it.level, it.x, it.y, it.d, 0.0, false, false, it.rank, it.plate) }
        val sm = p.small(id)!!
        val home = GrowLayout.HOME.getValue(id)
        return when (sm.kind) {
            GrowLayout.Kind.MINI -> Slot(GrowMode.MINI, sm.level, sm.spot!!.x, sm.spot.y, sm.d, 0.0, false, sm.pip, if (sm.pip) -1 else 5, null)
            GrowLayout.Kind.QUIET -> {
                val (hx, hy) = GrowLayout.point(GrowLayout.RQ, home)
                Slot(GrowMode.QUIET, sm.level, sm.spot?.x ?: hx, sm.spot?.y ?: hy, 0.0, 1.0, sm.spot == null, false, 9, null)
            }
            GrowLayout.Kind.DOT -> {
                val (hx, hy) = GrowLayout.point(GrowLayout.RD, home)
                Slot(GrowMode.OFF, LampLevel.OFF, sm.spot?.x ?: hx, sm.spot?.y ?: hy, GrowLayout.DOT_D, 0.0, sm.spot == null, false, 9, null)
            }
        }
    }

    private fun spring(t: Long, omega: Float, zeta: Float): Float = if (t >= GrowMotion.SETTLE_MS) 1f else FaceKit.pop(t, omega, zeta)
    private fun lerp(a: Double, b: Double, k: Float): Float = (a + (b - a) * k).toFloat()

    companion object {
        private val BULB_LAMPS: Map<String, Lamp> = TellTales.bulbCheck().associateBy { it.id }
        private val BULB: Map<String, LampLevel> = BULB_LAMPS.mapValues { it.value.level }

        /** Lamps this build knows (an unknown id from a newer phone has no place on the ring). */
        fun known(lamps: List<Lamp>): List<Lamp> = lamps.filter { it.id in GrowLayout.HOME && it.level != LampLevel.OFF }

        /** The status line(s) where the clock is (two at most). */
        fun lines(status: String?): List<String> = status?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(2).orEmpty()

        /** The text line: «проверка ламп» during the check, else [TellTales.top]'s text in its colour (lifted). */
        fun message(lamps: List<Lamp>, bulb: Boolean): GrowMessage? {
            if (bulb) return GrowMessage(Dial.BULB_TEXT, Palette.MUTED)
            val top = TellTales.top(lamps) ?: return null
            if (top.text.isBlank()) return null
            return GrowMessage(
                top.text,
                when (top.level) {
                    LampLevel.RED -> FaceKit.TITLE_RED
                    LampLevel.BLUE -> FaceKit.TITLE_BLUE
                    LampLevel.GREEN -> FaceKit.TITLE_GREEN
                    else -> Lamps.argb(top.level)
                },
            )
        }

        /** «41,9 В · 57 °C · FLY» → «41,9 В · 57° · FLY» (the field stays, the unit goes). */
        fun short(details: String): String = details.replace(" °C", "°")

        /** Digits as 0: the faces draw tabular digits, so the width is the same and a new value is not a new layout. */
        fun zeros(text: String): String {
            if (text.none { it in '1'..'9' }) return text
            val c = text.toCharArray()
            for (i in c.indices) if (c[i] in '1'..'9') c[i] = '0'
            return String(c)
        }
    }
}

/** The face's timing (the mock's): when it next moves ([wakeAt]), the pop, the ▲'s second one, the bulb check's wave, the charging band. */
object GrowMotion {
    /** Every transition settles in this (the springs: 99 % by then). */
    const val SETTLE_MS = FaceKit.APPEAR_MS
    /** The ring a lamp throws when it lights (with the vibration's tap). */
    const val POP_MS = 600L
    /** «быстро греется»: a second ▲ rises from the first when it lights and every [GHOST_EVERY_MS], for [GHOST_MS]. */
    const val GHOST_EVERY_MS = 10_000L
    const val GHOST_MS = 1_200L
    /** The limiter tap: the white ring, the tick's flare, the dot's glow. */
    const val TAP_RING_MS = 700L
    const val TAP_FLARE_MS = 900L
    const val TAP_GLOW_MS = 350L
    /** While charging a light band runs through the fill: [BAND_MS] every [BAND_EVERY_MS]. */
    const val BAND_EVERY_MS = 4_000L
    const val BAND_MS = 1_200L

    /** The bulb check: the place j (0 the top pair … 5 the bottom) lights at 60·j ms and goes out at 1050 + 50·j ms … */
    fun bulbOn(j: Int): Long = 60L * j
    fun bulbOff(j: Int): Long = 1_050L + 50L * j
    /** … and all is still again by then (the last place out, the rows back). */
    const val BULB_TAIL_MS = TellTales.BULB_CHECK_MS + SETTLE_MS

    /** When [l] lit or changed level (its pop); a change of text alone is no pop. */
    fun popAt(l: DialLamp): Long = if (l.before == null || l.before.level == l.level) l.sinceMs else l.changedMs

    /**
     * When the face next moves at [now] ([rememberFaceClock]): now while a red lamp pulses, the rumble runs, the bulb check
     * or a transition runs, a pop or the tap's flare; the next ▲ or charging band; null when all is still (and in ambient).
     */
    fun wakeAt(s: DialState, now: Long): Long? {
        if (s.ambient) return null
        if (s.lamps.lit.any { it.level == LampLevel.RED && !it.quiet } || (s.cues.rumble && s.live)) return now
        s.bulbAtMs?.let { if (now >= it && now < it + BULB_TAIL_MS) return now }
        val recent = listOfNotNull(s.lamps.changedMs?.plus(SETTLE_MS), s.cues.tapAtMs?.plus(TAP_FLARE_MS)) +
            s.lamps.lit.map { popAt(it) + POP_MS }
        if (recent.any { it > now }) return now
        var next: Long? = null
        for (l in s.lamps.lit) if (l.lamp.rising && !l.quiet) {
            val ph = Math.floorMod(now - l.sinceMs, GHOST_EVERY_MS)
            if (ph < GHOST_MS) return now
            next = minOf(next ?: Long.MAX_VALUE, now + GHOST_EVERY_MS - ph)
        }
        if (s.battery.charging) {
            val since = s.lamps[TellTales.CHARGING]?.sinceMs ?: s.nowMs
            val ph = Math.floorMod(now - since, BAND_EVERY_MS)
            if (ph < BAND_MS) return now
            next = minOf(next ?: Long.MAX_VALUE, now + BAND_EVERY_MS - ph)
        }
        return next
    }
}
