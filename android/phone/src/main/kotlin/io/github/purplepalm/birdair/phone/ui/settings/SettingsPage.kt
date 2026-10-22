package io.github.purplepalm.birdair.phone.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.DecoderInfo
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.OdometerInput
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.UpdateCardText
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.NavRow
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.SectionHeader
import io.github.purplepalm.birdair.phone.ui.Seg
import io.github.purplepalm.birdair.phone.ui.SettingSwitch
import io.github.purplepalm.birdair.phone.ui.TextField56
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.shell.LocalSnack
import io.github.purplepalm.birdair.phone.ui.shell.LocalSys
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi
import io.github.purplepalm.birdair.phone.ui.shell.UpdateButton
import io.github.purplepalm.birdair.phone.ui.shell.rememberOpenUri
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * «Настройки»: this build and the release, the update card where it stands, «Скачивать обновления сам»; the theme and the
 * units; three switches (the whole row is the target); the app odometer — the only place to correct it; background
 * work; the decoder and the site; the link's events. The update button is pinned under the page ([SettingsPinned]).
 */
@Composable
fun SettingsPage(modifier: Modifier = Modifier) {
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding)) {
        AppSection(s)
        ScreenSection(s)
        OdometerSection()
        BackgroundSection()
        AboutSection()
        EventsSection()
    }
}

/** Pinned above the dock: the one update button, «Проверить обновления» → «Проверяю…» → «Скачиваю 63 %» → «Установить v109». */
@Composable
fun SettingsPinned() = UpdateButton(Modifier.fillMaxWidth())

/** A 13 sp line under a control (the page's .msg). */
@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, lineHeight: Int = 18) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = lineHeight.sp, style = Tnum, modifier = modifier)

@Composable
private fun AppSection(s: UiSettings) {
    val c = LocalBal.current
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    val phase = UpdateFlow.phase(u, Updater.code)
    SectionHeader("ПРИЛОЖЕНИЕ", first = true)
    Line(UpdateCardText.app(Updater.name, Updater.code) + "\n" +
        UpdateCardText.release(u.latest, Updater.code, u.checkedAt.takeIf { it > 0 }?.let { Fmt.dayTime(it) }), c.ink2, Modifier.padding(bottom = 10.dp))
    UpdateCard(u, phase, st.running, moving)
    UpdateCardText.line(phase, u)?.let { (text, err) -> Line(text, if (err) c.critInk else c.goodInk, Modifier.padding(top = 2.dp, bottom = 4.dp)) }
    SettingSwitch("Скачивать обновления сам", s.autoDownload, { v -> ScooterRepo.updateUi { it.copy(autoDownload = v) } },
        hint = "После проверки (при запуске и раз в день). Ставишь — ты, кнопкой")
}

@Composable
private fun ScreenSection(s: UiSettings) {
    SectionHeader("ЭКРАН")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Seg(listOf("dark" to "Тёмная", "system" to "Как в системе"), s.theme, { v -> ScooterRepo.updateUi { it.copy(theme = v) } })
        Seg(listOf(Units.KMH to "км/ч, км", Units.MPH to "mph, мили"), Units.norm(s.units), { v -> ScooterRepo.updateUi { it.copy(units = v) } })
    }
    SettingSwitch("Не гасить экран", s.keepScreenOn, { v -> ScooterRepo.updateUi { it.copy(keepScreenOn = v) } },
        hint = "Пока открыта поездка; в фоне связь держится и так")
    SettingSwitch("Быстрая мощность", s.fastPower, { v -> ScooterRepo.updateUi { it.copy(fastPower = v) } },
        hint = "По просадке напряжения, ~3 раза в секунду")
    SettingSwitch("Плавная анимация", s.smooth, { v -> ScooterRepo.updateUi { it.copy(smooth = v) } },
        hint = "Полоса догоняет данные за ~0,1 с, и на часах")
}

/** The two odometers, 0,1 km apart at most (the page does not recompose with every frame). */
private data class Odo(val appKm: Double?, val scooterKm: Double?)

/**
 * «Одометр»: the app's (it adds every ride, even when the handlebar restarts from less) and the handlebar's.
 * «Исправить» → a field (the app's now) and «Задать», or «Взять с руля».
 */
@Composable
private fun OdometerSection() {
    val c = LocalBal.current
    val snack = LocalSnack.current
    val focus = LocalFocusManager.current
    val flow = remember {
        ScooterRepo.telemetry.map { t -> Odo((t.appOdometerKm ?: ScooterRepo.tracker.odometerState?.totalKm)?.let(::tenth), t.odometerKm?.let(::tenth)) }
            .distinctUntilChanged()
    }
    val odo by flow.collectAsStateWithLifecycle(Odo(ScooterRepo.tracker.odometerState?.totalKm?.let(::tenth), null))
    var edit by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var err by rememberSaveable { mutableStateOf("") }
    SectionHeader("ОДОМЕТР")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)) { append(Fmt.n(odo.appKm, 1) + " км") }
                append(" приложение\n")
                append(if (odo.scooterKm != null) "по рулю " + Fmt.n(odo.scooterKm, 1) + " км — руль теряет пробег при отключении"
                    else "по рулю — покажу, когда подключишься")
            },
            color = c.ink2, fontSize = 13.sp, lineHeight = 20.sp, style = Tnum, modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        BigButton(if (edit) "Скрыть" else "Исправить", {
            edit = !edit
            err = ""
            if (edit) text = odo.appKm?.let { Fmt.n(it, 1) } ?: ""
        }, style = BtnStyle.OUTLINED, small = true)
    }
    if (!edit) return
    val set = {
        val km = OdometerInput.parse(text)
        if (km == null) err = "Впиши пробег в километрах, например 1459,4."
        else {
            ScooterRepo.setOdometer(km)
            focus.clearFocus()
            edit = false
            snack.text("Одометр приложения: " + Fmt.n(km, 1) + " км — дальше считает от него.")
        }
    }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
        TextField56("Исправить, км", text, { text = it; err = "" }, Modifier.weight(1f), decimal = true, onDone = set, maxLen = 10)
        Spacer(Modifier.width(8.dp))
        BigButton("Задать", set)
    }
    val scooter = odo.scooterKm
    if (scooter != null) BigButton("Взять с руля (" + Fmt.n(scooter, 1) + " км)", {
        ScooterRepo.setOdometer(scooter)
        focus.clearFocus()
        edit = false
        snack.text("Взял с руля: " + Fmt.n(scooter, 1) + " км.")
    }, Modifier.fillMaxWidth().padding(top = 10.dp), style = BtnStyle.OUTLINED, small = true)
    if (err.isNotEmpty()) Line(err, c.critInk, Modifier.padding(top = 6.dp))
    else Line("Приложение прибавляет пробег из каждой поездки, даже когда руль после выключения начинает с меньшего числа.", c.muted, Modifier.padding(top = 6.dp))
}

private fun tenth(km: Double): Double = Math.round(km * 10) / 10.0

/** «Работа в фоне»: the battery exemption (Samsung's own list too) and the notifications, each row its own target. */
@Composable
private fun BackgroundSection() {
    val c = LocalBal.current
    val ui = LocalUi.current
    val sys = LocalSys.current
    val notif = sys.missingPerms.any { it.endsWith("POST_NOTIFICATIONS") }
    SectionHeader("РАБОТА В ФОНЕ")
    if (sys.ignoringBattery) {
        NavRow("Батарея: без ограничений",
            if (sys.samsung) "Samsung: «Никогда не засыпающие приложения» → Bird Air" else "Связь держится и с выключенным экраном",
            icon = AppIcons.Check, iconTint = c.goodInk, external = true, onClick = ui::openAppSettings)
    } else {
        NavRow("Разрешить работу в фоне", "Чтобы связь не рвалась с выключенным экраном",
            icon = AppIcons.Bat, iconTint = c.warnInk, onClick = ui::requestIgnoreBattery)
        if (sys.samsung) NavRow("Samsung: не засыпать", "«Никогда не засыпающие приложения» → Bird Air",
            icon = AppIcons.Gear, external = true, onClick = ui::openAppSettings)
    }
    if (notif) NavRow("Уведомления выключены", "Разрешить: поездка в шторке, «Отключить», предупреждения о батарее",
        icon = AppIcons.Bell, iconTint = c.warnInk, external = sys.permsBlocked,
        onClick = { if (sys.permsBlocked) ui.openAppSettings() else ui.requestPermissions() })
}

@Composable
private fun AboutSection() {
    val d: DecoderInfo by ScooterRepo.decoder.collectAsStateWithLifecycle()
    val open = rememberOpenUri()
    var note by rememberSaveable { mutableStateOf(false) }
    SectionHeader("О ПРИЛОЖЕНИИ")
    NavRow(if (d.version > 0) "Декодер v${d.version} (${d.source})" else "Декодер не загружен",
        if (note && d.note.isNotEmpty()) d.note else "С сайта — при запуске, если там новее; коснись — что в нём",
        onClick = { note = !note })
    NavRow("Сайт Bird Air Lab", "purple-palm.github.io/Bird-Air-Tweaking", external = true) { open(SITE) }
}

/** «События связи»: the service's last lines, newest first, errors in red. */
@Composable
private fun EventsSection() {
    val c = LocalBal.current
    val events by ScooterRepo.events.collectAsStateWithLifecycle()
    SectionHeader("СОБЫТИЯ СВЯЗИ")
    if (events.isEmpty()) Line("Пока пусто: здесь будут подключения и ошибки связи.", c.muted)
    for (e in events.asReversed()) Line(Fmt.clock(e.at) + " " + e.text, if (e.err) c.critInk else c.ink2, lineHeight = 22)
    Spacer(Modifier.height(4.dp))
}

private const val SITE = "https://purple-palm.github.io/Bird-Air-Tweaking/"
