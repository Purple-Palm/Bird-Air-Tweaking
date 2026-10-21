package io.github.purplepalm.birdair.phone.ui.charts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Trip
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Metric
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.Seg
import io.github.purplepalm.birdair.phone.ui.Tnum

/** «Графики» (a stub hosting the old cards; P1 builds the page): the three charts with the window's energy, the trip. */
@Composable
fun ChartsPage(modifier: Modifier = Modifier) {
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChartsCard(s.units, s.fastPower, s.chartWindow)
        TripCard(t, s.units)
    }
}

/** Pinned above the dock: the window, «1 мин / 5 мин / 15 мин / вся поездка». */
@Composable
fun ChartsPinned() {
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Seg(ChartWindow.OPTIONS, s.chartWindow, { w -> ScooterRepo.updateUi { it.copy(chartWindow = w) } }, weights = listOf(1f, 1f, 1f, 1.5f))
}

@Composable
private fun TripCard(t: Telemetry, units: String) {
    val d = Units.distLabel(units)
    val wh = t.whOut - t.whIn
    // the app odometer: the handlebar's loses progress at a power-off (15.10: 1439.6 → 1434.2 in one recording)
    val perKm = Trip.whPerKm(t.whOut, t.whIn, ScooterRepo.session?.appOdoStartKm, t.appOdometerKm)
    val perUnit = perKm?.let { if (units == Units.MPH) it * Units.MILE else it }
    val c = LocalBal.current
    Panel("Поездка") {
        // the app's odometer big, the dash's own (which loses progress when switched off) beside it
        Column {
            Text("Одометр (приложение)", color = c.muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.n(Units.dist(t.appOdometerKm, units), 1), color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, style = Tnum)
                Text(" $d", color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
                Spacer(Modifier.weight(1f))
                Text("по рулю " + Fmt.n(Units.dist(t.odometerKm, units), 1) + " " + d, color = c.ink2, fontSize = 14.sp, style = Tnum,
                    modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Row {
            Metric("С включения", Fmt.n(Units.dist(t.tripKm, units), 1), d, Modifier.weight(1f))
            Metric("Средняя", Fmt.n(Units.speed(t.avgSpeedKmh, units), 1), Units.speedLabel(units), Modifier.weight(1f))
        }
        Row {
            Metric("Энергия за сессию", Fmt.n(wh, 1), "Вт·ч", Modifier.weight(1f))
            Metric("Расход поездки", Fmt.n(perUnit, 1), "Вт·ч/$d", Modifier.weight(1f))
        }
        Msg("Из батареи " + Fmt.n(t.whOut, 1) + " Вт·ч, рекуперация " + Fmt.n(t.whIn, 1) + " Вт·ч" + (if (t.whOut > 0.05) " (" + Fmt.n(100 * t.whIn / t.whOut, 1) + " %)" else "") +
            ". Одометр руля теряет пробег при выключении — приложение считает своё (исправить — в «Настройках»).")
    }
}
