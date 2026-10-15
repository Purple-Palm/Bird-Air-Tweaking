package io.github.purplepalm.birdair.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker

/** One value of the details page. */
data class Cell(val label: String, val value: String)

/** The details page as rows of one or two cells: pure, so the previews and tests see the same text. */
fun detailRows(t: Telemetry): List<List<Cell>> {
    val u = t.units
    val back = if (t.whOut > 0) " · " + Fmt.num(100 * t.whIn / t.whOut, 0) + " %" else ""
    val rows = mutableListOf(
        listOf(Cell("Напряжение", Fmt.withUnit(t.voltage, 1, "В")), Cell("Ток", Fmt.withUnit(t.currentA, 1, "А"))),
        listOf(Cell("Мощность", Fmt.watts(t.powerW)), Cell("Шкала", "±" + Fmt.watts(t.peakW))),
        listOf(Cell("Потрачено", Fmt.withUnit(t.whOut, 1, "Вт·ч")), Cell("Возвращено$back", Fmt.withUnit(t.whIn, 1, "Вт·ч"))),
        listOf(
            Cell("Поездка", Fmt.withUnit(Fmt.dist(t.tripKm, u), 1, Fmt.distUnit(u))),
            Cell("Пробег", Fmt.withUnit(Fmt.dist(t.odometerKm, u), 1, Fmt.distUnit(u))),
        ),
        listOf(
            Cell("Средняя", Fmt.withUnit(Fmt.speed(t.avgSpeedKmh, u), 1, Fmt.speedUnit(u))),
            Cell("Режим", Modes.name(t.mode) ?: Fmt.DASH),
        ),
        listOf(Cell("Темп. 1", Fmt.withUnit(t.temp1C, 0, "°C")), Cell("Темп. 2", Fmt.withUnit(t.temp2C, 0, "°C"))),
        listOf(Cell("Батарея по BMS", Fmt.num(t.remainingMah, 0) + " / " + Fmt.withUnit(t.fullMah, 0, "мА·ч"))),
    )
    // custom decoder fields that have no typed slot (an error code, say)
    for ((name, v) in t.extras) if (name !in TelemetryTracker.CUSTOM) rows += listOf(Cell(name, Fmt.num(v, if (v % 1.0 == 0.0) 0 else 2)))
    return rows
}

@Composable
fun DetailsScreen(s: Screen, keepOn: Boolean, onKeepOn: (Boolean) -> Unit) {
    // start with the first values in the middle, the header and state above them
    val list = rememberScalingLazyListState(initialCenterItemIndex = 2)
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(list) }) {
        ScalingLazyColumn(
            Modifier.fillMaxSize(),
            state = list,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { ListHeader { Text("Подробно", color = argb(Palette.INK2)) } }
            item {
                Text(
                    s.health.label ?: "данные идут",
                    color = argb(if (s.health.live) Palette.GOOD else s.health.tone),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            for (row in detailRows(s.t)) item { CellRow(row, s.health.alpha) }
            item {
                ToggleChip(
                    checked = keepOn,
                    onCheckedChange = onKeepOn,
                    label = { Text("Экран не гаснет", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("пока идут данные", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = keepOn) },
                    colors = ToggleChipDefaults.toggleChipColors(checkedEndBackgroundColor = argb(Palette.FLOW).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CellRow(row: List<Cell>, alpha: Float) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (c in row) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.label, color = argb(Palette.MUTED), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    c.value,
                    Modifier.alpha(alpha),
                    color = argb(Palette.INK),
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
