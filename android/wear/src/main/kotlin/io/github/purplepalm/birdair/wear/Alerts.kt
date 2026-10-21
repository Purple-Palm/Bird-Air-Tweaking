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
        /** A lamp turned red (and every [LampAlarm.RED_REPEAT_MS] while one stays red): three long pulses at full strength. */
        val RED = Buzz(LampLevel.RED, longArrayOf(0, 230, 110, 230, 110, 230), intArrayOf(0, 255, 0, 255, 0, 255))
    }
}

/**
 * When the lamps vibrate. A lamp that rises to AMBER (from off, green, blue or yellow) taps once — the same lamp again
 * only after [amberCooldownMs]; a lamp that turns RED buzzes hard at once and then every [redRepeatMs] while any lamp
 * stays red; a red that comes back within [redRepeatMs] of the last red buzz waits for the repeat. Two buzzes are at
 * least [gapMs] apart: several lamps at once give one buzz (the strongest), and a lamp covered by a buzz (or rising
 * within the gap after one) counts as buzzed. Standing lamps ([TellTales.QUIET]: service, weak, ECO), falling levels,
 * YELLOW / GREEN / BLUE, old data (not live) and a bulb check never buzz. Clock: the watch's elapsedRealtime.
 */
class LampAlarm(
    val amberCooldownMs: Long = AMBER_COOLDOWN_MS,
    val redRepeatMs: Long = RED_REPEAT_MS,
    val gapMs: Long = GAP_MS,
) {
    private var levels: Map<String, LampLevel> = emptyMap()
    private val amberAt = HashMap<String, Long>()
    private var redAt: Long? = null
    private var redBuzzed: Set<String> = emptySet()
    private var lastAt: Long? = null

    /** The buzz for this message's lamps, or null. */
    fun onMessage(lamps: List<Lamp>, live: Boolean, nowMs: Long): Buzz? {
        // a stale gap changes nothing: what stayed lit through it is not news afterwards
        if (!live || Cluster.isBulbCheck(lamps)) return null
        val now = lamps.filter { it.id !in TellTales.QUIET }.associate { it.id to it.level }
        val before = levels
        levels = now
        val reds = now.filterValues { it == LampLevel.RED }.keys
        val ambers = now.filter { (id, lvl) -> lvl == LampLevel.AMBER && (before[id] ?: LampLevel.OFF) < LampLevel.AMBER }.keys
        val gapOk = lastAt.let { it == null || nowMs - it >= gapMs }
        val repeat = redAt.let { it == null || nowMs - it >= redRepeatMs }
        val newRed = reds.any { it !in redBuzzed && before[it] != LampLevel.RED }
        val buzz = when {
            reds.isNotEmpty() && (repeat || (newRed && gapOk)) -> Buzz.RED
            gapOk && ambers.any { id -> amberAt[id].let { it == null || nowMs - it >= amberCooldownMs } } -> Buzz.AMBER
            else -> null
        }
        if (buzz != null || !gapOk) for (id in ambers) amberAt[id] = nowMs
        if (buzz == Buzz.RED) {
            redAt = nowMs
            redBuzzed = reds
        }
        if (buzz != null) lastAt = nowMs
        return buzz
    }

    companion object {
        const val AMBER_COOLDOWN_MS = 300_000L
        const val RED_REPEAT_MS = 30_000L
        const val GAP_MS = 5_000L
    }
}

/**
 * «Вибрация быстрее N mph»: the watch vibrates without a stop while the speed is above the threshold — a cue to brake
 * without looking down. On above the threshold, off below it − [hystMph]; off at once when disabled, without a speed or
 * when the data is not live (older than [Health.STALE_MS], the link not CONNECTED or not streaming). The motor runs a
 * repeating waveform ([TIMINGS] from [REPEAT]); while on it is armed again every [rearmMs]: the system cancels an app's
 * vibration when the screen is turned off by a gesture, and re-arming picks it up again.
 */
class OverspeedAlarm(val hystMph: Double = HYST_MPH, val rearmMs: Long = REARM_MS) {
    enum class Action { NONE, START, STOP }

    var on: Boolean = false
        private set
    private var armedAtMs = 0L

    /** [live]: [Health.LIVE] at [nowMs]. START also means «arm again». */
    fun update(enabled: Boolean, thresholdMph: Double, speedKmh: Double?, live: Boolean, nowMs: Long): Action {
        val mph = speedKmh?.takeIf { it.isFinite() }?.div(Fmt.MILE)
        val want = enabled && live && mph != null && thresholdMph.isFinite() &&
            (mph > thresholdMph || (on && mph >= thresholdMph - hystMph))
        val action = when {
            want && (!on || nowMs - armedAtMs >= rearmMs) -> Action.START.also { armedAtMs = nowMs }
            !want && on -> Action.STOP
            else -> Action.NONE
        }
        on = want
        return action
    }

    /** The service stops: STOP when it was on. */
    fun stop(): Action = if (on) Action.STOP.also { on = false } else Action.NONE

    companion object {
        const val HYST_MPH = 0.5
        const val REARM_MS = 2_000L
        /** 500 ms on, 100 ms off, repeated from [REPEAT]: a steady buzz with a beat in it, so it is not tuned out. */
        val TIMINGS = longArrayOf(0, 500, 100)
        val AMPLITUDES = intArrayOf(0, 255, 0)
        const val REPEAT = 1
    }
}

/** The overspeed threshold on the details page: mph, in [STEP] steps from [MIN] to [MAX]. */
object OverspeedPref {
    const val DEFAULT_MPH = 16.0
    const val MIN = 5.0
    const val MAX = 25.0
    const val STEP = 0.5
    /** InlineSlider's steps: the values between the two ends. */
    val STEPS: Int = Math.round((MAX - MIN) / STEP).toInt() - 1

    fun clamp(mph: Double): Double =
        if (!mph.isFinite()) DEFAULT_MPH else (Math.round(mph / STEP) * STEP).coerceIn(MIN, MAX)

    fun step(mph: Double, by: Int): Double = clamp(clamp(mph) + by * STEP)

    /** «16 mph», «16,5 mph». */
    fun mph(mph: Double): String = Fmt.withUnit(mph, if (mph % 1.0 == 0.0) 0 else 1, "mph")

    /** «16 mph · 25,7 км/ч» while the phone shows km/h, «16 mph» in mph. */
    fun describe(mph: Double, units: String): String =
        if (Fmt.mph(units)) mph(mph) else mph(mph) + " · " + Fmt.withUnit(mph * Fmt.MILE, 1, "км/ч")
}

