package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.LogRecorder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One file of a recording. A long ride is several parts ([LogRecorder.rotate] at [LogRecorder.maxEntries]); each is
 * saved and uploaded like a log of its own. The name: `<start of the part>-<slug of the description>` while it was
 * never uploaded, fixed at the first upload ([base]) — later uploads overwrite that file, like the page.
 */
class LogPart(val recorder: LogRecorder, val number: Int) {
    /** The name, fixed at the first upload; null before. */
    @Volatile var base: String? = null
    // local file
    @Volatile var savedPushed = -1L
    @Volatile var savedNote: String? = null
    @Volatile var savedFile: String? = null
    // GitHub: the file this part went to, its sha for the next overwrite, what was in it
    @Volatile var gh: GhPut? = null
    @Volatile var ghDest: String? = null
    @Volatile var ghPushed = -1L
    @Volatile var ghNote: String? = null
    @Volatile var ghAt = 0L
    @Volatile var ghFailedAt = 0L
    /** When the last upload was asked for (wall clock): spaces out uploads after drops. */
    @Volatile var ghTryAt = 0L

    /** Rotated: nothing new comes in any more. */
    val finished: Boolean get() = recorder.next != null
    val note: String? get() = Notes.clean(recorder.note)

    /** The file base now: the fixed one, else from the start time and the description. */
    fun baseNow(): String = base ?: recorder.fileBase()

    /** Something to save: new entries or another description. */
    val dirtyLocal: Boolean get() = recorder.pushed != savedPushed || note != savedNote || savedFile != baseNow() + LogFiles.EXT

    /** Something GitHub does not have yet ([dest]: the GhConfig.dest it goes to). */
    fun dirtyGh(dest: String): Boolean = !(ghPushed == recorder.pushed && ghDest == dest && ghNote == note && gh != null)
}

/**
 * One recording: from «Подключить» to «Отключить», reconnects included. [clock]: ms on the recording's clock (the
 * service passes elapsedRealtime − t0), the same for every part. Thread-safe.
 */
class Session(val startedAtMs: Long, private val clock: () -> Long, maxEntries: Int = MAX_ENTRIES) {
    private val list = CopyOnWriteArrayList<LogPart>()
    @Volatile var part: LogPart = LogPart(LogRecorder(startedAtMs, maxEntries), 1).also { list.add(it) }
        private set
    val parts: List<LogPart> get() = list
    /** The part being written. */
    val recorder: LogRecorder get() = part.recorder

    @Volatile var gatt: List<io.github.purplepalm.birdair.core.GattService> = emptyList()
    /** The app odometer at the first 3A 17 frame of the session (moved along with a correction): the distance for Wh/km. */
    @Volatile var appOdoStartKm: Double? = null
    val rx = java.util.concurrent.atomic.AtomicLong()

    fun now(): Long = clock()

    fun device(name: String?, id: String?) { for (p in list) { p.recorder.deviceName = name; p.recorder.deviceId = id } }

    /** The owner's description: every part of this recording gets it (and parts to come, through [LogRecorder.rotate]). */
    var note: String?
        get() = Notes.clean(part.recorder.note)
        set(v) { val n = Notes.clean(v); synchronized(this) { for (p in list) p.recorder.note = n } }

    /** A marker at now in the part being written. */
    fun mark(label: String): Boolean {
        val l = label.trim().take(60)
        if (l.isEmpty()) return false
        recorder.mk(l, now())
        return true
    }

    /** The marker [from] at [tMs] (this recording's clock) renamed [to], in whichever part has it (a rotation may sit between). */
    fun relabel(tMs: Long, from: String, to: String): Boolean = list.any { it.recorder.relabel(tMs, from, to) }

    /** [LogRecorder.needsRotation]: the next part starts now; returns the finished one, else null. */
    fun rotateIfNeeded(): LogPart? = synchronized(this) {
        val p = part
        if (!p.recorder.needsRotation) return null
        val r = p.recorder.rotate(now())
        val np = LogPart(r.next, list.size + 1)
        list.add(np)
        part = np
        p
    }

    companion object {
        const val MAX_ENTRIES = 250_000
    }
}

/** The description of a log. */
object Notes {
    /** Like birdlog.clean_note: trimmed, null when blank. */
    fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    /** The device's name and id taken out (the commit message is public), like LogRecorder's anonymization. */
    fun hide(text: String, name: String?, id: String?): String {
        var n = text
        val secrets = LogRecorder.secretNames(listOf(name, id)).filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
        for (s in secrets) {
            n = if (s.length < 4) Regex("(?<![0-9A-Za-zА-Яа-яЁё])" + Regex.escape(s) + "(?![0-9A-Za-zА-Яа-яЁё])").replace(n, "scooter")
            else n.replace(s, "scooter")
        }
        return n
    }
}

/** A log file on the phone. [current]: a part of the recording that is running now. */
data class LocalLog(val name: String, val size: Long, val mtime: Long, val current: Boolean = false) {
    val base: String get() = name.removeSuffix(LogFiles.EXT)
}

/** Where a local file went: [size] and [mtime] of the bytes that were uploaded. */
data class SentLog(val path: String, val sha: String?, val dest: String, val size: Long, val mtime: Long, val url: String?)

/** Log files and the record of what was uploaded (the app keeps it in SharedPreferences, one JSON per file name). */
object LogFiles {
    const val EXT = ".json.gz"
    private val JSON = Json { ignoreUnknownKeys = true }

    fun encode(s: SentLog): String = JsonObject(buildMap {
        put("path", JsonPrimitive(s.path)); put("dest", JsonPrimitive(s.dest))
        put("size", JsonPrimitive(s.size)); put("mtime", JsonPrimitive(s.mtime))
        s.sha?.let { put("sha", JsonPrimitive(it)) }
        s.url?.let { put("url", JsonPrimitive(it)) }
    }).toString()

    fun decode(text: String?): SentLog? = try {
        val o = text?.let { JSON.parseToJsonElement(it) as? JsonObject } ?: null
        if (o == null) null else {
            val path = (o["path"] as? JsonPrimitive)?.contentOrNull
            val dest = (o["dest"] as? JsonPrimitive)?.contentOrNull
            val size = (o["size"] as? JsonPrimitive)?.longOrNull
            val mtime = (o["mtime"] as? JsonPrimitive)?.longOrNull
            if (path == null || dest == null || size == null || mtime == null) null
            else SentLog(path, (o["sha"] as? JsonPrimitive)?.contentOrNull, dest, size, mtime, (o["url"] as? JsonPrimitive)?.contentOrNull)
        }
    } catch (e: Exception) { null }

    /** A file is sent when the bytes on the phone are the ones that were uploaded (same size and time). */
    fun isSent(f: LocalLog, sent: SentLog?): Boolean = sent != null && sent.size == f.size && sent.mtime == f.mtime

    /** Not uploaded yet (or changed since), newest first. */
    fun unsent(files: List<LocalLog>, sent: Map<String, SentLog>): List<LocalLog> =
        files.filter { it.name.endsWith(EXT) && !isSent(it, sent[it.name]) }.sortedByDescending { it.mtime }

    /**
     * Which files to delete to keep [keep]: the oldest sent ones beyond it; unsent ones only beyond [hardKeep]
     * (never one of the running recording).
     */
    fun prune(files: List<LocalLog>, sent: Map<String, SentLog>, keep: Int, hardKeep: Int): List<LocalLog> {
        val all = files.filter { it.name.endsWith(EXT) }.sortedByDescending { it.mtime }
        if (all.size <= keep) return emptyList()
        val out = ArrayList<LocalLog>()
        var left = all.size
        for (f in all.drop(keep).asReversed()) if (!f.current && isSent(f, sent[f.name])) { out.add(f); left-- }
        if (left > hardKeep) for (f in all.asReversed()) {
            if (left <= hardKeep) break
            if (!f.current && f !in out) { out.add(f); left-- }
        }
        return out
    }

    /** What a saved log says about itself: its "note" and how many entries (streamed, the file is not kept in memory). */
    class Peek(val note: String?, val entries: Int)

    /** [input]: the decompressed JSON of a log as LogRecorder writes it. */
    fun peek(input: InputStream): Peek {
        val head = StringBuilder()
        val pat = "{\"t\":".toByteArray(Charsets.US_ASCII)
        var match = 0
        var count = 0
        val buf = ByteArray(1 shl 16)
        val headBytes = java.io.ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (headBytes.size() < HEAD_BYTES) headBytes.write(buf, 0, minOf(n, HEAD_BYTES - headBytes.size()))
            for (k in 0 until n) {
                val b = buf[k]
                match = if (b == pat[match]) match + 1 else if (b == pat[0]) 1 else 0
                if (match == pat.size) { count++; match = 0 }
            }
        }
        head.append(String(headBytes.toByteArray(), Charsets.UTF_8))
        return Peek(noteIn(head.toString()), count)
    }

    /** The top-level "note" in the head of a log (it comes before "gatt", "decoder" and "entries"). */
    fun noteIn(head: String): String? {
        val stop = head.indexOf("\"entries\"").let { if (it < 0) head.length else it }
        val gatt = head.indexOf(",\"gatt\":").let { if (it < 0) stop else minOf(it, stop) }
        val i = head.lastIndexOf(",\"note\":", gatt)
        if (i < 0) return null
        var j = i + 8
        if (j >= head.length || head[j] != '"') return null
        val sb = StringBuilder("\"")
        j++
        while (j < head.length) {
            val c = head[j]
            sb.append(c)
            if (c == '\\' && j + 1 < head.length) { sb.append(head[j + 1]); j += 2; continue }
            if (c == '"') break
            j++
        }
        return try { Notes.clean((JSON.parseToJsonElement(sb.toString()) as? JsonPrimitive)?.contentOrNull) } catch (e: Exception) { null }
    }

    private const val HEAD_BYTES = 64 * 1024
}
