package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialStateTest {
    private val nb = TellTales.NBSP
    private fun t(lamps: List<Lamp>, units: String = TellTales.KMH, kmh: Double = 20.0, mode: Int = 3) =
        Telemetry(link = Link.CONNECTED, streaming = true, units = units, speedKmh = kmh, mode = mode, voltage = 38.0, powerW = 300.0,
            powerFastW = 320.0, peakW = 640.0, batteryPctFine = 56.3, batteryPct = 56.0, lamps = lamps)

    private val hot = TellTales.lamp(TellTales.TEMP, LampLevel.AMBER, TellTales.T_HOT, 49.0)
    private val over = TellTales.lamp(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 56.0)
    private val svc = TellTales.lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0)

    @Test fun theRedBeatKeepsItsPhaseThroughANewTextAndFollowsTheRepeats() {
        // 20.10's battery: «Заряд на исходе» (amber), «Сейчас отключится» (red), «BMS отключает батарею» (red, a new text)
        fun bat(level: LampLevel, text: String) = TellTales.lamp(TellTales.BATTERY, level, text, 31.5)
        val b = DialBuilder()
        b.build(t(listOf(bat(LampLevel.AMBER, TellTales.T_LOW))), Health.LIVE, 1_000)
        val s1 = b.build(t(listOf(bat(LampLevel.RED, TellTales.T_CUTTING))), Health.LIVE, 5_300)
        assertEquals(5_300L, s1.lamps[TellTales.BATTERY]!!.levelSinceMs)
        val l = b.build(t(listOf(bat(LampLevel.RED, TellTales.T_BMS_CUT))), Health.LIVE, 9_000).lamps[TellTales.BATTERY]!!
        assertEquals(1_000L, l.sinceMs)
        assertEquals(9_000L, l.changedMs)
        assertEquals(5_300L, l.levelSinceMs)
        // no buzz: from when it turned red, not from when it lit amber
        assertEquals(5_300L, FaceKit.redSince(l, DialCues()))
        // its red buzz (the state built a frame after the motor) is the beat; an amber tap after it does not move it
        val red = DialCues(buzz = LampBuzz(5_280, LampLevel.RED), redAtMs = 5_280)
        assertEquals(5_280L, FaceKit.redSince(l, red))
        assertEquals(5_280L, FaceKit.redSince(l, red.copy(buzz = LampBuzz(11_000, LampLevel.AMBER))))
        // a repeat 30 s on re-phases it (a buzz alone, as the phone's previews give it, counts too)
        assertEquals(35_280L, FaceKit.redSince(l, DialCues(buzz = LampBuzz(35_280, LampLevel.RED))))
        // a red buzz of an earlier episode does not
        assertEquals(5_300L, FaceKit.redSince(l, DialCues(redAtMs = 1_000)))
        // the red fill dips no lower than 80 % of its cap at the bottom of the beat
        assertEquals(FaceKit.RED_FILL_MIN, FaceKit.redFill(FaceKit.PULSE_MIN), 1e-6f)
        assertEquals(1f, FaceKit.redFill(1f), 1e-6f)
        assertTrue(FaceKit.NIGHT_FILL * FaceKit.redFill(FaceKit.PULSE_MIN) > 0.65f)
    }

    @Test fun theLampsHistory() {
        val b = DialBuilder()
        val s0 = b.build(t(listOf(svc)), Health.LIVE, 1_000)
        assertEquals(1_000L, s0.lamps.changedMs)
        assertEquals(emptyList(), s0.lamps.before)
        assertNull(s0.lamps.top)                                                   // the service lamp: quiet
        val s1 = b.build(t(listOf(hot, svc)), Health.LIVE, 2_000)
        assertEquals(DialLamp(hot, 2_000), s1.lamps[TellTales.TEMP])
        assertEquals(TellTales.TEMP, s1.lamps.top?.id)
        assertEquals(2_000L, s1.lamps.changedMs)
        assertEquals(listOf(svc), s1.lamps.before)
        assertEquals(DialLamp(svc, 1_000), s1.lamps[TellTales.SERVICE])
        // only the value moves: no change
        val s2 = b.build(t(listOf(hot.copy(value = 49.4), svc)), Health.LIVE, 2_500)
        assertEquals(2_000L, s2.lamps[TellTales.TEMP]!!.changedMs)
        assertEquals(2_000L, s2.lamps.changedMs)
        // a stronger level: the same lamp since 2 s, changed now, what it was before
        val s3 = b.build(t(listOf(over, svc)), Health.LIVE, 3_000)
        val l = s3.lamps[TellTales.TEMP]!!
        assertEquals(2_000L, l.sinceMs); assertEquals(3_000L, l.changedMs); assertEquals(LampLevel.AMBER, l.before?.level)
        assertTrue(l.rose && s3.lamps.anyRed)
        assertEquals(3_000L, FaceKit.redSince(l, s3.cues))
        // out: in gone for its exit, then forgotten
        val s4 = b.build(t(listOf(svc)), Health.LIVE, 4_000)
        assertNull(s4.lamps[TellTales.TEMP])
        assertEquals(4_000L, s4.lamps.gone.single().changedMs)
        assertEquals(TellTales.TEMP, s4.lamps.gone.single().id)
        assertEquals(listOf(over, svc), s4.lamps.before)
        assertTrue(b.build(t(listOf(svc)), Health.LIVE, 4_000 + DialBuilder.GONE_MS + 1).lamps.gone.isEmpty())
        // lit again: new since
        assertEquals(6_000L, b.build(t(listOf(hot, svc)), Health.LIVE, 6_000).lamps[TellTales.TEMP]!!.sinceMs)
    }

    @Test fun anOlderPhonesLampsGetTheirFields() {
        val s = DialBuilder().build(t(listOf(Lamp(TellTales.BATTERY, LampLevel.RED, TellTales.T_CUTTING, 31.5))), Health.LIVE, 0)
        val l = s.lamps.lit.single().lamp
        assertEquals("Сейчас отключится", l.title)
        assertEquals("31,5${nb}В", l.valueText)
        assertEquals("под нагрузкой", l.detail)
        assertEquals(Glyphs.BY_ID.getValue(TellTales.BATTERY), FaceKit.icon(l))
        assertEquals(Glyphs.BATTERY_CUT, FaceKit.icon(l.copy(text = TellTales.T_BMS_CUT)))
        assertEquals(Glyphs.BATTERY_CUT, FaceKit.icon(l.copy(text = TellTales.T_NO_BMS)))
    }

    @Test fun speedPowerBattery() {
        val b = DialBuilder()
        val km = b.build(t(emptyList(), kmh = 25.0), Health.LIVE, 0)
        assertEquals(25.0, km.speed.value)
        assertEquals(25.0 / Fmt.MILE, km.speed.other!!, 1e-9)
        assertEquals("км/ч", km.speed.unit); assertEquals("mph", km.speed.otherUnit)
        assertEquals(30.0, km.speed.max)
        assertEquals(16 * Fmt.MILE, km.speed.limit!!, 1e-9)
        assertEquals(15.5 * Fmt.MILE, km.speed.tapAt!!, 1e-9)
        assertEquals(17.5 * Fmt.MILE, km.speed.rumbleAt!!, 1e-9)
        assertTrue(km.speed.atLimiter)                                             // 25 km/h ≥ 24,9
        assertEquals("FLY", km.speed.modeName)
        // mph, TAKEOFF: the points × 9 / 16; a cue off has none
        val mi = b.build(t(emptyList(), TellTales.MPH, kmh = 12.0, mode = 1), Health.LIVE, 0, tapMph = 16.0, rumbleMph = null)
        assertEquals(12.0 / Fmt.MILE, mi.speed.value!!, 1e-9)
        assertEquals(20.0, mi.speed.max)
        assertEquals(9.0, mi.speed.limit)
        assertEquals(9.0, mi.speed.tapAt)
        assertNull(mi.speed.rumbleAt)
        assertEquals(0.5f, mi.speed.fraction(10.0))
        // the power: the fast one while live, nothing otherwise
        assertEquals(320.0, km.power.watts)
        assertEquals(0.5f, km.power.fraction)
        assertEquals("320${nb}Вт${nb}тяга", km.power.text)
        val old = b.build(t(emptyList()), Health.SILENT, 0)
        assertNull(old.power.watts); assertEquals(0f, old.power.fraction)
        assertEquals("руль молчит", old.status)
        assertNull(km.status)
        assertEquals("56,3${Dial.NNBSP}%", km.battery.text)
        assertEquals(BatteryTone.GOOD, km.battery.tone)
        assertFalse(km.battery.charging)
        assertEquals("38,0${nb}В · FLY", km.details)
    }

    @Test fun theScenarios() {
        assertEquals(DialScenarios.KEYS, DialScenarios.ALL.map { it.key })
        for (sc in DialScenarios.ALL) {
            assertTrue(sc.states.zipWithNext().all { (a, b) -> a.nowMs < b.nowMs }, sc.key)
            assertEquals(0L, sc.states.first().nowMs, sc.key)
            assertTrue(sc.changeMs in 0..sc.lengthMs, sc.key)
            for (s in sc.states) for (l in s.lamps.lit) {
                assertTrue(l.lamp.title.isNotBlank() && l.lamp.title.length <= TellTales.TITLE_MAX, "${sc.key} ${l.lamp.title}")
                assertTrue(l.sinceMs <= s.nowMs && l.changedMs <= s.nowMs, sc.key)
            }
            assertEquals(sc.states.last(), sc.at(sc.lengthMs + 10_000))
        }
        val amber = DialScenarios["amber"].at(1_500)
        assertEquals(1_500L, amber.lamps[TellTales.TRACTION]!!.changedMs)
        assertEquals(TellTales.TEMP, amber.lamps.top?.id)                           // same level: the IDS order
        assertEquals(LampBuzz(300, LampLevel.AMBER), amber.cues.buzz)              // the second amber within 5 s: no buzz
        val crit = DialScenarios["critical"].at(1_600)
        assertEquals(LampBuzz(1_600, LampLevel.RED), crit.cues.buzz)
        assertEquals(TellTales.BATTERY, crit.lamps.top?.id)
        val many = DialScenarios["many"].at(4_300)
        assertEquals(6, many.lamps.lit.size)                                        // five came, the service lamp stands
        assertEquals(listOf(TellTales.CONSUMPTION, TellTales.OVERVOLT, TellTales.LINK, TellTales.TEMP), many.lamps.alerts.map { it.id })
        assertEquals(listOf(TellTales.WEAK, TellTales.SERVICE), many.lamps.quiet.map { it.id })
        val lim = DialScenarios["limiter"]
        assertEquals(lim.changeMs, lim.at(lim.changeMs).cues.tapAtMs)
        assertNull(lim.at(lim.changeMs - 10).cues.tapAtMs)
        val down = DialScenarios["overspeed"]
        assertTrue(down.at(down.changeMs).cues.rumble && !down.at(down.changeMs - 10).cues.rumble)
        assertNotNull(down.at(down.changeMs + 600).lamps[TellTales.OVERSPEED])
        assertEquals("mph", down.at(down.changeMs).speed.unit)
        val bulb = DialScenarios["bulb"]
        assertEquals(0f, bulb.at(bulb.changeMs).bulbProgress(bulb.changeMs))
        assertTrue(bulb.at(bulb.changeMs).bulbOn)
        assertFalse(bulb.at(bulb.changeMs + TellTales.BULB_CHECK_MS).bulbOn)
        assertTrue(DialScenarios["ambient"].states.all { it.ambient && it.burnInDp == BurnIn.offset(2) })
        val ch = DialScenarios["charging"].at(1_000)
        assertTrue(ch.battery.charging)
        assertEquals(Flow.CHARGE, ch.power.flow)
        assertTrue(DialScenarios.PREVIEW.all { it in DialScenarios.KEYS })
    }

    @Test fun motion() {
        assertEquals(1f, FaceKit.pulse(1_000, 1_000), 1e-6f)
        assertEquals(FaceKit.PULSE_MIN, FaceKit.pulse(1_000, 1_500), 1e-6f)
        assertEquals(1f, FaceKit.pulse(1_000, 2_000), 1e-6f)
        assertEquals(1f, FaceKit.pulse(1_000, 500))
        assertEquals(4f, FaceKit.bob(0, 225, 4f), 1e-4f)
        assertEquals(0f, FaceKit.bob(0, 1_000, 4f))
        assertEquals(4f, FaceKit.bob(0, 10_225, 4f), 1e-4f)
        assertNull(FaceKit.ripple(null, 0)); assertNull(FaceKit.ripple(100, 50)); assertNull(FaceKit.ripple(100, 800))
        assertEquals(0.5f, FaceKit.ripple(100, 400)!!, 1e-6f)
        assertEquals(1f, FaceKit.back(1f), 1e-6f); assertEquals(0f, FaceKit.back(0f), 1e-6f)
        assertTrue((1..99).map { FaceKit.back(it / 100f) }.any { it > 1f })            // it overshoots
        assertEquals(0.5f, FaceKit.ease(0.5f), 1e-6f)
        assertEquals(0f, FaceKit.pop(0))
        assertTrue(FaceKit.pop(150) > 1f)                                           // grow's pop overshoots once …
        assertEquals(1f, FaceKit.pop(1_000), 0.01f)                                 // … and settles
        assertEquals(1f, FaceKit.pop(600, zeta = 1f), 0.01f)
        assertEquals(0.5f, FaceKit.smoothstep(0f, 2f, 1f), 1e-6f)
    }

    @Test fun framesOnlyWhileSomethingMoves() {
        val b = DialBuilder()
        val calm = b.build(t(listOf(svc)), Health.LIVE, 0)
        assertEquals(0L, FaceKit.wakeAt(calm, 0))                                   // the service lamp's entrance
        assertNull(FaceKit.wakeAt(calm, FaceKit.APPEAR_MS))                          // then still: no redraw loop
        val red = b.build(t(listOf(over, svc)), Health.LIVE, 10_000)
        assertEquals(20_000L, FaceKit.wakeAt(red, 20_000))                          // the red pulse runs
        assertNull(FaceKit.wakeAt(red.copy(ambient = true), 20_000))                // ambient: static
        val rising = b.build(t(listOf(hot.copy(rising = true), svc)), Health.LIVE, 30_000)
        assertEquals(31_000L - 1_000 + FaceKit.BOB_EVERY_MS, FaceKit.wakeAt(rising, 31_000))   // asleep till the next ▲ hop
        assertEquals(40_100L, FaceKit.wakeAt(rising, 40_100))
        assertEquals(0L, FaceKit.wakeAt(calm.copy(cues = DialCues(tapAtMs = 0)), 0))
        assertNotNull(FaceKit.wakeAt(calm.copy(cues = DialCues(rumble = true)), 99_999))
    }

    @Test fun facesAndChoices() {
        assertEquals(DialFace.STACK, DialFace.DEFAULT)
        assertEquals(listOf("stack", "takeover", "grow", "ring", "classic"), DialFace.entries.map { it.key })
        assertEquals(listOf("Крупные лампы", "Захват экрана", "Растущие лампы", "Кольцо", "Классика"), DialFace.entries.map { it.title })
        for (f in DialFace.entries) { assertEquals(f, DialFace.of(f.key)); assertTrue(f.about.length in 40..120, f.about) }
        assertEquals(DialFace.STACK, DialFace.of("a-newer-face"))
        val p = FaceChoice(DialFace.RING, 2_000, FaceChoice.PHONE)
        val w = FaceChoice(DialFace.CLASSIC, 1_000, FaceChoice.WATCH)
        assertEquals(p, FaceChoice.newest(p, w)); assertEquals(p, FaceChoice.newest(w, p))
        assertEquals(w, FaceChoice.newest(null, w)); assertEquals(w, FaceChoice.newest(w, w.copy(face = DialFace.GROW)))
        assertNull(FaceChoice.of("", 1, "x"))
        assertEquals(FaceChoice(DialFace.GROW, 0, ""), FaceChoice.of("grow", null, null))
    }
}
