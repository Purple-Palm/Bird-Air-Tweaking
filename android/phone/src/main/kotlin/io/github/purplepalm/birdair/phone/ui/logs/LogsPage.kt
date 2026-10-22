package io.github.purplepalm.birdair.phone.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.LocalLog
import io.github.purplepalm.birdair.phone.LogFiles
import io.github.purplepalm.birdair.phone.LogPeek
import io.github.purplepalm.birdair.phone.LogStatus
import io.github.purplepalm.birdair.phone.LogStore
import io.github.purplepalm.birdair.phone.LogsText
import io.github.purplepalm.birdair.phone.MarkerWords
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.Chip48
import io.github.purplepalm.birdair.phone.ui.IconButton48
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.NavRow
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.PlateTone
import io.github.purplepalm.birdair.phone.ui.SettingSwitch
import io.github.purplepalm.birdair.phone.ui.Tag
import io.github.purplepalm.birdair.phone.ui.TagTone
import io.github.purplepalm.birdair.phone.ui.TextField56
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.shell.LocalNav
import io.github.purplepalm.birdair.phone.ui.shell.LocalSnack
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi
import io.github.purplepalm.birdair.phone.ui.shell.SnackState
import io.github.purplepalm.birdair.phone.ui.shell.Sub
import io.github.purplepalm.birdair.phone.ui.shell.rememberOpenUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** At most this many unsent logs listed; the rest as «…и ещё N». */
private const val ROWS = 12

/**
 * «Логи»: the unsent logs (pull down to re-read them), GitHub and auto-send, the recording (its description → the file
 * name, save, share), the markers (the analyzer's words, one tap each, or the owner's own).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsPage(modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val c = LocalBal.current
    var refreshing by remember { mutableStateOf(false) }
    // «проверено 22:24»: the page opened or was pulled
    var checkedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { ui.refreshLogs() }
    LaunchedEffect(refreshing) { if (refreshing) { delay(REFRESH_MS); refreshing = false } }
    val refresh = { refreshing = true; checkedAt = System.currentTimeMillis(); ui.refreshLogs() }
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing, onRefresh = refresh, modifier = modifier, state = pull,
        indicator = { PullToRefreshDefaults.Indicator(pull, refreshing, Modifier.align(Alignment.TopCenter), containerColor = c.surface, color = c.flow) },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding)) {
            Unsent(checkedAt, refresh)
            GitHubBlock()
            Recording()
            Markers()
        }
    }
}

private const val REFRESH_MS = 700L

/** «НЕОТПРАВЛЕННЫЕ (3)»: one row per file with its tag and «Поделиться»; nothing left: what went last, or that all is there. */
@Composable
private fun Unsent(checkedAt: Long, refresh: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
    val log by ScooterRepo.log.collectAsStateWithLifecycle()
    val gh by ScooterRepo.gh.collectAsStateWithLifecycle()
    // while «Отправить все» runs its files stay listed (with «отправлен») though they leave the unsent list
    var run by remember { mutableStateOf(emptyList<LocalLog>()) }
    LaunchedEffect(log.bulkBusy, unsent) { run = if (log.bulkBusy) LogsText.runRows(run, unsent) else emptyList() }
    val rows = if (log.bulkBusy) LogsText.runRows(run, unsent) else unsent
    Head(
        "НЕОТПРАВЛЕННЫЕ" + if (rows.isNotEmpty()) " (${rows.size})" else "", trailing = "потяни вниз — обновить", first = true,
        // TalkBack has no pull: the header (one node) offers it as an action
        modifier = Modifier.semantics(mergeDescendants = true) { customActions = listOf(CustomAccessibilityAction("Обновить список") { refresh(); true }) },
    )
    if (rows.isEmpty()) {
        val e = LogsText.empty(log, gh, System.currentTimeMillis(), checkedAt)
        Plate(plateTone(e.tone), e.title, Modifier.padding(vertical = 4.dp), text = e.text)
    } else {
        val shown = rows.take(ROWS)
        val tags = LogsText.tags(shown, unsent, log.bulkBusy) { f -> LogFiles.isSent(f, sentRecord(f.name)) }
        shown.forEachIndexed { i, f ->
            key(f.name) {
                // the running recording's count keeps changing: not shown
                var entries by remember(f) { mutableStateOf(if (f.current) null else LogPeek.cached(f)) }
                LaunchedEffect(f) { if (!f.current && entries == null) entries = withContext(Dispatchers.IO) { LogPeek.entries(LogStore.dir(ctx), f) } }
                LogRow(f, tags[i], entries, first = i == 0) { ui.shareFile(f) }
            }
        }
        if (rows.size > ROWS) Text("…и ещё ${rows.size - ROWS}", color = LocalBal.current.muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
    }
    if (log.bulkErr && log.bulkMessage.isNotEmpty()) Line(log.bulkMessage, err = true)
}

/** What the phone recorded as uploaded for a file (the shots and a fresh start may have no prefs yet). */
private fun sentRecord(name: String) = try { ScooterRepo.prefs.sentLog(name) } catch (e: UninitializedPropertyAccessException) { null }

/** A file: 64 dp, the name, its tag and «462 КБ · с 21:42», a 48 dp share button; a hairline between rows. */
@Composable
private fun LogRow(f: LocalLog, tag: LogsText.Tag, entries: Int?, first: Boolean, onShare: () -> Unit) {
    val c = LocalBal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (first) Modifier else Modifier.drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Doc, contentDescription = null, tint = c.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(f.base, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (tag) {
                    LogsText.Tag.REC -> Tag("● идёт запись", TagTone.REC)
                    LogsText.Tag.SENT -> Tag("отправлен", TagTone.OK)
                    LogsText.Tag.SENDING -> Tag("отправляю…", TagTone.FLOW)
                    LogsText.Tag.NONE -> {}
                }
                if (tag != LogsText.Tag.NONE) Spacer(Modifier.width(6.dp))
                Text(LogsText.sub(f, entries), color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Tnum)
            }
        }
        Spacer(Modifier.width(10.dp))
        IconButton48(AppIcons.Share, "Поделиться: ${f.base}", onShare)
    }
}

/** The GitHub row (→ its own screen) and «Отправлять лог сам», with what the last upload said. */
@Composable
private fun GitHubBlock() {
    val c = LocalBal.current
    val nav = LocalNav.current
    val open = rememberOpenUri()
    val gh by ScooterRepo.gh.collectAsStateWithLifecycle()
    val hasToken by ScooterRepo.hasToken.collectAsStateWithLifecycle()
    val log by ScooterRepo.log.collectAsStateWithLifecycle()
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
    NavRow("GitHub", LogsText.ghSub(gh, hasToken), icon = AppIcons.Key) { nav.sub(Sub.GITHUB) }
    SettingSwitch("Отправлять лог сам", gh.auto, { v -> ScooterRepo.updateGh { it.copy(auto = v) } },
        hint = "Каждые 5 минут, пока есть связь, и сразу после «Отключить»")
    if (!hasToken && gh.auto) Line("Для автоотправки нужен токен.", err = true)
    if (log.ghMessage.isNotEmpty()) {
        val url = log.ghUrl
        Line(log.ghMessage, err = log.ghErr, onClick = url?.let { { open(it) } })
    }
}

/** «ИДЁТ ЗАПИСЬ» / «ЗАПИСЬ ОСТАНОВЛЕНА»: the description (→ the file name), «Сохранить», «Поделиться». */
@Composable
private fun Recording() {
    val c = LocalBal.current
    val ui = LocalUi.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val log by ScooterRepo.log.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    // the field's own state (a StateFlow round trip would make the cursor jump); every change goes to the recording
    var note by rememberSaveable { mutableStateOf(ScooterRepo.note.value) }
    val s = ScooterRepo.session
    val start = s?.takeIf { it.recorder.size > 0 || st.running }?.startedAtMs
    Head(if (st.running) "ИДЁТ ЗАПИСЬ" else "ЗАПИСЬ ОСТАНОВЛЕНА", trailing = LogsText.recording(st.running, log.entries, log.part, start, log.savedName != null))
    TextField56("Описание лога", note, { note = it; ScooterRepo.setNote(it) }, placeholder = "например: вдвоём, FLY, до отсечки",
        onDone = { focus.clearFocus() }, maxLen = ScooterRepo.NOTE_MAX)
    Text(LogsText.fileHint(note, s?.part?.base, st.deviceName, st.address), color = c.muted, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton("Сохранить", ui::saveLog, Modifier.weight(1f), icon = AppIcons.Save, style = BtnStyle.OUTLINED, small = true)
        BigButton("Поделиться", ui::shareLog, Modifier.weight(1f), icon = AppIcons.Share, style = BtnStyle.OUTLINED, small = true)
    }
    if (log.message.isNotEmpty()) Line(log.message, err = log.err)
}

/** «МЕТКИ»: the analyzer's six words, one tap each into the running log; an own marker; how they are read. */
@Composable
private fun Markers() {
    val c = LocalBal.current
    val snack = LocalSnack.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val markers by ScooterRepo.markers.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var own by rememberSaveable { mutableStateOf("") }
    val place = {
        if (own.isBlank()) snack.text("Сначала впиши текст метки")
        else placeMark(own, snack)?.let { own = ""; focus.clearFocus() }
        Unit
    }
    Head("МЕТКИ", trailing = LogsText.marks(markers.size))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in MarkerWords.PRESETS.chunked(3)) FlexRow(8.dp) {
            for (w in row) Chip48(w, { placeMark(w, snack) }, enabled = st.running)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextField56("Своя метка", own, { own = it }, Modifier.weight(1f), placeholder = "горка, вдвоём…", onDone = place, maxLen = 60)
        BigButton("Поставить", place, Modifier.height(56.dp), small = true, enabled = st.running)
    }
    Text("Эти слова понимает analyze_log.py; режим и фара отмечаются сами командами. На ходу — «Метка» на главном: одно касание, подпись — второе.",
        color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
}

/** A marker now with [label]; the snackbar says when («Газ» · 22:12:21) or why not (the repo's message). */
private fun placeMark(label: String, snack: SnackState): io.github.purplepalm.birdair.phone.Marker? {
    val m = ScooterRepo.mark(label)
    if (m != null) snack.text("«${m.label}» · ${ScooterRepo.markClock(m)}")
    else ScooterRepo.log.value.markMessage.takeIf { it.isNotEmpty() }?.let { snack.text(it) }
    return m
}

/**
 * One row of chips, each as wide as its word plus an equal share of what is left (the prototype's flex: 1 1 auto);
 * too narrow, they shrink in proportion.
 */
@Composable
private fun FlexRow(gap: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { ms, cs ->
        val g = gap.roundToPx()
        val w = cs.maxWidth
        if (ms.isEmpty()) return@Layout layout(w, 0) {}
        val want = ms.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val free = w - want.sum() - g * (ms.size - 1)
        val widths = if (free >= 0) want.mapIndexed { i, x -> x + free / ms.size + if (i == ms.lastIndex) free % ms.size else 0 }
        else { val total = want.sum().coerceAtLeast(1); want.map { x -> (x + free.toLong() * x / total).toInt().coerceAtLeast(0) } }
        val ps = ms.mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i], minHeight = 0, maxHeight = cs.maxHeight)) }
        val h = ps.maxOf { it.height }
        layout(w, h) {
            var x = 0
            for (p in ps) { p.place(x, (h - p.height) / 2); x += p.width + g }
        }
    }
}

/**
 * A section header like [io.github.purplepalm.birdair.phone.ui.SectionHeader] (13 sp SemiBold muted), but the title
 * keeps its line and [trailing] takes the rest, flush right (the kit's two weights split the row: «НЕОТПРАВЛЕН/НЫЕ (3)»).
 */
@Composable
private fun Head(text: String, modifier: Modifier = Modifier, trailing: String? = null, first: Boolean = false) {
    val c = LocalBal.current
    Row(
        modifier.fillMaxWidth().padding(top = if (first) 4.dp else 20.dp, bottom = 8.dp).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, style = Tnum, maxLines = 1)
        if (trailing != null) Text(
            trailing, color = c.muted, fontSize = 13.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

/** A message line: muted, red for an error; with [onClick] a 48 dp target (a commit link). */
@Composable
private fun Line(text: String, err: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = LocalBal.current
    val color = when { err -> c.critInk; onClick != null -> c.flow; else -> c.ink2 }
    Box(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick) else Modifier.padding(vertical = 6.dp)),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum) }
}

internal fun plateTone(t: LogsText.Tone): PlateTone = when (t) {
    LogsText.Tone.GOOD -> PlateTone.GOOD
    LogsText.Tone.WARN -> PlateTone.WARN
    LogsText.Tone.BAD -> PlateTone.CRIT
    LogsText.Tone.INFO -> PlateTone.INFO
}

/**
 * Pinned above the dock: «Метка» (one tap, while recording; the snackbar asks for its word) and the one send button by
 * state — «Отправить все (N)», «Отправляю k из N…», «Открыть коммит», «Отправить текущий», «Всё на GitHub».
 */
@Composable
fun LogsPinned() {
    val ui = LocalUi.current
    val snack = LocalSnack.current
    val open = rememberOpenUri()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val log: LogStatus by ScooterRepo.log.collectAsStateWithLifecycle()
    val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
    val send = LogsText.send(DashText.logsTile(unsent, log, System.currentTimeMillis()), log.bulkUrl, st.running, log.ghBusy)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.running) BigButton("Метка", { ScooterRepo.markNow()?.let { snack.mark(it) } }, icon = AppIcons.Flag, style = BtnStyle.OUTLINED)
        val m = Modifier.weight(1f)
        when (send.kind) {
            LogsText.SendKind.ALL -> BigButton(send.label, ui::sendAll, m, icon = AppIcons.Up)
            LogsText.SendKind.SENDING -> BigButton(send.label, {}, m, enabled = false, progress = send.progress)
            LogsText.SendKind.COMMIT -> BigButton(send.label, { log.bulkUrl?.let(open) }, m, icon = AppIcons.Ext, style = BtnStyle.OUTLINED)
            LogsText.SendKind.CURRENT -> BigButton(send.label, ui::sendLog, m, icon = AppIcons.Up, style = BtnStyle.OUTLINED, enabled = send.enabled)
            LogsText.SendKind.DONE -> BigButton(send.label, {}, m, icon = AppIcons.Up, style = BtnStyle.OUTLINED, enabled = false)
        }
    }
}
