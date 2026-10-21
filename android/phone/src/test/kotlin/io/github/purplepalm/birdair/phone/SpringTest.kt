package io.github.purplepalm.birdair.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SpringTest {
    /** Frames of [hz] until within [tol] of the target; the path along the way. */
    private fun stepResponse(hz: Double, tol: Double = 0.02): Pair<Double, List<Double>> {
        val s = CriticalSpring(0.0)
        val path = ArrayList<Double>()
        var t = 0.0
        var settledAt = -1.0
        while (t < 0.5) {
            s.step(1.0, 1 / hz)
            t += 1 / hz
            path.add(s.value)
            if (settledAt < 0 && abs(1.0 - s.value) <= tol) settledAt = t
        }
        return settledAt to path
    }

    @Test fun settlesIn100To120msWithoutOvershoot() {
        assertEquals(110.0, CriticalSpring.SETTLE_MS, 0.5)
        for (hz in doubleArrayOf(60.0, 90.0, 120.0)) {
            val (at, path) = stepResponse(hz)
            assertTrue("$hz Hz: settled at ${at * 1000} ms", at in 0.095..0.125)
            for (i in 1 until path.size) assertTrue("$hz Hz: monotonic", path[i] >= path[i - 1])
            assertTrue("$hz Hz: never past the target", path.all { it <= 1.0 })
            assertEquals("$hz Hz: on it in the end", 1.0, path.last(), 0.0)
        }
        // a third of the way within ~20 ms: the bar starts moving on the next frame
        val s = CriticalSpring(0.0)
        repeat(3) { s.step(1.0, 1 / 120.0) }
        assertTrue(s.value > 0.25)
    }

    @Test fun exactForAnyFrameRate() {
        val a = CriticalSpring(0.0)
        val b = CriticalSpring(0.0)
        for (i in 0 until 6) { a.step(0.8, 1 / 60.0); b.step(0.8, 1 / 120.0); b.step(0.8, 1 / 120.0) }
        assertEquals(a.value, b.value, 1e-9)
        assertEquals(a.velocity, b.velocity, 1e-6)
    }

    /** Data at ~18/s moving the target every few frames: never beyond what any target asked for, never across one. */
    @Test fun noOvershootWhileTheTargetMoves() {
        val rnd = Random(7)
        val s = CriticalSpring(0.0)
        var target = 0.0
        var lo = 0.0
        var hi = 0.0
        for (frame in 0 until 20_000) {
            if (frame % (5 + rnd.nextInt(4)) == 0) {
                target = if (rnd.nextInt(10) == 0) 0.0 else rnd.nextDouble(-1.0, 1.0)
                lo = minOf(s.value, target); hi = maxOf(s.value, target)
            }
            val before = s.value - target
            s.step(target, if (rnd.nextInt(50) == 0) 0.05 else 1 / 120.0)
            val after = s.value - target
            assertTrue("frame $frame: crossed the target", before * after >= 0 || after == 0.0)
            assertTrue("frame $frame: left [lo, hi]", s.value >= lo - 1e-12 && s.value <= hi + 1e-12)
            assertTrue("moves toward the target only", abs(after) <= abs(before) + 1e-12)
        }
    }

    @Test fun snapsAndSleeps() {
        val s = CriticalSpring(0.3)
        assertTrue(s.settled(0.3))
        for (i in 0 until 120) s.step(-0.5, 1 / 120.0)
        assertTrue("settled exactly, the frame loop can sleep", s.settled(-0.5))
        assertEquals(-0.5, s.value, 0.0)
        s.step(Double.NaN, 1 / 120.0)
        assertEquals(-0.5, s.value, 0.0)
        s.step(0.2, 0.0)
        assertEquals("no time, no move", -0.5, s.value, 0.0)
        s.step(0.2, 10.0)
        assertTrue("a long frame lands on the target", s.settled(0.2))
    }
}
