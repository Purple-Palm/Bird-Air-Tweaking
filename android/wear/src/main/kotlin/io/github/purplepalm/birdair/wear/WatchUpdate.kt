package io.github.purplepalm.birdair.wear

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/*
 * The watch updates itself from the android-latest release, without Bugjaeger: pure logic here (JVM tests),
 * network, files and PackageInstaller in WatchUpdater.kt. Nothing here talks to the scooter.
 */

/**
 * version.json of android-latest: {"versionCode": 112, "versionName": "12-1a2b3c4", "sha": "…"}, one versionCode
 * for both APKs of a CI run; CI may add "watchSha256" / "watchSize" of bird-air-watch.apk.
 */
data class WatchRelease(
    val versionCode: Int,
    val versionName: String = "",
    val sha: String = "",
    val watchSha256: String? = null,
    val watchSize: Long? = null,
)

object Release {
    private const val BASE = "https://github.com/Purple-Palm/Bird-Air-Tweaking/releases/download/android-latest/"
    const val VERSION_URL = BASE + "version.json"
    const val WATCH_APK = "bird-air-watch.apk"
    const val WATCH_APK_URL = BASE + WATCH_APK
    /** Check this often while the process lives, and at every start (see [checkDue]). */
    const val CHECK_EVERY_MS = 24 * 3600_000L
    /** Starts closer together than this do not check again. */
    const val START_GAP_MS = 10 * 60_000L

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** null for anything that is not a version.json with a positive versionCode. */
    fun parse(text: String?): WatchRelease? {
        val o = try {
            text?.trim()?.takeIf { it.startsWith("{") }?.let { JSON.parseToJsonElement(it) as? JsonObject }
        } catch (e: Exception) { null } ?: return null
        val code = long(o["versionCode"])?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
        return WatchRelease(
            code,
            str(o["versionName"]) ?: "",
            str(o["sha"]) ?: "",
            sha256(str(o["watchSha256"])),
            long(o["watchSize"])?.takeIf { it > 0 },
        )
    }

    /** By versionCode only: names are free text («dev» for a local build, which is code 1). */
    fun isNewer(r: WatchRelease?, installedCode: Int): Boolean = r != null && r.versionCode > installedCode

    /** Never checked, a day passed, the clock went back, or [atStart] and the last check is [START_GAP_MS] old. */
    fun checkDue(lastCheckMs: Long, nowMs: Long, atStart: Boolean): Boolean {
        if (lastCheckMs <= 0 || nowMs < lastCheckMs) return true
        val age = nowMs - lastCheckMs
        return age >= CHECK_EVERY_MS || (atStart && age >= START_GAP_MS)
    }

    /** Lower-case 64 hex digits, or null (a malformed hash counts as none: the installer still checks the signature). */
    fun sha256(s: String?): String? = s?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    private fun long(e: Any?): Long? {
        val p = e as? JsonPrimitive ?: return null
        return p.longOrNull ?: p.contentOrNull?.trim()?.toLongOrNull()
    }

    private fun str(e: Any?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}

/** The release download redirects github.com → objects / release-assets .githubusercontent.com: HTTPS and GitHub only. */
object Redirects {
    const val MAX = 5

    fun allowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "github.com" || h.endsWith(".githubusercontent.com")
    }

    /** The next URL for a 3xx from [from] with [location] (may be relative); null when missing or not allowed. */
    fun next(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val u = try { URI(from).resolve(location.trim()) } catch (e: Exception) { return null }
        if (!u.scheme.equals("https", ignoreCase = true) || !allowedHost(u.host)) return null
        return u.toString()
    }
}

object Sha256 {
    fun hex(d: ByteArray): String = d.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun of(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    fun of(f: File): String = f.inputStream().use { of(it) }

    /** No published hash: nothing to compare (true). */
    fun matches(expected: String?, actual: String?): Boolean {
        val e = Release.sha256(expected) ?: return true
        return e == Release.sha256(actual)
    }
}

/** HTTP range arithmetic of a resumed download. */
object Ranges {
    /** «bytes 100-199/200» → 100. */
    fun start(contentRange: String?): Long? =
        contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim()?.toLongOrNull()

    /** «bytes 100-199/200» → 200; an unknown total (an asterisk) → null. */
    fun total(contentRange: String?): Long? = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    /** The whole file's size for a 200 (from 0) or a 206 (from [offset]); null when the server did not say. */
    fun total(code: Int, offset: Long, contentLength: Long, contentRange: String?): Long? = when (code) {
        206 -> total(contentRange) ?: if (contentLength >= 0) offset + contentLength else null
        200 -> if (contentLength >= 0) contentLength else null
        else -> null
    }
}

/** Checks of a downloaded file before the installer sees it; null = fine, else what is wrong (Russian). */
object ApkCheck {
    fun file(r: WatchRelease, size: Long, sha256: String?): String? = when {
        size <= 0 -> "файл пустой"
        r.watchSize != null && size != r.watchSize -> "размер ${Fmt.num(size.toDouble(), 0)} байт вместо ${Fmt.num(r.watchSize.toDouble(), 0)}"
        !Sha256.matches(r.watchSha256, sha256) -> "контрольная сумма SHA-256 не совпала"
        else -> null
    }

    /** [pkg] / [code]: of the APK (getPackageArchiveInfo); null pkg: not an APK at all. */
    fun apk(pkg: String?, code: Long?, ourPkg: String, installedCode: Int): String? = when {
        pkg == null -> "это не APK"
        pkg != ourPkg -> "это не Bird Air ($pkg)"
        code == null || code <= installedCode -> "в файле v$code, не новее этой (v$installedCode)"
        else -> null
    }
}

/** A network failure in a few Russian words (our own IOExceptions already are). */
object NetError {
    fun text(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "нет интернета (не нашёлся github.com)"
        is java.net.SocketTimeoutException -> "сеть не отвечает"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "нет соединения"
        is javax.net.ssl.SSLException -> "сбой защищённого соединения"
        else -> e.message?.trim()?.takeIf { it.isNotEmpty() } ?: "нет сети"
    }
}

/** Download speed over the last [windowMs]. */
class Rate(private val windowMs: Long = 3000) {
    private val ts = ArrayDeque<Pair<Long, Long>>()

    fun add(tMs: Long, totalBytes: Long) {
        ts.addLast(tMs to totalBytes)
        while (ts.size > 2 && tMs - ts.first().first > windowMs) ts.removeFirst()
    }

    fun bytesPerS(): Double? {
        if (ts.size < 2) return null
        val (t0, b0) = ts.first()
        val (t1, b1) = ts.last()
        return if (t1 - t0 < 300) null else (b1 - b0) * 1000.0 / (t1 - t0)
    }
}

/**
 * PackageInstaller.STATUS_* (the SDK's values, a test pins them) → what the details page says and does next.
 */
object InstallStatus {
    const val PENDING_USER_ACTION = -1
    const val SUCCESS = 0
    const val FAILURE = 1
    const val FAILURE_BLOCKED = 2
    const val FAILURE_ABORTED = 3
    const val FAILURE_INVALID = 4
    const val FAILURE_CONFLICT = 5
    const val FAILURE_STORAGE = 6
    const val FAILURE_INCOMPATIBLE = 7
    const val FAILURE_TIMEOUT = 8

    /** CONFIRM: start the system dialog; RETRY: the file is fine, tap «Установить» again; REDOWNLOAD: drop the file. */
    enum class Kind { CONFIRM, DONE, CANCELLED, RETRY, REDOWNLOAD }

    data class Outcome(val kind: Kind, val text: String?, val err: Boolean)

    const val BUGJAEGER = "Если не выйдет — поставь ${Release.WATCH_APK} через Bugjaeger, как в первый раз."

    fun of(status: Int, message: String?): Outcome {
        val why = message?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return when (status) {
            PENDING_USER_ACTION -> Outcome(Kind.CONFIRM, null, false)
            SUCCESS -> Outcome(Kind.DONE, "Часы обновлены. Открой Bird Air снова.", false)
            FAILURE_ABORTED -> Outcome(Kind.CANCELLED, "Установка отменена.", false)
            FAILURE_BLOCKED -> Outcome(Kind.RETRY, "Система не дала поставить приложение$why. $BUGJAEGER", true)
            FAILURE_INVALID -> Outcome(Kind.REDOWNLOAD, "Файл не подошёл установщику$why. Скачай заново.", true)
            FAILURE_CONFLICT -> Outcome(Kind.RETRY, "Конфликт с установленной версией$why. $BUGJAEGER", true)
            FAILURE_STORAGE -> Outcome(Kind.RETRY, "На часах мало места$why. Освободи память и нажми «Установить».", true)
            FAILURE_INCOMPATIBLE -> Outcome(Kind.REDOWNLOAD, "Сборка не встаёт поверх этой: другая подпись или версия Android$why. Удали Bird Air с часов и поставь через Bugjaeger.", true)
            FAILURE_TIMEOUT -> Outcome(Kind.RETRY, "Установщик не ответил вовремя$why. Нажми «Установить» ещё раз.", true)
            FAILURE -> Outcome(Kind.RETRY, "Не установилось$why. Нажми «Установить» ещё раз. $BUGJAEGER", true)
            else -> Outcome(Kind.RETRY, "Установщик ответил $status$why. $BUGJAEGER", true)
        }
    }
}

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, CONFIRM }

/**
 * The updater as the details page shows it. [got]: bytes downloaded (in IDLE: a partial file to resume);
 * [total]: the APK's size when known; [wifi]: the download goes over Wi-Fi (null: not decided yet);
 * [readyCode]: a downloaded and checked APK waits for «Установить».
 */
data class UpdateState(
    val latest: WatchRelease? = null,
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val got: Long = 0,
    val total: Long? = null,
    val bytesPerS: Double? = null,
    val wifi: Boolean? = null,
    val readyCode: Int? = null,
    val needsPermission: Boolean = false,
    val message: String? = null,
    val err: Boolean = false,
)

enum class UpdateAction { NONE, CHECK, DOWNLOAD, INSTALL, ALLOW, CONFIRM }

/** The details page's update chip. [progress]: 0..1 of the download; [cancel]: an «Отменить» chip below. */
data class UpdateChip(
    val label: String,
    val secondary: String? = null,
    val action: UpdateAction = UpdateAction.NONE,
    val progress: Float? = null,
    val cancel: Boolean = false,
    val primary: Boolean = false,
    /** A line under the chip (the message, if any, goes below it). */
    val hint: String? = null,
)

object UpdateView {
    const val WIFI_HINT = "быстрее по Wi-Fi"

    /** Over the phone's Bluetooth a 25 MB APK takes minutes. */
    const val SLOW_HINT = "По Bluetooth телефона это долго. Быстрее по Wi-Fi: подключи часы к сети в их настройках."

    fun mb(bytes: Long): String = Fmt.withUnit(bytes / 1_048_576.0, 1, "МБ")

    /** «6,1 из 24,5 МБ», or «6,1 МБ» without a total. */
    fun of(got: Long, total: Long?): String =
        if (total == null) mb(got) else Fmt.num(got / 1_048_576.0, 1) + " из " + mb(total)

    fun speed(bps: Double?): String? = when {
        bps == null || !bps.isFinite() || bps <= 0 -> null
        bps >= 1_048_576 -> Fmt.withUnit(bps / 1_048_576, 1, "МБ/с")
        else -> Fmt.withUnit(bps / 1024, 0, "КБ/с")
    }

    fun of(s: UpdateState, installedCode: Int): UpdateChip {
        val v = s.latest?.versionCode
        return when (s.phase) {
            UpdatePhase.CHECKING -> UpdateChip("Проверяю обновления…")
            UpdatePhase.DOWNLOADING -> {
                val total = s.total?.takeIf { it > 0 }
                val frac = total?.let { (s.got.toDouble() / it).coerceIn(0.0, 1.0) }
                val label = if (frac != null) "Скачиваю " + Fmt.num(100 * frac, 0) + "\u00A0%" else "Скачиваю v$v"
                val via = when (s.wifi) {
                    null -> "включаю Wi-Fi…"
                    true -> "по Wi-Fi"
                    false -> "через телефон"
                }
                UpdateChip(
                    label, listOfNotNull(of(s.got, total), speed(s.bytesPerS)).joinToString(" · ") + "\n" + via,
                    progress = frac?.toFloat() ?: 0f, cancel = true, hint = if (s.wifi == false) SLOW_HINT else null,
                )
            }
            UpdatePhase.READY -> when {
                s.needsPermission -> UpdateChip("Разрешить установку", "один раз: «Неизвестные приложения»", UpdateAction.ALLOW, primary = true)
                else -> UpdateChip("Установить v${s.readyCode}", "Bird Air закроется — открой снова", UpdateAction.INSTALL, primary = true)
            }
            UpdatePhase.INSTALLING -> UpdateChip("Устанавливаю v${s.readyCode}…")
            UpdatePhase.CONFIRM -> UpdateChip("Подтвердить установку", "v${s.readyCode} · окно установщика", UpdateAction.CONFIRM, primary = true)
            UpdatePhase.IDLE -> if (Release.isNewer(s.latest, installedCode)) {
                val size = s.total ?: s.latest?.watchSize
                val from = when {
                    s.got > 0 -> "докачаю с " + of(s.got, size)
                    size != null -> mb(size)
                    else -> null
                }
                UpdateChip(if (s.err) "Повторить (v$v)" else "Обновить часы (v$v)", listOfNotNull(from, WIFI_HINT).joinToString(" · "), UpdateAction.DOWNLOAD, primary = true)
            } else {
                UpdateChip("Проверить обновления", v?.let { if (it == installedCode) "стоит последняя: v$it" else "в релизе v$it" }, UpdateAction.CHECK)
            }
        }
    }
}
