package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.WarningLevel

/**
 * When the «Предупреждения» notification buzzes. The advisor's warnings already have hysteresis; on top of that:
 *  - it buzzes when a warning appears or steps up (WARNING → CRITICAL), but each (id, level) at most once per
 *    [cooldownMs] — a warning that clears and comes back within that time only updates the text silently;
 *  - stepping down (CRITICAL → WARNING) never buzzes;
 *  - when all warnings are gone, the notification goes too.
 * Pure logic (the clock is passed in); not thread-safe — the publisher is the one caller.
 */
class WarningAlerts(val cooldownMs: Long = COOLDOWN_MS) {
    enum class Action { NONE, ALERT, UPDATE, CANCEL }

    class Decision(val action: Action, val warnings: List<BatteryWarning>)

    private var prev: Map<String, WarningLevel> = emptyMap()
    private val lastAt = HashMap<Pair<String, WarningLevel>, Long>()

    fun update(ws: List<BatteryWarning>, nowMs: Long): Decision {
        val cur = ws.associate { it.id to it.level }
        if (cur == prev) return Decision(Action.NONE, ws)
        val was = prev
        prev = cur
        if (cur.isEmpty()) return Decision(Action.CANCEL, ws)
        var alert = false
        for ((id, lvl) in cur) {
            val before = was[id]
            val up = before == null || lvl.ordinal > before.ordinal
            if (!up) continue
            val key = id to lvl
            val last = lastAt[key]
            if (last == null || nowMs - last >= cooldownMs) { alert = true; lastAt[key] = nowMs }
        }
        return Decision(if (alert) Action.ALERT else Action.UPDATE, ws)
    }

    /** A new recording: what is on now counts as new again, but the cooldown stays (a reconnect must not re-buzz). */
    fun reset() { prev = emptyMap() }

    companion object {
        const val COOLDOWN_MS = 5 * 60_000L
    }
}

/**
 * The voltage under load shown with «Заряд на исходе» / «Сейчас отключится»: the lowest voltage of the last
 * [windowMs] at a current ≥ [loadA], the current taken from the fast estimate (it belongs to the same frame as the
 * voltage). The advisor's own value is the last voltage while the BMS current was ≥ 5 A, and the BMS current lags
 * ~0.6 s: on letting go of the throttle it pairs the rebounded voltage (35–36 V) with the old 10 A. Null when there
 * was no load in the window (then no number is shown). Not thread-safe: the publisher is the one caller.
 */
class LoadSag(val windowMs: Long = 30_000, val loadA: Double = 5.0) {
    private val t = ArrayDeque<Long>()
    private val v = ArrayDeque<Double>()

    fun add(tMs: Long, volts: Double?, amps: Double?) {
        if (volts != null && amps != null && volts.isFinite() && amps >= loadA) { t.addLast(tMs); v.addLast(volts) }
        while (t.isNotEmpty() && t.first() < tMs - windowMs) { t.removeFirst(); v.removeFirst() }
    }

    fun minV(nowMs: Long): Double? {
        var m: Double? = null
        for (i in t.indices) if (t[i] >= nowMs - windowMs) m = if (m == null) v[i] else minOf(m, v[i])
        return m
    }

    /** [ws] with battery_low's value replaced by [minV]. */
    fun fix(ws: List<BatteryWarning>, nowMs: Long): List<BatteryWarning> =
        if (ws.none { it.id == BatteryAdvisor.LOW }) ws else ws.map { if (it.id == BatteryAdvisor.LOW) it.copy(value = minV(nowMs)) else it }

    fun reset() { t.clear(); v.clear() }
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
        BatteryAdvisor.LOW to WarningLevel.CRITICAL -> "Под нагрузкой ниже 32 В: контроллер вот-вот отключит мотор. Сбавь газ, ищи где встать."
        BatteryAdvisor.LOW to WarningLevel.WARNING -> "Под нагрузкой батарея проседает к отсечке (31,5–32 В), хотя BMS ещё показывает проценты."
        BatteryAdvisor.HOT to WarningLevel.CRITICAL -> "Дай батарее остыть: сбавь скорость или остановись. Перегретую батарею BMS может отключить."
        BatteryAdvisor.HOT to WarningLevel.WARNING -> "Батарея горячая. Если поднимется до 55 °C — остановись остыть."
        else -> ""
    }

    /** The notification: title and text for all active warnings, CRITICAL first. */
    fun title(ws: List<BatteryWarning>): String = ws.firstOrNull()?.text ?: ""

    fun body(ws: List<BatteryWarning>): String = ws.joinToString("\n") { line(it) }

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
