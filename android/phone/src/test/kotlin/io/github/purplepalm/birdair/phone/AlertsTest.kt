package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private fun low(l: WarningLevel, v: Double = 32.2) = BatteryWarning(BatteryAdvisor.LOW, l, BatteryAdvisor.TEXTS.getValue(BatteryAdvisor.LOW to l), v)
    private fun hot(l: WarningLevel, v: Double = 52.0) = BatteryWarning(BatteryAdvisor.HOT, l, BatteryAdvisor.TEXTS.getValue(BatteryAdvisor.HOT to l), v)

    /** Letting go of the throttle: the voltage rebounds at once, the BMS current says 10 A for another ~0.6 s. */
    @Test fun voltageUnderLoadIgnoresTheRebound() {
        val s = LoadSag()
        for (i in 0 until 30) s.add(i * 300L, 31.85 + (i % 3) * 0.01, 10.2)   // riding at the edge
        s.add(9_000, 35.7, 1.2)     // let go: fast current ~1 A (the BMS still reports 10 A)
        s.add(9_300, 35.8, -3.5)
        assertEquals(31.85, s.minV(9_300)!!, 1e-9)
        val fixed = s.fix(listOf(low(WarningLevel.CRITICAL, 35.7), hot(WarningLevel.WARNING, 51.0)), 9_300)
        assertEquals(31.85, fixed[0].value!!, 1e-9)
        assertEquals("other warnings untouched", 51.0, fixed[1].value!!, 0.0)
        // 30 s without load: no number rather than a stale one
        s.add(40_000, 36.0, 0.0)
        assertEquals(null, s.minV(40_000))
        assertEquals("Сейчас отключится", WarnText.line(s.fix(listOf(low(WarningLevel.CRITICAL)), 40_000)[0]))
        s.reset()
        assertEquals(null, s.minV(0))
    }

    /** Every snapshot carries the last BMS frame's voltage and current; a sample is taken once per BMS frame, at its time. */
    @Test fun oneSamplePerBmsFrameAtItsTime() {
        val s = LoadSag()
        val crit = low(WarningLevel.CRITICAL, 35.0)
        fun snap(t: Long, at: Long?, v: Double, i: Double) = s.decorate(Telemetry(
            t = t, voltage = v, currentA = i, currentFastA = i, powerAtMs = at, warnings = listOf(crit),
            lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.RED, crit.text, crit.value)),
        ))
        // the last BMS frame at 1 s: 31,24 V at 7,7 A; the controller sends 3A 17 for 5 s more (20.10 after the cut)
        assertEquals(31.24, snap(1_000, 1_000, 31.24, 7.7).warnings[0].value!!, 0.0)
        for (t in 1_070L..6_000L step 70) assertEquals(31.24, snap(t, 1_000, 31.24, 7.7).warnings[0].value!!, 0.0)
        // 30 s after that frame, not after the last 3A 17: no number, on the lamp neither
        assertEquals(31.24, snap(31_000, 1_000, 31.24, 7.7).warnings[0].value!!, 0.0)
        val late = snap(31_001, 1_000, 31.24, 7.7)
        assertNull(late.warnings[0].value)
        assertNull(late.lamps[0].value)
        assertEquals("Сейчас отключится", WarnText.line(late.warnings[0]))
        // the next power-on: 3A 17 before its first 3A 18 still carry the old frame — nothing; a frame without load neither
        s.restart()
        assertNull(snap(300_000, 1_000, 31.24, 7.7).warnings[0].value)
        assertNull(snap(300_100, 300_050, 37.28, -4.9).warnings[0].value)
        assertEquals(32.5, snap(300_400, 300_350, 32.5, 11.4).warnings[0].value!!, 0.0)
        // a new connection within the window: what came before it goes, and the old frame is not taken again
        s.restart()
        assertNull(snap(300_500, 300_350, 32.5, 11.4).warnings[0].value)
        assertEquals(32.6, snap(300_700, 300_650, 32.6, 9.0).warnings[0].value!!, 0.0)
        // a new recording: its clock starts over
        s.reset()
        assertEquals(33.0, snap(900, 900, 33.0, 9.0).warnings[0].value!!, 0.0)
    }

    /**
     * 20.10 through the pipeline: the BMS cut the pack at 1812.4 s, the dash rebooted at 2117 s and 2145 s with the BMS
     * silent and at 2159 s for the ride home (first BMS frame 2163.0 s: 37,3 V, 4,9 A into the pack). Taken per
     * snapshot at the publish time, the 3A 17 frames before that first frame (31,24 V at 7,6 A, the last frame before
     * the cut) showed «Сейчас отключится · 31,2 В под нагрузкой» until 2193 s.
     */
    @Test fun noStaleVoltageAfterTheReboot() {
        class Frame(val t: Long, val v: Double, val a: Double)
        for (old in listOf(false, true)) {
            val sag = LoadSag()
            val frames = ArrayList<Frame>()           // every BMS frame the snapshots brought, of this connection
            var lo = 0
            var lastAt: Long? = null
            var stale = 0
            var inWindow = 0
            val decorate: (Telemetry, Boolean) -> Telemetry = if (!old) { t, _ -> sag.decorate(t) } else { t, fresh ->
                if (fresh) sag.add(t.t, t.voltage, t.currentFastA ?: t.currentA)
                if (t.warnings.isEmpty()) t else sag.fix(t.warnings, t.t).let { ws -> t.copy(warnings = ws, lamps = LoadSag.fixLamp(t.lamps, ws)) }
            }
            TestLogs.publish(TestLogs.TO_ZERO, decorate, { if (!old) { sag.restart(); frames.clear(); lo = 0 } }) { t ->
                val at = t.powerAtMs
                if (at != null && at != lastAt) { lastAt = at; frames.add(Frame(at, t.voltage!!, t.currentFastA ?: t.currentA!!)) }
                while (lo < frames.size && frames[lo].t < t.t - 30_000) lo++
                val w = t.warnings.firstOrNull { it.id == BatteryAdvisor.LOW } ?: return@publish
                val lamp = t.lamps.firstOrNull { it.id == TellTales.BATTERY }
                if (t.t in 2_117_000..2_260_000) {
                    inWindow++
                    if (w.value != null || WarnText.line(w).contains("31,") || lamp?.let { LampText.full(it).contains("31,") } == true) stale++
                }
                if (old) return@publish
                // the lowest of this connection's BMS frames of the last 30 s at ≥ 5 A: never an older frame
                var want: Double? = null
                for (k in lo until frames.size) if (frames[k].a >= 5.0) want = minOf(want ?: frames[k].v, frames[k].v)
                assertEquals("t=${t.t}", want, w.value)
            }
            assertTrue("battery_low is on after the reboot: $inWindow", inWindow > 500)
            if (old) assertTrue("the old way showed 31,2 V there: $stale", stale > 100)
            else assertEquals("snapshots with a stale «под нагрузкой» after the reboot", 0, stale)
        }
    }

    @Test fun texts() {
        assertEquals("Сейчас отключится · 31,8 В под нагрузкой", WarnText.line(low(WarningLevel.CRITICAL, 31.83)))
        assertEquals("Батарея перегрета — скоро отключится, сбавь газ · 56 °C", WarnText.line(hot(WarningLevel.CRITICAL, 56.0)))
        assertEquals("Заряд на исходе", WarnText.line(low(WarningLevel.WARNING).copy(value = null)))
        assertTrue(WarnText.hint(low(WarningLevel.CRITICAL)).isNotEmpty())
        assertTrue(WarnText.hint(low(WarningLevel.CRITICAL)).contains("31,6 В"))
        // the notification without lamps (the advisor alone): CRITICAL first
        val items = RideAlerts.items(emptyList(), listOf(hot(WarningLevel.WARNING, 51.0), low(WarningLevel.CRITICAL, 31.8)))
        assertEquals("Сейчас отключится", RideAlerts.title(items))
        assertEquals("Сейчас отключится · 31,8 В под нагрузкой\nБатарея греется · 51 °C", RideAlerts.body(items))
        val r = RangeEstimate(km = 4.2, mi = 4.2 / 1.609344, whLeft = 82.0, whPerKm = 19.5, basis = "recent")
        assertEquals("≈ 4,2 км / 2,6 mi до отключения", WarnText.range(r, Units.KMH))
        assertEquals("≈ 2,6 mi / 4,2 км до отключения", WarnText.range(r, Units.MPH))
        assertTrue(WarnText.basis(r).startsWith("по расходу за последние 2 км: 19,5 Вт·ч/км"))
        assertTrue(WarnText.basis(r.copy(basis = "default", whPerKm = 15.0)).contains("15 Вт·ч/км"))
    }
}
