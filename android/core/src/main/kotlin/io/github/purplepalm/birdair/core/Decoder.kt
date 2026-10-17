package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One decoder field (birdair-lab-decoder, same model as the page and tools/birdlog.py):
 * [match] is a space-separated hex prefix of the packet (or frame), `??` any byte; [uuid] limits it to one
 * characteristic; raw = read([type], [offset]); with [mask] raw = (raw & mask) >> trailing zeros of mask;
 * value = raw · [scale] + [add].
 */
@Serializable
data class FieldSpec(
    val key: String = "custom",
    val name: String = "",
    val uuid: String = "",
    val match: String = "",
    val offset: Int = 0,
    val type: String = "u8",
    val mask: Long? = null,
    val scale: Double = 1.0,
    val add: Double = 0.0,
)

/** decoders/current.json. [framing] "colon-sum16": fields read whole controller frames, otherwise raw packets. */
@Serializable
data class DecoderSpec(
    val format: String = Decoders.FORMAT,
    val version: Int = 0,
    val updatedAt: String = "",
    val note: String = "",
    val framing: String? = null,
    val json: Boolean = false,
    val fields: List<FieldSpec> = emptyList(),
)

object Decoders {
    const val FORMAT = "birdair-lab-decoder"
    val METRICS = setOf(
        "battery_pct", "voltage", "current", "power", "speed_kmh", "speed_mph", "temp", "mode",
        "odometer_km", "odometer_mi", "error", "custom",
    )
    val TYPE_LEN: Map<String, Int> = mapOf(
        "u8" to 1, "s8" to 1, "u16le" to 2, "s16le" to 2, "u16be" to 2, "s16be" to 2, "u24le" to 3, "u24be" to 3,
        "u32le" to 4, "s32le" to 4, "u32be" to 4,
    ) + (0..7).associate { "bit$it" to 1 }

    private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** A decoder from JSON (decoders/current.json, a log's "decoder"). Unknown keys are ignored, a field that is
     *  not an object or does not parse is dropped (the page does the same). Throws IllegalArgumentException
     *  when the text is not a decoder at all. */
    fun parse(json: String): DecoderSpec {
        val root = try { JSON.parseToJsonElement(json) } catch (e: Exception) { throw IllegalArgumentException("decoder: not JSON", e) }
        if (root !is JsonObject) throw IllegalArgumentException("decoder: not a JSON object")
        val arr = root["fields"] as? JsonArray ?: throw IllegalArgumentException("decoder: no fields list")
        val fields = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            try { JSON.decodeFromJsonElement(FieldSpec.serializer(), cleanField(o)) } catch (e: Exception) { null }
        }
        val top = try {
            JSON.decodeFromJsonElement(DecoderSpec.serializer(), JsonObject(root - "fields"))
        } catch (e: Exception) { throw IllegalArgumentException("decoder: " + e.message, e) }
        return top.copy(fields = fields)
    }

    fun parseOrNull(json: String): DecoderSpec? = try { parse(json) } catch (e: IllegalArgumentException) { null }

    /** The page's decoderToJson() plus format/version/updatedAt/note: defaults and empty strings left out. */
    fun toJson(d: DecoderSpec): String = toJsonObject(d, full = true).toString()

    /** The "decoder" object of a log: {json, framing?, fields: [{key, name?, uuid?, match?, offset, type, mask?, scale, add?}]}. */
    fun toJsonObject(d: DecoderSpec, full: Boolean = false): JsonObject = buildJsonObject {
        if (full) {
            put("format", d.format); put("version", d.version)
            if (d.updatedAt.isNotEmpty()) put("updatedAt", d.updatedAt)
            if (d.note.isNotEmpty()) put("note", d.note)
        }
        put("json", d.json)
        if (d.framing == Frames.FRAMING) put("framing", d.framing)
        put("fields", buildJsonArray {
            for (f in d.fields) add(buildJsonObject {
                put("key", f.key)
                if (f.name.isNotEmpty()) put("name", f.name)
                if (f.uuid.isNotEmpty()) put("uuid", f.uuid)
                if (f.match.isNotEmpty()) put("match", f.match)
                put("offset", f.offset)
                put("type", f.type)
                if (f.mask != null && f.mask > 0) put("mask", f.mask)
                put("scale", num(f.scale))
                if (f.add != 0.0) put("add", num(f.add))
            })
        })
    }

    private fun num(d: Double): JsonPrimitive = if (d == Math.rint(d) && Math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    /** Hand-edited values the page accepts: offset "9", scale "0,1", mask "0x03" / "0b11" / "3". */
    private fun cleanField(o: JsonObject): JsonObject {
        val m = o.toMutableMap()
        (m["offset"] as? JsonPrimitive)?.let { p -> m["offset"] = p.contentOrNull?.trim()?.toDoubleOrNull()?.let { JsonPrimitive(it.toInt()) } ?: JsonNull }
        for (k in listOf("scale", "add")) (m[k] as? JsonPrimitive)?.let { p ->
            m[k] = p.contentOrNull?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonNull
        }
        (m["mask"] as? JsonPrimitive)?.let { p -> m["mask"] = parseMask(p.contentOrNull)?.let { JsonPrimitive(it) } ?: JsonNull }
        return JsonObject(m)
    }

    /** 3, "0x03", "0b11" -> the mask; null when empty or not a mask in 1..0xFFFFFFFF (page: parseMask). */
    fun parseMask(v: String?): Long? {
        val s = (v ?: "").trim().lowercase().replace(Regex("[\\s_]"), "")
        if (s.isEmpty()) return null
        val n: Long? = when {
            Regex("0x[0-9a-f]{1,8}").matches(s) -> s.substring(2).toLong(16)
            Regex("0b[01]{1,32}").matches(s) -> s.substring(2).toLong(2)
            else -> s.toDoubleOrNull()?.takeIf { it == Math.rint(it) && it < 1e11 }?.toLong()
        }
        return n?.takeIf { it in 1..0xFFFFFFFFL }
    }

    /** "5A A5 ?? 25" -> [0x5A, 0xA5, -1, 0x25] (-1: any byte); "" -> []; invalid -> null (page: parsePattern). */
    fun parsePattern(s: String?): IntArray? {
        val out = ArrayList<Int>()
        for (tok in (s ?: "").trim().split(Regex("[\\s,]+"))) {
            if (tok.isEmpty()) continue
            if (tok == "??" || tok == "?" || tok == "**" || tok == "xx" || tok == "XX") { out.add(-1); continue }
            val m = if (tok.length > 2 && (tok.startsWith("0x") || tok.startsWith("0X"))) tok.substring(2) else tok
            if (m.isEmpty() || m.length > 2 || !m.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            out.add(m.toInt(16))
        }
        return out.toIntArray()
    }

    fun matchPrefix(b: ByteArray, pat: IntArray): Boolean {
        if (pat.isEmpty()) return true
        if (b.size < pat.size) return false
        for (i in pat.indices) if (pat[i] >= 0 && (b[i].toInt() and 0xFF) != pat[i]) return false
        return true
    }

    /** (raw & mask) shifted down by the mask's trailing zero bits: mask 0x0C turns bits 2–3 into 0…3. */
    fun applyMask(raw: Long, mask: Long): Long = (raw and mask) ushr java.lang.Long.numberOfTrailingZeros(mask)
}

/** Number of [type] at [off] in [b], or null when it does not fit or the type is unknown (page: readNum). */
fun readNum(b: ByteArray, off: Int, type: String): Long? {
    val need = Decoders.TYPE_LEN[type] ?: return null
    if (off < 0 || off + need > b.size) return null
    fun u(i: Int) = (b[off + i].toInt() and 0xFF).toLong()
    return when (type) {
        "u8" -> u(0)
        "s8" -> b[off].toLong()
        "u16le" -> u(0) or (u(1) shl 8)
        "s16le" -> (u(0) or (u(1) shl 8)).toShort().toLong()
        "u16be" -> (u(0) shl 8) or u(1)
        "s16be" -> ((u(0) shl 8) or u(1)).toShort().toLong()
        "u24le" -> u(0) or (u(1) shl 8) or (u(2) shl 16)
        "u24be" -> (u(0) shl 16) or (u(1) shl 8) or u(2)
        "u32le" -> u(0) or (u(1) shl 8) or (u(2) shl 16) or (u(3) shl 24)
        "s32le" -> (u(0) or (u(1) shl 8) or (u(2) shl 16) or (u(3) shl 24)).toInt().toLong()
        "u32be" -> (u(0) shl 24) or (u(1) shl 16) or (u(2) shl 8) or u(3)
        else -> (u(0) shr type.substring(3).toInt()) and 1   // bit0..bit7
    }
}

/** A compiled [DecoderSpec]: the page's applyFields() for one packet or frame. Immutable, thread-safe. */
class Decoder(val spec: DecoderSpec) {
    private class Compiled(val f: FieldSpec, val uuid: String, val pat: IntArray)

    /** Fields read whole controller frames (assembled per characteristic) instead of raw packets. */
    val framed: Boolean = spec.framing == Frames.FRAMING

    /** Fields as the page normalizes them: unknown key -> custom, uuid full or "", offset ≥ 0, unknown type -> u8,
     *  a mask that is not a mask dropped. Bad match patterns stay in the list but never match. */
    val fields: List<FieldSpec>
    private val compiled: List<Compiled>

    init {
        val fs = ArrayList<FieldSpec>()
        val cs = ArrayList<Compiled>()
        for (f in spec.fields) {
            val n = f.copy(
                key = if (f.key in Decoders.METRICS) f.key else "custom",
                uuid = if (f.uuid.isNotBlank()) Uuids.norm(f.uuid) ?: "" else "",
                offset = maxOf(0, f.offset),
                type = if (f.type in Decoders.TYPE_LEN) f.type else "u8",
                mask = f.mask?.takeIf { it in 1..0xFFFFFFFFL },
                scale = if (f.scale.isFinite()) f.scale else 1.0,
                add = if (f.add.isFinite()) f.add else 0.0,
            )
            fs.add(n)
            val pat = Decoders.parsePattern(n.match) ?: continue
            cs.add(Compiled(n, n.uuid, pat))
        }
        fields = fs
        compiled = cs
    }

    /** Values of the fields that fit [bytes] from characteristic [uuid] (any form; normalized to the full UUID). */
    fun decode(bytes: ByteArray, uuid: String): List<Pair<FieldSpec, Double>> {
        val u = Uuids.full(uuid)
        var out: ArrayList<Pair<FieldSpec, Double>>? = null
        for (c in compiled) {
            if (c.uuid.isNotEmpty() && c.uuid != u) continue
            if (!Decoders.matchPrefix(bytes, c.pat)) continue
            var raw = readNum(bytes, c.f.offset, c.f.type) ?: continue
            if (c.f.mask != null) raw = Decoders.applyMask(raw, c.f.mask)
            val v = raw * c.f.scale + c.f.add
            (out ?: ArrayList<Pair<FieldSpec, Double>>().also { out = it }).add(c.f to v)
        }
        return out ?: emptyList()
    }

    companion object {
        val EMPTY = Decoder(DecoderSpec())
    }
}
