package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private fun low(l: WarningLevel, v: Double = 32.2) = BatteryWarning(BatteryAdvisor.LOW, l, BatteryAdvisor.TEXTS.getValue(BatteryAdvisor.LOW to l), v)
    private fun hot(l: WarningLevel, v: Double = 52.0) = BatteryWarning(BatteryAdvisor.HOT, l, BatteryAdvisor.TEXTS.getValue(BatteryAdvisor.HOT to l), v)

    @Test fun buzzOncePerWarningAndLevelWithACooldown() {
        val a = WarningAlerts()
        val W = WarningLevel.WARNING
        val C = WarningLevel.CRITICAL
        val min = 60_000L
        assertEquals(WarningAlerts.Action.NONE, a.update(emptyList(), 0).action)
        assertEquals("new warning", WarningAlerts.Action.ALERT, a.update(listOf(low(W)), 1 * min).action)
        assertEquals("the same, every publish", WarningAlerts.Action.NONE, a.update(listOf(low(W, 32.1)), 1 * min + 50).action)
        assertEquals("stepped up", WarningAlerts.Action.ALERT, a.update(listOf(low(C)), 2 * min).action)
        assertEquals("stepped down: silent", WarningAlerts.Action.UPDATE, a.update(listOf(low(W)), 3 * min).action)
        assertEquals("up again within 5 min: silent", WarningAlerts.Action.UPDATE, a.update(listOf(low(C)), 4 * min).action)
        assertEquals("another warning on top", WarningAlerts.Action.ALERT, a.update(listOf(low(C), hot(W)), 4 * min + 1).action)
        assertEquals("all clear", WarningAlerts.Action.CANCEL, a.update(emptyList(), 5 * min).action)
        assertEquals("back within the cooldown", WarningAlerts.Action.UPDATE, a.update(listOf(hot(W)), 6 * min).action)
        assertEquals(WarningAlerts.Action.CANCEL, a.update(emptyList(), 7 * min).action)
        assertEquals("back after 5 min", WarningAlerts.Action.ALERT, a.update(listOf(hot(W)), 10 * min).action)
        // a reconnect (new session) does not re-buzz what just buzzed
        a.reset()
        assertEquals(WarningAlerts.Action.UPDATE, a.update(listOf(hot(W)), 11 * min).action)
        a.reset()
        assertEquals(WarningAlerts.Action.ALERT, a.update(listOf(hot(C)), 11 * min).action)
        assertEquals(5 * 60_000L, WarningAlerts.COOLDOWN_MS)
    }

    /** Flapping right at a threshold never buzzes more than once per cooldown. */
    @Test fun flappingIsQuiet() {
        val a = WarningAlerts()
        var alerts = 0
        for (i in 0 until 3600) {   // an hour, one change a second
            val ws = if (i % 2 == 0) listOf(hot(WarningLevel.WARNING)) else emptyList()
            if (a.update(ws, i * 1000L).action == WarningAlerts.Action.ALERT) alerts++
        }
        assertEquals(12, alerts)
    }

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

    @Test fun texts() {
        assertEquals("Сейчас отключится · 31,8 В под нагрузкой", WarnText.line(low(WarningLevel.CRITICAL, 31.83)))
        assertEquals("Батарея перегрета — скоро отключится, сбавь газ · 56 °C", WarnText.line(hot(WarningLevel.CRITICAL, 56.0)))
        assertEquals("Заряд на исходе", WarnText.line(low(WarningLevel.WARNING).copy(value = null)))
        assertTrue(WarnText.hint(low(WarningLevel.CRITICAL)).isNotEmpty())
        val ws = listOf(low(WarningLevel.CRITICAL, 31.8), hot(WarningLevel.WARNING, 51.0))
        assertEquals("Сейчас отключится", WarnText.title(ws))
        assertEquals("Сейчас отключится · 31,8 В под нагрузкой\nБатарея греется · 51 °C", WarnText.body(ws))
        val r = RangeEstimate(km = 4.2, mi = 4.2 / 1.609344, whLeft = 82.0, whPerKm = 19.5, basis = "recent")
        assertEquals("≈ 4,2 км / 2,6 mi до отключения", WarnText.range(r, Units.KMH))
        assertEquals("≈ 2,6 mi / 4,2 км до отключения", WarnText.range(r, Units.MPH))
        assertTrue(WarnText.basis(r).startsWith("по расходу за последние 2 км: 19,5 Вт·ч/км"))
        assertTrue(WarnText.basis(r.copy(basis = "default", whPerKm = 15.0)).contains("15 Вт·ч/км"))
    }
}
