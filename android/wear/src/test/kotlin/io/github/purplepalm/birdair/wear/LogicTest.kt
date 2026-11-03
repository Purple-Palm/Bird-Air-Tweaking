package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.PowerState
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.Wire
import io.github.purplepalm.birdair.dial.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcTest {
    @Test fun zeroAtTwelveFullAtPeak() {
        assertEquals(-90f, Arc.ZERO_DEG)
        assertEquals(0f, Arc.fraction(0.0, 800.0))
        assertEquals(1f, Arc.fraction(800.0, 800.0))
        assertEquals(-1f, Arc.fraction(-800.0, 800.0))
        assertEquals(0.5f, Arc.fraction(400.0, 800.0), 1e-6f)
        assertEquals(135f, Arc.sweep(1f))
        assertEquals(-135f, Arc.sweep(-1f))
        assertEquals(67.5f, Arc.sweep(0.5f), 1e-4f)
    }

    @Test fun clampsAndFallsBack() {
        assertEquals(1f, Arc.fraction(5000.0, 800.0))
        assertEquals(-1f, Arc.fraction(-5000.0, 800.0))
        assertEquals(0f, Arc.fraction(null, 800.0))
        assertEquals(0f, Arc.fraction(Double.NaN, 800.0))
        // a bad scale falls back to the tracker's 300 W floor
        assertEquals(0.5f, Arc.fraction(150.0, 0.0), 1e-6f)
        assertEquals(0.5f, Arc.fraction(150.0, Double.NaN), 1e-6f)
        assertEquals(135f, Arc.sweep(7f))
        assertEquals(0f, Arc.sweep(Float.NaN))
    }

    @Test fun tractionClockwiseRegenCounterClockwise() {
        // traction: from 12 o'clock clockwise, the round tip (3.4°) ends on the value
        assertEquals(Arc.Bar(1f, 64.1f), Arc.bar(0.5f, 3.4f))
        // regen: the same length counter-clockwise
        assertEquals(Arc.Bar(-1f, -64.1f), Arc.bar(-0.5f, 3.4f))
        // shorter than the tip (4.9 W idle draw on a 300 W scale): the tip alone, still on its own side of zero
        assertEquals(Arc.Bar(1f, 0f), Arc.bar(4.9f / 300f, 3.4f))
        assertEquals(Arc.Bar(-1f, 0f), Arc.bar(-0.02f, 3.4f))
        assertNull(Arc.bar(0f, 3.4f))
        assertNull(Arc.bar(0.001f, 3.4f))
        assertNull(Arc.bar(Float.NaN, 3.4f))
        // the track is the whole ±135° range
        assertEquals(-225f to 270f, Arc.track)
    }

    @Test fun colourBySign() {
        assertEquals(Palette.DRAW, Arc.colorFor(250.0))
        assertEquals(Palette.FLOW, Arc.colorFor(-250.0))
        assertEquals(Palette.MUTED, Arc.colorFor(0.0))
        assertEquals(Palette.MUTED, Arc.colorFor(null))
        // the page's dark-theme tokens
        assertEquals(0xFFD95926, Palette.DRAW)
        assertEquals(0xFF3987E5, Palette.FLOW)
    }
}

class BatteryTest {
    @Test fun thresholdsLikeThePage() {
        assertEquals(BatteryTone.GOOD, Battery.tone(100.0))
        assertEquals(BatteryTone.GOOD, Battery.tone(31.0))
        assertEquals(BatteryTone.GOOD, Battery.tone(30.0))
        assertEquals(BatteryTone.WARN, Battery.tone(29.9))
        assertEquals(BatteryTone.WARN, Battery.tone(15.0))
        assertEquals(BatteryTone.CRIT, Battery.tone(14.9))
        assertEquals(BatteryTone.CRIT, Battery.tone(0.0))
        assertEquals(BatteryTone.NONE, Battery.tone(null))
        assertEquals(0xFF0CA30C, BatteryTone.GOOD.argb)
        assertEquals(0xFFFAB219, BatteryTone.WARN.argb)
        assertEquals(0xFFD03B3B, BatteryTone.CRIT.argb)
    }

    @Test fun bmsFirstThenVoltage() {
        assertEquals(Battery.Level(54.0, false), Battery.level(Telemetry(batteryPct = 54.0, voltage = 30.0)))
        assertEquals(Battery.Level(100.0, false), Battery.level(Telemetry(batteryPct = 140.0)))
        val est = Battery.level(Telemetry(voltage = 38.0))!!
        assertTrue(est.estimated)
        assertEquals(50.0, est.pct, 1e-9)
        assertNull(Battery.level(Telemetry()))
        assertEquals(0.0, Battery.socFromVoltage(29.0), 0.0)
        assertEquals(100.0, Battery.socFromVoltage(42.0), 0.0)
        assertEquals(85.0, Battery.socFromVoltage(40.5), 1e-9)
    }
}

class ModesTest {
    @Test fun names() {
        assertEquals("TAKEOFF", Modes.name(1))
        assertEquals("GLIDE", Modes.name(2))
        assertEquals("FLY", Modes.name(3))
        assertEquals("режим 0", Modes.name(0))
        assertNull(Modes.name(null))
        assertEquals(Palette.MODE_GLIDE, Modes.argb(2))
        assertEquals(Palette.MUTED, Modes.argb(null))
    }
}

class HealthTest {
    private val live = Telemetry(link = Link.CONNECTED, streaming = true)

    @Test fun staleAfterThreeSeconds() {
        assertEquals(Health.NO_PHONE, Health.of(live, null, 10_000))
        assertEquals(Health.LIVE, Health.of(live, 7_000, 10_000))
        assertEquals(Health.NO_PHONE, Health.of(live, 6_999, 10_000))
        assertTrue(Health.stale(null, 0))
        assertFalse(Health.stale(10_000, 10_000))
    }

    @Test fun idlePhoneAnswersOnlyHellos() {
        // no ride: the phone sends an IDLE snapshot per hello (every 5 s); it must not flip to «нет данных»
        val idle = Telemetry(link = Link.IDLE)
        assertEquals(Health.IDLE, Health.of(idle, 10_000, 14_999))
        assertEquals(Health.IDLE, Health.of(idle, 10_000, 22_000))
        assertEquals(Health.NO_PHONE, Health.of(idle, 10_000, 22_001))
        // the hello still goes out every 5 s meanwhile
        assertTrue(Hello.due(10_000, 10_000, 15_000))
    }

    @Test fun phoneStates() {
        val now = 50_000L
        assertEquals(Health.RECONNECTING, Health.of(live.copy(link = Link.RECONNECTING), now, now))
        assertEquals(Health.SCANNING, Health.of(live.copy(link = Link.SCANNING), now, now))
        assertEquals(Health.CONNECTING, Health.of(live.copy(link = Link.CONNECTING), now, now))
        assertEquals(Health.IDLE, Health.of(live.copy(link = Link.IDLE), now, now))
        assertEquals(Health.SILENT, Health.of(live.copy(streaming = false), now, now))
        assertEquals("руль молчит", Health.SILENT.label)
        assertEquals("переподключаюсь", Health.RECONNECTING.label)
        assertEquals("нет данных с телефона", Health.NO_PHONE.label)
        assertNull(Health.LIVE.label)
    }

    @Test fun dimming() {
        assertEquals(1f, Health.LIVE.alpha)
        assertTrue(Health.NO_PHONE.alpha < Health.SILENT.alpha)
        assertTrue(Health.SILENT.alpha < 1f)
        for (h in Health.entries) if (h != Health.LIVE) assertTrue(h.alpha < 1f)
    }
}

class FeedTest {
    @Test fun messagesUpdateRxAndLive() {
        val f0 = Feed()
        val f1 = f0.received(Wire.encode(Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = 12.0)), 1_000)!!
        assertEquals(1_000L, f1.rxAtMs)
        assertEquals(1_000L, f1.liveAtMs)
        assertEquals(12.0, f1.t.speedKmh!!, 0.0)
        val f2 = f1.received(Wire.encode(Telemetry(link = Link.RECONNECTING)), 2_000)!!
        assertEquals(2_000L, f2.rxAtMs)
        assertEquals(1_000L, f2.liveAtMs)
        assertNull(f2.received("garbage".toByteArray(), 3_000))
        assertNotNull(f2.received("{}".toByteArray(), 3_000))
    }
}

class FmtTest {
    @Test fun russianNumbers() {
        assertEquals("1 428,1", Fmt.num(1428.06, 1))
        assertEquals("0,0", Fmt.num(0.04, 1))
        assertEquals("−5,2", Fmt.num(-5.2, 1))
        assertEquals("0", Fmt.num(-0.2, 0))
        assertEquals("—", Fmt.num(null, 1))
        assertEquals("—", Fmt.num(Double.NaN, 1))
        assertEquals("12 345 678", Fmt.num(12_345_678.0, 0))
        assertEquals("472 Вт", Fmt.watts(-472.4))
        assertEquals("— Вт", Fmt.watts(null))
    }

    @Test fun speedAndUnits() {
        assertEquals("23" to ",4", Fmt.speedParts(23.44, "kmh"))
        assertEquals("29" to ",8", Fmt.speedParts(29.8, "kmh"))
        assertEquals("0" to ",0", Fmt.speedParts(0.0, "kmh"))
        assertEquals("—" to "", Fmt.speedParts(null, "kmh"))
        // 25.75 km/h = 16.0 mph, the FLY limit
        assertEquals("16" to ",0", Fmt.speedParts(25.75, "mph"))
        assertEquals("км/ч", Fmt.speedUnit("kmh"))
        assertEquals("mph", Fmt.speedUnit("mph"))
        assertEquals("mi", Fmt.distUnit("mph"))
        assertEquals(1.0, Fmt.dist(1.609344, "mph")!!, 1e-12)
        assertEquals("54 %", Fmt.pct(Battery.Level(54.0, false)))
        assertEquals("≈50 %", Fmt.pct(Battery.Level(50.0, true)))
        assertEquals("— %", Fmt.pct(null))
    }
}

class FlowTest {
    @Test fun likeThePage() {
        assertEquals(Flow.NONE, Flow.of(Telemetry()))
        assertEquals(Flow.DRIVE, Flow.of(Telemetry(currentA = 8.0, speedKmh = 20.0, powerW = 300.0)))
        assertEquals(Flow.REGEN, Flow.of(Telemetry(currentA = -3.0, speedKmh = 15.0, powerW = -110.0)))
        assertEquals(Flow.CHARGE, Flow.of(Telemetry(currentA = -1.9, speedKmh = 0.0)))
        assertEquals(Flow.INTO, Flow.of(Telemetry(powerW = -50.0)))
        assertEquals(Flow.IDLE, Flow.of(Telemetry(currentA = 0.1, speedKmh = 0.0, powerW = 4.0)))
        assertEquals(Flow.DRIVE, Flow.of(Telemetry(currentA = 0.7)))
        assertEquals(Palette.DRAW, Flow.DRIVE.tone)
        assertEquals(Palette.FLOW, Flow.REGEN.tone)
        // riding, the phone's state with its deadband: −0.2 A on letting go is coasting, not regen
        val coasting = Telemetry(currentA = -0.2, currentFastA = -0.3, powerW = -8.0, powerFastW = -11.0, speedKmh = 22.0)
        assertEquals(Flow.REGEN, Flow.of(coasting, fast = true))                     // an older phone: by the sign
        assertEquals(Flow.COAST, Flow.of(coasting.copy(powerState = PowerState.COAST), fast = true))
        assertEquals(Flow.REGEN, Flow.of(coasting.copy(powerState = PowerState.REGEN)))
        assertEquals(Flow.DRIVE, Flow.of(coasting.copy(powerState = PowerState.TRACTION)))
        assertEquals(Flow.CHARGE, Flow.of(Telemetry(currentA = -1.9, speedKmh = 0.0, powerState = PowerState.COAST)))   // standing: as before
        assertEquals(Palette.MUTED, Flow.COAST.tone)
    }
}

class TimersTest {
    @Test fun burnInStaysSmall() {
        val seen = HashSet<Pair<Float, Float>>()
        for (k in -20..40) {
            val (x, y) = BurnIn.offset(k)
            assertTrue(kotlin.math.abs(x) <= BurnIn.STEP_DP && kotlin.math.abs(y) <= BurnIn.STEP_DP)
            seen += x to y
        }
        assertEquals(9, seen.size)
        assertEquals(0f to 0f, BurnIn.offset(0))
    }

    @Test fun idleAndScreen() {
        val min5 = Idle.AFTER_MS
        assertFalse(Idle.expired(lastLiveMs = 1_000, sinceMs = 0, nowMs = 1_000 + min5))
        assertTrue(Idle.expired(lastLiveMs = 1_000, sinceMs = 0, nowMs = 1_001 + min5))
        // opened recently: not idle even without live data
        assertFalse(Idle.expired(lastLiveMs = null, sinceMs = 10_000, nowMs = 10_000 + min5))
        assertTrue(Idle.expired(lastLiveMs = null, sinceMs = 10_000, nowMs = 10_001 + min5))
        assertTrue(Idle.keepScreenOn(true, 1_000, 0, 2_000))
        assertFalse(Idle.keepScreenOn(false, 1_000, 0, 2_000))
        assertFalse(Idle.keepScreenOn(true, 1_000, 0, 2_000 + min5))
    }

    @Test fun theRideServiceOutlivesAPauseWithTheScooterOff() {
        val min5 = Idle.AFTER_MS
        val s = SessionIdle(0)
        assertFalse(s.expired(Health.LIVE, 1_000))
        // a 20-minute stop, scooter off: the phone keeps reconnecting, the service (and the overspeed buzz) stays
        for (k in 1..1_200) assertFalse(s.expired(Health.RECONNECTING, 1_000 + k * 1_000L))
        assertFalse(s.expired(Health.SILENT, 1_300_000))
        // «Отключить» on the phone: 5 minutes later the service goes
        assertFalse(s.expired(Health.IDLE, 1_300_000 + min5))
        assertTrue(s.expired(Health.IDLE, 1_300_001 + min5))
        // the phone gone (no messages) counts the same; started but never active: 5 minutes from the start
        val gone = SessionIdle(0)
        assertFalse(gone.expired(Health.CONNECTING, 10_000))
        assertTrue(gone.expired(Health.NO_PHONE, 10_001 + min5))
        assertTrue(SessionIdle(0).expired(Health.NO_PHONE, min5 + 1))
        assertFalse(SessionIdle(0).expired(Health.NO_PHONE, min5))
    }

    @Test fun helloPacing() {
        assertTrue(Hello.due(rxAtMs = null, lastHelloMs = null, nowMs = 0))
        // nothing comes: retry every 5 s
        assertFalse(Hello.due(null, 10_000, 14_999))
        assertTrue(Hello.due(null, 10_000, 15_000))
        // data flows: a reminder every 30 s
        assertFalse(Hello.due(20_000, 10_000, 20_500))
        assertTrue(Hello.due(39_000, 10_000, 40_000))
    }

    @Test fun rideStatus() {
        val live = Telemetry(link = Link.CONNECTED, streaming = true, batteryPct = 54.0, tripKm = 3.24)
        assertEquals("54 % · 3,2 км", RideStatus.text(live, Health.LIVE))
        assertEquals("переподключаюсь", RideStatus.text(live, Health.RECONNECTING))
        assertEquals("едем", RideStatus.text(Telemetry(), Health.LIVE))
    }
}

class DetailsTest {
    @Test fun rowsFromTelemetry() {
        val rows = detailRows(Samples.riding)
        val cells = rows.flatten().associate { it.label to it.value }
        assertEquals("37,9 В", cells["Напряжение"])
        assertEquals("1 428,1 км", cells["Пробег"])
        assertEquals("2,6 Вт·ч", cells["Возвращено · 6 %"])
        assertEquals("GLIDE", cells["Режим"])
        // a typed v5 custom field is not repeated; an unknown one is shown
        val t = Samples.riding.copy(extras = mapOf("Поездка с включения, км" to 3.6, "Код ошибки" to 37.0))
        val names = detailRows(t).flatten().map { it.label }
        assertTrue("Код ошибки" in names)
        assertFalse("Поездка с включения, км" in names)
    }
}
