package io.github.purplepalm.birdair.core

/**
 * The two controller frames FastPower, BatteryAdvisor and AppOdometer read, with the offsets checked
 * against the logs (docs/bird-air-protocol.md; tools/bird_frames.py does the same, number for number).
 * Offsets count from the 3A.
 */
object ScooterFrames {
    /** "3A 18 20 10": BMS, ~3.2 per second. */
    const val BMS_HEAD = "3A 18 20 10"
    /** "3A 17 00 0D": speed, mode, trip and odometer, ~15 per second. */
    const val MOTION_HEAD = "3A 17 00 0D"

    private fun u(f: ByteArray, i: Int) = f[i].toInt() and 0xFF
    private fun u16le(f: ByteArray, i: Int) = u(f, i) or (u(f, i + 1) shl 8)
    private fun u16be(f: ByteArray, i: Int) = (u(f, i) shl 8) or u(f, i + 1)

    private fun head(f: ByteArray, b1: Int, b2: Int, b3: Int) =
        f[0].toInt() == Frames.COLON && u(f, 1) == b1 && u(f, 2) == b2 && u(f, 3) == b3

    /** A 3A 18 20 10 frame (with its 3A), or null. */
    fun bms(f: ByteArray): BmsFrame? {
        if (f.size < 22 || !head(f, 0x18, 0x20, 0x10)) return null
        var rawI = u16le(f, 11)
        if (rawI >= 0x8000) rawI -= 0x10000
        return BmsFrame(
            voltage = u16le(f, 9) * 0.001, currentA = rawI * -0.001, motor = ((u(f, 5) shr 5) and 1) == 1,
            temp1C = u(f, 7), temp2C = u(f, 8), remainingMah = u16le(f, 13), fullMah = u16le(f, 15), pct = u(f, 17),
            b4 = u(f, 4), b5 = u(f, 5), b6 = u(f, 6), b18 = u(f, 18),
        )
    }

    /** A 3A 17 00 0D frame (with its 3A), or null. */
    fun motion(f: ByteArray): MotionFrame? {
        if (f.size < 19 || !head(f, 0x17, 0x00, 0x0D)) return null
        return MotionFrame(
            speedKmh = u16be(f, 6) * 0.1, avgSpeedKmh = u(f, 5) * 0.1, mode = u(f, 9) and 3, tripKm = u16be(f, 10) * 0.1,
            odometerKm = ((u(f, 12) shl 16) or (u(f, 13) shl 8) or u(f, 14)) * 0.1, code = u(f, 8),
        )
    }
}

/**
 * 3A 18 20 10: [voltage] V (9–10 u16le × 0.001), [currentA] A + traction / − regen and charge (11–12 s16le mA,
 * sign flipped), [motor] bit 0x20 of byte 5, [temp1C] / [temp2C] bytes 7 and 8 (°C, unverified), [remainingMah]
 * 13–14, [fullMah] 15–16 (u16le), [pct] byte 17.
 *
 * The status bytes raw ([b4], [b5], [b6], [b18]; meaning from the logs, docs/bird-air-protocol.md):
 *  - [b4]: 0x01 discharge allowed?, 0x02 charge allowed (cleared by the over-voltage and hot-charge
 *    protections), 0x40 charger connected;
 *  - [b5]: 0x80 charging, 0x20 the motor bit, 0x08 over-voltage warning (from 42.23 V), 0x04 over-voltage
 *    protection, 0x02 under-voltage: the BMS is about to cut the pack (20.10: 2.4 s before it went silent);
 *  - [b6]: 0x08 current into a pack at T2 ≥ 46 °C, 0x04 hot-charge latch (charging waits until ~37 / 39 °C),
 *    0x80 discharge over-temperature (15.10: 0x84 at the thermal cutoff);
 *  - [b18]: grows by one per charge (105 → 110 over 14–20.10): a cycle counter?
 */
data class BmsFrame(
    val voltage: Double,
    val currentA: Double,
    val motor: Boolean,
    val temp1C: Int,
    val temp2C: Int,
    val remainingMah: Int,
    val fullMah: Int,
    val pct: Int,
    val b4: Int = 0,
    val b5: Int = 0,
    val b6: Int = 0,
    val b18: Int = 0,
) {
    /** b5 0x02: the BMS is cutting the pack off (under-voltage). */
    val underVoltage: Boolean get() = b5 and UNDER_VOLTAGE != 0
    /** b5 0x08: over-voltage warning (regen on a full pack). */
    val overVoltageWarn: Boolean get() = b5 and OVER_VOLTAGE_WARN != 0
    /** b5 0x04: over-voltage protection, regen cut. */
    val overVoltageCut: Boolean get() = b5 and OVER_VOLTAGE_CUT != 0
    /** b4 0x40: the charger is plugged in. */
    val charger: Boolean get() = b4 and CHARGER != 0
    /** b6 0x04: too hot to charge, charging waits for the pack to cool. */
    val hotChargeLatch: Boolean get() = b6 and HOT_CHARGE != 0

    companion object {
        const val CHARGER = 0x40
        const val UNDER_VOLTAGE = 0x02
        const val OVER_VOLTAGE_WARN = 0x08
        const val OVER_VOLTAGE_CUT = 0x04
        const val HOT_CHARGE = 0x04
    }
}

/**
 * 3A 17 00 0D: speed 6–7 u16be, average since power-on byte 5, mode byte 9 & 3, trip since power-on 10–11 u16be, odometer
 * 12–14 u24be (× 0.1). [code] byte 8, raw: 0 in every frame of the logs but two runs — 0x26 in the 5 s between the BMS
 * going silent and the controller (20.10), 0x21 in 201 frames over 12.8 s parked (15.10, 112.3–125.0 s, then the
 * dash went silent and the link dropped).
 */
data class MotionFrame(
    val speedKmh: Double,
    val avgSpeedKmh: Double,
    val mode: Int,
    val tripKm: Double,
    val odometerKm: Double,
    val code: Int = 0,
) {
    companion object {
        /** [code] after the BMS went silent (20.10). */
        const val CODE_NO_BMS = 0x26
    }
}
