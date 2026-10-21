package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** «Кольцо» laid out ([RingLayout], the JVM's rough widths): the demo, any lamps, long texts, the centre, taps, frames. */
class FaceRingTest {
    private val m = RingLayout.ROUGH

    private fun check(s: DialState, now: Long, pick: RingPick? = null, what: String = "") {
        val f = RingLayout.frame(s, now, m, pick)
        val p = RingLayout.problems(s, now, f, pick)
        if (p.isNotEmpty()) fail("$what @$now: " + p.joinToString("; "))
    }

    private fun tele(lamps: List<Lamp>, units: String = TellTales.MPH, kmh: Double? = 22.0, link: Link = Link.CONNECTED, streaming: Boolean = true) =
        Telemetry(
            t = 0, link = link, streaming = streaming, units = units, speedKmh = kmh, mode = 3, voltage = 38.4, batteryPct = 56.0,
            batteryPctFine = 56.3, powerW = 300.0, powerFastW = 310.0, currentA = 8.0, currentFastA = 8.1, peakW = 640.0,
            temp1C = 30.0, temp2C = 31.0, range = RangeEstimate(5.6, 3.5, 67.2, 12.0, "recent"), lamps = lamps,
        )

    private fun lamp(id: String, level: LampLevel, text: String, value: Double? = null, rising: Boolean = false, units: String = TellTales.MPH) =
        TellTales.lamp(id, level, text, value, rising, units)

    private val hot = lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
    private val warm = lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM_RISING, 42.0, rising = true)
    private val cut = lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5)
    private val traction = lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8)
    private val link = lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST)
    private val svc = lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0)

    @Test fun theDemoLaysOut() {
        for (sc in DialScenarios.ALL) for ((i, s) in sc.states.withIndex()) {
            val until = sc.states.getOrNull(i + 1)?.nowMs ?: (s.nowMs + 1_000)
            for (dt in longArrayOf(0, 50, 150, 350, 600)) if (s.nowMs + dt < until || dt == 0L) check(s, s.nowMs + dt, what = sc.key)
        }
    }

    @Test fun theDemosCentre() {
        val amber = DialScenarios["amber"]
        // both amber: the temperature first (IDS order), the traction tile ringed
        val s = amber.at(amber.changeMs + 1_200)
        val f = RingLayout.frame(s, s.nowMs, m)
        assertEquals(TellTales.TEMP, f.centre.tgt?.id)
        assertTrue(f.ops.any { it is RingLayout.Sector && it.colour == androidx.compose.ui.graphics.Color.White })
        assertTrue(f.boxes.any { it.what == "Батарея горячая" })
        assertTrue(f.boxes.any { it.what == "49" } && f.boxes.any { it.what == "${TellTales.NBSP}°C" })
        val crit = DialScenarios["critical"]
        assertEquals(TellTales.BATTERY, RingLayout.frame(crit.at(crit.changeMs + 600), crit.changeMs + 600, m).centre.tgt?.id)
        // the limiter's line: in from the tap, out by 3 s
        val lim = DialScenarios["limiter"]
        fun line(at: Long) = RingLayout.frame(lim.at(at), at, m).boxes.firstOrNull { it.what.startsWith("Ограничитель") }
        assertNull(line(lim.changeMs))
        assertNotNull(line(lim.changeMs + 600))
        assertTrue(line(lim.changeMs + 600)!!.what.endsWith("mph"))
        assertNull(line(lim.changeMs + RingLayout.LINE_MS + 1))
        // the bulb check: no lamp in the centre, its line under the power
        val bulb = DialScenarios["bulb"]
        val b = RingLayout.frame(bulb.at(bulb.changeMs + 600), bulb.changeMs + 600, m)
        assertNull(b.centre.tgt)
        assertTrue(b.boxes.any { it.what == Dial.BULB_TEXT })
        assertTrue(b.tiles.count { it.bulb } >= 8)
    }

    @Test fun anyLampsAnyLevelsAnyHealth() {
        val r = Random(20261021)
        val texts = mapOf(
            TellTales.TEMP to listOf(TellTales.T_OVERHEAT, TellTales.T_HOT, TellTales.T_HOT_RISING, TellTales.T_WARM, TellTales.T_WARM_RISING, TellTales.T_RISING),
            TellTales.BATTERY to listOf(TellTales.T_NO_BMS, TellTales.T_BMS_CUT, TellTales.T_CUTTING, TellTales.T_LOW, "Заряд 23,4 %"),
            TellTales.TRACTION to listOf(TellTales.T_LIMIT, TellTales.T_SAG),
            TellTales.CONSUMPTION to listOf("Расход 24 Вт·ч/км"),
            TellTales.WEAK to listOf("Батарея устала: 0,38 Ом"),
            TellTales.OVERVOLT to listOf(TellTales.T_OV_CUT, TellTales.T_FULL, "Перенапряжение 42,5 В"),
            TellTales.ECO to listOf("ECO"),
            TellTales.SERVICE to listOf("Ёмкость 75 % от паспорта"),
            TellTales.LINK to listOf(TellTales.T_LINK_LOST, TellTales.T_DASH_NO_BMS, TellTales.T_SILENT, "Код контроллера 0x21",
                "Связь с самокатом то есть, то нет уже который раз подряд"),
            TellTales.OVERSPEED to listOf(TellTales.T_OVERSPEED),
            TellTales.CHARGING to listOf("Зарядка 1,7 А", TellTales.T_CHARGE_WAIT),
            TellTales.COLD to listOf(TellTales.T_COLD),
        )
        val levels = LampLevel.entries.filter { it != LampLevel.OFF }
        var frames = 0
        repeat(1_500) { case ->
            val units = if (r.nextBoolean()) TellTales.MPH else TellTales.KMH
            val b = DialBuilder()
            var t = 10_000L * case
            fun pickLamps(): List<Lamp> = TellTales.IDS.filter { r.nextInt(4) == 0 }.map { id ->
                val text = texts.getValue(id).random(r)
                // now and then a level this build reads as OFF (a newer phone's)
                val level = if (r.nextInt(25) == 0) LampLevel.OFF else if (r.nextInt(3) == 0) levels.random(r) else when {
                    text == TellTales.T_OVERHEAT || text == TellTales.T_NO_BMS || text == TellTales.T_BMS_CUT || text == TellTales.T_CUTTING || text == TellTales.T_OV_CUT -> LampLevel.RED
                    id == TellTales.ECO || text.startsWith("Зарядка 1") -> LampLevel.GREEN
                    id == TellTales.OVERSPEED || id == TellTales.COLD -> LampLevel.BLUE
                    text.contains("тёпл", true) || id == TellTales.WEAK || text == TellTales.T_RISING -> LampLevel.YELLOW
                    else -> LampLevel.AMBER
                }
                val value = when (r.nextInt(6)) { 0 -> null; 1 -> r.nextDouble(-40.0, 140_000.0); else -> r.nextDouble(0.0, 60.0) }
                val l = TellTales.lamp(id, level, text, value, rising = r.nextInt(5) == 0, units = units)
                when (r.nextInt(8)) {
                    0 -> Lamp(l.id, l.level, l.text, l.value, l.rising)                          // an older phone: no titles
                    1 -> l.copy(title = "Очень длинный заголовок лампы с подробностями", detail = "и подпись под числом из многих разных слов")
                    else -> l
                }
            } + if (r.nextInt(10) == 0) listOf(Lamp("brakes", levels.random(r), "Тормоза перегрелись 80 °C", 80.0)) else emptyList()
            val health = if (r.nextInt(3) == 0) Health.entries.random(r) else Health.LIVE
            val ambient = r.nextInt(5) == 0
            val bulbAt = if (r.nextInt(6) == 0) t + r.nextLong(-1_000, 500) else null
            val cues = DialCues(
                tapAtMs = if (r.nextInt(4) == 0) t + r.nextLong(-3_500, 200) else null,
                rumble = r.nextInt(6) == 0,
                buzz = if (r.nextInt(3) == 0) LampBuzz(t + r.nextLong(-2_000, 200), if (r.nextBoolean()) LampLevel.RED else LampLevel.AMBER) else null,
            )
            val kmh = if (r.nextInt(8) == 0) null else r.nextDouble(0.0, 40.0)
            for (step in 0 until 3) {
                val tel = tele(pickLamps(), units, kmh).let { if (r.nextInt(8) == 0) it.copy(range = null, voltage = null, batteryPctFine = null, batteryPct = null) else it }
                val s = b.build(
                    tel, health, t, cues = cues, bulbAtMs = bulbAt, bulbOn = bulbAt != null && t - bulbAt in 0 until TellTales.BULB_CHECK_MS,
                    ambient = ambient, lowBit = ambient && r.nextBoolean(), clock = if (r.nextBoolean()) "21:10" else "",
                )
                val pick = if (r.nextInt(4) == 0) RingLayout.next(s, t)?.let { RingPick(it, t - r.nextLong(0, 7_000)) } else null
                for (dt in longArrayOf(0, 120, 300, 450, 900, 5_200)) {
                    check(s, t + dt, pick, "case $case step $step")
                    frames++
                }
                t += r.nextLong(100, 6_000)
            }
        }
        assertTrue(frames > 20_000)
    }

    @Test fun longTextsShrinkThenEllipsize() {
        val long = Lamp(TellTales.LINK, LampLevel.AMBER, "x", title = "Невероятно длинный заголовок лампы", valueText = "123${TellTales.NBSP}456,7${TellTales.NBSP}Вт·ч/км",
            detail = "подпись из очень многих слов подряд")
        val s = DialBuilder().build(tele(listOf(long)), Health.LIVE, 0)
        val f = RingLayout.frame(s, 1_000, m)
        assertTrue(RingLayout.problems(s, 1_000, f).isEmpty(), RingLayout.problems(s, 1_000, f).toString())
        val title = f.ops.filterIsInstance<RingLayout.Txt>().first { it.text.startsWith("Невероятно") }
        assertTrue(title.text.endsWith("…") && title.px == 18f)
        // a value too wide for 42 px: smaller, still beside the icon and inside the ring
        val num = f.ops.filterIsInstance<RingLayout.Txt>().first { it.text.startsWith("123") }
        assertTrue(num.px < 42f && num.x == 216f)
        // no value: the detail in its place, two lines when it needs them
        val bms = DialBuilder().build(tele(listOf(lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS))), Health.LIVE, 0)
        val fb = RingLayout.frame(bms, 1_000, m)
        assertTrue(fb.boxes.any { it.what == "BMS молчит" })
        val detail = fb.ops.filterIsInstance<RingLayout.Txt>().filter { it.x == 216f }
        assertEquals("руль на связи", detail.joinToString(" ") { it.text })
        assertTrue(RingLayout.problems(bms, 1_000, fb).isEmpty())
    }

    @Test fun theValueSplitsAtItsNoBreakSpace() {
        assertEquals("49" to "°C", RingLayout.split("49${TellTales.NBSP}°C"))
        assertEquals("24" to "Вт·ч/км", RingLayout.split("24${TellTales.NBSP}Вт·ч/км"))
        assertEquals("0x21" to "", RingLayout.split("0x21"))
        assertEquals("1${TellTales.NBSP}234" to "Вт", RingLayout.split("1${TellTales.NBSP}234${TellTales.NBSP}Вт"))
    }

    @Test fun theCentreIsTopAndYellowHoldsFiveSeconds() {
        val b = DialBuilder()
        b.build(tele(listOf(svc)), Health.LIVE, 0)
        val s = b.build(tele(listOf(warm, svc)), Health.LIVE, 1_000)
        assertEquals(TellTales.TEMP, RingLayout.target(s, 1_000)?.id)
        assertEquals(TellTales.TEMP, RingLayout.target(s, 5_999)?.id)
        assertNull(RingLayout.target(s, 6_000))                                       // only the tile now
        // the hold's end: a crossfade to «детали», and frames for it
        val c = RingLayout.centreAt(s, 6_100)
        assertNull(c.tgt); assertEquals(TellTales.TEMP, c.prev?.id); assertEquals(100L, c.tauMs)
        assertEquals(6_000L, RingLayout.wakeAt(s, 4_000))
        assertEquals(6_100L, RingLayout.wakeAt(s, 6_100))
        // amber and red hold it while lit; the stronger one leads; the quiet ones never
        val s2 = b.build(tele(listOf(warm, traction, svc)), Health.LIVE, 9_000)
        assertEquals(TellTales.TRACTION, RingLayout.target(s2, 30_000)?.id)
        val s3 = b.build(tele(listOf(warm, cut, traction, svc)), Health.LIVE, 10_000)
        assertEquals(TellTales.BATTERY, RingLayout.target(s3, 10_000)?.id)
        assertEquals(s3.lamps.top?.id, RingLayout.target(s3, 10_000)?.id)
        val q = b.build(tele(listOf(svc, lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 120.0))), Health.LIVE, 11_000)
        assertNull(RingLayout.target(q, 11_000))
        // the set change crossfades from the lamp before
        val c3 = RingLayout.centreAt(s3, 10_200)
        assertEquals(TellTales.BATTERY, c3.tgt?.id); assertEquals(TellTales.TRACTION, c3.prev?.id); assertEquals(200L, c3.tauMs)
        assertNull(RingLayout.centreAt(s3, 10_400).tauMs)
    }

    @Test fun aTapShowsTheNextLampForSixSeconds() {
        val b = DialBuilder()
        val s = b.build(tele(listOf(hot, traction, link, svc)), Health.LIVE, 1_000)
        assertEquals(listOf(TellTales.TEMP, TellTales.TRACTION, TellTales.LINK), s.lamps.alerts.map { it.id })
        val t1 = RingLayout.next(s, 2_000)
        assertEquals(TellTales.TRACTION, t1)
        val p1 = RingPick(t1!!, 2_000)
        assertEquals(TellTales.TRACTION, RingLayout.target(s, 2_000, p1)?.id)
        assertEquals(TellTales.TRACTION, RingLayout.target(s, 7_999, p1)?.id)
        assertEquals(TellTales.TEMP, RingLayout.target(s, 8_000, p1)?.id)          // back to the main one
        val c = RingLayout.centreAt(s, 2_150, p1)
        assertEquals(TellTales.TRACTION, c.tgt?.id); assertEquals(TellTales.TEMP, c.prev?.id)
        assertEquals(2_000L, RingLayout.wakeAt(s.copy(nowMs = 2_000), 2_000, p1))
        assertEquals(8_000L, RingLayout.wakeAt(s, 3_000, p1))
        // again and again: round the lit ones in TellTales.top's order
        val t2 = RingLayout.next(s, 3_000, p1)
        assertEquals(TellTales.LINK, t2)
        assertEquals(TellTales.TEMP, RingLayout.next(s, 3_500, RingPick(t2!!, 3_000)))
        // the picked one goes out: the main one again
        val s2 = b.build(tele(listOf(hot, link, svc)), Health.LIVE, 4_000)
        assertEquals(TellTales.TEMP, RingLayout.target(s2, 4_000, p1)?.id)
        // nothing lit, the bulb check, ambient: no pick
        val calm = DialBuilder().build(tele(listOf(svc)), Health.LIVE, 0)
        assertNull(RingLayout.next(calm, 100))
        assertNull(RingLayout.next(s.copy(ambient = true), 2_000))
        assertNull(RingLayout.next(s.copy(bulbAtMs = 1_900, bulbOn = true), 2_000))
        // a yellow past its hold comes back for 6 s
        val y = DialBuilder().build(tele(listOf(warm)), Health.LIVE, 0)
        assertNull(RingLayout.target(y, 9_000))
        assertEquals(TellTales.TEMP, RingLayout.next(y, 9_000))
        assertEquals(TellTales.TEMP, RingLayout.target(y, 9_000, RingPick(TellTales.TEMP, 9_000))?.id)
    }

    @Test fun theCrossfadeHasNoDipToBlack() {
        val sc = DialScenarios["amber"]
        val first = DialScenarios["warm"]
        // details → lamp: the details out by 180 ms, the lamp in from 100 to 400 ms
        val s = first.at(first.changeMs)
        fun alphaOf(f: RingLayout.Frame, what: String) = f.boxes.filter { it.what == what }.maxOfOrNull { it.alpha } ?: 0f
        val f0 = RingLayout.frame(s, first.changeMs, m)
        assertEquals(0f, alphaOf(f0, "Батарея тёплая"))
        assertEquals(1f, alphaOf(f0, s.range!!))
        val f4 = RingLayout.frame(s, first.changeMs + 400, m)
        assertEquals(1f, alphaOf(f4, "Батарея тёплая"))
        // lamp → lamp keeps the lamp layout's lines steady
        val c = DialScenarios["critical"]
        val fc = RingLayout.frame(c.at(c.changeMs + 150), c.changeMs + 150, m)
        assertTrue(fc.boxes.filter { it.group == RingLayout.Group.LOWER }.all { it.alpha == 1f })
        assertTrue(fc.boxes.any { it.group == RingLayout.Group.OUT } && fc.boxes.any { it.group == RingLayout.Group.IN })
        assertTrue(sc.states.isNotEmpty())
    }

    @Test fun framesOnlyWhileSomethingMoves() {
        val b = DialBuilder()
        val calm = b.build(tele(listOf(svc)), Health.LIVE, 0)
        assertNull(RingLayout.wakeAt(calm, 5_000))
        assertEquals(100L, RingLayout.wakeAt(calm, 100))                           // the service lamp's entrance
        val red = b.build(tele(listOf(cut, svc)), Health.LIVE, 10_000)
        assertEquals(60_000L, RingLayout.wakeAt(red, 60_000))                      // the red pulse runs
        assertNull(RingLayout.wakeAt(red.copy(ambient = true), 60_000))
        // the limiter's line: in, still, out
        val tap = calm.copy(cues = DialCues(tapAtMs = 20_000))
        assertEquals(20_100L, RingLayout.wakeAt(tap, 20_100))
        assertEquals(20_000L + RingLayout.LINE_MS - RingLayout.LINE_OUT_MS, RingLayout.wakeAt(tap, 21_000))
        assertNull(RingLayout.wakeAt(tap, 23_100))
        // the ▲ hops every 10 s
        val w = DialBuilder().build(tele(listOf(warm)), Health.LIVE, 0)
        assertEquals(5_000L, RingLayout.wakeAt(w, 3_000))                          // first the hold's end
        assertEquals(10_000L, RingLayout.wakeAt(w, 7_000))
    }

    @Test fun ambientIsStaticOutlinesAndShifted() {
        val sc = DialScenarios["ambient"]
        val s = sc.at(sc.changeMs + 600)
        val a = RingLayout.frame(s, s.nowMs, m)
        val b = RingLayout.frame(s, s.nowMs + 777, m)
        assertEquals(a.ops, b.ops)
        assertTrue(a.ops.filterIsInstance<RingLayout.Icon>().filter { it.px >= RingLayout.BIG_ICON }.all { it.outline })
        assertTrue(a.ops.none { it is RingLayout.Hair })                            // off places are not drawn
        assertTrue(s.burnInDp != 0f to 0f)
        assertNull(RingLayout.wakeAt(s, s.nowMs))
    }

    @Test fun oldDataDimsTheValuesNotTheStatusNorTheBulbCheck() {
        val b = DialBuilder()
        val s = b.build(tele(listOf(hot, svc), link = Link.RECONNECTING), Health.RECONNECTING, 0, bulbAtMs = 0, bulbOn = true)
        val f = RingLayout.frame(s, 400, m)
        assertEquals("переподключаюсь", f.boxes.single { it.group == RingLayout.Group.STATUS }.what)
        assertEquals(1f, f.boxes.single { it.group == RingLayout.Group.STATUS }.alpha)
        assertTrue(f.ops.filterIsInstance<RingLayout.Power>().single().alpha < 1f)
        assertTrue(f.tiles.any { it.bulb })
        // the phone gone: two lines where the clock is
        val gone = b.build(tele(listOf(hot, svc)), Health.NO_PHONE, 10_000)
        val g = RingLayout.frame(gone, 10_000, m)
        assertEquals(listOf("нет данных", "с телефона"), g.boxes.filter { it.group == RingLayout.Group.STATUS }.map { it.what })
        assertTrue(RingLayout.problems(gone, 10_000, g).isEmpty())
        assertTrue(g.boxes.filter { it.group == RingLayout.Group.IN }.all { it.alpha == Health.NO_PHONE.alpha })
    }

    @Test fun kmhAndMissingValues() {
        val b = DialBuilder()
        val s = b.build(
            tele(listOf(lamp(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 29.3, units = TellTales.KMH)), TellTales.KMH, 29.3),
            Health.LIVE, 0, cues = DialCues(rumble = true, rumbleSinceMs = 0),
        )
        val f = RingLayout.frame(s, 1_000, m)
        assertTrue(f.boxes.any { it.what == "${TellTales.NBSP}км/ч" })
        assertTrue(f.boxes.any { it.what == "18,2${TellTales.NBSP}mph" })
        assertTrue(RingLayout.problems(s, 1_000, f).isEmpty())
        val none = b.build(Telemetry(link = Link.CONNECTED, streaming = true), Health.LIVE, 2_000)
        val n = RingLayout.frame(none, 2_000, m)
        assertTrue(RingLayout.problems(none, 2_000, n).isEmpty())
        assertNull(n.ops.filterIsInstance<RingLayout.Speed>().single().fraction)
        assertEquals(Fmt.DASH + Dial.NNBSP + "%", n.ops.filterIsInstance<RingLayout.Battery>().single().text)
    }

    @Test fun theTilesShareTheirPlacesAndStayInTheirBand() {
        // all twelve lit red and amber by turns: neighbours share 27,5°, none overlaps, all inside r 213
        val all = TellTales.IDS.mapIndexed { i, id -> Lamp(id, if (i % 2 == 0) LampLevel.RED else LampLevel.AMBER, "x", title = "Т", valueText = "1") }
        val s = DialBuilder().build(tele(all), Health.LIVE, 0)
        for (t in longArrayOf(0, 60, 120, 200, 300, 420, 1_000)) check(s, t, what = "all lit")
        val f = RingLayout.frame(s, 1_000, m)
        assertEquals(TellTales.IDS.size - TellTales.QUIET.size, f.tiles.size)                // the quiet ones are icons
        // two lit neighbours share the 27,5° between their middles
        val byId = f.tiles.associateBy { it.id }
        val temp = byId.getValue(TellTales.TEMP)
        val battery = byId.getValue(TellTales.BATTERY)
        assertEquals(RingLayout.SPACE, (temp.a1 - 0f) + (30f - battery.a0), 0.01f)
    }
}
