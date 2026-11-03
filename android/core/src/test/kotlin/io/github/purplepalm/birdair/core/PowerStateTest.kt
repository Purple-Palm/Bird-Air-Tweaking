package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerStateTest {
    @Test fun deadbandAndHysteresis() {
        val ps = PowerStateTracker()
        var t = 0L
        fun step(i: Double, kmh: Double = 20.0): PowerState { t += 300; return ps.update(t, i, kmh) }
        assertNull(ps.state)
        assertEquals(PowerState.COAST, step(-0.2))
        assertEquals(PowerState.COAST, step(-0.3))                 // letting go of the throttle: coasting
        assertEquals(PowerState.COAST, step(-1.2))                 // one frame is not braking yet
        assertEquals(PowerState.REGEN, step(-1.5))
        assertEquals(PowerState.REGEN, step(-0.6))                 // between −1.0 and −0.5: still braking
        for (k in 0..3) assertEquals(PowerState.REGEN, step(-0.2))  // above −0.5 A: 0, 0.3, 0.6, 0.9 s …
        assertEquals(PowerState.COAST, step(-0.2))                 // … 1.2 s
        assertEquals(PowerState.COAST, step(0.7))
        assertEquals(PowerState.TRACTION, step(0.8))
        assertEquals(PowerState.TRACTION, step(0.4))               // above 0.3 A: still pulling
        for (k in 0..3) assertEquals(PowerState.TRACTION, step(0.2))
        assertEquals(PowerState.COAST, step(0.2))
        assertEquals(PowerState.COAST, step(-3.0, 5.0))            // below 6 km/h braking is not regen (< 1 A there)
        assertEquals(PowerState.COAST, step(-3.0, 5.0))
        step(-3.0); assertEquals(PowerState.REGEN, step(-3.0))
        step(2.0); assertEquals(PowerState.TRACTION, step(2.0))    // straight from braking to pulling
        t += 3000
        assertEquals(PowerState.TRACTION, step(5.0))               // a gap: from the start, by the frame alone
        assertEquals(PowerState.COAST, PowerStateTracker().update(0, 0.5, 20.0))
        assertEquals(PowerState.REGEN, PowerStateTracker().update(0, -1.0, 6.0))
        ps.reset()
        assertNull(ps.state)
    }

    /** 20.10 through FastPower and the tracker: every frame at −0.15…−0.5 A was «рекуп.» (the old −0.15 A threshold), 162
     *  of them at ≥ 6 km/h; now 36 are, the tails of real brakings (the 1 s to leave), and braking at ≥ 3 A is regen
     *  but for each braking's first frame (257 of 283). */
    @Test fun coastingIsNotRegen() {
        val fast = FastPower()
        val ps = PowerStateTracker()
        var speed = 0.0
        var weak = 0; var weakRegen = 0; var hard = 0; var hardRegen = 0
        for (e in RideLogs.ride(RideLogs.TO_ZERO).events) when (e) {
            is ScooterEvent.Reset -> { fast.reset(); ps.reset() }
            is ScooterEvent.Motion -> speed = e.f.speedKmh
            is ScooterEvent.Bms -> {
                val i = fast.update(e.t, e.f.voltage, e.f.currentA, e.f.motor).currentA
                val s = ps.update(e.t, i, speed)
                if (speed < 6) continue
                if (i < -0.15 && i > -0.5) { weak++; if (s == PowerState.REGEN) weakRegen++ }
                if (i <= -3.0) { hard++; if (s == PowerState.REGEN) hardRegen++ }
            }
        }
        assertTrue(weak > 100 && hard > 200, "$weak $hard")
        assertTrue(weakRegen < 0.3 * weak, "$weakRegen of $weak")
        assertTrue(hardRegen > 0.85 * hard, "$hardRegen of $hard")
    }

    @Test fun inTheSnapshot() {
        val tr = TelemetryTracker(Repo.decoder())
        assertNull(tr.snapshot(0).powerState)
        val log = Repo.log(Repo.RIDE)
        val seen = HashSet<PowerState>()
        for ((k, e) in log.entries.withIndex()) {
            if (e.k == "rx" && e.b != null) tr.onNotification(e.s!!, e.u!!, e.b, e.t)
            if (k % 10 == 0) tr.snapshot(e.t).powerState?.let { seen.add(it) }
        }
        assertEquals(PowerState.entries.toSet(), seen)
        tr.restartStreams()
        assertNull(tr.snapshot(log.entries.last().t).powerState)
        // an older watch knows no powerState: it reads the rest of the message
        val t = Telemetry(speedKmh = 20.0, powerState = PowerState.COAST)
        assertEquals(PowerState.COAST, Wire.decode(Wire.encode(t))!!.powerState)
        assertNull(Wire.decode("{\"speedKmh\":20.0,\"powerState\":\"SAILING\"}".toByteArray())!!.powerState)
    }
}
