package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.dial.ChipEl
import io.github.purplepalm.birdair.dial.Kind
import io.github.purplepalm.birdair.dial.StackCheck
import io.github.purplepalm.birdair.dial.StackTimeline
import io.github.purplepalm.birdair.dial.TextEl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Крупные лампы» on real rides ([DialReplay]: the phone's pipeline, the watch's health, cues and buzzes): every second
 * of the log it lays out (and mid-change, 350 ms on) inside the round screen; on still frames the main chip is the top
 * lamp and nothing overlaps; between changes the face asks for no frames.
 */
class StackReplayTest {
    private class Tally(var frames: Int = 0, var still: Int = 0, var idle: Int = 0, var reds: Int = 0, var mains: Int = 0, val texts: HashSet<String> = HashSet())

    private fun ride(path: String): Tally {
        RideReplay.assume(path)
        val tl = StackTimeline()
        val n = Tally()
        var next = Long.MIN_VALUE
        DialReplay.replay(path) { s ->
            tl.feed(s)
            if (s.nowMs < next) return@replay
            next = s.nowMs + 1_000
            for (at in longArrayOf(s.nowMs, s.nowMs + 350)) {
                val sc = StackCheck.scene(s, tl, at)
                n.frames++
                val out = StackCheck.outside(sc)
                assertTrue("$path at $at: $out", out.isEmpty())
                if (at == s.nowMs && StackCheck.settled(tl, at)) {
                    n.still++
                    val bad = StackCheck.overlaps(sc, s)
                    assertTrue("$path at $at: $bad", bad.isEmpty())
                    if (tl.wakeAt(s, at, calm = false) == null) n.idle++
                    val hero = sc.hero.filterIsInstance<ChipEl>().singleOrNull { it.kind == Kind.HERO }
                    if (hero != null) {
                        n.mains++
                        if (hero.lamp.level == LampLevel.RED) n.reds++
                        n.texts += sc.text.filterIsInstance<TextEl>().joinToString(" ") { it.text }
                    }
                }
            }
        }
        return n
    }

    /** 20.10, 100 % to the BMS's cut: the red «BMS отключает» at the end. */
    @Test fun theRideToZero() {
        val n = ride(RideReplay.TO_ZERO)
        assertTrue(n.frames > 3_000)
        assertTrue("reds ${n.reds}", n.reds > 0)
        assertTrue(n.texts.any { it.startsWith("BMS отключает") })
        // most still seconds draw nothing: no frame loop while nothing moves
        assertTrue("idle ${n.idle} of ${n.still}", n.idle > n.still / 3)
    }

    /** 15.10, two-up to the thermal cutoff: the temperature climbs to red. */
    @Test fun theHotCutoff() {
        val n = ride(CUTOFF_HOT)
        assertTrue(n.reds > 0)
        assertTrue(n.texts.any { it.startsWith("Перегрев") })
    }

    /** 16.10, two-up to half: traction at the current limit lights again and again. */
    @Test fun theTwoUpRide() {
        val n = ride(TWO_UP_HALF)
        assertTrue(n.mains > 0)
        assertTrue(n.texts.any { it.startsWith("Мало тяги") })
        assertEquals(0, n.texts.count { it.isBlank() })
    }

    companion object {
        const val CUTOFF_HOT = "logs/20261015-155523-log-app.json.gz"
        const val TWO_UP_HALF = "logs/20261016-135138-log-app.json.gz"
    }
}
