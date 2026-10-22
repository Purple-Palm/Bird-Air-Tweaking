package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.Mode
import io.github.purplepalm.birdair.phone.Power
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.PowerBar
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck
import io.github.purplepalm.birdair.phone.ui.rememberSpring
import kotlin.math.abs

/**
 * Over an open page, 56 dp: speed, mode, power with a short bar, battery % (red when the top lamp is); a red line under
 * it while a red lamp burns (recording only). A tap folds the page down to the dash. Idle: «Не подключено · scooter».
 */
@Composable
fun MiniDash(onClick: () -> Unit) {
    val c = LocalBal.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val bulb = rememberBulbCheck()
    // only while recording: idle, the last snapshot's lamps are not live
    val crit = st.running && !bulb && TellTales.top(t.lamps)?.level == LampLevel.RED
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(c.bg)
            .drawBehind { if (crit) drawRect(c.crit, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = size.copy(height = 3.dp.toPx())) }
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Свернуть к поездке" }
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!st.running) {
            Box(Modifier.size(8.dp).background(c.muted, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(if (st.link == Link.CONNECTING) "Подключаюсь…" else "Не подключено" + (st.deviceName?.let { " · $it" } ?: ""), color = c.ink, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            val silent = st.link != Link.CONNECTED || !t.streaming
            Box(Modifier.size(8.dp).background(if (silent) c.warn else c.good, CircleShape))
            Spacer(Modifier.width(9.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.n(if (silent) null else Units.speed(t.speedKmh, s.units), 1), color = c.ink, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1).sp, style = Tnum, maxLines = 1)
                Text(" " + Units.speedLabel(s.units), color = c.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp), maxLines = 1)
            }
            Spacer(Modifier.width(9.dp))
            val m = Mode.of(t.mode)
            val mc = when (m) { Mode.TAKEOFF -> c.modeTake; Mode.GLIDE -> c.modeGlide; Mode.FLY -> c.modeFly; null -> c.base }
            Text(m?.title ?: "—", color = c.ink, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.border(2.dp, mc, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Spacer(Modifier.width(9.dp))
            val p = if (silent) null else Power.watts(t, s.fastPower)
            val live = !silent && p != null
            Column(Modifier.weight(1f)) {
                Text((if (p == null) "—" else Fmt.n(abs(p))) + " Вт", color = flowColor(p, live, io.github.purplepalm.birdair.phone.Flow.of(t, s.fastPower)), fontSize = 13.sp,
                    lineHeight = 15.sp, fontWeight = FontWeight.SemiBold, style = Tnum, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp))
                val frac = rememberSpring(if (live) (p!! / t.peakW).toFloat().coerceIn(-1f, 1f) else 0f, s.smooth)
                PowerBar(frac, live, height = 12.dp)
            }
            Spacer(Modifier.width(9.dp))
            val pct = if (silent) null else t.batteryPctFine ?: t.batteryPct
            Text(Fmt.n(pct, if (t.batteryPctFine != null) 1 else 0) + " %", color = if (crit) c.critInk else c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                style = Tnum, maxLines = 1)
        }
        Box(Modifier.size(30.dp, 44.dp), contentAlignment = Alignment.Center) {
            Icon(AppIcons.Down, contentDescription = null, tint = c.ink2, modifier = Modifier.size(24.dp))
        }
    }
}
