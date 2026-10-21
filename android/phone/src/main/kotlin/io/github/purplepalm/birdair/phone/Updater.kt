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

/**
 * In-app update from the android-latest release: version.json at start and once a day, then on «Обновить» the
 * phone APK into the cache and the system installer through the FileProvider. Android asks the owner once to allow
 * «Установка неизвестных приложений» for Bird Air. The APK is signed with the same key, so it installs over this one
 * with settings and logs kept. Nothing here talks to the scooter.
 */
object Updater {
    private val lock = Mutex()
    @Volatile private var daily = false
    private val state get() = ScooterRepo.update

    /** The installed build. */
    val code: Int get() = BuildConfig.VERSION_CODE
    val name: String get() = BuildConfig.VERSION_NAME
    val sha: String get() = BuildConfig.GIT_SHA

    /** «0.42 (v42) · 1a2b3c4» */
    fun installedLabel(): String = "$name (v$code)" + if (sha.isNotEmpty()) " · " + sha.take(7) else ""

    fun available(): Boolean = Versions.isNewer(state.value.latest, code)

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

    /** Reads version.json; [manual]: say what came out even when there is nothing new. */
    suspend fun check(@Suppress("UNUSED_PARAMETER") ctx: Context, manual: Boolean) = withContext(Dispatchers.IO) {
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
                    manual -> "Обновлений нет: стоит последняя сборка (${info.label()})."
                    else -> it.message
                }
                it.copy(latest = latest, checkedAt = if (info != null) now else it.checkedAt, checking = false, message = msg, err = info == null && manual)
            }
        }
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

    private fun apkFile(ctx: Context): File = File(File(ctx.cacheDir, "update").apply { mkdirs() }, Versions.PHONE_APK)

    /** versionCode of a downloaded APK if it is this app, else null. */
    private fun apkCode(ctx: Context, f: File): Int? {
        if (!f.isFile) return null
        val info = try { ctx.packageManager.getPackageArchiveInfo(f.path, 0) } catch (e: Exception) { null } ?: return null
        if (info.packageName != ctx.packageName) return null
        return info.longVersionCode.toInt()
    }

    /** The release's phone APK in the cache (kept when it is already there and newer). */
    suspend fun download(ctx: Context): File? = withContext(Dispatchers.IO) {
        lock.withLock {
            val f = apkFile(ctx)
            apkCode(ctx, f)?.let { if (it > code) return@withLock f }
            val tmp = File(f.path + ".part")
            state.update { it.copy(downloading = true, progress = 0f, message = "Скачиваю ${Versions.PHONE_APK}…", err = false) }
            try {
                val c = URL(Versions.PHONE_APK_URL).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15_000
                    c.readTimeout = 30_000
                    c.useCaches = false
                    c.setRequestProperty("User-Agent", "bird-air-lab-android")
                    if (c.responseCode != 200) throw IOException("ответ ${c.responseCode}")
                    val total = c.contentLengthLong
                    var got = 0L
                    var shown = 0L
                    c.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                if (got - shown >= 256 * 1024) {
                                    shown = got
                                    state.update { it.copy(progress = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else null, message = "Скачиваю ${Versions.PHONE_APK}: ${Fmt.n(got / 1048576.0, 1)} МБ…") }
                                }
                            }
                        }
                    }
                    if (total > 0 && got != total) throw IOException("скачалось ${got} из $total байт")
                } finally { c.disconnect() }
                f.delete()
                if (!tmp.renameTo(f)) throw IOException("не переименовался файл")
                val apk = apkCode(ctx, f)
                when {
                    apk == null -> { f.delete(); state.update { it.copy(message = "Скачанный файл — не Bird Air для телефона. Попробуй ещё раз позже.", err = true) }; null }
                    apk <= code -> { state.update { it.copy(message = "В релизе пока та же сборка (v$apk) — CI ещё собирает новую? Попробуй через пару минут.", err = true) }; null }
                    else -> { state.update { it.copy(message = "Скачано (v$apk). Открываю установщик…", err = false) }; f }
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

    /** The system installer for [f]; false (and [UpdateState.needsPermission]) when Android wants the permission first. */
    fun install(a: Activity, f: File): Boolean {
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
