package io.github.purplepalm.birdair.phone.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FacePreview
import io.github.purplepalm.birdair.phone.WatchFaceState
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.mix

/** The watch's dial face as far as the phone heard, in a sentence (under the tiles while the watch has not taken the pick). */
fun faceStatus(f: WatchFaceState): String = when {
    f.applied -> "На часах: «" + f.selected.title + "»"
    f.watch == null -> "Часы ещё не ответили — возьмут выбор, когда включатся"
    else -> "На часах пока «" + f.watch.face.title + "» — сменится, как только часы на связи"
}

/**
 * The five dial faces, three to a row: each a live [FacePreview] (the demo scenarios playing — the same faces the watch
 * draws) above its name; the selected one in a blue frame with a check. A tap picks it ([onPick]): the watch gets it over
 * the Data Layer, even after being off. The previews run only while «Часы» is open (pages are built lazily).
 */
@Composable
fun FacePicker(selected: DialFace, onPick: (DialFace) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 8.dp
        val tile = (maxWidth - gap * 2) / 3
        // 80 dp at 390, 72 at the S21's 360
        val preview = min(80.dp, tile * 0.7f)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (row in DialFace.entries.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (f in row) FaceTile(f, f == selected, preview, Modifier.width(tile)) { onPick(f) }
            }
        }
    }
}

@Composable
private fun FaceTile(face: DialFace, on: Boolean, preview: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.clip(shape)
            .background(if (on) mix(c.well, c.flow, 0.10f) else c.well)
            .border(if (on) 2.dp else 1.dp, if (on) c.flow else c.hair, shape)
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "Циферблат «${face.title}»" },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            FacePreview(face, Modifier.size(preview))
            Spacer(Modifier.size(6.dp))
            Text(face.title, color = c.ink, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                minLines = 2, maxLines = 2)
        }
        if (on) Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(c.flow),
            contentAlignment = Alignment.Center,
        ) { Icon(AppIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}
