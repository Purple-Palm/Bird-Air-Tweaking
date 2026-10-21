package io.github.purplepalm.birdair.phone.ui.lamps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.purplepalm.birdair.phone.Power
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.WarnText
import io.github.purplepalm.birdair.phone.ui.Big
import io.github.purplepalm.birdair.phone.ui.LevelBar
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.Tag
import io.github.purplepalm.birdair.phone.ui.TagTone
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck

/**
 * «Приборка» (a stub hosting the cards of the old screen; P1 builds the page): the advisor's warnings, the 12 lamps with
 * their journal, the battery in detail with the current line that was under the power bar.
 */
@Composable
fun LampsPage(modifier: Modifier = Modifier) {
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (t.warnings.isNotEmpty()) Warnings(t.warnings)
        ClusterCard(t, s.units)
        BatteryCard(t, s.units, s.fastPower)
    }
}

/** Right of the title: «проверка ламп» while every lamp is lit. */
@Composable
fun LampsTool() {
    if (rememberBulbCheck()) Tag("проверка ламп", TagTone.FLOW)
}

/** The battery advisor's warnings, CRITICAL first: red / amber by level, with the value and what to do. */
@Composable
private fun Warnings(ws: List<BatteryWarning>) {
    val c = LocalBal.current
    for (w in ws) {
        val crit = w.level == WarningLevel.CRITICAL
        val col = if (crit) c.crit else c.warn
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = col.copy(alpha = if (crit) 0.22f else 0.16f),
            border = BorderStroke(if (crit) 2.dp else 1.dp, col),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 4.dp).size(12.dp).background(col, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text((if (crit) "Опасно: " else "Внимание: ") + WarnText.line(w), color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = Tnum)
                    val hint = WarnText.hint(w)
                    if (hint.isNotEmpty()) Text(hint, color = c.ink2, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(t: Telemetry, units: String, fast: Boolean) {
    val c = LocalBal.current
    // to 0.1 % from mAh (the BMS's own percent floors to 1 %)
    val pct = t.batteryPctFine ?: t.batteryPct
    Panel("Батарея") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.n(pct, if (t.batteryPctFine != null) 1 else 0), color = c.ink, fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, style = Big)
            Text(" %", color = c.ink2, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.n(t.voltage, 2) + " В", color = c.ink, fontSize = 18.sp, style = Tnum)
                Text(Fmt.n(t.remainingMah) + " / " + Fmt.n(t.fullMah) + " мА·ч", color = c.ink2, fontSize = 13.sp, style = Tnum)
            }
        }
        LevelBar(pct)
        val r = t.range
        if (r != null) {
            Text(WarnText.range(r, units), color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = Tnum)
            Text(WarnText.basis(r) + ". Отсечка бывает раньше, чем BMS покажет 0 %.", color = c.muted, fontSize = 12.sp)
        }
        if (t.temp1C != null || t.temp2C != null) {
            Msg("Температуры: " + Fmt.n(t.temp1C) + " / " + Fmt.n(t.temp2C) + " °C (похоже на батарею, термометром не сверено)")
        }
        // was under the power bar
        val bms = if (fast && t.currentFastA != null) " (BMS " + Fmt.n(t.currentA, 1) + " А)" else ""
        Text(
            "Ток " + Fmt.n(Power.amps(t, fast), 1) + " А" + bms + " · " + Fmt.n(t.voltage, 1) + " В" + (if (t.motorOn == true) " · мотор тянет" else ""),
            color = c.ink2, fontSize = 14.sp, style = Tnum,
        )
    }
}
