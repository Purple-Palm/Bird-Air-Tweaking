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
import io.github.purplepalm.birdair.dial.*
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
        assertEquals("8${NB}Вт${NB}накат", Dial.powerText(-8.0, Flow.COAST))
        assertEquals("5${NB}Вт", Dial.powerText(4.9, Flow.IDLE))
        assertEquals("—${NB}Вт", Dial.powerText(null, Flow.NONE))
        assertEquals(Palette.DRAW, Dial.powerArgb(180.0))
        assertEquals(Palette.FLOW, Dial.powerArgb(-150.0))
        assertEquals(Palette.MUTED, Dial.powerArgb(0.0))
        assertEquals(Palette.MUTED, Dial.powerArgb(null))
        // coasting: −8 W of a released throttle is grey «накат», not blue regen; the phone's state decides
        assertEquals(Palette.MUTED, Dial.powerArgb(-8.0, Flow.COAST))
        assertEquals(Palette.FLOW, Dial.powerArgb(-150.0, Flow.REGEN))
        assertEquals(Palette.DRAW, Dial.powerArgb(180.0, Flow.DRIVE))
    }

    @Test fun aLongLampTextIsSetSmallerThenEllipsized() {
        assertEquals(Dial.MSG_FONT, Dial.msgFont(100f, 124f))
        assertEquals(Dial.MSG_FONT, Dial.msgFont(124f, 124f))
        assertEquals(Dial.MSG_FONT * 124f / 131f * Dial.MSG_FIT, Dial.msgFont(131f, 124f), 1e-5f)
        assertTrue(131f * Dial.msgFont(131f, 124f) / Dial.MSG_FONT < 124f)
        assertEquals(Dial.MSG_MIN_FONT, Dial.msgFont(400f, 124f))
        assertEquals(Dial.MSG_FONT, Dial.msgFont(0f, 124f))
        assertTrue(Dial.MSG_MIN_FONT < Dial.MSG_FONT)
    }

    @Test fun theBmsLines() {
        // after the BMS cut the pack (20.10): the dash answers, no frame comes — the top line says so
        val lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.RED, Cluster.NO_BMS), Lamp(TellTales.LINK, LampLevel.AMBER, Cluster.DASH_NO_BMS))
        assertEquals("BMS молчит", Cluster.statusLabel(Health.SILENT, lamps))
        assertEquals("руль молчит", Cluster.statusLabel(Health.SILENT, lamps.take(1)))
        assertEquals("нет данных\nс телефона", Cluster.statusLabel(Health.NO_PHONE, lamps))
        assertEquals("нет данных с телефона", Cluster.statusLabel(Health.NO_PHONE, lamps, gauge = false))
        assertNull(Cluster.statusLabel(Health.LIVE, lamps))
        assertEquals("BMS молчит", RideStatus.text(Telemetry(link = Link.CONNECTED, lamps = lamps), Health.SILENT))
        // the details: the BMS's flag with the voltage it came at; with the BMS gone no old value
        val warn = BatteryWarning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, "Сейчас отключится", 31.2)
        val cut = Telemetry(warnings = listOf(warn), lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.RED, Cluster.BMS_CUT, 30.94)))
        assertEquals("Заряд · 30,9${NB}В", lampLines(cut).single().second)
        assertEquals("Заряд", lampLines(cut.copy(lamps = lamps.take(1))).single().second)
        assertEquals("Заряд · 31,2${NB}В под нагрузкой", lampLines(cut.copy(lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.2)))).single().second)
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

    @Test fun redRepeatsSlowDownThenStop() {
        val a = LampAlarm()
        val red = listOf(lamp(temp, LampLevel.RED))
        assertSame(Buzz.RED, a.onMessage(red, true, 0, 25.0))
        // riding on with it red for half an hour: 30 s, then 2 min, then 5 min later, then quiet
        val buzzes = (1..1_800).mapNotNull { k -> a.onMessage(red, true, k * 1_000L, 25.0)?.let { k } }
        assertEquals(listOf(30, 150, 450), buzzes)
        assertEquals(listOf(30_000L, 120_000L, 300_000L), LampAlarm.RED_REPEATS_MS)
        // cleared: quiet
        assertNull(a.onMessage(emptyList(), true, 1_850_000, 25.0))
        assertNull(a.onMessage(emptyList(), true, 1_900_000, 25.0))
    }

    @Test fun noRepeatsAtAStandstill() {
        val a = LampAlarm()
        val red = listOf(lamp(temp, LampLevel.RED))
        assertSame(Buzz.RED, a.onMessage(red, true, 0, 20.0))
        // stopped to cool off, as the lamp asks: the overheat stays red, the watch keeps quiet
        for (k in 1..600) assertNull(a.onMessage(red, true, k * 1_000L, if (k % 2 == 0) 0.0 else 2.9))
        // riding again, still red: the overdue repeat once, then the next one 2 min on
        assertSame(Buzz.RED, a.onMessage(red, true, 601_000, 12.0))
        assertNull(a.onMessage(red, true, 700_000, 12.0))
        assertSame(Buzz.RED, a.onMessage(red, true, 721_000, 12.0))
        // a red that turns on at a standstill is news all the same
        val b = LampAlarm()
        assertSame(Buzz.RED, b.onMessage(listOf(lamp(battery, LampLevel.RED)), true, 0, 0.0))
        assertEquals(3.0, LampAlarm.MOVING_KMH, 0.0)
    }

    @Test fun aFlickeringRedWaitsForTheRepeat() {
        val a = LampAlarm()
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 0))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 10_000))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 20_000))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 30_000))
        // out for less than 2 min and back: still the same red, the next repeat 2 min after the last
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 40_000))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 140_000))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 150_000))
        // out for 2 min or more: news again when it comes back
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 160_000))
        assertNull(a.onMessage(listOf(lamp(temp, LampLevel.AMBER)), true, 160_000 + LampAlarm.RED_FORGET_MS))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(temp, LampLevel.RED)), true, 161_000 + LampAlarm.RED_FORGET_MS))
    }

    @Test fun redRightAfterAnAmberDoesNotWaitButTwoRedsDo() {
        val a = LampAlarm()
        assertSame(Buzz.AMBER, a.onMessage(listOf(lamp(traction, LampLevel.AMBER)), true, 0))
        assertSame(Buzz.RED, a.onMessage(listOf(lamp(traction, LampLevel.AMBER), lamp(temp, LampLevel.RED)), true, 1_000))
        // another red 1 s later: after the gap, not lost
        val both = listOf(lamp(temp, LampLevel.RED), lamp(battery, LampLevel.RED))
        assertNull(a.onMessage(both, true, 2_000))
        assertNull(a.onMessage(both, true, 5_999))
        assertSame(Buzz.RED, a.onMessage(both, true, 6_000))
        assertNull(a.onMessage(both, true, 7_000))
    }

    @Test fun theLinkLampNeverBuzzes() {
        // lit only while the data is not live (or for its hold after): a buzz could only come once the data is back
        val a = LampAlarm()
        assertNull(a.onMessage(listOf(lamp(TellTales.LINK, LampLevel.AMBER)), true, 0))
        assertNull(a.onMessage(emptyList(), true, 400_000))
        assertNull(a.onMessage(listOf(lamp(TellTales.LINK, LampLevel.AMBER)), true, 800_000))
        assertTrue(TellTales.LINK in LampAlarm.SILENT)
        assertTrue(LampAlarm.SILENT.containsAll(TellTales.QUIET))
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
        // the settings below the values: the tap right under «Плавно», its slider and pause, then the rumble
        val s = Screen(Samples.critical, Health.LIVE)
        assertEquals(2 + lines.size + detailRows(s.t, s.fast).size + 1, smoothChipIndex(s))
        assertEquals(smoothChipIndex(s) + 1, tapChipIndex(s))
        assertEquals(tapChipIndex(s) + 3, overspeedChipIndex(s))
        // «Вибрация ламп» under the threshold slider
        assertEquals(overspeedChipIndex(s) + 2, lampBuzzChipIndex(s))
    }
}
