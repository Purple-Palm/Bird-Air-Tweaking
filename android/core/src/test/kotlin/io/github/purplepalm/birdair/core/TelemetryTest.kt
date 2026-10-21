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

    /** FastPower, BatteryAdvisor and AppOdometer fed by the tracker from the frames themselves. */
    @Test fun fastPowerWarningsRangeOdometer() {
        // the same frames through a standalone FastPower: t → the outputs at that t
        val want = HashMap<Long, MutableList<FastPower.Sample>>()
        val fp = FastPower()
        for (e in RideLogs.ride(Repo.RIDE).events) when (e) {
            is ScooterEvent.Reset -> fp.reset()
            is ScooterEvent.Bms -> want.getOrPut(e.t) { ArrayList() }.add(fp.update(e.t, e.f.voltage, e.f.currentA, e.f.motor))
            else -> {}
        }
        val tr = TelemetryTracker(Repo.decoder())
        tr.epochOffsetMs = java.time.Instant.parse("2026-10-15T01:54:22.971Z").toEpochMilli()
        var seq = -1L
        var checked = 0
        var lastAt: Long? = null
        val (_, end) = replay(Repo.RIDE, tr) { s ->
            assertTrue(s.seq >= seq)
            seq = s.seq
            val at = s.powerAtMs
            if (at != null && at != lastAt) {
                lastAt = at
                val w = want.getValue(at).last()
                assertEquals(w.currentA, s.currentFastA)
                assertEquals(w.powerW, s.powerFastW)
                if (s.powerFastW != 0.0) assertEquals(s.voltage!! * s.currentFastA!!, s.powerFastW!!, 1e-9)
                checked++
            }
        }
        assertTrue(checked > 7000, "$checked")
        val s = tr.snapshot(end)
        assertTrue(s.seq > 40000, "${s.seq}")
        assertEquals(listOf(BatteryWarning("battery_low", WarningLevel.CRITICAL, "Сейчас отключится", s.warnings[0].value)), s.warnings)
        assertEquals("recent", s.range!!.basis)
        assertTrue(s.range!!.km < 0.2, "${s.range}")
        assertTrue(s.range!!.whPerKm in 9.0..13.0, "${s.range}")
        assertEquals(1433.3, s.appOdometerKm!!, 1e-9)
        val st = tr.odometerState!!
        assertEquals(Triple(1433.3, 1424.8, 8.5), Triple(st.totalKm, st.baseKm, st.tripKm))
        assertTrue(st.wallMs!! > tr.epochOffsetMs!! + 2_000_000)
        // the odometer survives tracker.reset() and comes back through odometerState
        val state = tr.odometerState
        tr.reset()
        val r = tr.snapshot(end)
        assertNull(r.currentFastA)
        assertTrue(r.warnings.isEmpty())
        assertNull(r.range)
        assertEquals(1433.3, r.appOdometerKm!!, 1e-9)
        assertTrue(r.seq > s.seq)
        val tr2 = TelemetryTracker(Repo.decoder())
        tr2.odometerState = state
        assertEquals(1433.3, tr2.snapshot(0).appOdometerKm!!, 1e-9)
        tr2.onNotification(Uuids.SERVICE, Uuids.CHAR, MODE_FRAME, 1)   // a frame with trip 0.0 at 1424.8: a new power-on, +0
        assertEquals(1433.3, tr2.snapshot(1).appOdometerKm!!, 1e-9)
    }

    /** «Задать» / «Взять с руля» mid-ride through the tracker: the app odometer moves, the range does not. */
    @Test fun odometerCorrectionLeavesTheRange() {
        fun ranges(by: Double?): Pair<List<RangeEstimate?>, Double?> {
            val tr = TelemetryTracker(Repo.decoder())
            tr.link = Link.CONNECTED
            val es = Repo.log(Repo.RIDE).entries
            val out = ArrayList<RangeEstimate?>()
            for ((k, e) in es.withIndex()) {
                if (by != null && k == es.size / 3) tr.odometerState = tr.odometerState!!.let { it.copy(totalKm = it.totalKm + by) }
                if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) tr.restartStreams()
                if (e.k != "rx" || e.b == null) continue
                tr.onNotification(e.s!!, e.u!!, e.b, e.t)
                out.add(tr.snapshot(e.t).range)
            }
            return out to tr.snapshot(0).appOdometerKm
        }
        val (base, odo) = ranges(null)
        assertEquals(1433.3, odo!!, 1e-9)
        for (by in listOf(8.3, -8.3)) {
            val (fixed, odo2) = ranges(by)
            assertEquals(1433.3 + by, odo2!!, 1e-9)
            assertEquals(base, fixed, "$by")
        }
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

    /** The typed slots are found by the decoder's field names: a rename in decoders/current.json must fail here. */
    @Test fun customNamesAreInTheDecoder() {
        val fields = Repo.decoder().spec.fields.filter { it.key == "custom" }
        for (name in TelemetryTracker.CUSTOM.keys) {
            val f = fields.singleOrNull { it.name == name }
            assertNotNull(f, "decoders/current.json has no custom field «$name»: update TelemetryTracker.CUSTOM")
            assertEquals(TelemetryTracker.CUSTOM[name], TelemetryTracker.role(f))
        }
        // a verified field renamed by a later decoder keeps its slot; an unverified one does not
        val trip = fields.single { it.name == "Поездка с включения, км" }
        assertEquals("trip_km", TelemetryTracker.role(trip.copy(name = "Поездка, км")))
        val motor = fields.single { it.name == "Мотор тянет (бит)" }
        assertNull(TelemetryTracker.role(motor.copy(name = "Тормоз (бит)")))
        val tr = TelemetryTracker(Repo.decoder().let { d -> Decoder(d.spec.copy(fields = d.spec.fields.map { if (it.name == trip.name) it.copy(name = "Поездка, км") else it })) })
        tr.onNotification(Uuids.SERVICE, Uuids.CHAR, MODE_FRAME, 0)
        assertNotNull(tr.snapshot(0).tripKm)
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
