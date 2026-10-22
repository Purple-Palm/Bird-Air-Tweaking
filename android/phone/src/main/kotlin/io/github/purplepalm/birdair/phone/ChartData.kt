package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Telemetry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** A contiguous copy of chart samples, oldest first. NaN: no value then. t: the session's clock (ms). */
class Samples(
    val t: LongArray,
    /** Fast power (W) and the BMS power (W); currents likewise (A). */
    val pFast: FloatArray, val pBms: FloatArray,
    val iFast: FloatArray, val iBms: FloatArray,
    val v: FloatArray, val s: FloatArray,
    /** The app's odometer (km), NaN when unknown. */
    val odo: DoubleArray,
) {
    val size: Int get() = t.size
    fun p(fast: Boolean): FloatArray = if (fast) pFast else pBms
    fun i(fast: Boolean): FloatArray = if (fast) iFast else iBms

    /** The sample nearest to [tMs], −1 when empty. */
    fun nearest(tMs: Long): Int {
        if (t.isEmpty()) return -1
        var lo = 0
        var hi = t.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[mid] < tMs) lo = mid + 1 else hi = mid }
        if (lo > 0 && abs(t[lo - 1] - tMs) <= abs(t[lo] - tMs)) lo--
        return lo
    }

    companion object { val EMPTY = Samples(LongArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), DoubleArray(0)) }
}

/**
 * The chart's history: one sample per published snapshot that brought new data (≈ the frame rate, 15–20 a second
 * while riding), [capacity] of them at most (2 h at 20/s), then the oldest go. Primitive arrays, allocated on the
 * first sample. Thread-safe: the publisher adds, the chart copies a window.
 */
class SampleRing(val capacity: Int = CAPACITY) {
    private var t = LongArray(0)
    private var pFast = FloatArray(0); private var pBms = FloatArray(0)
    private var iFast = FloatArray(0); private var iBms = FloatArray(0)
    private var v = FloatArray(0); private var s = FloatArray(0)
    private var odo = DoubleArray(0)
    private var head = 0
    private var n = 0

    val size: Int @Synchronized get() = n
    /** Time of the newest sample, null when empty. */
    val lastT: Long? @Synchronized get() = if (n == 0) null else t[idx(n - 1)]
    val firstT: Long? @Synchronized get() = if (n == 0) null else t[idx(0)]

    private fun idx(i: Int) = (head + i) % capacity

    @Synchronized
    fun add(tMs: Long, pf: Double?, pb: Double?, iFastA: Double?, iBmsA: Double?, volts: Double?, kmh: Double?, odoKm: Double?) {
        if (t.isEmpty()) {
            t = LongArray(capacity); pFast = FloatArray(capacity); pBms = FloatArray(capacity)
            iFast = FloatArray(capacity); iBms = FloatArray(capacity); v = FloatArray(capacity); s = FloatArray(capacity)
            odo = DoubleArray(capacity)
        }
        if (n > 0 && tMs < t[idx(n - 1)]) return   // the clock never goes back within a session
        val k: Int
        if (n < capacity) { k = idx(n); n++ } else { k = head; head = (head + 1) % capacity }
        t[k] = tMs
        pFast[k] = f(pf); pBms[k] = f(pb); iFast[k] = f(iFastA); iBms[k] = f(iBmsA); v[k] = f(volts); s[k] = f(kmh)
        odo[k] = odoKm?.takeIf { it.isFinite() } ?: Double.NaN
    }

    fun add(at: Long, x: Telemetry) =
        add(at, x.powerFastW ?: x.powerW, x.powerW, x.currentFastA ?: x.currentA, x.currentA, x.voltage, x.speedKmh, x.appOdometerKm)

    @Synchronized
    fun clear() { head = 0; n = 0 }

    /** The samples with t ≥ [fromT], oldest first. */
    @Synchronized
    fun copy(fromT: Long = Long.MIN_VALUE): Samples {
        if (n == 0) return Samples.EMPTY
        var lo = 0
        var hi = n
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[idx(mid)] < fromT) lo = mid + 1 else hi = mid }
        val m = n - lo
        val out = Samples(LongArray(m), FloatArray(m), FloatArray(m), FloatArray(m), FloatArray(m), FloatArray(m), FloatArray(m), DoubleArray(m))
        for (j in 0 until m) {
            val k = idx(lo + j)
            out.t[j] = t[k]; out.pFast[j] = pFast[k]; out.pBms[j] = pBms[k]; out.iFast[j] = iFast[k]; out.iBms[j] = iBms[k]
            out.v[j] = v[k]; out.s[j] = s[k]; out.odo[j] = odo[k]
        }
        return out
    }

    private fun f(x: Double?): Float = if (x == null || !x.isFinite()) Float.NaN else x.toFloat()

    companion object {
        /** 2 h at 20 samples a second. */
        const val CAPACITY = 2 * 3600 * 20
    }
}

/** A run of points (time order) between gaps. */
class Run(val t: LongArray, val y: FloatArray) { val size: Int get() = t.size }

/** One series ready to draw: [runs] split at gaps, at most 2 points per pixel column; [min]/[max] over the window (NaN: no data). */
class Envelope(val runs: List<Run>, val min: Float, val max: Float) {
    val points: Int get() = runs.sumOf { it.size }
    val empty: Boolean get() = runs.isEmpty()
}

/**
 * Min/max per pixel column: of all samples falling into one column only the lowest and the highest stay, in the order
 * they happened, so spikes survive any zoom and a 2-hour window draws ~2 points a pixel. A gap longer than [gapMs]
 * ends a run (no line across it). Samples outside [start, end] and NaN values are skipped.
 */
object Downsample {
    fun minMax(t: LongArray, y: FloatArray, start: Long, end: Long, columns: Int, gapMs: Long = GAP_MS): Envelope {
        val cols = maxOf(1, columns)
        val span = maxOf(1L, end - start).toDouble()
        val runs = ArrayList<Run>()
        val rt = ArrayList<Long>()
        val ry = ArrayList<Float>()
        var gMin = Float.NaN
        var gMax = Float.NaN
        var col = -1
        var minT = 0L; var minY = 0f; var maxT = 0L; var maxY = 0f
        var prevT = Long.MIN_VALUE
        fun flushCol() {
            if (col < 0) return
            if (minT == maxT) { rt.add(minT); ry.add(minY) }
            else if (minT < maxT) { rt.add(minT); ry.add(minY); rt.add(maxT); ry.add(maxY) }
            else { rt.add(maxT); ry.add(maxY); rt.add(minT); ry.add(minY) }
            col = -1
        }
        fun flushRun() {
            flushCol()
            if (rt.isNotEmpty()) { runs.add(Run(rt.toLongArray(), ry.toFloatArray())); rt.clear(); ry.clear() }
        }
        var i = lowerBound(t, start)
        while (i < t.size) {
            val ti = t[i]
            if (ti > end) break
            val yi = y[i]
            if (!yi.isNaN()) {
                if (prevT != Long.MIN_VALUE && ti - prevT > gapMs) flushRun()
                prevT = ti
                if (gMin.isNaN() || yi < gMin) gMin = yi
                if (gMax.isNaN() || yi > gMax) gMax = yi
                val c = floor((ti - start) / span * cols).toInt().coerceIn(0, cols - 1)
                if (c != col) {
                    flushCol()
                    col = c; minT = ti; minY = yi; maxT = ti; maxY = yi
                } else {
                    if (yi < minY) { minY = yi; minT = ti }
                    if (yi > maxY) { maxY = yi; maxT = ti }
                }
            }
            i++
        }
        flushRun()
        return Envelope(runs, gMin, gMax)
    }

    /** First index with t ≥ [x]. */
    fun lowerBound(t: LongArray, x: Long): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (t[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** No data for this long: the line breaks (the tracker's «live» window). */
    const val GAP_MS = 3000L
}

/**
 * Energy over a chart window, integrated like the tracker (each power value until the next sample, dt clamped to
 * 2 s, gaps over 3 s skipped): [outWh] from the battery, [inWh] back into it (regen, charging), [seconds] with data,
 * [km] by the app's odometer. [whPerKm] needs 50 m at least.
 */
data class EnergySummary(val outWh: Double, val inWh: Double, val seconds: Double, val km: Double?) {
    val netWh: Double get() = outWh - inWh
    val avgW: Double? get() = if (seconds >= 1.0) netWh * 3600.0 / seconds else null
    val whPerKm: Double? get() = km?.takeIf { it >= 0.05 }?.let { netWh / it }

    companion object {
        val EMPTY = EnergySummary(0.0, 0.0, 0.0, null)

        fun of(x: Samples, fast: Boolean, start: Long = Long.MIN_VALUE, end: Long = Long.MAX_VALUE): EnergySummary {
            val p = x.p(fast)
            var out = 0.0
            var into = 0.0
            var secs = 0.0
            var firstOdo = Double.NaN
            var lastOdo = Double.NaN
            var i = Downsample.lowerBound(x.t, start)
            while (i < x.size && x.t[i] <= end) {
                val o = x.odo[i]
                if (!o.isNaN()) { if (firstOdo.isNaN()) firstOdo = o; lastOdo = o }
                if (i + 1 < x.size && x.t[i + 1] <= end) {
                    val gap = x.t[i + 1] - x.t[i]
                    val pi = p[i]
                    if (gap <= Downsample.GAP_MS && !pi.isNaN()) {
                        val dt = minOf(gap / 1000.0, MAX_DT_S)
                        secs += dt
                        if (pi >= 0) out += pi * dt / 3600.0 else into += -pi * dt / 3600.0
                    }
                }
                i++
            }
            val km = if (firstOdo.isNaN()) null else lastOdo - firstOdo
            return EnergySummary(out, into, secs, km)
        }

        private const val MAX_DT_S = 2.0
    }
}

/** Round axis ticks (the page's niceTicks): about [n] steps of 1, 2, 2.5 or 5 × 10^k covering lo…hi. */
object Ticks {
    fun step(span: Double, n: Int): Double {
        val raw = span / maxOf(1, n)
        if (!(raw > 0) || !raw.isFinite()) return 1.0
        val p = 10.0.pow(floor(log10(raw)))
        val m = raw / p
        return (if (m <= 1) 1.0 else if (m <= 2) 2.0 else if (m <= 2.5) 2.5 else if (m <= 5) 5.0 else 10.0) * p
    }

    fun ticks(lo0: Double, hi0: Double, n: Int = 4): Pair<List<Double>, Double> {
        var lo = lo0
        var hi = hi0
        if (!(hi > lo)) hi = lo + 1
        val st = step(hi - lo, n)
        val a = floor(lo / st) * st
        val b = ceil(hi / st) * st
        val out = ArrayList<Double>()
        var k = 0
        while (true) {
            val x = a + k * st
            if (x > b + st * 0.5 || k > 50) break
            out.add(if (abs(x) < st * 1e-6) 0.0 else x)
            k++
        }
        return out to st
    }

    /** The decimals the tick labels need. */
    fun decimals(step: Double): Int = if (step >= 1) 0 else if (step >= 0.1) 1 else 2
}

/**
 * The y axes of «Графики» (the prototype's drawChart): power in 250 W steps (500 W once the window spans over 900 W,
 * 1000 W over 2700 W), at least one step either side of zero; speed from 0 in steps of 10 (at least 0…10); volts in
 * steps of 5 around the window (at least 5 wide). The ticks, lowest first, and the step.
 */
object ChartAxis {
    fun power(min: Double, max: Double): Pair<List<Double>, Double> {
        val mn = if (min.isFinite()) min else 0.0
        val mx = if (max.isFinite()) max else 0.0
        val span = mx - mn
        val step = if (span > 2700) 1000.0 else if (span > 900) 500.0 else 250.0
        return between(minOf(-step, floor(mn / step) * step), maxOf(step, ceil(mx / step) * step), step) to step
    }

    fun speed(max: Double): Pair<List<Double>, Double> {
        val mx = if (max.isFinite()) max else 0.0
        return between(0.0, maxOf(10.0, ceil(mx / 10) * 10), 10.0) to 10.0
    }

    fun volts(min: Double, max: Double): Pair<List<Double>, Double> {
        if (!min.isFinite() || !max.isFinite()) return between(30.0, 35.0, 5.0) to 5.0
        val lo = floor(min / 5) * 5
        return between(lo, maxOf(lo + 5, ceil(max / 5) * 5), 5.0) to 5.0
    }

    private fun between(lo: Double, hi: Double, step: Double): List<Double> {
        val out = ArrayList<Double>()
        var k = 0
        while (k <= 40) {
            val x = lo + k * step
            if (x > hi + step * 1e-6) break
            out.add(if (abs(x) < step * 1e-6) 0.0 else x)
            k++
        }
        return out
    }
}

/**
 * The marker labels on the power chart (the prototype's): a label longer than 13 characters is cut to 12 and «…»;
 * it goes right of its line, left of it where it would cross the plot's right edge; one that would come within [gap] of
 * a label already placed is left out (its line stays).
 */
object MarkerLabels {
    fun text(label: String): String = if (label.length > 13) label.take(12).trim() + "…" else label

    /** Lines at [xs] (time order) with label widths [ws], [pad] between line and label, a plot ending at [right]: each
     *  label's left edge, NaN where it is left out. */
    fun place(xs: FloatArray, ws: FloatArray, right: Float, pad: Float, gap: Float): FloatArray {
        val out = FloatArray(xs.size) { Float.NaN }
        val taken = ArrayList<FloatArray>()
        for (i in xs.indices) {
            val w = ws[i]
            val x0 = if (xs[i] + pad + w > right) xs[i] - pad - w else xs[i] + pad
            if (taken.any { x0 < it[1] + gap && x0 + w > it[0] - gap }) continue
            out[i] = x0
            taken.add(floatArrayOf(x0, x0 + w))
        }
        return out
    }
}

/** The chart windows: «1 мин / 5 мин / 15 мин / вся поездка». */
object ChartWindow {
    const val ALL = "all"
    val OPTIONS: List<Pair<String, String>> = listOf("60" to "1 мин", "300" to "5 мин", "900" to "15 мин", ALL to "вся поездка")
    fun norm(w: String?): String = if (OPTIONS.any { it.first == w }) w!! else "300"

    /**
     * [start, end] on the session clock. A window ends now while the link runs ([nowMs]), else at the last sample;
     * «вся поездка» starts at the first sample (at least 10 s wide).
     */
    fun range(w: String, nowMs: Long?, firstT: Long?, lastT: Long?): LongArray {
        val end = nowMs ?: lastT ?: 0L
        if (w == ALL) {
            val start = firstT ?: end
            return longArrayOf(start, maxOf(end, start + 10_000))
        }
        val span = (w.toLongOrNull() ?: 300L) * 1000
        return longArrayOf(end - span, end)
    }

    /** Relative labels for the time axis («−1 мин», «сейчас»), every 15 s / 1 min / 5 min. */
    fun relStepS(spanS: Double): Int = if (spanS <= 90) 15 else if (spanS <= 400) 60 else 300

    /** Clock labels for «вся поездка»: a step from 1 s to 1 h giving at most 6 labels. */
    fun clockStepS(spanS: Double): Int = intArrayOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600).firstOrNull { spanS / it <= 6 } ?: 3600
}
