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
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker

/** One value of the details page. */
data class Cell(val label: String, val value: String)

/**
 * The details page as rows of one or two cells: pure, so the previews and tests see the same text. [fast]: the
 * power and current are the fast estimate, as on the gauge ([PowerView]); the BMS's own values get a row of their own.
 */
fun detailRows(t: Telemetry, fast: Boolean = false): List<List<Cell>> {
    val u = t.units
    val back = if (t.whOut > 0) " · " + Fmt.num(100 * t.whIn / t.whOut, 0) + " %" else ""
    val rows = mutableListOf(
        listOf(Cell("Напряжение", Fmt.withUnit(t.voltage, 1, "В")), Cell("Ток", Fmt.withUnit(PowerView.amps(t, fast), 1, "А"))),
        listOf(Cell("Мощность", Fmt.watts(PowerView.watts(t, fast))), Cell("Шкала", "±" + Fmt.watts(t.peakW))),
    )
    if (PowerView.usesFast(t, fast)) rows += listOf(Cell("Ток BMS", Fmt.withUnit(t.currentA, 1, "А")), Cell("Мощность BMS", Fmt.watts(t.powerW)))
    t.range?.let { r ->
        rows += listOf(
            Cell("Запас хода", RangeText.value(r, u) ?: Fmt.DASH),
            Cell(RangeText.basis(r), Fmt.withUnit(r.whPerKm.takeIf { it.isFinite() }, 1, "Вт·ч/км")),
        )
    }
    rows += listOf(Cell("Потрачено", Fmt.withUnit(t.whOut, 1, "Вт·ч")), Cell("Возвращено$back", Fmt.withUnit(t.whIn, 1, "Вт·ч")))
    rows += listOf(
        Cell("Поездка", Fmt.withUnit(Fmt.dist(t.tripKm, u), 1, Fmt.distUnit(u))),
        Cell("Пробег", Fmt.withUnit(Fmt.dist(t.odometerKm, u), 1, Fmt.distUnit(u))),
    )
    // the scooter's own odometer loses progress across power cycles; the phone keeps one of its own
    t.appOdometerKm?.let { rows += listOf(Cell("Пробег по приложению", Fmt.withUnit(Fmt.dist(it, u), 1, Fmt.distUnit(u)))) }
    rows += listOf(
        Cell("Средняя", Fmt.withUnit(Fmt.speed(t.avgSpeedKmh, u), 1, Fmt.speedUnit(u))),
        Cell("Режим", Modes.name(t.mode) ?: Fmt.DASH),
    )
    rows += listOf(Cell("Темп. 1", Fmt.withUnit(t.temp1C, 0, "°C")), Cell("Темп. 2", Fmt.withUnit(t.temp2C, 0, "°C")))
    rows += listOf(Cell("Батарея по BMS", Fmt.num(t.remainingMah, 0) + " / " + Fmt.withUnit(t.fullMah, 0, "мА·ч")))
    // custom decoder fields that have no typed slot (an error code, say); not the phone's flags riding in extras
    for ((name, v) in t.extras) if (name !in TelemetryTracker.CUSTOM && name !in PhoneFlags.KEYS) rows += listOf(Cell(name, Fmt.num(v, if (v % 1.0 == 0.0) 0 else 2)))
    return rows
}

/** The details page's warning lines: «Сейчас отключится» over «31,8 В под нагрузкой». */
fun warningLines(ws: List<BatteryWarning>): List<Pair<BatteryWarning, String?>> = ws.map { it to Warnings.valueText(it) }

/** This build, at the bottom of the details page: «Часы: версия 0.3 (12)». */
fun versionLine(name: String, code: Int): String = "Часы: версия $name ($code)"

/** Index of the «Плавно» chip in the details list (the screenshots scroll there). */
fun smoothChipIndex(s: Screen): Int = 2 + warningLines(s.t.warnings).size + detailRows(s.t, s.fast).size + 1

/** [list]: by default starts with the first values in the middle, the header and state above them. */
@Composable
fun DetailsScreen(
    s: Screen,
    prefs: WatchPrefs,
    list: ScalingLazyListState = rememberScalingLazyListState(initialCenterItemIndex = 2),
    onPrefs: (WatchPrefs) -> Unit,
) {
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
            for ((w, value) in warningLines(s.t.warnings)) item { WarningRow(w, value, s.health.alpha) }
            for (row in detailRows(s.t, s.fast)) item { CellRow(row, s.health.alpha) }
            item {
                ToggleChip(
                    checked = prefs.keepOn,
                    onCheckedChange = { onPrefs(prefs.copy(keepOn = it)) },
                    label = { Text("Экран не гаснет", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("пока идут данные", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = prefs.keepOn) },
                    colors = ToggleChipDefaults.toggleChipColors(checkedEndBackgroundColor = argb(Palette.FLOW).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            item {
                // one chip, three states: a tap goes как на телефоне → вкл → выкл → как на телефоне
                Chip(
                    onClick = { onPrefs(prefs.copy(smooth = prefs.smooth.next())) },
                    label = { Text("Плавно", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(prefs.smooth.describe(s.flags.smooth), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    versionLine(HelloInfo.THIS.versionName, HelloInfo.THIS.versionCode),
                    color = argb(Palette.MUTED),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun WarningRow(w: BatteryWarning, value: String?, alpha: Float) {
    Column(Modifier.fillMaxWidth().alpha(alpha), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            Warnings.text(w),
            color = argb(Warnings.ink(w.level)),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) Text(value, color = argb(Palette.INK2), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
