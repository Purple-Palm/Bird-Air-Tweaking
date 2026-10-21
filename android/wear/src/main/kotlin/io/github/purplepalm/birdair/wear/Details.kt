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
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.InlineSliderDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import io.github.purplepalm.birdair.core.BatteryAdvisor
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.TellTales

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
    // custom decoder fields that have no typed slot (an error code, say); not the phone's flags riding in extras, nor
    // the raw status bytes (they are in the lamps)
    for ((name, v) in t.extras) if (name !in TelemetryTracker.CUSTOM && name !in TelemetryTracker.STATUS && name !in PhoneFlags.KEYS)
        rows += listOf(Cell(name, Fmt.num(v, if (v % 1.0 == 0.0) 0 else 2)))
    return rows
}

/** The details page's warning lines: «Сейчас отключится» over «31,8 В под нагрузкой». */
fun warningLines(ws: List<BatteryWarning>): List<Pair<BatteryWarning, String?>> = ws.map { it to Warnings.valueText(it) }

/**
 * The lit lamps in words, strongest first: «Перегрев — сбавь газ» over «Температура батареи · 56 °C». The battery adds
 * the phone's «31,8 В под нагрузкой». The icons on the dial are learnt here.
 */
fun lampLines(t: Telemetry): List<Pair<Lamp, String>> =
    Cluster.lamps(t).filter { it.level != LampLevel.OFF }
        .sortedWith(compareByDescending<Lamp> { it.level.ordinal }.thenBy { TellTales.IDS.indexOf(it.id) })
        .map { l ->
            val name = TellTales.NAMES[l.id] ?: l.id
            val value = when (l.id) {
                TellTales.TEMP -> l.value?.let { Fmt.withUnit(it, 0, "°C") }
                TellTales.BATTERY -> when (l.text) {
                    // the BMS's own flag: the voltage it came at; with the BMS gone the advisor's value is old
                    Cluster.BMS_CUT -> l.value?.let { Fmt.withUnit(it, 1, "В") }
                    Cluster.NO_BMS -> null
                    else -> t.warnings.firstOrNull { it.id == BatteryAdvisor.LOW }?.let { Warnings.valueText(it) }
                }
                // «предел тока» carries the current, «просадка» the voltage
                TellTales.TRACTION -> l.value?.let { Fmt.withUnit(it, 1, if (l.text.contains("предел")) "А" else "В") }
                else -> null
            }
            l to (if (value != null) "$name · $value" else name)
        }

/** This build, at the bottom of the details page: «Часы: версия 0.3 (12)». */
fun versionLine(name: String, code: Int): String = "Часы: версия $name ($code)"

/** Index of the «Плавно» chip in the details list (the screenshots scroll there). */
fun smoothChipIndex(s: Screen): Int = 2 + lampLines(s.t).size + detailRows(s.t, s.fast).size + 1

/** Index of the «Тап у ограничителя» switch, right under «Плавно»; its slider and pause follow. */
fun tapChipIndex(s: Screen): Int = smoothChipIndex(s) + 1

/** Index of the vibration past the limiter, under the tap's pause. */
fun overspeedChipIndex(s: Screen): Int = tapChipIndex(s) + 3

/** Index of «Вибрация ламп», under the overspeed threshold. */
fun lampBuzzChipIndex(s: Screen): Int = overspeedChipIndex(s) + 2

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
                    Cluster.statusLabel(s.health, Cluster.lamps(s.t), gauge = false) ?: "данные идут",
                    color = argb(if (s.health.live) Palette.GOOD else s.health.tone),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            for ((lamp, about) in lampLines(s.t)) item { LampRow(lamp, about, s.health.alpha) }
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
            // the speed cues: a tap where the limiter starts cutting, a rumble past it (FLY's mph, the others scale)
            item {
                ToggleChip(
                    checked = prefs.tapOn,
                    onCheckedChange = { onPrefs(prefs.copy(tapOn = it)) },
                    label = { Text("Тап у ограничителя", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("толчок > " + SpeedPref.mph(prefs.tapMph), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = prefs.tapOn) },
                    colors = ToggleChipDefaults.toggleChipColors(checkedEndBackgroundColor = argb(Palette.LAMP_AMBER).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SpeedSlider(prefs.tapMph, prefs.tapOn, s.t.units) { onPrefs(prefs.copy(tapMph = SpeedPref.clamp(it, SpeedPref.TAP_MPH))) } }
            item {
                // one chip, two pauses: a tap goes 60 → 120 → 60 s
                Chip(
                    onClick = { onPrefs(prefs.copy(tapPauseS = TapPause.next(prefs.tapPauseS))) },
                    label = { Text("Пауза тапа", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(TapPause.describe(prefs.tapPauseS), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ChipDefaults.secondaryChipColors(),
                    enabled = prefs.tapOn,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                ToggleChip(
                    checked = prefs.overspeedOn,
                    onCheckedChange = { onPrefs(prefs.copy(overspeedOn = it)) },
                    label = { Text("Вибро > " + SpeedPref.mph(prefs.overspeedMph), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("быстрее ограничителя", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = prefs.overspeedOn) },
                    colors = ToggleChipDefaults.toggleChipColors(checkedEndBackgroundColor = argb(Palette.LAMP_RED).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SpeedSlider(prefs.overspeedMph, prefs.overspeedOn, s.t.units) {
                    onPrefs(prefs.copy(overspeedMph = SpeedPref.clamp(it, SpeedPref.RUMBLE_MPH)))
                }
            }
            item {
                ToggleChip(
                    checked = prefs.lampBuzz,
                    onCheckedChange = { onPrefs(prefs.copy(lampBuzz = it)) },
                    label = { Text("Вибрация ламп", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text("оранжевые и красные", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    toggleControl = { Switch(checked = prefs.lampBuzz) },
                    colors = ToggleChipDefaults.toggleChipColors(checkedEndBackgroundColor = argb(Palette.LAMP_AMBER).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // the watch's own update («Обновить часы», WatchUpdater), above the version line
            item { WatchUpdateItem() }
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

/** A lit lamp: its line in its colour (two lines for a long one), what it is about under it. */
@Composable
private fun LampRow(l: Lamp, about: String, alpha: Float) {
    Column(Modifier.fillMaxWidth().alpha(alpha), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            l.text.ifBlank { TellTales.NAMES[l.id] ?: l.id },
            color = argb(Lamps.argb(l.level)),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(about, color = argb(Palette.INK2), fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A cue's FLY point in [SpeedPref.STEP] mph steps (km/h beside it while the phone shows km/h), what it is in the
 * other modes under it; greyed while the cue is off.
 */
@Composable
private fun SpeedSlider(mph: Double, enabled: Boolean, units: String, onMph: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "FLY " + SpeedPref.describe(mph, units),
            color = argb(if (enabled) Palette.INK else Palette.MUTED),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        InlineSlider(
            value = mph.toFloat(),
            onValueChange = { onMph(it.toDouble()) },
            steps = SpeedPref.STEPS,
            decreaseIcon = { Icon(InlineSliderDefaults.Decrease, "медленнее") },
            increaseIcon = { Icon(InlineSliderDefaults.Increase, "быстрее") },
            valueRange = SpeedPref.MIN.toFloat()..SpeedPref.MAX.toFloat(),
            segmented = false,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Text(
            SpeedPref.otherModes(mph),
            color = argb(Palette.MUTED),
            style = TextStyle(fontSize = 11.sp, fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
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
