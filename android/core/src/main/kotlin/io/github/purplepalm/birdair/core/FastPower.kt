package io.github.purplepalm.birdair.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Battery current and power from the voltage sag, fresh on every 3A 18 20 10 frame (~3.2/s), where the BMS
 * current refreshes ~1/s and describes the 0.7 s before the frame it changed in (on screen it lags ~0.6 s).
 * V = OCV − R·I, so I = (OCV − V)/R; the BMS current only anchors the level: OCV ≈ V̄ + R·I_bms with V̄ the mean
 * voltage over the same 0.7 s. Causal: an output depends on this and earlier frames only.
 *
 * A step-by-step port of tools/fast_power.py (FastPowerTest runs both on every log and compares); the algorithm
 * and why the parameters are what they are is described there. Inputs: t (ms, any one clock), V (volts), I_bms
 * (amps, + traction, − regen and charging). Output: I_fast (A, + traction, |I| ≤ [Params.iMax]) and
 * P_fast = V·I_fast (W), 0 below [Params.deadbandW]. [reset] on a new connection; a gap over [Params.gapMs]
 * or time going back resets by itself. R and its sums survive [reset] (a property of the battery).
 *
 * Not thread-safe: one caller (TelemetryTracker holds its lock).
 */
class FastPower(val params: Params = Params()) {
    data class Params(
        val r0: Double = 0.25,
        val rMin: Double = 0.08,
        val rMax: Double = 0.6,
        val rLambda: Double = 0.98,
        val rPrior: Double = 50.0,
        val diMin: Double = 1.0,
        val pairMaxMs: Long = 2500,
        val windowMs: Long = 700,
        val holdMs: Long = 1200,
        val holdDv: Double = 0.1,
        val kOcv: Double = 0.5,
        val kSteady: Double = 1.0,
        val kSlope: Double = 0.2,
        val slopeMax: Double = 0.05,
        val horizonMs: Long = 2000,
        val gapMs: Long = 2500,
        val iMax: Double = 17.0,
        val deadbandW: Double = 5.0,
    )

    /** One frame's estimate: [currentA] + traction, [powerW] = V·I. */
    data class Sample(val currentA: Double, val powerW: Double)

    /** Internal resistance, Ω. */
    var r: Double = params.r0
        private set
    private var sxx = 0.0
    private var sxy = 0.0
    private var syy = 0.0

    /** Open-circuit voltage at the last anchor, V; null before the first frame of a stream. */
    var ocv: Double? = null
        private set
    private var slope = 0.0
    private var tOcv = 0.0
    private val histT = ArrayDeque<Long>()
    private val histV = ArrayDeque<Double>()
    private var lastT: Long? = null
    private var lastI = 0.0
    private var anchorT = 0L
    private var hasPrev = false
    private var prevT = 0L
    private var prevV = 0.0
    private var prevI = 0.0
    private var vlo = 0.0
    private var vhi = 0.0
    /** Anchors since the last reset. */
    var anchors: Int = 0
        private set

    /** A new stream of frames (reconnect, gap). [full]: R and its sums too. */
    fun reset(full: Boolean = false) {
        if (full) {
            r = params.r0
            sxx = 0.0; sxy = 0.0; syy = 0.0
        }
        ocv = null
        slope = 0.0
        tOcv = 0.0
        histT.clear(); histV.clear()
        lastT = null
        lastI = 0.0
        anchorT = 0L
        hasPrev = false
        vlo = 0.0; vhi = 0.0
        anchors = 0
    }

    private fun clamp(x: Double, lo: Double, hi: Double) = if (x < lo) lo else if (x > hi) hi else x

    private fun learnR(t: Long, vbar: Double, i: Double) {
        val p = params
        if (!hasPrev || t - prevT > p.pairMaxMs) return
        val di = i - prevI
        val dv = vbar - prevV
        if (abs(di) < p.diMin) return
        val lam = p.rLambda
        sxx = lam * sxx + di * di
        sxy = lam * sxy - dv * di
        syy = lam * syy + dv * dv
        if (sxy > 0) {
            val rr = sqrt((syy + p.rPrior * p.r0 * p.r0) / (sxx + p.rPrior))
            r = clamp(rr, p.rMin, p.rMax)
        }
    }

    private fun anchor(t: Long, i: Double) {
        val p = params
        val n = histT.size
        var vs = 0.0
        var ts = 0L
        var lo = histV.first()
        var hi = lo
        for (k in 0 until n) {
            val v = histV[k]
            ts += histT[k]
            vs += v
            lo = minOf(lo, v)
            hi = maxOf(hi, v)
        }
        val vbar = vs / n
        val tc = ts.toDouble() / n
        learnR(t, vbar, i)
        val dtc = (tc - tOcv) / 1000.0
        val pred = ocv!! + slope * dtc
        val e = vbar + r * i - pred
        val steady = hasPrev && abs(i - prevI) < p.diMin
        ocv = pred + (if (steady) p.kSteady else p.kOcv) * e
        if (dtc > 0.2) slope = clamp(slope + p.kSlope * e / dtc, -p.slopeMax, p.slopeMax)
        tOcv = tc
        hasPrev = true; prevT = t; prevV = vbar; prevI = i
        vlo = lo; vhi = hi
        lastI = i
        anchorT = t
        anchors++
    }

    /** One BMS frame. [motor] (the «мотор тянет» bit) is not used: the current ramps down 0.3–0.6 s after it drops. */
    @Suppress("UNUSED_PARAMETER")
    fun update(tMs: Long, voltage: Double, iBms: Double, motor: Boolean = false): Sample {
        val p = params
        val lt = lastT
        if (lt != null && (tMs - lt > p.gapMs || tMs < lt)) reset()
        lastT = tMs
        histT.addLast(tMs); histV.addLast(voltage)
        while (histT.first() < tMs - p.windowMs) { histT.removeFirst(); histV.removeFirst() }
        val o = ocv
        if (o == null) {
            ocv = voltage + r * iBms
            slope = 0.0
            tOcv = tMs.toDouble()
            lastI = iBms
            anchorT = tMs
            hasPrev = false
            vlo = voltage; vhi = voltage
        } else {
            vlo = minOf(vlo, voltage)
            vhi = maxOf(vhi, voltage)
            if (iBms != lastI || (tMs - anchorT >= p.holdMs && vhi - vlo <= p.holdDv)) anchor(tMs, iBms)
        }
        val ocvNow = ocv!! + slope * minOf(tMs - tOcv, p.horizonMs.toDouble()) / 1000.0
        var i = clamp((ocvNow - voltage) / r, -p.iMax, p.iMax)
        var pw = voltage * i
        if (abs(pw) < p.deadbandW) { i = 0.0; pw = 0.0 }
        return Sample(i, pw)
    }
}
