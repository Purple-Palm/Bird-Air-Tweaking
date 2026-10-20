package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryAdvisorTest {
    class Step(val t: Long, val km: Double?, val ws: List<BatteryWarning>, val range: RangeEstimate?, val f: BmsFrame)

    /** A log through AppOdometer and BatteryAdvisor, a step per BMS frame (tools/battery_advisor.py replay()). */
    private fun replay(path: String): List<Step> {
        val ride = RideLogs.ride(path)
        val adv = BatteryAdvisor()
        val od = AppOdometer()
        val out = ArrayList<Step>()
        for (e in ride.events) when (e) {
            is ScooterEvent.Motion -> adv.onDistance(e.t, od.update(e.f.tripKm, e.f.odometerKm, ride.startedAtMs + e.t))
            is ScooterEvent.Bms -> {
                val b = e.f
                adv.onBms(e.t, b.voltage, b.currentA, b.temp1C.toDouble(), b.temp2C.toDouble(), b.remainingMah.toDouble(), b.fullMah.toDouble())
                out.add(Step(e.t, adv.km, adv.warnings(), adv.range(), b))
            }
            is ScooterEvent.Reset -> {}
        }
        return out
    }

    private fun level(ws: List<BatteryWarning>, id: String) =
        ws.firstOrNull { it.id == id }?.let { if (it.level == WarningLevel.CRITICAL) 2 else 1 } ?: 0

    private fun first(steps: List<Step>, id: String, critical: Boolean): Long? =
        steps.firstOrNull { level(it.ws, id) >= (if (critical) 2 else 1) }?.t

    @Test fun sameAsPythonOnAllLogs() {
        Py.assume()
        var n = 0
        for (path in RideLogs.ALL) {
            val mine = replay(path)
            val py = RideLogs.python.getValue(path)["adv"]!!.jsonArray
            assertEquals(py.size, mine.size, path)
            for ((k, row) in py.withIndex()) {
                val r = row.jsonArray
                val m = mine[k]
                assertEquals(r[0].jsonPrimitive.long, m.t, "$path #$k")
                assertEquals(r[1].jsonPrimitive.int, level(m.ws, BatteryAdvisor.LOW), "$path #$k low")
                assertEquals(r[2].jsonPrimitive.int, level(m.ws, BatteryAdvisor.HOT), "$path #$k hot")
                if (r[3] is JsonNull) assertNull(m.km) else assertEquals(r[3].jsonPrimitive.double, m.km!!, 1e-9, "$path #$k km")
                if (r[4] is JsonNull) assertNull(m.range, "$path #$k") else {
                    val g = m.range!!
                    assertTrue(abs(r[4].jsonPrimitive.double - g.km) <= 1e-9, "$path #$k range ${r[4]} vs ${g.km}")
                    assertTrue(abs(r[5].jsonPrimitive.double - g.whPerKm) <= 1e-9, "$path #$k Wh/km")
                    assertEquals(r[6].jsonPrimitive.content, g.basis, "$path #$k")
                }
            }
            n += mine.size
        }
        assertTrue(n > 60_000, "$n")
    }

    @Test fun warningsBeforeTheRealCutoffs() {
        for ((path, cut) in RideLogs.CUTOFF_T) {
            val st = replay(path)
            val low = first(st, BatteryAdvisor.LOW, false)!!
            val crit = first(st, BatteryAdvisor.LOW, true)!!
            assertTrue(cut - low >= 5 * 60_000, "$path: «Заряд на исходе» ${(cut - low) / 1000} s before")
            assertTrue(cut - crit >= 60_000, "$path: «Сейчас отключится» ${(cut - crit) / 1000} s before")
            assertTrue(cut - first(st, BatteryAdvisor.HOT, true)!! >= 5 * 60_000, path)
            val w = st.last().ws
            assertEquals(listOf(BatteryAdvisor.LOW, BatteryAdvisor.HOT), w.map { it.id })
            assertEquals("Сейчас отключится", w[0].text)
            assertEquals(WarningLevel.CRITICAL, w[0].level)
        }
        // two-up at 54 %, charging: nothing
        for (path in listOf(RideLogs.TWO_UP_HALF, RideLogs.CHARGE, RideLogs.CAR_CHARGE))
            assertTrue(replay(path).all { it.ws.isEmpty() }, path)
    }

    @Test fun rangeOnTheCutoffAtOnePercent() {
        val st = replay(RideLogs.CUTOFF)
        val end = st.last().km!!
        for (left in listOf(5.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.5)) {
            val s = st.first { it.km != null && end - it.km <= left }
            assertEquals(end - s.km!!, s.range!!.km, 0.6, "at $left km left")
        }
        val r = st.last().range!!
        assertEquals(0.0, r.km)
        assertEquals("recent", r.basis)
        assertEquals(r.km / 1.609344, r.mi, 1e-12)
    }

    @Test fun unitLevelsAndHysteresis() {
        val a = BatteryAdvisor()
        fun feed(t0: Long, t1: Long, v: Double, i: Double, temp: Double? = null) { var t = t0; while (t < t1) { a.onBms(t, v, i, temp, null); t += 300 } }
        feed(0, 1800, 32.2, 9.0)
        assertTrue(a.warnings().isEmpty())
        feed(1800, 4500, 32.2, 9.0)
        assertEquals(listOf(BatteryWarning("battery_low", WarningLevel.WARNING, "Заряд на исходе", 32.2)), a.warnings())
        feed(4500, 7500, 31.8, 10.0)
        assertEquals(WarningLevel.CRITICAL, a.warnings()[0].level)
        feed(7500, 30000, 32.8, 5.5)
        assertEquals(WarningLevel.WARNING, a.warnings()[0].level)
        feed(30000, 41000, 40.5, -1.5)
        assertTrue(a.warnings().isEmpty())
        feed(41000, 41300, 40.0, 0.1, 55.0)
        assertEquals(BatteryWarning("battery_hot", WarningLevel.CRITICAL, "Батарея перегрета", 55.0), a.warnings().single())
        feed(41300, 41600, 40.0, 0.1, 53.0)
        assertEquals(WarningLevel.WARNING, a.warnings().single().level)
        feed(41600, 41900, 40.0, 0.1, 48.0)
        assertTrue(a.warnings().isEmpty())
        assertNull(a.range())
        a.onBms(42000, 38.0, 0.06, null, null, 2000.0, 4000.0)
        val r = a.range()
        assertNotNull(r)
        assertEquals("default", r.basis)
        assertEquals((2000 - 120) * 0.031 / 15.0, r.km, 1e-12)
    }
}
