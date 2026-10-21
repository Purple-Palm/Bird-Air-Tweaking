package io.github.purplepalm.birdair.phone.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.UpdateState
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi

/**
 * The update in words (a stub: the old card on the new states; P3 builds it): what is available or downloaded, the
 * download's progress, and what installing does now ([running]: the link drops; [moving]: not now). The button is not
 * here: [io.github.purplepalm.birdair.phone.ui.shell.UpdateButton] is the same everywhere.
 */
@Composable
fun UpdateCard(u: UpdateState, phase: UpdPhase, running: Boolean, moving: Boolean, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val ui = LocalUi.current
    val latest = u.latest
    if (phase == UpdPhase.NONE || phase == UpdPhase.CHECKING || latest == null) { Msg(u.message, u.err); return }
    Panel(modifier = modifier) {
        Text((if (phase == UpdPhase.READY) "Скачано" else "Есть обновление") + ": v" + (u.readyCode ?: latest.versionCode), color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Сейчас " + Updater.installedLabel() + " → " + latest.label() + (UpdateFlow.mb(latest.phoneSize)?.let { " · $it" } ?: ""), color = c.muted, fontSize = 12.sp)
        latest.notes?.let { Msg("Что нового: $it") }
        if (phase == UpdPhase.DOWNLOADING) {
            val p = UpdateFlow.fraction(u)
            if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth(), color = c.flow, trackColor = c.well)
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.flow, trackColor = c.well)
        }
        Msg(u.message, u.err)
        UpdateFlow.installBlock(moving)?.let { if (phase == UpdPhase.READY) Msg(it, err = true) }
        // installing replaces the running app: the link ends (the log and the odometer are saved first)
        if (running && !moving) Msg("Идёт запись: установка закроет приложение и оборвёт связь с самокатом. Лог и одометр сохранятся перед установкой, но лучше обнови после поездки.")
        if (u.needsPermission) {
            OutlinedButton(onClick = ui::allowInstall, modifier = Modifier.fillMaxWidth()) { Text("Разрешить установку из Bird Air") }
            Msg("Настройки → «Установка неизвестных приложений» → Bird Air → включи. Вернёшься — установка продолжится. Настройки, адрес самоката и логи сохранятся.")
        }
    }
}
