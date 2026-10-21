package io.github.purplepalm.birdair.wear

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The watch updates itself from the android-latest release: version.json at start and once a day; on «Обновить
 * часы» bird-air-watch.apk into the cache (over Wi-Fi when the watch can bring it up, resumable, size and SHA-256
 * checked); on «Установить» a PackageInstaller session, and the system's own confirmation. Nothing installs
 * without the owner's taps; nothing here throws into the UI. Same signing key, so settings stay.
 */
object WatchUpdater {
    private val state = MutableStateFlow(UpdateState())
    val ui: StateFlow<UpdateState> = state.asStateFlow()

    // never crash the app: whatever slips past [safely] ends here
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val checking = Mutex()
    @Volatile private var started = false
    @Volatile private var job: Job? = null
    @Volatile private var conn: HttpURLConnection? = null
    // the system's confirmation dialog, kept for «Подтвердить установку» when it could not be started at once
    @Volatile private var confirm: Intent? = null
    @Volatile private var askedPermission = false

    private val installed: Int get() = BuildConfig.VERSION_CODE

    private const val PREFS = "bal.wear.update"
    private const val JSON = "versionJson"
    private const val CHECKED_AT = "checkedAt"
    /** The versionCode handed to the installer: on the next start, «Часы обновлены» if it took. */
    private const val INSTALLING = "installing"
    private const val WIFI_WAIT_MS = 8_000L
    private const val UA = "bird-air-lab-wear"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.applicationContext.cacheDir, "update").apply { mkdirs() }
    private fun part(ctx: Context, code: Int) = File(dir(ctx), "watch-v$code.part")
    private fun apk(ctx: Context, code: Int) = File(dir(ctx), "watch-v$code.apk")
    private fun codeOf(f: File): Int? = Regex("""watch-v(\d+)\.(part|apk)""").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()

    /** Once per process: the last version.json and any download from before, then a check now and once a day. */
    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        scope.launch {
            safely { restore(app) }
            var first = true
            while (isActive) {
                safely { if (Release.checkDue(prefs(app).getLong(CHECKED_AT, 0), System.currentTimeMillis(), first)) checkNow(app, manual = false) }
                first = false
                delay(3600_000L)
            }
        }
    }

    /** The details page's chip. */
    fun act(ctx: Context, a: UpdateAction) = safely {
        when (a) {
            UpdateAction.NONE -> Unit
            UpdateAction.CHECK -> { val app = ctx.applicationContext; scope.launch { safely { checkNow(app, manual = true) } } }
            UpdateAction.DOWNLOAD -> download(ctx)
            UpdateAction.INSTALL -> install(ctx)
            UpdateAction.ALLOW -> allow(ctx)
            UpdateAction.CONFIRM -> launchConfirm(ctx)
        }
    }

    /** Back from the settings or the installer: the permission may have changed. */
    fun resumed(ctx: Context) = safely {
        if (state.value.phase == UpdatePhase.READY) state.update { it.copy(needsPermission = needsPermission(ctx)) }
    }

    fun cancel() {
        job?.cancel()
        // unblocks a read that waits on the network
        try { conn?.disconnect() } catch (e: Exception) { }
    }

    private fun restore(app: Context) {
        val p = prefs(app)
        val latest = Release.parse(p.getString(JSON, null))
        val was = p.getInt(INSTALLING, 0)
        var msg: String? = null
        if (was > 0) {
            if (installed >= was) msg = "Часы обновлены: v$installed."
            p.edit().remove(INSTALLING).apply()
        }
        var ready: Int? = null
        var got = 0L
        for (f in dir(app).listFiles().orEmpty()) {
            val c = codeOf(f)
            val keep = c != null && c > installed && (latest == null || c == latest.versionCode)
            when {
                !keep -> f.delete()
                f.name.endsWith(".apk") -> ready = c
                else -> got = f.length()
            }
        }
        state.update {
            it.copy(
                latest = latest ?: it.latest,
                phase = if (ready != null && it.phase == UpdatePhase.IDLE) UpdatePhase.READY else it.phase,
                readyCode = ready ?: it.readyCode,
                got = if (it.phase == UpdatePhase.IDLE) got else it.got,
                total = latest?.watchSize ?: it.total,
                needsPermission = ready != null && needsPermission(app),
                message = msg ?: it.message,
            )
        }
    }

    private suspend fun checkNow(app: Context, manual: Boolean) {
        if (!checking.tryLock()) return
        try {
            if (manual) state.update { if (it.phase == UpdatePhase.IDLE) it.copy(phase = UpdatePhase.CHECKING, message = null, err = false) else it }
            val text = try { getText(Release.VERSION_URL) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val info = Release.parse(text)
            if (info != null) prefs(app).edit().putString(JSON, text).putLong(CHECKED_AT, System.currentTimeMillis()).apply()
            state.update { s ->
                val idle = s.phase == UpdatePhase.IDLE || s.phase == UpdatePhase.CHECKING
                // a downloaded build that the release has moved past: offer the new one
                val stale = info != null && s.phase == UpdatePhase.READY && (s.readyCode ?: 0) < info.versionCode
                val newer = Release.isNewer(info, installed)
                val msg = when {
                    info == null -> if (manual) "Не прочитался version.json релиза android-latest: нет сети? Часы ходят в интернет через телефон или Wi-Fi." else s.message
                    manual && !newer -> "Обновлений нет: стоит v$installed, в релизе v${info.versionCode}."
                    manual || stale -> null
                    else -> s.message
                }
                val sameTarget = info == null || info.versionCode == s.latest?.versionCode
                s.copy(
                    latest = info ?: s.latest,
                    phase = if (idle || stale) UpdatePhase.IDLE else s.phase,
                    readyCode = if (stale) null else s.readyCode,
                    got = if ((idle || stale) && !sameTarget) 0 else s.got,
                    total = if (!(idle || stale)) s.total else if (sameTarget) info?.watchSize ?: s.total else info?.watchSize,
                    message = msg,
                    err = info == null && manual,
                )
            }
            if (info != null) {
                val s = state.value
                if (s.phase == UpdatePhase.IDLE) for (f in dir(app).listFiles().orEmpty()) if (codeOf(f) != info.versionCode || info.versionCode <= installed) f.delete()
            }
        } finally { checking.unlock() }
    }

    private fun download(ctx: Context) {
        val r = state.value.latest ?: return
        if (job?.isActive == true || !Release.isNewer(r, installed)) return
        val app = ctx.applicationContext
        job = scope.launch { safely { downloadNow(app, r) } }
    }

    private suspend fun downloadNow(app: Context, r: WatchRelease) {
        val part = part(app, r.versionCode)
        val apk = apk(app, r.versionCode)
        for (f in dir(app).listFiles().orEmpty()) if (f != part) f.delete()
        state.update { it.copy(phase = UpdatePhase.DOWNLOADING, got = part.length(), total = r.watchSize, bytesPerS = null, wifi = null, readyCode = null, message = null, err = false) }
        var lease: WifiLease? = null
        val wake = try {
            app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "birdair:update")?.apply {
                setReferenceCounted(false)
                acquire(30 * 60_000L)
            }
        } catch (e: Exception) { null }
        try {
            lease = wifi(app)
            state.update { it.copy(wifi = lease != null || onWifi(app)) }
            fetch(r, part, lease?.network)
            val problem = ApkCheck.file(r, part.length(), if (r.watchSha256 != null) Sha256.of(part) else null)
                ?: archive(app, part).let { (pkg, code) -> ApkCheck.apk(pkg, code, app.packageName, installed) }
            if (problem != null) {
                part.delete()
                state.update { it.copy(phase = UpdatePhase.IDLE, got = 0, bytesPerS = null, message = "Файл не прошёл проверку: $problem. Нажми ещё раз — скачаю заново.", err = true) }
                // the release may have changed under the same name
                scope.launch { safely { checkNow(app, manual = false) } }
                return
            }
            apk.delete()
            if (!part.renameTo(apk)) throw IOException("не переименовался файл")
            state.update {
                it.copy(phase = UpdatePhase.READY, readyCode = r.versionCode, got = apk.length(), bytesPerS = null, needsPermission = needsPermission(app),
                    message = "Скачано и проверено (v${r.versionCode}).", err = false)
            }
        } catch (e: Exception) {
            if (e is CancellationException || !currentCoroutineContext().isActive) {
                part.delete()
                state.update { it.copy(phase = UpdatePhase.IDLE, got = 0, bytesPerS = null, message = "Загрузка отменена.", err = false) }
                if (e is CancellationException) throw e
            } else {
                state.update {
                    it.copy(phase = UpdatePhase.IDLE, got = part.length(), bytesPerS = null, err = true,
                        message = "Не скачалось: ${NetError.text(e)}. Нажми ещё раз — докачаю.")
                }
            }
        } finally {
            conn = null
            lease?.release()
            try { if (wake?.isHeld == true) wake.release() } catch (e: Exception) { }
        }
    }

    /** bird-air-watch.apk into [part], resuming what is there. */
    private suspend fun fetch(r: WatchRelease, part: File, net: Network?) {
        var offset = part.length()
        val size = r.watchSize
        if (size != null && offset > size) { part.delete(); offset = 0 }
        if (size != null && offset == size) return
        val c = open(Release.WATCH_APK_URL, net, offset)
        conn = c
        try {
            val code = c.responseCode
            when (code) {
                206 -> if (Ranges.start(c.getHeaderField("Content-Range")) != offset) { part.delete(); throw IOException("сервер прислал не тот кусок") }
                200 -> offset = 0
                416 -> { part.delete(); throw IOException("докачка не вышла, начну заново") }
                else -> throw IOException("ответ сервера $code")
            }
            val total = Ranges.total(code, offset, c.contentLengthLong, c.getHeaderField("Content-Range")) ?: size
            if (size != null && total != size) { part.delete(); throw IOException("в релизе файл другого размера — CI выкладывает новую сборку? Повтори через пару минут") }
            state.update { it.copy(got = offset, total = total) }
            val rate = Rate()
            var got = offset
            var shownAt = 0L
            var shownBytes = offset
            FileOutputStream(part, offset > 0).use { out ->
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - shownAt >= 500 || got - shownBytes >= 512 * 1024) {
                            rate.add(now, got)
                            shownAt = now
                            shownBytes = got
                            state.update { it.copy(got = got, bytesPerS = rate.bytesPerS()) }
                        }
                    }
                }
                out.flush()
                out.fd.sync()
            }
            state.update { it.copy(got = got) }
            if (total != null && got != total) throw IOException("скачалось ${UpdateView.mb(got)} из ${UpdateView.mb(total)}")
        } finally {
            c.disconnect()
        }
    }

    /** [url] with up to [Redirects.MAX] redirects, each to GitHub over HTTPS only. */
    private fun open(url: String, net: Network?, offset: Long): HttpURLConnection {
        var u = url
        repeat(Redirects.MAX + 1) {
            val c = (net?.openConnection(URL(u)) ?: URL(u).openConnection()) as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            c.setRequestProperty("Cache-Control", "no-cache")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            val code = try { c.responseCode } catch (e: Exception) { c.disconnect(); throw e }
            if (code !in 300..399) return c
            val next = Redirects.next(u, c.getHeaderField("Location"))
            c.disconnect()
            u = next ?: throw IOException("переадресация не на GitHub")
        }
        throw IOException("слишком много переадресаций")
    }

    private fun getText(url: String): String? {
        val c = open(url, null, 0)
        try {
            if (c.responseCode != 200) return null
            // version.json is a few hundred bytes; never read more than 64 KB
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (out.size() < 64 * 1024) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }

    /** Package name and versionCode of [f]; (null, null) when it is not an APK. */
    private fun archive(app: Context, f: File): Pair<String?, Long?> {
        val pm = app.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageArchiveInfo(f.path, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(f.path, 0)
        } catch (e: Exception) { null }
        return (info?.packageName) to info?.longVersionCode
    }

    private class WifiLease(val cm: ConnectivityManager, val cb: ConnectivityManager.NetworkCallback, val network: Network) {
        fun release() = unregister(cm, cb)
    }

    private fun unregister(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback) {
        try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    /**
     * Wear OS keeps Wi-Fi off while the phone is near and routes through its Bluetooth: ~25 MB take minutes. Ask
     * for Wi-Fi for [WIFI_WAIT_MS]; null (the default network) when there is none.
     */
    private suspend fun wifi(app: Context): WifiLease? {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return null
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val got = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { got.complete(network) }
        }
        try { cm.requestNetwork(req, cb) } catch (e: Exception) { return null }
        val n = try { withTimeoutOrNull(WIFI_WAIT_MS) { got.await() } } catch (e: CancellationException) { unregister(cm, cb); throw e }
        if (n == null) { unregister(cm, cb); return null }
        return WifiLease(cm, cb, n)
    }

    private fun onWifi(app: Context): Boolean = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (e: Exception) { false }

    private fun needsPermission(ctx: Context): Boolean =
        !askedPermission && !(try { ctx.packageManager.canRequestPackageInstalls() } catch (e: Exception) { false })

    /** Settings → «Установка неизвестных приложений» for Bird Air; Wear OS may not have that screen. */
    private fun allow(ctx: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        )
        for (i in tries) {
            try {
                start(ctx, i)
                state.update { it.copy(message = "Включи «Разрешить» для Bird Air и вернись сюда.", err = false) }
                return
            } catch (e: ActivityNotFoundException) {
                // the next one
            } catch (e: SecurityException) { }
        }
        // no such screen: the installer may still ask itself; the next tap tries it
        askedPermission = true
        state.update {
            it.copy(needsPermission = false, err = true, message = "На часах нет экрана «Установка неизвестных приложений». Нажми «Установить» — " +
                "система может спросить сама. Иначе один раз через Bugjaeger: appops set ${ctx.packageName} REQUEST_INSTALL_PACKAGES allow — " +
                "или поставь ${Release.WATCH_APK} через Bugjaeger, как раньше.")
        }
    }

    @SuppressLint("ApplySharedPref") // the marker must be on disk before the installer may kill us
    private fun install(ctx: Context) {
        val s = state.value
        val code = s.readyCode ?: return
        if (s.phase != UpdatePhase.READY) return
        if (needsPermission(ctx)) { allow(ctx); return }
        val app = ctx.applicationContext
        val f = apk(app, code)
        if (!f.isFile) {
            state.update { it.copy(phase = UpdatePhase.IDLE, readyCode = null, got = 0, message = "Скачанный файл пропал (система почистила кэш). Нажми ещё раз — скачаю заново.", err = true) }
            return
        }
        state.update { it.copy(phase = UpdatePhase.INSTALLING, message = null, err = false) }
        scope.launch {
            try {
                // before the commit: a self-update kills this process as soon as the owner confirms
                prefs(app).edit().putInt(INSTALLING, code).commit()
                commit(app, f)
            } catch (e: Exception) {
                prefs(app).edit().remove(INSTALLING).apply()
                state.update { it.copy(phase = UpdatePhase.READY, message = "Установщик не принял файл: ${e.message ?: e.javaClass.simpleName}. ${InstallStatus.BUGJAEGER}", err = true) }
            }
        }
    }

    /** One session: the APK written and fsync'ed, committed with a PendingIntent to [InstallReceiver]. */
    private fun commit(app: Context, f: File) {
        val pi = app.packageManager.packageInstaller
        for (old in pi.mySessions) try { pi.abandonSession(old.sessionId) } catch (e: Exception) { }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(f.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            session.openWrite(Release.WATCH_APK, 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out, 1 shl 16) }
                session.fsync(out)
            }
            // mutable: the installer fills in the status (and the confirmation intent)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(
                app, id,
                Intent(app, InstallReceiver::class.java).setAction(InstallReceiver.ACTION).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(status.intentSender)
            session.close()
        } catch (e: Exception) {
            try { session.abandon() } catch (x: Exception) { }
            throw e
        }
    }

    /** From [InstallReceiver]. */
    internal fun onStatus(ctx: Context, status: Int, message: String?, confirmIntent: Intent?) {
        val o = InstallStatus.of(status, message)
        val app = ctx.applicationContext
        when (o.kind) {
            InstallStatus.Kind.CONFIRM -> {
                confirm = confirmIntent
                state.update { it.copy(phase = UpdatePhase.CONFIRM) }
                launchConfirm(ctx)
            }
            InstallStatus.Kind.DONE -> {
                confirm = null
                for (f in dir(app).listFiles().orEmpty()) f.delete()
                state.update { it.copy(phase = UpdatePhase.IDLE, readyCode = null, got = 0, message = o.text, err = false) }
            }
            InstallStatus.Kind.CANCELLED, InstallStatus.Kind.RETRY -> {
                confirm = null
                prefs(app).edit().remove(INSTALLING).apply()
                state.update { it.copy(phase = UpdatePhase.READY, message = o.text, err = o.err) }
            }
            InstallStatus.Kind.REDOWNLOAD -> {
                confirm = null
                prefs(app).edit().remove(INSTALLING).apply()
                for (f in dir(app).listFiles().orEmpty()) f.delete()
                state.update { it.copy(phase = UpdatePhase.IDLE, readyCode = null, got = 0, message = o.text, err = true) }
            }
        }
    }

    private fun launchConfirm(ctx: Context) {
        val i = confirm
        if (i == null) {
            state.update { it.copy(phase = UpdatePhase.READY, message = "Окно установки не пришло — нажми «Установить» ещё раз.", err = true) }
            return
        }
        try {
            start(ctx, Intent(i))
        } catch (e: ActivityNotFoundException) {
            confirm = null
            state.update { it.copy(phase = UpdatePhase.READY, message = "На часах нет окна установки приложений. ${InstallStatus.BUGJAEGER}", err = true) }
        } catch (e: Exception) {
            // started from the background and refused: «Подтвердить установку» stays on the details page
            state.update { it.copy(message = "Нажми «Подтвердить установку».", err = false) }
        }
    }

    /** From the details page (an Activity) as is; from [InstallReceiver] it needs a task of its own. */
    @SuppressLint("WearRecents")
    private fun start(ctx: Context, i: Intent) {
        if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            state.update { it.copy(phase = if (it.phase == UpdatePhase.CHECKING) UpdatePhase.IDLE else it.phase, message = "Обновление: ${e.message ?: e.javaClass.simpleName}", err = true) }
        }
    }
}
