package io.github.purplepalm.birdair.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry

/** Android Studio's large round Wear OS device (454 px, close to the Ultra's 480 px). */
private const val WATCH = "id:wearos_large_round"

/** Fake telemetry for the previews: mid-ride, values like the 40-minute log. */
object Samples {
    val riding = Telemetry(
        t = 1_200_000, link = Link.CONNECTED, streaming = true, units = "kmh",
        speedKmh = 23.4, mode = 2, batteryPct = 54.0, voltage = 37.9, currentA = 12.4, powerW = 470.0,
        odometerKm = 1428.1, tripKm = 3.6, avgSpeedKmh = 17.2, remainingMah = 2800.0, fullMah = 5200.0,
        temp1C = 31.0, temp2C = 27.0, motorOn = true, whOut = 41.3, whIn = 2.6, peakW = 820.0,
    )
    val regen = riding.copy(speedKmh = 18.1, currentA = -5.2, powerW = -205.0, mode = 3, batteryPct = 22.0)
    val lowBattery = riding.copy(speedKmh = 9.0, mode = 1, batteryPct = 8.0, powerW = 640.0, peakW = 700.0)
    val silent = riding.copy(streaming = false)
}

@Preview(name = "Тяга", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeRidingPreview() = WatchApp(Screen(Samples.riding, Health.LIVE), null, true) {}

@Preview(name = "Рекуперация", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeRegenPreview() = WatchApp(Screen(Samples.regen, Health.LIVE), null, true) {}

@Preview(name = "Мало заряда", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeLowPreview() = WatchApp(Screen(Samples.lowBattery, Health.LIVE), null, true) {}

@Preview(name = "Нет телефона", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeStalePreview() = WatchApp(Screen(Samples.riding, Health.NO_PHONE), null, true) {}

@Preview(name = "Ambient", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun GaugeAmbientPreview() = WatchApp(Screen(Samples.riding, Health.LIVE), AmbientState(burnIn = true, tick = 2), true) {}

@Preview(name = "Подробно", device = WATCH, showSystemUi = true, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun DetailsPreview() = WatchTheme { DetailsScreen(Screen(Samples.silent, Health.SILENT), true) {} }
