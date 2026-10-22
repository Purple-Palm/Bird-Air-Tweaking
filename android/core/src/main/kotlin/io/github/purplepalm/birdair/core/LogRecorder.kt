package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/** Characteristic properties as the page logs them. */
@Serializable
data class GattProps(
    val read: Boolean = false,
    val write: Boolean = false,
    val wnr: Boolean = false,
    val notify: Boolean = false,
    val indicate: Boolean = false,
)

@Serializable
data class GattChar(val uuid: String, val props: GattProps = GattProps())

@Serializable
data class GattService(val uuid: String, val chars: List<GattChar> = emptyList())

/**
 * A recording in the birdair-lab-log v1 format (logs/README.md), the same file the page's sessionObject()
 * makes, so tools/analyze_log.py and tools/uart_frames.py read it as is.
 * Every tMs is milliseconds on the caller's clock; the log's t is tMs − [tOffsetMs] (0 for the first part: ms since
 * the recording started). [startedAtMs] is wall-clock epoch ms at t = 0.
 * Long recordings: when [needsRotation], [rotate] finishes this log and gives the next part, which goes on with
 * the same caller clock under a new file name. Without that, above [maxEntries] the oldest entries are dropped in
 * steps, and the log says "trimmed": true. [note]: the owner's description, written as "note" and slugged into
 * [fileBase]. Thread-safe.
 */
class LogRecorder(val startedAtMs: Long, val maxEntries: Int = 250_000, val tOffsetMs: Long = 0) {
    private class Entry(
        val t: Long, val k: String, val s: String?, val u: String?, val b: ByteArray?,
        val n: String?, val v: Double?, val a: Long?, val err: Boolean,
    )

    private val entries = ArrayList<Entry>()
    private val uuids = HashMap<String, String>()
    private val trimStep = maxOf(1, minOf(10_000, maxEntries / 25))
    private var trimmedFlag = false

    /** The scooter's BLE name and address: written as is only when not anonymized. */
    @Volatile var deviceName: String? = null
    @Volatile var deviceId: String? = null
    @Volatile var demo: Boolean = false
    /** The owner's description of the log: top-level "note" (trimmed; blank → not written) and the file name's label. */
    @Volatile var note: String? = null
    /** Set by [rotate]: entries added here afterwards go to that part. */
    @Volatile var next: LogRecorder? = null
        private set

    val size: Int
        @Synchronized get() = entries.size
    val trimmed: Boolean
        @Synchronized get() = trimmedFlag
    /** Entries ever added, trimmed ones included. */
    var pushed: Long = 0
        @Synchronized get
        private set

    fun rx(service: String, char: String, bytes: ByteArray, tMs: Long) = data("rx", service, char, bytes, tMs)
    fun rd(service: String, char: String, bytes: ByteArray, tMs: Long) = data("rd", service, char, bytes, tMs)
    fun tx(service: String, char: String, bytes: ByteArray, tMs: Long) = data("tx", service, char, bytes, tMs)

    /** A service line; [err] is kept for display only (the page does not write it into the file either). */
    fun sys(text: String, tMs: Long, err: Boolean = false) = add(Entry(tMs, "sys", null, null, null, text, null, null, err))

    /** A marker («Газ», «Тормоз»…), cut to 60 characters like the page. */
    fun mk(label: String, tMs: Long) = add(Entry(tMs, "mk", null, null, null, label.take(60), null, null, false))

    /** Phone GPS speed (m/s) and accuracy (m); never coordinates. */
    fun gps(speedMs: Double, accuracyM: Double?, tMs: Long) {
        if (!speedMs.isFinite()) return
        add(Entry(tMs, "gps", null, null, null, null, Math.round(speedMs * 100) / 100.0, accuracyM?.takeIf { it.isFinite() }?.let { Math.round(it) }, false))
    }

    /** The line the page writes after each service discovery; the tools start a new frame stream there. */
    fun servicesFound(services: Int, chars: Int, tMs: Long) = sys("$SYS_FOUND$services, характеристик: $chars", tMs)

    private fun data(k: String, service: String, char: String, bytes: ByteArray, tMs: Long) =
        add(Entry(tMs, k, intern(service), intern(char), bytes.copyOf(), null, null, null, false))

    @Synchronized
    private fun intern(u: String): String = uuids.getOrPut(u) { Uuids.full(u) }

    @Synchronized
    private fun add(e0: Entry) {
        next?.let { n -> n.add(e0); return }      // rotated: a late caller still holding this part writes into the next one (same clock)
        val e = if (tOffsetMs == 0L) e0 else Entry(e0.t - tOffsetMs, e0.k, e0.s, e0.u, e0.b, e0.n, e0.v, e0.a, e0.err)
        entries.add(e)
        pushed++
        if (entries.size > maxEntries + trimStep) {
            entries.subList(0, entries.size - maxEntries).clear()
            trimmedFlag = true
        }
    }

    /**
     * The marker [from] at [tMs] (the caller's clock) renamed [to] (cut to 60, like [mk]); false when this part has no such
     * marker. Counts as a change ([pushed] + 1) so the next save and upload carry it.
     */
    @Synchronized
    fun relabel(tMs: Long, from: String, to: String): Boolean {
        val t = tMs - tOffsetMs
        val name = from.take(60)
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.k != "mk" || e.t != t || e.n != name) continue
            entries[i] = Entry(e.t, e.k, e.s, e.u, e.b, to.take(60), e.v, e.a, e.err)
            pushed++
            return true
        }
        return false
    }

    @Synchronized
    fun clear() {
        entries.clear()
        trimmedFlag = false
    }

    /** Time to [rotate]: [maxEntries] reached (trimming starts maxEntries / 25, at most 10 000, entries later). */
    val needsRotation: Boolean
        @Synchronized get() = next == null && entries.size >= maxEntries

    /** This part, finished, and the next one. */
    class Rotation(val finished: LogRecorder, val next: LogRecorder)

    /**
     * Finishes this log at [tMs] (the caller's clock) and starts the next part: startedAt = this one's wall clock at
     * [tMs], the same caller clock ([tOffsetMs] = [tMs]), the same device, demo flag and note, and as its first entry
     * the sys line «продолжение лога <this part's file base>». Entries added here afterwards go to the next part.
     * File names have 1 s resolution: parts less than a second apart would share one. Rotating twice returns the
     * same next part.
     */
    @Synchronized
    fun rotate(tMs: Long, zone: ZoneId = ZoneId.systemDefault()): Rotation {
        next?.let { return Rotation(this, it) }
        val n = LogRecorder(startedAtMs + (tMs - tOffsetMs), maxEntries, tMs)
        n.deviceName = deviceName; n.deviceId = deviceId; n.demo = demo; n.note = note
        n.sys(CONTINUED + fileBase(null, zone), tMs)
        next = n
        return Rotation(this, n)
    }

    /** The log as JSON. [anonymize]: device → scooter, the name masked in packets (see [writeJson]). */
    fun toJson(anonymize: Boolean = true, gatt: List<GattService>? = null, decoder: DecoderSpec? = null): String {
        val sb = StringBuilder(64 + minOf(size, 20_000) * 60)
        write(sb, anonymize, gatt, decoder)
        return sb.toString()
    }

    /** The log as .json.gz, streamed: no JSON string of the whole log in memory. */
    fun toGzip(anonymize: Boolean = true, gatt: List<GattService>? = null, decoder: DecoderSpec? = null): ByteArray {
        val bo = ByteArrayOutputStream(16 + size * 12)
        GZIPOutputStream(bo, 1 shl 16).use { writeJson(it, anonymize, gatt, decoder) }
        return bo.toByteArray()
    }

    /**
     * Writes the log as UTF-8 JSON to [out] (not closed). Anonymized like the page and tools/birdlog.py anonymize():
     * device {name: "scooter", id: "scooter"}; the name, its 6+ digit runs and the id replaced by «scooter» in
     * text entries; Device Name / Serial Number values → «scooter», System ID → zeros; the name masked with '*'
     * inside packets, also across notifications of one characteristic. On top of that whatever follows the dash's
     * «AT+SETNAME=» up to the line end is masked with '*' even when the name is unknown.
     */
    fun writeJson(out: OutputStream, anonymize: Boolean = true, gatt: List<GattService>? = null, decoder: DecoderSpec? = null) {
        val w = OutputStreamWriter(out, Charsets.UTF_8).buffered(1 shl 16)
        write(w, anonymize, gatt, decoder)
        w.flush()
    }

    /** YYYYMMDD-HHMMSS-<label> from [startedAtMs] in local time, like the page's GitHub file name (its ghBase);
     *  [label] null: the [note] (the page's «Подпись в имени файла») with the device's name and id taken out (the
     *  file name is public too), else "log" / "demo". */
    fun fileBase(label: String? = null, zone: ZoneId = ZoneId.systemDefault()): String {
        val text = label ?: note?.let { hide(it, secretNames(listOf(deviceName, deviceId)).filter { s -> s.length >= 2 }.sortedByDescending { s -> s.length }) }
        return stamp(startedAtMs, zone) + "-" + slug(text, if (demo) "demo" else "log")
    }

    fun fileName(label: String?, gz: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        fileBase(label, zone) + if (gz) ".json.gz" else ".json"

    // -- writing

    private fun write(w: Appendable, anonymize: Boolean, gatt: List<GattService>?, decoder: DecoderSpec?) {
        val es: List<Entry>
        val wasTrimmed: Boolean
        synchronized(this) { es = ArrayList(entries); wasTrimmed = trimmedFlag }
        var name = deviceName
        var id = deviceId
        var text = note?.trim()?.takeIf { it.isNotEmpty() }
        val list = if (anonymize) {
            val (l, secrets) = anonymized(es, name, id)
            text = text?.let { hide(it, secrets) }
            name = SCOOTER; id = SCOOTER
            l
        } else es
        w.append("{\"format\":\"").append(FORMAT).append("\",\"version\":1,\"startedAt\":")
        quote(w, ISO.format(Instant.ofEpochMilli(startedAtMs)))
        w.append(",\"device\":{\"name\":"); quoteOrNull(w, name)
        w.append(",\"id\":"); quoteOrNull(w, id)
        w.append("},\"demo\":").append(demo.toString()).append(",\"trimmed\":").append(wasTrimmed.toString())
        if (text != null) { w.append(",\"note\":"); quote(w, text) }
        w.append(",\"gatt\":").append(JSON.encodeToString(GATT, gatt ?: emptyList()))
        w.append(",\"decoder\":").append(Decoders.toJsonObject(decoder ?: DecoderSpec()).toString())
        w.append(",\"entries\":[")
        for ((i, e) in list.withIndex()) {
            if (i > 0) w.append(',')
            w.append("{\"t\":").append(e.t.toString()).append(",\"k\":\"").append(e.k).append('"')
            if (e.s != null) { w.append(",\"s\":"); quote(w, e.s) }
            if (e.u != null) { w.append(",\"u\":"); quote(w, e.u) }
            if (e.b != null) w.append(",\"h\":\"").append(Hex.of(e.b)).append('"')
            if (e.n != null) { w.append(",\"n\":"); quote(w, e.n) }
            if (e.v != null) w.append(",\"v\":").append(JsonPrimitive(e.v).toString())
            if (e.a != null) w.append(",\"a\":").append(e.a.toString())
            w.append('}')
        }
        w.append("]}")
    }

    // -- anonymization (assets/app.js anonymizeLog, tools/birdlog.py anonymize)

    /** The entries anonymized, and the strings hidden in text (for the note too). */
    private fun anonymized(es: List<Entry>, name: String?, id: String?): Pair<List<Entry>, List<String>> {
        val out = ArrayList(es)
        // byte streams: one per (kind, service, characteristic), Device Name / Serial / System ID left out
        val streams = LinkedHashMap<String, MutableList<Int>>()
        for ((i, e) in es.withIndex()) {
            if (e.b == null || (e.k != "rx" && e.k != "rd" && e.k != "tx") || e.u in NAME_CHARS || e.u in ZERO_CHARS) continue
            streams.getOrPut(e.k + "|" + e.s + "|" + e.u) { ArrayList() }.add(i)
        }
        val flats = streams.values.map { idx -> flatten(idx.map { es[it].b!! }) }
        val found = ArrayList<String>()
        val spans = flats.map { f -> setNameSpans(f).onEach { r ->
            val s = String(f, r.first, r.last - r.first + 1, Charsets.UTF_8)
            if (s.any { it != '*' }) found.add(s)
        } }
        val secrets = secretNames(listOf(name, id) + found).filter { it.length >= 2 }.distinct().sortedByDescending { it.length }
        // text entries
        for ((i, e) in es.withIndex()) {
            if (e.n == null) continue
            val n = hide(e.n, secrets)
            if (n != e.n) out[i] = Entry(e.t, e.k, e.s, e.u, e.b, n, e.v, e.a, e.err)
        }
        // characteristics that hold the name or the MAC
        for ((i, e) in es.withIndex()) {
            if (e.b == null) continue
            if (e.u in NAME_CHARS) out[i] = Entry(e.t, e.k, e.s, e.u, SCOOTER_BYTES, e.n, e.v, e.a, e.err)
            else if (e.u in ZERO_CHARS) out[i] = Entry(e.t, e.k, e.s, e.u, ByteArray(e.b.size), e.n, e.v, e.a, e.err)
        }
        // the name inside packets, also split across notifications
        val pats = secrets.map { it.toByteArray(Charsets.UTF_8) }.filter { it.size >= 4 }.sortedByDescending { it.size }
        for ((si, idx) in streams.values.withIndex()) {
            val flat = flats[si]
            val masked = flat.copyOf()
            var hit = false
            for (p in pats) {
                var pos = indexOf(flat, p, 0)
                while (pos >= 0) {
                    masked.fill(STAR, pos, pos + p.size)
                    hit = true
                    pos = indexOf(flat, p, pos + 1)
                }
            }
            for (r in spans[si]) { masked.fill(STAR, r.first, r.last + 1); hit = true }
            if (!hit) continue
            var off = 0
            for (i in idx) {
                val e = out[i]
                val n = e.b!!.size
                val nb = masked.copyOfRange(off, off + n)
                if (!nb.contentEquals(e.b)) out[i] = Entry(e.t, e.k, e.s, e.u, nb, e.n, e.v, e.a, e.err)
                off += n
            }
        }
        return out to secrets
    }

    private fun hide(text: String, secrets: List<String>): String {
        var n = text
        for (s in secrets) n = if (s.length < 4) word(s).replace(n, SCOOTER) else n.replace(s, SCOOTER)
        return n
    }

    companion object {
        const val FORMAT = "birdair-lab-log"
        /** sys line after each service discovery: a new link, the tools drop half-assembled frames there. */
        const val SYS_FOUND = "Найдено сервисов: "
        /** First entry of a part made by [rotate], followed by the previous part's file base. */
        const val CONTINUED = "продолжение лога "
        private const val SCOOTER = "scooter"
        private val SCOOTER_BYTES = SCOOTER.toByteArray(Charsets.US_ASCII)
        private const val STAR = '*'.code.toByte()
        private val SETNAME = "AT+SETNAME=".toByteArray(Charsets.US_ASCII)
        /** Device Name, Serial Number String; System ID (derived from the MAC). */
        private val NAME_CHARS = setOf(Uuids.full("2a00"), Uuids.full("2a25"))
        private val ZERO_CHARS = setOf(Uuids.full("2a23"))
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val JSON = Json { encodeDefaults = true }
        private val GATT = kotlinx.serialization.builtins.ListSerializer(GattService.serializer())

        private val TRANSLIT: Map<Char, String> = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя".toList().zip(listOf(
            "a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
            "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya",
        )).toMap()

        /** File-name label: transliterated, [a-z0-9-], at most 40 characters (page and birdlog: slug). */
        fun slug(label: String?, default: String = "log"): String {
            val sb = StringBuilder()
            for (c in (label ?: "").lowercase()) sb.append(TRANSLIT[c] ?: c.toString())
            val s = sb.toString().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trimEnd('-')
            return s.ifEmpty { default }
        }

        /** YYYYMMDD-HHMMSS in [zone]. */
        fun stamp(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

        /** The strings to hide for a device: each name and any serial-like run of 6+ digits in it. */
        fun secretNames(names: List<String?>): List<String> {
            val out = ArrayList<String>()
            for (n in names) {
                if (n.isNullOrEmpty() || n == SCOOTER) continue
                out.add(n)
                Regex("\\d{6,}").findAll(n).forEach { out.add(it.value) }
            }
            return out
        }

        /** A short secret (a 2–3 letter name) only counts as a whole word, so «MTU» or «LE …» survive. */
        private fun word(s: String) = Regex("(?<![0-9A-Za-zА-Яа-яЁё])" + Regex.escape(s) + "(?![0-9A-Za-zА-Яа-яЁё])")

        /** Bytes after each «AT+SETNAME=» up to the line end (a control byte), at most 64. */
        private fun setNameSpans(flat: ByteArray): List<IntRange> {
            val out = ArrayList<IntRange>()
            var pos = indexOf(flat, SETNAME, 0)
            while (pos >= 0) {
                val from = pos + SETNAME.size
                var to = from
                while (to < flat.size && to - from < 64) {
                    val x = flat[to].toInt() and 0xFF
                    if (x < 0x20 || x == 0x7F) break
                    to++
                }
                if (to > from) out.add(from until to)
                pos = indexOf(flat, SETNAME, from)
            }
            return out
        }

        private fun flatten(parts: List<ByteArray>): ByteArray {
            val out = ByteArray(parts.sumOf { it.size })
            var off = 0
            for (p in parts) { p.copyInto(out, off); off += p.size }
            return out
        }

        private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            if (needle.isEmpty()) return -1
            val first = needle[0]
            var i = maxOf(0, from)
            val last = hay.size - needle.size
            outer@ while (i <= last) {
                if (hay[i] != first) { i++; continue }
                for (k in 1 until needle.size) if (hay[i + k] != needle[k]) { i++; continue@outer }
                return i
            }
            return -1
        }

        private fun quoteOrNull(w: Appendable, s: String?) { if (s == null) w.append("null") else quote(w, s) }

        private fun quote(w: Appendable, s: String) {
            w.append('"')
            for (c in s) {
                when {
                    c == '"' -> w.append("\\\"")
                    c == '\\' -> w.append("\\\\")
                    c == '\n' -> w.append("\\n")
                    c == '\r' -> w.append("\\r")
                    c == '\t' -> w.append("\\t")
                    c < ' ' || c == ' ' || c == ' ' -> w.append(String.format("\\u%04x", c.code))
                    else -> w.append(c)
                }
            }
            w.append('"')
        }
    }
}
