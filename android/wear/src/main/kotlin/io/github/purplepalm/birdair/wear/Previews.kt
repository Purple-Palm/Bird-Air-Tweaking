package io.github.purplepalm.birdair.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.core.WarningLevel

/** Android Studio's large round Wear OS device (454 px, close to the Ultra's 480 px). */
private const val WATCH = "id:wearos_large_round"

/**
 * Fake telemetry for the previews and shots: mid-ride, values like the 40-minute log; the warnings like the two-up
 * rides; [normal], [attention] and [critical] are the three dials of the approved mock (mock3.html), with the lamps
 * as TellTales lights them.
 */
object Samples {
    private fun range(km: Double, whKm: Double, basis: String = "recent") =
        RangeEstimate(km, km / BatteryAdvisor.MILE_KM, km * whKm, whKm, basis)

    private fun warning(id: String, level: WarningLevel, value: Double) =
        BatteryWarning(id, level, BatteryAdvisor.TEXTS.getValue(id to level), value)

    /** Lit on every log since 15.10: the BMS's full capacity is 3900 of 5200 mAh. */
    val service = Lamp(TellTales.SERVICE, LampLevel.AMBER, "Ёмкость 75 % от паспорта", 75.0)

    val riding = Telemetry(
        t = 1_200_000, link = Link.CONNECTED, streaming = true, units = "kmh",
        speedKmh = 23.4, mode = 2, batteryPct = 54.0, voltage = 37.9, currentA = 12.4, powerW = 470.0,
        odometerKm = 1428.1, tripKm = 3.6, avgSpeedKmh = 17.2, remainingMah = 2800.0, fullMah = 5200.0,
        temp1C = 31.0, temp2C = 27.0, motorOn = true, whOut = 41.3, whIn = 2.6, peakW = 820.0,
        currentFastA = 13.1, powerFastW = 496.0, seq = 4211, range = range(7.4, 11.4), appOdometerKm = 1431.7,
        lamps = listOf(service), batteryPctFine = 54.3,
    )
    val regen = riding.copy(speedKmh = 18.1, currentA = -5.2, powerW = -205.0, currentFastA = -5.6, powerFastW = -221.0, mode = 3, batteryPct = 22.0, batteryPctFine = 22.4)
    val lowBattery = riding.copy(
        speedKmh = 9.0, mode = 1, batteryPct = 8.0, batteryPctFine = 8.7, powerW = 640.0, powerFastW = 655.0, peakW = 700.0, range = range(0.9, 19.5),
        lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.AMBER, "Заряд 8,7 %", 8.7), service),
    )

    /** «Норма»: FLY, but gently — ECO is green; the battery with its range in the centre. */
    val normal = riding.copy(
        speedKmh = 14.0, mode = 3, batteryPct = 86.0, batteryPctFine = 86.4, voltage = 40.2, currentA = 4.5, powerW = 180.0,
        currentFastA = 4.5, powerFastW = 180.0, peakW = 700.0, temp1C = 31.0, temp2C = 29.0, range = range(9.1, 11.8),
        lamps = listOf(Lamp(TellTales.ECO, LampLevel.GREEN, "ECO", 172.0), service),
    )

    /** «Внимание»: the battery heats fast (▲), the current at the controller's ~16 A, a high consumption. */
    val attention = normal.copy(
        speedKmh = 22.0, batteryPct = 41.0, batteryPctFine = 41.6, voltage = 35.6, currentA = 15.8, powerW = 560.0,
        currentFastA = 15.8, powerFastW = 560.0, temp1C = 49.0, temp2C = 46.0, range = range(3.8, 21.0),
        lamps = listOf(
            Lamp(TellTales.TEMP, LampLevel.AMBER, "Горячая, быстро греется", 49.0, rising = true),
            Lamp(TellTales.TRACTION, LampLevel.AMBER, "Мало тяги: предел тока", 15.8),
            Lamp(TellTales.CONSUMPTION, LampLevel.AMBER, "Расход 21 Вт·ч/км", 21.0),
            service,
        ),
    )

    /** Two-up, bottom quarter: 31,9 V under 10 A while the BMS still says 24 %. */
    val warning = riding.copy(
        speedKmh = 19.6, mode = 3, batteryPct = 24.0, batteryPctFine = 24.3, voltage = 32.6, currentA = 10.2, powerW = 333.0,
        currentFastA = 11.0, powerFastW = 351.0, range = range(2.1, 19.5),
        warnings = listOf(warning(BatteryAdvisor.LOW, WarningLevel.WARNING, 31.9)),
        lamps = listOf(Lamp(TellTales.BATTERY, LampLevel.AMBER, "Заряд на исходе", 31.9), service),
    )

    /** «Критично»: overheat (red, blinks), at the cutoff, the link dropped a moment ago; braking. */
    val critical = warning.copy(
        speedKmh = 11.0, batteryPct = 12.0, batteryPctFine = 12.4, voltage = 31.8, currentA = -4.7, powerW = -150.0,
        currentFastA = -4.7, powerFastW = -150.0, peakW = 700.0, temp1C = 56.0, temp2C = 52.0, range = range(0.6, 19.5),
        warnings = listOf(warning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, 31.5), warning(BatteryAdvisor.HOT, WarningLevel.CRITICAL, 56.0)),
        lamps = listOf(
            Lamp(TellTales.TEMP, LampLevel.RED, "Перегрев — сбавь газ", 56.0),
            Lamp(TellTales.BATTERY, LampLevel.RED, "Сейчас отключится", 31.5),
            Lamp(TellTales.TRACTION, LampLevel.AMBER, "Мало тяги: просадка", 31.8),
            service,
            Lamp(TellTales.LINK, LampLevel.AMBER, "Связь рвётся"),
        ),
    )

    /** Downhill in FLY past 16 mph: the overspeed vibration runs, the blue lamp is lit past the limiter. */
    val overspeed = normal.copy(
        speedKmh = 29.5, currentA = -1.2, powerW = -48.0, currentFastA = -1.2, powerFastW = -48.0, range = range(8.6, 11.8),
        lamps = listOf(Lamp(TellTales.OVERSPEED, LampLevel.BLUE, "Быстрее ограничителя", 29.5), service),
    )

    /** The start of a ride: no consumption measured yet, the range is the default guess. */
    val roughRange = riding.copy(speedKmh = 6.0, tripKm = 0.1, batteryPct = 98.0, batteryPctFine = 98.2, range = range(26.0, 15.0, "default"))

    val silent = riding.copy(streaming = false)
}

@Preview(name = "Норма", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeNormalPreview() = WatchApp(Screen(Samples.normal, Health.LIVE), null)

@Preview(name = "Внимание", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeAttentionPreview() = WatchApp(Screen(Samples.attention, Health.LIVE), null)

@Preview(name = "Критично", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeCriticalPreview() = WatchApp(Screen(Samples.critical, Health.LIVE), null)

@Preview(name = "Проверка ламп", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeBulbPreview() = WatchApp(Screen(Samples.normal, Health.LIVE, bulb = true), null)

@Preview(name = "Быстрее 16 mph", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeOverspeedPreview() = WatchApp(Screen(Samples.overspeed, Health.LIVE, overspeed = true), null)

@Preview(name = "Рекуперация", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeRegenPreview() = WatchApp(Screen(Samples.regen, Health.LIVE), null)

@Preview(name = "Нет телефона", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeStalePreview() = WatchApp(Screen(Samples.riding, Health.NO_PHONE), null)

@Preview(name = "Ambient", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeAmbientPreview() = WatchApp(Screen(Samples.critical, Health.LIVE), AmbientState(burnIn = true, tick = 2))

@Preview(name = "Подробно", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun DetailsPreview() = WatchTheme { DetailsScreen(Screen(Samples.silent, Health.SILENT), WatchPrefs()) {} }
