package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.WarningLevel

/* The phone's battery warnings, range and power choice as the watch shows them: pure, for the JVM tests. */

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
class Buzz private constructor(val level: WarningLevel, val timings: LongArray, val amplitudes: IntArray) {
    /** Total time the motor runs, ms. */
    val onMs: Long get() = timings.filterIndexed { i, _ -> amplitudes[i] > 0 }.sum()
    val pulses: Int get() = amplitudes.count { it > 0 }

    companion object {
        /** Two short taps: not like a notification's one long buzz. */
        val WARNING = Buzz(WarningLevel.WARNING, longArrayOf(0, 70, 110, 70), intArrayOf(0, 170, 0, 170))
        /** Three long pulses at full strength. */
        val CRITICAL = Buzz(WarningLevel.CRITICAL, longArrayOf(0, 230, 110, 230, 110, 230), intArrayOf(0, 255, 0, 255, 0, 255))

        fun of(level: WarningLevel): Buzz = if (level == WarningLevel.CRITICAL) CRITICAL else WARNING
    }
}

/**
 * When to vibrate for the phone's warnings. Something new must happen — a warning appears (an id not active in
 * the message before) or the level rises — and then it buzzes if the level is above the last buzz or the last buzz
 * is [cooldownMs] old: a warning that flickers on and off, or a second one at the same level right after the first,
 * stays quiet; «Сейчас отключится» right after «Заряд на исходе» does not. A warning that simply stays on buzzes
 * once. Only live messages count (old data says nothing new). Clock: the watch's elapsedRealtime.
 */
class WarningAlarm(val cooldownMs: Long = COOLDOWN_MS) {
    private var active: Map<String, WarningLevel> = emptyMap()
    private var buzzAtMs: Long? = null
    private var buzzLevel: WarningLevel? = null

    /** The buzz to play for this message, or null. */
    fun onMessage(ws: List<BatteryWarning>, live: Boolean, nowMs: Long): Buzz? {
        if (!live) return null
        val now = ws.associate { it.id to it.level }
        val before = active
        active = now
        val top = Warnings.level(ws) ?: return null
        val news = now.any { (id, lvl) -> before[id].let { it == null || lvl > it } }
        if (!news) return null
        val last = buzzAtMs
        val higher = buzzLevel.let { it == null || top > it }
        if (last != null && nowMs - last < cooldownMs && !higher) return null
        buzzAtMs = nowMs
        buzzLevel = top
        return Buzz.of(top)
    }

    companion object {
        const val COOLDOWN_MS = 120_000L
    }
}
