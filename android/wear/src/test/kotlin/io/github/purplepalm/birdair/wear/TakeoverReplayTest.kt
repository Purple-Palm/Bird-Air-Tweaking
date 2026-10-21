package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.TakeoverLayout
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Захват экрана» on real rides, as the watch would get them ([DialReplay]: the phone's pipeline, the health, the tap,
 * the rumble and the lamp buzzes): every second of the log laid out at that moment and 1,5 s on — inside the circle,
 * nothing on top of anything once settled, the centre's first lamp TellTales.top().
 */
class TakeoverReplayTest {
    private val m = TakeoverLayout.ROUGH

    private class Seen(var frames: Int = 0, var settled: Int = 0, var takeovers: Int = 0, var slot: Int = 0, var idle: Int = 0, val bad: MutableList<String> = ArrayList())

    private fun ride(path: String, everyMs: Long = 1_000): Seen {
        RideReplay.assume(path)
        val seen = Seen()
        var last: Long? = null
        DialReplay.replay(path) { s ->
            if (last != null && s.nowMs - last!! < everyMs) return@replay
            last = s.nowMs
            check(s, s.nowMs, seen)
            check(s, s.nowMs + 1_500, seen)
            if (TakeoverLayout.wakeAt(s, s.nowMs + 1_500) == null) seen.idle++
        }
        return seen
    }

    private fun check(s: DialState, now: Long, seen: Seen) {
        val f = TakeoverLayout.frame(s, now, m)
        seen.frames++
        if (f.settled) seen.settled++
        if (f.takeover > 0.99f) seen.takeovers++
        if (f.slot.isNotEmpty()) seen.slot++
        val p = TakeoverLayout.problems(f, m)
        if (p.isNotEmpty() && seen.bad.size < 20) seen.bad += "at ${s.nowMs} (+${now - s.nowMs}): $p ${s.lamps.lit.map { it.id + ":" + it.level }}"
        val top = s.lamps.top
        if (f.settled && top != null && top.level != LampLevel.YELLOW && f.main != top.id && seen.bad.size < 20)
            seen.bad += "at ${s.nowMs}: the centre has ${f.main}, top() is ${top.id}"
    }

    private fun assertClean(name: String, seen: Seen) {
        println("$name: ${seen.frames} frames, ${seen.settled} settled, ${seen.takeovers} in the takeover, ${seen.slot} with the slot, ${seen.idle} idle")
        assertTrue("$name: ${seen.bad.joinToString("\n")}", seen.bad.isEmpty())
        assertTrue("$name: ${seen.frames} frames, ${seen.settled} settled", seen.frames > 1_000 && seen.settled > seen.frames / 3)
    }

    /** 20.10, 100 % to the BMS's cut: the red takeover at the cut. */
    @Test fun theRideToZero() {
        val seen = ride(RideReplay.TO_ZERO)
        assertClean("TO_ZERO", seen)
        assertTrue("takeovers ${seen.takeovers}", seen.takeovers > 0)
        assertTrue("idle ${seen.idle}", seen.idle > 0)
    }

    /** 15.10, two-up, the hot cut-off: «Перегрев» red, then dark. */
    @Test fun theHotCutoff() {
        val seen = ride(CUTOFF_HOT)
        assertClean("CUTOFF_HOT", seen)
        assertTrue("takeovers ${seen.takeovers}", seen.takeovers > 0)
        assertTrue("slot ${seen.slot}", seen.slot > 0)
    }

    /** 16.10, two-up to half the battery: «Мало тяги» and friends. */
    @Test fun aTwoUpRide() {
        val seen = ride(TWO_UP)
        assertClean("TWO_UP", seen)
        assertTrue("slot ${seen.slot}", seen.slot > 0)
    }

    companion object {
        const val CUTOFF_HOT = "logs/20261015-155523-log-app.json.gz"
        const val TWO_UP = "logs/20261016-135138-log-app.json.gz"
    }
}
