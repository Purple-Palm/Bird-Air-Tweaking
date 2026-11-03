package io.github.purplepalm.birdair.dial

import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import org.junit.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Растущие лампы» in motion ([GrowScene], [GrowMotion]): the demo scenarios frame by frame, the pop, the red pulse, the bulb check, idle. */
class FaceGrowMotionTest {
    /** A rough Roboto (FaceGrowLayoutTest checks it against the mock's browser). */
    private val model = GrowMeasure { text, px, weight -> text.length * 0.53 * px * (1 + (weight - 400) / 3000.0) }

    /** Every sprite and row inside the round screen, every number finite. */
    private fun check(f: GrowFrame, tag: String) {
        for (s in f.sprites) {
            for (v in listOf(s.x, s.y, s.d, s.q)) assertTrue(v.isFinite(), "$tag ${s.id}")
            if (s.lit && !s.hide) assertTrue(hypot(s.x - 240f, s.y - 240f) + s.d / 2 <= 240f, "$tag ${s.id} off the screen: ${s.x},${s.y} ${s.d}")
            s.plate?.let { assertTrue(s.plateText != null, "$tag ${s.id} plate text") }
        }
        for (r in listOfNotNull(f.clock, f.bat, f.range, f.det, f.msg, f.pow)) {
            assertTrue(r.cx.isFinite() && r.y.isFinite() && r.s in 0.5f..1.2f && r.a in -0.01f..1.01f, "$tag row ${r.cx} ${r.s} ${r.a}")
            assertTrue(hypot(r.cx - 240f, r.y - 240f) < 200f, "$tag row at ${r.cx},${r.y}")
        }
        f.speed?.let { assertTrue(it in 0f..1f, tag) }
        assertTrue(f.power in -1f..1f, tag)
    }

    @Test fun everyScenarioFrameByFrame() {
        for (sc in DialScenarios.ALL) {
            val scene = GrowScene(model)
            var t = 0L
            while (t <= sc.lengthMs) {
                val s = sc.at(t)
                check(scene.frame(s, t, s.speed.fraction(), s.power.fraction), "${sc.key} $t")
                t += 25
            }
        }
    }

    /** The pop: one overshoot, settled by 700 ms; a ring for 600 ms; the main lamp ends on its badge. */
    @Test fun thePop() {
        val sc = DialScenarios["warm"]
        val scene = GrowScene(model)
        fun temp(ms: Long) = sc.at(sc.changeMs + ms).let { s -> scene.frame(s, sc.changeMs + ms, 0.7f, 0.4f).sprites.first { it.id == TellTales.TEMP } }
        assertEquals(GrowMode.LOUD, temp(0).mode)
        assertTrue(temp(0).d < 9f)                                                    // from its dot …
        assertTrue(temp(150).d > temp(150).dTarget, "${temp(150).d}")                 // … over its size once …
        assertEquals(temp(700).dTarget, temp(700).d, 0.01f)                           // … and settled
        assertEquals(104f, temp(1200).d, 0.01f)
        assertNotNull(temp(300).pop); assertNull(temp(600).pop)
        assertNotNull(temp(1200).plateText)
        assertEquals("42${TellTales.NBSP}°C", temp(1200).plateText)
        // the ▲'s second one rises when it lit and every 10 s
        assertNotNull(temp(600).ghost); assertNull(temp(1300).ghost)
    }

    /** Red: 1 Hz from its vibration, bright on the beat (55–100 %), the ripple from the second half; a text change is no new pop. */
    @Test fun theRedPulse() {
        val sc = DialScenarios["critical"]
        val scene = GrowScene(model)
        val at = sc.changeMs
        fun bat(ms: Long) = sc.at(at + ms).let { s -> scene.frame(s, at + ms, 0.5f, 0.5f).sprites.first { it.id == TellTales.BATTERY } }
        assertEquals(LampBuzz(at, LampLevel.RED), sc.at(at).cues.buzz)
        assertEquals(1f, bat(0).pulse!!, 1e-4f)
        assertEquals(0f, bat(500).pulse!!, 1e-4f)
        assertEquals(1f, bat(3000).pulse!!, 1e-4f)
        assertNull(bat(300).ripple); assertNotNull(bat(1200).ripple)
        assertEquals(126f, bat(2000).d, 0.01f)
        assertEquals(GrowLayout.HOME.getValue(TellTales.BATTERY).toFloat(), FaceKit.clockDeg(bat(2000).x, bat(2000).y), 0.01f)
        // a new value (the voltage) is not a new lamp: no pop, the same place
        val s = sc.at(at + 2000)
        val l = s.lamps[TellTales.BATTERY]!!
        val moved = s.copy(lamps = s.lamps.copy(lit = s.lamps.lit.map { if (it.id == l.id) DialLamp(it.lamp.copy(value = 31.3, valueText = "31,3${TellTales.NBSP}В"), it.sinceMs, at + 1900, it.lamp) else it }))
        val f = scene.frame(moved, at + 2000, 0.5f, 0.5f).sprites.first { it.id == TellTales.BATTERY }
        assertNull(f.pop)
        assertEquals("31,3${TellTales.NBSP}В", f.plateText)
        assertEquals(bat(2000).x, f.x, 0.01f)
    }

    /** The bulb check: a wave from the top down, 44 px discs at r 158, «проверка ламп», the speed and power swept; then back. */
    @Test fun theBulbCheck() {
        val sc = DialScenarios["bulb"]
        val b = sc.changeMs
        val scene = GrowScene(model)
        fun f(ms: Long) = sc.at(b + ms).let { scene.frame(it, b + ms, 0f, 0f) }
        assertTrue(f(30).sprites.filter { it.mode == GrowMode.CHECK }.map { it.id }.toSet() == setOf(TellTales.TEMP, TellTales.ECO))
        assertEquals(12, f(400).sprites.count { it.mode == GrowMode.CHECK })
        assertEquals(44f, f(1000).sprites.first { it.id == TellTales.COLD }.d, 0.5f)
        assertEquals(Dial.BULB_TEXT, f(400).msgText?.text)
        assertEquals(1f, f(700).speed!!, 1e-4f); assertEquals("20", f(700).speedLabel)
        assertEquals(1f, f(700).power, 1e-4f)
        assertTrue(f(1100).sprites.first { it.id == TellTales.TEMP }.mode != GrowMode.CHECK)   // the top pair out first …
        assertEquals(GrowMode.CHECK, f(1100).sprites.first { it.id == TellTales.COLD }.mode)   // … the bottom one still on
        val after = f(GrowMotion.BULB_TAIL_MS + 100)
        assertEquals(0, after.sprites.count { it.mode == GrowMode.CHECK })
        assertTrue(after.sprites.filter { it.mode == GrowMode.OFF }.all { it.d == GrowLayout.DOT_D.toFloat() })
        assertNull(after.msg?.takeIf { it.a > 0.01f })
    }

    /** The text line waits for the power to reach its new place, then fades in. */
    @Test fun theLineWaitsForThePower() {
        val sc = DialScenarios["warm"]
        val scene = GrowScene(model)
        fun f(ms: Long) = sc.at(sc.changeMs + ms).let { scene.frame(it, sc.changeMs + ms, 0.7f, 0.4f) }
        assertEquals(330f, f(0).pow.y, 0.01f)
        assertTrue(f(100).msg!!.a < 0.05f)
        assertEquals(352f, f(800).pow.y, 0.01f)
        assertEquals(1f, f(800).msg!!.a, 0.01f)
        assertEquals(TellTales.T_WARM_RISING, f(800).msgText?.text)
    }

    /** Frames only while something moves: idle none, ambient none, red and the rumble all along, a ▲ every 10 s. */
    @Test fun framesOnlyWhileSomethingMoves() {
        val norm = DialScenarios["norm"]
        val calm = norm.at(norm.lengthMs)
        assertNull(GrowMotion.wakeAt(calm, norm.lengthMs + GrowMotion.SETTLE_MS))
        assertEquals(norm.changeMs, GrowMotion.wakeAt(norm.at(norm.changeMs), norm.changeMs))
        val crit = DialScenarios["critical"].let { it.at(it.changeMs + 3_000) }
        assertEquals(99_000L, GrowMotion.wakeAt(crit, 99_000))
        assertNull(GrowMotion.wakeAt(crit.copy(ambient = true), 99_000))
        val down = DialScenarios["overspeed"].let { it.at(it.changeMs + 500) }
        assertEquals(12_345L, GrowMotion.wakeAt(down, 12_345))
        val warm = DialScenarios["warm"]
        val w = warm.at(warm.changeMs + 3_000)
        val since = w.lamps[TellTales.TEMP]!!.sinceMs
        assertEquals(since + GrowMotion.GHOST_EVERY_MS, GrowMotion.wakeAt(w, since + 3_000))
        val ch = DialScenarios["charging"].let { it.at(it.changeMs + 3_000) }
        assertNotNull(GrowMotion.wakeAt(ch, ch.nowMs + 2_000))
    }

    /** Ambient: the settled layout, outlines, nothing moves (a frame is the same at any time). */
    @Test fun ambientIsStill() {
        val sc = DialScenarios["ambient"]
        val scene = GrowScene(model)
        val s = sc.at(sc.changeMs)
        val a = scene.frame(s, sc.changeMs, 0.6f, 0.3f)
        val b = scene.frame(s, sc.changeMs + 5_555, 0.6f, 0.3f)
        for ((x, y) in a.sprites.zip(b.sprites)) {
            assertEquals(x.x, y.x); assertEquals(x.d, y.d); assertNull(x.pop); assertNull(x.pulse); assertNull(x.ghost)
        }
        assertEquals(a.sprites.first { it.id == TellTales.TRACTION }.d, a.sprites.first { it.id == TellTales.TRACTION }.dTarget)
        assertNull(a.tapS); assertTrue(!a.rumble)
    }

    /** Old data: the status in the clock's place (two lines for «нет данных / с телефона»), no clock; km/h; nothing at all. */
    @Test fun healthUnitsAndNothing() {
        val b = DialBuilder()
        val lamp = TellTales.lamp(TellTales.TEMP, LampLevel.RED, TellTales.T_OVERHEAT, 57.0)
        val t = Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = 26.0, mode = 3, voltage = 36.0, powerFastW = 420.0, peakW = 700.0, batteryPctFine = 33.3, lamps = listOf(lamp))
        val scene = GrowScene(model)
        val gone = b.build(t, Health.NO_PHONE, 10_000, clock = "21:10")
        val f = scene.frame(gone, 10_000, gone.speed.fraction(), gone.power.fraction)
        assertEquals(listOf("нет данных", "с телефона"), f.status.map { it.second })
        assertNull(f.clock)
        assertEquals(0f, f.power)                                                    // old power is no power
        assertTrue(!f.atLimit && !f.rumble)
        val kmh = b.build(t, Health.LIVE, 11_000, clock = "21:10")
        val k = scene.frame(kmh, 11_000, kmh.speed.fraction(), kmh.power.fraction)
        assertEquals("26", k.speedLabel)
        assertTrue(k.atLimit)                                                        // 26 km/h ≥ 24,9
        assertEquals("57${TellTales.NBSP}°C", k.sprites.first { it.id == TellTales.TEMP }.plateText)
        val none = DialBuilder().build(Telemetry(), Health.NO_PHONE, 0)
        val n = scene.frame(none, 0, none.speed.fraction(), none.power.fraction)
        check(n, "empty")
        assertNull(n.speed); assertNull(n.range); assertNull(n.det); assertNull(n.msgText)
        assertTrue(n.sprites.all { it.mode == GrowMode.OFF })
    }

    /** A lamp of a newer phone (an id this build does not know) is left out, the rest drawn. */
    @Test fun anUnknownLamp() {
        val s = DialBuilder().build(
            Telemetry(link = Link.CONNECTED, streaming = true, lamps = listOf(Lamp("brakes", LampLevel.RED, "Тормоза"), TellTales.lamp(TellTales.LINK, LampLevel.AMBER, TellTales.T_LINK_LOST))),
            Health.LIVE, 0,
        )
        val f = GrowScene(model).frame(s, 5_000, null, 0f)
        assertEquals(12, f.sprites.size)
        assertEquals(GrowMode.LOUD, f.sprites.first { it.id == TellTales.LINK }.mode)
    }
}
