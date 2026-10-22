package io.github.purplepalm.birdair.phone.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.UpdateCardText
import io.github.purplepalm.birdair.phone.UpdateCardText.Tone
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.UpdateState
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.PlateTone
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.WellCard
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi

/**
 * The update in words, on Настройки and in the update sheet: what is available or downloaded (v108 → v109, its size,
 * what is new), then by phase — when it will be installed, the download's bar, or what installing does now ([running]:
 * the link drops; [moving]: not now, the button is off). Nothing while there is no update ([UpdPhase.NONE],
 * [UpdPhase.CHECKING]): the page and the sheet say that in a line. The button is not here: [io.github.purplepalm.birdair.phone.ui.shell.UpdateButton]
 * is the same everywhere.
 */
@Composable
fun UpdateCard(u: UpdateState, phase: UpdPhase, running: Boolean, moving: Boolean, modifier: Modifier = Modifier) {
    if (phase == UpdPhase.NONE || phase == UpdPhase.CHECKING) return
    val code = UpdateCardText.code(phase, u) ?: return
    val c = LocalBal.current
    val ui = LocalUi.current
    WellCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(AppIcons.Dl, contentDescription = null, tint = c.flow, modifier = Modifier.padding(top = 2.dp).size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(UpdateCardText.title(phase, code), color = c.ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, style = Tnum)
                Text(UpdateCardText.change(Updater.name, Updater.code, code, u.latest, UpdateCardText.size(u)), color = c.ink2, fontSize = 13.sp,
                    lineHeight = 18.sp, style = Tnum)
            }
        }
        u.latest?.notes?.takeIf { it.isNotBlank() && u.latest.versionCode == code }?.let {
            Text("Что нового: $it", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (phase == UpdPhase.DOWNLOADING) DownloadBar(UpdateFlow.fraction(u), Modifier.padding(top = 12.dp))
        UpdateCardText.note(phase, u, running, moving)?.let { n ->
            when (n.tone) {
                Tone.TEXT, Tone.OK -> Text(n.title, color = if (n.tone == Tone.OK) c.goodInk else c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum,
                    modifier = Modifier.padding(top = if (phase == UpdPhase.DOWNLOADING) 6.dp else 10.dp))
                Tone.WARN, Tone.CRIT -> Plate(if (n.tone == Tone.CRIT) PlateTone.CRIT else PlateTone.WARN, n.title, Modifier.padding(top = 10.dp), text = n.text)
            }
        }
        // Android asks once for «Установка неизвестных приложений»; the install goes on after it
        if (u.needsPermission) {
            BigButton("Разрешить установку из Bird Air", ui::allowInstall, Modifier.fillMaxWidth().padding(top = 12.dp), style = BtnStyle.OUTLINED, small = true)
            Text("Настройки → «Установка неизвестных приложений» → Bird Air → включи. Вернёшься — установка продолжится. Настройки, адрес самоката и логи сохранятся.",
                color = c.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (u.err && u.message.isNotEmpty()) Text(u.message, color = c.critInk, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/** The download's 6 dp bar ([f] null: the size is unknown, an empty track). */
@Composable
private fun DownloadBar(f: Float?, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val p = (f ?: 0f).coerceIn(0f, 1f)
    Canvas(modifier.fillMaxWidth().height(6.dp).semantics { progressBarRangeInfo = ProgressBarRangeInfo(p, 0f..1f) }) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(c.well, cornerRadius = r)
        if (p > 0f) drawRoundRect(c.flow, size = Size(size.width * p, size.height), cornerRadius = r)
        drawRoundRect(c.hair, cornerRadius = r, style = Stroke(1.dp.toPx()))
    }
}
