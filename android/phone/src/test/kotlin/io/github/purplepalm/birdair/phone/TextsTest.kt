package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Decoders
import io.github.purplepalm.birdair.core.Frames
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.Uuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TextsTest {
    private val nb = Fmt.NBSP

    @Test fun numbersTheRussianWay() {
        assertEquals("25,6", Fmt.n(25.6, 1))
        assertEquals("25,7", Fmt.n(25.65000001, 1))
        assertEquals("1433,3", Fmt.n(1433.3, 1))
        assertEquals("14${nb}333,3", Fmt.n(14333.3, 1))
        assertEquals("1${nb}234${nb}567", Fmt.n(1234567.0))
        assertEquals("0,0", Fmt.n(-0.04, 1))
        assertEquals("${Fmt.MINUS}150", Fmt.n(-150.4))
        assertEquals("320", Fmt.n(319.6))
        assertEquals("—", Fmt.n(null))
        assertEquals("—", Fmt.n(Double.NaN, 1))
        assertEquals("320 Вт", Fmt.watts(320.0))
        assertEquals("12,5 кВт", Fmt.watts(12_480.0))
        assertEquals("±300 Вт", Fmt.scale(300.0))
        assertEquals("±1,2 кВт", Fmt.scale(1210.0))
    }

    @Test fun flowLikeThePage() {
        assertEquals(Flow.NONE, Flow.of(null, null, null))
        assertEquals(Flow.IDLE, Flow.of(0.1, 4.0, 0.0))
        assertEquals(Flow.IDLE, Flow.of(-0.1, -4.0, 0.0))
        assertEquals(Flow.DRIVE, Flow.of(0.7, 28.0, 0.0))
        assertEquals(Flow.DRIVE, Flow.of(0.0, 0.0, 2.0))
        assertEquals(Flow.DRIVE, Flow.of(null, 25.0, null))
        assertEquals(Flow.REGEN, Flow.of(-0.2, -8.0, 5.0))
        assertEquals(Flow.CHARGE, Flow.of(-0.2, -8.0, 0.0))
        assertEquals(Flow.INTO, Flow.of(-0.2, null, null))
        assertEquals(Flow.INTO, Flow.of(null, -10.0, null))
        assertEquals("Тяга", Flow.DRIVE.label)
        assertEquals("Рекуперация", Flow.REGEN.label)
        assertEquals("Стоит", Flow.IDLE.label)
    }

    @Test fun modes() {
        assertEquals(Mode.FLY, Mode.of(3))
        assertNull(Mode.of(0))
        assertEquals("16 mph", Mode.FLY.limit(Units.MPH))
        assertEquals("12,5 mph", Mode.GLIDE.limit(Units.MPH))
        assertEquals("14,5 км/ч", Mode.TAKEOFF.limit(Units.KMH))
        assertEquals("25,7 км/ч", Mode.FLY.limit(Units.KMH))
    }

    @Test fun notificationText() {
        val base = Telemetry(link = Link.CONNECTED, streaming = true, speedKmh = 25.6, batteryPct = 75.0, voltage = 40.0, currentA = 8.0, powerW = 320.0)
        assertEquals("25,6 км/ч · 75 % · тяга 320 Вт", StatusText.notification(base))
        assertEquals("20,0 км/ч · 74 % · рекуперация 150 Вт",
            StatusText.notification(base.copy(speedKmh = 20.0, batteryPct = 74.0, currentA = -3.75, powerW = -150.0)))
        assertEquals("0,0 км/ч · 75 % · стоит", StatusText.notification(base.copy(speedKmh = 0.0, currentA = 0.05, powerW = 2.0)))
        assertEquals("15,9 mph · 75 % · тяга 320 Вт", StatusText.notification(base.copy(units = "mph")))
        assertEquals("нет связи — переподключаюсь", StatusText.notification(base.copy(link = Link.RECONNECTING)))
        assertEquals("подключаюсь…", StatusText.notification(base.copy(link = Link.CONNECTING)))
        assertEquals("подключено — жду данные от руля", StatusText.notification(base.copy(streaming = false)))
        assertEquals("не подключено", StatusText.notification(Telemetry()))
        assertEquals("Bird Air · FLY", StatusText.title(base.copy(mode = 3)))
        assertEquals("Bird Air", StatusText.title(base))
        assertEquals("На связи", StatusText.pill(Link.CONNECTED, true, false))
        assertEquals("Подключено — жду данные", StatusText.pill(Link.CONNECTED, false, false))
        assertEquals("Ищу самокат…", StatusText.pill(Link.IDLE, false, true))
    }

    /** Real decoder v5 on frames built like the dash's: decode → snapshot → the notification line. */
    @Test fun fromFramesThroughDecoderV5() {
        val root = File(System.getProperty("repoRoot") ?: "../..")
        val tr = TelemetryTracker(Decoder(Decoders.parse(File(root, "decoders/current.json").readText())))
        tr.link = Link.CONNECTED
        // 3A 17 00 0D: avg 20,0 km/h, speed 25,6 km/h, mode FLY (0x0B), trip 8,5 km, odometer 1433,3 km
        val f17 = Frames.make(0x17, 0x00, byteArrayOf(0, 200.toByte(), 0x01, 0x00, 0, 0x0B, 0, 85, 0x00, 0x37, 0xFD.toByte(), 1, 1))
        // 3A 18 20 10: 40,000 V, −8000 mA raw (= 8 A from the battery), 3287 / 4363 mAh, 75 %, temps 30/31, motor bit
        val f18 = Frames.make(0x18, 0x20, byteArrayOf(
            3, 0x20, 0, 30, 31, 0x40, 0x9C.toByte(), 0xC0.toByte(), 0xE0.toByte(),
            (3287 and 0xFF).toByte(), (3287 shr 8).toByte(), (4363 and 0xFF).toByte(), (4363 shr 8).toByte(), 75, 0x69, 0,
        ))
        // split into 20-byte notifications like the BLE bridge
        for ((i, f) in listOf(f17, f18).withIndex()) {
            f.toList().chunked(20).forEach { tr.onNotification(Uuids.SERVICE, Uuids.CHAR, it.toByteArray(), 1000L + i * 100) }
        }
        val t = tr.snapshot(1500)
        assertEquals(25.6, t.speedKmh!!, 1e-9)
        assertEquals(3, t.mode)
        assertEquals(320.0, t.powerW!!, 1e-6)
        assertEquals(true, t.motorOn)
        assertEquals("25,6 км/ч · 75 % · тяга 320 Вт", StatusText.notification(t))
        assertEquals("Bird Air · FLY", StatusText.title(t))
        assertEquals(Flow.DRIVE, Flow.of(t))
        // 4 s later without frames: no longer live
        assertEquals("подключено — жду данные от руля", StatusText.notification(tr.snapshot(5000)))
    }

    @Test fun whPerKm() {
        assertEquals(11.4, Trip.whPerKm(103.0, 5.8, 1424.8, 1433.3)!!, 0.05)
        assertNull(Trip.whPerKm(1.0, 0.0, 1424.8, 1424.82))
        assertNull(Trip.whPerKm(1.0, 0.0, null, 1424.8))
    }
}
