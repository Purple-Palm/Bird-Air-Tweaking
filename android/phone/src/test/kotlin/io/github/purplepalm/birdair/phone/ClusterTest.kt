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
    private val silent = Lamp(TellTales.LINK, AMBER, "Руль молчит")
    private val dashOnly = Lamp(TellTales.LINK, AMBER, "Руль на связи, BMS молчит")
    private val critical = Lamp(TellTales.BATTERY, RED, "Сейчас отключится", 31.5)
    private val bmsCut = Lamp(TellTales.BATTERY, RED, "BMS отключает батарею", 31.249)    // 20.10's first flagged frame
    private val noBms = Lamp(TellTales.BATTERY, RED, "Нет связи с BMS")
    private val chargeWait = Lamp(TellTales.CHARGING, AMBER, "Зарядка ждёт остывания", 45.0)
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
        // the BMS's and the controller's own conditions
        assertEquals("⛔ BMS отключает батарею 31,2 В", LampText.mark(bmsCut))
        assertEquals("BMS отключает батарею · 31,2 В под нагрузкой", LampText.full(bmsCut))
        assertEquals("⛔ Нет связи с BMS (код 0x26)", LampText.mark(noBms))
        assertEquals("Нет связи с BMS", LampText.full(noBms))
        assertEquals("Заряд: Нет связи с BMS (код 0x26) · красная", LampText.journal(LampEvent(0, TellTales.BATTERY, RED, RED, noBms)))
        assertEquals("✓ Нет связи с BMS — погасла", LampText.offMark(LampText.offLabel(noBms)))
        assertEquals("⚠ Руль на связи, BMS молчит", LampText.mark(dashOnly))
        assertEquals("⛔ Защита от перенапряжения 42,4 В", LampText.mark(Lamp(TellTales.OVERVOLT, RED, "Защита от перенапряжения", 42.36)))
        assertEquals("the dial's short «Полная» whole in the log", "⚠ Батарея полная 42,2 В", LampText.mark(Lamp(TellTales.OVERVOLT, AMBER, "Полная — тормози мягко", 42.23)))
        assertEquals("Полная — тормози мягко · 42,2 В", LampText.full(Lamp(TellTales.OVERVOLT, AMBER, "Полная — тормози мягко", 42.23)))
        assertEquals("⚠ Зарядка ждёт остывания 45 °C", LampText.mark(chargeWait))
        assertEquals("⚠ Код контроллера 0x21", LampText.mark(Lamp(TellTales.LINK, AMBER, "Код контроллера 0x21", 33.0)))
        assertTrue(LampText.hint(noBms).contains("0x26"))
        assertTrue(LampText.hint(bmsCut).startsWith("BMS отключает"))
        assertTrue(LampText.hint(chargeWait).contains("остынет"))
        // «Приборка» explains them from the core's HELP
        assertTrue(LampText.help(TellTales.BATTERY).contains("«BMS отключает батарею»") && LampText.help(TellTales.BATTERY).contains("«Нет связи с BMS»"))
        assertTrue(LampText.help(TellTales.LINK).contains("«Руль на связи, BMS молчит»"))
        assertTrue(LampText.help(TellTales.CHARGING).contains("37 / 39 °C"))
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
        // red stays red, the BMS's own condition still goes in; back to the advisor's text at the same level: no
        assertEquals(1, cw.changes(listOf(critical, service), 6 * s).size)
        assertEquals(listOf("Заряд: BMS отключает батарею 31,2 В · красная"), cw.changes(listOf(bmsCut, service), 7 * s).map { LampText.journal(cw.journal.list().first()) })
        assertEquals(1, cw.changes(listOf(noBms, service), 8 * s).size)
        assertEquals(0, cw.changes(listOf(noBms, service), 9 * s).size)
        assertEquals(0, cw.changes(listOf(critical, service), 10 * s).size)
        assertEquals(listOf(TellTales.BATTERY, TellTales.LINK), cw.changes(listOf(noBms, service, dashOnly), 11 * s).map { it.id })
        assertEquals(0, cw.changes(listOf(noBms, service, silent), 12 * s).size)
        assertEquals(1, cw.changes(listOf(noBms, service, dashOnly), 13 * s).size)
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
        val wh = Lamp(TellTales.CONSUMPTION, AMBER, "Расход 21 Вт·ч/км", 21.0)
        assertEquals(listOf("⚠ Расход 21 Вт·ч/км"), at(t0, wh))
        assertEquals(emptyList<String>(), at(t0 + 5 * s))
        assertEquals(emptyList<String>(), at(t0 + 10 * s, wh))
        assertEquals(emptyList<String>(), at(t0 + 20 * s))
        assertEquals(listOf("✓ Высокий расход — погасла"), at(t0 + 30 * s))
        assertEquals("on again: a step up, at once", listOf("⚠ Расход 22 Вт·ч/км"), at(t0 + 31 * s, wh.copy(text = "Расход 22 Вт·ч/км")))
        m.reset()
        assertEquals(listOf("⚠ Мало тяги: просадка 31,9 В"), at(t0 + 32 * s, sag))
        assertEquals(listOf("✓ Мало тяги: просадка — погасла"), at(t0 + 62 * s))
        assertEquals("traction is noisy: on again within 30 s waits too", emptyList<String>(), at(t0 + 70 * s, traction))
        assertEquals(listOf("⚠ Мало тяги: предел тока 15,8 А"), at(t0 + 92 * s, traction))
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

    /** What the BMS and the controller say themselves is marked at once, at the lamp's level or not, noisy or not. */
    @Test fun bmsConditionsAreMarkedAtOnce() {
        val m = LampMarks()
        fun at(t: Long, vararg ls: Lamp) = m.update(ls.toList(), t).map { it.text }
        assertEquals(listOf("⛔ Сейчас отключится 31,5 В"), at(0, critical))
        assertEquals("red stays red: a new condition", listOf("⛔ BMS отключает батарею 31,2 В"), at(2 * s, bmsCut))
        assertEquals(listOf("⛔ Нет связи с BMS (код 0x26)"), at(5 * s, noBms))
        assertEquals(listOf("⚠ Руль молчит"), at(8 * s, noBms, silent))
        assertEquals("the link is noisy, this one is not held back", listOf("⚠ Руль на связи, BMS молчит"), at(10 * s, noBms, dashOnly))
        assertEquals(emptyList<String>(), at(12 * s, noBms, silent))
        assertEquals("the same one again within 30 s: nothing", emptyList<String>(), at(14 * s, noBms, dashOnly))
        assertEquals(emptyList<String>(), at(20 * s, noBms, silent))
        assertEquals(listOf("⚠ Руль на связи, BMS молчит"), at(41 * s, noBms, dashOnly))
        assertEquals("back to «Сейчас отключится»: the same level, no mark", emptyList<String>(), at(50 * s, critical))
        assertEquals(listOf("⚠ Зарядка ждёт остывания 45 °C"), at(60 * s, critical, chargeWait))
        val ov = Lamp(TellTales.OVERVOLT, AMBER, "Полная — тормози мягко", 42.2)
        assertEquals(listOf("⚠ Батарея полная 42,2 В"), at(62 * s, critical, chargeWait, ov))
        assertEquals(listOf("⛔ Защита от перенапряжения 42,4 В"), at(63 * s, critical, chargeWait, ov.copy(level = RED, text = "Защита от перенапряжения", value = 42.4)))
        // shown again after 30 s: marked again; [Mark.event] tells these from the level changes
        assertEquals(listOf(true to "⛔ Нет связи с BMS (код 0x26)"), m.update(listOf(noBms, chargeWait), 64 * s).map { it.event to it.text })
        // traction is noisy now: on, off, on again within 30 s — one mark
        val n = LampMarks()
        assertEquals(1, n.update(listOf(sag), 0).size)
        assertEquals(0, n.update(emptyList(), 6 * s).size)
        assertEquals(0, n.update(listOf(sag), 12 * s).size)
        assertEquals(0, n.update(emptyList(), 20 * s).size)
        assertEquals(listOf("✓ Мало тяги: просадка — погасла"), n.update(emptyList(), 31 * s).map { it.text })
        assertTrue(TellTales.TRACTION in LampMarks.NOISY)
    }

    /** The notification's title is the item that buzzed, not the first one in the list. */
    @Test fun theTitleIsTheItemThatRang() {
        val a = RideAlerts()
        val hotLamp = temp(AMBER, 49.0)
        val low = BatteryWarning(BatteryAdvisor.LOW, WarningLevel.WARNING, "Заряд на исходе", 32.0)
        val lowLamp = Lamp(TellTales.BATTERY, AMBER, "Заряд на исходе", 32.0)
        assertEquals(RideAlerts.Action.ALERT, a.update(listOf(hotLamp), emptyList(), 0).action)
        // 20.10 22:03:47: the temperature lamp was on (it buzzed before), «Заряд на исходе» buzzed
        val d = a.update(listOf(hotLamp, lowLamp), listOf(low), 60_000)
        assertEquals(RideAlerts.Action.ALERT, d.action)
        assertEquals(listOf(TellTales.TEMP, TellTales.BATTERY), d.items.map { it.id })
        assertEquals(listOf(TellTales.BATTERY), d.rang.map { it.id })
        val w = RideAlerts.text(d.items, d.head)
        assertEquals("Заряд на исходе", w.title)
        assertEquals("Заряд на исходе · 32,0 В под нагрузкой", w.text)
        assertEquals("Заряд на исходе · 32,0 В под нагрузкой\nБатарея горячая · 49 °C\n" + WarnText.hint(low), w.big)
        assertEquals("the list's first, as before", "Батарея горячая", RideAlerts.title(d.items))
        // another lamp's own first buzz: its title
        val u = a.update(listOf(hotLamp, lowLamp, traction), listOf(low), 70_000)
        assertEquals(RideAlerts.Action.ALERT, u.action)
        assertEquals("Мало тяги: предел тока", u.head!!.title)
        val q = a.update(listOf(hotLamp, lowLamp), listOf(low), 80_000)
        assertEquals(RideAlerts.Action.UPDATE, q.action)
        assertEquals("what buzzed went off: the first one", "Батарея горячая", q.head!!.title)
        assertEquals("a quiet change keeps it", "Батарея горячая", a.update(listOf(hotLamp, lowLamp.copy(value = 31.9)), listOf(low), 85_000).head!!.title)
        // red, then something as strong silently: the one that buzzed stays; stronger silently: the title follows
        val crit = BatteryWarning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, "Сейчас отключится", 31.5)
        val r = a.update(listOf(hotLamp, critical), listOf(crit), 110_000)
        assertEquals(RideAlerts.Action.ALERT, r.action)
        assertEquals("Сейчас отключится", RideAlerts.text(r.items, r.head).title)
        assertEquals(RideAlerts.Action.ALERT, a.update(listOf(temp(RED, 55.0), critical), listOf(crit), 120_000).action)
        val r2 = a.update(listOf(temp(RED, 56.0), critical), listOf(crit), 130_000)
        assertEquals("Перегрев — сбавь газ", r2.head!!.title)
        val r3 = a.update(listOf(hotLamp, critical), listOf(crit), 140_000)
        val r4 = a.update(listOf(temp(RED, 55.0), critical), listOf(crit), 150_000)
        assertEquals("temperature red again within 5 min: silent", RideAlerts.Action.UPDATE, r4.action)
        assertEquals("as strong as the rest: the title stays", r3.head!!.title, r4.head!!.title)
        assertEquals(RideAlerts.Action.CANCEL, a.update(emptyList(), emptyList(), 160_000).action)
        // the advisor's advice is not put on the charge lamp lit for the BMS itself
        val gone = crit.copy(value = null)
        assertEquals(LampText.hint(noBms), RideAlerts.items(listOf(noBms), listOf(gone)).single().hint)
        assertEquals(WarnText.hint(gone), RideAlerts.items(listOf(critical), listOf(gone)).single().hint)
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
        for ((name, wantRed) in listOf(HOT to true, TWO_UP_HALF to false, TO_ZERO to false)) {
            val r = replay("logs/$name")
            // never two marks of a noisy lamp within 30 s; another one within 30 s only as a step up — or as what the
            // BMS / the controller say themselves (LampMarks.EVENTS)
            for (id in TellTales.IDS) {
                val ms = r.marks.filter { it.second.id == id }
                for ((a, b) in ms.zipWithNext()) {
                    val dt = b.first - a.first
                    if (b.second.event) assertTrue("$name $id ${b.second}", LampMarks.EVENTS.getValue(id).any { b.second.text.contains(it) })
                    else if (id in LampMarks.NOISY) assertTrue("$name $id", dt >= LampMarks.GAP_MS)
                    else if (dt < LampMarks.GAP_MS) assertTrue("$name $id ${a.second} → ${b.second}", b.second.level.ordinal > a.second.level.ordinal)
                }
            }
            assertTrue(name + ": " + r.marks.size + " marks of " + r.changes + " changes", r.marks.size < r.changes)
            val tr = r.marks.count { it.second.id == TellTales.TRACTION }
            println("$name: ${r.marks.size} marks of ${r.changes} changes (${r.other.size} with traction not noisy), traction $tr (${r.other.count { it.second.id == TellTales.TRACTION }}), rang ${r.rang.size}")
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

    /** 20.10 to the BMS's cut: the BMS's and the controller's own words in the log, the right title on the notification. */
    @Test fun toZero() {
        val r = replay("logs/$TO_ZERO")
        fun first(prefix: String) = r.marks.firstOrNull { it.second.text.startsWith(prefix) }?.first
        val cut = first("⛔ BMS отключает батарею")!!
        assertTrue("$cut", cut in 1_810_000L..1_811_000L)                     // b5 0x02 from 1810.0 s, the last BMS frame 1812.4 s
        assertEquals("31.249 V rounded once", "⛔ BMS отключает батарею 31,2 В", r.marks.first { it.first == cut }.second.text)
        assertTrue("${first("⛔ Нет связи с BMS (код 0x26)")}", first("⛔ Нет связи с BMS (код 0x26)")!! in 1_814_700L..1_815_500L)
        val dash = r.marks.filter { it.second.text == "⚠ Руль на связи, BMS молчит" }.map { it.first }
        assertTrue("$dash", dash.first() in 1_822_000L..1_824_000L && dash.any { it in 2_117_000L..2_160_000L })
        assertTrue("regen on the full pack: the BMS's protection", r.marks.any { it.first in 130_000L..145_000L && it.second.text.startsWith("⛔ Защита от перенапряжения") })
        // «Заряд на исходе» buzzed at 22:03:47 with «Батарея горячая» on: the title is the one that buzzed
        val low = r.alerts.single { (_, d) -> d.rang.any { it.title == "Заряд на исходе" } }
        assertTrue("${low.first}", low.first in 1_285_000L..1_287_000L)
        assertEquals(listOf(TellTales.TEMP, TellTales.BATTERY), low.second.items.map { it.id })
        assertEquals("Заряд на исходе", RideAlerts.text(low.second.items, low.second.head).title)
        // «просадка» held below 30 %, traction noisy: no relighting every minute towards the cut
        fun lit(ms: List<Pair<Long, LampMarks.Mark>>) = ms.filter { it.second.id == TellTales.TRACTION && it.second.level != OFF }
        val traction = lit(r.marks)
        val stepsAtOnce = lit(r.other)
        println("20.10 traction marks: ${traction.size} (${traction.count { it.first > 1_100_000 }} from 1100 s), at once as before: ${stepsAtOnce.size} (${stepsAtOnce.count { it.first > 1_100_000 }})")
        assertTrue("${traction.map { it.first }}", traction.count { it.first > 1_100_000 } <= 3)
        assertTrue(traction.size <= stepsAtOnce.size)
    }

    /** The charging lamp waiting for the pack to cool, as a mark: 19.10 on the charger after the cutoff, 16.10 at the start. */
    @Test fun chargeWaitsAreMarked() {
        for ((name, from) in listOf("20261019-174739-log-app.json.gz" to 5_590_000L, "20261016-194323-log-app.json.gz" to 0L)) {
            val r = replay("logs/$name")
            val w = r.marks.filter { it.second.text.startsWith("⚠ Зарядка ждёт остывания") }.map { it.first }
            println("$name charge-wait marks: " + w.map { it / 1000.0 })
            assertTrue("$name $w", w.isNotEmpty() && w.first() >= from)
            for (m in r.marks) for (k in ANALYZER_KEYS) assertFalse("$name: «${m.second.text}» reads as a «$k» label to tools/analyze_log.py", m.second.text.lowercase().contains(k))
        }
    }

    /** [other]: the marks with traction not noisy (steps up at once, as before). */
    private class Replay(
        val marks: List<Pair<Long, LampMarks.Mark>>, val changes: Int, val rang: List<Pair<Long, AlertItem>>,
        val alerts: List<Pair<Long, RideAlerts.Decision>>, val other: List<Pair<Long, LampMarks.Mark>>,
    )

    /** Through the tracker as the phone runs it (a new stream at each «Найдено сервисов»), a snapshot every 250 ms. */
    private fun replay(path: String): Replay {
        val tr = TelemetryTracker(TestLogs.decoder())
        tr.link = Link.CONNECTED
        val cw = ClusterWatch()
        val before = LampMarks(noisy = LampMarks.NOISY - TellTales.TRACTION)
        val other = ArrayList<Pair<Long, LampMarks.Mark>>()
        val marks = ArrayList<Pair<Long, LampMarks.Mark>>()
        val rang = ArrayList<Pair<Long, AlertItem>>()
        val alerts = ArrayList<Pair<Long, RideAlerts.Decision>>()
        var changes = 0
        val rx = TestLogs.rx(path, found = true)
        var k = 0
        var clock = rx.first().t
        while (clock <= rx.last().t + 5000) {
            while (k < rx.size && rx[k].t <= clock) {
                val e = rx[k++]
                if (e.found) tr.restartStreams() else tr.onNotification(e.s, e.u, e.b, e.t)
            }
            val t = tr.snapshot(clock)
            changes += cw.changes(t.lamps, clock).size
            for (m in cw.marks.update(t.lamps, clock)) marks.add(clock to m)
            for (m in before.update(t.lamps, clock)) other.add(clock to m)
            val d = cw.alerts.update(t.lamps, t.warnings, clock)
            for (i in d.rang) rang.add(clock to i)
            if (d.action == RideAlerts.Action.ALERT) alerts.add(clock to d)
            clock += 250
        }
        return Replay(marks, changes, rang, alerts, other)
    }

    companion object {
        const val HOT = "20261015-155523-log-app.json.gz"
        const val TWO_UP_HALF = "20261016-135138-log-app.json.gz"
        const val TO_ZERO = "20261020-214221-do-otsechki-do-nulya-app.json.gz"
        /** tools/analyze_log.py MARKER_KINDS: a mark with one of these is taken for the owner's own label. */
        val ANALYZER_KEYS = listOf(
            "отпустил", "release", "зарядка вкл", "charge on", "charging on", "зарядка выкл", "charge off", "charging off",
            "газ", "throttle", "тормоз", "brake", "стоп", "stop", "режим", "mode", "фара", "свет", "light",
        )
    }
}
