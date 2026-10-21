package io.github.purplepalm.birdair.phone

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Logs on the phone (filesDir/logs/<base>.json.gz, always anonymized: the repository is public) and on GitHub
 * (<folder>/<base>-app.json.gz, overwritten with the sha GitHub gave last time). A recording is one or more
 * [LogPart]s; each is a file of its own. What was uploaded is recorded per file ([Prefs.sentLogs]), so the
 * «Неотправленные логи» list survives restarts. Everything here runs on IO.
 */
object LogStore {
    /** Keep this many files; older ones go once they are on GitHub. */
    private const val KEEP = 40
    /** Unsent files go only beyond this many. */
    private const val HARD_KEEP = 120
    /** Automatic sending: at most this often while connected. */
    const val AUTO_EVERY_MS = 5 * 60 * 1000L
    /** After a drop: at most this often, so a flapping link does not commit the whole log every few seconds. */
    const val DROP_EVERY_MS = 60_000L

    private val saveLock = Mutex()
    private val ghLock = Mutex()
    /** The last PUT that went through: «Отправить все» shows its commit. */
    @Volatile private var lastPut: GhPut? = null

    fun dir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun latest(ctx: Context): File? = dir(ctx).listFiles { f -> f.name.endsWith(LogFiles.EXT) }?.maxByOrNull { it.lastModified() }

    /** Writes [p] if anything changed since its last save (entries, description, name). Returns the file, or null. */
    suspend fun save(ctx: Context, s: Session, p: LogPart, force: Boolean = false): File? = withContext(Dispatchers.IO) {
        saveLock.withLock {
            if (p.recorder.size == 0) return@withLock null
            val name = p.baseNow() + LogFiles.EXT
            val f = File(dir(ctx), name)
            if (!force && !p.dirtyLocal && f.isFile) return@withLock f
            val pushed = p.recorder.pushed
            val note = p.note
            val tmp = File(f.path + ".tmp")
            try {
                GZIPOutputStream(FileOutputStream(tmp), 1 shl 16).use {
                    p.recorder.writeJson(it, anonymize = true, gatt = s.gatt, decoder = ScooterRepo.tracker.decoder.spec)
                }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename") }
                // renamed (another description before the first upload): the old file goes
                val old = p.savedFile
                if (old != null && old != name) File(dir(ctx), old).delete()
                p.savedPushed = pushed; p.savedNote = note; p.savedFile = name
                prune(ctx)
                if (p === s.part) ScooterRepo.log.update { it.copy(savedName = f.name, savedBytes = f.length(), message = "Сохранено: ${f.name} (${Fmt.n(f.length() / 1024.0)} КБ)", err = false) }
                f
            } catch (e: IOException) {
                tmp.delete()
                ScooterRepo.log.update { it.copy(message = "Не сохранилось: ${e.message ?: "ошибка записи"}", err = true) }
                null
            } finally {
                ScooterRepo.refreshUnsent(ctx)
            }
        }
    }

    /** Every part of [s] that changed. */
    suspend fun saveAll(ctx: Context, s: Session, force: Boolean = false) {
        for (p in s.parts) if (force || p.dirtyLocal) save(ctx, s, p, force)
    }

    fun localLogs(ctx: Context, current: Set<String> = emptySet()): List<LocalLog> =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(LogFiles.EXT) }?.map { LocalLog(it.name, it.length(), it.lastModified(), it.name in current) } ?: emptyList()

    private fun prune(ctx: Context) {
        val cur = ScooterRepo.currentFiles()
        for (f in LogFiles.prune(localLogs(ctx, cur), ScooterRepo.prefs.sentLogs(), KEEP, HARD_KEEP)) {
            File(dir(ctx), f.name).delete()
            ScooterRepo.prefs.setSent(f.name, null)
        }
    }

    /** ACTION_SEND for a saved log through the FileProvider. */
    fun shareIntent(ctx: Context, f: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".logs", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gzip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Bird Air: " + f.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Поделиться логом").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Automatic sending is on, set up, and [p] has something GitHub does not have, with scooter data in the
     * recording. [force]: now (a drop, «Отключить», a finished part), not on the 5-min schedule, but only if no
     * upload of it was asked for within [minGapMs].
     */
    fun autoDue(s: Session, p: LogPart, force: Boolean, minGapMs: Long = 0, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cfg = ScooterRepo.gh.value
        if (!cfg.auto || !cfg.complete || !ScooterRepo.hasToken.value) return false
        if (s.rx.get() == 0L || p.recorder.size == 0) return false
        if (!p.dirtyGh(cfg.dest)) return false
        if (force) return nowMs - p.ghTryAt >= minGapMs
        val last = maxOf(if (p.gh != null) p.ghAt else p.recorder.startedAtMs, p.ghFailedAt)
        return nowMs - last >= AUTO_EVERY_MS
    }

    /** Every due part of [s], oldest first. */
    suspend fun autoUpload(ctx: Context, s: Session, force: Boolean, minGapMs: Long = 0) {
        for (p in s.parts) if (autoDue(s, p, force, minGapMs)) upload(ctx, s, p, auto = true)
    }

    private fun commit(base: String, n: Int, note: String?, part: Int, s: Session?): String {
        val text = note?.let { if (s != null) Notes.hide(it, s.recorder.deviceName, s.recorder.deviceId) else it }
        return GitHubApi.commitMessage(base, n, text, part)
    }

    /**
     * Saves, then PUTs [p] to GitHub: one part = one file, sent again it is overwritten with the sha of the previous
     * response. Its name is fixed here at the first upload (the description as it is now). A name taken by someone
     * else (422 sha) moves to -2, -3…. When that sha went stale (a PUT whose answer was lost, the file changed in
     * the repo: 409/422), the current one is fetched and the PUT tried once more. The outcome goes to
     * [ScooterRepo.log]; true when the file is on GitHub.
     */
    suspend fun upload(ctx: Context, s: Session, p: LogPart, auto: Boolean): Boolean = withContext(Dispatchers.IO) {
        p.ghTryAt = System.currentTimeMillis()
        ghLock.withLock {
            val cfg = ScooterRepo.gh.value
            val token = GitHubApi.cleanToken(ScooterRepo.prefs.token)
            fun say(text: String, err: Boolean = false, url: String? = null) =
                ScooterRepo.log.update { it.copy(ghMessage = (if (auto && err) "Автоотправка не удалась: " else "") + text, ghErr = err, ghUrl = url ?: if (err) it.ghUrl else null) }
            if (!cfg.complete) { say("Заполни owner, репозиторий и ветку в настройках GitHub.", true); return@withLock false }
            if (token.isEmpty()) { say("Нет токена: вставь fine-grained токен в настройках GitHub.", true); return@withLock false }
            if (!GitHubApi.tokenOk(token)) { say("В токене лишние символы (пробел, кириллица или невидимый знак). Скопируй токен заново.", true); return@withLock false }
            if (p.recorder.size == 0) { say("Лог пуст — нечего отправлять.", true); return@withLock false }
            if (!p.dirtyGh(cfg.dest)) { say("Этот лог уже отправлен: ${p.gh?.path}", url = p.gh?.htmlUrl); return@withLock true }
            ScooterRepo.log.update { it.copy(ghBusy = true, ghMessage = "Отправляю…", ghErr = false) }
            // the name: the description as it is at the first upload that goes through
            val named = p.base != null
            if (!named) p.base = p.recorder.fileBase()
            var sent = false
            try {
                val mark = p.recorder.pushed
                val note = p.note
                val file = save(ctx, s, p) ?: throw GhError("Не удалось сохранить лог.", 0, "")
                val size = file.length()
                val mtime = file.lastModified()
                val bytes = file.readBytes()
                val n = p.recorder.size
                val base = p.baseNow()
                // overwrite only with a known sha; without one GitHub would answer 422
                val prev = p.gh?.takeIf { p.ghDest == cfg.dest && !it.sha.isNullOrEmpty() }
                var sha = prev?.sha
                var refreshed = false
                var got: GhPut? = null
                var attempt = 1
                while (got == null) {
                    val path = prev?.path ?: GitHubApi.path(cfg.folder, GitHubApi.fileName(base, attempt))
                    val req = GitHubApi.put(cfg, token, path, bytes, commit(base, n, note, if (s.parts.size > 1) p.number else 0, s), sha)
                    try {
                        got = GitHubApi.parsePut(path, GitHubHttp.call(req, cfg).body)
                    } catch (e: GhError) {
                        when {
                            prev == null && GitHubApi.isShaConflict(e.status, e.ghMessage) && attempt < 9 -> attempt++
                            prev != null && !refreshed && GitHubApi.isStaleSha(e.status) -> { refreshed = true; sha = currentSha(cfg, token, path) }
                            else -> throw e
                        }
                    }
                }
                val put: GhPut = got
                lastPut = put
                p.gh = put; p.ghDest = cfg.dest; p.ghPushed = mark; p.ghNote = note; p.ghAt = System.currentTimeMillis()
                ScooterRepo.prefs.setSent(file.name, SentLog(put.path, put.sha, cfg.dest, size, mtime, put.htmlUrl))
                ScooterRepo.refreshUnsent(ctx)
                say((if (auto) "Автоотправка: " else "Отправлено: ") + "${put.path} (${Fmt.n(bytes.size / 1024.0)} КБ, $n записей)", url = put.htmlUrl)
                sent = true
                true
            } catch (e: GhError) {
                if (auto) p.ghFailedAt = System.currentTimeMillis()
                say(e.message ?: "Ошибка GitHub", true)
                false
            } catch (e: IOException) {
                if (auto) p.ghFailedAt = System.currentTimeMillis()
                say("Не удалось прочитать файл лога: ${e.message}", true)
                false
            } finally {
                if (!sent && !named) p.base = null
                ScooterRepo.log.update { it.copy(ghBusy = false) }
            }
        }
    }

    /**
     * A saved file that is not part of the running recording (an earlier ride, a part left over after a restart).
     * Its name is the file's; with an upload on record it overwrites that file. Without one, if the name is taken on
     * GitHub, it is the same recording sent before this list existed: identical bytes only get recorded, other
     * bytes overwrite it (same start second = same recording).
     */
    private suspend fun uploadDisk(ctx: Context, f: LocalLog): String? = withContext(Dispatchers.IO) {
        ghLock.withLock {
            val cfg = ScooterRepo.gh.value
            val token = GitHubApi.cleanToken(ScooterRepo.prefs.token)
            val file = File(dir(ctx), f.name)
            if (!file.isFile) return@withLock "файла уже нет"
            val size = file.length()
            val mtime = file.lastModified()
            val bytes = file.readBytes()
            val peek = try { GZIPInputStream(bytes.inputStream()).use { LogFiles.peek(it) } } catch (e: IOException) { LogFiles.Peek(null, 0) }
            val base = f.base
            val rec = ScooterRepo.prefs.sentLog(f.name)?.takeIf { it.dest == cfg.dest }
            val path = rec?.path ?: GitHubApi.path(cfg.folder, GitHubApi.fileName(base))
            var sha = rec?.sha
            if (sha == null) {
                sha = currentSha(cfg, token, path)
                if (sha != null && sha == gitBlobSha(bytes)) {
                    ScooterRepo.prefs.setSent(f.name, SentLog(path, sha, cfg.dest, size, mtime, null))
                    return@withLock null
                }
            }
            val msg = commit(base, peek.entries, peek.note, 0, null)
            val put = try {
                GitHubApi.parsePut(path, GitHubHttp.call(GitHubApi.put(cfg, token, path, bytes, msg, sha), cfg).body)
            } catch (e: GhError) {
                if (!GitHubApi.isStaleSha(e.status)) throw e
                GitHubApi.parsePut(path, GitHubHttp.call(GitHubApi.put(cfg, token, path, bytes, msg, currentSha(cfg, token, path)), cfg).body)
            }
            lastPut = put
            ScooterRepo.prefs.setSent(f.name, SentLog(put.path, put.sha, cfg.dest, size, mtime, put.htmlUrl))
            null
        }
    }

    /** «Отправить все»: every unsent file, oldest first, the running recording's parts through [upload]. */
    suspend fun uploadAll(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val cfg = ScooterRepo.gh.value
        val token = GitHubApi.cleanToken(ScooterRepo.prefs.token)
        fun say(text: String, err: Boolean = false) = ScooterRepo.log.update { it.copy(bulkMessage = text, bulkErr = err) }
        if (!cfg.complete) { say("Заполни owner, репозиторий и ветку в настройках GitHub.", true); return@withContext false }
        if (token.isEmpty() || !GitHubApi.tokenOk(token)) { say("Нет токена: вставь fine-grained токен в настройках GitHub.", true); return@withContext false }
        val s = ScooterRepo.session
        if (s != null) saveAll(ctx, s)
        val list = ScooterRepo.refreshUnsent(ctx).asReversed()
        if (list.isEmpty()) { say("Неотправленных логов нет."); return@withContext true }
        lastPut = null
        ScooterRepo.log.update { it.copy(bulkBusy = true, bulkDone = 0, bulkTotal = list.size, bulkCommit = null, bulkUrl = null, bulkAt = 0) }
        var ok = 0
        var failed: String? = null
        try {
            for ((i, f) in list.withIndex()) {
                say("Отправляю ${i + 1} из ${list.size}: ${f.name}…")
                val part = s?.parts?.firstOrNull { it.savedFile == f.name }
                val err = try {
                    if (part != null) { if (upload(ctx, s, part, auto = false)) null else ScooterRepo.log.value.ghMessage }
                    else uploadDisk(ctx, f)
                } catch (e: GhError) { e.message ?: "ошибка GitHub" } catch (e: IOException) { e.message ?: "ошибка чтения" }
                if (err == null) { ok++; ScooterRepo.log.update { it.copy(bulkDone = ok) } } else { failed = "${f.name}: $err"; break }
            }
        } finally {
            val put = lastPut
            ScooterRepo.log.update {
                it.copy(bulkBusy = false, bulkDone = ok, bulkCommit = put?.commitSha?.take(7), bulkUrl = put?.commitUrl ?: put?.htmlUrl,
                    bulkAt = if (failed == null && ok > 0) System.currentTimeMillis() else 0)
            }
            ScooterRepo.refreshUnsent(ctx)
        }
        if (failed != null) say("Отправлено $ok из ${list.size}. Не ушло — $failed", true) else say("Отправлено: $ok из ${list.size}.")
        failed == null
    }

    /** The sha of [path] on the branch now; null when the file is not there (the PUT then creates it). */
    private fun currentSha(cfg: GhConfig, token: String, path: String): String? = try {
        GitHubApi.parseSha(GitHubHttp.call(GitHubApi.getContents(cfg, token, path), cfg).body)
    } catch (e: GhError) {
        if (e.status == 404) null else throw e
    }

    /** Git's blob id of [bytes] (what the contents API calls the file's sha). */
    fun gitBlobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.US_ASCII))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** «Проверить доступ»: the repo and the branch, like the page's checkGitHub(). */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        val cfg = ScooterRepo.gh.value
        val token = GitHubApi.cleanToken(ScooterRepo.prefs.token)
        if (token.isEmpty()) return@withContext "Сначала вставь токен."
        if (!GitHubApi.tokenOk(token)) return@withContext "В токене лишние символы — скопируй его заново."
        try {
            val repo = GitHubHttp.call(GitHubApi.getRepo(cfg, token), cfg)
            GitHubHttp.call(GitHubApi.getBranch(cfg, token), cfg)
            val push = Regex("\"push\"\\s*:\\s*(true|false)").find(repo.body ?: "")?.groupValues?.get(1)
            "Доступ есть: ${cfg.owner}/${cfg.repo}, ветка ${cfg.branch}." +
                if (push == "false") " Но у твоей учётной записи нет права записи." else " Право записи проверится при первой отправке."
        } catch (e: GhError) { e.message ?: "Ошибка GitHub" }
    }
}
