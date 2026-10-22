package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** The dash's words: the band, the range, the pill, the trip title, the logs tile. */
class DashTextTest {
    private val zone = TimeZone.getDefault()
    @Before fun la() = TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    @After fun back() = TimeZone.setDefault(zone)

    private val red = Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5)
    private val hot = Lamp(TellTales.TEMP, LampLevel.AMBER, "Батарея горячая", 50.0)
    private val service = Lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 76 % от паспорта")

    private fun band(lamps: List<Lamp>, bulb: Boolean = false, running: Boolean = true, link: Link = Link.CONNECTED, streaming: Boolean = true) =
        DashText.band(lamps, Units.KMH, bulb, running, link, streaming)

    @Test fun theBand() {
        val b = band(listOf(service, hot, red))
        assertEquals("the strongest lamp, the value without «под нагрузкой»", "Сейчас отключится · 31,5 В", b.title)
        assertEquals("сбавь газ, ищи где встать", b.hint)
        assertEquals(TellTales.BATTERY, b.id)
        assertTrue(b.crit)
        val warm = band(listOf(service, hot))
        assertEquals("Батарея горячая · 50 °C", warm.title)
        assertEquals("дойдёт до 55 °C — дай остыть", warm.hint)
        assertFalse(warm.crit)
        assertEquals("the quiet lamps never take it", Band(null, null, "Предупреждений нет", null, false), band(listOf(service)))
        assertEquals("Проверка ламп…", band(listOf(red), bulb = true).title)
        assertNull(band(listOf(red), bulb = true).id)
        val silent = band(listOf(red), streaming = false)
        assertEquals(TellTales.T_SILENT, silent.title)
        assertEquals("жду кадры после запроса +SVGT=?…", silent.hint)
        assertEquals(TellTales.LINK, silent.id)
        assertEquals("Переподключаюсь…", band(listOf(red), link = Link.RECONNECTING, streaming = false).title)
        // what the BMS says has its own line
        assertEquals("через секунды мотор пропадёт — остановись", band(listOf(red.copy(text = LampText.BMS_CUT, value = 31.2))).hint)
        assertEquals("мотор не потянет, на руле — ошибка 12", band(listOf(red.copy(text = LampText.NO_BMS, value = null))).hint)
        // a lamp without its own hint: its state
        assertEquals("горит оранжевым", band(listOf(Lamp(TellTales.CONSUMPTION, LampLevel.AMBER, "Высокий расход", 21.4))).hint)
    }

    @Test fun rangeInTheOwnersUnit() {
        val r = RangeEstimate(0.2, 0.124, 6.5, 12.9, "recent")
        assertEquals("≈ 0,2 км до отключения", DashText.range(r, Units.KMH))
        assertEquals("≈ 0,1 mi до отключения", DashText.range(r, Units.MPH))
        assertNull(DashText.range(null, Units.KMH))
    }

    @Test fun ampsVoltsAndPill() {
        val t = Telemetry(currentA = 7.6, currentFastA = 7.84, voltage = 31.41)
        assertEquals("7,8 А · 31,41 В", DashText.ampsVolts(t, fast = true))
        assertEquals("7,6 А · 31,41 В", DashText.ampsVolts(t, fast = false))
        assertEquals("На связи", DashText.pill(Link.CONNECTED, true, false, narrow = false))
        assertEquals("Жду данные", DashText.pill(Link.CONNECTED, false, false, narrow = false))
        assertEquals("Переподключаюсь…", DashText.pill(Link.RECONNECTING, false, false, narrow = false))
        assertEquals("at 360 dp", "Связь…", DashText.pill(Link.RECONNECTING, false, false, narrow = true))
        assertEquals("Не подключено", DashText.pill(Link.IDLE, false, false, narrow = true))
        assertEquals("Ищу самокат…", DashText.pill(Link.IDLE, false, true, narrow = true))
    }

    @Test fun tripTitle() {
        val start = TestLogs.startedAt(TestLogs.TO_ZERO)
        assertEquals("Поездка · с 21:42 · 98 Вт·ч", DashText.tripTitle(start, null, 98.2))
        assertEquals("Поездка · 21:42–22:22", DashText.tripTitle(start, start + 2_400_000, null))
        assertEquals("22 мин", DashText.minutes(1_320))
        assertEquals("1 ч 05 мин", DashText.minutes(3_900))
    }

    @Test fun logsTile() {
        val now = 1_792_000_000_000L
        val three = listOf(LocalLog("a.json.gz", 1, 1, current = true), LocalLog("b.json.gz", 1, 1), LocalLog("c.json.gz", 1, 1))
        assertEquals(DashText.LogsTile(DashText.LogsKind.UNSENT, 3, 0, null), DashText.logsTile(three, LogStatus(), now))
        assertEquals("sending: k of n", DashText.LogsTile(DashText.LogsKind.SENDING, 3, 1, null),
            DashText.logsTile(three, LogStatus(bulkBusy = true, bulkDone = 1, bulkTotal = 3), now))
        val sent = LogStatus(bulkDone = 3, bulkTotal = 3, bulkCommit = "7c1e2a9", bulkAt = now - 60_000)
        assertEquals(DashText.LogsTile(DashText.LogsKind.SENT, 3, 3, "7c1e2a9"), DashText.logsTile(emptyList(), sent, now))
        assertNull("5 minutes later the tile goes", DashText.logsTile(emptyList(), sent, now + DashText.SENT_SHOW_MS))
        assertNull("nothing to send", DashText.logsTile(emptyList(), LogStatus(), now))
        assertEquals("a new unsent log beats «На GitHub»", DashText.LogsKind.UNSENT, DashText.logsTile(three.take(1), sent, now)?.kind)
        assertEquals("1 лог", DashText.logs(1))
        assertEquals("3 лога", DashText.logs(3))
        assertEquals("5 логов", DashText.logs(5))
        assertEquals("11 логов", DashText.logs(11))
        assertEquals("22 лога", DashText.logs(22))
    }

    @Test fun disconnectedSaysWhatAutoSendWillDo() {
        val on = GhConfig(auto = true)
        assertEquals("Отключено · лог сохранён и уходит в GitHub", DashText.disconnected(on, hasToken = true))
        assertEquals("Отключено · лог сохранён", DashText.disconnected(GhConfig(auto = false), hasToken = true))
        assertEquals("on without a token: nothing goes (LogStore.autoDue)", "Отключено · лог сохранён · для автоотправки нужен токен",
            DashText.disconnected(on, hasToken = false))
        assertEquals("Отключено · лог сохранён · для автоотправки заполни «Логи» → «GitHub»", DashText.disconnected(on.copy(branch = " "), hasToken = true))
        assertFalse(on.copy(owner = "").autoReady(true))
        assertTrue(on.autoReady(true))
    }
}
