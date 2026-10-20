package io.github.purplepalm.birdair.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {
    private val full = Telemetry(
        t = 123_456_789, link = Link.CONNECTED, streaming = true, units = "mph", speedKmh = 24.3, mode = 3,
        batteryPct = 61.0, voltage = 38.912, currentA = -6.25, powerW = -243.2, odometerKm = 1430.1, tripKm = 5.4,
        avgSpeedKmh = 18.7, remainingMah = 2650.0, fullMah = 4363.0, temp1C = 37.0, temp2C = 41.0, motorOn = false,
        whOut = 61.5, whIn = 3.25, peakW = 612.0, extras = mapOf("Поездка с включения, км" to 5.4, "Мотор тянет (бит)" to 0.0),
    )

    @Test fun roundTrip() {
        assertEquals(full, Wire.decode(Wire.encode(full)))
        assertEquals(Telemetry(), Wire.decode(Wire.encode(Telemetry())))
        val live = TelemetryTracker(Repo.decoder()).apply { onNotification("ffe0", "ffe1", VOLT_FRAME + MODE_FRAME, 1) }.snapshot(2)
        assertEquals(live, Wire.decode(Wire.encode(live)))
    }

    @Test fun newFieldsRoundTrip() {
        val t = full.copy(
            currentFastA = 12.25, powerFastW = 401.5, powerAtMs = 123_456_700, seq = 99,
            warnings = listOf(BatteryWarning("battery_low", WarningLevel.CRITICAL, "Сейчас отключится", 31.9), BatteryWarning("battery_hot", WarningLevel.WARNING, "Батарея греется", 51.0)),
            range = RangeEstimate(1.5, 1.5 / 1.609344, 28.0, 18.7, "recent"), appOdometerKm = 1459.4,
        )
        assertEquals(t, Wire.decode(Wire.encode(t)))
        // an older watch app ignores what it does not know, a newer one reads an older phone's message
        assertEquals(full, Wire.decode(Wire.encode(full)))
        assertTrue(Wire.encode(t).size < 900)
    }

    @Test fun compact() {
        assertEquals("{}", String(Wire.encode(Telemetry()), Charsets.UTF_8))
        val n = Wire.encode(full).size
        assertTrue(n < 600, "$n bytes")
        assertTrue(!String(Wire.encode(full), Charsets.UTF_8).contains(' '.toString() + '"'))
    }

    @Test fun garbageIsNull() {
        for (s in listOf("", "null", "[]", "42", "\"x\"", "{", "{\"t\":\"later\"}", "{\"speedKmh\":[1]}"))
            assertNull(Wire.decode(s.toByteArray()), s)
        val rnd = Random(1)
        repeat(200) {
            val b = ByteArray(rnd.nextInt(2, 64)) { rnd.nextInt(256).toByte() }
            assertNull(Wire.decode(b))
            b[0] = '{'.code.toByte(); b[1] = 1                // an object that breaks right away
            assertNull(Wire.decode(b))
        }
    }

    @Test fun newerSenderStillParses() {
        val t = Wire.decode("{\"speedKmh\":12.5,\"link\":\"PAIRING\",\"futureField\":{\"a\":1}}".toByteArray())
        assertEquals(12.5, t?.speedKmh)
        assertEquals(Link.IDLE, t?.link)
    }

    @Test fun paths() {
        assertTrue(Wire.PATH.startsWith("/birdair/"))
        assertTrue(Wire.HELLO.startsWith("/birdair/"))
    }
}
