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
        )
    }

    /** A 3A 17 00 0D frame (with its 3A), or null. */
    fun motion(f: ByteArray): MotionFrame? {
        if (f.size < 19 || !head(f, 0x17, 0x00, 0x0D)) return null
        return MotionFrame(
            speedKmh = u16be(f, 6) * 0.1, avgSpeedKmh = u(f, 5) * 0.1, mode = u(f, 9) and 3, tripKm = u16be(f, 10) * 0.1,
            odometerKm = ((u(f, 12) shl 16) or (u(f, 13) shl 8) or u(f, 14)) * 0.1,
        )
    }
}

/**
 * 3A 18 20 10: [voltage] V (9–10 u16le × 0.001), [currentA] A + traction / − regen and charge (11–12 s16le mA,
 * sign flipped), [motor] bit 0x20 of byte 5, [temp1C] / [temp2C] bytes 7 and 8 (°C, unverified), [remainingMah]
 * 13–14, [fullMah] 15–16 (u16le), [pct] byte 17.
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
)

/** 3A 17 00 0D: speed 6–7 u16be, average since power-on byte 5, mode byte 9 & 3, trip since power-on 10–11 u16be, odometer 12–14 u24be (× 0.1). */
data class MotionFrame(
    val speedKmh: Double,
    val avgSpeedKmh: Double,
    val mode: Int,
    val tripKm: Double,
    val odometerKm: Double,
)
