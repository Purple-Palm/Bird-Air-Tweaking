package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.LampLevel.AMBER
import io.github.purplepalm.birdair.core.LampLevel.GREEN
import io.github.purplepalm.birdair.core.LampLevel.OFF
import io.github.purplepalm.birdair.core.LampLevel.RED
import io.github.purplepalm.birdair.core.LampLevel.YELLOW
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterTest {
    private fun temp(level: LampLevel, c: Double, rising: Boolean = false) = Lamp(
        TellTales.TEMP, level,
        when (level) { RED -> "Перегрев — сбавь газ"; AMBER -> "Батарея горячая"; else -> "Батарея тёплая" }, c, rising,
    )
    private val traction = Lamp(TellTales.TRACTION, AMBER, "Мало тяги: предел тока", 15.8)
    private val sag = Lamp(TellTales.TRACTION, AMBER, "Мало тяги: просадка", 31.9)
    private val eco = Lamp(TellTales.ECO, GREEN, "ECO", 180.0)
    private val over = Lamp(TellTales.OVERSPEED, LampLevel.BLUE, "Быстрее ограничителя", 28.9)
    private val service = Lamp(TellTales.SERVICE, AMBER, "Ёмкость 75 % от паспорта", 75.0)
    private val link = Lamp(TellTales.LINK, AMBER, "Связь рвётся")
    private val s = 1000L

    @Test fun texts() {
        assertEquals("⚠ Батарея горячая 49 °C", LampText.mark(temp(AMBER, 49.0)))
        assertEquals("⛔ Перегрев 56 °C", LampText.mark(temp(RED, 56.4)))
        assertEquals("⛔ Перегрев 56 °C ▲", LampText.mark(temp(RED, 56.0, rising = true)))
        assertEquals("the text says it already", "⚠ Горячая, быстро греется 48 °C", LampText.mark(Lamp(TellTales.TEMP, AMBER, "Горячая, быстро греется", 48.0, true)))
        assertEquals("✓ Батарея горячая — погасла", LampText.offMark(LampText.offLabel(temp(AMBER, 49.0))))
        assertEquals("⚠ Мало тяги: предел тока 15,8 А", LampText.mark(traction))
        assertEquals("⚠ Мало тяги: просадка 31,9 В", LampText.mark(sag))
        assertEquals("● ECO 180 Вт", LampText.mark(eco))
        assertEquals("● Быстрее ограничителя 28,9 км/ч", LampText.mark(over))
        assertEquals("Быстрее ограничителя · 18,0 mph", LampText.full(over.copy(value = 18.0 * Units.MILE), Units.MPH))
        // a number in the text already: nothing added; once off, the lamp's name instead
        assertEquals("⚠ Ёмкость 75 % от паспорта", LampText.mark(service))
        assertEquals("Сервис", LampText.offLabel(service))
        assertEquals("Перегрев — сбавь газ · 56 °C", LampText.full(temp(RED, 56.0)))
        assertEquals("Сейчас отключится · 31,8 В под нагрузкой", LampText.full(Lamp(TellTales.BATTERY, RED, "Сейчас отключится", 31.8)))
        assertEquals("⚠ Связь рвётся", LampText.mark(link))
        assertEquals("Температура батареи: Перегрев 56 °C · красная",
            LampText.journal(LampEvent(0, TellTales.TEMP, AMBER, RED, temp(RED, 56.0))))
        assertEquals("Мало тяги: просадка 31,9 В · оранжевая", LampText.journal(LampEvent(0, TellTales.TRACTION, OFF, AMBER, sag)))
        assertEquals("Мало тяги — погасла", LampText.journal(LampEvent(0, TellTales.TRACTION, AMBER, OFF, null)))
        assertEquals("Горит красным, мигает", LampText.state(RED))
        assertTrue(LampText.help(TellTales.TEMP).startsWith("Жёлтая — "))
        for (id in TellTales.IDS) assertTrue(id, LampText.help(id).isNotEmpty())
    }

    @Test fun journalIsARingNewestFirst() {
        val j = LampJournal(3)
        assertEquals(emptyList<LampEvent>(), j.list())
        for (k in 1..5) j.add(LampEvent(k * s, TellTales.IDS[k], OFF, AMBER, null))
        assertEquals(3, j.size)
        assertEquals(listOf(5 * s, 4 * s, 3 * s), j.list().map { it.atMs })
        j.clear()
        assertEquals(0, j.list().size)
        j.add(LampEvent(9, TellTales.TEMP, OFF, YELLOW, null))
        assertEquals(listOf(9L), j.list().map { it.atMs })
    }

    @Test fun everyLevelChangeGoesIntoTheJournal() {
        val cw = ClusterWatch(capacity = 50)
        assertTrue(cw.changes(emptyList(), 0).isEmpty())
        assertEquals(2, cw.changes(listOf(temp(YELLOW, 41.0), service), 1 * s).size)
        assertTrue("only the value moved", cw.changes(listOf(temp(YELLOW, 42.0), service), 2 * s).isEmpty())
        assertEquals(1, cw.changes(listOf(temp(AMBER, 48.0), service), 3 * s).size)
        assertEquals(1, cw.changes(listOf(service), 4 * s).size)
        val j = cw.journal.list()
        assertEquals(listOf(TellTales.TEMP, TellTales.TEMP, TellTales.SERVICE, TellTales.TEMP), j.map { it.id })
        assertEquals(listOf(OFF, AMBER, AMBER, YELLOW), j.map { it.to })
        assertEquals(4 * s, j.first().atMs)
        cw.reset()
        assertTrue(cw.journal.list().isEmpty())
        assertEquals("after a reset what is lit is new again", 1, cw.changes(listOf(service), 5 * s).size)
    }

    @Test fun stepsUpAreMarkedAtOnceTheRestAtMostEvery30s() {
        val m = LampMarks()
        fun at(t: Long, vararg ls: Lamp) = m.update(ls.toList(), t).map { it.text }
        assertEquals(listOf("⚠ Батарея тёплая 41 °C"), at(0, temp(YELLOW, 41.0)))
        assertEquals("the value alone is no change", emptyList<String>(), at(2 * s, temp(YELLOW, 43.0)))
        assertEquals(listOf("⚠ Батарея горячая 48 °C"), at(5 * s, temp(AMBER, 48.0)))
        assertEquals("red never waits", listOf("⛔ Перегрев 55 °C"), at(6 * s, temp(RED, 55.0)))
        assertEquals("a step down waits for the gap", emptyList<String>(), at(10 * s, temp(AMBER, 53.0)))
        assertEquals(emptyList<String>(), at(35 * s, temp(AMBER, 52.0)))
        assertEquals(listOf("⚠ Батарея горячая 52 °C"), at(36 * s, temp(AMBER, 52.0)))
        assertEquals("off, but within 30 s of the last mark", emptyList<String>(), at(40 * s))
        assertEquals(listOf("✓ Батарея горячая — погасла"), at(66 * s))
        // a flicker inside the gap is one stretch: on, (off, on, off), off after the gap
        val t0 = 100 * s
        assertEquals(listOf("⚠ Мало тяги: предел тока 15,8 А"), at(t0, traction))
        assertEquals(emptyList<String>(), at(t0 + 5 * s))
        assertEquals(emptyList<String>(), at(t0 + 10 * s, traction))
        assertEquals(emptyList<String>(), at(t0 + 20 * s))
        assertEquals(listOf("✓ Мало тяги: предел тока — погасла"), at(t0 + 30 * s))
        assertEquals("on again: a step up, at once", listOf("⚠ Мало тяги: просадка 31,9 В"), at(t0 + 31 * s, sag))
        m.reset()
        assertEquals(listOf("⚠ Мало тяги: просадка 31,9 В"), at(t0 + 32 * s, sag))
    }

    @Test fun ecoOnlyAfter30sOnNoisyLampsOncePer30s() {
        val m = LampMarks()
        fun at(t: Long, vararg ls: Lamp) = m.update(ls.toList(), t).map { it.text }
        for (k in 0..20) assertEquals("a short ECO stretch is not marked", emptyList<String>(), at(k * s, eco))
        assertEquals(emptyList<String>(), at(21 * s))
        val t0 = 100 * s
        for (k in 0..29) assertEquals(emptyList<String>(), at(t0 + k * s, eco))
        assertEquals(listOf("● ECO 180 Вт"), at(t0 + 30 * s, eco))
        assertEquals(emptyList<String>(), at(t0 + 40 * s))
        assertEquals(listOf("✓ ECO — погасла"), at(t0 + 60 * s))
        // overspeed and link flapping every second for 10 minutes: never two marks of one lamp within 30 s
        val marks = ArrayList<Pair<Long, String>>()
        for (k in 0 until 600) {
            val t = 1000 * s + k * s
            val ls = if (k % 2 == 0) listOf(over, link) else emptyList()
            for (mk in m.update(ls, t)) marks.add(t to mk.id)
        }
        for (id in listOf(TellTales.OVERSPEED, TellTales.LINK)) {
            val ts = marks.filter { it.second == id }.map { it.first }
            assertTrue(id, ts.size in 10..21)
            assertTrue(id, ts.zipWithNext().all { (a, b) -> b - a >= LampMarks.GAP_MS })
        }
    }

    @Test fun redRingsWithACooldownAmberOncePerRide() {
        val a = RideAlerts()
        val min = 60_000L
        fun act(t: Long, ls: List<Lamp>, ws: List<BatteryWarning> = emptyList()) = a.update(ls, ws, t).action
        assertEquals(RideAlerts.Action.NONE, act(0, emptyList()))
        assertEquals("quiet lamps and the link never ring", RideAlerts.Action.NONE, act(0, listOf(service, eco, link, temp(YELLOW, 41.0))))
        assertEquals("amber, first this ride", RideAlerts.Action.ALERT, act(1 * min, listOf(temp(AMBER, 48.0), service)))
        assertEquals("the same, every publish", RideAlerts.Action.NONE, act(1 * min + 50, listOf(temp(AMBER, 49.0))))
        assertEquals("another amber lamp", RideAlerts.Action.ALERT, act(2 * min, listOf(temp(AMBER, 49.0), traction)))
        assertEquals("traction off: text only", RideAlerts.Action.UPDATE, act(2 * min + 10, listOf(temp(AMBER, 49.0))))
        assertEquals("traction again: once per ride", RideAlerts.Action.UPDATE, act(3 * min, listOf(temp(AMBER, 49.0), traction)))
        assertEquals("red", RideAlerts.Action.ALERT, act(4 * min, listOf(temp(RED, 55.0), traction)))
        assertEquals("stepped down: silent", RideAlerts.Action.UPDATE, act(5 * min, listOf(temp(AMBER, 53.0))))
        assertEquals("red again within 5 min: silent", RideAlerts.Action.UPDATE, act(6 * min, listOf(temp(RED, 55.0))))
        assertEquals("all clear", RideAlerts.Action.CANCEL, act(7 * min, listOf(service)))
        assertEquals("red after 5 min", RideAlerts.Action.ALERT, act(9 * min, listOf(temp(RED, 56.0))))
        // a new ride: amber rings again (when not within the cooldown), red keeps its cooldown
        a.reset()
        assertEquals(RideAlerts.Action.UPDATE, act(10 * min, listOf(temp(RED, 56.0))))
        a.reset()
        assertEquals(RideAlerts.Action.ALERT, act(20 * min, listOf(traction)))
        assertEquals(5 * 60_000L, RideAlerts.COOLDOWN_MS)
    }

    /** Flapping right at a threshold for an hour, one change a second. */
    @Test fun flappingIsQuiet() {
        fun count(l: Lamp): Int {
            val a = RideAlerts()
            var n = 0
            for (i in 0 until 3600) if (a.update(if (i % 2 == 0) listOf(l) else emptyList(), emptyList(), i * 1000L).action == RideAlerts.Action.ALERT) n++
            return n
        }
        assertEquals(12, count(temp(RED, 55.0)))
        assertEquals(1, count(traction))
    }

    @Test fun theAdvisorsWarningsShareTheLampsItems() {
        val hot = BatteryWarning(BatteryAdvisor.HOT, WarningLevel.CRITICAL, BatteryAdvisor.TEXTS.getValue(BatteryAdvisor.HOT to WarningLevel.CRITICAL), 56.0)
        val low = BatteryWarning(BatteryAdvisor.LOW, WarningLevel.WARNING, "Заряд на исходе", 32.1)
        val items = RideAlerts.items(listOf(temp(RED, 56.0), traction), listOf(hot, low))
        assertEquals(listOf(TellTales.TEMP, TellTales.BATTERY, TellTales.TRACTION), items.map { it.id })
        assertEquals(listOf(RED, AMBER, AMBER), items.map { it.level })
        assertEquals("the lamp's words, the advisor's advice", "Перегрев — сбавь газ", items[0].title)
        assertEquals(WarnText.hint(hot), items[0].hint)
        assertEquals("Заряд на исходе · 32,1 В под нагрузкой", items[1].line)
        // the advisor alone (a lamp the core did not light): still one item
        val hotOnly = RideAlerts.items(emptyList(), listOf(hot))
        assertEquals(1, hotOnly.size)
        assertEquals("Батарея перегрета — скоро отключится, сбавь газ · 56 °C", hotOnly[0].line)
        assertEquals("Перегрев — сбавь газ", RideAlerts.title(items))
        assertEquals("Перегрев — сбавь газ · 56 °C\nЗаряд на исходе · 32,1 В под нагрузкой\nМало тяги: предел тока · 15,8 А", RideAlerts.body(items))
        // the watch and the dial show battery_low's voltage under load on the charge lamp
        val lamp = Lamp(TellTales.BATTERY, AMBER, "Заряд на исходе", 35.7)
        assertEquals(32.1, LoadSag.fixLamp(listOf(lamp, traction), listOf(low))[0].value!!, 0.0)
        assertEquals("percent lamp untouched", 24.3, LoadSag.fixLamp(listOf(lamp.copy(text = "Заряд 24,3 %", value = 24.3)), listOf(low))[0].value!!, 0.0)
    }

    /** Real rides through the tracker, a snapshot every 250 ms like the publisher: what the log and the phone get. */
    @Test fun realRides() {
        for ((name, wantRed) in listOf(HOT to true, TWO_UP_HALF to false)) {
            val r = replay("logs/$name")
            // never two marks of a noisy lamp within 30 s; another one within 30 s only as a step up
            for (id in TellTales.IDS) {
                val ms = r.marks.filter { it.second.id == id }
                for ((a, b) in ms.zipWithNext()) {
                    val dt = b.first - a.first
                    if (id in LampMarks.NOISY) assertTrue("$name $id", dt >= LampMarks.GAP_MS)
                    else if (dt < LampMarks.GAP_MS) assertTrue("$name $id ${a.second} → ${b.second}", b.second.level.ordinal > a.second.level.ordinal)
                }
            }
            assertTrue(name + ": " + r.marks.size + " marks of " + r.changes + " changes", r.marks.size < r.changes)
            assertEquals(name, wantRed, r.marks.any { it.second.text.startsWith("⛔ Перегрев") })
            // amber rings once per lamp, red at most once per 5 min
            val amber = r.rang.filter { it.second.level == AMBER }.map { it.second.id }
            assertEquals(name, amber.distinct(), amber)
            for (id in TellTales.IDS) {
                val red = r.rang.filter { it.second.id == id && it.second.level == RED }.map { it.first }
                assertTrue("$name $id", red.zipWithNext().all { (a, b) -> b - a >= RideAlerts.COOLDOWN_MS })
            }
            assertEquals(name, wantRed, r.rang.any { it.second.id == TellTales.TEMP && it.second.level == RED })
            assertTrue(name + ": " + r.rang.size, r.rang.size in 1..12)
            for (m in r.marks) for (k in ANALYZER_KEYS) assertFalse("$name: «${m.second.text}» reads as a «$k» label to tools/analyze_log.py", m.second.text.lowercase().contains(k))
        }
        val hot = replay("logs/$HOT")
        val first = hot.marks.first { it.second.id == TellTales.TEMP }.second.text
        assertTrue(first, first.startsWith("⚠ Батарея"))
        assertTrue("the two-up ride sits at the current limit: traction is marked", replay("logs/$TWO_UP_HALF").marks.any { it.second.id == TellTales.TRACTION })
    }

    private class Replay(val marks: List<Pair<Long, LampMarks.Mark>>, val changes: Int, val rang: List<Pair<Long, AlertItem>>)

    private fun replay(path: String): Replay {
        val tr = TelemetryTracker(TestLogs.decoder())
        tr.link = Link.CONNECTED
        val cw = ClusterWatch()
        val marks = ArrayList<Pair<Long, LampMarks.Mark>>()
        val rang = ArrayList<Pair<Long, AlertItem>>()
        var changes = 0
        val rx = TestLogs.rx(path)
        var k = 0
        var clock = rx.first().t
        while (clock <= rx.last().t + 5000) {
            while (k < rx.size && rx[k].t <= clock) { val e = rx[k++]; tr.onNotification(e.s, e.u, e.b, e.t) }
            val t = tr.snapshot(clock)
            changes += cw.changes(t.lamps, clock).size
            for (m in cw.marks.update(t.lamps, clock)) marks.add(clock to m)
            for (i in cw.alerts.update(t.lamps, t.warnings, clock).rang) rang.add(clock to i)
            clock += 250
        }
        return Replay(marks, changes, rang)
    }

    companion object {
        const val HOT = "20261015-155523-log-app.json.gz"
        const val TWO_UP_HALF = "20261016-135138-log-app.json.gz"
        /** tools/analyze_log.py MARKER_KINDS: a mark with one of these is taken for the owner's own label. */
        val ANALYZER_KEYS = listOf(
            "отпустил", "release", "зарядка вкл", "charge on", "charging on", "зарядка выкл", "charge off", "charging off",
            "газ", "throttle", "тормоз", "brake", "стоп", "stop", "режим", "mode", "фара", "свет", "light",
        )
    }
}
