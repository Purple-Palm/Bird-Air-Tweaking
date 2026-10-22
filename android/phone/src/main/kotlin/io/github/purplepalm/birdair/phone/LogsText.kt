package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.LogRecorder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** «Логи» and its GitHub screen: the lines they show, pure (LogsTextTest). */
object LogsText {
    /** «462 КБ», «1,1 МБ». */
    fun size(bytes: Long): String =
        if (bytes < 1000L * 1024) Fmt.n(bytes.coerceAtLeast(0) / 1024.0) + " КБ" else Fmt.n(bytes / 1048576.0, 1) + " МБ"

    /** «9806 записей», «39 251 запись». */
    fun entries(n: Int): String = Fmt.n(n.toDouble()) + " " + DashText.plural(n, "запись", "записи", "записей")

    /** When a file's part started, from its name (<yyyyMMdd-HHmmss>-…, the phone's clock then): [day] «20.10 21:42», [time] «21:42». */
    data class Start(val day: String, val time: String)

    private val STAMP = Regex("^(\\d{8}-\\d{6})")
    private val PARSE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val DAY = DateTimeFormatter.ofPattern("dd.MM HH:mm")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun start(name: String): Start? {
        val m = STAMP.find(name) ?: return null
        return try {
            val t = LocalDateTime.parse(m.value, PARSE)
            Start(DAY.format(t), TIME.format(t))
        } catch (e: Exception) { null }
    }

    /** Under a file's name: «462 КБ · с 21:42» (the running recording's), «118 КБ · 20.10 19:55 · 9806 записей». */
    fun sub(f: LocalLog, entries: Int?): String {
        val st = start(f.name)
        val out = mutableListOf(size(f.size))
        if (f.current) out += "с " + (st?.time ?: Fmt.clock(f.mtime, seconds = false))
        else {
            out += st?.day ?: Fmt.dayTime(f.mtime)
            if (entries != null) out += entries(entries)
        }
        return out.joinToString(" · ")
    }

    enum class Tag { NONE, REC, SENT, SENDING }

    /**
     * The tag of each row ([rows] as shown, newest first). Not sending: «● идёт запись» on the running recording's files.
     * While «Отправить все» runs ([busy]) it goes oldest first: a row gone from [unsent], or [sent] by now, is «отправлен»,
     * the oldest of the rest «отправляю…»; the others keep «● идёт запись» if they have it.
     */
    fun tags(rows: List<LocalLog>, unsent: List<LocalLog>, busy: Boolean, sent: (LocalLog) -> Boolean): List<Tag> {
        if (!busy) return rows.map { if (it.current) Tag.REC else Tag.NONE }
        val now = unsent.associateBy { it.name }
        val done = rows.map { r -> now[r.name].let { it == null || sent(it) } }
        val next = rows.indices.filter { !done[it] }.minByOrNull { rows[it].mtime }
        return rows.mapIndexed { i, r ->
            when {
                done[i] -> Tag.SENT
                i == next -> Tag.SENDING
                r.current -> Tag.REC
                else -> Tag.NONE
            }
        }
    }

    /** The rows while «Отправить все» runs: what it started with ([run]) and what is unsent now (fresher), newest first. */
    fun runRows(run: List<LocalLog>, unsent: List<LocalLog>): List<LocalLog> {
        val m = LinkedHashMap<String, LocalLog>()
        for (f in run) m[f.name] = f
        for (f in unsent) m[f.name] = f
        return m.values.sortedByDescending { it.mtime }
    }

    /** «main/logs». */
    fun where(gh: GhConfig): String = listOf(gh.branch.trim(), gh.folder.trim().trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    /** The GitHub row: «Purple-Palm/Bird-Air-Tweaking · main/logs · токен сохранён». */
    fun ghSub(gh: GhConfig, hasToken: Boolean): String =
        "${gh.owner}/${gh.repo} · ${where(gh)} · " + if (hasToken) "токен сохранён" else "нет токена"

    /** The recording's header: «38 120 записей · часть 1» while it runs, «лог 20.10 21:42 сохранён» after; null before any ([startMs] null). */
    fun recording(running: Boolean, entries: Int, part: Int, startMs: Long?, saved: Boolean): String? = when {
        running -> entries(entries) + " · часть $part"
        startMs != null -> "лог " + Fmt.dayTime(startMs) + if (saved) " сохранён" else ""
        else -> null
    }

    /** Under «Описание лога»: the slug it gives the file (device name and id hidden, like the file), or the name fixed at the first upload. */
    fun fileHint(note: String, fixedBase: String?, deviceName: String?, deviceId: String?): String =
        if (fixedBase != null) "Имя файла закреплено при первой отправке: $fixedBase"
        else "Попадёт в лог и в имя файла: " + LogRecorder.slug(Notes.clean(note)?.let { Notes.hide(it, deviceName, deviceId) })

    enum class SendKind { ALL, SENDING, COMMIT, CURRENT, DONE }

    /** The pinned send button: its job, label, whether it takes a tap, the bar behind its label. */
    data class Send(val kind: SendKind, val label: String, val enabled: Boolean, val progress: Float? = null)

    /** By [DashText.logsTile]: «Отправить все (3)» → «Отправляю 2 из 3…» → «Открыть коммит»; nothing unsent: «Отправить текущий» while recording, else «Всё на GitHub». */
    fun send(tile: DashText.LogsTile?, commitUrl: String?, running: Boolean, ghBusy: Boolean): Send = when (tile?.kind) {
        DashText.LogsKind.UNSENT -> Send(SendKind.ALL, "Отправить все (${tile.n})", true)
        DashText.LogsKind.SENDING -> Send(SendKind.SENDING, "Отправляю ${minOf(tile.n, tile.k + 1)} из ${tile.n}…", false, tile.k.toFloat() / tile.n.coerceAtLeast(1))
        DashText.LogsKind.SENT -> if (commitUrl != null) Send(SendKind.COMMIT, "Открыть коммит", true) else rest(running, ghBusy)
        null -> rest(running, ghBusy)
    }

    private fun rest(running: Boolean, ghBusy: Boolean): Send =
        if (running) Send(SendKind.CURRENT, if (ghBusy) "Отправляю…" else "Отправить текущий", !ghBusy)
        else Send(SendKind.DONE, "Всё на GitHub", false)

    enum class Tone { GOOD, WARN, BAD, INFO }

    /** A plate's tone and lines. */
    data class Result(val tone: Tone, val title: String, val text: String? = null)

    /**
     * Nothing unsent: «Отправлено 3 лога · коммит 7c1e2a9» / «main/logs · только что» for [DashText.SENT_SHOW_MS] after
     * «Отправить все», else «Все сохранённые логи уже на GitHub» / «main/logs · проверено 22:24» ([checkedAtMs]).
     */
    fun empty(log: LogStatus, gh: GhConfig, nowMs: Long, checkedAtMs: Long): Result {
        val tile = DashText.logsTile(emptyList(), log, nowMs)
        return if (tile?.kind == DashText.LogsKind.SENT) {
            val ago = if (nowMs - log.bulkAt < 60_000) "только что" else "в " + Fmt.clock(log.bulkAt, seconds = false)
            Result(Tone.GOOD, "Отправлено " + DashText.logs(tile.n) + (tile.commit?.let { " · коммит $it" } ?: ""), where(gh) + " · " + ago)
        } else Result(Tone.INFO, "Все сохранённые логи уже на GitHub", where(gh) + " · проверено " + Fmt.clock(checkedAtMs, seconds = false))
    }

    /** [LogStore.check]'s answer as a plate: «Доступ есть» and the rest (a warning when there is no write right), else the error. */
    fun checked(msg: String): Result {
        val ok = "Доступ есть"
        if (!msg.startsWith(ok)) return Result(Tone.BAD, msg)
        val rest = msg.removePrefix(ok).trimStart(':', ' ').ifEmpty { null }
        return Result(if (msg.contains("нет права записи")) Tone.WARN else Tone.GOOD, ok, rest)
    }

    /** What «Сохранить» does with the token field: [token] to store (null: keep the saved one) and what to say. */
    data class TokenSave(val token: String?, val result: Result)

    /** The pasted token cleaned and checked as before ([GitHubApi.cleanToken], [GitHubApi.tokenOk]); empty keeps the saved one. */
    fun saveToken(raw: String, hasToken: Boolean, gh: GhConfig): TokenSave {
        val clean = GitHubApi.cleanToken(raw)
        return when {
            clean.isEmpty() && !hasToken -> TokenSave(null, Result(Tone.BAD, "Вставь токен."))
            clean.isEmpty() -> TokenSave(null, Result(Tone.GOOD, "Сохранено", "${gh.owner}/${gh.repo} · ${where(gh)}, токен прежний."))
            !GitHubApi.tokenOk(clean) -> TokenSave(null, Result(Tone.BAD, "В токене есть буквы не из латиницы или лишние знаки — скопируй его заново."))
            else -> TokenSave(clean, Result(Tone.GOOD, "Токен сохранён", "Проверь доступ кнопкой «Проверить»."))
        }
    }

    /** «МЕТКИ»: «в этой записи: 3». */
    fun marks(n: Int): String = "в этой записи: $n"
}
