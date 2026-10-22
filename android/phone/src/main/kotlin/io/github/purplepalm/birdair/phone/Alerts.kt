package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel

/**
 * The voltage under load shown with «Заряд на исходе» / «Сейчас отключится»: the lowest voltage of the BMS frames of
 * the last [windowMs] at a current ≥ [loadA], the current taken from the fast estimate (it belongs to the same frame
 * as the voltage). The advisor's own value is the last voltage while the BMS current was ≥ 5 A, and the BMS current
 * lags ~0.6 s: on letting go of the throttle it pairs the rebounded voltage (35–36 V) with the old 10 A. Null when
 * there was no load in the window (then no number is shown).
 *
 * [decorate] takes one sample per BMS frame ([Telemetry.powerAtMs] moved), stamped with that frame's time. A snapshot
 * of a 3A 17 frame carries the last BMS frame's voltage and current: taken with the publish time, 20.10 after the BMS
 * cut the pack (the controller still sent 3A 17 for 5 s) and at the next power-on (3A 17 before the first 3A 18) they
 * kept «Сейчас отключится · 31,2 В» up. [restart] on each new connection. Synchronized: the publisher feeds it, the
 * link callback restarts it.
 */
class LoadSag(val windowMs: Long = 30_000, val loadA: Double = 5.0) {
    private val t = ArrayDeque<Long>()
    private val v = ArrayDeque<Double>()
    private var frameAt: Long? = null

    @Synchronized
    fun add(tMs: Long, volts: Double?, amps: Double?) {
        if (volts != null && amps != null && volts.isFinite() && amps >= loadA) { t.addLast(tMs); v.addLast(volts) }
        while (t.isNotEmpty() && t.first() < tMs - windowMs) { t.removeFirst(); v.removeFirst() }
    }

    @Synchronized
    fun minV(nowMs: Long): Double? {
        var m: Double? = null
        for (i in t.indices) if (t[i] >= nowMs - windowMs) m = if (m == null) v[i] else minOf(m, v[i])
        return m
    }

    /** [ws] with battery_low's value replaced by [minV]. */
    fun fix(ws: List<BatteryWarning>, nowMs: Long): List<BatteryWarning> =
        if (ws.none { it.id == BatteryAdvisor.LOW }) ws else ws.map { if (it.id == BatteryAdvisor.LOW) it.copy(value = minV(nowMs)) else it }

    /** A snapshot before it is published: a sample if it brought a new BMS frame, then battery_low's value (screen,
     *  notification, watch) and the charge lamp's when it is lit for battery_low. */
    @Synchronized
    fun decorate(s: Telemetry): Telemetry {
        val at = s.powerAtMs
        if (at != null && at != frameAt) { frameAt = at; add(at, s.voltage, s.currentFastA ?: s.currentA) }
        if (s.warnings.isEmpty()) return s
        val ws = fix(s.warnings, s.t)
        return s.copy(warnings = ws, lamps = fixLamp(s.lamps, ws))
    }

    /** A new connection: the samples go; the last frame stays seen, a snapshot still carrying it adds nothing. */
    @Synchronized
    fun restart() { t.clear(); v.clear() }

    /** A new recording. */
    @Synchronized
    fun reset() { restart(); frameAt = null }

    companion object {
        /** The charge lamp shows battery_low's value when it is lit for it: the same voltage under load as the warning. */
        fun fixLamp(lamps: List<Lamp>, ws: List<BatteryWarning>): List<Lamp> {
            val low = ws.firstOrNull { it.id == BatteryAdvisor.LOW } ?: return lamps
            val v = TellTales.lampValue(low.value)
            return lamps.map { if (it.id == TellTales.BATTERY && it.text == low.text) it.copy(value = v, valueText = TellTales.volts(v)) else it }
        }
    }
}

/** The warnings and the range in words. */
object WarnText {
    /** «Сейчас отключится · 31,8 В под нагрузкой», «Батарея перегрета · 56 °C». */
    fun line(w: BatteryWarning): String {
        val v = w.value ?: return w.text
        return when (w.id) {
            BatteryAdvisor.LOW -> w.text + " · " + Fmt.n(v, 1) + " В под нагрузкой"
            BatteryAdvisor.HOT -> w.text + " · " + Fmt.n(v) + " °C"
            else -> w.text
        }
    }

    /** What to do about it. */
    fun hint(w: BatteryWarning): String = when (w.id to w.level) {
        BatteryAdvisor.LOW to WarningLevel.CRITICAL -> "Под нагрузкой ниже 31,6 В: контроллер вот-вот отключит мотор (19.10 — на 31,45 В). Сбавь газ, ищи где встать."
        BatteryAdvisor.LOW to WarningLevel.WARNING -> "Под нагрузкой батарея проседает к отсечке (31,5–32 В), хотя BMS ещё показывает проценты."
        BatteryAdvisor.HOT to WarningLevel.CRITICAL -> "Дай батарее остыть: сбавь скорость или остановись. Перегретую батарею BMS может отключить."
        BatteryAdvisor.HOT to WarningLevel.WARNING -> "Батарея горячая. Если поднимется до 55 °C — остановись остыть."
        else -> ""
    }

    /** «≈ 4,2 км / 2,6 mi до отключения», the chosen unit first. */
    fun range(r: RangeEstimate, units: String): String {
        val km = Fmt.n(r.km, 1) + " км"
        val mi = Fmt.n(r.mi, 1) + " mi"
        return "≈ " + (if (units == Units.MPH) "$mi / $km" else "$km / $mi") + " до отключения"
    }

    /** What the range is based on. */
    fun basis(r: RangeEstimate): String = when (r.basis) {
        "recent" -> "по расходу за последние 2 км: " + Fmt.n(r.whPerKm, 1) + " Вт·ч/км"
        "session" -> "по расходу с начала записи: " + Fmt.n(r.whPerKm, 1) + " Вт·ч/км"
        else -> "по среднему расходу " + Fmt.n(r.whPerKm, 0) + " Вт·ч/км (своего пробега пока мало)"
    } + ", запас " + Fmt.n(r.whLeft, 0) + " Вт·ч до отсечки"
}
