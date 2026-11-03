package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ScooterFramesTest {
    /** 20.10: the last BMS frame before it went silent, and the controller's last frame after it (tests/test_bird_frames.py). */
    private val lastBms = hex("3A 18 20 10 01 22 04 2D 32 0C 7A 15 E2 CB 00 60 0F 05 6E 00 F8 03 0D 0A")
    private val noBms = hex("3A 17 00 0D 00 BD 00 7D 26 0B 00 60 00 39 36 01 01 60 02 0D 0A")

    @Test fun statusBytes() {
        assertTrue(Frames.isValid(lastBms))
        val b = assertNotNull(ScooterFrames.bms(lastBms))
        assertEquals(listOf(0x01, 0x22, 0x04, 110), listOf(b.b4, b.b5, b.b6, b.b18))
        assertEquals(31.244, b.voltage, 1e-9)
        assertEquals(7.659, b.currentA, 1e-9)
        assertEquals(listOf(45, 50, 203, 3936, 5), listOf(b.temp1C, b.temp2C, b.remainingMah, b.fullMah, b.pct))
        assertTrue(b.motor && b.underVoltage && b.hotChargeLatch)
        assertFalse(b.charger || b.overVoltageWarn || b.overVoltageCut)
        val m = assertNotNull(ScooterFrames.motion(noBms))
        assertEquals(MotionFrame.CODE_NO_BMS, m.code)
        assertEquals(12.5, m.speedKmh, 1e-9)
        assertEquals(9.6, m.tripKm, 1e-9)
        assertEquals(1464.6, m.odometerKm, 1e-9)
        assertEquals(0, ScooterFrames.motion(MODE_FRAME)!!.code)
        // the status bytes have defaults: the 8-field frame still builds, all bits clear
        val old = BmsFrame(38.0, 1.0, false, 20, 20, 2000, 4000, 50)
        assertEquals(listOf(0, 0, 0, 0), listOf(old.b4, old.b5, old.b6, old.b18))
    }

    /** Every frame of the ride logs: the same status bytes and codes as tools/bird_frames.py. */
    @Test fun sameStatusBytesAsPython() {
        Py.assume()
        for (path in RideLogs.ALL) {
            val bits = HashMap<String, Int>()
            val codes = HashMap<String, Int>()
            for (e in RideLogs.ride(path).events) when (e) {
                is ScooterEvent.Bms -> e.f.run { bits.merge("$b4 $b5 $b6 $b18", 1, Int::plus) }
                is ScooterEvent.Motion -> codes.merge(e.f.code.toString(), 1, Int::plus)
                is ScooterEvent.Reset -> {}
            }
            val py = RideLogs.python.getValue(path)
            assertEquals(py["bits"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }, bits, path)
            assertEquals(py["codes"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }, codes, path)
        }
        // the under-voltage flag: on 20.10 only, in its last 2.4 s of BMS frames
        val uv = RideLogs.ALL.associateWith { p -> RideLogs.ride(p).events.filter { it is ScooterEvent.Bms && it.f.underVoltage }.map { it.t } }
        assertEquals(listOf(RideLogs.TO_ZERO), uv.filterValues { it.isNotEmpty() }.keys.toList())
        assertTrue(uv.getValue(RideLogs.TO_ZERO).all { it in 1_810_000..RideLogs.CUTOFF_T.getValue(RideLogs.TO_ZERO) })
    }
}
