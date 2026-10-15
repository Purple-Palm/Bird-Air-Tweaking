package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryTest {
    /** Feeds a log the way the page does: rx notifications, a new stream after each «Найдено сервисов». */
    private fun replay(path: String, tr: TelemetryTracker = TelemetryTracker(Repo.decoder()), each: (Telemetry) -> Unit = {}): Pair<TelemetryTracker, Long> {
        tr.link = Link.CONNECTED
        var last = 0L
        for (e in Repo.log(path).entries) {
            if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) tr.restartStreams()
            if (e.k != "rx" || e.b == null) continue
            tr.onNotification(e.s!!, e.u!!, e.b, e.t)
            each(tr.snapshot(e.t))
            last = e.t
        }
        return tr to last
    }

    /** logs/20261014-185422-log.json.gz: the 40-minute ride, 8.5 km, battery 75 → 2 %. */
    @Test fun rideLog() {
        var firstOdo: Double? = null
        var lastOdo: Double? = null
        var firstBat: Double? = null
        var lastBat: Double? = null
        var maxSpeed = 0.0
        var minPower = 0.0
        var maxPower = 0.0
        var motor = false
        val modes = HashSet<Int>()
        val (tr, end) = replay(Repo.RIDE) { s ->
            s.odometerKm?.let { if (firstOdo == null) firstOdo = it; lastOdo = it }
            s.batteryPct?.let { if (firstBat == null) firstBat = it; lastBat = it }
            s.speedKmh?.let { maxSpeed = maxOf(maxSpeed, it) }
            s.powerW?.let { minPower = minOf(minPower, it); maxPower = maxOf(maxPower, it) }
            s.mode?.let { modes += it }
            if (s.motorOn == true) motor = true
        }
        assertEquals(1424.8, firstOdo!!, 1e-6)
        assertEquals(1433.3, lastOdo!!, 1e-6)
        assertEquals(75.0, firstBat)
        assertEquals(2.0, lastBat)
        assertEquals(29.8, maxSpeed, 1e-6)
        val s = tr.snapshot(end)
        assertEquals(103.1, s.whOut, 1.0)
        assertEquals(5.88, s.whIn, 0.3)
        assertEquals(97.2, s.whOut - s.whIn, 1.0)
        assertTrue(minPower < -150 && maxPower > 500, "power $minPower…$maxPower")
        assertTrue(s.peakW >= 300.0)
        assertTrue(motor)
        assertTrue(3 in modes)
        assertEquals(4363.0, s.fullMah)
        assertEquals(8.5, s.odometerKm!! - firstOdo!!, 0.05)
        assertTrue(s.streaming)
        assertEquals(Link.CONNECTED, s.link)
        val st = tr.frameStats
        assertTrue(st.frames > 40000 && st.recovered > 0, st.toString())
    }

    /** logs/20261014-174148-log.json: standing still after +MODE=3. */
    @Test fun standstillLog() {
        val modes = ArrayList<Int>()
        val (tr, end) = replay(Repo.STANDSTILL) { s -> s.mode?.let { if (modes.lastOrNull() != it) modes += it } }
        val s = tr.snapshot(end)
        assertEquals(39.84, s.voltage!!, 0.1)
        assertEquals(75.0, s.batteryPct)
        assertEquals(0.0, s.speedKmh)
        assertEquals(false, s.motorOn)
        assertEquals(s.voltage!! * s.currentA!!, s.powerW!!, 1e-9)
        assertEquals(1424.8, s.odometerKm!!, 1e-6)
        assertTrue(s.temp1C!! in 15.0..35.0)
        assertNotNull(s.temp2C)
        assertNotNull(s.tripKm)
        assertNotNull(s.avgSpeedKmh)
        assertNotNull(s.remainingMah)
        assertEquals(s.remainingMah, s.extras["Остаток в батарее, мА·ч"])
        assertEquals(listOf(3, 2, 1), modes.take(3))                // +MODE=3, +MODE=2, +MODE=1 in the log
        val st = tr.frameStats
        assertEquals(284 to 16, st.frames to st.recovered)
        assertEquals(0, st.badSum)
    }

    @Test fun streamingMeansAFrameWithinThreeSeconds() {
        val tr = TelemetryTracker(Repo.decoder())
        assertFalse(tr.snapshot(0).streaming)
        tr.onNotification("ffe0", "ffe1", VOLT_FRAME, 10_000)
        assertTrue(tr.snapshot(10_000).streaming)
        assertTrue(tr.snapshot(13_000).streaming)
        assertFalse(tr.snapshot(13_001).streaming)
        // a frame no field reads still counts as the dash talking
        tr.onNotification("ffe0", "ffe1", Frames.make(0x20, 0x01, ByteArray(3)), 20_000)
        assertTrue(tr.snapshot(21_000).streaming)
        // echo text alone does not
        tr.onNotification("ffe0", "ffe1", ascii("+SVGT=0"), 30_000)
        assertFalse(tr.snapshot(30_000).streaming)
    }

    private fun rawDecoder(vararg f: FieldSpec) = Decoder(DecoderSpec(fields = f.toList()))

    @Test fun energyAndPeak() {
        // raw packets: voltage u8, current s8 (A)
        val tr = TelemetryTracker(rawDecoder(FieldSpec("voltage", offset = 0), FieldSpec("current", offset = 1, type = "s8")))
        val pk = { v: Int, i: Int -> byteArrayOf(v.toByte(), i.toByte()) }
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, pk(40, 10), 0)        // 400 W, first point: nothing integrated yet
        assertEquals(0.0, tr.snapshot(0).whOut)
        assertEquals(440.0, tr.snapshot(0).peakW, 1e-9)
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, pk(40, 10), 1000)
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, pk(40, 10), 6000)     // a gap: dt clamped to 2 s
        assertEquals(400.0 * 3 / 3600, tr.snapshot(6000).whOut, 1e-9)
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, pk(40, -5), 7000)     // regen: −200 W for 1 s
        val s = tr.snapshot(7000)
        assertEquals(200.0 / 3600, s.whIn, 1e-9)
        assertEquals(-200.0, s.powerW)
        assertEquals(440.0 * 0.999, s.peakW, 1e-9)                       // 440 held by 400 W samples, then decays
        tr.resetEnergy()
        assertEquals(0.0, tr.snapshot(7000).whOut)
        tr.reset()
        val r = tr.snapshot(8000)
        assertNull(r.voltage)
        assertEquals(300.0, r.peakW)
    }

    @Test fun peakNeverBelow300() {
        val tr = TelemetryTracker(rawDecoder(FieldSpec("power", offset = 0)))
        tr.onNotification("ffe0", "ffe1", byteArrayOf(50), 0)
        assertEquals(300.0, tr.snapshot(0).peakW)
        assertEquals(50.0, tr.snapshot(0).powerW)
    }

    @Test fun keysAndUnits() {
        val tr = TelemetryTracker(rawDecoder(
            FieldSpec("speed_mph", offset = 0),
            FieldSpec("odometer_mi", offset = 1),
            FieldSpec("temp", offset = 2, type = "s8"),
            FieldSpec("custom", name = "Мотор тянет (бит)", offset = 3, type = "bit0"),
            FieldSpec("custom", name = "Что-то своё", offset = 3),
            FieldSpec("error", offset = 3),
            FieldSpec("battery_pct", uuid = "fff1", offset = 0),         // another characteristic
        ))
        tr.units = "mph"
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, byteArrayOf(10, 100, -5, 1), 5)
        val s = tr.snapshot(5)
        assertEquals("mph", s.units)
        assertEquals(16.09344, s.speedKmh!!, 1e-9)
        assertEquals(160.9344, s.odometerKm!!, 1e-9)
        assertEquals(-5.0, s.temp1C)
        assertEquals(true, s.motorOn)
        assertEquals(1.0, s.extras["Что-то своё"])
        assertEquals(1.0, s.extras["Код ошибки"])
        assertNull(s.batteryPct)
        assertNull(s.powerW)
        // framing off: raw packets, so no frame assembly is needed for data
        assertTrue(s.streaming)
        tr.setDecoder(Repo.decoder())
        assertNull(tr.snapshot(6).speedKmh)
    }

    @Test fun jsonLines() {
        val tr = TelemetryTracker(Decoder(DecoderSpec(json = true)))
        tr.onNotification("ffe0", "ffe1", ascii("{\"v\":40.5,\"i\":2,"), 0)
        tr.onNotification("ffe0", "ffe1", ascii("\"mph\":10,\"x\":1}\n{\"soc\":"), 10)
        val s = tr.snapshot(10)
        assertEquals(40.5, s.voltage)
        assertEquals(81.0, s.powerW)
        assertEquals(16.09344, s.speedKmh!!, 1e-9)
        assertNull(s.batteryPct)
        tr.onNotification("ffe0", "ffe1", ascii("88}"), 20)
        assertEquals(88.0, tr.snapshot(20).batteryPct)
    }

    @Test fun readsAreRawOnly() {
        val tr = TelemetryTracker(rawDecoder(FieldSpec("battery_pct", offset = 0)))
        tr.onRead("180f", "2a19", byteArrayOf(64), 0)
        assertEquals(64.0, tr.snapshot(0).batteryPct)
        val framed = TelemetryTracker(Repo.decoder())
        framed.onRead("ffe0", "ffe1", VOLT_FRAME, 0)
        assertNull(framed.snapshot(0).voltage)
    }
}
