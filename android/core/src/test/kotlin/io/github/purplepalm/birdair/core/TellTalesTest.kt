package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TellTalesTest {
    /** One snapshot of a replay: what the lamps saw and showed. */
    class Snap(
        val t: Long, val lamps: Map<String, Lamp>, val streaming: Boolean, val speed: Double?, val mode: Int?,
        val i: Double?, val tempC: Double?, val pct: Double?, val pctFine: Double?,
    ) {
        fun level(id: String) = lamps[id]?.level ?: LampLevel.OFF
        val moving get() = (speed ?: 0.0) >= 3.0
    }

    /** A lit stretch of one lamp: [t0]..[t1] (snapshot times), the strongest level in it. */
    class Period(val t0: Long, val t1: Long, val level: LampLevel) { val s get() = (t1 - t0) / 1000 + 1 }

    companion object {
        val NAMES: List<String> by lazy {
            Repo.file("logs").listFiles()!!.map { it.name }.filter { it.endsWith(".json") || it.endsWith(".json.gz") }.sorted()
        }

        private val cache = HashMap<String, List<Snap>>()

        /** Every log through TelemetryTracker as the phone runs it (link CONNECTED, a new stream after each «Найдено
         *  сервисов»), a snapshot every second of the log's clock, gaps included. Not cached as entries. */
        @Synchronized
        fun replay(name: String): List<Snap> = cache.getOrPut(name) {
            val log = Repo.parseLog(String(Repo.readBytes("logs/$name"), Charsets.UTF_8))
            val es = log.entries
            val out = ArrayList<Snap>()
            if (es.isEmpty()) return@getOrPut out
            val tr = TelemetryTracker(Repo.decoder())
            tr.link = Link.CONNECTED
            var k = 0
            var clock = es.first().t
            while (clock <= es.last().t + 1000) {
                while (k < es.size && es[k].t <= clock) {
                    val e = es[k++]
                    if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) tr.restartStreams()
                    if (e.k == "rx" && e.b != null) tr.onNotification(e.s!!, e.u!!, e.b, e.t)
                }
                val s = tr.snapshot(clock)
                val temp = listOfNotNull(s.temp1C, s.temp2C).maxOrNull()
                out.add(Snap(clock, s.lamps.associateBy { it.id }, s.streaming, s.speedKmh, s.mode, s.currentA, temp, s.batteryPct, s.batteryPctFine))
                clock += 1000
            }
            out
        }

        fun periods(snaps: List<Snap>, id: String, pred: (Lamp) -> Boolean = { true }): List<Period> {
            val out = ArrayList<Period>()
            var t0: Long? = null; var t1 = 0L; var lvl = LampLevel.OFF
            for (s in snaps) {
                val l = s.lamps[id]?.takeIf(pred)
                if (l != null) {
                    if (t0 == null) { t0 = s.t; lvl = l.level } else lvl = maxOf(lvl, l.level)
                    t1 = s.t
                } else if (t0 != null) { out.add(Period(t0, t1, lvl)); t0 = null }
            }
            if (t0 != null) out.add(Period(t0, t1, lvl))
            return out
        }

        const val SOLO = "20261014-185422-log.json.gz"
        const val CUTOFF_HOT = "20261015-155523-log-app.json.gz"
        const val TWO_UP_HALF = "20261016-135138-log-app.json.gz"
        const val TWO_UP_EMPTY = "20261016-190042-log-app.json.gz"
        const val CUTOFF = "20261019-174739-log-app.json.gz"
        const val EVENING = "20261019-210801-log-app.json.gz"
        const val CHARGE = "20261016-205717-log-app.json.gz"
        val TWO_UP = listOf(CUTOFF_HOT, TWO_UP_HALF, TWO_UP_EMPTY, CUTOFF, EVENING)
        val RIDES = listOf(SOLO) + TWO_UP
    }

    private fun first(snaps: List<Snap>, pred: (Snap) -> Boolean) = snaps.firstOrNull(pred)?.t
    private fun share(snaps: List<Snap>, pred: (Snap) -> Boolean) = snaps.count(pred).toDouble() / snaps.size
    private val logsWithBms get() = NAMES.filter { n -> replay(n).any { it.pctFine != null } }

    /** No lamp (and no ▲) lights for under 3 s or goes dark for under 4 s between two lit stretches, on any log. */
    @Test fun noneFlickers() {
        for (name in NAMES) {
            val sn = replay(name)
            for (id in TellTales.IDS) for ((what, ps) in listOf(id to periods(sn, id), "$id ▲" to periods(sn, id) { it.rising })) {
                assertTrue(ps.all { it.s >= 3 }, "$name $what lit for ${ps.minOfOrNull { it.s }} s")
                val gaps = ps.zipWithNext { a, b -> (b.t0 - a.t1) / 1000 - 1 }
                assertTrue(gaps.all { it >= 4 }, "$name $what dark for ${gaps.minOrNull()} s")
            }
        }
    }

    /** 3900–4363 mAh of the rated 5200 (75–84 %): «сервис» from the first BMS frame on, in every log. */
    @Test fun serviceAlways() {
        for (name in logsWithBms) {
            val sn = replay(name).dropWhile { it.pctFine == null }
            assertTrue(sn.all { it.level(TellTales.SERVICE) == LampLevel.AMBER }, name)
            val l = sn.last().lamps.getValue(TellTales.SERVICE)
            assertTrue(l.value!! in 74.0..85.0 && l.text.endsWith("% от паспорта"), "$name $l")
        }
        assertEquals("Ёмкость 84 % от паспорта", replay(SOLO).last().lamps.getValue(TellTales.SERVICE).text)   // 4363 mAh
    }

    /** «Зарядка» on the charger only: at standstill with current into the pack, never on a ride. */
    @Test fun chargingOnlyOnTheCharger() {
        for (name in NAMES) {
            val sn = replay(name)
            for ((k, s) in sn.withIndex()) if (TellTales.CHARGING in s.lamps) {
                assertTrue((s.speed ?: 0.0) < 0.5, "$name ${s.t}")
                assertTrue(sn.subList(maxOf(0, k - 6), k + 1).any { (it.i ?: 0.0) <= -0.1 }, "$name ${s.t}")
            }
        }
        for (name in listOf("20261016-182721-log-app.json.gz", "20261016-182924-log.json.gz", "20261016-183424-log-app.json.gz",
            "20261016-184128-log-app.json.gz", "20261016-194838-log-app.json.gz", "20261019-193325-log-app.json.gz", CHARGE))
            assertTrue(replay(name).any { TellTales.CHARGING in it.lamps }, name)
        val ch = replay(CHARGE).filter { it.streaming }.drop(6)
        assertTrue(share(ch) { TellTales.CHARGING in it.lamps } > 0.99)
        assertEquals("Зарядка 1,7 А", ch[100].lamps.getValue(TellTales.CHARGING).text)
        // the rides before 19.10 had no charger in them; on 19.10 the log ran on the charger first
        for (name in listOf(SOLO, CUTOFF_HOT, TWO_UP_HALF, TWO_UP_EMPTY)) assertTrue(replay(name).none { TellTales.CHARGING in it.lamps }, name)
        for (name in listOf(CUTOFF, EVENING)) assertTrue(replay(name).filter { it.moving }.none { TellTales.CHARGING in it.lamps }, name)
    }

    /** Regen right after a full charge: 43.5 V (19.10 afternoon) and 44.0 V (evening); nowhere else over 43 V. */
    @Test fun overvoltOnTheNineteenthOnly() {
        for (name in NAMES) {
            val ps = periods(replay(name), TellTales.OVERVOLT)
            assertEquals(name == CUTOFF || name == EVENING, ps.isNotEmpty(), name)
        }
        val peak = { name: String -> replay(name).mapNotNull { it.lamps[TellTales.OVERVOLT]?.value }.max() }
        assertEquals(43.5, peak(CUTOFF), 0.05)
        assertEquals(44.04, peak(EVENING), 0.01)
    }

    /** Graded by the hotter sensor: тёплая ≥ 40, горячая ≥ 48, перегрев ≥ 55, each clearing 2 °C lower. */
    @Test fun temperatureGrades() {
        for (name in logsWithBms) for (s in replay(name)) {
            val c = s.tempC ?: continue
            val lvl = s.level(TellTales.TEMP)
            if (c >= 55) assertEquals(LampLevel.RED, lvl, "$name ${s.t} $c")
            else if (c >= 48) assertTrue(lvl >= LampLevel.AMBER, "$name ${s.t} $c")
            else if (c >= 40) assertTrue(lvl >= LampLevel.YELLOW, "$name ${s.t} $c")
            if (lvl == LampLevel.RED) assertTrue(c > 53, "$name ${s.t} $c")
            if (lvl == LampLevel.AMBER) assertTrue(c > 46, "$name ${s.t} $c")
            if (lvl == LampLevel.YELLOW) assertTrue(c > 38 || s.lamps.getValue(TellTales.TEMP).rising, "$name ${s.t} $c")
        }
        // solo the battery stayed ≤ 45 °C: never past «тёплая»
        assertTrue(replay(SOLO).all { it.level(TellTales.TEMP) <= LampLevel.YELLOW })
        // 15.10 went dark at 57 / 61 °C: red ≥ 15 min before, and at the end
        val cut = RideLogs.CUTOFF_T.getValue(RideLogs.CUTOFF_HOT)
        val sn = replay(CUTOFF_HOT)
        val red = first(sn) { it.level(TellTales.TEMP) == LampLevel.RED }!!
        assertTrue(cut - red >= 15 * 60_000, "${(cut - red) / 1000} s")
        assertEquals("Перегрев — сбавь газ", sn.last { it.t <= cut }.lamps.getValue(TellTales.TEMP).text)
    }

    /** ▲ «быстро греется» (> 1 °C/min over 2 min) comes before «тёплая» on every two-up ride. */
    @Test fun risingBeforeWarm() {
        for (name in TWO_UP) {
            val sn = replay(name)
            val rise = first(sn) { it.lamps[TellTales.TEMP]?.rising == true }!!
            val warm = first(sn) { (it.tempC ?: 0.0) >= 40 }!!
            assertTrue(warm - rise >= 30_000, "$name: ▲ ${(warm - rise) / 1000} s before 40 °C")
            assertEquals("Батарея быстро греется", sn.first { it.t == rise }.lamps.getValue(TellTales.TEMP).text)
        }
    }

    /** «Мало тяги»: two-up at the 16 A limit, solo rarely; and at the low-voltage tail, where the controller cuts the current. */
    @Test fun tractionTwoUpAndAtTheTail() {
        fun limited(s: Snap) = s.lamps[TellTales.TRACTION]?.text?.endsWith("предел тока") == true
        fun sagged(s: Snap) = s.lamps[TellTales.TRACTION]?.text?.endsWith("просадка") == true
        val half = replay(TWO_UP_HALF).filter { it.moving }
        assertTrue(share(half, ::limited) > 0.3, "${share(half, ::limited)}")
        assertTrue(half.none(::sagged))                                          // 100 → 54 %: no tail
        for (name in TWO_UP) assertTrue(share(replay(name).filter { it.moving }, ::limited) > 0.05, name)   // 10–43 % of the riding
        val solo = replay(SOLO).filter { it.moving }
        assertTrue(share(solo, ::limited) < 0.03, "${share(solo, ::limited)}")                            // 1 %: two hard starts
        // the tail: only in the bottom third of the charge, and in the last 10 min before the 19.10 cutoff
        for (name in RIDES) assertTrue(replay(name).filter(::sagged).all { (it.pct ?: 100.0) <= 35 }, name)
        val cut = RideLogs.CUTOFF_T.getValue(RideLogs.CUTOFF)
        assertTrue(replay(CUTOFF).any { sagged(it) && it.t in cut - 600_000..cut })
    }

    /** ECO in gentle stretches, in any mode (FLY too), never while pulling hard or at the traction limit. */
    @Test fun ecoWhenGentle() {
        val solo = replay(SOLO).filter { it.moving }
        assertTrue(share(solo) { TellTales.ECO in it.lamps } > 0.25)
        assertTrue(solo.count { TellTales.ECO in it.lamps && it.mode == 3 } > 300)
        for (name in TWO_UP) {
            val mv = replay(name).filter { it.moving }
            val eco = share(mv) { TellTales.ECO in it.lamps }
            assertTrue(eco > 0.0 && eco < 0.25, "$name $eco")
        }
        assertTrue(share(replay(TWO_UP_HALF).filter { it.moving }) { TellTales.ECO in it.lamps } < 0.1)
        for (name in NAMES) for (s in replay(name)) if (TellTales.ECO in s.lamps) {
            assertTrue(TellTales.TRACTION !in s.lamps, "$name ${s.t}")
            assertTrue(s.lamps.getValue(TellTales.ECO).value!! <= 300, "$name ${s.t}")   // the last 10 s on average
        }
    }

    /** «Высокий расход» > 20 Wh/km over the last 2 km: two-up, never solo (10–13 Wh/km). */
    @Test fun consumptionTwoUpOnly() {
        assertTrue(replay(SOLO).none { TellTales.CONSUMPTION in it.lamps })
        for (name in TWO_UP) {
            val ls = replay(name).mapNotNull { it.lamps[TellTales.CONSUMPTION] }
            assertTrue(ls.isNotEmpty(), name)
            assertTrue(ls.all { it.value!! >= 19 }, "$name ${ls.minOf { it.value!! }}")
        }
    }

    /** «Заряд»: red with the advisor's «Сейчас отключится» (19.10 ≥ 1 min before the cutoff), amber below 25 %. */
    @Test fun battery() {
        val cut = RideLogs.CUTOFF_T.getValue(RideLogs.CUTOFF)
        val red = first(replay(CUTOFF)) { it.level(TellTales.BATTERY) == LampLevel.RED }!!
        assertTrue(cut - red >= 60_000)
        val hot = replay(CUTOFF_HOT)                                              // the thermal cutoff: amber only
        assertTrue(hot.none { it.level(TellTales.BATTERY) == LampLevel.RED })
        assertEquals(LampLevel.AMBER, hot.last { it.t <= RideLogs.CUTOFF_T.getValue(RideLogs.CUTOFF_HOT) }.level(TellTales.BATTERY))
        assertTrue(replay(TWO_UP_HALF).none { TellTales.BATTERY in it.lamps })   // 100 → 53 %
        assertEquals(LampLevel.RED, replay(SOLO).last().level(TellTales.BATTERY))
        for (name in logsWithBms) for (s in replay(name)) if ((s.pctFine ?: 100.0) <= 25) assertTrue(TellTales.BATTERY in s.lamps, "$name ${s.t}")
        assertEquals("Заряд 1,8 %", replay("20261019-193212-log-app.json.gz").last().lamps.getValue(TellTales.BATTERY).text)
    }

    /** «Быстрее ограничителя»: > 10 % over the mode's limit (FLY 28.3 km/h), downhill; solo a few times. */
    @Test fun overspeed() {
        for (name in NAMES) {
            val sn = replay(name)
            for (p in periods(sn, TellTales.OVERSPEED)) {
                // a 1 s snapshot may miss the peak (15 frames a second, held 2 s): within the hysteresis then
                val over = sn.filter { it.t in p.t0..p.t1 }.any { s -> (s.speed ?: 0.0) > TellTales.limitKmh(s.mode)!! * 1.1 - 1.0 }
                assertTrue(over, "$name ${p.t0}")
            }
        }
        assertTrue(periods(replay(SOLO), TellTales.OVERSPEED).size in 1..6)
        assertTrue(replay(TWO_UP_HALF).none { TellTales.OVERSPEED in it.lamps })
    }

    /** «Связь»: only around silence (no frame for 3 s) — before the stream starts, after it ends, over reconnections. */
    @Test fun linkOnlyAroundSilence() {
        for (name in NAMES) {
            val sn = replay(name)
            for (p in periods(sn, TellTales.LINK)) assertTrue(sn.any { it.t in p.t0..p.t1 && !it.streaming }, "$name ${p.t0}")
            assertTrue(TellTales.LINK !in sn.first().lamps, name)                  // not in the first 3 s of a connection
        }
        assertTrue(replay("20261014-160502-log.json").drop(5).all { TellTales.LINK in it.lamps })   // the dash never spoke
    }

    @Test fun neverLitInTheLogs() {
        // the coldest battery was 20 °C, R at most 0.33 Ω
        for (name in NAMES) for (s in replay(name)) {
            assertTrue(TellTales.COLD !in s.lamps && TellTales.WEAK !in s.lamps, "$name ${s.t}")
        }
    }

    @Test fun batteryPctFine() {
        for (name in logsWithBms) for (s in replay(name)) if (s.pctFine != null)
            assertEquals(s.pct!!, kotlin.math.floor(s.pctFine), "$name ${s.t}: ${s.pctFine} vs ${s.pct}")
    }

    /** The rules on a made-up stream. */
    @Test fun unit() {
        val tt = TellTales()
        fun bms(t: Long, temp: Int, v: Double = 38.0, i: Double = 0.1, motor: Boolean = false, rem: Int = 2000) =
            tt.onBms(t, BmsFrame(v, i, motor, temp - 3, temp, rem, 3931, 100 * rem / 3931), FastPower.Sample(i, v * i), 0.25, 30)
        fun lamps(t: Long, link: Link = Link.CONNECTED, streaming: Boolean = true) = tt.lamps(t, null, emptyList(), link, streaming).associateBy { it.id }
        var t = 0L
        bms(t, 39); assertNull(lamps(t)[TellTales.TEMP])
        assertEquals(50.8, tt.batteryPctFine)                                     // 2000 / 3931 = 50.88, floored
        for ((c, want) in listOf(40 to LampLevel.YELLOW, 48 to LampLevel.AMBER, 55 to LampLevel.RED, 54 to LampLevel.RED,
            53 to LampLevel.AMBER, 47 to LampLevel.AMBER, 46 to LampLevel.YELLOW, 38 to LampLevel.OFF)) {
            t += 300; bms(t, c)
            assertEquals(want, lamps(t)[TellTales.TEMP]?.level ?: LampLevel.OFF, "$c °C")
        }
        // a steady 1.5 °C/min climb: ▲ after 1.5 min of history, on a «быстро греется» yellow below 40 °C
        val tt2 = TellTales()
        for (k in 0..400) tt2.onBms(k * 300L, BmsFrame(38.0, 5.0, true, 30, (30 + k * 300 * 1.5 / 60_000).toInt(), 2000, 3931, 50), null, null, 0)
        val l = tt2.lamps(120_000, null, emptyList(), Link.CONNECTED, true).single { it.id == TellTales.TEMP }
        assertEquals(Lamp(TellTales.TEMP, LampLevel.YELLOW, "Батарея быстро греется", 33.0, true), l)
        // the link: silent for over 3 s once connected; reconnecting at once
        val tt3 = TellTales()
        assertNull(tt3.lamps(0, null, emptyList(), Link.CONNECTED, false).firstOrNull { it.id == TellTales.LINK })
        assertNull(tt3.lamps(3000, null, emptyList(), Link.CONNECTED, false).firstOrNull { it.id == TellTales.LINK })
        assertEquals("Руль молчит", tt3.lamps(3001, null, emptyList(), Link.CONNECTED, false).single { it.id == TellTales.LINK }.text)
        assertEquals("Связь рвётся", tt3.lamps(3002, null, emptyList(), Link.RECONNECTING, false).single { it.id == TellTales.LINK }.text)
        assertTrue(tt3.lamps(3003, null, emptyList(), Link.IDLE, false).none { it.id == TellTales.LINK })
        // charge low by the BMS, the advisor's verdicts on top
        tt.onBms(t + 300, BmsFrame(36.0, 0.1, false, 30, 30, 900, 3931, 22), null, null, 0)
        assertEquals("Заряд 22,8 %", tt.lamps(t + 300, null, emptyList(), Link.CONNECTED, true).single { it.id == TellTales.BATTERY }.text)
        val crit = BatteryWarning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, "", 31.5)
        assertEquals(Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5), tt.lamps(t + 300, null, listOf(crit), Link.CONNECTED, true).single { it.id == TellTales.BATTERY })
        // consumption from the advisor's recent range only
        val r = RangeEstimate(1.0, 0.6, 20.0, 21.4, "recent")
        assertEquals("Расход 21 Вт·ч/км", tt.lamps(t + 300, r, emptyList(), Link.CONNECTED, true).single { it.id == TellTales.CONSUMPTION }.text)
        assertTrue(tt.lamps(t + 10_000, r.copy(basis = "session"), emptyList(), Link.CONNECTED, true).none { it.id == TellTales.CONSUMPTION })
    }

    @Test fun helpers() {
        assertEquals(TellTales.IDS, TellTales.bulbCheck().map { it.id })
        assertEquals(TellTales.IDS.toSet(), TellTales.NAMES.keys)
        assertEquals(TellTales.IDS.toSet(), TellTales.HELP.keys)
        assertEquals(25.749504, TellTales.limitKmh(3)!!, 1e-9)
        assertEquals(9.0, TellTales.limitMph(1))
        assertNull(TellTales.limitKmh(0))
        val temp = Lamp(TellTales.TEMP, LampLevel.RED, "Перегрев — сбавь газ", 56.0)
        val bat = Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5)
        val svc = Lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 76 % от паспорта", 75.6)
        val eco = Lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 180.0)
        val ch = Lamp(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7)
        assertEquals(temp, TellTales.top(listOf(temp, bat, svc)))               // red first, then the cluster order
        assertNull(TellTales.top(listOf(svc, eco)))                              // standing conditions and ECO: no text
        assertEquals(ch, TellTales.top(listOf(svc, eco, ch)))
        val c = TellTales.changes(listOf(svc, eco), listOf(temp, svc))
        assertEquals(listOf(LampChange(TellTales.TEMP, LampLevel.OFF, LampLevel.RED, temp), LampChange(TellTales.ECO, LampLevel.GREEN, LampLevel.OFF, null)), c)
        assertEquals("Лампа «Температура батареи»: красная — Перегрев — сбавь газ", TellTales.markText(c[0]))
        assertEquals("Лампа «ECO»: погасла", TellTales.markText(c[1]))
    }
}
