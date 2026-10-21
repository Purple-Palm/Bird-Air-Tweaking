package io.github.purplepalm.birdair.phone.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.UpdateState
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.Seg
import io.github.purplepalm.birdair.phone.ui.SwitchRow
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.UiActions
import io.github.purplepalm.birdair.phone.ui.shell.LocalSys
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi
import io.github.purplepalm.birdair.phone.ui.shell.UpdateButton

/**
 * «Настройки» (a stub hosting the old cards; P3 builds the page): the app and its update, the screen, the odometer,
 * background work, the decoder and the link's events.
 */
@Composable
fun SettingsPage(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val sys = LocalSys.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UpdateCard(u, UpdateFlow.phase(u, Updater.code), st.running, moving)
        SettingsCard(s, t, u)
        BatteryHintCard(sys, ui)
        Events()
        DecoderFooter()
    }
}

/** Pinned above the dock: the one update button. */
@Composable
fun SettingsPinned() = UpdateButton(Modifier.fillMaxWidth())

@Composable
private fun SettingsCard(s: UiSettings, t: Telemetry, u: UpdateState) {
    val c = LocalBal.current
    Panel("Настройки") {
        Text("Приложение: " + Updater.installedLabel(), color = c.ink2, fontSize = 14.sp, style = Tnum)
        val latest = u.latest
        Text(
            (if (latest != null) "В релизе android-latest: " + latest.label() else "Релиз ещё не проверен") +
                (if (u.checkedAt > 0) " · проверено " + Fmt.dayTime(u.checkedAt) else ""),
            color = c.muted, fontSize = 12.sp,
        )
        SwitchRow("Скачивать обновления сам", s.autoDownload, { v -> ScooterRepo.updateUi { it.copy(autoDownload = v) } },
            hint = "После проверки (при запуске и раз в день). Ставишь — ты, кнопкой")
        HorizontalDivider(color = c.hair)
        Text("Скорость и расстояние", color = c.ink2, fontSize = 14.sp)
        Seg(listOf(Units.KMH to "км/ч, км", Units.MPH to "mph, мили"), s.units, { u2 -> ScooterRepo.updateUi { it.copy(units = u2) } })
        Text("Тема", color = c.ink2, fontSize = 14.sp)
        Seg(listOf("dark" to "Тёмная", "system" to "Как в системе"), s.theme, { v -> ScooterRepo.updateUi { it.copy(theme = v) } })
        SwitchRow("Не гасить экран", s.keepScreenOn, { v -> ScooterRepo.updateUi { it.copy(keepScreenOn = v) } },
            hint = "Только пока открыт этот экран. В фоне связь держится и с выключенным экраном.")
        SwitchRow("Быстрая мощность", s.fastPower, { v -> ScooterRepo.updateUi { it.copy(fastPower = v) } },
            hint = "Полоса, уведомление и часы — по просадке напряжения: свежая на каждом кадре BMS (~3 раза в секунду). Ток BMS обновляется раз в секунду и запаздывает на ~0,6 с.")
        SwitchRow("Плавная анимация", s.smooth, { v -> ScooterRepo.updateUi { it.copy(smooth = v) } },
            hint = "Полоса догоняет данные за ~0,1 с без перелёта, на телефоне и на часах. Выключено — прыгает вместе с данными.")
        HorizontalDivider(color = c.hair)
        OdometerSettings(t)
    }
}

/** «Одометр приложения»: set it to the right value (e.g. what the scooter showed before it lost progress). */
@Composable
private fun OdometerSettings(t: Telemetry) {
    val c = LocalBal.current
    var text by rememberSaveable { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Text("Одометр приложения: " + Fmt.n(t.appOdometerKm, 1) + " км · по рулю " + Fmt.n(t.odometerKm, 1) + " км", color = c.ink2, fontSize = 14.sp, style = Tnum)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(10) }, singleLine = true, label = { Text("Исправить, км") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            val km = text.replace(',', '.').replace(" ", "").toDoubleOrNull()
            if (km == null || km < 0 || km > 1_000_000) { msg = "Впиши пробег в километрах, например 1459,4."; err = true; return@Button }
            ScooterRepo.setOdometer(km); text = ""; msg = "Одометр приложения: " + Fmt.n(km, 1) + " км — дальше считает от него."; err = false
        }, modifier = Modifier.height(56.dp)) { Text("Задать") }
    }
    val odo = t.odometerKm
    if (odo != null) TextButton(onClick = { ScooterRepo.setOdometer(odo); msg = "Взят одометр руля: " + Fmt.n(odo, 1) + " км."; err = false }, modifier = Modifier.height(48.dp)) {
        Text("Взять с руля (" + Fmt.n(odo, 1) + " км)")
    }
    Msg(msg.ifEmpty { "Приложение прибавляет пробег из каждой поездки, даже когда руль после выключения начинает с меньшего числа." }, err)
}

@Composable
private fun BatteryHintCard(sys: SystemState, ui: UiActions) {
    val notif = sys.missingPerms.any { it.endsWith("POST_NOTIFICATIONS") }
    Panel("Работа в фоне") {
        if (!sys.ignoringBattery) {
            Msg("Чтобы связь не рвалась с выключенным экраном, разреши приложению работать без ограничений батареи.")
            Button(onClick = ui::requestIgnoreBattery, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Разрешить работу в фоне") }
        } else Msg("✓ Батарея: без ограничений")
        if (sys.samsung) {
            Msg("На Samsung ещё: Настройки → Батарея → Ограничения фоновой работы → «Никогда не засыпающие приложения» → добавь Bird Air. " +
                "И в настройках приложения: Батарея → «Без ограничений».")
            OutlinedButton(onClick = ui::openAppSettings, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Открыть настройки приложения") }
        }
        if (notif) {
            Msg("Уведомления выключены: значок поездки в шторке, кнопка «Отключить» и предупреждения о батарее. Связь работает и без них.")
            OutlinedButton(onClick = { if (sys.permsBlocked) ui.openAppSettings() else ui.requestPermissions() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Разрешить") }
        }
    }
}

/** «События связи»: the last service lines, errors in red. */
@Composable
private fun Events() {
    val c = LocalBal.current
    val events by ScooterRepo.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) return
    Panel("События связи") {
        for (e in events.asReversed()) Text(Fmt.clock(e.at) + " " + e.text, color = if (e.err) c.critInk else c.ink2, fontSize = 13.sp, style = Tnum)
    }
}

@Composable
private fun DecoderFooter() {
    val c = LocalBal.current
    val d by ScooterRepo.decoder.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    Column(Modifier.clickable { open = !open }.padding(vertical = 4.dp)) {
        Text(if (d.version > 0) "Декодер v${d.version} (${d.source})" else "Декодер не загружен", color = c.muted, fontSize = 12.sp)
        if (open && d.note.isNotEmpty()) Text(d.note, color = c.muted, fontSize = 12.sp)
    }
}
