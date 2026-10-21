package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NB = "\u00A0"

private fun kmh(mph: Double) = mph * Fmt.MILE

class CuePointsTest {
    @Test fun flysPointsAndTheOtherModesScaled() {
        assertEquals(15.5, CuePoints.tap(15.5, 3).onMph, 1e-9)
        assertEquals(14.9, CuePoints.tap(15.5, 3).offMph, 1e-9)
        assertEquals(CuePoint(17.5, 17.0), CuePoints.rumble(17.5, 3))
        // TAKEOFF (9 mph): 8.7 / 9.8, the hysteresis scaled too (0.34 / 0.28 mph)
        val tt = CuePoints.tap(15.5, 1)
        val tr = CuePoints.rumble(17.5, 1)
        assertEquals(8.72, tt.onMph, 0.01)
        assertEquals(8.38, tt.offMph, 0.01)
        assertEquals(9.84, tr.onMph, 0.01)
        assertEquals(9.56, tr.offMph, 0.01)
        // TAKEOFF's taper (8.7–9.5 mph, none from 15.3 km/h): the rumble is above it, off over its 15.1 km/h cruise
        assertTrue(kmh(tr.onMph) > 15.3 && kmh(tr.offMph) > 15.1)
        // GLIDE (12.5 mph): 12.1 / 13.7
        assertEquals(12.11, CuePoints.tap(15.5, 2).onMph, 0.01)
        assertEquals(13.67, CuePoints.rumble(17.5, 2).onMph, 0.01)
        // no mode or an unknown one: FLY's
        assertEquals(CuePoints.tap(15.5, 3), CuePoints.tap(15.5, null))
        assertEquals(CuePoints.rumble(17.5, 3), CuePoints.rumble(17.5, 0))
        assertEquals(1.0, CuePoints.scale(7), 0.0)
    }

    @Test fun fromFlysLimiter() {
        // 20.10 and every FLY log: −25 % of the current at 24.75 km/h, none from ≈27.75 km/h
        assertEquals(24.94, kmh(SpeedPref.TAP_MPH), 0.01)
        assertEquals(28.16, kmh(SpeedPref.RUMBLE_MPH), 0.01)
        assertTrue(kmh(SpeedPref.TAP_MPH) > 24.75 && kmh(SpeedPref.RUMBLE_MPH) > 27.75)
        // the rumble lets go above a full battery's flat cruise (26.3 km/h): off after the hill
        assertTrue(kmh(CuePoints.rumble(SpeedPref.RUMBLE_MPH, 3).offMph) > 26.3)
    }
}

class ConfirmTest {
    @Test fun twoFreshSamplesAtLeastApart() {
        val c = Confirm(300)
        assertFalse(c.update(true, 1_000))
        assertFalse(c.update(true, 1_200))
        // the same sample again (a timer, a setting): not a second one
        assertFalse(c.update(true, 1_200))
        assertTrue(c.update(true, 1_300))
        assertTrue(c.update(true, 1_400))
        // one sample below: from the start
        assertFalse(c.update(false, 1_500))
        assertFalse(c.update(true, 1_600))
        assertTrue(c.update(true, 1_900))
        assertFalse(c.update(true, null))
        c.reset()
        assertFalse(c.update(true, 2_000))
    }
}

class LimiterTapTest {
    private val fly = CuePoints.tap(SpeedPref.TAP_MPH, 3)
    private val pause = 60_000L

    /** One fresh sample at [t] ms (its own time): true for a tap. */
    private fun LimiterTap.at(mph: Double, t: Long, point: CuePoint = fly, pauseMs: Long = pause, live: Boolean = true, on: Boolean = true) =
        update(on, point, pauseMs, kmh(mph), live, t, t)

    /** Samples every 100 ms from [t0] for [ms] at [mph]: the times of the taps. */
    private fun LimiterTap.ride(mph: Double, t0: Long, ms: Long, point: CuePoint = fly, pauseMs: Long = pause): List<Long> =
        (t0 until t0 + ms step 100).filter { at(mph, it, point, pauseMs) }

    @Test fun oneTapOverTheLimiterPoint() {
        val a = LimiterTap()
        assertEquals(emptyList<Long>(), a.ride(14.0, 0, 5_000))
        // above it for 0.3 s over two updates: one tap, then quiet however long the cruise
        assertFalse(a.at(15.6, 5_000))
        assertFalse(a.at(15.6, 5_200))
        assertTrue(a.at(15.6, 5_300))
        assertFalse(a.armed)
        assertEquals(emptyList<Long>(), a.ride(16.0, 5_400, 120_000))
        // just under 15.5: not above
        val b = LimiterTap()
        assertEquals(emptyList<Long>(), b.ride(15.45, 0, 5_000))
        assertEquals(listOf(5_300L), b.ride(15.51, 5_000, 2_000))
    }

    @Test fun aLoneReadingDoesNotTap() {
        val a = LimiterTap()
        for (k in 0 until 20) {
            val t = k * 1_000L
            assertFalse(a.at(16.0, t))
            assertFalse(a.at(15.0, t + 100))
        }
        assertTrue(a.armed)
    }

    @Test fun armedAgainBelowTheLineOnceThePauseIsOver() {
        val a = LimiterTap()
        assertEquals(listOf(300L), a.ride(16.0, 0, 2_000))
        // below 15.5 but not under 14.9: not armed, however long
        assertEquals(emptyList<Long>(), a.ride(15.0, 2_000, 100_000))
        assertEquals(emptyList<Long>(), a.ride(16.0, 102_000, 2_000))
        // under 14.9 within the pause: not armed either, and that dip is not remembered
        val b = LimiterTap()
        assertEquals(listOf(300L), b.ride(16.0, 0, 2_000))
        b.ride(14.0, 2_000, 10_000)
        assertFalse(b.armed)
        assertEquals(emptyList<Long>(), b.ride(16.0, 12_000, 60_000))
        // under 14.9 after it: armed, the next rise taps
        b.ride(14.0, 72_000, 1_000)
        assertTrue(b.armed)
        assertEquals(listOf(73_300L), b.ride(16.0, 73_000, 2_000))
    }

    @Test fun theTwoMinutePause() {
        val a = LimiterTap()
        val two = 120_000L
        assertEquals(listOf(300L), a.ride(16.0, 0, 1_000, pauseMs = two))
        a.ride(14.0, 1_000, 79_000, pauseMs = two)
        assertFalse(a.armed)
        assertEquals(emptyList<Long>(), a.ride(16.0, 80_000, 1_000, pauseMs = two))
        a.ride(14.0, 81_000, 40_000, pauseMs = two)
        assertTrue(a.armed)
        assertEquals(listOf(121_300L), a.ride(16.0, 121_000, 1_000, pauseMs = two))
        assertEquals(120_000L, WatchPrefs(tapPauseS = 120).tapPauseMs)
        assertEquals(60_000L, WatchPrefs().tapPauseMs)
    }

    @Test fun offOrWithoutLiveDataNothingHappens() {
        val a = LimiterTap()
        for (t in 0L until 2_000L step 100) assertFalse(a.at(16.0, t, on = false))
        for (t in 2_000L until 4_000L step 100) assertFalse(a.at(16.0, t, live = false))
        assertFalse(a.update(true, fly, pause, null, true, 4_000, 4_000))
        assertTrue(a.armed)
        // switched on above the point: it taps, once confirmed
        assertEquals(listOf(5_300L), a.ride(16.0, 5_000, 1_000))
    }

    @Test fun eachModeAtItsOwnPoint() {
        // TAKEOFF: 8.7 mph = 14.0 km/h; its cruise 14.5–15.1 km/h on 20.10 taps once
        val take = CuePoints.tap(SpeedPref.TAP_MPH, 1)
        val a = LimiterTap()
        assertEquals(emptyList<Long>(), a.ride(13.9 / Fmt.MILE, 0, 2_000, take))
        assertEquals(listOf(2_300L), a.ride(14.8 / Fmt.MILE, 2_000, 30_000, take))
        // FLY's point would be far off
        val b = LimiterTap()
        assertEquals(emptyList<Long>(), b.ride(14.8 / Fmt.MILE, 0, 30_000))
    }
}

class OverspeedAlarmTest {
    private val fly = CuePoints.rumble(SpeedPref.RUMBLE_MPH, 3)

    private fun OverspeedAlarm.at(mph: Double, t: Long, point: CuePoint = fly) = update(true, point, kmh(mph), true, t, t)

    @Test fun onAfterTwoUpdatesAboveOffHalfAMileBelow() {
        val a = OverspeedAlarm()
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.0, 0))
        // just under 17.5: not above
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.45, 100))
        // above: the first update only starts the count, the one 0.3 s later starts the rumble
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.6, 200))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.6, 400))
        assertFalse(a.on)
        assertEquals(OverspeedAlarm.Action.START, a.at(17.7, 500))
        assertTrue(a.on)
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.2, 600))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.01, 700))
        assertTrue(a.on)
        assertEquals(OverspeedAlarm.Action.STOP, a.at(16.99, 800))
        assertFalse(a.on)
        // back between: stays off until above 17.5 for 0.3 s again
        assertEquals(OverspeedAlarm.Action.NONE, a.at(17.4, 900))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(18.0, 1_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(18.0, 1_299))
        assertEquals(OverspeedAlarm.Action.START, a.at(18.0, 1_300))
    }

    @Test fun aLoneReadingOrATimerDoesNotStartIt() {
        val a = OverspeedAlarm()
        for (k in 0 until 20) {
            assertEquals(OverspeedAlarm.Action.NONE, a.at(18.0, k * 1_000L))
            assertEquals(OverspeedAlarm.Action.NONE, a.at(17.0, k * 1_000L + 100))
        }
        // the same sample looked at again later (the service's timer): still one sample
        val b = OverspeedAlarm()
        assertEquals(OverspeedAlarm.Action.NONE, b.update(true, fly, kmh(18.0), true, 0, 0))
        assertEquals(OverspeedAlarm.Action.NONE, b.update(true, fly, kmh(18.0), true, 0, 1_000))
        assertEquals(OverspeedAlarm.Action.START, b.update(true, fly, kmh(18.0), true, 300, 1_300))
    }

    @Test fun armedAgainEveryTwoSeconds() {
        val a = OverspeedAlarm()
        a.at(18.0, 9_700)
        assertEquals(OverspeedAlarm.Action.START, a.at(18.0, 10_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(18.0, 11_999))
        assertEquals(OverspeedAlarm.Action.START, a.at(18.0, 12_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.at(18.0, 13_000))
        assertEquals(OverspeedAlarm.REARM_MS, a.rearmMs)
    }

    @Test fun stopsAtOnceWithoutLiveData() {
        val over = kmh(20.0)
        for (stop in listOf<(OverspeedAlarm) -> OverspeedAlarm.Action>(
            { it.update(true, fly, over, false, 1_000, 1_000) },                      // stale / not streaming / link down
            { it.update(false, fly, over, true, 1_000, 1_000) },                      // switched off on the details page
            { it.update(true, fly, null, true, 1_000, 1_000) },                       // no speed
            { it.update(true, CuePoints.rumble(22.0, 3), over, true, 1_000, 1_000) }, // threshold raised past the speed
            { it.update(true, CuePoints.rumble(17.5, 1), kmh(9.0), true, 1_000, 1_000) }, // TAKEOFF now, under its 9.6
            { it.stop() },                                                            // the service stops
        )) {
            val a = OverspeedAlarm()
            a.update(true, fly, over, true, 0, 0)
            assertEquals(OverspeedAlarm.Action.START, a.update(true, fly, over, true, 300, 300))
            assertEquals(OverspeedAlarm.Action.STOP, stop(a))
            assertFalse(a.on)
        }
        assertEquals(OverspeedAlarm.Action.NONE, OverspeedAlarm().stop())
    }

    @Test fun stopsWithinAboutASecondAndAHalfWithoutNewData() {
        // what RideService passes: live and fresh; the phone's heartbeat keeps «streaming» true 3 s after the last frame
        val t = Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = kmh(19.0))
        fun live(freshAt: Long, rxAt: Long, now: Long) = Health.of(t, rxAt, now).live && OverspeedAlarm.fresh(freshAt, now)
        val a = OverspeedAlarm()
        a.update(true, fly, t.speedKmh, live(9_700, 9_700, 9_700), 9_700, 9_700)
        assertEquals(OverspeedAlarm.Action.START, a.update(true, fly, t.speedKmh, live(10_000, 10_000, 10_000), 10_000, 10_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.update(true, fly, t.speedKmh, live(10_000, 11_000, 11_500), 10_000, 11_500))
        assertEquals(OverspeedAlarm.Action.STOP, a.update(true, fly, t.speedKmh, live(10_000, 11_000, 11_501), 10_000, 11_501))
        assertTrue(OverspeedAlarm.FRESH_MS < Health.STALE_MS)
        assertFalse(Health.of(t, 10_000, 13_001).live)
        assertFalse(Health.of(t.copy(link = Link.RECONNECTING), 10_000, 10_000).live)
        assertFalse(Health.of(t.copy(streaming = false), 10_000, 10_000).live)
    }

    @Test fun aRepeatingWaveformAndAShortTap() {
        val t = OverspeedAlarm.TIMINGS
        val amp = OverspeedAlarm.AMPLITUDES
        assertEquals(t.size, amp.size)
        assertTrue(OverspeedAlarm.REPEAT in t.indices)
        // the repeated part is mostly on, at full strength
        val on = t.indices.filter { it >= OverspeedAlarm.REPEAT && amp[it] > 0 }.sumOf { t[it] }
        val all = t.indices.filter { it >= OverspeedAlarm.REPEAT }.sumOf { t[it] }
        assertTrue(on >= 0.8 * all)
        assertEquals(255, amp.max())
        // re-armed before the system could notice a gap
        assertTrue(OverspeedAlarm.REARM_MS < Health.STALE_MS)
        // the tap: one pulse of ~150 ms, firmer and longer than an amber lamp's
        assertEquals(LimiterTap.TIMINGS.size, LimiterTap.AMPLITUDES.size)
        assertEquals(1, LimiterTap.AMPLITUDES.count { it > 0 })
        assertEquals(150L, LimiterTap.TIMINGS.filterIndexed { i, _ -> LimiterTap.AMPLITUDES[i] > 0 }.sum())
        assertTrue(LimiterTap.AMPLITUDES.max() > Buzz.AMBER.amplitudes.max())
    }
}

class SpeedCuesTest {
    private fun t(mph: Double, mode: Int? = 3) = Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = kmh(mph), mode = mode)

    @Test fun aTapThenTheRumbleDownTheHill() {
        val c = SpeedCues()
        val p = WatchPrefs()
        val out = ArrayList<String>()
        var now = 0L
        fun go(mph: Double, ms: Long) {
            val end = now + ms
            while (now < end) {
                val o = c.update(p, t(mph), true, now, now)
                if (o.tap) out += "tap@$now"
                if (o.rumble != OverspeedAlarm.Action.NONE) out += "${o.rumble}@$now"
                now += 100
            }
        }
        go(14.0, 1_000)
        go(16.0, 1_000)    // the limiter: one tap
        go(18.0, 1_000)    // the hill: the rumble, armed again every 2 s
        go(18.5, 2_000)
        go(16.5, 1_000)    // braked: off below 17.0
        go(14.0, 1_000)
        go(16.0, 1_000)    // within the minute: no second tap
        assertEquals(listOf("tap@1300", "START@2300", "START@4300", "STOP@5000"), out)
    }

    @Test fun aNewModeWaitsUntilTheSpeedIsUnderItsPoints() {
        // 16.10: FLY → TAKEOFF at 16.4 km/h (10.2 mph) to slow down; TAKEOFF's points are 8.7 / 9.8 mph
        val c = SpeedCues()
        val p = WatchPrefs()
        val out = ArrayList<String>()
        var now = 0L
        fun go(kmh: Double, mode: Int, ms: Long) {
            val end = now + ms
            while (now < end) {
                val o = c.update(p, Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = kmh, mode = mode), true, now, now)
                if (o.tap) out += "tap@$now"
                if (o.rumble != OverspeedAlarm.Action.NONE) out += "${o.rumble}@$now"
                now += 100
            }
        }
        go(16.4, 3, 2_000)
        go(16.4, 1, 1_000)      // switched: no rumble, no tap
        assertTrue(c.rumble.held && c.tap.held)
        go(15.0, 1, 1_000)      // under the rumble's 9.56 mph (15.39 km/h): its hold is over
        assertFalse(c.rumble.held)
        assertTrue(c.tap.held)
        go(13.2, 1, 1_000)      // under the tap's 8.38 mph (13.49 km/h): its too
        assertFalse(c.tap.held)
        go(14.5, 1, 1_000)      // TAKEOFF's limiter: the tap
        go(17.7, 1, 1_000)      // downhill past TAKEOFF's zero: the rumble
        assertEquals(listOf("tap@5300", "START@6300"), out)
        // one running goes on through a change of mode
        go(29.0, 3, 1_000)
        assertTrue(c.rumble.on)
        assertTrue(out.none { it.startsWith("STOP") })
        // the first mode seen is no change
        val d = SpeedCues()
        assertFalse(d.update(p, Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = 26.0, mode = 3), true, 0, 0).tap)
        assertTrue(d.update(p, Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = 26.0, mode = 3), true, 300, 300).tap)
    }

    @Test fun noTapWhileTheRumbleRuns() {
        // a tap point set above the rumble's: the rumble wins, the tap would cut it short
        val c = SpeedCues()
        val p = WatchPrefs(tapMph = 18.0, overspeedMph = 17.5)
        var taps = 0
        for (k in 0 until 50) if (c.update(p, t(19.0), true, k * 100L, k * 100L).tap) taps++
        assertEquals(0, taps)
        assertTrue(c.rumble.on)
        assertEquals(OverspeedAlarm.Action.STOP, c.stop())
    }

    @Test fun theSwitchesAreTheirOwn() {
        val c = SpeedCues()
        val p = WatchPrefs(tapOn = false)
        var taps = 0
        var starts = 0
        for (k in 0 until 50) {
            val o = c.update(p, t(19.0), true, k * 100L, k * 100L)
            if (o.tap) taps++
            if (o.rumble == OverspeedAlarm.Action.START) starts++
        }
        assertEquals(0, taps)
        assertTrue(starts > 0)
        val d = SpeedCues()
        var dTaps = 0
        for (k in 0 until 50) {
            val o = d.update(WatchPrefs(overspeedOn = false), t(19.0), true, k * 100L, k * 100L)
            assertEquals(OverspeedAlarm.Action.NONE, o.rumble)
            if (o.tap) dTaps++
        }
        assertEquals(1, dTaps)
    }
}

class SpeedPrefTest {
    @Test fun halfMileSteps() {
        assertEquals(16.0, SpeedPref.clamp(16.2, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(16.5, SpeedPref.clamp(16.3, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(25.0, SpeedPref.clamp(100.0, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(5.0, SpeedPref.clamp(1.0, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(17.5, SpeedPref.clamp(Double.NaN, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(15.5, SpeedPref.clamp(Double.NaN, SpeedPref.TAP_MPH), 0.0)
        assertEquals(18.0, SpeedPref.step(17.5, 1, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(15.0, SpeedPref.step(15.5, -1, SpeedPref.TAP_MPH), 0.0)
        assertEquals(25.0, SpeedPref.step(25.0, 1, SpeedPref.RUMBLE_MPH), 0.0)
        assertEquals(5.0, SpeedPref.step(5.0, -1, SpeedPref.TAP_MPH), 0.0)
        // 5 … 25 in 0,5: 41 values, 39 between the ends
        assertEquals(39, SpeedPref.STEPS)
    }

    @Test fun texts() {
        assertEquals("17,5${NB}mph", SpeedPref.mph(17.5))
        assertEquals("16${NB}mph", SpeedPref.mph(16.0))
        assertEquals("15,5${NB}mph · 24,9${NB}км/ч", SpeedPref.describe(15.5, "kmh"))
        assertEquals("17,5${NB}mph · 28,2${NB}км/ч", SpeedPref.describe(17.5, "kmh"))
        assertEquals("17,5${NB}mph", SpeedPref.describe(17.5, "mph"))
        assertEquals("TAKEOFF 8,7 · GLIDE 12,1 mph", SpeedPref.otherModes(15.5))
        assertEquals("TAKEOFF 9,8 · GLIDE 13,7 mph", SpeedPref.otherModes(17.5))
        assertEquals("не чаще раза в 60 с", TapPause.describe(60))
        assertEquals("не чаще раза в 120 с", TapPause.describe(120))
    }

    @Test fun defaults() {
        val p = WatchPrefs()
        assertTrue(p.tapOn)
        assertEquals(15.5, p.tapMph, 0.0)
        assertEquals(60, p.tapPauseS)
        assertTrue(p.overspeedOn)
        assertEquals(17.5, p.overspeedMph, 0.0)
        assertTrue(p.lampBuzz)
    }

    @Test fun thePauseCycles() {
        assertEquals(listOf(60, 120), TapPause.CHOICES_S)
        assertEquals(120, TapPause.next(60))
        assertEquals(60, TapPause.next(120))
        assertEquals(60, TapPause.of(45))
        assertEquals(120, TapPause.next(45))
    }
}

class PrefsMigrationTest {
    @Test fun v107sDefaultMovesOnAChosenValueStays() {
        // v107 saved its default 16 with every setting: that becomes 17.5
        assertEquals(17.5, PrefsMigration.overspeedMph(0, 16.0), 0.0)
        // moved by the owner: kept
        assertEquals(16.5, PrefsMigration.overspeedMph(0, 16.5), 0.0)
        assertEquals(14.0, PrefsMigration.overspeedMph(0, 14.0), 0.0)
        // never saved: the new default
        assertEquals(17.5, PrefsMigration.overspeedMph(0, null), 0.0)
        assertEquals(17.5, PrefsMigration.overspeedMph(0, Double.NaN), 0.0)
        // once migrated, a 16 chosen later stays 16
        assertEquals(16.0, PrefsMigration.overspeedMph(PrefsMigration.VERSION, 16.0), 0.0)
        assertEquals(2, PrefsMigration.VERSION)
        // a stored float: 16f reads back as exactly 16.0
        assertEquals(17.5, PrefsMigration.overspeedMph(1, 16.0f.toDouble()), 0.0)
        assertEquals(25.0, PrefsMigration.overspeedMph(2, 40.0), 0.0)
    }
}
