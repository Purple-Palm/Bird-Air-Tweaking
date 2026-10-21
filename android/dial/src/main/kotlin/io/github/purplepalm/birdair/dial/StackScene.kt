package io.github.purplepalm.birdair.dial

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * DialFace.STACK's motion and layout, pure (FaceStack.kt draws it): [StackTimeline] keeps what the face has shown — the
 * lit sets in the order it showed them (each change ≥ [Stack.QGAP] after the previous one), the values' and the
 * battery's last changes, the buzzes, the startup — and [StackScene.build] lays one frame out in the mock's 480-px grid
 * (gallery/stack.html, stack2's motion spec): chips, rings, texts, the battery, the tracks. Every curve is a function of
 * (now − a start), so any frame is exact: a shot, a test or a theme switch mid-move gives the same picture.
 */

/** «Крупные лампы»'s timing (stack2's motion spec, ms from the event that starts each) and its springs. */
object Stack {
    /** A change of the lit set starts no sooner than this after the previous one (the queue is the face's). */
    const val QGAP = 750L
    const val OUT = 150L
    const val OUT_DX = 8f
    const val MOVE = 480L
    const val BULGE_IN = -40f
    const val BULGE_OUT = 18f
    const val BEAD = 280L
    /**
     * The bloom's overshoot peak (the bead's 280 ms + 188): with this face the watch plays a lamp's buzz this long after
     * its message, so the motor goes as the chip lands (WatchFeed); the rings and kicks are drawn from the real buzz.
     */
    const val LAND_MS = 470L
    val TXT = longArrayOf(380, 420, 460)
    val TXT_STEP = longArrayOf(150, 190, 230)
    const val TXT_D = 200L
    const val TXT_DX = 14f
    val GHOSTS = floatArrayOf(0.45f, 0.28f, 0.16f, 0.07f)
    const val GHOST_LAG = 24L
    const val GHOST_FADE = 60L
    const val LAUNCH = 200L
    const val RING_LIFE = 600L
    const val RING_GROW = 34f
    val RED_PULSES = longArrayOf(0, 340, 680)
    const val RED_ON = 230L
    const val AMBER_ON = 110L
    const val KICK = 0.05f
    const val SWEEP0 = 150L
    const val SWEEP1 = 470L
    const val FADE_DOWN = 300L
    const val OFF0 = 220L
    const val OFF1 = 520L
    const val OFF2 = 700L
    const val CHECK0 = 100L
    const val CHECK1 = 280L
    const val CHECK2 = 450L
    const val CHECK3 = 700L
    const val ROLL = 300L
    const val ROLL_STAGGER = 40L
    const val ROLL_GAP = 1000L

    /** How long [from] → [to] rolls: 300 ms, plus 40 for each changed glyph after the first (the whole: one). */
    fun rollMs(from: String?, to: String?): Long {
        if (from == null || to == null || from == to) return 0
        val n = if (from.length == to.length) from.indices.count { from[it] != to[it] } else 1
        return ROLL + ROLL_STAGGER * (n - 1)
    }
    const val BREATH = 1000L
    const val DEPTH = 0.45f
    const val DEPTH_CALM = 0.22f
    const val CALM_AFTER = 8000L
    const val CALM_EASE = 1000L
    const val REPHASE = 120L
    // the motor (wear's OverspeedAlarm / LimiterTap): 500 on / 100 off from each (re)arm every 2 s; the tap 150 ms
    const val RUMBLE_ON = 500L
    const val RUMBLE_PERIOD = 600L
    const val REARM = 2000L
    const val WAVE_LIFE = 900L
    const val MOTOR_FLOOR = 0.3f
    const val MOTOR_RAMP = 30L
    const val TAP_ON = 150L
    const val TAP_RING = 600L
    const val TAP_GLOW = 700L
    const val TAP_KICK = 0.25f
    /** «Ограничитель · 15,6 mph» under the battery after a tap. */
    const val TAP_LINE = 2140L
    const val SHIMMER_PERIOD = 4000L
    const val SHIMMER_AT = 600L
    const val SHIMMER_D = 1400L
    const val TONE = 300L
    const val QUIET_FADE = 200L
    const val LAYOUT_DELAY = 80L
    const val LAYOUT = 440L
    // the startup from the gauge's bulb check (ms from DialState.bulbAtMs)
    const val BOOT_FADE = 200L
    const val BOOT_UP0 = 200L
    const val BOOT_UP1 = 800L
    const val BOOT_DOWN0 = 900L
    const val BOOT_DOWN1 = 1500L
    const val BOOT_FILL0 = 200L
    const val BOOT_FILL1 = 900L
    const val BOOT_WAVE0 = 200L
    const val BOOT_WAVE_STEP = 45L
    const val BOOT_FLY0 = 1300L
    const val BOOT_FLY_STEP = 8L
    const val BOOT_FLY = 300L
    const val BOOT_DOT_FADE = 500L
    /** The lamp part ends (TellTales.BULB_CHECK_MS after the wave starts); lamps lit meanwhile come in from here. */
    const val BOOT_END = 1700L
    const val BOOT_TOTAL = 2200L
    /** A gauge opened riding (live, from this speed) gets the lamp wave only: the arc and the dot keep the real data. */
    const val BOOT_STANDING_KMH = 3.0

    /** (ζ, stiffness): amber / red land hard, an info lamp never harder than an alarm; the startup's chips; «+N». */
    val ALARM = 0.55f to 400f
    val INFO = 0.75f to 400f
    val POP = 0.55f to 600f
    val PLUS = 0.6f to 500f
}

/** Text width in px at [px] (mock px), [weight] 400…700, tabular digits or not: the face's measurer, or a test's. */
fun interface TextWidth {
    fun width(text: String, px: Float, weight: Int, tnum: Boolean): Float
}

/** The curves of the motion spec on 0…1 (clamped), and its springs. */
internal object Ez {
    fun c(x: Float): Float = if (x.isNaN()) 0f else x.coerceIn(0f, 1f)
    fun fosi(x: Float): Float = FastOutSlowInEasing.transform(c(x))
    fun losi(x: Float): Float = LinearOutSlowInEasing.transform(c(x))
    fun foli(x: Float): Float = FastOutLinearInEasing.transform(c(x))
    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    fun sm(a: Float, b: Float, x: Float): Float = FaceKit.smoothstep(a, b, x)
    fun sm(a: Long, b: Long, x: Long): Float = FaceKit.smoothstep(a.toFloat(), b.toFloat(), x.toFloat())
    /** (e − a) / d through [f], 0 before. */
    fun tween(e: Long, a: Long, d: Long, f: (Float) -> Float = ::fosi): Float = f((e - a).toFloat() / d)
    /** One motor pulse: a half sine over its on-time. */
    fun half(e: Long, on: Long): Float = if (e in 0 until on) sin(PI.toFloat() * e / on) else 0f
    /** A unit step from rest through a spring (mass 1): Animatable(0f).animateTo(1f, spring(ζ, k)). */
    fun spring(ms: Long, s: Pair<Float, Float>): Float = FaceKit.pop(ms, sqrt(s.second), s.first)
    fun mix(a: Long, b: Long, q: Float): Long {
        val k = c(q)
        fun ch(v: Long, sh: Int) = ((v shr sh) and 0xFF).toFloat()
        var out = 0xFF000000L
        for (sh in intArrayOf(16, 8, 0)) out = out or ((ch(a, sh) + (ch(b, sh) - ch(a, sh)) * k).roundToInt().toLong() shl sh)
        return out
    }
}

/** A place on the grid (mock px) and a chip's diameter there. */
class Pos(val x: Float, val y: Float, val d: Float)

/** Where a lamp is: its dot, «+N», the main slot, the «smile», the standing icons. */
enum class Slot {
    DOT, HIDDEN, HERO, A, B, C, QL, QR, QR2;

    val visible: Boolean get() = this != DOT && this != HIDDEN
    val kind: Kind get() = when (this) { HERO -> Kind.HERO; QL, QR, QR2 -> Kind.STAND; else -> Kind.SEC }
}

/** How a chip is drawn: the main one (amber solid), a «smile» one (a ring), a standing icon. */
enum class Kind { HERO, SEC, STAND }

/** The geometry of gallery/stack.html (480-px grid). */
object StackGeo {
    const val C = 240f
    /** The power arc (20 px) and the lamps' dots in its bottom gap. */
    const val RP = 222f
    /** The speed track. */
    const val RS = 195f
    val HERO_AMBER = Pos(118f, 272f, 104f)
    val HERO_RED = Pos(122f, 272f, 116f)
    val SMILE_A = Pos(156f, 390f, 76f)
    val SMILE_B = Pos(240f, 408f, 76f)
    val SMILE_C = Pos(324f, 390f, 76f)
    const val PLUS_D = 56f
    /** The main lamp's text ends here: clear of the speed track, and of the dot and its rumble waves at 17–19 mph. */
    const val TEXT_R = 402f
    private val CALM = mapOf(Slot.QL to Pos(104f, 193f, 36f), Slot.QR to Pos(376f, 193f, 36f), Slot.QR2 to Pos(376f, 240f, 32f))
    private val ALERT = mapOf(Slot.QL to Pos(148f, 129f, 30f), Slot.QR to Pos(340f, 129f, 30f), Slot.QR2 to Pos(372f, 160f, 28f))

    fun stand(s: Slot, alert: Boolean): Pos = (if (alert) ALERT else CALM).getValue(s)

    /** The battery: calm, a lamp, a red main lamp. */
    class Bat(val x: Float, val y: Float, val w: Float, val h: Float, val font: Float, val line: Float) {
        fun lerp(b: Bat, k: Float) = Bat(Ez.lerp(x, b.x, k), Ez.lerp(y, b.y, k), Ez.lerp(w, b.w, k), Ez.lerp(h, b.h, k), Ez.lerp(font, b.font, k), Ez.lerp(line, b.line, k))
    }
    val BAT = arrayOf(Bat(162f, 160f, 140f, 66f, 40f, 4f), Bat(178f, 102f, 124f, 54f, 36f, 3.5f), Bat(184f, 106f, 112f, 48f, 30f, 3f))

    fun hero(level: LampLevel): Pos = if (level == LampLevel.RED) HERO_RED else HERO_AMBER

    fun smile(s: Slot): Pos = when (s) { Slot.A -> SMILE_A; Slot.C -> SMILE_C; else -> SMILE_B }

    /** Clock angle of lamp [id]'s dot: the 12 in the power arc's bottom gap, IDS order left → right (an unknown id: 6 o'clock). */
    fun dotDeg(id: String): Float = TellTales.IDS.indexOf(id).let { i -> if (i < 0) 180f else 208f - 56f * i / 11f }

    fun px(r: Float, deg: Float): Float = C + r * sin(Math.toRadians(deg.toDouble())).toFloat()
    fun py(r: Float, deg: Float): Float = C - r * cos(Math.toRadians(deg.toDouble())).toFloat()
    fun at(r: Float, deg: Float, d: Float) = Pos(px(r, deg), py(r, deg), d)
    fun deg(x: Float, y: Float): Float = ((Math.toDegrees(atan2((x - C).toDouble(), (C - y).toDouble())) + 360) % 360).toFloat()
    fun rad(x: Float, y: Float): Float = hypot(x - C, y - C)

    /** From [a] to [b] around the centre (radius and angle interpolated, the short way), a radial [bulge] at mid-way. */
    fun travel(a: Pos, b: Pos, p: Float, bulge: Float = 0f): Pos {
        val aa = deg(a.x, a.y); val ab = deg(b.x, b.y)
        val da = ((ab - aa + 540f) % 360f) - 180f
        val r = Ez.lerp(rad(a.x, a.y), rad(b.x, b.y), p) + bulge * sin(PI.toFloat() * p)
        return at(r, aa + da * p, Ez.lerp(a.d, b.d, p))
    }

    /** The speed track's clock angle for [f] of it. */
    fun vDeg(f: Float): Float = -135f + 270f * f.coerceIn(0f, 1f)
}

/** Which lamp goes where: the strongest main, the next two in the «smile», more in «+N», the quiet ones by the battery. */
class Assign(val slots: Map<String, Slot>, val n: Int, val mode: Int, val plus: Int, val plusLv: LampLevel?) {
    fun slot(id: String): Slot = slots[id] ?: Slot.DOT

    companion object {
        /** [TellTales.top]'s order: the level, then the IDS order (an unknown id first, as top() has it). */
        val SEVERITY: Comparator<Lamp> = compareByDescending<Lamp> { it.level.ordinal }.thenBy { TellTales.IDS.indexOf(it.id) }

        fun of(lit: Collection<Lamp>): Assign {
            val slots = HashMap<String, Slot>()
            val ids = lit.map { it.id }.toSet()
            if (TellTales.SERVICE in ids) slots[TellTales.SERVICE] = Slot.QR
            if (TellTales.ECO in ids) slots[TellTales.ECO] = Slot.QL
            if (TellTales.WEAK in ids) slots[TellTales.WEAK] = if (TellTales.ECO in ids) Slot.QR2 else Slot.QL
            val al = lit.filter { it.id !in TellTales.QUIET && it.level != LampLevel.OFF }.sortedWith(SEVERITY)
            val n = al.size
            if (n >= 1) slots[al[0].id] = Slot.HERO
            if (n == 2) slots[al[1].id] = Slot.B
            if (n >= 3) { slots[al[1].id] = Slot.A; slots[al[2].id] = Slot.C }
            var plusLv: LampLevel? = null
            for (i in 3 until n) {
                slots[al[i].id] = Slot.HIDDEN
                if (plusLv == null || al[i].level > plusLv) plusLv = al[i].level
            }
            val mode = if (n == 0) 0 else if (al[0].level == LampLevel.RED) 2 else 1
            return Assign(slots, n, mode, max(0, n - 3), plusLv)
        }
    }
}

/**
 * What the face tells apart of a lamp: a change of these moves or restyles its chip; a value alone rolls, and so does a
 * detail with a number in it (the overspeed lamp's other unit, «28,6 км/ч»: live, it changes with every message).
 */
data class LampKey(val level: LampLevel, val title: String, val detail: String?, val rising: Boolean) {
    companion object {
        fun of(l: Lamp?) = l?.let { LampKey(it.level, it.title, it.detail?.takeIf { d -> d.none(Char::isDigit) }, it.rising) }
    }
}

/**
 * A lit set as the face shows it from [t] on (it came at [arrived]; later when queued): [lit] by id (IDS order), its
 * [assign], since when each lamp has had its level ([levelSince]: a red one's breathing, which amber a buzz is about)
 * and its ▲ ([riseSince]).
 */
class Snap(val t: Long, lit: List<Lamp>, val levelSince: Map<String, Long>, val riseSince: Map<String, Long>, val arrived: Long = t) {
    val lit: LinkedHashMap<String, Lamp> = LinkedHashMap<String, Lamp>().apply { for (l in lit) put(l.id, l) }
    val assign: Assign = Assign.of(lit)
    val key: List<Pair<String, LampKey>> = lit.map { it.id to LampKey.of(it)!! }
    /** The lamps that are news (not [TellTales.QUIET]): only their changes are queued; a standing one just fades. */
    val alertKey: List<Pair<String, LampKey>> = key.filter { it.first !in TellTales.QUIET }
    /** When the latest change of [alertKey] up to this set showed. */
    var alertT: Long = t
        internal set
    fun keyOf(id: String): LampKey? = LampKey.of(lit[id])
}

/** A displayed text's last change: [from] → [to] at [atMs], [gapMs] after the change before it; values for the direction. */
class TextChange(val from: String?, val to: String?, val atMs: Long, val gapMs: Long, val fromV: Double?, val toV: Double?)

/**
 * A lamp buzz as the face draws it: at [atMs] (the motor's [realMs]; one that came with its change, not after the chip's
 * landing — the phone's previews, a replay — goes as the chip lands), about lamp [id] (null: none to draw it at).
 */
class Buzzed(val atMs: Long, val level: LampLevel, val id: String?, val realMs: Long = atMs)

/**
 * What [FaceStack] has shown, fed with every [DialState] in time order (one per face instance; a state older than the
 * last one starts it again — a new scenario, a new screen). Its first state seeds it from [DialLamps.before] /
 * [DialLamps.changedMs], so a face switched to mid-change (or a shot) still shows that change.
 */
class StackTimeline {
    val snaps = ArrayList<Snap>()
    private var last: DialState? = null
    /** The startup's (the bulb check's) start, and whether it is the full one (the gauge opened standing). */
    var bootAt: Long? = null
        private set
    var bootFull: Boolean = true
        private set
    private val values = HashMap<String, TextChange>()
    var battery: TextChange? = null
        private set
    var toneFrom: Long? = null
        private set
    var toneAt: Long = Long.MIN_VALUE / 4
        private set
    private var tone: Long? = null
    val buzzes = ArrayList<Buzzed>()
    var rumbleOn: Long? = null
        private set
    var rumbleOff: Long? = null
        private set
    var chargeSince: Long? = null
        private set
    /** fitHero's results (their text and width do not change while a lamp stays). */
    internal val fits = object : LinkedHashMap<String, HeroFit>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HeroFit>?) = size > 32
    }

    fun reset() {
        snaps.clear(); last = null; bootAt = null; bootFull = true; values.clear(); battery = null; toneFrom = null
        toneAt = Long.MIN_VALUE / 4; tone = null; buzzes.clear(); rumbleOn = null; rumbleOff = null; chargeSince = null
    }

    fun value(id: String): TextChange? = values[id]

    fun feed(s: DialState) {
        if (s === last) return
        val prev = last
        if (prev != null && s.nowMs < prev.nowMs) reset()
        val now = s.nowMs
        s.bulbAtMs?.let { at ->
            if (at != bootAt) {
                bootAt = at
                bootFull = !(s.live && (s.telemetry.speedKmh ?: 0.0) >= Stack.BOOT_STANDING_KMH)
            }
        }
        val lit = s.lamps.lit.map { it.lamp }
        if (snaps.isEmpty()) seed(s, lit) else show(lit, now)
        for (l in lit) {
            val v = values[l.id]
            if (v == null) values[l.id] = TextChange(null, l.valueText, NEVER, NEVER, null, l.value)
            else if (v.to != l.valueText) values[l.id] = TextChange(v.to, l.valueText, now, now - v.atMs, v.toV, l.value)
        }
        val b = battery
        val bt = s.battery.text
        if (b == null) battery = TextChange(null, bt, NEVER, NEVER, null, s.battery.pct)
        else if (b.to != bt) battery = TextChange(b.to, bt, now, now - b.atMs, b.toV, s.battery.pct)
        val tn = s.battery.toneArgb
        if (tone != null && tn != tone) { toneFrom = tone; toneAt = now }
        tone = tn
        s.cues.buzz?.let { z ->
            if (buzzes.none { it.realMs == z.atMs }) {
                // about the set that had come by then; one that came with that set's change waits for the landing
                val sn = snaps.lastOrNull { it.arrived <= z.atMs } ?: snaps.last()
                val id = buzzLamp(z, sn)
                val early = id != null && sn.t != NEVER && z.atMs - sn.arrived in 0..EARLY_MS && sn.assign.slot(id).visible
                buzzes += Buzzed(if (early) max(z.atMs, sn.t + Stack.LAND_MS) else z.atMs, z.level, id, z.atMs)
                if (buzzes.size > 4) buzzes.removeAt(0)
            }
        }
        val on = s.cues.rumble && s.live
        if (on && (rumbleOn == null || rumbleOff != null)) { rumbleOn = s.cues.rumbleSinceMs ?: now; rumbleOff = null }
        if (!on && rumbleOn != null && rumbleOff == null) rumbleOff = now
        chargeSince = if (s.battery.charging) chargeSince ?: now else null
        last = s
    }

    /** The first state: what it changed from and when, its lamps' history from the builder's. */
    private fun seed(s: DialState, lit: List<Lamp>) {
        val before = s.lamps.before.map { TellTales.display(it, s.units) }
        val at = s.lamps.changedMs
        val levels = HashMap<String, Long>()
        val rises = HashMap<String, Long>()
        for (l in s.lamps.lit) {
            levels[l.id] = if (l.before != null && l.before.level != l.level) l.changedMs else l.sinceMs
            if (l.lamp.rising) rises[l.id] = if (l.before?.rising == true) l.sinceMs else l.changedMs
        }
        val key = lit.map { it.id to LampKey.of(it)!! }
        if (at != null && before.map { it.id to LampKey.of(it)!! } != key) {
            val s0 = Snap(NEVER, before, emptyMap(), emptyMap())
            snaps += s0
            snaps += Snap(hold(at), lit, levels, rises, at).also { if (it.alertKey == s0.alertKey) it.alertT = NEVER }
        } else snaps += Snap(NEVER, lit, levels, rises)
    }

    /** No sooner than the startup's lamp part ends. */
    private fun hold(t: Long): Long = bootAt?.let { b -> if (t < b + Stack.BOOT_END) b + Stack.BOOT_END else t } ?: t

    /**
     * A new state's lamps: the same set — fresh values; another — shown [Stack.QGAP] after the last change of the lamps
     * that are news at the soonest (a standing lamp's own fade overlaps nothing: it is not held back, nor holds back).
     */
    private fun show(lit: List<Lamp>, now: Long) {
        val key = lit.map { it.id to LampKey.of(it)!! }
        val top = snaps.last()
        if (key == top.key) { for (l in lit) top.lit[l.id] = l; return }
        if (top.t > now && snaps.size >= 2) {
            // the queued one has not shown yet: it becomes this (or goes, when this is what shows now)
            snaps.removeAt(snaps.size - 1)
            val under = snaps.last()
            if (key == under.key) { for (l in lit) under.lit[l.id] = l; return }
            snaps += next(under, top.t, lit, now)
            return
        }
        val news = key.filter { it.first !in TellTales.QUIET } != top.alertKey
        snaps += next(top, hold(if (news) max(now, max(top.t, top.alertT + Stack.QGAP)) else max(now, top.t)), lit, now)
        while (snaps.size > KEEP) snaps.removeAt(0)
    }

    private fun next(prev: Snap, t: Long, lit: List<Lamp>, arrived: Long): Snap {
        val levels = HashMap<String, Long>()
        val rises = HashMap<String, Long>()
        for (l in lit) {
            val p = prev.lit[l.id]
            levels[l.id] = if (p != null && p.level == l.level) prev.levelSince[l.id] ?: t else t
            if (l.rising) rises[l.id] = if (p?.rising == true) prev.riseSince[l.id] ?: t else t
        }
        return Snap(t, lit, levels, rises, arrived).also { if (it.alertKey == prev.alertKey) it.alertT = prev.alertT }
    }

    /** Which lamp a buzz is about: red — the strongest red; amber — the amber that rose last. */
    private fun buzzLamp(z: LampBuzz, sn: Snap): String? {
        val cands = sn.lit.values.filter { it.level == z.level && it.id !in TellTales.QUIET }
        if (cands.isEmpty()) return null
        if (z.level == LampLevel.RED) return cands.sortedWith(Assign.SEVERITY).first().id
        return cands.sortedWith(compareByDescending<Lamp> { sn.levelSince[it.id] ?: NEVER }.then(Assign.SEVERITY)).first().id
    }

    /** The snapshot on screen at [t]: the last one started by then. */
    fun index(t: Long): Int {
        var k = 0
        for (i in snaps.indices) if (snaps[i].t <= t) k = i
        return k
    }

    /** Lamp [id]'s latest change up to snapshot [k] (slot or [LampKey]), as the motion spec's events count from it. */
    fun lampAt(id: String, t: Long, k: Int): LampAt {
        fun slot(i: Int) = snaps[i].assign.slot(id)
        for (i in k downTo 1) {
            if (slot(i) == slot(i - 1) && snaps[i].keyOf(id) == snaps[i - 1].keyOf(id)) continue
            val lNew = snaps[i].lit[id]
            val lOld = snaps[i - 1].lit[id]
            return LampAt(slot(i - 1), slot(i), t - snaps[i].t, snaps[i].t, lNew ?: lOld, lNew, lOld, snaps[i - 1].assign.mode, snaps[i].assign.mode)
        }
        val l = snaps[k].lit[id]
        return LampAt(slot(k), slot(k), FAR, NEVER, l, l, l, snaps[k].assign.mode, snaps[k].assign.mode)
    }

    /** The last change of [what] up to snapshot [k]: before, after, ms since, and the two snapshots. */
    fun changeOf(t: Long, k: Int, what: (Snap) -> Int): Change {
        for (i in k downTo 1) if (what(snaps[i]) != what(snaps[i - 1])) return Change(what(snaps[i - 1]), what(snaps[i]), t - snaps[i].t, snaps[i - 1], snaps[i])
        return Change(what(snaps[k]), what(snaps[k]), FAR, snaps[k], snaps[k])
    }

    /**
     * When the face next moves after [nowMs] ([rememberFaceClock]): now while anything runs (a change ≤ 1 s, a buzz's
     * rings, red breathing, the rumble, a tap, a roll, the startup, the charging light), later for the next ▲ hop or
     * charging light, null when all is still. Ambient: never.
     */
    fun wakeAt(s: DialState, nowMs: Long, calm: Boolean): Long? {
        if (s.ambient || snaps.isEmpty()) return null
        val wakes = ArrayList<Long>(4)
        val k = index(nowMs)
        val top = snaps.last()
        if (top.t > nowMs) wakes += top.t
        if (nowMs < snaps[k].t + 1_000) return nowMs
        bootAt?.let { if (nowMs < it + Stack.BOOT_TOTAL) return nowMs }
        buzzes.lastOrNull()?.let { if (nowMs < it.atMs + 680 + Stack.RING_LIFE + 600) return nowMs }
        if (!calm && snaps[k].lit.values.any { it.level == LampLevel.RED && it.id !in TellTales.QUIET }) return nowMs
        if (s.cues.rumble && s.live) return nowMs
        rumbleOff?.let { if (nowMs < it + Stack.WAVE_LIFE) return nowMs }
        // the tap: its flash and ring, then still until its line under the battery fades
        s.cues.tapAtMs?.let { tap ->
            val out = tap + Stack.TAP_LINE - 350
            if (nowMs < tap + Stack.TAP_GLOW || nowMs in out..tap + Stack.TAP_LINE + 20) return nowMs
            if (nowMs < out) wakes += out
        }
        if (nowMs < toneAt + Stack.TONE) return nowMs
        battery?.let { if (it.gapMs >= Stack.ROLL_GAP && nowMs < it.atMs + Stack.rollMs(it.from, it.to)) return nowMs }
        // a value rolls only on the main lamp, and only after a still second (a live one changes too often)
        for ((id, v) in values) if (v.gapMs >= Stack.ROLL_GAP && snaps[k].assign.slot(id) == Slot.HERO &&
            nowMs < v.atMs + Stack.rollMs(v.from, v.to)) return nowMs
        if (!calm) chargeSince?.let { c ->
            val ph = Math.floorMod(nowMs - c, Stack.SHIMMER_PERIOD)
            if (ph in Stack.SHIMMER_AT..Stack.SHIMMER_AT + Stack.SHIMMER_D) return nowMs
            wakes += nowMs - ph + (if (ph < Stack.SHIMMER_AT) Stack.SHIMMER_AT else Stack.SHIMMER_PERIOD + Stack.SHIMMER_AT)
        }
        if (!calm) for ((id, since) in snaps[k].riseSince) {
            if (snaps[k].assign.slot(id).kind == Kind.STAND || !snaps[k].assign.slot(id).visible) continue
            val from = since + Stack.LAND_MS
            val ph = Math.floorMod(nowMs - from, FaceKit.BOB_EVERY_MS)
            if (nowMs >= from && ph <= FaceKit.BOB_MS) return nowMs
            wakes += if (nowMs < from) from else nowMs - ph + FaceKit.BOB_EVERY_MS
        }
        return wakes.minOrNull()
    }

    companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val FAR = Long.MAX_VALUE / 4
        private const val KEEP = 10
        /** A buzz this close after its change came with it (not after the landing, as the watch plays it). */
        private const val EARLY_MS = 50L
    }
}

/** One lamp's latest change: slot [from] → [to] [e] ms ago (at [tc]); its lamp before / after (null: not lit there). */
class LampAt(
    val from: Slot, val to: Slot, val e: Long, val tc: Long, val l: Lamp?, val lNew: Lamp?, val lOld: Lamp?, val mFrom: Int, val mTo: Int,
)

/** A change of something about the whole set (the layout's mode, the «+N» count). */
class Change(val a: Int, val b: Int, val e: Long, val before: Snap, val after: Snap)

/** The main lamp's text block as it fits: title [lines] at [s], the value at [vz] (0: none), the sub at [sz], [inline]. */
class HeroFit(val lines: List<String>, val s: Float, val vz: Float, val sz: Float, val inline: Boolean, val h: Float, val title: String, val value: String?, val sub: String?)

/** A changed number rolling: from [from] since [e] ms, up ([dir] 1) or down. */
class Roll(val from: String, val e: Long, val dir: Int)

/** What [FaceStack] draws in one frame (mock px). */
sealed interface El

/** A lamp chip at ([x], [y]) [d] across: [kind]'s style, [bright] the red's breathing, [pie] a colour sweep's share (null: whole). */
class ChipEl(
    val lamp: Lamp, val x: Float, val y: Float, val d: Float, val kind: Kind, val alpha: Float, val bright: Float = 1f,
    val pie: Float? = null, val pieR: Float = 0f,
) : El

/** A plain disc (the comet tail). */
class DiscEl(val x: Float, val y: Float, val r: Float, val argb: Long, val alpha: Float) : El

/** A ring [r] in radius, [w] wide. */
class RingEl(val x: Float, val y: Float, val r: Float, val w: Float, val argb: Long, val alpha: Float) : El

/** The ▲ «быстро греется» on a chip [d] across, lifted by [bob]. */
class RiseEl(val x: Float, val y: Float, val d: Float, val level: LampLevel, val bob: Float) : El

/** The green ✓ of a red that cleared: [size] square, drawn on to [trim]. */
class CheckEl(val x: Float, val y: Float, val size: Float, val trim: Float, val alpha: Float) : El

/** «+N» at ([x], [y]) [d] across in [level]'s ring. */
class PlusEl(val x: Float, val y: Float, val d: Float, val level: LampLevel, val text: String, val alpha: Float, val roll: Roll?) : El

/** A text line: [baseline] at [x] ([anchor]), [px], [weight], [halo] px of black under it, a [roll] of its changed glyphs. */
class TextEl(
    val text: String, val x: Float, val baseline: Float, val px: Float, val weight: Int, val argb: Long, val alpha: Float = 1f,
    val anchor: Anchor = Anchor.CENTER, val tnum: Boolean = false, val halo: Float = 0f, val roll: Roll? = null,
) : El

/** The speed track's moving parts. */
class TrackEl(
    val ticks: List<Float>, val limitDeg: Float?, val limitW: Float, val atLimiter: Boolean, val tapGlow: Float?, val rumbleDeg: Float?,
    val passedDeg: Float?, val dotX: Float, val dotY: Float, val dotR: Float, val label: String?, val blue: Boolean,
    /** The rumble's string from [rumbleDeg] to the dot: its motor envelope (null: none) and phase time. */
    val stringEnv: Float?, val stringT: Long, val waves: List<RingEl>, val glow: Boolean, val tapRing: RingEl?, val calmRing: RingEl?,
)

/** A lamp's dot in the arc's gap. */
class DotEl(val x: Float, val y: Float, val r: Float, val argb: Long, val alpha: Float, val outline: Boolean)

/** The battery box [b], its fill to [pct] in [tone], the % [text] (rolling), the charging light at [charge] (0…1). */
class BatteryEl(val b: StackGeo.Bat, val pct: Double?, val tone: Long, val text: String, val roll: Roll?, val charge: Float?, val alpha: Float)

/**
 * One frame. [face]: the startup's fade; [values]: the health's dimming (all but [status]; not the startup's [sweep] of
 * the tracks nor its lamp [wave] and dots); [centre]: the bulb check's dim.
 */
class StackScene(
    val face: Float, val values: Float, val powerDeg: Float, val track: TrackEl, val dots: List<DotEl>, val centre: Float,
    val battery: BatteryEl, val lines: List<TextEl>, val stand: List<El>, val sec: List<El>, val hero: List<El>, val fx: List<El>,
    val text: List<El>, val status: List<TextEl>, val ambient: Boolean, val lowBit: Boolean, val calm: Boolean,
    val sweep: Boolean = false, val wave: Boolean = false, val dotsFull: Boolean = false,
) {
    val all: List<El> get() = stand + sec + hero + fx + text

    companion object {
        private val BULB = TellTales.bulbCheck().associateBy { it.id }
        private const val NB = TellTales.NBSP

        /**
         * The frame of [s] at [t] on [tl]'s history: [calm] the reduced variant (no flight, no springs, no rolls, no
         * breathing), [tw] the text widths; [powerF] / [speedF] the arc's and the dot's values as drawn (springs), null
         * speed: none.
         */
        fun build(s: DialState, tl: StackTimeline, t: Long, calm: Boolean, tw: TextWidth, powerF: Float, speedF: Float?): StackScene {
            if (tl.snaps.isEmpty()) tl.feed(s)
            return Frame(s, tl, t, calm, tw).build(powerF, speedF)
        }

        /** The main lamp's text: the title 38 → 26 px (one line, else two), the value 32, the sub 21; ≤ [maxH] tall. */
        fun fitHero(l: Lamp, w: Float, maxH: Float, amb: Boolean, tw: TextWidth): HeroFit {
            val tW = if (amb) 500 else 700
            val vW = if (amb) 500 else 700
            val sW = if (amb) 400 else 500
            val title = l.title.ifBlank { TellTales.TITLES[l.id] ?: l.id }
            // what is too long even at its smallest size (a newer phone's lamp) ends in «…»
            val value = l.valueText?.takeIf { it.isNotBlank() }?.let { ellipsize(it, 20f, vW, true, w, tw) }
            val sub = l.detail?.takeIf { it.isNotBlank() }?.let { ellipsize(it, 16f, sW, false, w, tw) }
            val top = if (amb) 28 else 38
            val low = if (amb) 22 else 26
            fun fit(text: String, size: Float, min: Float, weight: Int, tnum: Boolean): Float {
                var z = size
                while (z > min && tw.width(text, z, weight, tnum) > w) z -= 0.5f
                return z
            }
            for (si in top downTo low) {
                var s = si.toFloat()
                val lines = if (tw.width(title, s, tW, false) <= w) listOf(title) else split2(title, s, w, tW, tw) ?: if (si > low) continue else {
                    // one word wider than the block even at the smallest step: that line smaller still
                    s = fit(title, s, 14f, tW, false)
                    listOf(title)
                }
                val vz = if (value != null) fit(value, min(if (amb) 24f else 32f, max(s, 24f)), 20f, vW, true) else 0f
                val sz = if (sub != null) fit(sub, if (amb) 18f else 21f, 16f, sW, false) else 0f
                val h = lines.size * s * 1.04f + (if (vz > 0) 7 + vz * 0.9f else 0f) + (if (sz > 0) 6 + sz * 0.9f else 0f)
                if (h <= maxH) return HeroFit(lines, s, vz, sz, false, h, title, value, sub)
                // too tall: the sub goes on the value's line, smaller («18,1 mph  29,1 км/ч»), before the title shrinks
                if (vz > 0 && sz > 0) {
                    val z2 = if (amb) 16f else 18f
                    var v2 = min(if (amb) 24f else 32f, max(s, 24f))
                    while (v2 >= 24f) {
                        if (tw.width(value!!, v2, vW, true) + 8 + tw.width(sub!!, z2, sW, false) <= w) {
                            val h2 = lines.size * s * 1.04f + 7 + v2 * 0.9f
                            if (h2 <= maxH) return HeroFit(lines, s, v2, z2, true, h2, title, value, sub)
                            break
                        }
                        v2 -= 0.5f
                    }
                }
                if (si == low) return HeroFit(lines, s, vz, sz, false, h, title, value, sub)
            }
            return HeroFit(listOf(title), low.toFloat(), 0f, 0f, false, low * 1.04f, title, null, null)
        }

        /** [text] cut to fit [w] at [px] with «…» (as it is when it fits). */
        fun ellipsize(text: String, px: Float, weight: Int, tnum: Boolean, w: Float, tw: TextWidth): String {
            if (tw.width(text, px, weight, tnum) <= w) return text
            var n = text.length
            while (n > 1 && tw.width(text.substring(0, n).trimEnd() + "…", px, weight, tnum) > w) n--
            return text.substring(0, n).trimEnd() + "…"
        }

        /** [text] in two lines at [px], the break that keeps the wider one narrowest and within [w]; null when none fits. */
        fun split2(text: String, px: Float, w: Float, weight: Int, tw: TextWidth): List<String>? {
            val words = text.split(' ')
            var best: List<String>? = null
            var bestW = Float.MAX_VALUE
            for (i in 1 until words.size) {
                val a = words.subList(0, i).joinToString(" ")
                val b = words.subList(i, words.size).joinToString(" ")
                val m = max(tw.width(a, px, weight, false), tw.width(b, px, weight, false))
                if (m <= w && m < bestW) { best = listOf(a, b); bestW = m }
            }
            return best
        }
    }

    /** One frame's build (stack2's frame(), with the state's data in place of the mock's). */
    private class Frame(val s: DialState, val tl: StackTimeline, val t: Long, val rm: Boolean, val tw: TextWidth) {
        val amb = s.ambient
        val lowBit = s.lowBit
        val live = s.live
        val k = if (amb) tl.snaps.size - 1 else tl.index(t)
        val cur = tl.snaps[k]
        val stand = ArrayList<El>()
        val sec = ArrayList<El>()
        val hero = ArrayList<El>()
        val fx = ArrayList<El>()
        val text = ArrayList<El>()
        // ambient: every change has ended (no motion), the newest set at once
        fun el(e: Long) = if (amb) StackTimeline.FAR else e

        fun build(powerF: Float, speedF: Float?): StackScene {
            // the startup (the gauge opened, the bulb check): the arc and the dot sweep, the fill rises, the lamps' wave
            val bt = tl.bootAt?.let { t - it }?.takeIf { !amb && it >= 0 && it < Stack.BOOT_TOTAL }
            val full = bt != null && tl.bootFull
            var sweep = 0f
            var faceA = 1f
            var fillK = 1f
            if (bt != null && full) {
                faceA = if (rm) 0.5f + 0.5f * Ez.c(bt.toFloat() / Stack.BOOT_FADE) else Ez.losi(bt.toFloat() / Stack.BOOT_FADE)
                if (!rm) {
                    sweep = when {
                        bt >= Stack.BOOT_UP0 && bt < Stack.BOOT_UP1 -> Ez.tween(bt, Stack.BOOT_UP0, Stack.BOOT_UP1 - Stack.BOOT_UP0)
                        bt >= Stack.BOOT_UP1 && bt < Stack.BOOT_DOWN0 -> 1f
                        bt >= Stack.BOOT_DOWN0 && bt < Stack.BOOT_DOWN1 -> 1 - Ez.tween(bt, Stack.BOOT_DOWN0, Stack.BOOT_DOWN1 - Stack.BOOT_DOWN0)
                        else -> 0f
                    }
                    fillK = Ez.tween(bt, Stack.BOOT_FILL0, Stack.BOOT_FILL1 - Stack.BOOT_FILL0)
                }
            }
            val bulb = bt != null && bt >= Stack.BOOT_WAVE0 && bt < Stack.BOOT_END
            val bulbFade = if (bt == null) 0f else Ez.sm(Stack.BOOT_WAVE0, Stack.BOOT_WAVE0 + 150, bt) * (1 - Ez.sm(Stack.BOOT_END - 300, Stack.BOOT_END, bt))

            // layout: 0 calm, 1 a lamp, 2 a red main lamp; a red main lamp cleared to calm waits for its ✓
            val mc = tl.changeOf(t, k) { it.assign.mode }.let { if (amb) Change(it.b, it.b, StackTimeline.FAR, it.after, it.after) else it }
            val toCalm = mc.a != 0 && mc.b == 0
            val hold = if (toCalm && mc.a == 2) Stack.CHECK2 else 0L
            val kB = if (rm) 1f else Ez.tween(mc.e, Stack.LAYOUT_DELAY + hold, Stack.LAYOUT)
            val swE = if (toCalm) max(Stack.OUT, hold) else 0L
            val rmNew = mc.e >= swE
            val rmMode = if (rmNew) mc.b else mc.a
            val rmA = if (rmNew && mc.e < 100_000_000L) 0.5f + 0.5f * Ez.sm(swE, swE + 200, mc.e) else 1f
            val calmA = if (rmMode == 0) rmA else 0f
            val alertA = if (rmMode != 0) rmA else 0f
            val kL = when {
                mc.a == 0 && mc.b == 0 -> 0f
                mc.a != 0 && mc.b != 0 -> 1f
                mc.b != 0 -> kB
                else -> 1 - kB
            }

            // the power arc (rests at zero unless live; the startup sweeps it to +max and back)
            val powerDeg = if (sweep > 0) 135f * sweep else if (live) powerF.coerceIn(-1f, 1f) * 135f else 0f

            // the speed track
            val sp = s.speed
            val track = track(sp, speedF, sweep, bt)

            // the dots
            val dots = dots(bt)

            // centre: battery + lines; calm ↔ alert
            val centre = if (bulb) 1 - 0.6f * bulbFade else 1f
            val battery = battery(mc, kB, rm, rmMode, rmA, bt, fillK)
            val lines = ArrayList<TextEl>()
            val tapAt = s.cues.tapAtMs?.takeIf { !amb && live && t >= it && t < it + Stack.TAP_LINE }
            var msg: TextEl? = null
            var msgK = 0f
            if (tapAt != null && sp.value != null) {
                msgK = Ez.sm(tapAt, tapAt + 300, t) * (1 - Ez.sm(tapAt + Stack.TAP_LINE - 350, tapAt + Stack.TAP_LINE, t))
                msg = TextEl("Ограничитель" + NB + "·" + NB + Fmt.withUnit(sp.value, 1, sp.unit), 240f, 326f, 19f, 600, Palette.INK, tnum = true)
            }
            if (bt != null && bulbFade > 0) { msgK = bulbFade; msg = TextEl(Dial.BULB_TEXT, 240f, 326f, 19f, 600, Palette.MUTED) }
            val clockOk = s.status == null && s.clock.isNotBlank() && !(bulb && bulbFade > 0.5f)
            val clockA = if (bulb) 1 - bulbFade else 1f
            val inkMuted = if (amb) Palette.AMB_DIM else Palette.MUTED
            val ink = if (amb) Palette.AMB_INK else Palette.INK
            val rangeInk = if (amb) Palette.AMB_INK else if (s.rangeRough) Palette.MUTED else Palette.INK
            fun group(kl: Float, a: Float, withMsg: Boolean) {
                if (clockOk) lines += fitted(s.clock, Ez.lerp(122f, 90f, kl), Ez.lerp(20f, 18f, kl), 400, inkMuted, a * clockA, 200f, true)
                s.range?.let { lines += fitted(it, Ez.lerp(262f, 180f, kl), Ez.lerp(26f, 22f, kl), 500, rangeInk, a, Ez.lerp(300f, 210f, kl), true) }
                if (s.details.isNotBlank()) lines += fitted(s.details, Ez.lerp(290f, 200f, kl), Ez.lerp(19f, 17f, kl), 400, inkMuted, a, Ez.lerp(300f, 236f, kl), true)
                if (withMsg && msg != null && kl < 0.99f) {
                    val op = if (rm) msgK else Ez.sm(0.5f, 1f, msgK) * (1 - kl)
                    lines += fitted(msg.text, msg.baseline, msg.px, msg.weight, if (amb) Palette.AMB_INK else msg.argb, a * op, 300f, msg.tnum)
                }
            }
            if (!rm) group(kL, 1f, true)
            else {
                if (calmA > 0.01f) group(0f, calmA, true)
                if (alertA > 0.01f) group(1f, alertA, false)
            }
            val pw = s.power.text
            val pwInk = if (amb) Palette.AMB_INK else s.power.argb
            val mk = if (msg == null) 0f else if (rm) (if (msgK > 0) 1f else 0f) else Ez.sm(0f, 0.5f, msgK)
            val calmY = Ez.lerp(330f, 352f, mk)
            val alertY = 350f
            if (rm) {
                if (calmA > 0.01f) lines += fitted(pw, calmY, 20f, 400, pwInk, calmA, 240f, true)
                if (alertA > 0.01f) lines += fitted(pw, alertY, 20f, 400, pwInk, alertA, 140f, true)
            } else {
                val yA = if (mc.a == 0) calmY else alertY
                val yB = if (mc.b == 0) calmY else alertY
                val wA = if (mc.a == 0) 240f else 140f
                val wB = if (mc.b == 0) 240f else 140f
                lines += fitted(pw, Ez.lerp(yA, yB, kB), 20f, 400, pwInk, 1f, Ez.lerp(wA, wB, kB), true)
            }

            // the lamps
            if (!bulb) lamps(mc, kB, rmMode, rmA, bt)
            else wave(bt!!)

            // the status line: what is wrong with the data, where the clock is (not dimmed)
            val status = ArrayList<TextEl>()
            s.status?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { ls ->
                val ya = if (ls.size > 1) 80f else 84f
                val y0 = if (rm) (if (mc.b == 0) 116f else ya) else Ez.lerp(116f, ya, kL)
                val lh = 18f
                val tone = if (amb) Palette.AMB_INK else s.health.tone
                ls.forEachIndexed { i, ln -> status += fitted(ln, y0 + 6 - (ls.size - 1) * lh / 2 + i * lh, 17f, 500, tone, 1f, 220f, false) }
            }
            // the startup is the watch's own: at full strength even on old data (the values stay dimmed)
            return StackScene(
                faceA, s.health.alpha, powerDeg, track, dots, centre, battery, lines, stand, sec, hero, fx, text, status, amb, lowBit, rm,
                sweep = sweep > 0, wave = bulb, dotsFull = bt != null,
            )
        }

        /** A centred line, smaller when wider than [maxW]. */
        fun fitted(str: String, y: Float, px: Float, weight: Int, argb: Long, alpha: Float, maxW: Float, tnum: Boolean): TextEl {
            val w = tw.width(str, px, weight, tnum)
            val z = if (w > maxW && w > 0) px * maxW / w else px
            return TextEl(str, 240f, y, z, weight, argb, alpha, Anchor.CENTER, tnum)
        }

        fun track(sp: DialSpeed, speedF: Float?, sweep: Float, bt: Long?): TrackEl {
            val r = StackGeo.RS
            val ticks = ArrayList<Float>()
            if (!amb) {
                var v = 0.0
                while (v <= sp.max + 1e-9) { ticks += StackGeo.vDeg(sp.fraction(v) ?: 0f); v += Dial.TICK_EVERY }
            }
            val tapE = s.cues.tapAtMs?.takeIf { !amb }?.let { t - it } ?: -1L
            val tapOn = Ez.half(tapE, Stack.TAP_ON)
            val atLim = live && !amb && sp.atLimiter && sweep == 0f
            val limitDeg = sp.fraction(sp.limit)?.let { StackGeo.vDeg(it) }
            val flash = if (rm) (if (tapE in 0 until Stack.TAP_ON) 1f else 0f) else tapOn
            val limitW = (if (atLim) 5f else 3f) + 3f * flash
            val tapGlow = if (tapE in 0 until Stack.TAP_GLOW && !rm) tapE.toFloat() / Stack.TAP_GLOW else null
            val rumbleDeg = if (amb) null else sp.fraction(sp.rumbleAt)?.let { StackGeo.vDeg(it) }
            val f = when {
                sweep > 0 -> max(speedF ?: 0f, sweep)
                else -> speedF
            }
            val on = tl.rumbleOn
            val off = tl.rumbleOff ?: StackTimeline.FAR
            val rOn = live && !amb && on != null && t >= on && t < off && s.cues.rumble
            var env = 0f
            if (rOn) {
                val arm = on!! + Stack.REARM * ((t - on) / Stack.REARM)
                env = motor(Math.floorMod(t - arm, Stack.RUMBLE_PERIOD))
            }
            if (f == null) return TrackEl(ticks, limitDeg, limitW, atLim, tapGlow, rumbleDeg, null, 0f, 0f, 0f, null, false, null, t, emptyList(), false, null, null)
            val va = StackGeo.vDeg(f)
            var dx = StackGeo.px(r, va)
            var dy = StackGeo.py(r, va)
            if (rOn && !rm) {
                dx += 1.6f * env * sin(t / 1000f * 2 * PI.toFloat() * 23)
                dy += 1.3f * env * cos(t / 1000f * 2 * PI.toFloat() * 19)
            }
            val waves = ArrayList<RingEl>()
            var calmRing: RingEl? = null
            if (!amb && on != null && !rm) for (c0 in cycles(on, off, t, Stack.WAVE_LIFE)) {
                val q = (t - c0).toFloat() / Stack.WAVE_LIFE
                waves += RingEl(dx, dy, 10 + 16 * Ez.losi(q), 2.5f - q, Palette.LAMP_BLUE, 0.75f * (1 - q))
            }
            if (rOn && rm) calmRing = RingEl(dx, dy, 17f, 2f, Palette.LAMP_BLUE, 0.6f)
            var tapRing: RingEl? = null
            if (tapE in 0..Stack.TAP_RING) {
                val q = tapE.toFloat() / Stack.TAP_RING
                tapRing = if (rm) RingEl(dx, dy, 18f, 2f, 0xFFFFFFFF, 1 - q) else RingEl(dx, dy, 10 + 30 * Ez.losi(q), 3 - 2 * q, 0xFFFFFFFF, 0.85f * (1 - q))
            }
            val shown = if (sweep > 0) max(sp.value ?: 0.0, sp.max * sweep) else sp.value
            val label = shown?.let { Math.round(max(0.0, it)).toString() }
            val dr = 9f * (1 + (if (rm) 0f else Stack.TAP_KICK * tapOn))
            val stringOn = rOn && sp.value != null && sp.rumbleAt != null && sp.value > sp.rumbleAt && rumbleDeg != null
            return TrackEl(
                ticks, limitDeg, limitW, atLim, tapGlow, rumbleDeg, if ((shown ?: 0.0) > 0.05) va else null, dx, dy, dr, label, rOn,
                if (stringOn) (if (rm) 1f else env) else null, if (rm) 0L else t, waves, rOn, tapRing, calmRing,
            )
        }

        /** The rumble's motor at [ph] ms into its 600-ms cycle: 500 on, 100 off (floor 0,3, 30-ms ramps). */
        fun motor(ph: Long): Float =
            if (ph < Stack.RUMBLE_ON) (if (ph < Stack.MOTOR_RAMP) Ez.lerp(Stack.MOTOR_FLOOR, 1f, ph.toFloat() / Stack.MOTOR_RAMP) else 1f)
            else Ez.lerp(1f, Stack.MOTOR_FLOOR, Ez.c((ph - Stack.RUMBLE_ON).toFloat() / Stack.MOTOR_RAMP))

        /** The rumble's motor cycles started in (t − life, t]: from each re-arm (every 2 s), then every 600 ms. */
        fun cycles(on: Long, off: Long, t: Long, life: Long): List<Long> {
            val out = ArrayList<Long>(3)
            if (t < on) return out
            var a = on + max(0L, (t - life - on) / Stack.REARM - 1) * Stack.REARM
            while (a <= t && a < off) {
                var c = a
                while (c < min(a + Stack.REARM, off) && c <= t) { if (c > t - life) out += c; c += Stack.RUMBLE_PERIOD }
                a += Stack.REARM
            }
            return out
        }

        fun dots(bt: Long?): List<DotEl> {
            val out = ArrayList<DotEl>(12)
            val bulb = bt != null && bt >= Stack.BOOT_WAVE0 && bt < Stack.BOOT_END
            TellTales.IDS.forEachIndexed { i, id ->
                val deg = StackGeo.dotDeg(id)
                val x = StackGeo.px(StackGeo.RP, deg)
                val y = StackGeo.py(StackGeo.RP, deg)
                val l = if (bulb) null else cur.lit[id]
                val quiet = id in TellTales.QUIET
                var c = Palette.LAMP_OFF
                var op = 1f
                var r = 3.5f
                if (l != null) { c = Lamps.argb(l.level); op = if (quiet) 0.6f else 1f; r = 5f }
                if (amb) { if (l != null) out += DotEl(x, y, 4f, if (lowBit) Palette.AMB_INK else c, 1f, true); return@forEachIndexed }
                if (!rm && !bulb && !quiet) {
                    val st = tl.lampAt(id, t, k)
                    // the bead leaves its dot: a pop; a lamp going out: lit until its bead is back, then the dot takes it in
                    if (st.l != null && st.from == Slot.DOT && st.to.visible && st.e < Stack.LAUNCH) r = 5 + 3 * sin(PI.toFloat() * st.e / Stack.LAUNCH)
                    val old = st.lOld
                    if (st.to == Slot.DOT && st.from.visible && old != null) {
                        val lc = Lamps.argb(old.level)
                        if (st.e < Stack.OFF1) { c = lc; op = 1f; r = 5f }
                        else if (st.e < Stack.OFF2) {
                            val q = Ez.fosi((st.e - Stack.OFF1).toFloat() / (Stack.OFF2 - Stack.OFF1))
                            c = Ez.mix(lc, Palette.LAMP_OFF, q); r = Ez.lerp(7.5f, 3.5f, q); op = 1f
                        }
                    }
                }
                if (bt != null) {
                    // startup: dim in their bulb colours until each lamp flies home, then lit and fading to what they are
                    val land = Stack.BOOT_FLY0 + (if (rm) 0 else i * Stack.BOOT_FLY_STEP) + Stack.BOOT_FLY
                    val bc = Lamps.argb(BULB.getValue(id).level)
                    val real = cur.lit[id]
                    if (bt >= Stack.BOOT_WAVE0 && bt < land) { c = bc; op = 0.35f; r = 3.5f }
                    else if (bt >= land && bt < land + Stack.BOOT_DOT_FADE) {
                        val q = Ez.fosi((bt - land).toFloat() / Stack.BOOT_DOT_FADE)
                        c = Ez.mix(bc, if (real != null) Lamps.argb(real.level) else Palette.LAMP_OFF, q)
                        op = Ez.lerp(0.95f, if (real != null) (if (quiet) 0.6f else 1f) else 1f, q)
                        r = Ez.lerp(6f, if (real != null) 5f else 3.5f, q)
                    }
                }
                out += DotEl(x, y, r, c, op, false)
            }
            return out
        }

        fun battery(mc: Change, kB: Float, rm: Boolean, rmMode: Int, rmA: Float, bt: Long?, fillK: Float): BatteryEl {
            var tone = s.battery.toneArgb
            val tf = tl.toneFrom
            if (!rm && !amb && tf != null && t - tl.toneAt in 0 until Stack.TONE) tone = Ez.mix(tf, tone, (t - tl.toneAt).toFloat() / Stack.TONE)
            if (amb && lowBit) tone = Palette.AMB_INK
            var roll: Roll? = null
            val b = tl.battery
            if (!rm && !amb && bt == null && b != null && b.from != null && b.to == s.battery.text && b.gapMs >= Stack.ROLL_GAP && t - b.atMs in 0 until Stack.rollMs(b.from, b.to)) {
                roll = Roll(b.from, t - b.atMs, if ((b.toV ?: 0.0) >= (b.fromV ?: 0.0)) 1 else -1)
            }
            val charge = if (!rm && !amb && s.battery.charging) tl.chargeSince?.let { c ->
                ((Math.floorMod(t - c, Stack.SHIMMER_PERIOD) - Stack.SHIMMER_AT).toFloat() / Stack.SHIMMER_D).takeIf { it > 0f && it < 1f }
            } else null
            val box = if (rm) StackGeo.BAT[rmMode] else StackGeo.BAT[mc.a].lerp(StackGeo.BAT[mc.b], kB)
            return BatteryEl(box, s.battery.pct?.times(fillK), tone, s.battery.text, roll, charge, if (rm) rmA else 1f)
        }

        fun slotPos(sl: Slot, l: Lamp, mFrom: Boolean, mTo: Boolean, kk: Float): Pos? = when (sl) {
            Slot.DOT -> StackGeo.at(StackGeo.RP, StackGeo.dotDeg(l.id), 10f)
            Slot.HIDDEN -> null
            Slot.HERO -> StackGeo.hero(l.level)
            Slot.QL, Slot.QR, Slot.QR2 -> {
                val a = StackGeo.stand(sl, mFrom)
                val b = StackGeo.stand(sl, mTo)
                if (kk <= 0f) a else if (kk >= 1f) b else StackGeo.travel(a, b, Ez.fosi(kk))
            }
            else -> StackGeo.smile(sl)
        }

        /** A new lamp: a bead out of its dot along the power arc, then straight in while it blooms on a spring. */
        fun beadIn(a: Pos, b: Pos, e: Long, level: LampLevel): Pos {
            val aa = StackGeo.deg(a.x, a.y); val ab = StackGeo.deg(b.x, b.y)
            val da = ((ab - aa + 540f) % 360f) - 180f
            if (e < Stack.BEAD) {
                val q = e.toFloat() / Stack.BEAD
                return StackGeo.at(StackGeo.RP, aa + da * Ez.fosi(q), Ez.lerp(10f, 16f, Ez.c(q / 0.3f)))
            }
            val q = e - Stack.BEAD
            val spr = if (level >= LampLevel.AMBER) Stack.ALARM else Stack.INFO
            return StackGeo.at(Ez.lerp(StackGeo.RP, StackGeo.rad(b.x, b.y), Ez.losi(q / 200f)), ab, 16 + (b.d - 16) * Ez.spring(q, spr))
        }

        /** A lamp going out: shrinks to a bead straight out to the arc, then along it into its dot. */
        fun beadOut(a: Pos, b: Pos, e: Long): Pos {
            val aa = StackGeo.deg(a.x, a.y); val ab = StackGeo.deg(b.x, b.y)
            val da = ((ab - aa + 540f) % 360f) - 180f
            if (e < Stack.OFF0) {
                val q = Ez.foli(e.toFloat() / Stack.OFF0)
                return StackGeo.at(Ez.lerp(StackGeo.rad(a.x, a.y), StackGeo.RP, q), aa, Ez.lerp(a.d, 16f, q))
            }
            val q = Ez.fosi((e - Stack.OFF0).toFloat() / (Stack.OFF1 - Stack.OFF0))
            return StackGeo.at(StackGeo.RP, aa + da * q, Ez.lerp(16f, 10f, q))
        }

        fun layer(kd: Kind): ArrayList<El> = when (kd) { Kind.HERO -> hero; Kind.STAND -> stand; Kind.SEC -> sec }

        /** The lamps in the set's slots, moving between them (stack2's lamp section). */
        fun lamps(mc: Change, kB: Float, rmMode: Int, rmA: Float, bt: Long?) {
            val buzzNow = tl.buzzes.filter { !amb && t >= it.atMs && t < it.atMs + 680 + Stack.RING_LIFE + 600 }
            val ids = LinkedHashSet<String>()
            ids += TellTales.IDS.filter { id -> tl.snaps.subList(max(0, k - 1), k + 1).any { it.lit.containsKey(id) } }
            for (i in max(0, k - 1)..k) ids += tl.snaps[i].lit.keys
            val heroTaken = cur.assign.slots.values.contains(Slot.HERO)
            // after the startup's wave, the lamps that stayed come back in place
            val after = if (bt != null && bt >= Stack.BOOT_END) Ez.sm(Stack.BOOT_END, Stack.BOOT_END + 200, bt) else 1f
            for (id in ids) {
                val st0 = tl.lampAt(id, t, k)
                val st = if (amb) LampAt(st0.to, st0.to, StackTimeline.FAR, st0.tc, st0.lNew ?: st0.lOld, st0.lNew, st0.lNew, st0.mTo, st0.mTo) else st0
                if (st.l == null) continue
                if (st.from == Slot.DOT && st.to == Slot.DOT) continue
                val e = el(st.e)
                val lNow = st.lNew ?: st.lOld!!
                val lOld = st.lOld ?: lNow
                val mF = mc.a != 0
                val mT = mc.b != 0
                val pA = slotPos(st.from, lOld, mF, mT, kB)
                val pB = slotPos(st.to, lNow, mF, mT, kB)
                val qA = if (st.from.kind == Kind.STAND && st.from.visible) slotPos(st.from, lNow, mT, mT, 1f) else pA
                val quiet = id in TellTales.QUIET
                val both = st.lOld != null && st.lNew != null && st.from.visible && st.to.visible
                val stepUp = both && st.lNew!!.level > st.lOld!!.level
                val stepDown = both && st.lNew!!.level < st.lOld!!.level
                // the buzz that belongs to this lamp: its kicks (a half sine per motor pulse, +5 %) and the red breathing
                var kick = 0f
                if (!rm && !amb) for (b in buzzNow) if (b.id == id) {
                    val red = b.level == LampLevel.RED
                    for (p0 in if (red) Stack.RED_PULSES else longArrayOf(0)) kick += Stack.KICK * Ez.half(t - b.atMs - p0, if (red) Stack.RED_ON else Stack.AMBER_ON)
                }
                var bright = 1f
                val curL = cur.lit[id]
                if (lNow.level == LampLevel.RED && !quiet && !rm && !amb && curL?.level == LampLevel.RED) bright = breath(id)
                val static = st.from == st.to
                val put = { pos: Pos?, kd: Kind, alpha: Float, l: Lamp, noKick: Boolean, pie: Float?, ly: Kind? ->
                    if (pos != null && alpha > 0.005f && pos.d >= 1f) {
                        val d = pos.d * (1 + if (noKick) 0f else kick)
                        val a = alpha * (if (static) after else 1f)
                        layer(ly ?: kd) += ChipEl(l, pos.x, pos.y, d, kd, a, bright, pie, d / 2 + 16)
                        if (l.rising && d > 20 && kd != Kind.STAND && pie == null && a > 0.5f) {
                            // ▲: two hops when it lands, then once every 10 s (a new value or level does not count)
                            val rs = if (curL?.rising == true) cur.riseSince[id] ?: st.tc else st.tc
                            val since = t - (rs + if (rm) 350 else Stack.LAND_MS)
                            val ph = Math.floorMod(since, FaceKit.BOB_EVERY_MS)
                            val bob = if (rm || amb || since < 0 || ph > FaceKit.BOB_MS) 0f else 4f * abs(sin(PI.toFloat() * ph / 450f))
                            layer(ly ?: kd) += RiseEl(pos.x, pos.y, d, l.level, bob)
                        }
                        if (id == TellTales.OVERSPEED && kd == Kind.HERO && !amb && d > 60 && pie == null) rumbleRings(pos, d, a, ly ?: kd)
                    }
                }
                if (static) {
                    when {
                        rm && st.to.kind == Kind.STAND -> put(slotPos(st.to, lNow, rmMode != 0, rmMode != 0, 1f), Kind.STAND, rmA, lNow, false, null, null)
                        stepUp && !rm -> {
                            // a grade up in place: the new colour sweeps clockwise over the old chip (the main slot grows to red's)
                            val p = Ez.tween(e, Stack.SWEEP0, Stack.SWEEP1 - Stack.SWEEP0)
                            val a0 = slotPos(st.from, lOld, mF, mT, kB)!!
                            val pos = if (p > 0) StackGeo.travel(a0, pB!!, p) else a0
                            if (p < 1) put(pos, st.to.kind, 1f, lOld, false, null, null)
                            if (p > 0) put(pos, st.to.kind, 1f, lNow, false, p, null)
                        }
                        (stepUp || stepDown) && rm -> {
                            put(pB, st.to.kind, 1 - Ez.sm(150L, 350L, e), lOld, false, null, null)
                            put(pB, st.to.kind, Ez.sm(150L, 350L, e), lNow, false, null, null)
                        }
                        stepDown -> {
                            val a0 = slotPos(st.from, lOld, mF, mT, kB)!!
                            val q = Ez.c(e.toFloat() / Stack.FADE_DOWN)
                            val pos = StackGeo.travel(a0, pB!!, Ez.fosi(q))
                            put(pos, st.to.kind, 1 - q, lOld, false, null, null)
                            put(pos, st.to.kind, q, lNow, false, null, null)
                        }
                        else -> put(pB, st.to.kind, 1f, lNow, false, null, null)
                    }
                } else if (rm) {
                    // calmer: no flight, the old place fades out in 150 ms, the new one in at 150–350 ms
                    if (st.from.visible) put(qA, st.from.kind, 1 - Ez.c(e.toFloat() / Stack.OUT), lOld, false, null, null)
                    if (st.to.visible) put(pB, st.to.kind, Ez.sm(150L, 350L, e), lNow, false, null, null)
                    if (st.to == Slot.DOT && lOld.level == LampLevel.RED && st.from == Slot.HERO && !heroTaken && pA != null) {
                        val fa = Ez.c((e - Stack.CHECK0).toFloat() / 150) * (1 - Ez.c((e - Stack.CHECK2).toFloat() / (Stack.CHECK3 - Stack.CHECK2)))
                        if (fa > 0) fx += CheckEl(pA.x, pA.y, pA.d * 0.7f, 1f, fa)
                    }
                } else if (quiet) {
                    // standing lamps come and go in place: a fade and a slight grow, no flight (they are not news)
                    val q = Ez.fosi(e.toFloat() / Stack.QUIET_FADE)
                    if (st.from.visible && qA != null) put(Pos(qA.x, qA.y, qA.d * Ez.lerp(1f, 0.8f, q)), Kind.STAND, 1 - q, lOld, false, null, null)
                    if (st.to.visible && pB != null) put(Pos(pB.x, pB.y, pB.d * Ez.lerp(0.8f, 1f, q)), Kind.STAND, q, lNow, false, null, null)
                } else if (st.from == Slot.DOT) {
                    if (st.to.visible && pA != null && pB != null) {
                        // a comet tail: the same flight 24 ms ago and earlier, fading, gone 60 ms after the arc part
                        if (e < Stack.BEAD + Stack.GHOST_FADE) Stack.GHOSTS.forEachIndexed { gi, ga ->
                            val eg = e - Stack.GHOST_LAG * (gi + 1)
                            if (eg >= 0 && eg < Stack.BEAD) {
                                val g = beadIn(pA, pB, eg, lNow.level)
                                val fade = if (e < Stack.BEAD) 1f else 1 - (e - Stack.BEAD).toFloat() / Stack.GHOST_FADE
                                layer(st.to.kind) += DiscEl(g.x, g.y, g.d / 2 * (1 - 0.12f * (gi + 1)), Lamps.argb(lNow.level), ga * fade)
                            }
                        }
                        put(beadIn(pA, pB, e, lNow.level), st.to.kind, 1f, lNow, e < Stack.LAND_MS - 40, null, null)
                    }
                } else if (st.to == Slot.DOT) {
                    if (pA != null && pB != null) {
                        if (e < Stack.OFF1) put(beadOut(pA, pB, e), st.from.kind, 1f, lOld, false, null, null)
                        if (lOld.level == LampLevel.RED && st.from == Slot.HERO && !heroTaken) {
                            // a red cleared and nothing takes its place: a green ✓ draws on there, holds, fades (no buzz: no ring)
                            val dr = Ez.losi((e - Stack.CHECK0).toFloat() / (Stack.CHECK1 - Stack.CHECK0))
                            val fa = 1 - Ez.c((e - Stack.CHECK2).toFloat() / (Stack.CHECK3 - Stack.CHECK2))
                            if (e >= Stack.CHECK0 && fa > 0) fx += CheckEl(pA.x, pA.y, pA.d * 0.7f, dr, fa)
                        }
                    }
                } else if (st.to == Slot.HIDDEN) {
                    if (pA != null) put(Pos(pA.x, pA.y, pA.d * (1 - Ez.fosi(e / 250f))), st.from.kind, 1 - Ez.sm(150L, 250L, e), lOld, false, null, null)
                } else if (st.from == Slot.HIDDEN) {
                    if (pB != null) put(Pos(pB.x, pB.y, pB.d * Ez.spring(e - 250, Stack.PLUS)), st.to.kind, Ez.sm(250L, 330L, e), lNow, false, null, null)
                } else if (pA != null && pB != null) {
                    // between visible slots: the promoted one passes inside and on top, the demoted one outside and below
                    val p = Ez.fosi(e.toFloat() / Stack.MOVE)
                    val bulge = if (st.to == Slot.HERO) Stack.BULGE_IN else if (st.from == Slot.HERO) Stack.BULGE_OUT else 0f
                    val kF = st.from.kind
                    val kT = st.to.kind
                    val ly = if (st.to == Slot.HERO) Kind.HERO else if (st.from == Slot.HERO) Kind.SEC else (if (p < 0.5f) kF else kT)
                    val tp = StackGeo.travel(pA, pB, p, bulge)
                    // the promoted one stays small until late, the demoted one shrinks early: they pass with less overlap
                    val pos = when {
                        st.to == Slot.HERO -> Pos(tp.x, tp.y, Ez.lerp(pA.d, pB.d, Ez.sm(0.4f, 1f, p)))
                        st.from == Slot.HERO -> Pos(tp.x, tp.y, Ez.lerp(pA.d, pB.d, Ez.sm(0f, 0.5f, p)))
                        else -> tp
                    }
                    // the chip's style (main: solid amber; smile: ring) cross-fades over the middle of the move
                    val w = if (kF == kT) 1f else Ez.sm(0.35f, 0.65f, p)
                    val two = { l: Lamp, pie: Float? ->
                        if (w < 1) put(pos, kF, 1 - w, l, false, pie, ly)
                        if (w > 0) put(pos, kT, w, l, false, pie, ly)
                    }
                    if (stepUp) {
                        val q = Ez.tween(e, Stack.SWEEP0, Stack.SWEEP1 - Stack.SWEEP0)
                        if (q < 1) two(lOld, null)
                        if (q > 0) two(lNow, q)
                    } else two(lNow, null)
                }
                // the words as at its change, the value as it is now (the newest set has the fresh ones)
                val live = cur.lit[id]?.takeIf { LampKey.of(it) == LampKey.of(lNow) } ?: lNow
                heroText(st, e, live, lOld, stepUp || stepDown)
            }
            rings(buzzNow)
            plus()
        }

        /** The red's brightness: 1 Hz from its buzz (the peak with its first pulse), calmer 8 s after it; a repeat re-phases. */
        fun breath(id: String): Float {
            val redT = cur.levelSince[id] ?: StackTimeline.NEVER
            val reds = tl.buzzes.filter { it.level == LampLevel.RED && it.atMs <= t && it.atMs >= redT }
            val ph0 = if (reds.isNotEmpty()) reds.last().atMs else redT + Stack.LAND_MS
            if (t < ph0) return 1f
            fun br(p0: Long, since: Long): Float {
                val dd = if (since < Stack.CALM_AFTER) Stack.DEPTH else Ez.lerp(Stack.DEPTH, Stack.DEPTH_CALM, Ez.fosi((since - Stack.CALM_AFTER).toFloat() / Stack.CALM_EASE))
                return 1 - dd * (0.5f - 0.5f * cos(2 * PI.toFloat() * Math.floorMod(t - p0, Stack.BREATH) / Stack.BREATH))
            }
            var b = br(ph0, t - ph0)
            val prev = if (reds.size > 1) reds[reds.size - 2].atMs else null
            if (prev != null && t - ph0 < Stack.REPHASE) b = Ez.lerp(br(prev, t - prev), b, (t - ph0).toFloat() / Stack.REPHASE)
            return b
        }

        /** The rumble's waves around the blue main chip, one per motor cycle (no further than +13 px: the speed track). */
        fun rumbleRings(pos: Pos, d: Float, alpha: Float, ly: Kind) {
            val on = tl.rumbleOn ?: return
            val off = tl.rumbleOff ?: StackTimeline.FAR
            if (rm) {
                if (s.cues.rumble && live) layer(ly) += RingEl(pos.x, pos.y, d / 2 + 7, 2f, Palette.LAMP_BLUE, alpha * 0.6f)
                return
            }
            for (c0 in cycles(on, off, t, Stack.WAVE_LIFE)) {
                val q = (t - c0).toFloat() / Stack.WAVE_LIFE
                layer(ly) += RingEl(pos.x, pos.y, d / 2 + 3 + 10 * Ez.losi(q), 3 - 1.5f * q, Palette.LAMP_BLUE, alpha * 0.85f * (1 - q))
            }
        }

        /** The rings: one per motor pulse of a real buzz (amber 1, red 3), round the chip it is about, at its slot. */
        fun rings(buzzNow: List<Buzzed>) {
            if (amb) return
            for (b in buzzNow) {
                val id = b.id ?: continue
                val lamp = cur.lit[id] ?: continue
                val slot = cur.assign.slot(id)
                if (!slot.visible || slot.kind == Kind.STAND) continue
                val p = slotPos(slot, lamp, cur.assign.mode != 0, cur.assign.mode != 0, 1f) ?: continue
                val c = Lamps.argb(b.level)
                val pulses = if (b.level == LampLevel.RED) Stack.RED_PULSES else longArrayOf(0)
                pulses.forEachIndexed { j, p0 ->
                    val e = t - b.atMs - p0
                    if (rm) {
                        val q = (t - b.atMs).toFloat() / 1200f
                        if (e >= 0 && q <= 1) fx += RingEl(p.x, p.y, p.d / 2 + 6 + 6 * j, 2f, c, 0.7f * (1 - q))
                        return@forEachIndexed
                    }
                    if (e < 0 || e > Stack.RING_LIFE) return@forEachIndexed
                    val q = e.toFloat() / Stack.RING_LIFE
                    fx += RingEl(p.x, p.y, p.d / 2 + 4 + Stack.RING_GROW * Ez.losi(q), 4 - 3 * q, c, 0.8f * (1 - q))
                }
            }
        }

        /** «+N»: the lamps that did not fit. */
        fun plus() {
            val pc = tl.changeOf(t, k) { it.assign.plus }.let { if (amb) Change(it.b, it.b, StackTimeline.FAR, it.after, it.after) else it }
            val n = cur.assign.plus
            val shownN = if (n > 0) n else pc.a
            val scale = if (n > 0) (if (pc.a > 0 || rm) 1f else Ez.spring(pc.e - 420, Stack.PLUS)) else (if (pc.a > 0) 1 - Ez.fosi(pc.e / 200f) else 0f)
            val pa = if (rm && n > 0 && pc.a == 0) Ez.sm(150L, 350L, pc.e) else 1f
            if (shownN <= 0 || scale <= 0.01f) return
            val lv = (if (n > 0) cur.assign.plusLv else pc.before.assign.plusLv) ?: LampLevel.AMBER
            val roll = if (!rm && !amb && n > 0 && pc.a > 0 && pc.a != n && pc.e < Stack.rollMs("+" + pc.a, "+$n")) Roll("+" + pc.a, pc.e, if (n > pc.a) 1 else -1) else null
            sec += PlusEl(StackGeo.SMILE_B.x, StackGeo.SMILE_B.y, StackGeo.PLUS_D * scale, lv, "+$shownN", pa, roll)
        }

        /** The main lamp's text: the old out in 150 ms (back into its chip), the new in at 380 / 420 / 460 ms, 200 ms each. */
        fun heroText(st: LampAt, e: Long, lNow: Lamp, lOld: Lamp, step: Boolean) {
            val still = floatArrayOf(1f, 0f)
            fun inParts(step: Boolean): List<FloatArray> = (if (step) Stack.TXT_STEP else Stack.TXT).map { t0 ->
                if (rm) floatArrayOf(Ez.sm(250L, 450L, e), 0f)
                else Ez.losi((e - t0).toFloat() / Stack.TXT_D).let { q -> floatArrayOf(q, -Stack.TXT_DX * (1 - q)) }
            }
            val out = floatArrayOf(1 - Ez.c(e.toFloat() / Stack.OUT), if (rm) 0f else -Stack.OUT_DX * Ez.foli(e.toFloat() / Stack.OUT))
            val outs = listOf(out, out, out)
            val stills = listOf(still, still, still)
            when {
                st.from == Slot.HERO && st.to == Slot.HERO -> {
                    val changed = st.lOld != null && st.lNew != null && LampKey.of(st.lOld) != LampKey.of(st.lNew)
                    if (changed && e < 700) { block(lOld, outs, null); block(lNow, inParts(step), null) }
                    else block(lNow, stills, roll(lNow, st))
                }
                st.to == Slot.HERO -> block(lNow, inParts(false), if (e >= 700) roll(lNow, st) else null)
                st.from == Slot.HERO -> block(lOld, outs, null)
            }
        }

        /** The main lamp's value rolling when it changed (and the change before was ≥ 1 s earlier); never the live speed. */
        fun roll(l: Lamp, st: LampAt): Roll? {
            if (rm || amb || l.id == TellTales.OVERSPEED) return null
            val v = tl.value(l.id) ?: return null
            val from = v.from ?: return null
            val to = v.to ?: return null
            val e = t - v.atMs
            if (to != l.valueText || v.gapMs < Stack.ROLL_GAP || e < 0 || e >= Stack.rollMs(from, to)) return null
            if (st.tc >= v.atMs) return null
            return Roll(from, e, if ((v.toV ?: 0.0) >= (v.fromV ?: 0.0)) 1 else -1)
        }

        /** [l]'s title / value / sub right of the main chip, each part with its (alpha, dx). */
        fun block(l: Lamp, parts: List<FloatArray>, roll: Roll?) {
            val h = StackGeo.hero(l.level)
            val red = l.level == LampLevel.RED
            val x0 = h.x + h.d / 2 + (if (red) 12 else 14)
            val w = StackGeo.TEXT_R - x0
            val key = "${l.title}|${l.valueText}|${l.detail}|$red|$amb"
            val ft = tl.fits.getOrPut(key) { fitHero(l, w, if (amb) 104f else 112f, amb, tw) }
            val lh = ft.s * 1.04f
            var y = h.y - ft.h / 2
            val (pt, pv, ps) = parts
            val title = if (amb && lowBit) Palette.AMB_INK else titleArgb(l.level)
            if (pt[0] > 0.01f) ft.lines.forEachIndexed { i, ln ->
                text += TextEl(ln, x0 + pt[1], y + ft.s * 0.8f + i * lh, ft.s, if (amb) 500 else 700, title, pt[0], Anchor.START)
            }
            y += ft.lines.size * lh
            val value = ft.value
            val sub = ft.sub
            val vInk = if (amb) Palette.AMB_INK else 0xFFFFFFFF
            val sInk = if (amb) Palette.AMB_DIM else Palette.INK2
            if (ft.inline && value != null && sub != null) {
                val vy = y + 7 + ft.vz * 0.74f
                if (pv[0] > 0.01f) {
                    text += TextEl(value, x0 + pv[1], vy, ft.vz, if (amb) 500 else 700, vInk, pv[0], Anchor.START, true, roll = roll)
                    text += TextEl(sub, x0 + pv[1] + tw.width(value, ft.vz, if (amb) 500 else 700, true) + 8, vy, ft.sz, if (amb) 400 else 500, sInk, pv[0], Anchor.START)
                }
            } else {
                if (ft.vz > 0 && value != null) {
                    if (pv[0] > 0.01f) text += TextEl(value, x0 + pv[1], y + 7 + ft.vz * 0.74f, ft.vz, if (amb) 500 else 700, vInk, pv[0], Anchor.START, true, roll = roll)
                    y += 7 + ft.vz * 0.9f
                }
                if (ft.sz > 0 && sub != null && ps[0] > 0.01f) text += TextEl(sub, x0 + ps[1], y + 6 + ft.sz * 0.74f, ft.sz, if (amb) 400 else 500, sInk, ps[0], Anchor.START)
            }
        }

        /** The startup's wave: all 12 pop around the battery clockwise 45 ms apart, hold, then fly home into their dots. */
        fun wave(bt: Long) {
            TellTales.IDS.forEachIndexed { i, id ->
                val ti = Stack.BOOT_WAVE0 + (if (rm) 0 else i * Stack.BOOT_WAVE_STEP)
                val pe = bt - ti
                if (pe <= 0) return@forEachIndexed
                val ring = StackGeo.at(150f, i * 30f, 54f)
                val dot = StackGeo.at(StackGeo.RP, StackGeo.dotDeg(id), 10f)
                val fs = Stack.BOOT_FLY0 + i * Stack.BOOT_FLY_STEP
                val q = if (rm) 0f else Ez.fosi((bt - fs).toFloat() / Stack.BOOT_FLY)
                if (q >= 1f) return@forEachIndexed
                val tp = StackGeo.travel(ring, dot, q)
                val d = if (rm) tp.d else if (q > 0) Ez.lerp(54f, 10f, q) else 54f * Ez.spring(pe, Stack.POP)
                val alpha = if (rm) Ez.c(pe / 150f) * (1 - Ez.sm(Stack.BOOT_END - 250, Stack.BOOT_END, bt)) else 1f
                val l = BULB.getValue(id)
                hero += ChipEl(l, tp.x, tp.y, d, if (FaceKit.solid(l.level)) Kind.HERO else Kind.SEC, alpha)
            }
        }
    }
}

/** A lamp's title colour: red, blue and green lifted (#ff3b30 text is the darkest at night). */
internal fun titleArgb(level: LampLevel): Long = when (level) {
    LampLevel.RED -> FaceKit.TITLE_RED
    LampLevel.BLUE -> FaceKit.TITLE_BLUE
    LampLevel.GREEN -> FaceKit.TITLE_GREEN
    else -> Lamps.argb(level)
}
