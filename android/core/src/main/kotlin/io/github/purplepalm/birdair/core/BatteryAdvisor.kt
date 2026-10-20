package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable

@Serializable
enum class WarningLevel { WARNING, CRITICAL }

/**
 * An active warning. [id] is stable ("battery_low", "battery_hot"); [level] and [text] change with it;
 * [value]: the voltage under load (V) for battery_low, the hotter battery sensor (°C) for battery_hot.
 */
@Serializable
data class BatteryWarning(val id: String, val level: WarningLevel, val text: String, val value: Double? = null)

/**
 * Range to the observed empty (not BMS 0 %): [km] and [mi]; [whLeft] usable energy; [whPerKm] the consumption
 * it is divided by; [basis] where that came from: "recent" (last [BatteryAdvisor.Params.windowKm]), "session"
 * (since the advisor started) or "default".
 */
@Serializable
data class RangeEstimate(val km: Double, val mi: Double, val whLeft: Double, val whPerKm: Double, val basis: String)

/**
 * Low-battery and overheat warnings and a realistic range. A step-by-step port of tools/battery_advisor.py
 * (BatteryAdvisorTest replays every log through both and compares); thresholds and how they were calibrated on
 * the logs are described there.
 *
 *  - battery_low WARNING «Заряд на исходе»: under load (I ≥ [Params.loadA]) for [Params.sustainMs] in a row,
 *    V + rRef·(I − iRef) < [Params.lowV] (the voltage brought to 10 A); CRITICAL «Сейчас отключится»: the voltage
 *    under load itself < [Params.critV]. CRITICAL steps back to WARNING after [Params.clearMs] under load at
 *    ≥ [Params.critClearV]; both clear only after a charge: V ≥ [Params.chargedV] at I ≤ [Params.restA] for
 *    [Params.chargedMs].
 *  - battery_hot WARNING «Батарея греется» at max(temp1, temp2) ≥ [Params.hotC], CRITICAL «Батарея перегрета»
 *    at ≥ [Params.overheatC], each clearing [Params.hystC] lower.
 *  - range: (remaining − reservePct % of full, mAh) · whPerMah ÷ Wh/km over the last windowKm (≥ minWindowKm),
 *    else since the start (≥ minSessionKm), else defaultWhKm.
 *
 * Feed [onBms] with every 3A 18 20 10 frame (the BMS current, + traction) and [onDistance] with the app's
 * odometer ([AppOdometer.totalKm]). Not thread-safe: one caller (TelemetryTracker holds its lock).
 */
class BatteryAdvisor(val params: Params = Params()) {
    data class Params(
        val loadA: Double = 5.0,
        val sustainMs: Long = 2000,
        val rRef: Double = 0.25,
        val iRef: Double = 10.0,
        val lowV: Double = 32.4,
        val critV: Double = 32.0,
        val critClearV: Double = 32.5,
        val clearMs: Long = 20000,
        val chargedV: Double = 37.5,
        val restA: Double = 1.0,
        val chargedMs: Long = 10000,
        val hotC: Double = 50.0,
        val overheatC: Double = 55.0,
        val hystC: Double = 2.0,
        val reservePct: Double = 3.0,
        val windowKm: Double = 2.0,
        val minWindowKm: Double = 1.0,
        val minSessionKm: Double = 0.3,
        val defaultWhKm: Double = 15.0,
        val whPerMah: Double = 0.031,
        val gapMs: Long = 2500,
    )

    private var low = 0
    private var hot = 0
    private var lowSince: Long? = null
    private var critSince: Long? = null
    private var clearSince: Long? = null
    private var chargedSince: Long? = null
    private var loadedV: Double? = null
    /** The hotter battery sensor last seen, °C. */
    var tempMaxC: Double? = null
        private set
    private var lastT: Long? = null
    private var lastP = 0.0
    /** Energy out of the battery since the start, Wh (regen subtracted). */
    var whNet: Double = 0.0
        private set
    private var rem: Double? = null
    private var full: Double? = null
    /** The last distance, km. */
    var km: Double? = null
        private set
    private val cpKm = ArrayDeque<Double>()
    private val cpWh = ArrayDeque<Double>()
    private var firstKm = 0.0
    private var firstWh = 0.0
    private var hasFirst = false

    /** Everything back to the start (a new recording). */
    fun reset() {
        low = 0; hot = 0
        lowSince = null; critSince = null; clearSince = null; chargedSince = null
        loadedV = null; tempMaxC = null
        lastT = null; lastP = 0.0; whNet = 0.0
        rem = null; full = null; km = null
        cpKm.clear(); cpWh.clear(); hasFirst = false; firstKm = 0.0; firstWh = 0.0
    }

    /** One 3A 18 20 10 frame: [iBms] + traction. */
    fun onBms(tMs: Long, voltage: Double, iBms: Double, temp1C: Double? = null, temp2C: Double? = null, remainingMah: Double? = null, fullMah: Double? = null) {
        val p = params
        val lt = lastT
        val gap = lt != null && (tMs - lt > p.gapMs || tMs < lt)
        if (lt != null && !gap && tMs > lt) whNet += lastP * (tMs - lt).toDouble() / 3.6e6
        if (gap) { lowSince = null; critSince = null; clearSince = null; chargedSince = null }
        lastT = tMs
        lastP = voltage * iBms
        if (remainingMah != null && fullMah != null && fullMah != 0.0) { rem = remainingMah; full = fullMah }
        voltageRules(tMs, voltage, iBms)
        temperature(temp1C, temp2C)
    }

    /** The app's odometer, km (grows; a lower value is ignored). */
    fun onDistance(@Suppress("UNUSED_PARAMETER") tMs: Long, totalKm: Double?) {
        if (totalKm == null) return
        val k = km
        if (k == null || totalKm > k) {
            km = totalKm
            if (!hasFirst) { hasFirst = true; firstKm = totalKm; firstWh = whNet }
            cpKm.addLast(totalKm); cpWh.addLast(whNet)
            while (cpKm.size > 1 && cpKm[1] <= totalKm - params.windowKm) { cpKm.removeFirst(); cpWh.removeFirst() }
        }
    }

    private fun voltageRules(t: Long, v: Double, i: Double) {
        val p = params
        val loaded = i >= p.loadA
        if (loaded) loadedV = v
        if (loaded && v + p.rRef * (i - p.iRef) < p.lowV) {
            val s = lowSince ?: t.also { lowSince = it }
            if (t - s >= p.sustainMs && low < 1) low = 1
        } else lowSince = null
        if (loaded && v < p.critV) {
            val s = critSince ?: t.also { critSince = it }
            if (t - s >= p.sustainMs) low = 2
        } else critSince = null
        if (low == 2 && loaded && v >= p.critClearV) {
            val s = clearSince ?: t.also { clearSince = it }
            if (t - s >= p.clearMs) { low = 1; clearSince = null }
        } else clearSince = null
        if (low != 0 && i <= p.restA && v >= p.chargedV) {
            val s = chargedSince ?: t.also { chargedSince = it }
            if (t - s >= p.chargedMs) { low = 0; chargedSince = null }
        } else chargedSince = null
    }

    private fun temperature(t1: Double?, t2: Double?) {
        var x: Double? = null
        for (c in listOf(t1, t2)) if (c != null && c >= -30 && c <= 120) x = if (x == null) c else maxOf(x, c)
        if (x == null) return
        val p = params
        tempMaxC = x
        hot = if (x >= p.overheatC || (hot == 2 && x > p.overheatC - p.hystC)) 2
        else if (x >= p.hotC || (hot >= 1 && x > p.hotC - p.hystC)) 1
        else 0
    }

    /** Active warnings, CRITICAL first. */
    fun warnings(): List<BatteryWarning> {
        val out = ArrayList<BatteryWarning>(2)
        if (low != 0) out.add(warning(LOW, low, loadedV))
        if (hot != 0) out.add(warning(HOT, hot, tempMaxC))
        return out.sortedBy { if (it.level == WarningLevel.CRITICAL) 0 else 1 }
    }

    private fun warning(id: String, lvl: Int, value: Double?): BatteryWarning {
        val level = if (lvl == 2) WarningLevel.CRITICAL else WarningLevel.WARNING
        return BatteryWarning(id, level, TEXTS.getValue(id to level), value)
    }

    /** Range to the observed empty, or null until the BMS sent its remaining charge. */
    fun range(): RangeEstimate? {
        val p = params
        val r = rem ?: return null
        val f = full ?: return null
        var basis = "default"
        var whKm = p.defaultWhKm
        val k = km
        if (k != null && cpKm.isNotEmpty()) {
            if (k - cpKm.first() >= p.minWindowKm) { basis = "recent"; whKm = (whNet - cpWh.first()) / (k - cpKm.first()) }
            else if (k - firstKm >= p.minSessionKm) { basis = "session"; whKm = (whNet - firstWh) / (k - firstKm) }
        }
        if (whKm < 1.0) whKm = p.defaultWhKm
        val usable = r - p.reservePct * f / 100.0
        val whLeft = if (usable > 0) usable * p.whPerMah else 0.0
        val km = whLeft / whKm
        return RangeEstimate(km, km / MILE_KM, whLeft, whKm, basis)
    }

    companion object {
        const val LOW = "battery_low"
        const val HOT = "battery_hot"
        const val MILE_KM = 1.609344
        val TEXTS: Map<Pair<String, WarningLevel>, String> = mapOf(
            (LOW to WarningLevel.WARNING) to "Заряд на исходе",
            (LOW to WarningLevel.CRITICAL) to "Сейчас отключится",
            (HOT to WarningLevel.WARNING) to "Батарея греется",
            (HOT to WarningLevel.CRITICAL) to "Батарея перегрета",
        )
    }
}
