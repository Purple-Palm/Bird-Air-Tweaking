package io.github.purplepalm.birdair.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class ChartDataTest {
    @Test fun minMaxPerColumnKeepsTheSpikes() {
        val n = 144_000   // 2 h at 20/s
        val t = LongArray(n) { it * 50L }
        val rnd = Random(3)
        val y = FloatArray(n) { (300 * sin(it / 500.0) + rnd.nextDouble(-20.0, 20.0)).toFloat() }
        y[77_777] = 950f    // one frame of full throttle
        y[12_345] = -400f   // one of hard regen
        val cols = 750
        val e = Downsample.minMax(t, y, 0, t.last(), cols)
        assertEquals(950f, e.max)
        assertEquals(-400f, e.min)
        assertEquals(1, e.runs.size)
        val r = e.runs[0]
        assertTrue("≤ 2 points a column: ${r.size}", r.size <= 2 * cols)
        assertTrue(r.y.any { it == 950f } && r.y.any { it == -400f })
        for (i in 1 until r.size) assertTrue("time order", r.t[i] >= r.t[i - 1])
        // each kept point is a real sample
        for (i in 0 until r.size) assertEquals(y[(r.t[i] / 50).toInt()], r.y[i])
    }

    @Test fun gapsSplitRunsAndWindowsClip() {
        val t = longArrayOf(0, 100, 200, 5000, 5100, 9000, 20_000)
        val y = floatArrayOf(1f, 2f, Float.NaN, 4f, 5f, 6f, 7f)
        val e = Downsample.minMax(t, y, 0, 10_000, 1000)
        assertEquals("NaN skipped, 3 s gaps break the line", 3, e.runs.size)
        assertArrayEquals(longArrayOf(0, 100), e.runs[0].t)
        assertArrayEquals(longArrayOf(5000, 5100), e.runs[1].t)
        assertArrayEquals(longArrayOf(9000), e.runs[2].t)
        assertEquals(6f, e.max)
        val none = Downsample.minMax(t, y, 30_000, 40_000, 100)
        assertTrue(none.empty)
        assertTrue(none.min.isNaN())
        // one column, min before max: both, in time order
        val one = Downsample.minMax(longArrayOf(0, 10, 20, 30), floatArrayOf(5f, 1f, 9f, 5f), 0, 1000, 1)
        assertArrayEquals(longArrayOf(10, 20), one.runs[0].t)
        assertArrayEquals(floatArrayOf(1f, 9f), one.runs[0].y, 0f)
    }

    @Test fun ringKeepsTheNewestAndCopiesAWindow() {
        val r = SampleRing(capacity = 1000)
        assertEquals(0, r.copy().size)
        assertNull(r.lastT)
        for (i in 0 until 2500) r.add(i * 10L, i.toDouble(), i / 2.0, 1.0, 1.0, 40.0, 20.0, 1424.8 + i / 1000.0)
        assertEquals(1000, r.size)
        assertEquals(15_000L, r.firstT)
        assertEquals(24_990L, r.lastT)
        val all = r.copy()
        assertEquals(1000, all.size)
        for (i in 1 until all.size) assertTrue(all.t[i] > all.t[i - 1])
        val w = r.copy(24_000)
        assertEquals(100, w.size)
        assertEquals(24_000L, w.t[0])
        assertEquals(2400f, w.pFast[0])
        assertEquals(1200f, w.pBms[0])
        assertEquals(50, w.nearest(24_499))
        assertEquals(0, w.nearest(0))
        assertEquals(99, w.nearest(99_999))
        r.add(10L, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0)
        assertEquals("the clock never goes back", 24_990L, r.lastT)
        r.add(25_000L, null, null, null, null, null, null, null)
        assertTrue(r.copy(25_000).pFast[0].isNaN())
        r.clear()
        assertEquals(0, r.size)
        assertEquals(2 * 3600 * 20, SampleRing.CAPACITY)
    }

    @Test fun energyOverTheWindow() {
        // 100 W for 36 s, then 20 s of −50 W regen, a gap, 1 km on the app odometer
        val t = ArrayList<Long>(); val p = ArrayList<Float>(); val odo = ArrayList<Double>()
        var at = 0L
        for (i in 0 until 360) { t.add(at); p.add(100f); odo.add(1000.0 + i / 720.0); at += 100 }
        for (i in 0 until 200) { t.add(at); p.add(-50f); odo.add(1000.5 + i / 400.0); at += 100 }
        at += 10_000
        t.add(at); p.add(0f); odo.add(1001.0)
        val n = t.size
        val x = Samples(t.toLongArray(), p.toFloatArray(), FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), odo.toDoubleArray())
        val e = EnergySummary.of(x, fast = true)
        assertEquals(1.0, e.outWh, 1e-9)
        // the last regen value lasts until a sample 10 s later: a gap, not counted
        assertEquals(50.0 * 19.9 / 3600, e.inWh, 1e-9)
        assertEquals(55.9, e.seconds, 1e-9)
        assertEquals(1.0, e.km!!, 1e-9)
        assertEquals(e.netWh, e.whPerKm!!, 1e-9)
        assertEquals(e.netWh * 3600 / 55.9, e.avgW!!, 1e-9)
        val bms = EnergySummary.of(x, fast = false)
        assertEquals(0.0, bms.outWh, 0.0)
        val w = EnergySummary.of(x, true, start = 36_000, end = 56_000)
        assertEquals(0.0, w.outWh, 1e-9)
        assertNull(EnergySummary.EMPTY.avgW)
    }

    @Test fun ticksAndWindows() {
        val (ticks, step) = Ticks.ticks(-130.0, 470.0, 4)
        assertEquals(200.0, step, 0.0)
        assertEquals(listOf(-200.0, 0.0, 200.0, 400.0, 600.0), ticks)
        assertEquals(0, Ticks.decimals(200.0))
        assertEquals(1, Ticks.decimals(0.5))
        val (vt, vs) = Ticks.ticks(31.4, 32.1, 3)
        assertEquals(0.25, vs, 1e-12)
        assertEquals(31.25, vt.first(), 1e-9)
        assertEquals(listOf("60", "300", "900", "all"), ChartWindow.OPTIONS.map { it.first })
        assertEquals("вся поездка", ChartWindow.OPTIONS.last().second)
        assertEquals("300", ChartWindow.norm("bogus"))
        assertArrayEquals(longArrayOf(40_000, 100_000), ChartWindow.range("60", 100_000, 0, 90_000))
        assertArrayEquals("stopped: ends at the last sample", longArrayOf(-210_000, 90_000), ChartWindow.range("300", null, 0, 90_000))
        assertArrayEquals(longArrayOf(5000, 100_000), ChartWindow.range(ChartWindow.ALL, 100_000, 5000, 90_000))
        assertArrayEquals("at least 10 s wide", longArrayOf(5000, 15_000), ChartWindow.range(ChartWindow.ALL, null, 5000, 6000))
        assertEquals(15, ChartWindow.relStepS(60.0))
        assertEquals(60, ChartWindow.relStepS(300.0))
        assertEquals(300, ChartWindow.relStepS(900.0))
        assertEquals(1800, ChartWindow.clockStepS(7200.0))
    }

    /** The prototype's axes: 20.10 at 1800 s, 5 min (−430…380 W, 0…27 km/h, 31,1…37,0 V), and the whole ride. */
    @Test fun axesLikeThePrototype() {
        assertEquals(listOf(-500.0, -250.0, 0.0, 250.0, 500.0) to 250.0, ChartAxis.power(-430.0, 380.0))
        assertEquals("the whole ride: over 900 W of span, 500 W steps", listOf(-1000.0, -500.0, 0.0, 500.0, 1000.0) to 500.0, ChartAxis.power(-560.0, 610.0))
        assertEquals("standing: one step either side of zero", listOf(-250.0, 0.0, 250.0), ChartAxis.power(0.0, 3.0).first)
        assertEquals(listOf(-250.0, 0.0, 250.0), ChartAxis.power(Double.NaN, Double.NaN).first)
        assertEquals(1000.0, ChartAxis.power(-200.0, 3100.0).second, 0.0)
        assertEquals(listOf(0.0, 10.0, 20.0, 30.0), ChartAxis.speed(27.0).first)
        assertEquals("at least 0…10", listOf(0.0, 10.0), ChartAxis.speed(0.0).first)
        assertEquals(listOf(30.0, 35.0, 40.0), ChartAxis.volts(31.1, 37.0).first)
        assertEquals(listOf(30.0, 35.0, 40.0, 45.0), ChartAxis.volts(31.2, 42.4).first)
        assertEquals("at least 5 V wide", listOf(30.0, 35.0), ChartAxis.volts(34.9, 35.0).first)
        assertEquals(listOf(30.0, 35.0), ChartAxis.volts(Double.NaN, 1.0).first)
    }

    @Test fun markerLabelsNeverOverlap() {
        assertEquals("11% еду домой", MarkerLabels.text("11% еду домой"))
        assertEquals("55%, возвращ…", MarkerLabels.text("55%, возвращаюсь домой"))
        assertEquals("дома, 23%. в…", MarkerLabels.text("дома, 23%. в гараже"))
        assertEquals("trailing space trimmed before «…»", "Газ в горку…", MarkerLabels.text("Газ в горку 25 %"))
        // right of the line; at the right edge left of it; one too close to a placed label is left out
        val at = MarkerLabels.place(floatArrayOf(10f, 40f, 150f, 290f), floatArrayOf(50f, 50f, 50f, 50f), right = 300f, pad = 3f, gap = 6f)
        assertEquals(13f, at[0], 0f)
        assertTrue("overlaps the first", at[1].isNaN())
        assertEquals(153f, at[2], 0f)
        assertEquals("left of its line at the edge", 237f, at[3], 0f)
        val close = MarkerLabels.place(floatArrayOf(150f, 290f), floatArrayOf(50f, 50f), right = 300f, pad = 3f, gap = 40f)
        assertTrue("within the gap of the one before", close[1].isNaN())
        assertTrue(MarkerLabels.place(FloatArray(0), FloatArray(0), 100f, 3f, 6f).isEmpty())
    }
}
