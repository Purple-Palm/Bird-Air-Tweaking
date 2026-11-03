package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Telemetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** Why the last ride ended and what the idle dash keeps of it. */
class RideSummaryTest {
    private val zone = TimeZone.getDefault()
    @Before fun la() = TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    @After fun back() = TimeZone.setDefault(zone)

    @Test fun theBmsCutOf2010() {
        val start = TestLogs.startedAt(TestLogs.TO_ZERO)
        val cw = ClusterWatch()
        val sag = LoadSag()
        var last = Telemetry()
        var pctAtCut: Double? = null
        TestLogs.publish(TestLogs.TO_ZERO, decorate = { t, _ -> sag.decorate(t) }, restart = { sag.restart() }) { t ->
            val ch = cw.changes(t.lamps, start + t.t)
            if (pctAtCut == null && ch.any { it.lamp?.text?.startsWith(LampText.BMS_CUT) == true }) pctAtCut = t.batteryPct ?: t.batteryPctFine
            last = t
        }
        val end = RideEnds.of(EndCause.OWNER, null, cw.journal.list(), last, pctAtCut)
        assertNotNull(end)
        end!!
        assertEquals("BMS отключила батарею", end.title)
        assertTrue(end.crit)
        assertEquals("the BMS's flag at 1810 s, its voltage and charge, the controller's 0x26", "22:12:31 · 31,2 В под нагрузкой, 5 % · на руле ошибка 12", end.detail)
        // whatever ended the recording, the BMS's cut is the reason
        assertEquals(end, RideEnds.of(EndCause.LOST, "Самокат пропал 10 минут назад", cw.journal.list(), last, pctAtCut))
    }

    @Test fun otherEnds() {
        val t = Telemetry()
        assertNull("«Отключить» with nothing to say: no plate", RideEnds.of(EndCause.OWNER, null, emptyList(), t))
        val lost = RideEnds.of(EndCause.LOST, "Самокат не отвечает 10 минут", emptyList(), t)!!
        assertEquals(RideEnd("Связь пропала", "Самокат не отвечает 10 минут", crit = false), lost)
        val sys = RideEnds.of(EndCause.SYSTEM, null, emptyList(), t)!!
        assertEquals("Фоновую работу остановила система", sys.title)
        assertFalse(sys.crit)
    }

    @Test fun lastRideSurvivesThePrefs() {
        val r = LastRide(1_792_000_000_000L, 1_792_002_400_000L, 9.6, 1_800, 118.0, 12.3, RideEnd("BMS отключила батарею", "22:12:31 · 31,2 В", true), 1.3, 34.81, 54.0, 3936.0)
        assertEquals(r, LastRide.decode(r.encode()))
        val bare = r.copy(km = null, whPerKm = null, end = null, pct = null, volts = null, mah = null, fullMah = null)
        assertEquals(bare, LastRide.decode(bare.encode()))
        assertNull(LastRide.decode(null))
        assertNull(LastRide.decode("{"))
        assertNull(LastRide.decode("""{"km": 3}"""))
    }

    @Test fun movingTime() {
        // 1 s steps: 10 s at 10 km/h, 5 s standing, a 10 s gap (not counted), 3 s at 0,5 km/h, 4 s at 12 km/h
        val ts = ArrayList<Long>()
        val sp = ArrayList<Float>()
        var t = 0L
        fun add(n: Int, v: Float) { repeat(n) { ts.add(t); sp.add(v); t += 1000 } }
        add(10, 10f); add(5, 0f); t += 10_000; add(3, 0.5f); add(5, 12f)
        val n = ts.size
        val x = Samples(ts.toLongArray(), FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), FloatArray(n), sp.toFloatArray(), DoubleArray(n) { Double.NaN })
        assertEquals("10 s riding, then the step into the stop; 4 s at the end", 14L, TripStats.movingSeconds(x))
        assertEquals(0L, TripStats.movingSeconds(Samples.EMPTY))
    }

    @Test fun tripNowByTheAppOdometer() {
        val s = Session(1_792_000_000_000L, { 0L })
        s.appOdoStartKm = 1_455.0
        val t = Telemetry(appOdometerKm = 1_462.8, whOut = 110.0, whIn = 12.0)
        val trip = TripStats.now(s, t, movingS = 1_320)
        assertEquals(7.8, trip.km!!, 1e-9)
        assertEquals(98.0, trip.whNet, 1e-9)
        assertEquals(98.0 / 7.8, trip.whPerKm!!, 1e-9)
        assertEquals(1_320L, trip.movingS)
        assertNull(TripStats.now(null, t, 0).km)
    }
}
