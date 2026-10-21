package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.DialBuilder
import io.github.purplepalm.birdair.dial.DialCues
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceKit
import io.github.purplepalm.birdair.dial.Glyphs
import io.github.purplepalm.birdair.dial.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The faces' input on a real ride, and the watch's screen as a DialState. */
class DialReplayTest {
    /** 20.10, 100 % to the BMS's cut: what the faces get, as the watch would have it. */
    @Test fun theRideToZero() {
        RideReplay.assume(RideReplay.TO_ZERO)
        var states = 0
        var taps = 0
        var lastTap: Long? = null
        var cut: Long? = null
        var reds = 0
        var prev: Long? = null
        DialReplay.replay(RideReplay.TO_ZERO) { s ->
            states++
            prev?.let { assertTrue(s.nowMs >= it) }
            prev = s.nowMs
            for (l in s.lamps.lit) {
                assertTrue(l.sinceMs <= l.changedMs && l.changedMs <= s.nowMs)
                assertTrue(l.lamp.title.isNotBlank() && l.lamp.title.length <= TellTales.TITLE_MAX)
            }
            if (s.cues.tapAtMs != null && s.cues.tapAtMs != lastTap) { taps++; lastTap = s.cues.tapAtMs }
            if (s.lamps[TellTales.BATTERY]?.lamp?.text == TellTales.T_BMS_CUT && cut == null) {
                cut = s.nowMs
                assertEquals("BMS отключает", s.lamps.top?.lamp?.title)
                assertEquals(Glyphs.BATTERY_CUT, FaceKit.icon(s.lamps.top!!.lamp))
            }
            if (s.cues.buzz?.level == LampLevel.RED) reds++
        }
        assertTrue(states > 10_000)
        assertEquals(19, taps)                                                       // as SpeedCueLogTest counts them
        assertTrue("the BMS cut at ${cut}", cut != null && cut!! in 1_800_000..1_815_000)
        assertTrue(reds > 0)
        val few = DialReplay.states(RideReplay.TO_ZERO, everyMs = 60_000)
        assertTrue(few.size in 30..60)
    }

    /** The screen as the faces get it: the rumble the ride service runs, the cues' points off with their switches, ambient. */
    @Test fun theScreenAsADialState() {
        val b = DialBuilder()
        val s = Screen(Samples.overspeed, Health.LIVE, overspeed = true, cues = DialCues(tapAtMs = 5_000), bulb = true, bulbAtMs = 9_000)
        val d = dialState(b, s, AmbientState(burnIn = true, tick = 2), WatchPrefs(tapOn = false), smooth = false, clock = "21:10", nowMs = 10_000)
        assertTrue(d.cues.rumble)
        assertEquals(10_000L, d.cues.rumbleSinceMs)
        assertEquals(5_000L, d.cues.tapAtMs)
        assertNull(d.speed.tapAt)
        assertEquals(17.5 * 1.609344, d.speed.rumbleAt!!, 1e-9)
        assertTrue(d.ambient && !d.lowBit)
        assertEquals(io.github.purplepalm.birdair.dial.BurnIn.offset(2), d.burnInDp)
        assertTrue(d.bulbOn); assertEquals(9_000L, d.bulbAtMs)
        assertEquals("21:10", d.clock)
        assertFalse(d.smooth)
        val calm = dialState(b, Screen(Samples.normal, Health.LIVE), null, WatchPrefs(), smooth = true, clock = "", nowMs = 11_000)
        assertFalse(calm.cues.rumble || calm.ambient)
        assertEquals(DialFace.STACK, WatchPrefs().face)
    }
}
