package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The words of «Приборка»: the prototype's lines for 20.10 at 1800 s and after the ride. */
class LampsTextTest {
    private val red = Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5)
    private val temp = Lamp(TellTales.TEMP, LampLevel.AMBER, "Батарея горячая", 50.0)
    private val service = Lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 76 % от паспорта", 76.0)

    @Test fun dialLine() {
        val d = LampsText.dial(listOf(temp, red, service), Units.KMH, bulb = false, live = true)
        assertEquals("⛔ Сейчас отключится · 31,5 В под нагрузкой", d.text)
        assertEquals(LampLevel.RED, d.level)
        val quiet = LampsText.dial(listOf(service), Units.KMH, bulb = false, live = true)
        assertEquals("a quiet lamp is not the dial's", "Предупреждений нет", quiet.text)
        assertNull(quiet.level)
        assertEquals("Нет живых данных — подключись к самокату", LampsText.dial(listOf(red), Units.KMH, bulb = false, live = false).text)
        assertEquals("Проверка ламп…", LampsText.dial(listOf(red), Units.KMH, bulb = true, live = false).text)
    }

    @Test fun whatTheGridLights() {
        assertEquals(12, LampsText.shown(emptyList(), bulb = true, live = false).count { it.level != LampLevel.OFF })
        assertEquals(listOf(red), LampsText.shown(listOf(red), bulb = false, live = true))
        assertTrue("the last lamps are not live", LampsText.shown(listOf(red), bulb = false, live = false).isEmpty())
    }

    @Test fun batteryLines() {
        val t = Telemetry(currentA = 7.6, currentFastA = 7.8, motorOn = true)
        assertEquals("Ток 7,8 А (BMS 7,6 А) · мотор тянет", LampsText.current(t, fast = true))
        assertEquals("Ток 7,6 А", LampsText.current(t.copy(motorOn = false), fast = false))
        assertNull(LampsText.current(Telemetry(), fast = true))
        assertEquals("906 / 3936 мА·ч", LampsText.mah(906.0, 3936.0))
        assertNull(LampsText.mah(null, 3936.0))
        val r = RangeEstimate(0.2, 0.1, 5.0, 12.6, "recent")
        assertEquals("По расходу за последние 2 км: 12,6 Вт·ч/км, запас 5 Вт·ч до отсечки · температуры 48 / 50 °C", LampsText.basis(r, 48.0, 50.0))
        assertEquals("Температуры 48 / — °C", LampsText.basis(null, 48.0, null))
        assertEquals("", LampsText.basis(null, null, null))
    }

    @Test fun lastBattery() {
        val r = LastRide(0, 1, 9.9, 1800, 120.0, 12.2, RideEnd(RideEnds.BMS_OFF, "22:12:31 · 31,2 В под нагрузкой", true), 1.3, 34.81, 54.0, 3936.0)
        assertEquals("54 / 3936 мА·ч · после отключения BMS; ёмкость 76 % паспорта", LampsText.last(r))
        assertEquals("54 / 3936 мА·ч · ёмкость 76 % паспорта", LampsText.last(r.copy(end = null)))
        assertEquals("", LampsText.last(r.copy(end = null, mah = null, fullMah = null)))
    }

    @Test fun journalTitle() {
        assertEquals("ЖУРНАЛ ЛАМП (12)", LampsText.journalTitle(12, null))
        val day = ZonedDateTime.of(2026, 10, 20, 21, 42, 21, 0, ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("ЖУРНАЛ ЛАМП (12) · 20.10", LampsText.journalTitle(12, day))
    }
}
