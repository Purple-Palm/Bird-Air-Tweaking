package io.github.purplepalm.birdair.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FacePreview
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.WatchFaceState

/** The card's line about the watch: what it draws now, as far as the phone heard. */
fun faceStatus(f: WatchFaceState): String = when {
    f.applied -> "На часах: «" + f.selected.title + "»"
    f.watch == null -> "Часы ещё не ответили — возьмут выбор, когда включатся"
    else -> "На часах пока «" + f.watch.face.title + "» — сменится, как только часы на связи"
}

/**
 * «Циферблат часов»: the watch's dial theme, like a watch face but inside the app. Closed: the chosen one, still, and
 * what the watch draws; «Выбрать»: all five, each a live preview (the demo scenarios playing, the same faces the watch
 * draws, [FacePreview]) beside its name and what it does — a tap picks one, the watch gets it over the Data Layer (even
 * after being off) and says back.
 */
@Composable
fun WatchFaceCard() {
    val c = LocalBal.current
    val f by ScooterRepo.face.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val sel = f.selected
    Panel("Циферблат часов", trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "Свернуть" else "Выбрать") } }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FacePreview(sel, Modifier.size(84.dp), playing = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sel.title, color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(faceStatus(f), color = if (f.applied) c.goodInk else c.muted, fontSize = 13.sp)
            }
        }
        if (open) {
            for (face in DialFace.entries) FaceOption(face, face == sel, Modifier.fillMaxWidth()) { ScooterRepo.pickFace(face) }
            Msg("Как watch face, только внутри приложения: выбор уходит на часы и доходит, даже если они были выключены. " +
                "На часах то же — «Подробно» → «Циферблат»; действует последний выбор.")
        }
    }
}

@Composable
private fun FaceOption(face: DialFace, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) c.flow.copy(alpha = 0.12f) else c.well)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.flow else c.hair, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Циферблат «${face.title}»"; this.selected = selected }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FacePreview(face, Modifier.size(132.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(face.title, color = c.ink, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2)
            Text(face.about, color = c.muted, fontSize = 13.sp, lineHeight = 17.sp)
            if (selected) Text("выбран", color = c.flow, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
