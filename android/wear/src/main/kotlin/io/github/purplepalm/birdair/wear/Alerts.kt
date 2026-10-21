package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel

/* The phone's battery warnings, range and power choice as the watch shows them, and when the watch vibrates: pure,
 * for the JVM tests. */

/** Which power the gauge shows: the phone's fast estimate ([Telemetry.powerFastW]) when its «fast» flag is on. */
object PowerView {
    /** A phone that sends no flag but does send the fast value: use it (the better estimate, see FastPower). */
    fun fast(flags: PhoneFlags): Boolean = flags.fast ?: true

    fun watts(t: Telemetry, fast: Boolean): Double? = if (fast) t.powerFastW ?: t.powerW else t.powerW
    fun amps(t: Telemetry, fast: Boolean): Double? = if (fast) t.currentFastA ?: t.currentA else t.currentA

    /** Whether [watts] really is the fast value (the details page then also shows the BMS one). */
    fun usesFast(t: Telemetry, fast: Boolean): Boolean = fast && t.powerFastW != null
}

object Warnings {
    /** The one the gauge shows: CRITICAL before WARNING, the phone's order otherwise. */
    fun top(ws: List<BatteryWarning>): BatteryWarning? = ws.firstOrNull { it.level == WarningLevel.CRITICAL } ?: ws.firstOrNull()

    fun level(ws: List<BatteryWarning>): WarningLevel? = top(ws)?.level

    /** Fill / outline colour of the banner and the tinted arc track. */
    fun argb(level: WarningLevel): Long = if (level == WarningLevel.CRITICAL) Palette.CRIT else Palette.WARN

    /** Text colour on black. */
    fun ink(level: WarningLevel): Long = if (level == WarningLevel.CRITICAL) Palette.CRIT_INK else Palette.WARN

    /** The warning's text; the phone sends it, the advisor's own texts if it did not. */
    fun text(w: BatteryWarning): String =
        w.text.ifBlank { BatteryAdvisor.TEXTS[w.id to w.level] ?: if (w.level == WarningLevel.CRITICAL) "Батарея: опасно" else "Батарея" }

    /** "31,8 В под нагрузкой", "56 °C", or null. */
    fun valueText(w: BatteryWarning): String? {
        val v = w.value?.takeIf { it.isFinite() } ?: return null
        return when (w.id) {
            BatteryAdvisor.LOW -> Fmt.withUnit(v, 1, "В") + " под нагрузкой"
            BatteryAdvisor.HOT -> Fmt.withUnit(v, 0, "°C")
            else -> null
        }
    }

    /**
     * The battery's colour: the BMS % thresholds, but at least amber with «Заряд на исходе» and red with
     * «Сейчас отключится» — near the cutoff the BMS still says 11 % or more.
     */
    fun batteryTone(pct: Double?, ws: List<BatteryWarning>): BatteryTone {
        val byPct = Battery.tone(pct)
        val low = ws.filter { it.id == BatteryAdvisor.LOW }.maxOfOrNull { it.level } ?: return byPct
        val byWarning = if (low == WarningLevel.CRITICAL) BatteryTone.CRIT else BatteryTone.WARN
        return if (rank(byWarning) > rank(byPct)) byWarning else byPct
    }

    private fun rank(t: BatteryTone): Int = when (t) { BatteryTone.NONE -> 0; BatteryTone.GOOD -> 1; BatteryTone.WARN -> 2; BatteryTone.CRIT -> 3 }
}

/** «запас ≈4,2 км» under the battery: the advisor's range to the observed empty, in the phone's units. */
object RangeText {
    fun value(r: RangeEstimate?, units: String): String? {
        if (r == null) return null
        val d = if (Fmt.mph(units)) r.mi else r.km
        if (!d.isFinite() || d < 0) return null
        return "≈" + Fmt.withUnit(d, if (d < 10) 1 else 0, Fmt.distUnit(units))
    }

    fun line(r: RangeEstimate?, units: String): String? = value(r, units)?.let { "запас $it" }

    /** The default consumption (no ride yet to measure) is a rough guess: shown dimmer. */
    fun rough(r: RangeEstimate?): Boolean = r?.basis == "default"

    fun basis(r: RangeEstimate?): String = when (r?.basis) {
        "recent" -> "по последним 2 км"
        "session" -> "с начала поездки"
        "default" -> "расход не измерен"
        else -> Fmt.DASH
    }
}

/** A vibration: [timings] off/on/off/… in ms (starting with a pause), [amplitudes] 0–255 for each. */
class Buzz private constructor(val level: LampLevel, val timings: LongArray, val amplitudes: IntArray) {
    /** Total time the motor runs, ms. */
    val onMs: Long get() = timings.filterIndexed { i, _ -> amplitudes[i] > 0 }.sum()
    val pulses: Int get() = amplitudes.count { it > 0 }

    companion object {
        /** A lamp turned amber: one short tap. */
        val AMBER = Buzz(LampLevel.AMBER, longArrayOf(0, 110), intArrayOf(0, 200))
        /** A lamp turned red (and the [LampAlarm.RED_REPEATS_MS] repeats while one stays red): three long pulses at full strength. */
        val RED = Buzz(LampLevel.RED, longArrayOf(0, 230, 110, 230, 110, 230), intArrayOf(0, 255, 0, 255, 0, 255))
    }
}

/**
 * When the lamps vibrate. A lamp that rises to AMBER (from off, green, blue or yellow) taps once — the same lamp again
 * only after [amberCooldownMs]; a lamp that turns RED buzzes hard at once (after an amber tap without waiting for the
 * gap), then, while any lamp stays red, again [redRepeatsMs] after the previous red buzz (30 s, 2 min, 5 min) and no
 * more until a new red: not at standstill (below [movingKmh]; an overdue repeat waits until the ride goes on). A red
 * that comes back within [redForgetMs] of going out is a flicker, not news. Two buzzes are at least [gapMs] apart:
 * several lamps at once give one buzz (the strongest), and a lamp covered by a buzz (or rising within the gap after
 * one) counts as buzzed. Standing lamps ([TellTales.QUIET]: service, weak, ECO), the link lamp (it is lit only while the
 * data is not live, so it could only buzz late), falling levels, YELLOW / GREEN / BLUE, old data (not live) and a
 * bulb check never buzz. Clock: the watch's elapsedRealtime.
 */
class LampAlarm(
    val amberCooldownMs: Long = AMBER_COOLDOWN_MS,
    val redRepeatsMs: List<Long> = RED_REPEATS_MS,
    val redForgetMs: Long = RED_FORGET_MS,
    val gapMs: Long = GAP_MS,
    val movingKmh: Double = MOVING_KMH,
) {
    private var levels: Map<String, LampLevel> = emptyMap()
    private val amberAt = HashMap<String, Long>()
    // red lamps a buzz announced, and since when each is out (a short flicker keeps it announced)
    private val covered = HashSet<String>()
    private val redOffAt = HashMap<String, Long>()
    private var redAt: Long? = null
    private var repeats = 0
    private var lastAt: Long? = null
    private var lastRed = false

    /** The buzz for this message's lamps, or null. [speedKmh]: null when unknown (repeats are not held back then). */
    fun onMessage(lamps: List<Lamp>, live: Boolean, nowMs: Long, speedKmh: Double? = null): Buzz? {
        // a stale gap changes nothing: what stayed lit through it is not news afterwards
        if (!live || Cluster.isBulbCheck(lamps)) return null
        val now = lamps.filter { it.id !in SILENT }.associate { it.id to it.level }
        val before = levels
        levels = now
        val reds = now.filterValues { it == LampLevel.RED }.keys
        val gone = ArrayList<String>()
        for (id in covered) {
            if (id in reds) redOffAt.remove(id)
            else if (nowMs - redOffAt.getOrPut(id) { nowMs } >= redForgetMs) gone += id
        }
        for (id in gone) { covered.remove(id); redOffAt.remove(id) }
        val ambers = now.filter { (id, lvl) -> lvl == LampLevel.AMBER && (before[id] ?: LampLevel.OFF) < LampLevel.AMBER }.keys
        val gapOk = lastAt.let { it == null || nowMs - it >= gapMs }
        val news = reds.any { it !in covered }
        val moving = speedKmh == null || speedKmh.isNaN() || speedKmh >= movingKmh
        val repeatDue = redAt.let { at -> at != null && repeats < redRepeatsMs.size && nowMs - at >= redRepeatsMs[repeats] }
        val buzz = when {
            reds.isNotEmpty() && news && (gapOk || !lastRed) -> Buzz.RED
            reds.isNotEmpty() && repeatDue && moving && gapOk -> Buzz.RED
            gapOk && ambers.any { id -> amberAt[id].let { it == null || nowMs - it >= amberCooldownMs } } -> Buzz.AMBER
            else -> null
        }
        if (buzz != null || !gapOk) for (id in ambers) amberAt[id] = nowMs
        if (buzz == Buzz.RED) {
            repeats = if (news) 0 else repeats + 1
            redAt = nowMs
            covered += reds
        }
        if (buzz != null) {
            lastAt = nowMs
            lastRed = buzz == Buzz.RED
        }
        return buzz
    }

    companion object {
        const val AMBER_COOLDOWN_MS = 300_000L
        /** The red repeats, each after the previous red buzz; then quiet until a new red. */
        val RED_REPEATS_MS: List<Long> = listOf(30_000L, 120_000L, 300_000L)
        const val RED_FORGET_MS = 120_000L
        const val GAP_MS = 5_000L
        /** Below this the scooter stands (cooling off, as the overheat lamp asks): no red repeats. */
        const val MOVING_KMH = 3.0
        /** Lamps that never vibrate. */
        val SILENT: Set<String> = TellTales.QUIET + TellTales.LINK
    }
}

/**
 * One speed cue's points in the current mode, mph: [onMph] — the speed it starts above; [offMph] — the rumble stops
 * below it, the tap is armed again below it.
 */
data class CuePoint(val onMph: Double, val offMph: Double)

/**
 * Where the two speed cues sit. The settings are FLY's; FLY's limiter is a function of the speed (20.10 and every FLY
 * log): the current is 95 % of its plateau at 14.1 mph, 75 % at 15.4, 50 % at 16.9 and none from ≈17.2 mph, so the tap
 * goes at 15.5 (more throttle gives no more speed there) and the rumble at 17.5 (only a hill gets there: brake). The
 * other modes scale both and their hysteresis by the mode's limit (× [TellTales.limitMph] / 16: TAKEOFF 8.7 / 9.8 mph,
 * GLIDE 12.1 / 13.7; TAKEOFF's taper 8.7–9.5 mph); an unknown mode gets FLY's.
 */
object CuePoints {
    const val FLY_LIMIT_MPH = 16.0
    /** The tap is armed again this far below its point (FLY). */
    const val TAP_REARM_MPH = 0.6
    /** The rumble stops this far below its point (FLY). */
    const val RUMBLE_HYST_MPH = 0.5

    fun scale(mode: Int?): Double = (TellTales.limitMph(mode) ?: FLY_LIMIT_MPH) / FLY_LIMIT_MPH

    fun tap(flyMph: Double, mode: Int?): CuePoint = scale(mode).let { k -> CuePoint(flyMph * k, (flyMph - TAP_REARM_MPH) * k) }

    fun rumble(flyMph: Double, mode: Int?): CuePoint = scale(mode).let { k -> CuePoint(flyMph * k, (flyMph - RUMBLE_HYST_MPH) * k) }
}

/**
 * A condition held over fresh samples: true once a sample at least [ms] after the first one that met it meets it too,
 * every sample between meeting it — so it takes two updates, and a lone sample (or a timer looking at the same one
 * again) never confirms. [sampleAtMs]: when the sample came ([Feed.freshAtMs]).
 */
class Confirm(val ms: Long) {
    private var since: Long? = null

    fun reset() { since = null }

    fun update(c: Boolean, sampleAtMs: Long?): Boolean {
        if (!c || sampleAtMs == null) { since = null; return false }
        val s = since ?: sampleAtMs.also { since = it }
        return sampleAtMs - s >= ms
    }
}

/**
 * «Тап у ограничителя»: one short tap ([TIMINGS]) when the speed rises above the point where the controller starts
 * cutting the current ([CuePoints.tap], FLY 15.5 mph) — more throttle gives no more speed from there. It needs the
 * speed above the point for [confirmMs] over two fresh updates ([Confirm]); after a tap it is quiet until armed again,
 * and that only below [CuePoint.offMph] with at least the pause ([WatchPrefs.tapPauseMs], 60 / 120 s) passed since the
 * tap: cruising at the limiter gives one tap, not one per gust. After a change of mode ([hold]) it waits until the
 * speed has been under the new mode's [CuePoint.offMph]: switching FLY → TAKEOFF at 16 km/h to slow down is not «at the
 * limiter». Nothing happens (and nothing changes) while it is off or without live data. Clock: the watch's
 * elapsedRealtime.
 */
class LimiterTap(val confirmMs: Long = CONFIRM_MS) {
    var armed: Boolean = true
        private set
    var held: Boolean = false
        private set
    private var tapAtMs: Long? = null
    private val above = Confirm(confirmMs)

    /** The mode changed: its points moved under the speed. */
    fun hold() { held = true }

    /** True: tap now. [live]: as for [OverspeedAlarm.update]. */
    fun update(enabled: Boolean, point: CuePoint, pauseMs: Long, speedKmh: Double?, live: Boolean, sampleAtMs: Long?, nowMs: Long): Boolean {
        val mph = speedKmh?.takeIf { it.isFinite() }?.div(Fmt.MILE)
        if (!enabled || !live || mph == null || !point.onMph.isFinite()) { above.reset(); return false }
        if (mph < point.offMph) {
            held = false
            if (!armed && tapAtMs.let { it == null || nowMs - it >= pauseMs }) armed = true
        }
        if (!above.update(mph > point.onMph, sampleAtMs) || !armed || held) return false
        armed = false
        tapAtMs = nowMs
        return true
    }

    companion object {
        /** Two fresh updates at least this far apart (the dash sends ~15 a second): no tap on a lone reading. */
        const val CONFIRM_MS = 300L
        /** One firm tap, ~150 ms: unlike the amber lamp's lighter 110 ms. */
        val TIMINGS = longArrayOf(0, 150)
        val AMPLITUDES = intArrayOf(0, 255)
    }
}

/**
 * «Вибро быстрее ограничителя»: the watch vibrates without a stop while the speed is above the point ([CuePoints.rumble],
 * FLY 17.5 mph: past ≈17.2 the controller gives no current, only a hill gets there) — a cue to brake without looking
 * down. On once above it for [confirmMs] over two fresh updates ([Confirm]), off below [CuePoint.offMph]; off at once
 * when disabled, without a speed or when the data is not live (the link not CONNECTED or not streaming, or no new data
 * for [FRESH_MS]). After a change of mode ([hold]) it starts only once the speed has been under the new mode's off
 * point (16.10: FLY → TAKEOFF at 16.4 km/h to slow down buzzed 1 s); one running goes on. The motor runs a repeating
 * waveform ([TIMINGS] from [REPEAT]); while on it is armed again every [rearmMs]: the system cancels an app's
 * vibration when the screen is turned off by a gesture, and re-arming picks it up again.
 */
class OverspeedAlarm(val confirmMs: Long = CONFIRM_MS, val rearmMs: Long = REARM_MS) {
    enum class Action { NONE, START, STOP }

    var on: Boolean = false
        private set
    var held: Boolean = false
        private set
    private var armedAtMs = 0L
    private val above = Confirm(confirmMs)

    /** The mode changed: its points moved under the speed. */
    fun hold() { held = true }

    /** [live]: [Health.LIVE] and [fresh] at [nowMs]; [sampleAtMs]: when the speed came ([Feed.freshAtMs]). START also
     *  means «arm again». */
    fun update(enabled: Boolean, point: CuePoint, speedKmh: Double?, live: Boolean, sampleAtMs: Long?, nowMs: Long): Action {
        val mph = speedKmh?.takeIf { it.isFinite() }?.div(Fmt.MILE)
        val ok = enabled && live && mph != null && point.onMph.isFinite()
        if (ok && mph!! < point.offMph) held = false
        val over = ok && above.update(mph!! > point.onMph, sampleAtMs) && !held
        if (!ok) above.reset()
        val want = ok && (over || (on && mph!! >= point.offMph))
        val action = when {
            want && (!on || nowMs - armedAtMs >= rearmMs) -> Action.START.also { armedAtMs = nowMs }
            !want && on -> Action.STOP
            else -> Action.NONE
        }
        on = want
        return action
    }

    /** The service stops: STOP when it was on. */
    fun stop(): Action = if (on) Action.STOP.also { on = false; above.reset() } else Action.NONE

    companion object {
        /** Two fresh updates at least this far apart: a lone reading does not start it. */
        const val CONFIRM_MS = 300L
        const val REARM_MS = 2_000L
        /**
         * New data ([Feed.freshAtMs]: the phone's seq moved) within this: the speed is current. Riding, frames come ~3/s
         * and the phone sends each at once; the 3 s of [Health.STALE_MS] and of the phone's «streaming» are too slow
         * to stop a buzz that may no longer be true.
         */
        const val FRESH_MS = 1_500L

        fun fresh(freshAtMs: Long?, nowMs: Long): Boolean = freshAtMs != null && nowMs - freshAtMs <= FRESH_MS

        /** 500 ms on, 100 ms off, repeated from [REPEAT]: a steady buzz with a beat in it, so it is not tuned out. */
        val TIMINGS = longArrayOf(0, 500, 100)
        val AMPLITUDES = intArrayOf(0, 255, 0)
        const val REPEAT = 1
    }
}

/**
 * Both speed cues as the ride service runs them, on every message, setting change and timer: the rumble first, the
 * tap only while it is off (a tap would cut the rumble short). A new mode holds both ([OverspeedAlarm.hold],
 * [LimiterTap.hold]).
 */
class SpeedCues {
    val rumble = OverspeedAlarm()
    val tap = LimiterTap()
    private var mode: Int? = null

    data class Out(val rumble: OverspeedAlarm.Action, val tap: Boolean)

    fun update(p: WatchPrefs, t: Telemetry, live: Boolean, sampleAtMs: Long?, nowMs: Long): Out {
        t.mode?.let { m ->
            if (mode != null && m != mode) { rumble.hold(); tap.hold() }
            mode = m
        }
        val r = rumble.update(p.overspeedOn, CuePoints.rumble(p.overspeedMph, t.mode), t.speedKmh, live, sampleAtMs, nowMs)
        val tp = tap.update(p.tapOn, CuePoints.tap(p.tapMph, t.mode), p.tapPauseMs, t.speedKmh, live, sampleAtMs, nowMs)
        return Out(r, tp && !rumble.on)
    }

    fun stop(): OverspeedAlarm.Action = rumble.stop()
}

/** The cues' points on the details page: FLY's mph, in [STEP] steps from [MIN] to [MAX]. */
object SpeedPref {
    /** «Тап у ограничителя»: FLY's current is 75 % there (−25 %, felt in the hand). */
    const val TAP_MPH = 15.5
    /** «Вибро быстрее ограничителя»: past FLY's zero-current speed (≈17.2 mph). */
    const val RUMBLE_MPH = 17.5
    /** v107's rumble default: 159 s above it on 20.10, nearly all full-throttle cruising. */
    const val OLD_RUMBLE_MPH = 16.0
    const val MIN = 5.0
    const val MAX = 25.0
    const val STEP = 0.5
    /** InlineSlider's steps: the values between the two ends. */
    val STEPS: Int = Math.round((MAX - MIN) / STEP).toInt() - 1

    fun clamp(mph: Double, default: Double): Double =
        if (!mph.isFinite()) default else (Math.round(mph / STEP) * STEP).coerceIn(MIN, MAX)

    fun step(mph: Double, by: Int, default: Double): Double = clamp(clamp(mph, default) + by * STEP, default)

    /** «16 mph», «16,5 mph». */
    fun mph(mph: Double): String = Fmt.withUnit(mph, if (mph % 1.0 == 0.0) 0 else 1, "mph")

    /** «17,5 mph · 28,2 км/ч» while the phone shows km/h, «17,5 mph» in mph. */
    fun describe(mph: Double, units: String): String =
        if (Fmt.mph(units)) mph(mph) else mph(mph) + " · " + Fmt.withUnit(mph * Fmt.MILE, 1, "км/ч")

    /** The same point in the other modes: «TAKEOFF 9,8 · GLIDE 13,7 mph». */
    fun otherModes(mph: Double): String =
        "TAKEOFF " + Fmt.num(mph * CuePoints.scale(1), 1) + " · GLIDE " + Fmt.num(mph * CuePoints.scale(2), 1) + " mph"
}

/** The tap's pause: at least this long between two taps, a chip on the details page cycles the choices. */
object TapPause {
    val CHOICES_S = listOf(60, 120)
    const val DEFAULT_S = 60

    fun of(s: Int): Int = if (s in CHOICES_S) s else DEFAULT_S

    fun next(s: Int): Int = CHOICES_S[(CHOICES_S.indexOf(of(s)) + 1) % CHOICES_S.size]

    /** «не чаще раза в 60 с». */
    fun describe(s: Int): String = "не чаще раза в " + of(s) + " с"
}

/**
 * The stored settings across versions. [VERSION] 2: the two-stage speed cue. Up to v107 the rumble's threshold was
 * saved with every setting, at its default 16 mph unless moved: 16 becomes the new default 17.5, a value the owner
 * chose stays.
 */
object PrefsMigration {
    const val VERSION = 2

    /** [stored]: the saved rumble threshold, null when none was saved; [storedVersion]: 0 before versions were kept. */
    fun overspeedMph(storedVersion: Int, stored: Double?): Double = when {
        stored == null || !stored.isFinite() -> SpeedPref.RUMBLE_MPH
        storedVersion < 2 && stored == SpeedPref.OLD_RUMBLE_MPH -> SpeedPref.RUMBLE_MPH
        else -> SpeedPref.clamp(stored, SpeedPref.RUMBLE_MPH)
    }
}

