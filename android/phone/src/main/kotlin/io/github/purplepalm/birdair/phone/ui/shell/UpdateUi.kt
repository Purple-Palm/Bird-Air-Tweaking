package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Tnum

/** The update's phase now (recomposes with [ScooterRepo.update]). */
@Composable
fun rememberUpdPhase(): UpdPhase {
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    return UpdateFlow.phase(u, Updater.code)
}

/**
 * THE update button, the same everywhere (Настройки' pinned action, the update sheet): «Проверить обновления» →
 * «Проверяю…» → «Обновить до v109» → «Скачиваю 63 %» (the fill behind it) → «Установить v109» (off while moving).
 */
@Composable
fun UpdateButton(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    val phase = UpdateFlow.phase(u, Updater.code)
    val label = UpdateFlow.button(phase, u.latest, u)
    when (phase) {
        UpdPhase.NONE -> BigButton(label, ui::updateStep, modifier, icon = AppIcons.Refresh, style = BtnStyle.OUTLINED)
        UpdPhase.CHECKING -> BigButton(label, {}, modifier, style = BtnStyle.OUTLINED, enabled = false)
        UpdPhase.AVAILABLE -> BigButton(label, ui::updateStep, modifier, icon = AppIcons.Dl)
        UpdPhase.DOWNLOADING -> BigButton(label, {}, modifier, enabled = false, progress = UpdateFlow.fraction(u) ?: 0f)
        UpdPhase.READY -> BigButton(label, ui::updateStep, modifier, icon = AppIcons.Dl, enabled = !moving)
    }
}

/** The top bar's update chip (opens the update sheet): a spinner, «↓ v109», the ring with «63 %», «✓ v109». Nothing when NONE. */
@Composable
fun UpdateChip(onClick: () -> Unit) {
    val c = LocalBal.current
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val phase = UpdateFlow.phase(u, Updater.code)
    if (phase == UpdPhase.NONE) return
    val v = "v" + (u.readyCode ?: u.latest?.versionCode ?: "")
    val ok = phase == UpdPhase.READY
    val tint = if (ok) c.good else c.flow
    val shape = RoundedCornerShape(19.dp)
    val desc = when (phase) {
        UpdPhase.CHECKING -> "Проверяю обновления"
        UpdPhase.AVAILABLE -> "Есть обновление $v"
        UpdPhase.DOWNLOADING -> "Скачиваю обновление"
        else -> "Обновление $v скачано"
    }
    Box(Modifier.height(48.dp).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(38.dp).clip(shape).background(tint.copy(alpha = 0.12f)).border(1.dp, tint.copy(alpha = 0.6f), shape)
                .padding(start = 9.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (phase) {
                UpdPhase.CHECKING -> { CircularProgressIndicator(Modifier.size(18.dp), color = c.flow, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("…", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                UpdPhase.DOWNLOADING -> {
                    val f = UpdateFlow.fraction(u) ?: 0f
                    Ring(f, 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("${UpdateFlow.percent(u) ?: 0} %", color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, softWrap = false)
                }
                else -> {
                    Icon(if (ok) AppIcons.Check else AppIcons.Dl, contentDescription = null, tint = if (ok) c.goodInk else c.ink, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(v, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** A progress ring: [f] of the way round from 12 o'clock. */
@Composable
fun Ring(f: Float, dia: Dp) {
    val c = LocalBal.current
    Canvas(Modifier.size(dia)) {
        val w = dia.toPx() * 2.6f / 22f
        val inset = w / 2 + dia.toPx() * 2f / 22f
        val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
        drawArc(c.hair, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
        drawArc(c.flow, -90f, 360f * f.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The phase the update tile shows (AVAILABLE, DOWNLOADING, READY), else null. */
@Composable
fun rememberUpdateTile(): UpdPhase? = rememberUpdPhase().takeIf { it == UpdPhase.AVAILABLE || it == UpdPhase.DOWNLOADING || it == UpdPhase.READY }

/** The dash's update tile: «Обновить · до v109 · 18,4 МБ» → «Скачиваю · 63 % · 11,6 МБ» → «Установить · связь оборвётся». */
@Composable
fun RowScope.UpdateTile(phase: UpdPhase, armed: Boolean) {
    val c = LocalBal.current
    val ui = LocalUi.current
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val v = u.readyCode ?: u.latest?.versionCode
    val size = u.latest?.phoneSize ?: u.totalBytes
    when (phase) {
        UpdPhase.AVAILABLE -> Tile(AppIcons.Dl, c.flow, "Обновить", "до v$v" + (UpdateFlow.mb(size)?.let { " · $it" } ?: ""), armed, onClick = ui::updateStep)
        UpdPhase.DOWNLOADING -> {
            val f = UpdateFlow.fraction(u) ?: 0f
            Tile(AppIcons.Dl, c.flow, "Скачиваю", "${UpdateFlow.percent(u) ?: 0} %" + (UpdateFlow.mb(u.gotBytes)?.let { " · $it" } ?: ""), armed, progress = f)
        }
        else -> Tile(AppIcons.Dl, c.goodInk, "Установить", if (st.running) "связь оборвётся" else "v$v · скачано", armed, onClick = ui::updateStep,
            subColor = if (st.running) c.warnInk else null)
    }
}

/** The update tile on its own (a page that wants it). */
@Composable
fun UpdateTile(modifier: Modifier = Modifier) {
    val p = rememberUpdateTile() ?: return
    Row(modifier.fillMaxWidth()) { UpdateTile(p, armed = true) }
}
