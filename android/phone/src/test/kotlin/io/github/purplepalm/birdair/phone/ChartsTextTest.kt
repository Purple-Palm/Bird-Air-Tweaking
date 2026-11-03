package io.github.purplepalm.birdair.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The words of «Графики»: the readout of the prototype's 22:12:06 (20.10), the window's energy, the trip. */
class ChartsTextTest {
    private val start = ZonedDateTime.of(2026, 10, 20, 21, 42, 21, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun one(p: Float, i: Float, v: Float, s: Float) =
        Samples(longArrayOf(1_785_500), floatArrayOf(p), floatArrayOf(p), floatArrayOf(i), floatArrayOf(i), floatArrayOf(v), floatArrayOf(s), doubleArrayOf(1464.0))

    @Test fun readout() {
        val r = ChartsText.readout(one(254f, 8.1f, 31.41f, 22.5f), 0, start, fast = true, units = Units.KMH)
        assertEquals("22:12:06", r.time)
        assertEquals("254 Вт", r.watts)
        assertEquals("тяга", r.word)
        assertFalse(r.into)
        assertEquals("8,1 А · 31,41 В · 22,5 км/ч", r.line2)
        val regen = ChartsText.readout(one(-120f, -3.8f, 33.2f, 18f), 0, start, fast = true, units = Units.KMH)
        assertEquals("120 Вт", regen.watts)
        assertEquals("в батарею", regen.word)
        assertTrue(regen.into)
        assertEquals("no current: P / U", "8,1 А · 31,41 В · 14,0 mph", ChartsText.readout(one(254f, Float.NaN, 31.41f, 22.5f), 0, start, true, Units.MPH).line2)
        val none = ChartsText.readout(one(Float.NaN, Float.NaN, Float.NaN, Float.NaN), 0, start, true, Units.KMH)
        assertEquals("— Вт", none.watts)
        assertEquals("", none.word)
        assertEquals("— А · — В · — км/ч", none.line2)
    }

    @Test fun energy() {
        assertEquals("ЭНЕРГИЯ ЗА ОКНО · 5 МИН", ChartsText.energyTitle("300"))
        assertEquals("ЭНЕРГИЯ ЗА ОКНО · 1 МИН", ChartsText.energyTitle("60"))
        assertEquals("ЭНЕРГИЯ ЗА ОКНО · ВСЯ ПОЕЗДКА", ChartsText.energyTitle(ChartWindow.ALL))
        assertEquals("5 мин 0 с", ChartsText.duration(299.6))
        assertEquals("0 мин 45 с", ChartsText.duration(45.0))
        assertEquals("1 ч 12 мин", ChartsText.duration(4330.0))
        assertEquals("Данные за 5 мин 0 с · 1,64 км по одометру приложения. " + ChartsText.GESTURES,
            ChartsText.energyNote(EnergySummary(18.0, 0.3, 300.0, 1.64), Units.KMH))
        assertEquals("Данные за 5 мин 0 с · 1,02 mi по одометру приложения. " + ChartsText.GESTURES,
            ChartsText.energyNote(EnergySummary(18.0, 0.3, 300.0, 1.64), Units.MPH))
        assertEquals("Данные за 5 мин 0 с. " + ChartsText.GESTURES, ChartsText.energyNote(EnergySummary(18.0, 0.3, 300.0, null), Units.KMH))
        assertEquals(ChartsText.GESTURES, ChartsText.energyNote(EnergySummary.EMPTY, Units.KMH))
        assertEquals(12.0 * Units.MILE, ChartsText.perDist(12.0, Units.MPH)!!, 1e-9)
        assertEquals(12.0, ChartsText.perDist(12.0, Units.KMH)!!, 0.0)
    }

    @Test fun trip() {
        assertEquals(
            "Из батареи 104,7 Вт·ч, рекуперация 6,4 Вт·ч (6,1 %). Одометр руля теряет пробег при выключении — приложение считает своё (исправить — в «Настройках»).",
            ChartsText.tripNote(104.7, 6.4),
        )
        assertTrue(ChartsText.tripNote(0.0, 0.0).startsWith("Из батареи 0,0 Вт·ч, рекуперация 0,0 Вт·ч. "))
    }
}
