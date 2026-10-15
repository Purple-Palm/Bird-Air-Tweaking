package io.github.purplepalm.birdair.phone.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.phone.ControlState
import io.github.purplepalm.birdair.phone.Flow
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.Found
import io.github.purplepalm.birdair.phone.GhConfig
import io.github.purplepalm.birdair.phone.LinkStatus
import io.github.purplepalm.birdair.phone.LogStatus
import io.github.purplepalm.birdair.phone.Mode
import io.github.purplepalm.birdair.phone.ScanState
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.StatusText
import io.github.purplepalm.birdair.phone.Trip
import io.github.purplepalm.birdair.phone.UiSettings
import io.github.purplepalm.birdair.phone.Units
import io.github.purplepalm.birdair.phone.Watch
import io.github.purplepalm.birdair.phone.WatchStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the screen asks the activity for (permissions, system screens, the service). */
interface UiActions {
    fun requestPermissions()
    fun openAppSettings()
    fun enableBluetooth()
    fun connect()
    fun disconnect()
    fun scan(all: Boolean)
    fun closeScan()
    fun choose(f: Found)
    fun saveLog()
    fun shareLog()
    fun sendLog()
    fun openOnWatch()
    fun requestIgnoreBattery()
}

/** Activity-side state the screen shows. */
data class SystemState(
    val missingPerms: List<String> = emptyList(),
    val permsBlocked: Boolean = false,
    val bluetoothOn: Boolean = true,
    val ignoringBattery: Boolean = true,
    val samsung: Boolean = false,
)

@Composable
fun MainScreen(ui: UiActions, sys: SystemState) {
    val c = LocalBal.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val scan by ScooterRepo.scan.collectAsStateWithLifecycle()
    val settings by ScooterRepo.settings.collectAsStateWithLifecycle()

    // «Не гасить экран»: only while this screen is on top and the setting is on
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // «Часы» status while the screen is visible (the service refreshes it itself while it runs)
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!ScooterRepo.status.value.running) Watch.nodes(ctx)
                delay(10_000)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(t, st, scan)
        if (sys.missingPerms.isNotEmpty()) PermissionCard(sys, ui)
        if (!sys.bluetoothOn) BluetoothCard(ui)
        ConnectCard(st, scan, ui)
        if (scan.shown) ScanCard(scan, ui)
        SpeedCard(t, settings.units)
        PowerCard(t)
        BatteryCard(t)
        TripCard(t, settings.units)
        ControlCard(t, st)
        WatchCard(ui)
        LogCard(ui)
        SettingsCard(settings)
        if (!settings.batteryHintHidden && (!sys.ignoringBattery || sys.samsung)) BatteryHintCard(sys, ui)
        DecoderFooter()
        Spacer(Modifier.height(24.dp))
    }
}

// -- header, connection

@Composable
private fun Header(t: Telemetry, st: LinkStatus, scan: ScanState) {
    val c = LocalBal.current
    val tone = when {
        st.link == Link.CONNECTED && t.streaming -> Tone.GOOD
        st.link == Link.CONNECTED || st.link == Link.CONNECTING -> Tone.WARN
        st.link == Link.RECONNECTING -> Tone.CRIT
        scan.scanning -> Tone.FLOW
        else -> Tone.NEUTRAL
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Bird Air", color = c.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
        Pill(StatusText.pill(st.link, t.streaming, scan.scanning), tone)
    }
}

@Composable
private fun PermissionCard(sys: SystemState, ui: UiActions) {
    val notifOnly = sys.missingPerms.all { it.endsWith("POST_NOTIFICATIONS") }
    Panel(if (notifOnly) "Уведомления выключены" else "Нужны разрешения") {
        if (!notifOnly) Msg(
            if (sys.missingPerms.any { it.endsWith("LOCATION") }) "«Местоположение» — на этой версии Android без него Bluetooth не ищет устройства. Координаты приложение не читает."
            else "«Устройства поблизости» — чтобы найти самокат и держать с ним связь по Bluetooth. Местоположение приложение не читает."
        )
        if (sys.missingPerms.any { it.endsWith("POST_NOTIFICATIONS") }) {
            Msg("«Уведомления» — значок поездки в шторке со скоростью и тягой и кнопкой «Отключить». Связь работает и без него.")
        }
        if (sys.permsBlocked) {
            Msg("Android больше не показывает запрос: разреши в настройках приложения → Разрешения.", err = true)
            Button(onClick = ui::openAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки") }
        } else Button(onClick = ui::requestPermissions, modifier = Modifier.fillMaxWidth()) { Text("Разрешить") }
    }
}

@Composable
private fun BluetoothCard(ui: UiActions) {
    Panel("Bluetooth выключен") {
        Msg("Без Bluetooth связи с самокатом нет. Включи его — подключение продолжится само.")
        Button(onClick = ui::enableBluetooth, modifier = Modifier.fillMaxWidth()) { Text("Включить Bluetooth") }
    }
}

@Composable
private fun ConnectCard(st: LinkStatus, scan: ScanState, ui: UiActions) {
    val c = LocalBal.current
    val events by ScooterRepo.events.collectAsStateWithLifecycle()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(st.deviceName ?: if (st.address != null) "Самокат" else "Самокат не выбран", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(st.address ?: "Нажми «Подключить» — найду его поблизости", color = c.muted, fontSize = 13.sp)
            }
        }
        val pad = PaddingValues(horizontal = 12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.running) {
                Button(onClick = ui::disconnect, modifier = Modifier.weight(1f).height(52.dp), contentPadding = pad,
                    colors = ButtonDefaults.buttonColors(containerColor = c.crit, contentColor = Color.White)) { Text("Отключить", fontSize = 16.sp, maxLines = 1) }
            } else {
                Button(onClick = ui::connect, enabled = !scan.scanning, modifier = Modifier.weight(1f).height(52.dp), contentPadding = pad) {
                    Text("Подключить", fontSize = 16.sp, maxLines = 1)
                }
            }
            OutlinedButton(onClick = { ui.scan(false) }, enabled = !scan.scanning, modifier = Modifier.weight(1f).height(52.dp), contentPadding = pad) {
                Text("Выбрать другой", maxLines = 1)
            }
        }
        val last = events.lastOrNull()
        if (last != null) Msg(last.text, last.err)
    }
}

@Composable
private fun ScanCard(scan: ScanState, ui: UiActions) {
    val c = LocalBal.current
    Panel(if (scan.scanning) "Поиск…" else "Найдено", trailing = { TextButton(onClick = ui::closeScan) { Text("Закрыть") } }) {
        Msg(scan.message)
        for (f in scan.found) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.hair, RoundedCornerShape(10.dp))
                    .clickable { ui.choose(f) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name ?: "(без имени)", color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(f.address, color = c.muted, fontSize = 12.sp)
                }
                Text("${f.rssi} дБм", color = c.ink2, fontSize = 13.sp, style = Tnum)
            }
        }
        if (!scan.scanning) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ui.scan(false) }) { Text("Искать ещё") }
            OutlinedButton(onClick = { ui.scan(true) }) { Text("Все устройства") }
        }
    }
}

// -- instruments

@Composable
private fun SpeedCard(t: Telemetry, units: String) {
    val c = LocalBal.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скорость", color = c.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            ModeChip(Mode.of(t.mode), units)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.n(Units.speed(t.speedKmh, units), 1), color = if (t.streaming) c.ink else c.muted, fontSize = 96.sp, lineHeight = 96.sp,
                fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
            Text(" " + Units.speedLabel(units), color = c.ink2, fontSize = 22.sp, modifier = Modifier.padding(bottom = 16.dp))
        }
        if (t.link == Link.CONNECTED && !t.streaming) Msg("Руль молчит: жду кадры после запроса +SVGT=?…")
    }
}

@Composable
private fun ModeChip(m: Mode?, units: String) {
    val c = LocalBal.current
    val color = when (m) { Mode.TAKEOFF -> c.modeTake; Mode.GLIDE -> c.modeGlide; Mode.FLY -> c.modeFly; null -> c.base }
    Column(
        Modifier
            .border(2.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(m?.title ?: "—", color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (m != null) Text(m.limit(units), color = c.muted, fontSize = 12.sp)
    }
}

@Composable
private fun PowerCard(t: Telemetry) {
    val c = LocalBal.current
    val p = t.powerW
    val live = t.streaming && p != null
    val flow = Flow.of(t)
    val color = when {
        p == null || !live -> c.muted
        p < 0 -> c.flow
        abs(p) < 5 -> c.ink
        else -> c.draw
    }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(flow.label, color = if (live) color else c.muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text("шкала " + Fmt.scale(t.peakW), color = c.muted, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (p == null) "—" else Fmt.n(abs(p)), color = color, fontSize = 64.sp, lineHeight = 64.sp, fontWeight = FontWeight.SemiBold, style = Big, maxLines = 1)
            Text(" Вт", color = c.ink2, fontSize = 20.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        PowerBar(if (live && p != null) (p / t.peakW).toFloat() else 0f, live)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("← рекуперация", color = c.flow, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("тяга →", color = c.draw, fontSize = 13.sp)
        }
        Text(
            "Ток " + Fmt.n(t.currentA, 1) + " А · " + Fmt.n(t.voltage, 1) + " В" + (if (t.motorOn == true) " · мотор тянет" else ""),
            color = c.ink2, fontSize = 14.sp, style = Tnum,
        )
    }
}

@Composable
private fun BatteryCard(t: Telemetry) {
    val c = LocalBal.current
    Panel("Батарея") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Fmt.n(t.batteryPct), color = c.ink, fontSize = 48.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold, style = Big)
            Text(" %", color = c.ink2, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.n(t.voltage, 2) + " В", color = c.ink, fontSize = 18.sp, style = Tnum)
                Text(Fmt.n(t.remainingMah) + " / " + Fmt.n(t.fullMah) + " мА·ч", color = c.ink2, fontSize = 13.sp, style = Tnum)
            }
        }
        LevelBar(t.batteryPct)
        if (t.temp1C != null || t.temp2C != null) {
            Msg("Температуры: " + Fmt.n(t.temp1C) + " / " + Fmt.n(t.temp2C) + " °C (похоже на батарею, термометром не сверено)")
        }
    }
}

@Composable
private fun TripCard(t: Telemetry, units: String) {
    val d = Units.distLabel(units)
    val wh = t.whOut - t.whIn
    val perKm = Trip.whPerKm(t.whOut, t.whIn, ScooterRepo.session?.odoStartKm, t.odometerKm)
    val perUnit = perKm?.let { if (units == Units.MPH) it * Units.MILE else it }
    Panel("Поездка") {
        Row {
            Metric("С включения", Fmt.n(Units.dist(t.tripKm, units), 1), d, Modifier.weight(1f))
            Metric("Одометр", Fmt.n(Units.dist(t.odometerKm, units), 1), d, Modifier.weight(1f))
        }
        Row {
            Metric("Средняя", Fmt.n(Units.speed(t.avgSpeedKmh, units), 1), Units.speedLabel(units), Modifier.weight(1f))
            Metric("Расход", Fmt.n(perUnit, 1), "Вт·ч/$d", Modifier.weight(1f))
        }
        Row {
            Metric("Энергия за сессию", Fmt.n(wh, 1), "Вт·ч", Modifier.weight(1f))
            Metric("Рекуперация", Fmt.n(t.whIn, 1), "Вт·ч", Modifier.weight(1f), color = LocalBal.current.flow)
        }
        Msg("Из батареи " + Fmt.n(t.whOut, 1) + " Вт·ч" + if (t.whOut > 0.05) ", вернулось " + Fmt.n(100 * t.whIn / t.whOut, 1) + " %" else "")
    }
}

// -- control

@Composable
private fun ControlCard(t: Telemetry, st: LinkStatus) {
    val c = LocalBal.current
    val ctl: ControlState by ScooterRepo.control.collectAsStateWithLifecycle()
    val can = ctl.allow && st.link == Link.CONNECTED
    Panel("Управление") {
        SwitchRow("Разрешить команды", ctl.allow, { ScooterRepo.setAllowCommands(it) },
            hint = "Только команды приложения Bird: режим и фара. Выключается при каждом запуске.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in Mode.entries) {
                val on = t.mode == m.n
                val color = when (m) { Mode.TAKEOFF -> c.modeTake; Mode.GLIDE -> c.modeGlide; Mode.FLY -> c.modeFly }
                OutlinedButton(
                    onClick = { Commands.mode(m.n)?.let { ScooterRepo.command(it) } }, enabled = can,
                    modifier = Modifier.weight(1f).height(56.dp),
                    border = androidx.compose.foundation.BorderStroke(if (on) 3.dp else 1.dp, if (can || on) color else c.hair),
                    contentPadding = PaddingValues(4.dp),
                ) { Text(m.title, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { ScooterRepo.command(Commands.light(true)) }, enabled = can, modifier = Modifier.weight(1f).height(52.dp)) { Text("Фара вкл") }
            OutlinedButton(onClick = { ScooterRepo.command(Commands.light(false)) }, enabled = can, modifier = Modifier.weight(1f).height(52.dp)) { Text("Фара выкл") }
        }
        Msg(ctl.message, ctl.err)
    }
}

// -- watch

@Composable
private fun WatchCard(ui: UiActions) {
    val c = LocalBal.current
    val w: WatchStatus by ScooterRepo.watch.collectAsStateWithLifecycle()
    // re-evaluated with the telemetry ticks
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val seen = w.helloAt > 0 && SystemClock.elapsedRealtime() - w.helloAt < 20_000
    val status = when {
        !w.available -> "нет (Wear OS недоступен на телефоне)"
        w.nodes.isNotEmpty() -> "на связи — " + w.nodes.joinToString()
        !w.checked -> "проверяю…"
        else -> "нет"
    }
    Panel("Часы") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Часы: ", color = c.ink2, fontSize = 15.sp)
            Text(status, color = if (w.nodes.isNotEmpty()) c.goodInk else c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (seen) Msg("Приложение на часах открыто" + if (t.link == Link.IDLE) " — подключись к самокату, и оно оживёт." else ".")
        Button(onClick = ui::openOnWatch, enabled = w.nodes.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Открыть на часах") }
        Msg(w.message, w.err)
    }
}

// -- log, GitHub

@Composable
private fun LogCard(ui: UiActions) {
    val c = LocalBal.current
    val log: LogStatus by ScooterRepo.log.collectAsStateWithLifecycle()
    val gh: GhConfig by ScooterRepo.gh.collectAsStateWithLifecycle()
    val hasToken by ScooterRepo.hasToken.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Panel("Лог") {
        Text("Записей: " + Fmt.n(log.entries.toDouble()) + (log.savedName?.let { " · файл $it" } ?: ""), color = c.ink2, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ui::saveLog, modifier = Modifier.weight(1f)) { Text("Сохранить") }
            OutlinedButton(onClick = ui::shareLog, modifier = Modifier.weight(1f)) { Text("Поделиться") }
        }
        Msg(log.message, log.err)
        HorizontalDivider(color = c.hair)
        Text("GitHub: " + gh.dest, color = c.ink2, fontSize = 13.sp)
        SwitchRow("Отправлять лог сам", gh.auto, { v -> ScooterRepo.updateGh { it.copy(auto = v) } },
            hint = "Каждые 5 минут, пока есть связь, и сразу после обрыва. Всегда обезличенный.")
        Button(onClick = ui::sendLog, enabled = !log.ghBusy, modifier = Modifier.fillMaxWidth()) { Text(if (log.ghBusy) "Отправляю…" else "Отправить в GitHub") }
        if (log.ghMessage.isNotEmpty()) {
            val url = log.ghUrl
            Text(log.ghMessage, color = if (log.ghErr) c.critInk else c.ink2, fontSize = 13.sp,
                modifier = if (url != null) Modifier.clickable { uri.openUri(url) } else Modifier)
        }
        if (!hasToken && gh.auto) Msg("Для автоотправки нужен токен.", err = true)
        TextButton(onClick = { open = !open }) { Text(if (open) "Скрыть настройки GitHub" else "Настройки GitHub") }
        if (open) GhSettings(gh, hasToken)
    }
}

@Composable
private fun GhSettings(gh: GhConfig, hasToken: Boolean) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Field("Владелец (owner)", gh.owner) { v -> ScooterRepo.updateGh { it.copy(owner = v.trim()) } }
        Field("Репозиторий", gh.repo) { v -> ScooterRepo.updateGh { it.copy(repo = v.trim()) } }
        Field("Ветка", gh.branch) { v -> ScooterRepo.updateGh { it.copy(branch = v.trim()) } }
        Field("Папка", gh.folder) { v -> ScooterRepo.updateGh { it.copy(folder = v.trim()) } }
        OutlinedTextField(
            value = token, onValueChange = { token = it }, singleLine = true,
            label = { Text(if (hasToken) "Токен сохранён — вставь новый, чтобы заменить" else "Fine-grained токен (Contents: Read and write)") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val clean = io.github.purplepalm.birdair.phone.GitHubApi.cleanToken(token)
                if (clean.isEmpty()) { msg = "Вставь токен."; err = true; return@OutlinedButton }
                if (!io.github.purplepalm.birdair.phone.GitHubApi.tokenOk(clean)) { msg = "В токене есть буквы не из латиницы или лишние знаки — скопируй его заново."; err = true; return@OutlinedButton }
                ScooterRepo.setToken(clean); token = ""; msg = "Токен сохранён в приложении."; err = false
            }, modifier = Modifier.weight(1f)) { Text("Сохранить токен") }
            OutlinedButton(onClick = {
                msg = "Проверяю…"; err = false
                scope.launch { val r = io.github.purplepalm.birdair.phone.LogStore.check(); msg = r; err = !r.startsWith("Доступ есть") }
            }, modifier = Modifier.weight(1f)) { Text("Проверить доступ") }
        }
        if (hasToken) TextButton(onClick = { ScooterRepo.setToken(""); msg = "Токен удалён из приложения."; err = false }) { Text("Забыть токен") }
        Msg(msg, err)
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    var v by rememberSaveable(label) { mutableStateOf(value) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onChange(it) }, singleLine = true, label = { Text(label) },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), modifier = Modifier.fillMaxWidth(),
    )
}

// -- settings, hints

@Composable
private fun SettingsCard(s: UiSettings) {
    Panel("Настройки") {
        Text("Скорость и расстояние", color = LocalBal.current.ink2, fontSize = 14.sp)
        Segments(listOf(Units.KMH to "км/ч, км", Units.MPH to "mph, мили"), s.units) { u -> ScooterRepo.updateUi { it.copy(units = u) } }
        Text("Тема", color = LocalBal.current.ink2, fontSize = 14.sp)
        Segments(listOf("dark" to "Тёмная", "system" to "Как в системе"), s.theme) { v -> ScooterRepo.updateUi { it.copy(theme = v) } }
        SwitchRow("Не гасить экран", s.keepScreenOn, { v -> ScooterRepo.updateUi { it.copy(keepScreenOn = v) } },
            hint = "Только пока открыт этот экран. В фоне связь держится и с выключенным экраном.")
    }
}

@Composable
private fun Segments(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val c = LocalBal.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = key == selected, onClick = { onPick(key) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = c.flow.copy(alpha = 0.18f), activeContentColor = c.ink, activeBorderColor = c.flow),
            ) { Text(label) }
        }
    }
}

@Composable
private fun BatteryHintCard(sys: SystemState, ui: UiActions) {
    Panel("Работа в фоне") {
        if (!sys.ignoringBattery) {
            Msg("Чтобы связь не рвалась с выключенным экраном, разреши приложению работать без ограничений батареи.")
            Button(onClick = ui::requestIgnoreBattery, modifier = Modifier.fillMaxWidth()) { Text("Разрешить работу в фоне") }
        }
        if (sys.samsung) {
            Msg("На Samsung ещё: Настройки → Батарея → Ограничения фоновой работы → «Никогда не засыпающие приложения» → добавь Bird Air. " +
                "И в настройках приложения: Батарея → «Без ограничений».")
            OutlinedButton(onClick = ui::openAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки приложения") }
        }
        TextButton(onClick = { ScooterRepo.updateUi { it.copy(batteryHintHidden = true) } }) { Text("Понятно, скрыть") }
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
