package io.github.purplepalm.birdair.phone.ui.charts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.ChartsText
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Trip
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.SectionHeader
import io.github.purplepalm.birdair.phone.ui.Seg
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.WellCard
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * «Графики»: the readout row, the three charts and the window's energy ([ChartsBlock]), then the recording since
 * «Подключить» (both odometers, energy, Wh/km). The window is pinned above the dock ([ChartsPinned]); there is no pager:
 * the charts own the sideways drag, the page scrolls up and down.
 */
@Composable
fun ChartsPage(modifier: Modifier = Modifier) {
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(PagePadding)) {
        ChartsBlock(s.units, s.fastPower, ChartWindow.norm(s.chartWindow))
        SectionHeader("ПОЕЗДКА · С «ПОДКЛЮЧИТЬ»")
        TripCard()
    }
}

/** Pinned above the dock: the window, «1 мин / 5 мин / 15 мин / вся поездка» (a new one drops the readout). */
@Composable
fun ChartsPinned() {
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Seg(ChartWindow.OPTIONS, ChartWindow.norm(s.chartWindow), { w -> ScooterRepo.updateUi { it.copy(chartWindow = w) } }, weights = listOf(1f, 1f, 1f, 1.5f))
}

/** The trip card's figures as text: they change with a BMS frame, not 20 times a second. */
@Immutable
private data class TripNums(
    val dist: String, val speed: String, val appOdo: String, val dashOdo: String, val sinceOn: String, val avg: String,
    val wh: String, val perDist: String, val note: String,
) {
    companion object {
        /** The first frame's figures, before the flow delivers (outside composition: lint's StateFlowValueCalledInComposition). */
        fun now() = of(ScooterRepo.telemetry.value, ScooterRepo.settings.value)

        fun of(t: Telemetry, s: UiSettings): TripNums {
            val u = s.units
            // the app odometer: the handlebar's loses progress at a power-off (15.10: 1439.6 → 1434.2 in one recording)
            val perKm = Trip.whPerKm(t.whOut, t.whIn, ScooterRepo.session?.appOdoStartKm, t.appOdometerKm)
            return TripNums(
                Units.distLabel(u), Units.speedLabel(u), Fmt.n(Units.dist(t.appOdometerKm, u), 1), Fmt.n(Units.dist(t.odometerKm, u), 1),
                Fmt.n(Units.dist(t.tripKm, u), 1), Fmt.n(Units.speed(t.avgSpeedKmh, u), 1), Fmt.n(t.whOut - t.whIn, 1),
                Fmt.n(ChartsText.perDist(perKm, u), 1), ChartsText.tripNote(t.whOut, t.whIn),
            )
        }
    }
}

@Composable
private fun TripCard() {
    val c = LocalBal.current
    val f = remember { combine(ScooterRepo.telemetry, ScooterRepo.settings, TripNums::of).distinctUntilChanged() }
    val n by f.collectAsStateWithLifecycle(TripNums.now())
    WellCard {
        Row(Modifier.fillMaxWidth()) {
            Met("Одометр приложения", n.appOdo, n.dist, Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Met("Одометр по рулю", n.dashOdo, n.dist, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Met("С включения", n.sinceOn, n.dist, Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Met("Средняя", n.avg, n.speed, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Met("Энергия за сессию", n.wh, "Вт·ч", Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Met("Расход поездки", n.perDist, "Вт·ч/" + n.dist, Modifier.weight(1f))
        }
        Text(n.note, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 10.dp))
    }
}
