package io.github.purplepalm.birdair.phone.ui.logs

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.GhConfig
import io.github.purplepalm.birdair.phone.LinkStatus
import io.github.purplepalm.birdair.phone.LocalLog
import io.github.purplepalm.birdair.phone.LogStatus
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.NavRow
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.SwitchRow
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.UiActions
import io.github.purplepalm.birdair.phone.ui.shell.LocalNav
import io.github.purplepalm.birdair.phone.ui.shell.LocalSnack
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi
import io.github.purplepalm.birdair.phone.ui.shell.Sub
import io.github.purplepalm.birdair.phone.ui.shell.rememberOpenUri

/** «Логи» (a stub hosting the old card; P2 builds the page): the recording, markers, GitHub, the unsent logs. */
@Composable
fun LogsPage(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LogCard(ui, st)
    }
}

/**
 * Pinned above the dock: «Метка» (one tap, while recording) and the one send button by state — «Отправить все (N)»,
 * «Отправляю k из N…», «Открыть коммит», «Отправить текущий», «Всё на GitHub».
 */
@Composable
fun LogsPinned() {
    val ui = LocalUi.current
    val snack = LocalSnack.current
    val open = rememberOpenUri()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val log by ScooterRepo.log.collectAsStateWithLifecycle()
    val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
    val tile = DashText.logsTile(unsent, log, System.currentTimeMillis())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.running) BigButton("Метка", { ScooterRepo.markNow()?.let { snack.mark(it) } }, icon = AppIcons.Flag, style = BtnStyle.OUTLINED)
        val m = Modifier.weight(1f)
        when (tile?.kind) {
            DashText.LogsKind.UNSENT -> BigButton("Отправить все (${tile.n})", ui::sendAll, m, icon = AppIcons.Up)
            DashText.LogsKind.SENDING -> BigButton("Отправляю ${minOf(tile.n, tile.k + 1)} из ${tile.n}…", {}, m, enabled = false, progress = tile.k.toFloat() / tile.n)
            DashText.LogsKind.SENT -> BigButton("Открыть коммит", { log.bulkUrl?.let(open) }, m, icon = AppIcons.Ext, style = BtnStyle.OUTLINED)
            null -> if (st.running) BigButton(if (log.ghBusy) "Отправляю…" else "Отправить текущий", ui::sendLog, m, icon = AppIcons.Up, style = BtnStyle.OUTLINED, enabled = !log.ghBusy)
                else BigButton("Всё на GitHub", {}, m, icon = AppIcons.Up, style = BtnStyle.OUTLINED, enabled = false)
        }
    }
}

@Composable
private fun LogCard(ui: UiActions, st: LinkStatus) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val log: LogStatus by ScooterRepo.log.collectAsStateWithLifecycle()
    val gh: GhConfig by ScooterRepo.gh.collectAsStateWithLifecycle()
    val hasToken by ScooterRepo.hasToken.collectAsStateWithLifecycle()
    val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
    // the field's own state (a StateFlow round trip would make the cursor jump); every change goes to the recording
    var note by rememberSaveable { mutableStateOf(ScooterRepo.note.value) }
    var mark by rememberSaveable { mutableStateOf("") }
    val uri = LocalUriHandler.current
    Panel("Лог") {
        Text("Записей: " + Fmt.n(log.entries.toDouble()) + (if (log.part > 1) " · часть ${log.part}" else "") + (log.savedName?.let { " · файл $it" } ?: ""),
            color = c.ink2, fontSize = 14.sp)
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(ScooterRepo.NOTE_MAX); ScooterRepo.setNote(note) }, label = { Text("Описание лога") },
            placeholder = { Text("например: вдвоём, FLY, до отсечки") }, minLines = 1, maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth(),
        )
        Msg("Как подпись на сайте: из описания при первой отправке берётся имя файла (потом не меняется), весь текст — " +
            "в «note» лога и в коммит. Переходит на следующую запись — поменяй, если поездка другая.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = mark, onValueChange = { mark = it.take(60) }, singleLine = true, label = { Text("Метка") },
                placeholder = { Text("газ, тормоз, горка…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ScooterRepo.mark(mark) != null) mark = "" }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { if (ScooterRepo.mark(mark) != null) mark = "" }, enabled = st.running && mark.isNotBlank(),
                modifier = Modifier.height(56.dp)) { Text("Метка") }
        }
        Msg(log.markMessage, log.markErr)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = ui::saveLog, modifier = Modifier.weight(1f).height(48.dp)) { Text("Сохранить") }
            OutlinedButton(onClick = ui::shareLog, modifier = Modifier.weight(1f).height(48.dp)) { Text("Поделиться") }
        }
        Msg(log.message, log.err)
        HorizontalDivider(color = c.hair)
        NavRow("GitHub", gh.owner + "/" + gh.repo + " · " + gh.branch + "/" + gh.folder + " · " + if (hasToken) "токен сохранён" else "нет токена",
            icon = AppIcons.Key) { nav.sub(Sub.GITHUB) }
        SwitchRow("Отправлять лог сам", gh.auto, { v -> ScooterRepo.updateGh { it.copy(auto = v) } },
            hint = "Каждые 5 минут, пока есть связь, и сразу после обрыва. Длинная поездка уходит частями по 250 000 записей. Всегда обезличенный.")
        Button(onClick = ui::sendLog, enabled = !log.ghBusy, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(if (log.ghBusy) "Отправляю…" else "Отправить в GitHub") }
        if (log.ghMessage.isNotEmpty()) {
            val url = log.ghUrl
            Text(log.ghMessage, color = if (log.ghErr) c.critInk else c.ink2, fontSize = 13.sp,
                modifier = if (url != null) Modifier.clickable { uri.openUri(url) } else Modifier)
        }
        if (!hasToken && gh.auto) Msg("Для автоотправки нужен токен.", err = true)
        UnsentLogs(unsent, log, ui)
    }
}

@Composable
private fun UnsentLogs(list: List<LocalLog>, log: LogStatus, ui: UiActions) {
    val c = LocalBal.current
    HorizontalDivider(color = c.hair)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Неотправленные логи" + if (list.isNotEmpty()) " (${list.size})" else "", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton(onClick = ui::refreshLogs, modifier = Modifier.height(48.dp)) { Text("Обновить") }
    }
    if (list.isEmpty()) { Msg("Все сохранённые логи уже на GitHub."); Msg(log.bulkMessage, log.bulkErr); return }
    for (f in list.take(12)) {
        Row(
            Modifier.fillMaxWidth().border(1.dp, c.hair, RoundedCornerShape(10.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(f.name, color = c.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Fmt.dayTime(f.mtime) + " · " + Fmt.n(f.size / 1024.0) + " КБ" + if (f.current) " · идёт запись" else "", color = c.muted, fontSize = 12.sp, style = Tnum)
            }
            TextButton(onClick = { ui.shareFile(f) }, modifier = Modifier.height(48.dp)) { Text("Поделиться") }
        }
    }
    if (list.size > 12) Msg("…и ещё ${list.size - 12}")
    Msg(log.bulkMessage, log.bulkErr)
}
