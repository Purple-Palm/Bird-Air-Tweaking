package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.RingLayout
import io.github.purplepalm.birdair.dial.RingPick
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Кольцо» on real rides: every second of the log ([DialReplay], as the watch gets it — the cues, the buzzes, the health)
 * laid out at that moment and in its transitions, with a tap now and then: no exception, nothing out of the ring or over
 * another thing, the centre TellTales.top's lamp.
 */
class RingReplayTest {
    private fun ride(path: String, name: String): Map<String, Int> {
        RideReplay.assume(path)
        val m = RingLayout.ROUGH
        val seen = HashMap<String, Int>()
        var n = 0
        var pick: RingPick? = null
        for (s in DialReplay.states(path, everyMs = 1_000)) {
            n++
            // a tap every 47 s, while something is lit
            if (n % 47 == 0) RingLayout.next(s, s.nowMs, pick)?.let { pick = RingPick(it, s.nowMs) }
            val p = pick?.takeIf { s.nowMs - it.atMs < RingLayout.PICK_MS + RingLayout.XFADE_MS }
            for (dt in longArrayOf(0, 150, 350)) {
                val at = s.nowMs + dt
                val f = RingLayout.frame(s, at, m, p)
                val bad = RingLayout.problems(s, at, f, p)
                assertTrue("$name @${s.nowMs / 1000} s +$dt: $bad", bad.isEmpty())
                if (dt == 0L) {
                    f.centre.tgt?.let { seen.merge("centre:" + it.id + ":" + it.level, 1, Int::plus) }
                    if (s.status != null) seen.merge("status", 1, Int::plus)
                    if (s.cues.rumble) seen.merge("rumble", 1, Int::plus)
                    if (f.boxes.any { it.what.startsWith("Ограничитель") }) seen.merge("limiter", 1, Int::plus)
                    if (f.tiles.isNotEmpty()) seen.merge("tiles", 1, Int::plus)
                    if (p != null && f.centre.tgt?.id == p.id && p.id != s.lamps.top?.id) seen.merge("picked", 1, Int::plus)
                }
                RingLayout.wakeAt(s, at, p)
            }
        }
        assertTrue("$name: $n s", n > 600)
        return seen
    }

    /** 20.10, 100 % to the BMS's cut: the charge red in the centre, the limiter's line after its taps, old data, taps. */
    @Test fun theRideToZero() {
        val seen = ride(RideReplay.TO_ZERO, "zero")
        assertTrue(seen.toString(), (seen["centre:${TellTales.BATTERY}:${LampLevel.RED}"] ?: 0) > 0)
        assertTrue(seen.toString(), (seen["limiter"] ?: 0) > 0)
        assertTrue(seen.toString(), (seen["status"] ?: 0) > 0)
        assertTrue(seen.toString(), (seen["picked"] ?: 0) > 0)
    }

    /** 15.10, two-up to the hot cut-off: the temperature amber then red, the rumble downhill. */
    @Test fun theHotCutoff() {
        val seen = ride(CUTOFF_HOT, "hot")
        assertTrue(seen.toString(), (seen["centre:${TellTales.TEMP}:${LampLevel.RED}"] ?: 0) > 0)
        assertTrue(seen.toString(), (seen["centre:${TellTales.TEMP}:${LampLevel.AMBER}"] ?: 0) > 0)
        assertTrue(seen.toString(), (seen["rumble"] ?: 0) > 0)
    }

    /** 16.10, two-up to half: «мало тяги» at the current limit. */
    @Test fun theTwoUpRide() {
        val seen = ride(TWO_UP, "twoup")
        assertTrue(seen.toString(), (seen["centre:${TellTales.TRACTION}:${LampLevel.AMBER}"] ?: 0) > 0)
    }

    companion object {
        const val CUTOFF_HOT = "logs/20261015-155523-log-app.json.gz"
        const val TWO_UP = "logs/20261016-135138-log-app.json.gz"
    }
}
