package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel

/* The phone's power choice, battery warnings and range as the dial shows them, and where the watch's two speed cues sit
 * (moved from the watch's Alerts.kt with the faces): pure, for the JVM tests. */

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
    /** The defaults, FLY's: the tap where the limiter cuts the current by a quarter, the rumble past its zero-current speed. */
    const val TAP_MPH = 15.5
    const val RUMBLE_MPH = 17.5
    /** The tap is armed again this far below its point (FLY). */
    const val TAP_REARM_MPH = 0.6
    /** The rumble stops this far below its point (FLY). */
    const val RUMBLE_HYST_MPH = 0.5

    fun scale(mode: Int?): Double = (TellTales.limitMph(mode) ?: FLY_LIMIT_MPH) / FLY_LIMIT_MPH

    fun tap(flyMph: Double, mode: Int?): CuePoint = scale(mode).let { k -> CuePoint(flyMph * k, (flyMph - TAP_REARM_MPH) * k) }

    fun rumble(flyMph: Double, mode: Int?): CuePoint = scale(mode).let { k -> CuePoint(flyMph * k, (flyMph - RUMBLE_HYST_MPH) * k) }
}
