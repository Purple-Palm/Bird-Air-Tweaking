package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FastPowerTest {
    /** FastPower over a log's BMS frames, reset at each new connection (tools/fast_power.py run()). */
    private fun run(path: String): List<DoubleArray> {
        val fp = FastPower()
        val out = ArrayList<DoubleArray>()
        for (e in RideLogs.ride(path).events) when (e) {
            is ScooterEvent.Reset -> fp.reset()
            is ScooterEvent.Bms -> {
                val s = fp.update(e.t, e.f.voltage, e.f.currentA, e.f.motor)
                out.add(doubleArrayOf(e.t.toDouble(), e.f.voltage, e.f.currentA, s.currentA, s.powerW))
            }
            is ScooterEvent.Motion -> {}
        }
        return out
    }

    /** The port against tools/fast_power.py on every log, frame by frame. */
    @Test fun sameAsPythonOnAllLogs() {
        Py.assume()
        var frames = 0
        var maxDiff = 0.0
        for (path in RideLogs.ALL) {
            val mine = run(path)
            val py = RideLogs.python.getValue(path)["fast"]!!.jsonArray
            assertEquals(py.size, mine.size, path)
            for ((k, row) in py.withIndex()) {
                val r = row.jsonArray
                assertEquals(r[0].jsonPrimitive.long, mine[k][0].toLong(), "$path #$k t")
                assertEquals(r[1].jsonPrimitive.double, mine[k][1], "$path #$k V")
                assertEquals(r[2].jsonPrimitive.double, mine[k][2], "$path #$k I")
                for (c in 3..4) {
                    val d = abs(r[c].jsonPrimitive.double - mine[k][c])
                    maxDiff = maxOf(maxDiff, d)
                    assertTrue(d <= 1e-9, "$path #$k col $c: ${r[c]} vs ${mine[k][c]}")
                }
            }
            frames += mine.size
        }
        assertTrue(frames > 60_000, "$frames")
        println("FastPower vs Python: $frames frames, max |diff| = $maxDiff")
    }

    @Test fun soloRideLeadsTheBms() {
        val rows = run(RideLogs.SOLO)
        // the first frame of each throttle opening above 5 A: I_fast gets there before the BMS current
        var leads = 0
        var lags = 0
        var armedF = true
        var armedB = true
        var tf = -1.0
        for (r in rows) {
            if (r[3] < 4.0) armedF = true
            if (r[2] < 4.0) armedB = true
            if (armedF && r[3] >= 5.0) { armedF = false; tf = r[0] }
            if (armedB && r[2] >= 5.0) {
                armedB = false
                if (tf >= 0 && r[0] - tf in 0.0..3000.0) leads++ else lags++
            }
        }
        assertTrue(leads > 50 && leads > 5 * lags, "leads $leads, lags $lags")
        val ePy = rows.zipWithNext().sumOf { (a, b) -> if (b[0] - a[0] in 1.0..2500.0) a[4] * (b[0] - a[0]) / 3.6e6 else 0.0 }
        val eBms = rows.zipWithNext().sumOf { (a, b) -> if (b[0] - a[0] in 1.0..2500.0) a[1] * a[2] * (b[0] - a[0]) / 3.6e6 else 0.0 }
        assertEquals(eBms, ePy, eBms * 0.02)                                        // net energy within 2 %
        assertTrue(rows.all { abs(it[3]) <= 17.0 })
    }

    @Test fun chargingHasNoTraction() {
        for (path in listOf(RideLogs.CHARGE, RideLogs.CAR_CHARGE)) {
            val rows = run(path)
            assertTrue(rows.size > 900, path)
            assertTrue(rows.none { it[3] > 0 || it[4] > 0 }, path)
            assertTrue(rows.minOf { it[3] } < -1.0, path)
        }
    }

    @Test fun deadbandClampGap() {
        val fp = FastPower()
        assertEquals(FastPower.Sample(0.0, 0.0), fp.update(0, 40.0, 0.1))        // 4 W
        val s = fp.update(300, 30.0, 0.1)                                          // 10 V sag → 40 A at 0.25 Ω
        assertEquals(17.0, s.currentA)
        assertEquals(30.0 * 17.0, s.powerW, 1e-9)
        val r = fp.r
        assertEquals(10.0, fp.update(300 + 2501, 30.0, 10.0).currentA, 1e-9)      // gap: a new stream, I_bms as is
        assertEquals(0, fp.anchors)
        assertEquals(r, fp.r)
        fp.reset(full = true)
        assertEquals(0.25, fp.r)
        val nodb = FastPower(FastPower.Params(deadbandW = 0.0))
        assertEquals(0.1, nodb.update(0, 40.0, 0.1).currentA, 1e-9)
    }
}
