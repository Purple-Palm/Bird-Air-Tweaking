package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Decoders
import io.github.purplepalm.birdair.core.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.zip.GZIPInputStream

/** A notification of a real log. */
class Rx(val t: Long, val s: String, val u: String, val b: ByteArray)

object TestLogs {
    val root = File(System.getProperty("repoRoot") ?: "../..")
    /** The solo FLY ride of 14.10 (8,5 km, 85 706 notifications). */
    const val RIDE = "logs/20261014-185422-log.json.gz"

    fun decoder(): Decoder = Decoder(Decoders.parse(File(root, "decoders/current.json").readText()))

    /** rx entries in time order (stable), like birdlog.load_file. */
    fun rx(path: String, limit: Int = Int.MAX_VALUE): List<Rx> {
        val raw = File(root, path).readBytes()
        val text = if (raw[0] == 0x1f.toByte()) GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8) else raw.toString(Charsets.UTF_8)
        val o = Json.parseToJsonElement(text).jsonObject
        val out = ArrayList<Rx>()
        for (el in o["entries"]!!.jsonArray) {
            val e = el as? JsonObject ?: continue
            if (e["k"]?.jsonPrimitive?.contentOrNull != "rx") continue
            val t = e["t"]?.jsonPrimitive?.longOrNull ?: continue
            val h = Hex.parse(e["h"]?.jsonPrimitive?.contentOrNull ?: continue) ?: continue
            out.add(Rx(t, e["s"]?.jsonPrimitive?.contentOrNull ?: "", e["u"]?.jsonPrimitive?.contentOrNull ?: "", h))
        }
        out.sortBy { it.t }
        return if (out.size > limit) out.subList(0, limit) else out
    }
}
