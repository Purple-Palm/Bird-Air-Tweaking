package io.github.purplepalm.birdair.phone

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The installed build: versionCode, versionName («8-a41290d»), the commit. */
data class Installed(val code: Int, val name: String, val sha: String)

/**
 * In-app update from the android-latest release, one state machine ([UpdateFlow]): version.json at start and once a day
 * (and on «Проверить обновления»), the phone APK into filesDir/update (with «Скачивать обновления сам» right after the
 * check), checked (size and SHA-256 from version.json, this app, newer) and kept there across processes: «Установить vN»
 * hands it to the system installer through the FileProvider. The installer opens by itself only after the owner's tap
 * and only when no recording runs ([UpdateState.openWhenReady]). Android asks once to allow «Установка неизвестных
 * приложений» for Bird Air. Same key, so it installs over this one with settings and logs kept; MY_PACKAGE_REPLACED
 * ([UpdatedReceiver]) deletes the APK and brings the link back. Nothing here talks to the scooter.
 */
object Updater {
    private val lock = Mutex()
    @Volatile private var daily = false
    private val state get() = ScooterRepo.update

    /** The installed build; the shots set their own (else «dev (v1)»). */
    @Volatile var installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
    val code: Int get() = installed.code
    val name: String get() = installed.name
    val sha: String get() = installed.sha

    /** «0.42 (v42) · 1a2b3c4» */
    fun installedLabel(): String = "$name (v$code)" + if (sha.isNotEmpty()) " · " + sha.take(7) else ""

    fun available(): Boolean = Versions.isNewer(state.value.latest, code)

    fun phase(): UpdPhase = UpdateFlow.phase(state.value, code)

    /** At process start, then once a day while the process lives. */
    fun startDaily(ctx: Context) {
        if (daily) return
        daily = true
        val app = ctx.applicationContext
        ScooterRepo.scope.launch(Dispatchers.IO) {
            var first = true
            while (true) {
                if (Versions.checkDue(ScooterRepo.prefs.updateCheckedAt, System.currentTimeMillis(), atStart = first)) check(app, manual = false)
                first = false
                delay(3600_000L)
            }
        }
    }

    /** At process start: a kept APK still worth installing is READY again; an old one (or the cache's of older builds) goes. */
    fun restore(ctx: Context) {
        File(ctx.cacheDir, "update").deleteRecursively()
        val f = apkFile(ctx)
        val c = apkCode(ctx, f)
        if (UpdateFlow.keep(c, code, state.value.latest)) state.update { it.copy(readyCode = c) }
        else { f.delete(); state.update { it.copy(readyCode = null) } }
    }

    /** MY_PACKAGE_REPLACED: the kept APK is installed (or older) now. */
    fun cleanup(ctx: Context) {
        val f = apkFile(ctx)
        val c = apkCode(ctx, f)
        if (c == null || c <= code) { f.delete(); state.update { it.copy(readyCode = null, openWhenReady = false) } }
    }

    /** Reads version.json; [manual]: say what came out even when there is nothing new. With «Скачивать обновления сам» a
     *  newer build is downloaded right away; [byTap] (the owner's tap) lets the installer open after it when idle. */
    suspend fun check(ctx: Context, manual: Boolean, byTap: Boolean = manual) {
        val newer = withContext(Dispatchers.IO) {
            lock.withLock {
                state.update { it.copy(checking = true, message = if (manual) "Проверяю обновления…" else it.message, err = false) }
                val text = try { get(Versions.VERSION_URL) } catch (e: IOException) { null }
                val info = Versions.parse(text)
                val now = System.currentTimeMillis()
                if (info != null) {
                    ScooterRepo.prefs.updateJson = text
                    ScooterRepo.prefs.updateCheckedAt = now
                }
                state.update {
                    val latest = info ?: it.latest
                    val msg = when {
                        info == null -> if (manual) "Не удалось прочитать version.json релиза android-latest: нет сети или CI его ещё не выложил." else it.message
                        Versions.isNewer(info, code) -> "Есть обновление: ${info.label()}."
                        manual -> "Новее нет: v$code — последняя сборка."
                        else -> it.message
                    }
                    // a kept APK older than the release now: download again
                    val ready = it.readyCode?.takeIf { c -> UpdateFlow.keep(c, code, latest) }
                    it.copy(latest = latest, checkedAt = if (info != null) now else it.checkedAt, checking = false, message = msg,
                        err = info == null && manual, readyCode = ready)
                }
                Versions.isNewer(state.value.latest, code) && state.value.readyCode == null
            }
        }
        if (newer && ScooterRepo.settings.value.autoDownload) download(ctx, byTap)
    }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            c.setRequestProperty("User-Agent", "bird-air-lab-android")
            return if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
        } finally { c.disconnect() }
    }

    private fun apkFile(ctx: Context): File = File(File(ctx.filesDir, "update").apply { mkdirs() }, Versions.PHONE_APK)

    /** versionCode of a downloaded APK if it is this app, else null. */
    private fun apkCode(ctx: Context, f: File): Int? {
        if (!f.isFile) return null
        val info = try { ctx.packageManager.getPackageArchiveInfo(f.path, 0) } catch (e: Exception) { null } ?: return null
        if (info.packageName != ctx.packageName) return null
        return info.longVersionCode.toInt()
    }

    /** The kept APK when it is still worth installing. */
    fun readyFile(ctx: Context): File? {
        val f = apkFile(ctx)
        return f.takeIf { UpdateFlow.keep(apkCode(ctx, it), code, state.value.latest) }
    }

    /**
     * The release's phone APK into filesDir/update (kept when it is already there, newer than this build and not older
     * than what version.json says now), checked by size and SHA-256 when version.json has them. Run it in
     * [ScooterRepo.scope], not an activity's: the download outlives the screen. [byTap]: the installer may open after it.
     */
    suspend fun download(ctx: Context, byTap: Boolean): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = apkFile(ctx)
            val latest = state.value.latest
            apkCode(ctx, f)?.let { c ->
                if (UpdateFlow.keep(c, code, latest)) { ready(c, byTap); return@withLock f }
            }
            val tmp = File(f.path + ".part")
            state.update { it.copy(downloading = true, progress = 0f, gotBytes = 0, totalBytes = latest?.phoneSize, message = "Скачиваю ${Versions.PHONE_APK}…", err = false) }
            try {
                val md = MessageDigest.getInstance("SHA-256")
                val c = URL(Versions.PHONE_APK_URL).openConnection() as HttpURLConnection
                var got = 0L
                try {
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    c.useCaches = false
                    c.setRequestProperty("User-Agent", "bird-air-lab-android")
                    if (c.responseCode != 200) throw IOException("ответ ${c.responseCode}")
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: latest?.phoneSize ?: -1L
                    var shown = 0L
                    c.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                md.update(buf, 0, n)
                                got += n
                                if (got - shown >= 256 * 1024) {
                                    shown = got
                                    val g = got
                                    state.update { it.copy(gotBytes = g, totalBytes = total.takeIf { t -> t > 0 }, progress = if (total > 0) (g.toFloat() / total).coerceIn(0f, 1f) else null,
                                        message = "Скачиваю ${Versions.PHONE_APK}: ${Fmt.n(g / 1048576.0, 1)} МБ…") }
                                }
                            }
                        }
                    }
                    if (total > 0 && got != total) throw IOException("скачалось $got из $total байт")
                } finally { c.disconnect() }
                val want = latest?.phoneSha256
                if (want != null && hex(md.digest()) != want) throw IOException("файл не сошёлся по SHA-256 с version.json")
                val size = latest?.phoneSize
                if (size != null && got != size) throw IOException("размер $got, а в version.json $size")
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("не переименовался файл")
                val apk = apkCode(ctx, f)
                when {
                    apk == null -> { f.delete(); state.update { it.copy(message = "Скачанный файл — не Bird Air для телефона. Попробуй ещё раз позже.", err = true) }; null }
                    apk <= code -> { f.delete(); state.update { it.copy(message = "В релизе пока та же сборка (v$apk) — CI ещё собирает новую? Попробуй через пару минут.", err = true) }; null }
                    else -> { ready(apk, byTap); f }
                }
            } catch (e: IOException) {
                tmp.delete()
                state.update { it.copy(message = "Не скачалось: ${e.message ?: "нет сети"}. Нажми «Обновить» ещё раз.", err = true) }
                null
            } finally {
                state.update { it.copy(downloading = false, progress = null) }
            }
        }
    }

    /** [apk] is downloaded and checked: «Установить vN»; the installer opens by itself only after a tap with no recording. */
    private fun ready(apk: Int, byTap: Boolean) {
        val running = ScooterRepo.status.value.running
        state.update { it.copy(readyCode = apk, openWhenReady = UpdateFlow.openWhenDone(byTap, running), message = "Скачано (v$apk).", err = false) }
        if (running) ScooterRepo.toast("v$apk скачано · поставишь кнопкой «Установить»" + if (ScooterRepo.moving.value) " на стоянке" else "")
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** The system installer for [f]; false (and [UpdateState.needsPermission]) when Android wants the permission first. */
    fun install(a: Activity, f: File): Boolean {
        state.update { it.copy(openWhenReady = false) }
        if (!a.packageManager.canRequestPackageInstalls()) {
            state.update { it.copy(needsPermission = true, message = "Android спросит разрешение один раз: «Установка неизвестных приложений» → Bird Air → «Разрешить», затем вернись сюда.", err = false) }
            return false
        }
        state.update { it.copy(needsPermission = false) }
        val uri = FileProvider.getUriForFile(a, a.packageName + ".logs", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { a.startActivity(i); true } catch (e: ActivityNotFoundException) {
            state.update { it.copy(message = "Не нашёлся установщик пакетов. Поставь APK из Chrome: ${Versions.PHONE_APK} на странице релиза.", err = true) }
            false
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** Settings → «Установка неизвестных приложений» for this app. */
    fun allowIntent(ctx: Context): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
}
