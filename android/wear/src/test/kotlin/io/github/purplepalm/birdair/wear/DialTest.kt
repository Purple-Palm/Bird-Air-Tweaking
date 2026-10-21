package io.github.purplepalm.birdair.wear

import androidx.compose.ui.graphics.vector.PathParser
import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val NB = " "
private const val NNB = " "

class DialGeometryTest {
    @Test fun theMockHalved() {
        // mock3.html draws on 480 px: radii 222 / 195 / 165, the battery at (162, 160) 140×66
        assertEquals(222f, 2 * Dial.POWER_R)
        assertEquals(195f, 2 * Dial.SPEED_R)
        assertEquals(165f, 2 * Dial.LAMP_R)
        assertEquals(162f to 160f, 2 * Dial.BODY_X to 2 * Dial.BODY_Y)
        assertEquals(140f to 66f, 2 * Dial.BODY_W to 2 * Dial.BODY_H)
        assertEquals(240f, 2 * Dial.C)
        // text baselines 262 / 290 / 326 / 352 (330 without a lamp line) / 122
        assertEquals(listOf(262f, 290f, 326f, 352f, 330f, 122f),
            listOf(Dial.RANGE_Y, Dial.DETAILS_Y, Dial.MSG_Y, Dial.POWER_Y, Dial.POWER_ALONE_Y, Dial.CLOCK_Y).map { 2 * it })
    }

    @Test fun clockAngles() {
        assertEquals(120f to 20f, Dial.point(100f, 0f))
        val (x, y) = Dial.point(100f, 90f)
        assertEquals(220f, x, 1e-4f)
        assertEquals(120f, y, 1e-4f)
        assertEquals(-90f, Dial.composeDeg(0f))
        assertEquals(-225f, Dial.composeDeg(Dial.START_DEG))
        assertEquals(Arc.track.first, Dial.composeDeg(Dial.START_DEG))
        assertEquals(Arc.track.second, Dial.SPAN_DEG)
    }

    @Test fun lampsInTwoColumns() {
        // right column top to bottom: temp, battery, traction, consumption, weak, overvolt; left: eco … cold
        assertEquals(listOf(40f, 60f, 80f, 100f, 120f, 140f), (0..5).map { Dial.lampDeg(it) })
        assertEquals(listOf(-40f, -60f, -80f, -100f, -120f, -140f), (6..11).map { Dial.lampDeg(it) })
        assertEquals(listOf("temp", "battery", "traction", "consumption", "weak", "overvolt"), TellTales.IDS.take(6))
        assertEquals(listOf("eco", "service", "link", "overspeed", "charging", "cold"), TellTales.IDS.drop(6))
        val (x0, y0) = Dial.lampCentre(0)
        assertEquals(120 + 82.5 * sin(Math.toRadians(40.0)), x0.toDouble(), 1e-3)
        assertEquals(120 - 82.5 * cos(Math.toRadians(40.0)), y0.toDouble(), 1e-3)
        // mirrored: the left column is the right one flipped
        for (k in 0..5) {
            val (xr, yr) = Dial.lampCentre(k)
            val (xl, yl) = Dial.lampCentre(k + 6)
            assertEquals(240f - xr, xl, 1e-3f)
            assertEquals(yr, yl, 1e-3f)
        }
        // neighbours do not touch, the glow stays inside the speed track's ticks
        for (k in 0..4) {
            val (a, b) = Dial.lampCentre(k)
            val (c, d) = Dial.lampCentre(k + 1)
            assertTrue(hypot(a - c, b - d) > Dial.ICON + 10)
        }
        assertTrue(Dial.LAMP_R + Dial.GLOW_R < Dial.SPEED_R - Dial.LIMIT_HALF)
        // the clock and the % line stay clear of the top lamps
        val top = Dial.lampCentre(0).second
        assertTrue(Dial.BODY_Y > top + Dial.ICON / 2)
    }

    @Test fun speedTrackInTheUnits() {
        assertEquals(30.0, Dial.speedMax("kmh"), 0.0)
        assertEquals(20.0, Dial.speedMax("mph"), 0.0)
        assertEquals(0.5f, Dial.speedFraction(15.0, "kmh")!!, 1e-6f)
        assertEquals(1f, Dial.speedFraction(45.0, "kmh")!!, 0f)
        assertEquals(0f, Dial.speedFraction(-2.0, "kmh")!!, 0f)
        assertNull(Dial.speedFraction(null, "kmh"))
        assertNull(Dial.speedFraction(Double.NaN, "kmh"))
        // 16 mph (25.75 km/h) is 0.8 of the 20 mph track
        assertEquals(0.8f, Dial.speedFraction(16 * Fmt.MILE, "mph")!!, 1e-6f)
        assertEquals(-135f, Dial.speedDeg(0f))
        assertEquals(0f, Dial.speedDeg(0.5f))
        assertEquals(135f, Dial.speedDeg(1f))
        assertEquals(135f, Dial.speedDeg(3f))
        assertEquals("14", Dial.dotLabel(14.4, "kmh"))
        assertEquals("16", Dial.dotLabel(16 * Fmt.MILE, "mph"))
        assertEquals("0", Dial.dotLabel(-0.3, "kmh"))
        assertNull(Dial.dotLabel(null, "kmh"))
    }

    @Test fun ticksAndTheModeLimit() {
        assertEquals(listOf(0f, 1 / 6f, 2 / 6f, 3 / 6f, 4 / 6f, 5 / 6f, 1f), Dial.ticks("kmh"))
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), Dial.ticks("mph"))
        // TAKEOFF 9 / GLIDE 12,5 / FLY 16 mph
        assertEquals(0.45f, Dial.limitFraction(1, "mph")!!, 1e-6f)
        assertEquals(0.625f, Dial.limitFraction(2, "mph")!!, 1e-6f)
        assertEquals(0.8f, Dial.limitFraction(3, "mph")!!, 1e-6f)
        assertEquals((16 * 1.609344 / 30).toFloat(), Dial.limitFraction(3, "kmh")!!, 1e-6f)
        assertNull(Dial.limitFraction(null, "kmh"))
        assertNull(Dial.limitFraction(7, "kmh"))
    }
}

class DialTextTest {
    @Test fun fineBatteryPercent() {
        assertEquals("56,3${NNB}%", Dial.pctText(Telemetry(batteryPct = 56.0, batteryPctFine = 56.3)))
        // floored like the BMS floors, never rounded up
        assertEquals("56,2${NNB}%", Dial.pctText(Telemetry(batteryPctFine = 56.29)))
        assertEquals("100${NNB}%", Dial.pctText(Telemetry(batteryPctFine = 100.0)))
        assertEquals("0,0${NNB}%", Dial.pctText(Telemetry(batteryPctFine = -1.0)))
        // fallbacks: the whole BMS %, the voltage estimate, nothing
        assertEquals("54${NNB}%", Dial.pctText(Telemetry(batteryPct = 54.0)))
        assertEquals("≈50${NNB}%", Dial.pctText(Telemetry(voltage = 38.0)))
        assertEquals("—${NNB}%", Dial.pctText(Telemetry()))
        assertEquals(56.3, Dial.pct(Telemetry(batteryPct = 56.0, batteryPctFine = 56.3))!!, 0.0)
        assertEquals(54.0, Dial.pct(Telemetry(batteryPct = 54.0))!!, 0.0)
    }

    @Test fun batteryFill() {
        assertEquals(62f, Dial.fillWidth(100.0))
        assertEquals(31f, Dial.fillWidth(50.0), 1e-5f)
        assertEquals(3f, Dial.fillWidth(0.0))
        assertEquals(3f, Dial.fillWidth(1.0))
        assertEquals(0f, Dial.fillWidth(null))
        assertEquals(62f, Dial.fillWidth(140.0))
        // the % thresholds (page), no milder than the battery lamp
        assertEquals(BatteryTone.GOOD, Dial.batteryTone(Telemetry(batteryPctFine = 86.4), emptyList()))
        assertEquals(BatteryTone.WARN, Dial.batteryTone(Telemetry(batteryPctFine = 20.0), emptyList()))
        assertEquals(BatteryTone.CRIT, Dial.batteryTone(Telemetry(batteryPctFine = 12.0), emptyList()))
        val red = listOf(Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится"))
        val amber = listOf(Lamp(TellTales.BATTERY, LampLevel.AMBER, "Заряд на исходе"))
        assertEquals(BatteryTone.CRIT, Dial.batteryTone(Telemetry(batteryPctFine = 41.0), red))
        assertEquals(BatteryTone.WARN, Dial.batteryTone(Telemetry(batteryPctFine = 41.0), amber))
        assertEquals(BatteryTone.CRIT, Dial.batteryTone(Telemetry(batteryPctFine = 9.0), amber))
        // the mock's colours
        assertEquals(0xFF2ECC40, Dial.toneArgb(BatteryTone.GOOD))
        assertEquals(0xFFFFB020, Dial.toneArgb(BatteryTone.WARN))
        assertEquals(0xFFFF3B30, Dial.toneArgb(BatteryTone.CRIT))
    }

    @Test fun rangeDetailsPower() {
        val r = RangeEstimate(4.24, 4.24 / 1.609344, 60.0, 15.0, "recent")
        assertEquals("≈${NB}4,2${NB}км", Dial.rangeText(r, "kmh"))
        assertEquals("≈${NB}2,6${NB}mi", Dial.rangeText(r, "mph"))
        assertEquals("≈${NB}26${NB}км", Dial.rangeText(r.copy(km = 26.3), "kmh"))
        assertNull(Dial.rangeText(null, "kmh"))
        assertNull(Dial.rangeText(r.copy(km = Double.NaN), "kmh"))
        assertEquals("40,2${NB}В · 31${NB}°C · FLY", Dial.detailsText(Samples.normal))
        // the hotter sensor, what is missing is left out
        assertEquals("56${NB}°C", Dial.detailsText(Telemetry(temp1C = 52.0, temp2C = 56.0)))
        assertEquals("", Dial.detailsText(Telemetry()))
        assertEquals("180${NB}Вт${NB}тяга", Dial.powerText(180.0, Flow.DRIVE))
        assertEquals("150${NB}Вт${NB}рекуп.", Dial.powerText(-150.0, Flow.REGEN))
        assertEquals("5${NB}Вт", Dial.powerText(4.9, Flow.IDLE))
        assertEquals("—${NB}Вт", Dial.powerText(null, Flow.NONE))
        assertEquals(Palette.DRAW, Dial.powerArgb(180.0))
        assertEquals(Palette.FLOW, Dial.powerArgb(-150.0))
        assertEquals(Palette.MUTED, Dial.powerArgb(0.0))
        assertEquals(Palette.MUTED, Dial.powerArgb(null))
    }

    @Test fun theLampLine() {
        // «Норма»: ECO and «Сервис» are quiet, no line (the power moves up)
        assertNull(Dial.message(Samples.normal.lamps, bulb = false))
        assertEquals(Dial.Message("Горячая, быстро греется", Palette.LAMP_AMBER), Dial.message(Samples.attention.lamps, false))
        assertEquals(Dial.Message("Перегрев — сбавь газ", Palette.LAMP_RED), Dial.message(Samples.critical.lamps, false))
        assertEquals(Dial.Message("проверка ламп", Palette.MUTED), Dial.message(Samples.critical.lamps, true))
        assertEquals("Быстрее ограничителя", Dial.message(Samples.overspeed.lamps, false)!!.text)
    }
}

class LampsTest {
    @Test fun mockColours() {
        assertEquals(0xFF2C3532, Lamps.argb(LampLevel.OFF))
        assertEquals(0xFF2ECC40, Lamps.argb(LampLevel.GREEN))
        assertEquals(0xFF4AA3FF, Lamps.argb(LampLevel.BLUE))
        assertEquals(0xFFFFE14D, Lamps.argb(LampLevel.YELLOW))
        assertEquals(0xFFFFB020, Lamps.argb(LampLevel.AMBER))
        assertEquals(0xFFFF3B30, Lamps.argb(LampLevel.RED))
    }

    @Test fun onlyRedBlinks() {
        assertEquals(LampLevel.OFF, Lamps.shown(LampLevel.RED, blinkOn = false, ambient = false))
        assertEquals(LampLevel.RED, Lamps.shown(LampLevel.RED, blinkOn = true, ambient = false))
        // ambient and the bulb check: steady
        assertEquals(LampLevel.RED, Lamps.shown(LampLevel.RED, blinkOn = false, ambient = true))
        assertEquals(LampLevel.RED, Lamps.shown(LampLevel.RED, blinkOn = false, ambient = false, bulb = true))
        for (l in LampLevel.entries) if (l != LampLevel.RED) {
            assertFalse(Lamps.blinks(l))
            assertEquals(l, Lamps.shown(l, blinkOn = false, ambient = false))
        }
    }

    @Test fun everyLampHasAGlyphThatParses() {
        assertEquals(TellTales.IDS.toSet(), Glyphs.BY_ID.keys)
        for ((id, gs) in Glyphs.BY_ID) for (g in gs) {
            val nodes = PathParser().parsePathString(g.d).toNodes()
            assertTrue("$id: ${g.d}", nodes.size >= 2)
            assertTrue(g.width > 0)
        }
        // the ▲ sits right of the thermometer, pointing up
        val (a, b, c) = Glyphs.RISING
        assertTrue(a.first > Dial.ICON / 2 && b.second < a.second && c.second == a.second)
    }
}

class BlinkAndBulbTest {
    @Test fun oneHertz() {
        assertEquals(500L, Blink.HALF_MS)
        assertTrue(Blink.on(0))
        assertTrue(Blink.on(499))
        assertFalse(Blink.on(500))
        assertFalse(Blink.on(999))
        assertTrue(Blink.on(1000))
        // a whole period is one second
        for (t in 0L..5000L step 50) assertEquals(Blink.on(t), Blink.on(t + 1000))
    }

    @Test fun bulbCheckLastsOneAndAHalfSeconds() {
        assertEquals(1500L, TellTales.BULB_CHECK_MS)
        val b = BulbCheck()
        assertFalse(b.active(0))
        b.start(10_000)
        assertTrue(b.active(10_000))
        assertTrue(b.active(11_499))
        assertFalse(b.active(11_500))
        assertFalse(b.active(9_999))
        assertEquals(1000L, b.leftMs(10_500))
        assertEquals(0L, b.leftMs(12_000))
    }

    @Test fun againOnEachConnection() {
        val b = BulbCheck()
        // no message yet, then the first one: the opening check covers it
        assertFalse(b.onFeed(null, Link.IDLE, 0))
        assertFalse(b.onFeed(1_000, Link.CONNECTED, 1_000))
        assertFalse(b.onFeed(2_000, Link.CONNECTED, 2_000))
        // dropped and back: a check
        assertFalse(b.onFeed(3_000, Link.RECONNECTING, 3_000))
        assertTrue(b.onFeed(9_000, Link.CONNECTED, 9_000))
        assertTrue(b.active(10_000))
        assertFalse(b.onFeed(9_500, Link.CONNECTED, 9_500))
        // a new ride: idle → scanning → connecting → connected, one check
        val seq = listOf(Link.IDLE, Link.SCANNING, Link.CONNECTING, Link.CONNECTED, Link.CONNECTED)
        assertEquals(listOf(false, false, false, true, false), seq.mapIndexed { k, l -> b.onFeed(20_000L + k, l, 20_000L + k) })
    }

    @Test fun theCheckItself() {
        val all = TellTales.bulbCheck()
        assertEquals(TellTales.IDS, all.map { it.id })
        assertTrue(all.none { it.level == LampLevel.OFF })
        assertTrue(Cluster.isBulbCheck(all))
        assertFalse(Cluster.isBulbCheck(Samples.critical.lamps))
        assertFalse(Cluster.isBulbCheck(emptyList()))
    }
}

class ClusterTest {
    private fun w(id: String, level: WarningLevel, value: Double) = BatteryWarning(id, level, BatteryAdvisor.TEXTS.getValue(id to level), value)

    @Test fun thePhonesLampsAsTheyCome() {
        assertSame(Samples.critical.lamps, Cluster.lamps(Samples.critical))
        assertEquals(emptyList<Lamp>(), Cluster.lamps(Telemetry()))
    }

    @Test fun anOlderPhonesWarningsStandIn() {
        val t = Telemetry(warnings = listOf(w(BatteryAdvisor.HOT, WarningLevel.CRITICAL, 56.0), w(BatteryAdvisor.LOW, WarningLevel.WARNING, 32.1)))
        val l = Cluster.lamps(t)
        assertEquals(listOf(TellTales.TEMP, TellTales.BATTERY), l.map { it.id })
        assertEquals(Lamp(TellTales.TEMP, LampLevel.RED, "Перегрев — сбавь газ", 56.0), l[0])
        assertEquals(Lamp(TellTales.BATTERY, LampLevel.AMBER, "Заряд на исходе", 32.1), l[1])
        val warm = Cluster.lamps(Telemetry(warnings = listOf(w(BatteryAdvisor.HOT, WarningLevel.WARNING, 51.0))))
        assertEquals(LampLevel.AMBER, warm.single().level)
        // the 46-character overheat text never reaches the dial's line
        assertTrue(TellTales.top(l)!!.text.length <= 24)
    }
}

class LampAlarmTest {
    private fun lamp(id: String, level: LampLevel) = Lamp(id, level, "x")
    private val temp = TellTales.TEMP
    private val traction = TellTales.TRACTION
    private val battery = TellTales.BATTERY

    @Test fun amberTapsOnceRedBuzzesHard() {
        assertEquals(1, Buzz.AMBER.pulses)
        assertEquals(3, Buzz.RED.pulses)
        assertTrue(Buzz.RED.onMs > 4 * Buzz.AMBER.onMs)
        assertTrue(Buzz.RED.amplitudes.max() > Buzz.AMBER.amplitudes.max())
        for (b in listOf(Buzz.AMBER, Buzz.RED)) {
            assertEquals(b.timings.size, b.amplitudes.size)
            assertEquals(0L, b.timings[0])
            assertTrue(b.timings.sum() < 1_000)
            assertTrue(b.amplitudes.all { it in 0..255 })
        }
        assertEquals(LampLevel.RED, Buzz.RED.level)
    }

    @Test fun amberOnceThenACooldown() {
        val a = LampAlarm()
        assertNull(a.onMessage(emptyList(), true, 0))
        assertSame(Buzz.AMBER, a.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 1_000))
        // stays lit: quiet
        for (k in 1..30) assertNull(a.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 1_000 + k * 1_000L))
        // off and on again (two-up at the current limit): quiet within 5 minutes …
        assertNull(a.onMessage(emptyList(), true, 60_000))
        assertNull(a.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 120_000))
        assertNull(a.onMessage(emptyList(), true, 200_000))
        // … then one tap again
        assertSame(Buzz.AMBER, a.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 1_000 + LampAlarm.AMBER_COOLDOWN_MS))
        // from yellow up to amber is a rise; amber down from red is not
        val b = LampAlarm()
        assertNull(b.onMessage(listOf(lamp(temp, LampLevel.YELLOW)), true, 0))
        assertSame(Buzz.AMBER, b.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 10_000))
        assertSame(Buzz.RED, b.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 20_000))
        assertNull(b.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 25_000))
    }

    @Test fun redRepeatsEveryThirtySecondsWhileRed() {
        val a = LampAlarm()
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 0))
        val buzzes = (1..120).mapNotNull { k -> a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, k * 1_000L)?.let { k } }
        assertEquals(listOf(30, 60, 90, 120), buzzes)
        // cleared: quiet
        assertNull(a.onMessage(emptyList(), true, 150_000))
        assertNull(a.onMessage(emptyList(), true, 200_000))
    }

    @Test fun aFlickeringRedWaitsForTheRepeat() {
        val a = LampAlarm()
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 0))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 10_000))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 20_000))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 30_000))
    }

    @Test fun anotherRedAtOnceSeveralLampsOneBuzz() {
        val a = LampAlarm()
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 0))
        // the battery turns red 10 s later: news, at once (the repeat clock restarts)
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED), lamp(battery, LampLevel.RED)), true, 10_000))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.RED), lamp(battery, LampLevel.RED)), true, 30_000))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED), lamp(battery, LampLevel.RED)), true, 40_000))
        // three lamps at once: one buzz, the strongest; none of them taps on its own afterwards
        val b = LampAlarm()
        val all = listOf(lamp(temp, LampLevel.RED), lamp(traction, LampLevel.AMBER), lamp(TellTales.CONSUMPTION, LampLevel.AMBER))
        assertSame(Buzz.RED, b.onMessage(all, true, 0))
        assertNull(b.onMessage(all.drop(1), true, 6_000))
        assertNull(b.onMessage(emptyList(), true, 7_000))
        assertNull(b.onMessage(all.drop(1), true, 20_000))
        // two ambers a second apart: one tap
        val c = LampAlarm()
        assertSame(Buzz.AMBER, c.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 0))
        assertNull(c.onMessage(listOf(lamp(traction, LampLevel.AMBER), lamp(TellTales.OVERVOLT, LampLevel.AMBER)), true, 1_000))
        assertNull(c.onMessage(listOf(lamp(TellTales.OVERVOLT, LampLevel.AMBER)), true, 60_000))
    }

    @Test fun quietLampsAndCalmColoursNeverBuzz() {
        val a = LampAlarm()
        // service (always lit with this battery), weak, ECO; blue overspeed, green charging, yellow warm
        val calm = listOf(
            lamp(TellTales.SERVICE, LampLevel.AMBER), lamp(TellTales.WEAK, LampLevel.YELLOW), lamp(TellTales.ECO, LampLevel.GREEN),
            lamp(TellTales.OVERSPEED, LampLevel.BLUE), lamp(TellTales.CHARGING, LampLevel.GREEN), lamp(temp, LampLevel.YELLOW),
        )
        for (k in 0..10) assertNull(a.onMessage(if (k % 2 == 0) calm else emptyList(), true, k * 400_000L))
        // the normal dial: nothing
        assertNull(LampAlarm().onMessage(Samples.normal.lamps, true, 0))
    }

    @Test fun onlyLiveDataAndNoBulbCheck() {
        val a = LampAlarm()
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.RED)), false, 0))
        assertNull(a.onMessage(TellTales.bulbCheck(), true, 1_000))
        // the first live message with it lit: news
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 2_000))
        // a stale gap: no repeats meanwhile, and the lamp that stayed amber through it is not news
        val b = LampAlarm()
        assertSame(Buzz.AMBER, b.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 0))
        assertNull(b.onMessage(emptyList(), false, 100_000))
        assertNull(b.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 400_000))
    }

    @Test fun theMocksThreeDials() {
        val a = LampAlarm()
        assertNull(a.onMessage(Samples.normal.lamps, true, 0))
        // «Внимание»: one vibration
        assertSame(Buzz.AMBER, a.onMessage(Samples.attention.lamps, true, 60_000))
        assertNull(a.onMessage(Samples.attention.lamps, true, 61_000))
        // «Критично»: the strong one
        assertSame(Buzz.RED, a.onMessage(Samples.critical.lamps, true, 120_000))
    }
}

class OverspeedAlarmTest {
    private val mph16 = 16 * Fmt.MILE

    @Test fun onAboveOffBelowWithHalfAMileOfHysteresis() {
        val a = OverspeedAlarm()
        fun at(mph: Double, t: Long) = a.update(true, 16.0, mph * Fmt.MILE, true, t)
        assertEquals(OverspeedAlarm.Action.NONE, at(15.0, 0))
        // exactly 16: not above
        assertEquals(OverspeedAlarm.Action.NONE, at(16.0, 100))
        assertFalse(a.on)
        assertEquals(OverspeedAlarm.Action.START, at(16.1, 200))
        assertTrue(a.on)
        assertEquals(OverspeedAlarm.Action.NONE, at(15.6, 300))
        assertEquals(OverspeedAlarm.Action.NONE, at(15.51, 400))
        assertTrue(a.on)
        assertEquals(OverspeedAlarm.Action.STOP, at(15.49, 500))
        assertFalse(a.on)
        // back between: stays off until above 16 again
        assertEquals(OverspeedAlarm.Action.NONE, at(15.9, 600))
        assertEquals(OverspeedAlarm.Action.START, at(16.5, 700))
    }

    @Test fun armedAgainEveryTwoSeconds() {
        val a = OverspeedAlarm()
        assertEquals(OverspeedAlarm.Action.START, a.update(true, 16.0, mph16 + 1, true, 10_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.update(true, 16.0, mph16 + 1, true, 11_999))
        assertEquals(OverspeedAlarm.Action.START, a.update(true, 16.0, mph16 + 1, true, 12_000))
        assertEquals(OverspeedAlarm.Action.NONE, a.update(true, 16.0, mph16 + 1, true, 13_000))
        assertEquals(OverspeedAlarm.REARM_MS, a.rearmMs)
    }

    @Test fun stopsAtOnceWithoutLiveData() {
        for (stop in listOf<(OverspeedAlarm) -> OverspeedAlarm.Action>(
            { it.update(true, 16.0, mph16 + 3, false, 1_000) },   // stale / not streaming / link down
            { it.update(false, 16.0, mph16 + 3, true, 1_000) },   // switched off on the details page
            { it.update(true, 16.0, null, true, 1_000) },         // no speed
            { it.update(true, 20.0, mph16 + 3, true, 1_000) },    // threshold raised past the speed
            { it.stop() },                                        // the service stops
        )) {
            val a = OverspeedAlarm()
            assertEquals(OverspeedAlarm.Action.START, a.update(true, 16.0, mph16 + 3, true, 0))
            assertEquals(OverspeedAlarm.Action.STOP, stop(a))
            assertFalse(a.on)
        }
        assertEquals(OverspeedAlarm.Action.NONE, OverspeedAlarm().stop())
    }

    @Test fun liveMeansFreshAndStreaming() {
        // what RideService passes as «live»: Health.of at the time of the check
        val t = Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = mph16 + 3)
        assertTrue(Health.of(t, 10_000, 13_000).live)
        assertFalse(Health.of(t, 10_000, 13_001).live)
        assertFalse(Health.of(t.copy(link = Link.RECONNECTING), 10_000, 10_000).live)
        assertFalse(Health.of(t.copy(streaming = false), 10_000, 10_000).live)
        val a = OverspeedAlarm()
        assertEquals(OverspeedAlarm.Action.START, a.update(true, 16.0, t.speedKmh, Health.of(t, 10_000, 10_000).live, 10_000))
        assertEquals(OverspeedAlarm.Action.START, a.update(true, 16.0, t.speedKmh, Health.of(t, 10_000, 12_000).live, 12_000))
        assertEquals(OverspeedAlarm.Action.STOP, a.update(true, 16.0, t.speedKmh, Health.of(t, 10_000, 13_001).live, 13_001))
    }

    @Test fun aRepeatingWaveform() {
        val t = OverspeedAlarm.TIMINGS
        val amp = OverspeedAlarm.AMPLITUDES
        assertEquals(t.size, amp.size)
        assertTrue(OverspeedAlarm.REPEAT in t.indices)
        // the repeated part is mostly on, at full strength
        val on = t.indices.filter { it >= OverspeedAlarm.REPEAT && amp[it] > 0 }.sumOf { t[it] }
        val all = t.indices.filter { it >= OverspeedAlarm.REPEAT }.sumOf { t[it] }
        assertTrue(on >= 0.8 * all)
        assertEquals(255, amp.max())
        // re-armed before the system could notice a gap
        assertTrue(OverspeedAlarm.REARM_MS < Health.STALE_MS)
    }
}

class OverspeedPrefTest {
    @Test fun halfMileSteps() {
        assertEquals(16.0, OverspeedPref.clamp(16.2), 0.0)
        assertEquals(16.5, OverspeedPref.clamp(16.3), 0.0)
        assertEquals(25.0, OverspeedPref.clamp(100.0), 0.0)
        assertEquals(5.0, OverspeedPref.clamp(1.0), 0.0)
        assertEquals(16.0, OverspeedPref.clamp(Double.NaN), 0.0)
        assertEquals(16.5, OverspeedPref.step(16.0, 1), 0.0)
        assertEquals(15.5, OverspeedPref.step(16.0, -1), 0.0)
        assertEquals(25.0, OverspeedPref.step(25.0, 1), 0.0)
        assertEquals(5.0, OverspeedPref.step(5.0, -1), 0.0)
        // 5 … 25 in 0,5: 41 values, 39 between the ends
        assertEquals(39, OverspeedPref.STEPS)
    }

    @Test fun texts() {
        assertEquals("16${NB}mph", OverspeedPref.mph(16.0))
        assertEquals("16,5${NB}mph", OverspeedPref.mph(16.5))
        assertEquals("16${NB}mph · 25,7${NB}км/ч", OverspeedPref.describe(16.0, "kmh"))
        assertEquals("16${NB}mph", OverspeedPref.describe(16.0, "mph"))
    }

    @Test fun onAtSixteenByDefault() {
        val p = WatchPrefs()
        assertTrue(p.overspeedOn)
        assertEquals(16.0, p.overspeedMph, 0.0)
        assertEquals(OverspeedPref.DEFAULT_MPH, p.overspeedMph, 0.0)
    }
}

class LampLinesTest {
    @Test fun strongestFirstWithWhatItIsAbout() {
        val lines = lampLines(Samples.critical)
        assertEquals(listOf(TellTales.TEMP, TellTales.BATTERY, TellTales.TRACTION, TellTales.SERVICE, TellTales.LINK), lines.map { it.first.id })
        assertEquals("Температура батареи · 56${NB}°C", lines[0].second)
        assertEquals("Заряд · 31,5${NB}В под нагрузкой", lines[1].second)
        assertEquals("Мало тяги · 31,8${NB}В", lines[2].second)
        assertEquals("Мало тяги · 15,8${NB}А", lampLines(Samples.attention)[1].second)
        assertEquals("Сервис", lampLines(Samples.attention).last().second)
        assertEquals(emptyList<Pair<Lamp, String>>(), lampLines(Telemetry()))
        // the settings below the values, the overspeed switch right under «Плавно»
        val s = Screen(Samples.critical, Health.LIVE)
        assertEquals(2 + lines.size + detailRows(s.t, s.fast).size + 1, smoothChipIndex(s))
        assertEquals(smoothChipIndex(s) + 1, overspeedChipIndex(s))
    }
}
