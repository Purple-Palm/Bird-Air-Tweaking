package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed cues on every FLY ride (RideReplay: the phone's pipeline, the watch as RideService runs it) with the
 * default settings, against v107's single rumble above 16 mph. The tap: once per climb to the limiter, at most once a
 * minute; the rumble: only past FLY's zero-current speed (≈27.75 km/h, downhill) and a TAKEOFF hill.
 */
class SpeedCueLogTest {
    /** taps (60 s pause), taps (120 s), rumbles and their seconds, v107's rumbles and seconds. */
    private data class Counts(val taps: Int, val taps120: Int, val rumbles: Int, val rumbleS: Double, val oldRumbles: Int, val oldS: Double)

    private class Ride(val run: CueRun, val run120: CueRun, val old: OldRumble)

    private companion object {
        val EXPECTED = mapOf(
            "logs/20261014-185422-log.json.gz" to Counts(18, 11, 7, 11.9, 38, 311.6),           // solo, 1468 s FLY
            "logs/20261015-155523-log-app.json.gz" to Counts(11, 7, 6, 11.0, 25, 138.1),        // two-up, hot cutoff
            "logs/20261016-135138-log-app.json.gz" to Counts(4, 3, 0, 0.0, 5, 34.2),
            "logs/20261016-190042-log-app.json.gz" to Counts(3, 3, 1, 10.8, 2, 13.0),           // + 138 s TAKEOFF
            "logs/20261019-174739-log-app.json.gz" to Counts(6, 5, 0, 0.0, 4, 21.6),
            "logs/20261019-210801-log-app.json.gz" to Counts(4, 4, 1, 4.6, 5, 41.0),
            "logs/20261020-214221-do-otsechki-do-nulya-app.json.gz" to Counts(19, 12, 0, 0.0, 34, 247.0), // 100 % → BMS cut
        )

        val rides: Map<String, Ride> by lazy {
            RideReplay.FLY.filter { java.io.File(RideReplay.root, it).isFile }.associateWith { path ->
                val r = CueRun()
                val r120 = CueRun(WatchPrefs(tapPauseS = 120))
                val old = OldRumble()
                RideReplay.snapshots(path) { t, fresh -> r.sample(t, fresh); r120.sample(t, fresh); old.sample(t, fresh) }
                Ride(r.end(), r120.end(), old.end())
            }
        }
    }

    @Test fun everyFlyRide() {
        RideReplay.assume(RideReplay.TO_ZERO)
        assertEquals(RideReplay.FLY.toSet(), EXPECTED.keys)
        val got = StringBuilder()
        for ((path, want) in EXPECTED) {
            val r = rides[path] ?: continue
            got.append(path).append(": taps ").append(r.run.taps.size).append(" / ").append(r.run120.taps.size)
                .append(", rumble ").append(r.run.rumbles.size).append(" · ").append("%.1f".format(r.run.onMs / 1000.0))
                .append(" s, v107 ").append(r.old.episodes).append(" · ").append("%.1f".format(r.old.onMs / 1000.0)).append(" s\n")
            val msg = got.toString()
            assertEquals(msg, want.taps, r.run.taps.size)
            assertEquals(msg, want.taps120, r.run120.taps.size)
            assertEquals(msg, want.rumbles, r.run.rumbles.size)
            assertEquals(msg, want.rumbleS, r.run.onMs / 1000.0, 0.06)
            assertEquals(msg, want.oldRumbles, r.old.episodes)
            assertEquals(msg, want.oldS, r.old.onMs / 1000.0, 0.06)
        }
        // all rides: 113 rumbles and 806 s before, 15 and 50 s now; 65 taps
        assertEquals(113, rides.values.sumOf { it.old.episodes })
        assertEquals(15, rides.values.sumOf { it.run.rumbles.size })
        assertEquals(65, rides.values.sumOf { it.run.taps.size })
        assertTrue(rides.values.sumOf { it.run.onMs } < rides.values.sumOf { it.old.onMs } / 15)
    }

    @Test fun theRumbleOnlyPastTheLimiter() {
        RideReplay.assume(RideReplay.TO_ZERO)
        for ((path, r) in rides) for (e in r.run.rumbles) {
            // FLY: past the speed where the controller gives no current (≈27.75 km/h): only a hill gets there
            if (e.mode == 3) assertTrue("$path $e", e.topKmh >= 28.2)
            // TAKEOFF (16.10 two-up): downhill to 17.7 km/h, past its zero at 15.3
            else assertTrue("$path $e", e.mode == 1 && e.topKmh > 15.8)
        }
        // 20.10, 100 % to the cut, full-throttle cruise at 26.3 km/h: v107 buzzed 34 times (247 s), now never
        val zero = rides.getValue(RideReplay.TO_ZERO)
        assertEquals(0, zero.run.rumbles.size)
        assertEquals(34, zero.old.episodes)
    }

    @Test fun theTapAtTheLimiter() {
        RideReplay.assume(RideReplay.TO_ZERO)
        val zero = rides.getValue(RideReplay.TO_ZERO).run.taps
        // at least a minute apart, each mode at its point: TAKEOFF's ride home after the reboots taps once
        assertTrue(zero.zipWithNext().all { (a, b) -> b.first - a.first >= 60_000 })
        assertEquals(listOf(1), zero.filter { it.second != 3 }.map { it.second })
        assertEquals(2_268_600.0, zero.last().first.toDouble(), 1_000.0)
        // the first one 2.5 min in, on the first stretch to the limiter
        assertEquals(148_900.0, zero.first().first.toDouble(), 1_000.0)
        // 16.10: switching FLY → TAKEOFF at 16.4 km/h to slow down neither rumbles nor uses up TAKEOFF's tap
        val take = rides.getValue("logs/20261016-190042-log-app.json.gz").run
        assertEquals(1_229_900.0, take.taps.single { it.second == 1 }.first.toDouble(), 1_000.0)
        assertTrue(take.rumbles.none { it.startMs in 1_212_000L..1_214_000L })
    }

    /** The phone's texts the watch tells apart ([Cluster.BMS_CUT] …), as the phone's pipeline makes them on 20.10. */
    @Test fun theBmsTextsAsThePhoneSendsThem() {
        RideReplay.assume(RideReplay.TO_ZERO)
        var cut: Lamp? = null
        var noBms = false
        var silent: String? = null
        RideReplay.snapshots(RideReplay.TO_ZERO) { t, _ ->
            val battery = t.lamps.firstOrNull { it.id == TellTales.BATTERY }
            if (t.t in 1_810_000L..1_814_000L && battery?.text == Cluster.BMS_CUT) cut = battery
            if (t.t in 1_816_000L..2_160_000L) noBms = (if (t.t < 1_817_000L) true else noBms) && battery?.text == Cluster.NO_BMS
            if (t.t in 1_825_000L..1_826_000L) silent = Cluster.statusLabel(Health.of(t, t.t, t.t), t.lamps)
        }
        assertEquals(LampLevel.RED, cut!!.level)
        assertEquals(31.25, cut!!.value!!, 0.02)
        assertTrue(noBms)
        // the dash answers, the BMS is silent: the top line says which one is quiet
        assertEquals(Cluster.BMS_SILENT, silent)
    }
}
