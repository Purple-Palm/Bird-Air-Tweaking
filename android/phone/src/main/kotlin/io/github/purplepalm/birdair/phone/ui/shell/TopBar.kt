package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.DashView
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ViewPin
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Tone
import io.github.purplepalm.birdair.phone.ui.mix
import io.github.purplepalm.birdair.phone.ui.toneColor

/** The dash's top bar, 56 dp: the link pill; the view chip while data flows; the update chip. Nothing else up here. */
@Composable
fun TopBar(view: DashView, streaming: Boolean) {
    val nav = LocalNav.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val scan by ScooterRepo.scan.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
        val narrow = maxWidth < 380.dp
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(DashText.pill(st.link, streaming, scan.scanning, narrow), pillTone(st.link, streaming, scan.scanning))
            Spacer(Modifier.weight(1f))
            if (view == DashView.RIDE || view == DashView.PARKED) {
                ViewChip(view) { nav.sheet(SheetKind.VIEW) }
                Spacer(Modifier.width(6.dp))
            }
            UpdateChip { nav.sheet(SheetKind.UPDATE) }
        }
    }
}

fun pillTone(link: Link, streaming: Boolean, scanning: Boolean): Tone = when {
    link == Link.CONNECTED && streaming -> Tone.GOOD
    link == Link.CONNECTED || link == Link.CONNECTING -> Tone.WARN
    link == Link.RECONNECTING -> Tone.CRIT
    scanning -> Tone.FLOW
    else -> Tone.NEUTRAL
}

/** The status pill: 36 dp, a dot, the tint of its tone. */
@Composable
fun StatusPill(text: String, tone: Tone) {
    val c = LocalBal.current
    val dot = toneColor(tone)
    val neutral = tone == Tone.NEUTRAL
    Row(
        Modifier.height(36.dp).clip(CircleShape)
            .background(if (neutral) c.surface else mix(c.bg, dot, 0.12f))
            .border(1.dp, if (neutral) c.hair else dot.copy(alpha = 0.55f), CircleShape)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** «Еду · авто» / «Стою · авто», or a lock when pinned: opens the view sheet. */
@Composable
fun ViewChip(view: DashView, onClick: () -> Unit) {
    val c = LocalBal.current
    val pin by ScooterRepo.viewPin.collectAsStateWithLifecycle()
    val ride = view == DashView.RIDE
    val auto = pin == ViewPin.AUTO
    val shape = RoundedCornerShape(19.dp)
    Box(
        Modifier.height(48.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Вид главного экрана: " + (if (ride) "еду" else "стою") + if (auto) ", авто" else "" },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(38.dp).clip(shape).border(1.dp, c.base, shape).padding(start = 9.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (ride) AppIcons.Ride else AppIcons.Park, contentDescription = null, tint = c.ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (ride) "Еду" else "Стою", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(5.dp))
            if (auto) Text("авто", color = c.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            else Icon(AppIcons.Lock, contentDescription = null, tint = c.ink2, modifier = Modifier.size(18.dp))
        }
    }
}
