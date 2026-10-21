package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Крупные лампы» (FaceStack): its layout on every demo scenario, real-data corners, and the motion spec's numbers. */
class FaceStackTest {
    private val nb = TellTales.NBSP

    @Test fun everyScenarioLaysOutCleanly() {
        for (sc in DialScenarios.ALL) {
            val tl = StackTimeline()
            var i = 0
            var t = 0L
            while (t <= sc.lengthMs) {
                while (i < sc.states.size && sc.states[i].nowMs <= t) { tl.feed(sc.states[i]); i++ }
                val s = sc.at(t)
                val scene = StackLayout.scene(s, tl, t)
                if (StackLayout.settled(tl, s, t)) StackLayout.check(scene, s, "${sc.key} at $t ms")
                StackLayout.inside(scene, "${sc.key} at $t ms")
                t += 50
            }
        }
    }

    @Test fun theMainLampIsTopAndTheSmileTakesTheNext() {
        val many = DialScenarios["many"]
        val tl = StackLayout.fed(many, 7_000)
        val s = many.at(7_000)
        val scene = StackLayout.scene(s, tl, 7_000)
        val hero = scene.hero.filterIsInstance<ChipEl>().single()
        assertEquals(s.lamps.top!!.id, hero.lamp.id)
        assertEquals(TellTales.CONSUMPTION, hero.lamp.id)
        val smile = scene.sec.filterIsInstance<ChipEl>().map { it.lamp.id }.toSet()
        assertEquals(setOf(TellTales.OVERVOLT, TellTales.LINK), smile)
        assertEquals("+1", scene.sec.filterIsInstance<PlusEl>().single().text)
        assertEquals(LampLevel.YELLOW, scene.sec.filterIsInstance<PlusEl>().single().level)           // the hidden temperature
        // the quiet ones by the battery, as icons
        assertEquals(setOf(TellTales.SERVICE, TellTales.WEAK), scene.stand.filterIsInstance<ChipEl>().map { it.lamp.id }.toSet())
    }

    @Test fun theBloomLandsAsTheSpecSays() {
        // a red main lamp from its dot: 129 px at the overshoot peak (468 ms), within 1 px of 116 by 700
        val red = TellTales.lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_BMS_CUT, 32.6)
        val tl = StackTimeline()
        val b = DialBuilder()
        tl.feed(b.build(tele(emptyList()), Health.LIVE, 0))
        tl.feed(b.build(tele(listOf(red)), Health.LIVE, 1_000))
        fun d(ms: Long) = StackLayout.scene(b.build(tele(listOf(red)), Health.LIVE, 1_000 + ms), tl, 1_000 + ms).hero.filterIsInstance<ChipEl>().single { it.d > 20 }.d
        assertEquals(129f, d(468), 1f)
        assertEquals(116f, d(700), 1.2f)
        assertEquals(116f, d(1_500), 0.01f)
        // an amber one: 115 px; a yellow (info) one lands softer than an alarm: ≤ 2,8 % over
        val amber = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
        val yellow = TellTales.lamp(TellTales.TEMP, LampLevel.YELLOW, TellTales.T_WARM, 42.0)
        assertEquals(115f, StackLayout.heroAt(amber, 468).d, 1f)
        val peak = (0..700 step 4).maxOf { StackLayout.heroAt(yellow, it.toLong()).d }
        assertTrue(peak in 104f..107f, "info bloom peak $peak")
        // the bead flies along the arc for 280 ms first (at most 16 px)
        assertTrue(StackLayout.heroAt(amber, 200).d <= 16f)
        // the text is in by 660 ms (title 380, value 420, sub 460; 200 ms each)
        val s = StackLayout.sceneAt(amber, 660)
        assertTrue(s.text.filterIsInstance<TextEl>().all { it.alpha > 0.99f })
        assertTrue(StackLayout.sceneAt(amber, 379).text.isEmpty())
    }

    @Test fun ringsGoWithTheRealBuzzOnePerMotorPulse() {
        val red = TellTales.lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5)
        val b = DialBuilder()
        val tl = StackTimeline()
        tl.feed(b.build(tele(emptyList()), Health.LIVE, 0))
        tl.feed(b.build(tele(listOf(red)), Health.LIVE, 1_000))
        // the watch plays the red buzz LAND_MS after the message
        val cues = DialCues(buzz = LampBuzz(1_000 + Stack.LAND_MS, LampLevel.RED))
        val s = b.build(tele(listOf(red)), Health.LIVE, 1_470, cues = cues)
        tl.feed(s)
        fun rings(ms: Long) = StackLayout.scene(s, tl, 1_000 + ms).fx.filterIsInstance<RingEl>()
        assertEquals(0, rings(460).size)
        // a ring is born with each of the three motor pulses (0, 340, 680 ms) and lives 600 ms
        val born = (0..1_500 step 5).count { ms -> rings(470L + ms).any { it.r < 116f / 2 + 4 + 1.5f } && rings(470L + ms - 5).none { it.r < 116f / 2 + 4 + 1.5f } }
        assertEquals(3, born)
        assertEquals(1, rings(470 + 10).size)
        assertEquals(2, rings(470 + 350).size)
        assertEquals(0, rings(470 + 680 + 601).size)
        assertTrue(rings(470 + 599).first().alpha < 0.01f)
        // the breathing: full at the buzz, 55 % half a second later, the text never
        fun bright(ms: Long) = StackLayout.scene(s, tl, ms).hero.filterIsInstance<ChipEl>().single { it.d > 20 }.bright
        assertEquals(1f, bright(1_470 + 2_000), 0.001f)
        assertEquals(0.55f, bright(1_470 + 2_500), 0.001f)
        // calmer after 8 s: 78–100 %
        assertEquals(0.78f, bright(1_470 + 9_000 + 500 + 1_000), 0.005f)
        // an amber buzz: one ring
        val amber = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
        val b2 = DialBuilder()
        val tl2 = StackTimeline()
        tl2.feed(b2.build(tele(emptyList()), Health.LIVE, 0))
        tl2.feed(b2.build(tele(listOf(amber)), Health.LIVE, 1_000))
        val s2 = b2.build(tele(listOf(amber)), Health.LIVE, 1_470, cues = DialCues(buzz = LampBuzz(1_470, LampLevel.AMBER)))
        tl2.feed(s2)
        assertEquals(1, (0..1_500 step 10).maxOf { StackLayout.scene(s2, tl2, 1_000L + it).fx.filterIsInstance<RingEl>().size })
    }

    @Test fun aBuzzThatCameWithItsChangeIsDrawnAsTheChipLands() {
        // the phone's previews (DialScenarios) buzz at the change itself: the rings wait for the landing
        val bms = DialScenarios["bms"]
        val tl = StackLayout.fed(bms, 300)
        assertEquals(300L + Stack.LAND_MS, tl.buzzes.single().atMs)
        assertEquals(300L, tl.buzzes.single().realMs)
        assertEquals(TellTales.BATTERY, tl.buzzes.single().id)
        // started mid-ride (a shot, a theme switch): an older buzz is about the lamp it was for, not the newest change
        val amber = DialScenarios["amber"]
        val ta = StackTimeline().also { t -> amber.states.filter { it.nowMs >= amber.changeMs && it.nowMs <= 2_200 }.forEach(t::feed) }
        assertEquals(TellTales.TEMP, ta.buzzes.single().id)
        assertTrue((0..1_000 step 50).none { ms -> StackLayout.scene(amber.at(1_500L + ms), ta, 1_500L + ms).fx.any { it is RingEl } })
    }

    @Test fun aRedClearedLeavesAGreenCheck() {
        val bms = DialScenarios["bms"]
        val tl = StackLayout.fed(bms, bms.lengthMs)
        fun check(ms: Long) = StackLayout.scene(bms.at(5_300 + ms), tl, 5_300 + ms).fx.filterIsInstance<CheckEl>().singleOrNull()
        assertNull(check(50))
        assertNotNull(check(200))
        assertEquals(1f, check(300)!!.trim, 0.001f)
        assertNull(check(701))
        // the layout opens up only after the ✓ (80 + 450 ms)
        val b0 = StackLayout.scene(bms.at(5_300 + 500), tl, 5_300 + 500).battery.b
        assertEquals(StackGeo.BAT[2].w, b0.w, 0.01f)
        val b1 = StackLayout.scene(bms.at(5_300 + 1_000), tl, 5_300 + 1_000).battery.b
        assertEquals(StackGeo.BAT[0].w, b1.w, 0.01f)
    }

    @Test fun theQueueKeepsChangesApart() {
        val a = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
        val c = TellTales.lamp(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 24 Вт·ч/км", 24.0)
        val b = DialBuilder()
        val tl = StackTimeline()
        tl.feed(b.build(tele(emptyList()), Health.LIVE, 0))
        tl.feed(b.build(tele(listOf(a)), Health.LIVE, 1_000))
        tl.feed(b.build(tele(listOf(a, c)), Health.LIVE, 1_300))
        assertEquals(listOf(1_000L, 1_750L), tl.snaps.drop(1).map { it.t })
        // a third change before the queued one shows replaces it; back to what shows: dropped
        tl.feed(b.build(tele(listOf(a)), Health.LIVE, 1_400))
        assertEquals(listOf(1_000L), tl.snaps.drop(1).map { it.t })
        // values alone are not changes
        tl.feed(b.build(tele(listOf(a.copy(value = 50.0, valueText = "50${nb}°C"))), Health.LIVE, 1_500))
        assertEquals(1, tl.snaps.size - 1)
        assertEquals("50${nb}°C", tl.snaps.last().lit[TellTales.TEMP]!!.valueText)
    }

    @Test fun aValueRollsAfterAStillSecondOnly() {
        val a = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 48.0)
        val b = DialBuilder()
        val tl = StackTimeline()
        tl.feed(b.build(tele(emptyList()), Health.LIVE, 0))
        tl.feed(b.build(tele(listOf(a)), Health.LIVE, 1_000))
        val a2 = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
        val s = b.build(tele(listOf(a2)), Health.LIVE, 4_000)
        tl.feed(s)
        val v = StackLayout.scene(s, tl, 4_100).text.filterIsInstance<TextEl>().single { it.text.endsWith("°C") }
        assertEquals("48${nb}°C", v.roll?.from)
        assertEquals(1, v.roll?.dir)
        assertNull(StackLayout.scene(s, tl, 4_400).text.filterIsInstance<TextEl>().single { it.text.endsWith("°C") }.roll)
        // again 300 ms later: it snaps
        val s3 = b.build(tele(listOf(TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 50.0))), Health.LIVE, 4_300)
        tl.feed(s3)
        assertNull(StackLayout.scene(s3, tl, 4_350).text.filterIsInstance<TextEl>().single { it.text.endsWith("°C") }.roll)
        // the battery's tenths roll down
        val norm = DialScenarios["norm"]
        assertEquals("56,3${Dial.NNBSP}%", norm.at(0).battery.text)
    }

    @Test fun theRumbleFollowsTheMotor() {
        val b = DialBuilder()
        val tl = StackTimeline()
        fun at(ms: Long, on: Boolean, since: Long?) = b.build(tele(emptyList(), kmh = 29.0), Health.LIVE, ms, cues = DialCues(rumble = on, rumbleSinceMs = since))
        tl.feed(at(0, false, null))
        val s1 = at(1_000, true, 1_000)
        tl.feed(s1)
        // one wave per motor cycle (500 on / 100 off), the waveform restarting at each re-arm (2 s)
        assertEquals(1, StackLayout.scene(s1, tl, 1_100).track.waves.size)
        assertEquals(2, StackLayout.scene(s1, tl, 1_700).track.waves.size)
        assertTrue(StackLayout.scene(s1, tl, 1_550).track.blue)
        tl.feed(at(4_000, false, null))
        assertTrue(StackLayout.scene(at(4_000, false, null), tl, 4_100).track.waves.isNotEmpty())         // the live ones finish
        assertTrue(StackLayout.scene(at(4_000, false, null), tl, 5_000).track.waves.isEmpty())
        // a second rumble starts its own waveform
        val s2 = at(9_000, true, 9_000)
        tl.feed(s2)
        assertEquals(9_000L, tl.rumbleOn)
        assertEquals(1, StackLayout.scene(s2, tl, 9_050).track.waves.size)
    }

    @Test fun theMainValueStaysFreshAfterOtherChanges() {
        // the temperature lit, then the traction lamp came (a new set): the temperature's value goes on changing
        val b = DialBuilder()
        val tl = StackTimeline()
        val traction = TellTales.lamp(TellTales.TRACTION, LampLevel.AMBER, TellTales.T_LIMIT, 15.8)
        fun temp(v: Double) = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, v)
        tl.feed(b.build(tele(emptyList()), Health.LIVE, 0))
        tl.feed(b.build(tele(listOf(temp(48.0))), Health.LIVE, 1_000))
        tl.feed(b.build(tele(listOf(temp(48.0), traction)), Health.LIVE, 3_000))
        val s = b.build(tele(listOf(temp(49.0), traction.copy(value = 14.2, valueText = "14,2${nb}А"))), Health.LIVE, 6_000)
        tl.feed(s)
        val texts = StackLayout.scene(s, tl, 7_500).text.filterIsInstance<TextEl>().map { it.text }
        assertTrue("49${nb}°C" in texts, "$texts")
        // a value on one line with its detail («31,4 В под нагрузкой») rolls too
        fun cut(v: Double) = TellTales.lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, v)
        val b2 = DialBuilder()
        val t2 = StackTimeline()
        t2.feed(b2.build(tele(emptyList()), Health.LIVE, 0))
        t2.feed(b2.build(tele(listOf(cut(31.5))), Health.LIVE, 1_000))
        val s2 = b2.build(tele(listOf(cut(31.4))), Health.LIVE, 5_000)
        t2.feed(s2)
        val sc = StackLayout.scene(s2, t2, 5_100).text.filterIsInstance<TextEl>()
        val v = sc.single { it.text == "31,4${nb}В" }
        assertEquals("31,5${nb}В", v.roll?.from)
        assertEquals(-1, v.roll?.dir)
        assertEquals(v.baseline, sc.single { it.text == "под нагрузкой" }.baseline)       // inline here
    }

    @Test fun stillWhenNothingMoves() {
        val norm = DialScenarios["norm"]
        val tl = StackLayout.fed(norm, norm.lengthMs)
        val s = norm.at(norm.lengthMs)
        assertNull(tl.wakeAt(s, norm.lengthMs + 2_000, calm = false))          // service only, nothing moving: no frames
        assertNull(tl.wakeAt(s.copy(ambient = true), norm.lengthMs, calm = false))
        // a red lamp breathes: frames; calmer: none once the rings are gone
        val crit = DialScenarios["critical"]
        val tc = StackLayout.fed(crit, 4_000)
        assertEquals(4_000L, tc.wakeAt(crit.at(4_000), 4_000, calm = false))
        assertNull(tc.wakeAt(crit.at(4_000), 4_000, calm = true))
        // the ▲ wakes the face for its next hop only
        val warm = DialScenarios["warm"]
        val tw = StackLayout.fed(warm, 4_000)
        val next = tw.wakeAt(warm.at(4_000), 4_000, calm = false)
        assertEquals(300L + Stack.LAND_MS + FaceKit.BOB_EVERY_MS, next)
    }

    @Test fun realDataCorners() {
        // km/h, a long unknown lamp, no speed, no battery, the phone gone: it still lays out inside the circle
        val long = Lamp("future", LampLevel.AMBER, "Очень длинное предупреждение без пробелов", 12.3)
        val lamps = listOf(
            TellTales.lamp(TellTales.OVERVOLT, LampLevel.RED, TellTales.T_OV_CUT, 43.5),
            long,
            TellTales.lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_DASH_NO_BMS),
            TellTales.lamp(TellTales.COLD, LampLevel.BLUE, TellTales.T_COLD, -3.0),
            TellTales.lamp(TellTales.CHARGING, LampLevel.AMBER, TellTales.T_CHARGE_WAIT, 46.0),
            TellTales.lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 120.0),
            TellTales.lamp(TellTales.WEAK, LampLevel.YELLOW, "Батарея устала: 0,38 Ом", 0.38),
            TellTales.lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0),
        )
        for (h in Health.entries) for (units in listOf(TellTales.KMH, TellTales.MPH)) {
            val t = Telemetry(
                link = Link.CONNECTED, streaming = h == Health.LIVE, units = units, speedKmh = if (h == Health.LIVE) 27.0 else null,
                mode = 3, voltage = null, lamps = lamps.map { TellTales.display(it, units) },
                range = RangeEstimate(123.4, 76.7, 0.0, 12.0, "default"),
            )
            val b = DialBuilder()
            val tl = StackTimeline()
            val s = b.build(t, h, 10_000)
            tl.feed(s)
            for (ms in longArrayOf(10_000, 10_300, 10_700, 12_000)) {
                val sc = StackLayout.scene(s, tl, ms)
                StackLayout.inside(sc, "$h $units $ms")
                if (ms == 12_000L) StackLayout.check(sc, s, "$h $units")
                assertEquals(if (h == Health.LIVE) 0 else s.status!!.split('\n').size, sc.status.size)
                if (h != Health.LIVE) assertEquals(h.alpha, sc.values)
            }
        }
    }

    @Test fun theStartup() {
        val bulb = DialScenarios["bulb"]
        val tl = StackLayout.fed(bulb, 200)
        fun at(ms: Long) = StackLayout.scene(bulb.at(200 + ms), tl, 200 + ms)
        assertEquals(0f, at(0).face, 0.001f)                                     // from black
        assertEquals(1f, at(200).face, 0.001f)
        assertTrue(at(500).sweep && at(800).wave && !at(1_800).wave)            // the watch's own: not dimmed by old data
        assertTrue(at(500).powerDeg > 100f)                                      // the arc sweeps up …
        assertEquals(0f, at(1_600).powerDeg, 0.5f)                               // … and back
        assertEquals(12, at(1_000).hero.filterIsInstance<ChipEl>().size)        // all 12 around the battery
        assertEquals(0, at(1_800).hero.filterIsInstance<ChipEl>().size)         // home
        assertEquals("проверка ламп", at(800).lines.single { it.text == Dial.BULB_TEXT }.text)
        // the lamps' dots: dim in their bulb colours, then their real state
        assertEquals(0.35f, at(800).dots[0].alpha, 0.001f)
        assertEquals(Palette.LAMP_OFF, at(2_300).dots[0].argb)
        // a reconnection riding: the wave only (the arc and the dot keep the data)
        val b = DialBuilder()
        val tr = StackTimeline()
        val s = b.build(tele(emptyList(), kmh = 20.0), Health.LIVE, 5_000, bulbAtMs = 5_000, bulbOn = true)
        tr.feed(s)
        val mid = StackLayout.scene(s, tr, 5_500)
        assertEquals(1f, mid.face)
        assertEquals(s.power.fraction * 135f, mid.powerDeg, 0.01f)
        assertEquals(12, StackLayout.scene(s, tr, 6_000).hero.size)
    }

    @Test fun ambientIsStillOutlinesOnly() {
        val amb = DialScenarios["ambient"]
        val tl = StackLayout.fed(amb, amb.changeMs)
        val s = amb.at(amb.changeMs)
        val a = StackLayout.scene(s, tl, amb.changeMs)
        val b = StackLayout.scene(s, tl, amb.changeMs + 333)
        assertEquals(a.hero.size, b.hero.size)
        assertTrue(a.fx.isEmpty() && a.track.waves.isEmpty())
        assertEquals(a.hero.filterIsInstance<ChipEl>().map { it.d }, b.hero.filterIsInstance<ChipEl>().map { it.d })
        // the newest set at once: the second amber is in the smile already at its change
        assertEquals(TellTales.TRACTION, a.sec.filterIsInstance<ChipEl>().single().lamp.id)
        assertNull(tl.wakeAt(s, amb.changeMs, calm = false))
    }

    @Test fun theCalmerVariantHasNoFlight() {
        val bms = DialScenarios["bms"]
        val tl = StackLayout.fed(bms, 300)
        val s = bms.at(300)
        val sc = StackLayout.scene(s, tl, 400, calm = true)
        assertTrue(sc.hero.filterIsInstance<ChipEl>().all { abs(it.d - StackGeo.HERO_RED.d) < 0.01f && it.x == StackGeo.HERO_RED.x })
        assertTrue(sc.hero.filterIsInstance<DiscEl>().isEmpty())
        // the buzz count as still rings
        val rings = StackLayout.scene(s, tl, 300 + Stack.LAND_MS + 700, calm = true).fx.filterIsInstance<RingEl>()
        assertEquals(3, rings.size)
        assertEquals(1f, StackLayout.scene(s, tl, 3_000, calm = true).hero.filterIsInstance<ChipEl>().single().bright)
    }

    private fun tele(lamps: List<Lamp>, kmh: Double = 20.0) = Telemetry(
        link = Link.CONNECTED, streaming = true, units = TellTales.MPH, speedKmh = kmh, mode = 3, voltage = 38.0, powerW = 300.0,
        powerFastW = 320.0, peakW = 640.0, batteryPctFine = 56.3, batteryPct = 56.0, lamps = lamps, temp1C = 30.0, temp2C = 31.0,
        range = RangeEstimate(5.6, 3.5, 0.0, 12.0, "recent"),
    )
}

/** The stack's test helpers over [StackCheck]. */
object StackLayout {
    fun scene(s: DialState, tl: StackTimeline, t: Long, calm: Boolean = false): StackScene = StackCheck.scene(s, tl, t, calm)

    /** [sc]'s timeline fed up to [ms]. */
    fun fed(sc: DialScenario, ms: Long): StackTimeline = StackTimeline().also { tl -> sc.states.filter { it.nowMs <= ms }.forEach(tl::feed) }

    /** [l] alone, [ms] after it lit, from nothing (the main chip, or the bead). */
    fun sceneAt(l: Lamp, ms: Long): StackScene {
        val b = DialBuilder()
        val tl = StackTimeline()
        fun t(ls: List<Lamp>) = Telemetry(link = Link.CONNECTED, streaming = true, units = TellTales.MPH, speedKmh = 20.0, mode = 3, lamps = ls)
        tl.feed(b.build(t(emptyList()), Health.LIVE, 0))
        val s = b.build(t(listOf(l)), Health.LIVE, 1_000)
        tl.feed(s)
        return scene(s, tl, 1_000 + ms)
    }

    fun heroAt(l: Lamp, ms: Long): ChipEl = sceneAt(l, ms).hero.filterIsInstance<ChipEl>().maxBy { it.d }

    fun settled(tl: StackTimeline, @Suppress("UNUSED_PARAMETER") s: DialState, t: Long): Boolean = StackCheck.settled(tl, t)

    fun inside(sc: StackScene, where: String) = StackCheck.outside(sc).let { assertTrue(it.isEmpty(), "$where: $it") }

    fun check(sc: StackScene, s: DialState, where: String) = StackCheck.overlaps(sc, s).let { assertTrue(it.isEmpty(), "$where: $it") }
}
