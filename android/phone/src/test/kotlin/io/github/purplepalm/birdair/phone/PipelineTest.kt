package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.ScooterFrames
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.FrameAssembler
import io.github.purplepalm.birdair.core.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** The screen and the watch get every decoded value when it comes, not on a timer. */
class PipelineTest {
    private val rx by lazy { TestLogs.rx(TestLogs.RIDE) }

    /** Times of the notifications after which the assembler emitted a BMS frame (3A 18 20 10). */
    private fun bmsTimes(list: List<Rx>): Set<Long> {
        val a = FrameAssembler()
        val out = HashSet<Long>()
        for (e in list) if (Uuids.full(e.u) == Uuids.CHAR) for (f in a.feed(e.b)) if (ScooterFrames.bms(f) != null) out.add(e.t)
        return out
    }

    @Test fun everyDecodedFramePublishedAtOnce() {
        val part = rx.subList(0, 6000)
        val tracker = TelemetryTracker(TestLogs.decoder()).apply { link = Link.CONNECTED }
        var now = 0L
        // a heartbeat that never comes within the test: only the notifications themselves can publish
        val p = LivePipeline(tracker, { now }, { true }, heartbeatMs = 3_600_000)
        val fresh = ArrayList<Telemetry>()
        p.onPublish = { t, isNew -> if (isNew) fresh.add(t) }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        scope.launch { p.run() }
        try {
            var changed = 0
            var last = tracker.snapshot(0).seq
            for (e in part) {
                now = e.t
                p.onRx(e.s, e.u, e.b, e.t)
                val seq = tracker.snapshot(now).seq
                if (seq != last) {
                    changed++
                    last = seq
                    // published before onRx returned: nothing waits for a tick
                    assertEquals("seq published at t=${e.t}", seq, p.telemetry.value.seq)
                    assertEquals(e.t, p.telemetry.value.t)
                }
            }
            assertTrue("the ride has data", changed > 3000)
            assertEquals("one fresh publish per notification that brought a value", changed, fresh.size)
            // every BMS frame reached the screen: one new fast-power time each
            val bms = bmsTimes(part)
            assertTrue(bms.size > 500)
            assertEquals(bms, fresh.mapNotNull { it.powerAtMs }.toSet())
            assertTrue("fast power is there", fresh.any { it.powerFastW != null && it.powerFastW!! > 50 })
        } finally { scope.cancel() }
    }

    /** On real threads: from onRx to the published value well under a tick of the old 250 ms timer. */
    @Test fun latencyOnRealThreads() {
        val part = rx.subList(2000, 2600)
        val tracker = TelemetryTracker(TestLogs.decoder()).apply { link = Link.CONNECTED }
        val now = AtomicLong()
        val p = LivePipeline(tracker, { now.get() }, { true }, heartbeatMs = 3_600_000)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { p.run() }
        val lat = ArrayList<Long>()
        try {
            Thread.sleep(50)
            var last = tracker.snapshot(0).seq
            for (e in part) {
                now.set(e.t)
                val t0 = System.nanoTime()
                p.onRx(e.s, e.u, e.b, e.t)
                val seq = tracker.snapshot(e.t).seq
                if (seq == last) continue
                last = seq
                while (p.telemetry.value.seq < seq && System.nanoTime() - t0 < 2_000_000_000L) Thread.onSpinWait()
                assertTrue("published within 2 s", p.telemetry.value.seq >= seq)
                lat.add((System.nanoTime() - t0) / 1_000_000)
            }
        } finally { scope.cancel() }
        lat.sort()
        assertTrue(lat.size > 200)
        val median = lat[lat.size / 2]
        println("onRx → published: median $median ms, p95 ${lat[lat.size * 95 / 100]} ms, max ${lat.last()} ms over ${lat.size} values")
        assertTrue("median latency $median ms", median < 50)
    }

    /** Nothing new: the heartbeat still publishes (streaming turns false, the watch hears from the phone). */
    @Test fun heartbeatWhenIdle() {
        val tracker = TelemetryTracker(TestLogs.decoder())
        val p = LivePipeline(tracker, { 0L }, { true }, heartbeatMs = 30)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { p.run() }
        try {
            Thread.sleep(400)
            assertTrue("published ${p.published} times in 400 ms", p.published in 5..20)
        } finally { scope.cancel() }
    }

    @Test fun fastPowerAndItsScale() {
        val t = Telemetry(powerW = 300.0, currentA = 8.0, powerFastW = 420.0, currentFastA = 11.0, powerAtMs = 1)
        assertEquals(420.0, Power.watts(t, true)!!, 0.0)
        assertEquals(300.0, Power.watts(t, false)!!, 0.0)
        assertEquals(11.0, Power.amps(t, true)!!, 0.0)
        assertEquals(300.0, Power.watts(t.copy(powerFastW = null), true)!!, 0.0)
        val s = PowerScale()
        assertEquals(462.0, s.update(t, true), 1e-9)
        assertEquals("same frame: no second step", 462.0, s.update(t, true), 1e-9)
        assertEquals(462.0 * 0.999, s.update(t.copy(powerAtMs = 2, powerFastW = 10.0), true), 1e-9)
        s.reset()
        assertEquals(300.0, s.peakW, 0.0)
        assertEquals("BMS power", 330.0, s.update(t.copy(powerAtMs = 3), false), 1e-9)
        // the published snapshot carries the scale of what the bar shows
        val tracker = TelemetryTracker(TestLogs.decoder())
        val p = LivePipeline(tracker, { 0L }, { true })
        assertEquals(TelemetryTracker.PEAK_MIN, p.publishNow().peakW, 0.0)
    }
}
