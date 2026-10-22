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
        /** Solo FLY 100 % → the BMS cut the pack off (1812.4 s), the dash rebooted twice with the BMS silent. */
        const val TO_ZERO = "20261020-214221-do-otsechki-do-nulya-app.json.gz"
        /** 21.10 afternoon from 99 %; regen at 83 % (−6 A) touched 42.4–42.6 V. */
        const val AFTERNOON_2110 = "20261021-163714-do-otsechki-do-nulya-app.json.gz"
        val TWO_UP = listOf(CUTOFF_HOT, TWO_UP_HALF, TWO_UP_EMPTY, CUTOFF, EVENING)
        val SOLOS = listOf(SOLO, TO_ZERO)
        val RIDES = SOLOS + TWO_UP
        /** Logs on the charger with the BMS's hot-charge latch: charging waits for the pack to cool. */
        val CHARGE_WAITS = listOf("20261016-194323-log-app.json.gz", CUTOFF, "20261019-193212-log-app.json.gz", "20261019-193325-log-app.json.gz")
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
            for ((k, s) in sn.withIndex()) if (s.level(TellTales.CHARGING) == LampLevel.GREEN) {
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
        for (name in listOf(SOLO, CUTOFF_HOT, TWO_UP_HALF, TWO_UP_EMPTY, TO_ZERO)) assertTrue(replay(name).none { TellTales.CHARGING in it.lamps }, name)
        for (name in listOf(CUTOFF, EVENING)) assertTrue(replay(name).filter { it.moving }.none { TellTales.CHARGING in it.lamps }, name)
    }

    /** «Зарядка ждёт остывания»: the charger in (b4 0x40), the BMS's hot-charge latch (b6 0x04), no current, still — on the
     *  four logs where the pack came hot to the charger, nowhere else. */
    @Test fun chargingWaitsForTheHotPack() {
        fun waits(s: Snap) = s.level(TellTales.CHARGING) == LampLevel.AMBER
        for (name in NAMES) {
            val sn = replay(name)
            assertEquals(name in CHARGE_WAITS, sn.any(::waits), name)
            for (s in sn.filter(::waits)) {
                assertEquals("Зарядка ждёт остывания", s.lamps.getValue(TellTales.CHARGING).text)
                assertTrue((s.speed ?: 0.0) < 0.5 && kotlin.math.abs(s.i ?: 0.0) < 0.1 && (s.tempC ?: 0.0) >= 40, "$name ${s.t}")
            }
        }
        // 19.10 evening, 1 % at 41 / 45 °C: waited 6.6 min, unplugged at 398 s; replugged cooler (37 / 39 °C) it charged
        val sn = replay("20261019-193325-log-app.json.gz")
        val w = periods(sn, TellTales.CHARGING) { it.level == LampLevel.AMBER }.single()
        assertTrue(w.t0 <= 10_000 && w.t1 in 398_000..405_000, "${w.t0}–${w.t1}")
        assertTrue(sn.any { it.level(TellTales.CHARGING) == LampLevel.GREEN && it.t > 520_000 })
        // 19.10 after the cutoff the charger came in at 5598 s: it waited to the end of the log (56 → 46 °C)
        assertTrue(replay(CUTOFF).filter { it.t > 5_610_000 }.all(::waits))
    }

    /** Regen on a full pack: the BMS's over-voltage warning (b5 0x08, from 42.23 V) and protection (0x04, regen cut),
     *  else regen above 42.3 V. 19.10 to 43.5 V (afternoon) and 44.0 V (evening), 20.10 at 98 % (135–139 s, 42.5 V),
     *  16.10 one warning at 42.25 V, 21.10 warnings at 83 % (410 s and 474 s, to 42.59 V); never on the charger (≤ 41.9 V). */
    @Test fun overvoltOnAFullPack() {
        for (name in NAMES) {
            val ps = periods(replay(name), TellTales.OVERVOLT)
            assertEquals(name in listOf(TWO_UP_HALF, CUTOFF, EVENING, TO_ZERO, AFTERNOON_2110), ps.isNotEmpty(), name)
        }
        // 21.10: the BMS's warning twice on regen at 83 % (b5 0x08 at 409.9 s and 474.5 s), no protection
        val a = periods(replay(AFTERNOON_2110), TellTales.OVERVOLT)
        assertEquals(listOf(LampLevel.AMBER, LampLevel.AMBER), a.map { it.level })
        assertTrue(a[0].t0 in 409_000..411_000 && a[1].t0 in 474_000..476_000, a.joinToString { "${it.t0}" })
        assertEquals("Полная — тормози мягко", replay(AFTERNOON_2110).first { it.t == a[0].t0 }.lamps.getValue(TellTales.OVERVOLT).text)
        val peak = { name: String -> replay(name).mapNotNull { it.lamps[TellTales.OVERVOLT]?.value }.max() }
        assertEquals(43.5, peak(CUTOFF), 0.05)
        assertEquals(44.04, peak(EVENING), 0.01)
        assertEquals(42.53, peak(TO_ZERO), 0.01)
        assertEquals(42.585, peak(AFTERNOON_2110), 0.001)
        // 20.10: the protection from 135.6 s (the warning's single frame at 135.2 s falls between two snapshots)
        val z = periods(replay(TO_ZERO), TellTales.OVERVOLT).single()
        assertTrue(z.t0 in 135_000..137_000 && z.t1 in 139_000..145_000 && z.level == LampLevel.RED, "${z.t0}–${z.t1} ${z.level}")
        assertEquals("Защита от перенапряжения", replay(TO_ZERO).first { it.t == z.t0 }.lamps.getValue(TellTales.OVERVOLT).text)
        val h = periods(replay(TWO_UP_HALF), TellTales.OVERVOLT).single()
        assertTrue(h.t0 in 73_000..74_000 && h.level == LampLevel.AMBER, "${h.t0} ${h.level}")
        assertEquals("Полная — тормози мягко", replay(TWO_UP_HALF).first { it.t == h.t0 }.lamps.getValue(TellTales.OVERVOLT).text)
        // both 19.10 rides reached the protection
        for (name in listOf(CUTOFF, EVENING)) assertTrue(periods(replay(name), TellTales.OVERVOLT).any { it.level == LampLevel.RED }, name)
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
        // … held a minute down there instead of relighting on every pull (it lit 12 times from 30 % on 20.10, 16 on 14.10,
        // 5 on 19.10): from 30 % to the end at most two stretches, and lit at the 20.10 cutoff
        val lowSag = { name: String -> periods(replay(name), TellTales.TRACTION) { it.text.endsWith("просадка") }.filter { p -> replay(name).first { it.t == p.t0 }.pctFine!! <= 30 } }
        for (name in listOf(SOLO, CUTOFF_HOT, CUTOFF, TO_ZERO)) assertTrue(lowSag(name).size in 1..2, "$name ${lowSag(name).size}")
        val zeroCut = RideLogs.CUTOFF_T.getValue(RideLogs.TO_ZERO)
        assertTrue(sagged(replay(TO_ZERO).last { it.t <= zeroCut }))
    }

    /** ECO in gentle stretches, in any mode (FLY too), never while pulling hard, at the traction limit or at the speed
     *  limiter (from 88 % of the mode's limit: FLY 22.7 km/h, where it starts cutting the current). Solo half the riding
     *  is up there (14.10: 741 of 1527 s, 20.10: 993 of 2131 s); below it ECO lit 100 and 118 s (it was 30 % of all
     *  the riding, 180 and 319 s of it at the limiter); two-up 1–7 %. */
    @Test fun ecoWhenGentle() {
        for (name in SOLOS) {
            val solo = replay(name).filter { it.moving }
            assertTrue(share(solo) { TellTales.ECO in it.lamps } in 0.04..0.15, "$name ${share(solo) { TellTales.ECO in it.lamps }}")
            val fly = solo.count { TellTales.ECO in it.lamps && it.mode == 3 }
            assertTrue(fly > 50, "$name $fly in FLY")
        }
        for (name in TWO_UP) {
            val mv = replay(name).filter { it.moving }
            val eco = share(mv) { TellTales.ECO in it.lamps }
            assertTrue(eco > 0.0 && eco < 0.1, "$name $eco")
        }
        assertTrue(share(replay(TWO_UP_HALF).filter { it.moving }) { TellTales.ECO in it.lamps } < 0.02)
        for (name in NAMES) for (s in replay(name)) if (TellTales.ECO in s.lamps) {
            assertTrue(TellTales.TRACTION !in s.lamps, "$name ${s.t}")
            assertTrue(s.lamps.getValue(TellTales.ECO).value!! <= 300, "$name ${s.t}")   // the last 10 s on average
            assertTrue((s.speed ?: 0.0) < 0.88 * (TellTales.limitKmh(s.mode) ?: Double.MAX_VALUE), "$name ${s.t} ${s.speed}")
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
        // 20.10: «Сейчас отключится» 2.6 min before the BMS cut the pack off
        val zeroCut = RideLogs.CUTOFF_T.getValue(RideLogs.TO_ZERO)
        val zeroRed = first(replay(TO_ZERO)) { it.level(TellTales.BATTERY) == LampLevel.RED }!!
        assertTrue(zeroCut - zeroRed in 120_000..180_000, "${(zeroCut - zeroRed) / 1000} s")
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
            for (p in periods(sn, TellTales.LINK) { !it.text.startsWith("Код") }) assertTrue(sn.any { it.t in p.t0..p.t1 && !it.streaming }, "$name ${p.t0}")
            assertTrue(TellTales.LINK !in sn.first().lamps, name)                  // not in the first 3 s of a connection
        }
        assertTrue(replay("20261014-160502-log.json").drop(5).all { TellTales.LINK in it.lamps })   // the dash never spoke
        // the dash answering with no frame for 5 s: after the BMS cut the pack off (20.10, twice rebooted with the BMS
        // silent, error 12) and after the 15.10 thermal cutoff; a power-on's boot lines alone are not it
        fun dash(s: Snap) = s.lamps[TellTales.LINK]?.text == "Руль на связи, BMS молчит"
        for (name in NAMES) assertEquals(name == TO_ZERO || name == CUTOFF_HOT, replay(name).any(::dash), name)
        val z = periods(replay(TO_ZERO), TellTales.LINK) { it.text == "Руль на связи, BMS молчит" }
        assertTrue(z.all { p -> p.t0 in 1_822_000..1_830_000 || p.t0 in 2_117_000..2_157_000 }, z.joinToString { "${it.t0}–${it.t1}" })
        assertTrue(z.any { it.t0 in 2_117_000..2_143_000 } && z.any { it.t0 in 2_145_000..2_157_000 })   // boots A and B
        assertTrue(replay(TO_ZERO).filter { it.t in 2_159_000..2_162_000 }.none(::dash))                 // boot C: data came
    }

    /** The controller's code (byte 8 of 3A 17): 0x21 parked on 15.10 (112.3–125.0 s) on the link lamp; 0x26 (20.10, the BMS gone)
     *  is the charge lamp's «Нет связи с BMS». */
    @Test fun controllerCode() {
        fun code(s: Snap) = s.lamps[TellTales.LINK]?.text?.startsWith("Код контроллера") == true
        for (name in NAMES) assertEquals(name == CUTOFF_HOT, replay(name).any(::code), name)
        val sn = replay(CUTOFF_HOT).filter(::code)
        assertTrue(sn.all { it.t in 112_000..138_000 && it.streaming }, sn.joinToString { "${it.t}" })
        assertEquals(TellTales.lamp(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0), sn.first().lamps.getValue(TellTales.LINK))
    }

    /** 20.10, the BMS's under-voltage cutoff: «BMS отключает батарею» from its flag (b5 0x02, 1810.0–1812.4 s), then the
     *  controller's 0x26 «Нет связи с BMS» until the next power-on (boot C at 2162.9 s); never on another log. */
    @Test fun bmsCutoffOnTheTwentieth() {
        fun cut(s: Snap) = s.lamps[TellTales.BATTERY]?.text == "BMS отключает батарею"
        fun gone(s: Snap) = s.lamps[TellTales.BATTERY]?.text == "Нет связи с BMS"
        for (name in NAMES - TO_ZERO) assertTrue(replay(name).none { cut(it) || gone(it) }, name)
        val sn = replay(TO_ZERO)
        val c = sn.filter(::cut)
        assertTrue(c.size >= 3 && c.all { it.t in 1_810_000..1_815_000 && it.level(TellTales.BATTERY) == LampLevel.RED }, c.joinToString { "${it.t}" })
        // the last flagged frame's voltage as it is: 31.245 V at 1810.9 s (rounded to 0.01, 31.25, it read 31,3)
        assertEquals(31.245, c.first().lamps.getValue(TellTales.BATTERY).value!!, 1e-9)
        val g = periods(sn, TellTales.BATTERY) { it.text == "Нет связи с BMS" }.single()
        assertTrue(g.t0 in 1_814_000..1_816_000 && g.t1 in 2_162_000..2_168_000 && g.level == LampLevel.RED, "${g.t0}–${g.t1}")
        // after boot C the advisor's «Сейчас отключится» again (the pack was not charged)
        assertEquals("Сейчас отключится", sn.first { it.t > 2_170_000 }.lamps.getValue(TellTales.BATTERY).text)
    }

    /** Every text fits the dial's line (≈ 24 characters at its font). */
    @Test fun textsFit() {
        for (name in NAMES) for (s in replay(name)) for (l in s.lamps.values) assertTrue(l.text.length <= 25, "$name ${s.t} «${l.text}»")
    }

    /**
     * The display fields of every lamp lit on every log: a title that fits a dial's big line (no number in it), the value
     * with its unit after a no-break space, a short detail; an older phone's lamp (text and value only) gets the same.
     */
    @Test fun displayFieldsOnTheLogs() {
        val value = Regex("^(−?\\d+(,\\d+)? (°C|В|А|%|Вт|Ом|Вт·ч/км|км/ч)|0x[0-9A-F]{2})$")
        val seen = HashSet<String>()
        for (name in NAMES) for (s in replay(name)) for (l in s.lamps.values) {
            val at = "$name ${s.t} «${l.text}»"
            assertTrue(l.title.isNotBlank() && l.title.length <= TellTales.TITLE_MAX && l.title.none { it.isDigit() }, "$at: «${l.title}»")
            l.valueText?.let { assertTrue(value.matches(it), "$at: «$it»") }
            l.detail?.let { assertTrue(it.isNotBlank() && it.length <= TellTales.DETAIL_MAX, "$at: «$it»") }
            // the lamp's own number, rounded once: «31,249» is «31,2 В»
            if (l.value != null && l.id != TellTales.LINK && l.id != TellTales.OVERSPEED) assertTrue(l.valueText != null, at)
            assertEquals(l, TellTales.display(l.copy(title = "", valueText = null, detail = null)), at)
            seen += l.id + ":" + l.title
        }
        // what the rides lit, by title
        for (k in listOf("temp:Батарея горячая", "temp:Перегрев", "battery:Сейчас отключится", "battery:BMS отключает", "battery:Нет связи с BMS",
            "traction:Мало тяги", "overvolt:Полная", "eco:ECO", "service:Ёмкость батареи", "link:Связь", "charging:Ждёт остывания"))
            assertTrue(k in seen, "$k not in $seen")
    }

    /** Each text's display fields, in km/h and mph. */
    @Test fun displayFields() {
        val nb = TellTales.NBSP
        fun f(id: String, level: LampLevel, text: String, v: Double?, rising: Boolean = false, units: String = TellTales.KMH) =
            TellTales.lamp(id, level, text, v, rising, units).let { Triple(it.title, it.valueText, it.detail) }
        assertEquals(Triple("Перегрев", "56${nb}°C", "сбавь газ"), f(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 56.2))
        assertEquals(Triple("Батарея горячая", "49${nb}°C", "быстро греется"), f(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT_RISING, 49.0, true))
        assertEquals(Triple("Батарея тёплая", "42${nb}°C", null), f(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 42.0))
        assertEquals(Triple("Быстро греется", "38${nb}°C", null), f(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_RISING, 38.0, true))
        assertEquals(Triple("Батарея холодная", "−3${nb}°C", "ёмкость и тяга меньше"), f(TellTales.COLD, LampLevel.BLUE, TellTales.T_COLD, -3.0))
        assertEquals(Triple("BMS отключает", "31,2${nb}В", "низкое напряжение"), f(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 31.249))
        assertEquals(Triple("Нет связи с BMS", null, "код 0x26"), f(TellTales.BATTERY, LampLevel.RED, TellTales.T_NO_BMS, null))
        assertEquals(Triple("Сейчас отключится", "31,5${nb}В", "под нагрузкой"), f(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5))
        assertEquals(Triple("Заряд на исходе", "31,9${nb}В", "под нагрузкой"), f(TellTales.BATTERY, LampLevel.AMBER, TellTales.T_LOW, 31.9))
        assertEquals(Triple("Заряд", "8,7${nb}%", null), f(TellTales.BATTERY, LampLevel.AMBER, "Заряд 8,7 %", 8.7))
        assertEquals(Triple("Мало тяги", "15,8${nb}А", "предел тока"), f(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8))
        assertEquals(Triple("Мало тяги", "31,7${nb}В", "просадка"), f(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_SAG, 31.7))
        assertEquals(Triple("Полная", "42,2${nb}В", "тормози мягко"), f(TellTales.OVERVOLT, LampLevel.AMBER, TellTales.T_FULL, 42.23))
        assertEquals(Triple("Перенапряжение", "42,5${nb}В", "мотор не тормозит"), f(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 42.52))
        assertEquals(Triple("Перенапряжение", "42,4${nb}В", null), f(TellTales.OVERVOLT, LampLevel.AMBER, "Перенапряжение 42,4 В", 42.36))
        assertEquals(Triple("ECO", "180${nb}Вт", "едешь мягко"), f(TellTales.ECO, LampLevel.GREEN, "ECO", 180.4))
        assertEquals(Triple("Выше предела", "29,1${nb}км/ч", "18,1${nb}mph"), f(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 29.1))
        assertEquals(Triple("Выше предела", "18,1${nb}mph", "29,1${nb}км/ч"), f(TellTales.OVERSPEED, LampLevel.BLUE, TellTales.T_OVERSPEED, 29.1, units = TellTales.MPH))
        assertEquals(Triple("Зарядка", "1,7${nb}А", "ток в батарею"), f(TellTales.CHARGING, LampLevel.GREEN, "Зарядка 1,7 А", 1.7))
        assertEquals(Triple("Ждёт остывания", "45${nb}°C", "зарядка подключена"), f(TellTales.CHARGING, LampLevel.AMBER, TellTales.T_CHARGE_WAIT, 45.0))
        assertEquals(Triple("Высокий расход", "21${nb}Вт·ч/км", "за 2${nb}км"), f(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 21 Вт·ч/км", 21.4))
        assertEquals(Triple("Высокий расход", "34${nb}Вт·ч/mi", "за 1,2${nb}mi"), f(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 21 Вт·ч/км", 21.4, units = TellTales.MPH))
        assertEquals(Triple("Батарея устала", "0,38${nb}Ом", "сопротивление"), f(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38))
        assertEquals(Triple("Ёмкость батареи", "75${nb}%", "от паспорта"), f(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0))
        assertEquals(Triple("Связь", null, "рвётся"), f(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST, null))
        assertEquals(Triple("Связь", null, "руль молчит"), f(TellTales.LINK, LampLevel.AMBER, TellTales.T_SILENT, null))
        assertEquals(Triple("BMS молчит", null, "руль на связи"), f(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS, null))
        assertEquals(Triple("Код контроллера", "0x21", null), f(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0))
        // what came with its fields is kept; an id this build does not know: its text without the number
        val own = Lamp(TellTales.TEMP, LampLevel.AMBER, "x", 1.0, title = "Своё", valueText = "1 °C")
        assertEquals(own, TellTales.display(own))
        assertEquals(Triple("Давление", null, "2,1 бар"), TellTales.display(Lamp("tyre", LampLevel.AMBER, "Давление: 2,1 бар", 2.1)).let { Triple(it.title, it.valueText, it.detail) })
        // every title of a lamp without one of its own fits
        for (l in TellTales.bulbCheck()) assertTrue(l.title.length <= TellTales.TITLE_MAX, l.title)
        // the live tracker: the phone's units in the lamps
        val tr = TelemetryTracker(Repo.decoder()).apply { units = TellTales.MPH }
        assertEquals(TellTales.MPH, tr.snapshot(0).units)
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
        assertEquals(TellTales.lamp(TellTales.TEMP, LampLevel.YELLOW, "Батарея быстро греется", 33.0, true), l)
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
        assertEquals(TellTales.lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5), tt.lamps(t + 300, null, listOf(crit), Link.CONNECTED, true).single { it.id == TellTales.BATTERY })
        // consumption from the advisor's recent range only
        val r = RangeEstimate(1.0, 0.6, 20.0, 21.4, "recent")
        assertEquals("Расход 21 Вт·ч/км", tt.lamps(t + 300, r, emptyList(), Link.CONNECTED, true).single { it.id == TellTales.CONSUMPTION }.text)
        assertTrue(tt.lamps(t + 10_000, r.copy(basis = "session"), emptyList(), Link.CONNECTED, true).none { it.id == TellTales.CONSUMPTION })
    }

    /** The BMS's status bits and the controller's code on a made-up stream. */
    @Test fun unitStatusBits() {
        val tt = TellTales()
        fun bms(t: Long, b4: Int = 0x03, b5: Int = 0, b6: Int = 0, v: Double = 38.0, i: Double = 0.06, temp: Int = 30) =
            tt.onBms(t, BmsFrame(v, i, false, temp, temp, 2000, 3936, 50, b4, b5, b6, 110), FastPower.Sample(i, v * i), 0.25, 30)
        fun motion(t: Long, code: Int = 0, kmh: Double = 0.0) = tt.onMotion(t, MotionFrame(kmh, 10.0, 3, 1.0, 1460.0, code))
        fun lamp(t: Long, id: String, streaming: Boolean = true) = tt.lamps(t, null, emptyList(), Link.CONNECTED, streaming).firstOrNull { it.id == id }
        val crit = listOf(BatteryWarning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, "Сейчас отключится", 31.5))
        var t = 0L
        motion(t); bms(t)
        // under-voltage beats the advisor, held 5 s after the last flagged frame
        bms(300, b5 = 0x22, v = 31.25, i = 7.7)
        assertEquals(TellTales.lamp(TellTales.BATTERY, LampLevel.RED, "BMS отключает батарею", 31.25), tt.lamps(300, null, crit, Link.CONNECTED, true).single { it.id == TellTales.BATTERY })
        assertEquals("BMS отключает батарею", lamp(5300, TellTales.BATTERY, streaming = false)?.text)
        assertEquals("Сейчас отключится", tt.lamps(5301, null, crit, Link.CONNECTED, false).single { it.id == TellTales.BATTERY }.text)
        // the controller's 0x26: the BMS gone, kept while no 3A 17 says otherwise, and 5 s after the last 0x26
        motion(6000, code = 0x26, kmh = 12.5)
        assertEquals(TellTales.lamp(TellTales.BATTERY, LampLevel.RED, "Нет связи с BMS"), lamp(600_000, TellTales.BATTERY, streaming = false))
        motion(600_000, code = 0x26); motion(600_100)
        assertEquals("Нет связи с BMS", lamp(605_000, TellTales.BATTERY)?.text)
        assertNull(lamp(605_001, TellTales.BATTERY))
        // any other code: the link lamp while frames stream, held 5 s
        t = 700_000
        motion(t, code = 0x21)
        assertEquals(TellTales.lamp(TellTales.LINK, LampLevel.AMBER, "Код контроллера 0x21", 33.0), lamp(t, TellTales.LINK))
        motion(t + 300)
        assertEquals("Код контроллера 0x21", lamp(t + 5000, TellTales.LINK)?.text)
        assertNull(lamp(t + 5001, TellTales.LINK))
        assertNull(tt.lamps(t, null, emptyList(), Link.CONNECTED, false).firstOrNull { it.id == TellTales.LINK && it.text.startsWith("Код") })
        // over-voltage: warning AMBER, protection RED, the peak voltage; regen above 42.3 V without the flags
        t = 800_000
        bms(t, b5 = 0x08, v = 42.23, i = -1.4)
        assertEquals(TellTales.lamp(TellTales.OVERVOLT, LampLevel.AMBER, "Полная — тормози мягко", 42.23), lamp(t, TellTales.OVERVOLT))
        bms(t + 300, b5 = 0x04, v = 42.52, i = -1.9)
        assertEquals(TellTales.lamp(TellTales.OVERVOLT, LampLevel.RED, "Защита от перенапряжения", 42.52), lamp(t + 300, TellTales.OVERVOLT))
        bms(t + 600, v = 41.0, i = 1.3)
        assertEquals(LampLevel.RED, lamp(t + 5300, TellTales.OVERVOLT)?.level)
        assertNull(lamp(t + 5301, TellTales.OVERVOLT))
        val tv = TellTales()
        tv.onBms(0, BmsFrame(42.36, 0.5, false, 25, 25, 3800, 3936, 96), FastPower.Sample(-2.0, -84.72), 0.25, 30)
        assertEquals("Перенапряжение 42,4 В", tv.lamps(0, null, emptyList(), Link.CONNECTED, true).single { it.id == TellTales.OVERVOLT }.text)
        val tc = TellTales()
        tc.onBms(0, BmsFrame(42.35, -0.5, false, 25, 25, 3800, 3936, 96), FastPower.Sample(0.4, 16.9), 0.25, 30)
        assertTrue(tc.lamps(0, null, emptyList(), Link.CONNECTED, true).none { it.id == TellTales.OVERVOLT })   // not regen by the fast current
        // charger in, hot-charge latch, no current, still: «ждёт остывания» after 3 s; the current flowing is «Зарядка»
        t = 900_000
        for (k in 0..10) bms(t + k * 300L, b4 = 0x41, b6 = 0x04, i = 0.057, temp = 45)
        assertEquals(TellTales.lamp(TellTales.CHARGING, LampLevel.AMBER, "Зарядка ждёт остывания", 45.0), lamp(t + 3000, TellTales.CHARGING))
        // the dash answering again and again with no frame for 5 s: «Руль на связи, BMS молчит»
        val td = TellTales()
        td.onMotion(0, MotionFrame(0.0, 0.0, 3, 0.0, 1460.0))
        td.lamps(0, null, emptyList(), Link.CONNECTED, true)
        td.onDash(4000)
        assertEquals("Руль молчит", td.lamps(5000, null, emptyList(), Link.CONNECTED, false).single { it.id == TellTales.LINK }.text)
        td.onDash(6500)
        assertEquals("Руль на связи, BMS молчит", td.lamps(6500, null, emptyList(), Link.CONNECTED, false).single { it.id == TellTales.LINK }.text)
        assertEquals("Руль молчит", td.lamps(11_501, null, emptyList(), Link.CONNECTED, false).single { it.id == TellTales.LINK }.text)
        td.restartStreams()                                                       // a new connection: its boot lines alone are not it
        td.onDash(12_000); td.onDash(12_200)
        assertEquals("Руль молчит", td.lamps(13_000, null, emptyList(), Link.CONNECTED, false).single { it.id == TellTales.LINK }.text)
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
