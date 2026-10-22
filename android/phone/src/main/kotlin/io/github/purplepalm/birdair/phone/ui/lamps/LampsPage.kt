package io.github.purplepalm.birdair.phone.ui.lamps

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.WarningLevel
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.LampsText
import io.github.purplepalm.birdair.phone.LastRide
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.WarnText
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.Big
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.JournalRow
import io.github.purplepalm.birdair.phone.ui.LevelBar
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.PlateTone
import io.github.purplepalm.birdair.phone.ui.SectionHeader
import io.github.purplepalm.birdair.phone.ui.Tag
import io.github.purplepalm.birdair.phone.ui.TagTone
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.WellCard
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck
import io.github.purplepalm.birdair.phone.ui.shell.LocalNav
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * «Приборка»: the dial's line and the 12 lamps first (a tap opens the lamp's sheet), the advisor's warnings, the battery
 * in detail (the last one when nothing records), the lamp journal of the recording. Each block collects its own slice
 * of the 15–20 Hz telemetry, so a new speed redraws none of them.
 */
@Composable
fun LampsPage(modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(PagePadding)) {
        DialAndGrid()
        Advisor()
        SectionHeader("БАТАРЕЯ")
        Battery()
        Journal()
    }
}

/** Right of the title: «проверка ламп» while every lamp is lit. */
@Composable
fun LampsTool() {
    if (rememberBulbCheck()) Tag("проверка ламп", TagTone.FLOW)
}

/** A recording runs: the lamps and the numbers are live. */
@Composable
private fun rememberLive(): State<Boolean> {
    val f = remember { ScooterRepo.status.map { it.running }.distinctUntilChanged() }
    return f.collectAsStateWithLifecycle(Now.running())
}

/** The first frame's values, before a flow delivers (outside composition: lint's StateFlowValueCalledInComposition). */
private object Now {
    fun running() = ScooterRepo.status.value.running
    fun lamps() = ScooterRepo.telemetry.value.lamps
    fun warnings() = advice(ScooterRepo.telemetry.value)
    fun battery() = Bat.of(ScooterRepo.telemetry.value, ScooterRepo.settings.value)
}

/** The advisor's warnings while data flows; silent, they are stale. */
private fun advice(t: Telemetry): List<BatteryWarning> = if (t.streaming) t.warnings else emptyList()

@Composable
private fun DialAndGrid() {
    val c = LocalBal.current
    val nav = LocalNav.current
    val f = remember { ScooterRepo.telemetry.map { it.lamps }.distinctUntilChanged() }
    val lamps by f.collectAsStateWithLifecycle(Now.lamps())
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val live by rememberLive()
    val bulb = rememberBulbCheck()
    val d = LampsText.dial(lamps, s.units, bulb, live)
    Text(
        d.text, color = d.level?.let { lampColor(it, c.dark) } ?: c.muted, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
        style = Tnum, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
    )
    LampGrid(LampsText.shown(lamps, bulb, live), s.units, steady = bulb, onLamp = { nav.lamp(it) })
    Text("Коснись лампы — откроется, что она значит и когда горела.", color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
}

/** «СОВЕТЧИК»: the battery advisor's warnings while data flows (silent, they are stale), CRITICAL first, with what to do. */
@Composable
private fun Advisor() {
    val f = remember { ScooterRepo.telemetry.map(::advice).distinctUntilChanged() }
    val ws by f.collectAsStateWithLifecycle(Now.warnings())
    val live by rememberLive()
    if (!live || ws.isEmpty()) return
    SectionHeader("СОВЕТЧИК")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (w in ws) {
            val crit = w.level == WarningLevel.CRITICAL
            Plate(if (crit) PlateTone.CRIT else PlateTone.WARN, (if (crit) "Опасно: " else "Внимание: ") + WarnText.line(w),
                text = WarnText.hint(w).ifEmpty { null }, blink = crit)
        }
    }
}

/** The battery card's words, as they change (a BMS frame, a new current), not 20 times a second. */
@Immutable
private data class Bat(
    val pct: String, val level: Double?, val volts: String, val mah: String?, val range: String?, val basis: String, val current: String?,
) {
    companion object {
        fun of(t: Telemetry, s: UiSettings): Bat {
            // to 0.1 % from mAh (the BMS's own percent floors to 1 %)
            val pct = t.batteryPctFine ?: t.batteryPct
            return Bat(
                Fmt.n(pct, if (t.batteryPctFine != null) 1 else 0), pct, Fmt.n(t.voltage, 2) + " В", LampsText.mah(t.remainingMah, t.fullMah),
                t.range?.let { WarnText.range(it, s.units) }, LampsText.basis(t.range, t.temp1C, t.temp2C), LampsText.current(t, s.fastPower),
            )
        }
    }
}

@Composable
private fun Battery() {
    val live by rememberLive()
    if (live) LiveBattery() else {
        val r by ScooterRepo.lastRide.collectAsStateWithLifecycle()
        r?.let { LastBattery(it) } ?: NoBattery()
    }
}

@Composable
private fun LiveBattery() {
    val c = LocalBal.current
    val f = remember { combine(ScooterRepo.telemetry, ScooterRepo.settings, Bat::of).distinctUntilChanged() }
    val state = f.collectAsStateWithLifecycle(Now.battery())
    val v = state.value
    WellCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(v.pct, color = c.ink, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
                Text(" %", color = c.ink2, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(v.volts, color = c.ink, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1)
                if (v.mah != null) Text(v.mah, color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, maxLines = 1)
            }
        }
        LevelBar(v.level, Modifier.padding(top = 8.dp, bottom = 6.dp))
        if (v.range != null) Text(v.range, color = c.ink, fontSize = 16.sp, lineHeight = 20.sp, style = Tnum)
        if (v.basis.isNotEmpty()) Text(v.basis, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 4.dp))
        // was under the power bar
        if (v.current != null) Text(v.current, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 4.dp))
    }
}

/** «Батарея (последнее)»: the charge, the bar and the voltage as the last recording ended. */
@Composable
private fun LastBattery(r: LastRide) {
    val c = LocalBal.current
    WellCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Батарея (последнее)", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(Fmt.clock(r.endMs), color = c.muted, fontSize = 13.sp, style = Tnum)
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.n(r.pct, 1), color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
                Text(" %", color = c.ink2, fontSize = 15.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.width(12.dp))
            LevelBar(r.pct, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(Fmt.n(r.volts, 2) + " В", color = c.ink, fontSize = 15.sp, style = Tnum, maxLines = 1)
        }
        val more = LampsText.last(r)
        if (more.isNotEmpty()) Text(more, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun NoBattery() {
    val c = LocalBal.current
    WellCard {
        Text("Подключись к самокату — здесь будут заряд, напряжение и запас хода.", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** «ЖУРНАЛ ЛАМП (N)»: the lamp transitions of the recording, newest first, 6 of them until «Весь журнал · N». */
@Composable
private fun Journal() {
    val c = LocalBal.current
    val list by ScooterRepo.journal.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val live by rememberLive()
    var all by rememberSaveable { mutableStateOf(false) }
    SectionHeader(LampsText.journalTitle(list.size, if (live) null else list.lastOrNull()?.atMs))
    if (list.isEmpty()) Text("Пока ни одна лампа не загоралась и не гасла.", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
    for (e in if (all) list else list.take(LampsText.JOURNAL_ROWS)) JournalRow(e, s.units)
    if (list.size > LampsText.JOURNAL_ROWS) {
        BigButton(if (all) "Свернуть журнал" else "Весь журнал · " + list.size, { all = !all }, Modifier.fillMaxWidth().padding(top = 8.dp),
            style = BtnStyle.OUTLINED, small = true)
    }
    Text("Каждая смена лампы — ещё и метка в логе.", color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
}
