package io.github.purplepalm.birdair.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.RangeEstimate
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.WarningLevel

/** Android Studio's large round Wear OS device (454 px, close to the Ultra's 480 px). */
private const val WATCH = "id:wearos_large_round"

/** Fake telemetry for the previews: mid-ride, values like the 40-minute log; the warnings like the two-up rides. */
object Samples {
    private fun range(km: Double, whKm: Double, basis: String = "recent") =
        RangeEstimate(km, km / BatteryAdvisor.MILE_KM, km * whKm, whKm, basis)

    private fun warning(id: String, level: WarningLevel, value: Double) =
        BatteryWarning(id, level, BatteryAdvisor.TEXTS.getValue(id to level), value)

    val riding = Telemetry(
        t = 1_200_000, link = Link.CONNECTED, streaming = true, units = "kmh",
        speedKmh = 23.4, mode = 2, batteryPct = 54.0, voltage = 37.9, currentA = 12.4, powerW = 470.0,
        odometerKm = 1428.1, tripKm = 3.6, avgSpeedKmh = 17.2, remainingMah = 2800.0, fullMah = 5200.0,
        temp1C = 31.0, temp2C = 27.0, motorOn = true, whOut = 41.3, whIn = 2.6, peakW = 820.0,
        currentFastA = 13.1, powerFastW = 496.0, seq = 4211, range = range(7.4, 11.4), appOdometerKm = 1431.7,
    )
    val regen = riding.copy(speedKmh = 18.1, currentA = -5.2, powerW = -205.0, currentFastA = -5.6, powerFastW = -221.0, mode = 3, batteryPct = 22.0)
    val lowBattery = riding.copy(speedKmh = 9.0, mode = 1, batteryPct = 8.0, powerW = 640.0, powerFastW = 655.0, peakW = 700.0, range = range(0.9, 19.5))

    /** Two-up, bottom quarter: 31,9 V under 10 A while the BMS still says 24 %. */
    val warning = riding.copy(
        speedKmh = 19.6, mode = 3, batteryPct = 24.0, voltage = 32.6, currentA = 10.2, powerW = 333.0,
        currentFastA = 11.0, powerFastW = 351.0, range = range(2.1, 19.5),
        warnings = listOf(warning(BatteryAdvisor.LOW, WarningLevel.WARNING, 31.9)),
    )

    /** «Сейчас отключится» and an overheat at once: the critical one is on top. */
    val critical = warning.copy(
        speedKmh = 14.2, batteryPct = 12.0, voltage = 31.6, currentA = 9.6, powerW = 303.0, currentFastA = 9.9, powerFastW = 313.0,
        temp1C = 56.0, temp2C = 52.0, range = range(0.4, 19.5),
        warnings = listOf(warning(BatteryAdvisor.LOW, WarningLevel.CRITICAL, 31.5), warning(BatteryAdvisor.HOT, WarningLevel.CRITICAL, 56.0)),
    )

    /** The start of a ride: no consumption measured yet, the range is the default guess. */
    val roughRange = riding.copy(speedKmh = 6.0, tripKm = 0.1, batteryPct = 98.0, range = range(26.0, 15.0, "default"))

    val silent = riding.copy(streaming = false)
}

@Preview(name = "Тяга", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeRidingPreview() = WatchApp(Screen(Samples.riding, Health.LIVE), null)

@Preview(name = "Рекуперация", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeRegenPreview() = WatchApp(Screen(Samples.regen, Health.LIVE), null)

@Preview(name = "Мало заряда", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeLowPreview() = WatchApp(Screen(Samples.lowBattery, Health.LIVE), null)

@Preview(name = "Заряд на исходе", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeWarningPreview() = WatchApp(Screen(Samples.warning, Health.LIVE), null)

@Preview(name = "Сейчас отключится", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeCriticalPreview() = WatchApp(Screen(Samples.critical, Health.LIVE), null)

@Preview(name = "Нет телефона", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeStalePreview() = WatchApp(Screen(Samples.riding, Health.NO_PHONE), null)

@Preview(name = "Ambient", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeAmbientPreview() = WatchApp(Screen(Samples.riding, Health.LIVE), AmbientState(burnIn = true, tick = 2))

@Preview(name = "Подробно", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun DetailsPreview() = WatchTheme { DetailsScreen(Screen(Samples.silent, Health.SILENT), WatchPrefs()) {} }
