package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.WarningLevel
import io.github.purplepalm.birdair.core.Wire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class SpringTest {
    /** Steps [s] at [frameS] until [untilS]; returns (time, x) per frame. */
    private fun run(s: Spring, untilS: Double, frameS: Double = 1.0 / 60): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        while (t < untilS - 1e-12) { s.step(frameS); t += frameS; out += t to s.x }
        return out
    }

    @Test fun constants() {
        // (1 + y)·e^−y = 0.05 at y = Y95: 95 % of a step at SETTLE_MS
        assertEquals(0.05, (1 + Spring.Y95) * exp(-Spring.Y95), 1e-9)
        assertEquals(43.1, Spring.OMEGA, 0.05)
        assertTrue(Spring.SETTLE_MS in 100.0..120.0)
    }

    @Test fun stepFromRestSettlesIn110msWithoutOvershoot() {
        val s = Spring(x = 0.0)
        s.target = 1.0
        // exactly 95 % at 110 ms, whatever the frame time
        s.step(0.110)
        assertEquals(0.95, s.x, 1e-6)
        val s2 = Spring(x = 0.0).apply { target = 1.0 }
        val path = run(s2, 0.4, 1.0 / 120)
        var prev = 0.0
        for ((_, x) in path) {
            assertTrue("no overshoot: $x", x <= 1.0)
            assertTrue("monotone: $x after $prev", x >= prev)
            prev = x
        }
        val at = path.first { it.second >= 0.95 }.first
        assertTrue("95 % at $at s", at in 0.100..0.120)
        // half way in about a third of that: it does move at once, unlike a long tween
        assertTrue(path.first { it.second >= 0.5 }.first < 0.045)
        // and it lands exactly, so the frame loop stops
        run(s2, 0.4)
        assertTrue(s2.settled)
        assertEquals(1.0, s2.x, 0.0)
        assertEquals(0.0, s2.v, 0.0)
    }

    @Test fun frameRateIndependent() {
        val a = Spring(x = -0.3).apply { target = 0.8 }
        val b = Spring(x = -0.3).apply { target = 0.8 }
        a.step(0.048)
        repeat(4) { b.step(0.012) }
        assertEquals(a.x, b.x, 1e-12)
        assertEquals(a.v, b.v, 1e-9)
    }

    @Test fun retargetNeverOvershootsOrBacktracks() {
        // rising fast towards 1, then a new value just ahead: it slows down onto it, never past
        val s = Spring(x = 0.0).apply { target = 1.0 }
        run(s, 0.03)
        val mid = s.x
        assertTrue(s.v > 0)
        s.target = mid + 0.05
        for ((_, x) in run(s, 0.3)) assertTrue("past the new target: $x", x <= mid + 0.05 + 1e-12)
        assertEquals(mid + 0.05, s.x, 0.0)
        // rising, then the power drops: it turns back at once, no carry-on upwards
        val r = Spring(x = 0.0).apply { target = 1.0 }
        run(r, 0.03)
        val top = r.x
        r.target = 0.2
        var prev = top
        for ((_, x) in run(r, 0.3)) {
            assertTrue("moves towards 0.2 only: $x after $prev", x <= prev + 1e-12)
            assertTrue(x >= 0.2)
            prev = x
        }
        // traction to regen across zero: same rules, through zero without bouncing
        val z = Spring(x = 0.6).apply { target = -0.4 }
        for ((_, x) in run(z, 0.3)) assertTrue(x >= -0.4)
        assertEquals(-0.4, z.x, 0.0)
    }

    @Test fun keepsVelocityTowardsAFartherTarget() {
        // a second, larger value while rising: it carries on smoothly (same speed, no restart from zero)
        val s = Spring(x = 0.0).apply { target = 0.5 }
        run(s, 0.02)
        val v = s.v
        s.target = 1.0
        val x0 = s.x
        s.step(0.001)
        assertEquals(v, (s.x - x0) / 0.001, v * 0.1)
    }

    @Test fun snapAndBadInput() {
        val s = Spring(x = 0.2).apply { target = 0.9 }
        s.step(0.01)
        s.snap(0.4)
        assertEquals(0.4, s.x, 0.0)
        assertEquals(0.4, s.target, 0.0)
        assertTrue(s.settled)
        s.target = Double.NaN
        s.step(0.016)
        assertEquals(0.0, s.x, 0.0)
        s.target = 0.5
        s.step(Double.NaN)
        assertEquals(0.0, s.x, 0.0)
        s.step(10.0)
        assertEquals(0.5, s.x, 0.0)
    }
}

class SmoothPrefTest {
    @Test fun cyclesAndResolves() {
        assertEquals(SmoothPref.ON, SmoothPref.PHONE.next())
        assertEquals(SmoothPref.OFF, SmoothPref.ON.next())
        assertEquals(SmoothPref.PHONE, SmoothPref.OFF.next())
        assertTrue(SmoothPref.PHONE.resolve(true))
        assertFalse(SmoothPref.PHONE.resolve(false))
        // a phone that sends no flag: smooth, as before
        assertTrue(SmoothPref.PHONE.resolve(null))
        assertTrue(SmoothPref.ON.resolve(false))
        assertFalse(SmoothPref.OFF.resolve(true))
        assertEquals("как на телефоне: выкл", SmoothPref.PHONE.describe(false))
        assertEquals("как на телефоне: вкл", SmoothPref.PHONE.describe(null))
        assertEquals("выкл", SmoothPref.OFF.describe(true))
    }

    @Test fun storedKeys() {
        for (p in SmoothPref.entries) assertEquals(p, SmoothPref.of(p.key))
        assertEquals(SmoothPref.PHONE, SmoothPref.of(null))
        assertEquals(SmoothPref.PHONE, SmoothPref.of("garbage"))
        assertEquals(listOf("phone", "on", "off"), SmoothPref.entries.map { it.key })
    }
}

class PhoneFlagsTest {
    private fun msg(extra: String) = ("{\"t\":5,\"link\":\"CONNECTED\",\"streaming\":true,\"powerW\":120.0$extra}").toByteArray()

    @Test fun asThePhoneSendsThem() {
        // ScooterRepo.forWatch: the snapshot with extras «fast» / «smooth» as 1/0
        val t = Samples.riding.copy(extras = mapOf("Код ошибки" to 37.0, "fast" to 0.0, "smooth" to 1.0))
        val b = Wire.encode(t)
        assertEquals(PhoneFlags(fast = false, smooth = true), PhoneFlags.parse(b))
        val f = Feed().received(b, 1_000)!!
        assertFalse(Screen(f.t, Health.LIVE, f.flags).fast)
        // settings, not readings: not on the details page; a real custom field still is
        val names = detailRows(f.t, false).flatten().map { it.label }
        assertFalse("fast" in names)
        assertFalse("smooth" in names)
        assertTrue("Код ошибки" in names)
        // extras win over nothing, the top level over extras
        assertEquals(PhoneFlags(fast = true, smooth = true), PhoneFlags.parse(msg(",\"fast\":true,\"extras\":{\"fast\":0.0,\"smooth\":1.0}")))
    }

    @Test fun topLevelKeys() {
        assertEquals(PhoneFlags(fast = true, smooth = false), PhoneFlags.parse(msg(",\"fast\":true,\"smooth\":false")))
        assertEquals(PhoneFlags(), PhoneFlags.parse(msg("")))
        // Telemetry itself still decodes: the watch's Wire ignores the extra keys
        val t = Wire.decode(msg(",\"fast\":true,\"smooth\":false"))!!
        assertEquals(120.0, t.powerW!!, 0.0)
    }

    @Test fun aliasesNestingAndValues() {
        assertEquals(PhoneFlags(fast = false, smooth = true), PhoneFlags.parse(msg(",\"flags\":{\"fastPower\":false,\"smoothing\":true}")))
        assertEquals(PhoneFlags(fast = true, smooth = false), PhoneFlags.parse(msg(",\"ui\":{\"fast\":1,\"smooth\":\"off\"}")))
        assertEquals(PhoneFlags(fast = false, smooth = true), PhoneFlags.parse(msg(",\"settings\":{\"fast\":\"false\",\"animate\":\"on\"}")))
        // top level wins over nested
        assertEquals(PhoneFlags(fast = true), PhoneFlags.parse(msg(",\"fast\":true,\"watch\":{\"fast\":false}")))
        // nonsense values say nothing
        assertEquals(PhoneFlags(), PhoneFlags.parse(msg(",\"fast\":\"maybe\",\"smooth\":null")))
        assertEquals(PhoneFlags(), PhoneFlags.parse("garbage".toByteArray()))
        assertEquals(PhoneFlags(), PhoneFlags.parse(ByteArray(0)))
        assertEquals(PhoneFlags(), PhoneFlags.parse("[1,2]".toByteArray()))
    }

    @Test fun mergeKeepsTheLastWord() {
        val a = PhoneFlags(fast = false, smooth = true)
        assertEquals(a, a.merge(PhoneFlags()))
        assertEquals(PhoneFlags(fast = true, smooth = true), a.merge(PhoneFlags(fast = true)))
    }

    @Test fun feedCarriesFlags() {
        val f1 = Feed().received(msg(",\"fast\":false,\"smooth\":false"), 1_000)!!
        assertEquals(PhoneFlags(false, false), f1.flags)
        // a message without them (an older phone build, a hello answer) keeps them
        val f2 = f1.received(msg("").toString(Charsets.UTF_8).replace("\"t\":5", "\"t\":6").toByteArray(), 1_250)!!
        assertEquals(PhoneFlags(false, false), f2.flags)
        val s = Screen(f2.t, Health.LIVE, f2.flags)
        assertFalse(s.fast)
        assertTrue(Screen().fast)
    }
}

class FeedOrderTest {
    private fun t(time: Long, seq: Long, p: Double) = Wire.encode(Telemetry(t = time, seq = seq, link = Link.CONNECTED, streaming = true, powerW = p))

    @Test fun eachMessageAtOnceButLateOnesSkipped() {
        var f = Feed()
        f = f.received(t(1_000, 10, 100.0), 50_000)!!
        f = f.received(t(1_250, 11, 200.0), 50_250)!!
        assertEquals(200.0, f.t.powerW!!, 0.0)
        // the phone's answer to a hello, taken before the last stream message, arriving after it
        assertNull(f.received(t(1_100, 10, 150.0), 50_300))
        // same time, newer data: taken
        assertEquals(300.0, f.received(t(1_250, 12, 300.0), 50_300)!!.t.powerW!!, 0.0)
        // a newer snapshot with no new value (seq unchanged): taken, it carries the link state
        assertNotNull(f.received(t(1_500, 11, 200.0), 50_500))
    }

    @Test fun restartedPhoneBelievedSoon() {
        var f = Feed().received(t(900_000, 5_000, 100.0), 10_000)!!
        // the phone app started again: its clock and seq begin anew
        assertNull(f.received(t(300, 2, 50.0), 10_500))
        f = f.received(t(1_800, 7, 60.0), 10_000 + Feed.LATE_MS)!!
        assertEquals(60.0, f.t.powerW!!, 0.0)
        // the first message ever is always taken; a phone without seq/t (all 0) still flows
        assertNotNull(Feed().received(Wire.encode(Telemetry()), 0))
        val z = Feed().received(Wire.encode(Telemetry(link = Link.CONNECTED)), 0)!!
        assertNotNull(z.received(Wire.encode(Telemetry(link = Link.CONNECTED, speedKmh = 3.0)), 100))
    }
}

class PowerViewTest {
    private val t = Telemetry(currentA = 8.0, powerW = 300.0, currentFastA = 10.0, powerFastW = 370.0, speedKmh = 20.0)

    @Test fun fastWhenThePhoneSaysSo() {
        assertEquals(370.0, PowerView.watts(t, true)!!, 0.0)
        assertEquals(10.0, PowerView.amps(t, true)!!, 0.0)
        assertEquals(300.0, PowerView.watts(t, false)!!, 0.0)
        assertEquals(8.0, PowerView.amps(t, false)!!, 0.0)
        // no fast value (an older phone, a decoder without frames): the BMS one
        val old = t.copy(currentFastA = null, powerFastW = null)
        assertEquals(300.0, PowerView.watts(old, true)!!, 0.0)
        assertFalse(PowerView.usesFast(old, true))
        assertTrue(PowerView.usesFast(t, true))
        assertFalse(PowerView.usesFast(t, false))
        // the flag: what the phone says, fast when it says nothing
        assertTrue(PowerView.fast(PhoneFlags()))
        assertFalse(PowerView.fast(PhoneFlags(fast = false)))
    }

    @Test fun flowAndDetailsFollow() {
        // the BMS current still lags at +0.4 A while the fast one already shows regen
        val braking = Telemetry(currentA = 0.4, powerW = 15.0, currentFastA = -2.0, powerFastW = -75.0, speedKmh = 14.0)
        assertEquals(Flow.DRIVE, Flow.of(braking))
        assertEquals(Flow.REGEN, Flow.of(braking, fast = true))
        val cells = detailRows(Samples.riding, fast = true).flatten().associate { it.label to it.value }
        assertEquals("496\u00A0Вт", cells["Мощность"])
        assertEquals("13,1\u00A0А", cells["Ток"])
        assertEquals("470\u00A0Вт", cells["Мощность BMS"])
        assertEquals("12,4\u00A0А", cells["Ток BMS"])
        val bms = detailRows(Samples.riding, fast = false).flatten().associate { it.label to it.value }
        assertEquals("470\u00A0Вт", bms["Мощность"])
        assertFalse("Мощность BMS" in bms)
    }
}

class WarningsTest {
    private fun w(id: String, level: WarningLevel, value: Double? = null) =
        BatteryWarning(id, level, BatteryAdvisor.TEXTS.getValue(id to level), value)

    @Test fun topAndColours() {
        val low = w(BatteryAdvisor.LOW, WarningLevel.WARNING, 31.86)
        val hot = w(BatteryAdvisor.HOT, WarningLevel.CRITICAL, 56.4)
        assertSame(hot, Warnings.top(listOf(low, hot)))
        assertSame(low, Warnings.top(listOf(low, w(BatteryAdvisor.HOT, WarningLevel.WARNING))))
        assertNull(Warnings.top(emptyList()))
        assertEquals(WarningLevel.CRITICAL, Warnings.level(listOf(low, hot)))
        assertEquals(Palette.WARN, Warnings.argb(WarningLevel.WARNING))
        assertEquals(Palette.CRIT, Warnings.argb(WarningLevel.CRITICAL))
        assertEquals("Заряд на исходе", Warnings.text(low))
        assertEquals("Батарея перегрета — скоро отключится, сбавь газ", Warnings.text(hot))
        assertEquals("Сейчас отключится", Warnings.text(BatteryWarning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, "")))
        assertEquals("31,9\u00A0В под нагрузкой", Warnings.valueText(low))
        assertEquals("56\u00A0°C", Warnings.valueText(hot))
        assertNull(Warnings.valueText(w(BatteryAdvisor.HOT, WarningLevel.WARNING)))
        assertEquals(listOf(low to "31,9\u00A0В под нагрузкой"), warningLines(listOf(low)))
    }

    @Test fun batteryColourFollowsTheLowWarning() {
        // BMS 41 % (green) but the pack sags to the cutoff: amber, then red
        assertEquals(BatteryTone.GOOD, Warnings.batteryTone(41.0, emptyList()))
        assertEquals(BatteryTone.WARN, Warnings.batteryTone(41.0, listOf(w(BatteryAdvisor.LOW, WarningLevel.WARNING))))
        assertEquals(BatteryTone.CRIT, Warnings.batteryTone(41.0, listOf(w(BatteryAdvisor.LOW, WarningLevel.CRITICAL))))
        // never milder than the % says; heat does not colour the charge
        assertEquals(BatteryTone.CRIT, Warnings.batteryTone(9.0, listOf(w(BatteryAdvisor.LOW, WarningLevel.WARNING))))
        assertEquals(BatteryTone.GOOD, Warnings.batteryTone(80.0, listOf(w(BatteryAdvisor.HOT, WarningLevel.CRITICAL))))
    }

    @Test fun rideStatusPutsTheTopLampFirst() {
        // two red lamps: the temperature comes first in the cluster order
        val t = Samples.critical
        assertEquals("Перегрев — сбавь газ · 12 % · 3,6 км", RideStatus.text(t, Health.LIVE))
        assertEquals("temp:RED", RideStatus.warningKey(t, Health.LIVE))
        // the standing «Сервис» lamp says nothing in the status
        assertEquals("54 % · 3,6 км", RideStatus.text(Samples.riding, Health.LIVE))
        assertNull(RideStatus.warningKey(Samples.riding, Health.LIVE))
        // an older phone without lamps: its warnings stand in, with the short lamp text
        assertEquals("Перегрев — сбавь газ · 12 % · 3,6 км", RideStatus.text(t.copy(lamps = emptyList()), Health.LIVE))
        assertEquals("battery:AMBER", RideStatus.warningKey(Samples.warning.copy(lamps = emptyList()), Health.LIVE))
    }

    @Test fun rideStatusTiming() {
        // a changed warning goes out at once, other text changes wait for EVERY_MS
        assertTrue(RideStatus.due("a", "b", "battery_low:CRITICAL", "battery_low:WARNING", 1_000))
        assertFalse(RideStatus.due("a", "b", null, null, 1_000))
        assertTrue(RideStatus.due("a", "b", null, null, RideStatus.EVERY_MS))
        assertFalse(RideStatus.due("a", "a", "x", null, 0))
    }
}

class RangeTextTest {
    private fun r(km: Double, basis: String = "recent") = RangeEstimate(km, km / 1.609344, km * 15, 15.0, basis)

    @Test fun formats() {
        assertEquals("запас ≈4,2\u00A0км", RangeText.line(r(4.24), "kmh"))
        assertEquals("запас ≈2,6\u00A0mi", RangeText.line(r(4.24), "mph"))
        assertEquals("≈26\u00A0км", RangeText.value(r(26.3), "kmh"))
        assertEquals("≈0,0\u00A0км", RangeText.value(r(0.0), "kmh"))
        assertNull(RangeText.line(null, "kmh"))
        assertNull(RangeText.line(r(Double.NaN), "kmh"))
        assertTrue(RangeText.rough(r(20.0, "default")))
        assertFalse(RangeText.rough(r(20.0)))
        assertEquals("по последним 2 км", RangeText.basis(r(1.0)))
        assertEquals("с начала поездки", RangeText.basis(r(1.0, "session")))
        val cells = detailRows(Samples.warning).flatten().associate { it.label to it.value }
        assertEquals("≈2,1\u00A0км", cells["Запас хода"])
        assertEquals("19,5\u00A0Вт·ч/км", cells["по последним 2 км"])
        assertEquals("1\u00A0431,7\u00A0км", cells["Пробег по приложению"])
    }
}

class HelloInfoTest {
    @Test fun roundTrip() {
        val h = HelloInfo(12, "0.3-abc1234")
        val b = h.encode()
        assertEquals("{\"app\":\"wear\",\"versionCode\":12,\"versionName\":\"0.3-abc1234\"}", String(b, Charsets.UTF_8))
        assertEquals(h, HelloInfo.decode(b))
        // an older watch says hello with no bytes
        assertNull(HelloInfo.decode(ByteArray(0)))
        assertNull(HelloInfo.decode("garbage".toByteArray()))
        assertNull(HelloInfo.decode("{\"versionCode\":3}".toByteArray()))
        assertEquals("Часы: версия dev (1)", versionLine("dev", 1))
    }

    @Test fun buildConfigDefaults() {
        // a local build without -PversionCode / -PversionName
        assertTrue(BuildConfig.VERSION_CODE >= 1)
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
        assertEquals(HelloInfo(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME), HelloInfo.decode(HelloInfo.THIS.encode()))
    }
}
