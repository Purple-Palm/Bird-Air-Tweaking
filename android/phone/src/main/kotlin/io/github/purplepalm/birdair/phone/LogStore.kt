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
import java.util.zip.GZIPOutputStream

/**
 * Logs on the phone (filesDir/logs/<base>.json.gz, always anonymized: the repository is public) and on GitHub
 * (<folder>/<base>-app.json.gz, overwritten with the sha GitHub gave last time). Everything here runs on IO.
 */
object LogStore {
    private const val KEEP = 40
    /** Automatic sending: at most this often while connected. */
    const val AUTO_EVERY_MS = 5 * 60 * 1000L

    private val saveLock = Mutex()
    private val ghLock = Mutex()

    fun dir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun latest(ctx: Context): File? = dir(ctx).listFiles { f -> f.name.endsWith(".json.gz") }?.maxByOrNull { it.lastModified() }

    /** Writes the session's log if anything new came since the last save. Returns the file, or null. */
    suspend fun save(ctx: Context, s: Session, force: Boolean = false): File? = withContext(Dispatchers.IO) {
        saveLock.withLock {
            val f = File(dir(ctx), s.base() + ".json.gz")
            val pushed = s.recorder.pushed
            if (s.recorder.size == 0) return@withLock null
            if (!force && pushed == s.savedPushed && f.isFile) return@withLock f
            val tmp = File(f.path + ".tmp")
            try {
                GZIPOutputStream(FileOutputStream(tmp), 1 shl 16).use {
                    s.recorder.writeJson(it, anonymize = true, gatt = s.gatt, decoder = ScooterRepo.tracker.decoder.spec)
                }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw IOException("rename") }
                s.savedPushed = pushed
                prune(ctx)
                ScooterRepo.log.update { it.copy(savedName = f.name, savedBytes = f.length(), message = "Сохранено: ${f.name} (${Fmt.n(f.length() / 1024.0)} КБ)", err = false) }
                f
            } catch (e: IOException) {
                tmp.delete()
                ScooterRepo.log.update { it.copy(message = "Не сохранилось: ${e.message ?: "ошибка записи"}", err = true) }
                null
            }
        }
    }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".json.gz") }?.sortedByDescending { it.lastModified() } ?: return
        for (f in files.drop(KEEP)) f.delete()
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

    /** Automatic sending is on, set up, and there is something new with scooter data in it. */
    fun autoDue(s: Session, force: Boolean): Boolean {
        val cfg = ScooterRepo.gh.value
        if (!cfg.auto || !cfg.complete || !ScooterRepo.hasToken.value) return false
        if (s.rx.get() == 0L) return false
        if (s.ghPushed == s.recorder.pushed && s.ghDest == cfg.dest) return false
        if (force) return true
        val last = maxOf(if (s.gh != null) s.ghAt else s.startedAtMs, s.ghFailedAt)
        return System.currentTimeMillis() - last >= AUTO_EVERY_MS
    }

    /**
     * Saves, then PUTs the log to GitHub: one recording = one file, sent again it is overwritten with the sha of
     * the previous response. A name taken by someone else (422 sha) moves to -2, -3…. The outcome goes to
     * [ScooterRepo.log]; true when the file is on GitHub.
     */
    suspend fun upload(ctx: Context, s: Session, auto: Boolean): Boolean = withContext(Dispatchers.IO) {
        ghLock.withLock {
            val cfg = ScooterRepo.gh.value
            val token = GitHubApi.cleanToken(ScooterRepo.prefs.token)
            fun say(text: String, err: Boolean = false, url: String? = null) =
                ScooterRepo.log.update { it.copy(ghMessage = (if (auto && err) "Автоотправка не удалась: " else "") + text, ghErr = err, ghUrl = url ?: if (err) it.ghUrl else null) }
            if (!cfg.complete) { say("Заполни owner, репозиторий и ветку в настройках GitHub.", true); return@withLock false }
            if (token.isEmpty()) { say("Нет токена: вставь fine-grained токен в настройках GitHub.", true); return@withLock false }
            if (!GitHubApi.tokenOk(token)) { say("В токене лишние символы (пробел, кириллица или невидимый знак). Скопируй токен заново.", true); return@withLock false }
            if (s.recorder.size == 0) { say("Лог пуст — нечего отправлять.", true); return@withLock false }
            if (s.ghPushed == s.recorder.pushed && s.ghDest == cfg.dest && s.gh != null) { say("Этот лог уже отправлен: ${s.gh?.path}", url = s.gh?.htmlUrl); return@withLock true }
            ScooterRepo.log.update { it.copy(ghBusy = true, ghMessage = "Отправляю…", ghErr = false) }
            try {
                val mark = s.recorder.pushed
                val file = save(ctx, s) ?: throw GhError("Не удалось сохранить лог.", 0, "")
                val bytes = file.readBytes()
                val n = s.recorder.size
                val base = s.base()
                // overwrite only with a known sha; without one GitHub would answer 422
                val prev = s.gh?.takeIf { s.ghDest == cfg.dest && !it.sha.isNullOrEmpty() }
                var got: GhPut? = null
                var attempt = 1
                while (got == null) {
                    val path = prev?.path ?: GitHubApi.path(cfg.folder, GitHubApi.fileName(base, attempt))
                    val req = GitHubApi.put(cfg, token, path, bytes, GitHubApi.commitMessage(base, n), prev?.sha)
                    try {
                        got = GitHubApi.parsePut(path, GitHubHttp.call(req, cfg).body)
                    } catch (e: GhError) {
                        if (prev == null && GitHubApi.isShaConflict(e.status, e.ghMessage) && attempt < 9) attempt++ else throw e
                    }
                }
                val put: GhPut = got
                s.gh = put; s.ghDest = cfg.dest; s.ghPushed = mark; s.ghAt = System.currentTimeMillis()
                say((if (auto) "Автоотправка: " else "Отправлено: ") + "${put.path} (${Fmt.n(bytes.size / 1024.0)} КБ, $n записей)", url = put.htmlUrl)
                true
            } catch (e: GhError) {
                if (auto) s.ghFailedAt = System.currentTimeMillis()
                say(e.message ?: "Ошибка GitHub", true)
                false
            } catch (e: IOException) {
                if (auto) s.ghFailedAt = System.currentTimeMillis()
                say("Не удалось прочитать файл лога: ${e.message}", true)
                false
            } finally {
                ScooterRepo.log.update { it.copy(ghBusy = false) }
            }
        }
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
