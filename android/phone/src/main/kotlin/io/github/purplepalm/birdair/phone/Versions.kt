package io.github.purplepalm.birdair.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** version.json of the android-latest release: {"versionCode": int, "versionName": str, "sha": str}. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val sha: String) {
    /** «v42 (0.42, 1a2b3c4)» */
    fun label(): String = "v$versionCode" + listOfNotNull(versionName.ifEmpty { null }, sha.take(7).ifEmpty { null }).joinToString(", ").let { if (it.isEmpty()) "" else " ($it)" }
}

/** A watch app's version, from its «hello». */
data class WatchVersion(val versionCode: Int?, val versionName: String?)

object Versions {
    private const val RELEASE = "https://github.com/Purple-Palm/Bird-Air-Tweaking/releases/download/android-latest/"
    const val VERSION_URL = RELEASE + "version.json"
    const val PHONE_APK_URL = RELEASE + "bird-air-phone.apk"
    const val PHONE_APK = "bird-air-phone.apk"
    const val WATCH_APK = "bird-air-watch.apk"
    /** Check this often while the app runs (and at every start, see [checkDue]). */
    const val CHECK_EVERY_MS = 24 * 3600_000L
    /** Starts closer together than this do not check again (the process starts for every watch «hello»). */
    const val START_GAP_MS = 10 * 60_000L

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** null for anything that is not a version.json with a positive versionCode. */
    fun parse(text: String?): UpdateInfo? {
        val o = obj(text) ?: return null
        val code = num(o["versionCode"]) ?: return null
        if (code <= 0) return null
        return UpdateInfo(code, str(o["versionName"]) ?: "", str(o["sha"]) ?: "")
    }

    /** Newer than what is installed: by versionCode only (names are free text, «dev» for local builds). */
    fun isNewer(remote: UpdateInfo?, installedCode: Int): Boolean = remote != null && remote.versionCode > installedCode

    /** Time to look for an update: never checked, a day passed, or [atStart] and the last check is [START_GAP_MS] old. */
    fun checkDue(lastCheckMs: Long, nowMs: Long, atStart: Boolean): Boolean {
        if (lastCheckMs <= 0 || nowMs < lastCheckMs) return true
        val age = nowMs - lastCheckMs
        return age >= CHECK_EVERY_MS || (atStart && age >= START_GAP_MS)
    }

    /**
     * The watch's «hello» payload: empty (a watch build from before versions), or UTF-8 JSON
     * {"versionCode": 42, "versionName": "0.42"} (also "vc"/"vn"), or lines "versionCode=42".
     */
    fun watch(bytes: ByteArray?): WatchVersion? {
        if (bytes == null || bytes.isEmpty()) return null
        val s = String(bytes, Charsets.UTF_8).trim()
        if (s.isEmpty()) return null
        val o = obj(s)
        if (o != null) {
            val code = num(o["versionCode"]) ?: num(o["vc"]) ?: num(o["code"])
            val name = str(o["versionName"]) ?: str(o["vn"]) ?: str(o["name"])
            return if (code == null && name == null) null else WatchVersion(code, name)
        }
        val kv = s.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val code = (kv["versionCode"] ?: kv["vc"])?.toIntOrNull()
        val name = kv["versionName"] ?: kv["vn"]
        return if (code == null && name == null) null else WatchVersion(code, name)
    }

    /**
     * The watch app is open or riding along and vibrates for the warnings itself: its last hello told a version (the
     * builds that send one also buzz, `wear/Alerts.kt` LampAlarm) and is at most [WATCH_ACTIVE_MS] old (it says
     * hello every 30 s while data flows, every 5 s while not). Then the phone's «Предупреждения» notification stays on
     * the phone (local-only), so the wrist does not get two buzzes; otherwise Wear OS mirrors it to the watch.
     */
    fun watchBuzzes(w: WatchStatus, nowElapsedMs: Long): Boolean =
        w.version != null && w.helloAt > 0 && nowElapsedMs >= w.helloAt && nowElapsedMs - w.helloAt <= WATCH_ACTIVE_MS

    const val WATCH_ACTIVE_MS = 45_000L

    /**
     * The watch hint: null when it is fine or unknown; the phone's own code 1 is a local build and compares with nothing.
     * The watch updates itself («Подробно» → «Обновить часы»); a build without that button (one that does not tell its
     * version at all, or an older one) is put on by hand once.
     */
    fun watchHint(w: WatchVersion?, helloSeen: Boolean, phoneCode: Int): String? {
        if (w == null) return if (helloSeen && phoneCode > 1) "Часы не сообщают версию — там старая сборка: один раз поставь $WATCH_APK вручную (android/README.md), дальше — $WATCH_UPDATE." else null
        val c = w.versionCode ?: return null
        return if (c < phoneCode) "На часах v$c, на телефоне v$phoneCode — $WATCH_UPDATE (нет такой кнопки — один раз вручную, android/README.md)." else null
    }

    /** Where the watch app updates itself. */
    const val WATCH_UPDATE = "на часах: Подробно → Обновить часы"

    /** The first watch build with the dial themes: android-latest v108 (a41290d) is the last with the classic dial only. */
    const val WATCH_FACES_SINCE = 109

    /**
     * The watch said hello from a build without the dial themes (it never reads the face's DataItem): a version code
     * under [WATCH_FACES_SINCE], or no version at all (older still). Code 1 is a local build, a name alone says nothing:
     * not known, false.
     */
    fun watchWithoutFaces(w: WatchStatus): Boolean {
        if (w.helloAt <= 0) return false
        val v = w.version ?: return true
        val c = v.versionCode ?: return false
        return c in 2 until WATCH_FACES_SINCE
    }

    private fun obj(text: String?): JsonObject? = try {
        text?.trim()?.takeIf { it.startsWith("{") }?.let { JSON.parseToJsonElement(it) as? JsonObject }
    } catch (e: Exception) { null }

    private fun num(e: Any?): Int? {
        val p = e as? JsonPrimitive ?: return null
        return p.intOrNull ?: p.longOrNull?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: p.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun str(e: Any?): String? = (e as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
