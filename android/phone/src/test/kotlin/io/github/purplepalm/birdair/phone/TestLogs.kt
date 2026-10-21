package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Decoders
import io.github.purplepalm.birdair.core.Hex
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.LogRecorder
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.zip.GZIPInputStream

/** A notification of a real log; [found]: instead the log's «Найдено сервисов» line (a new connection, no bytes). */
class Rx(val t: Long, val s: String, val u: String, val b: ByteArray, val found: Boolean = false)

object TestLogs {
    val root = File(System.getProperty("repoRoot") ?: "../..")
    /** The solo FLY ride of 14.10 (8,5 km, 85 706 notifications). */
    const val RIDE = "logs/20261014-185422-log.json.gz"
    /** Solo FLY 100 % → the BMS cut the pack off at 1812.4 s (b5 0x02 from 1810.0 s, then controller code 0x26), the
     *  dash rebooted twice with the BMS silent (2117–2143 s, 2145–2157 s) and once more for a TAKEOFF ride home. */
    const val TO_ZERO = "logs/20261020-214221-do-otsechki-do-nulya-app.json.gz"

    fun decoder(): Decoder = Decoder(Decoders.parse(File(root, "decoders/current.json").readText()))

    /** rx entries in time order (stable), like birdlog.load_file; with [found] also the new connections. */
    fun rx(path: String, limit: Int = Int.MAX_VALUE, found: Boolean = false): List<Rx> {
        val raw = File(root, path).readBytes()
        val text = if (raw[0] == 0x1f.toByte()) GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8) else raw.toString(Charsets.UTF_8)
        val o = Json.parseToJsonElement(text).jsonObject
        val out = ArrayList<Rx>()
        for (el in o["entries"]!!.jsonArray) {
            val e = el as? JsonObject ?: continue
            val k = e["k"]?.jsonPrimitive?.contentOrNull
            val t = e["t"]?.jsonPrimitive?.longOrNull ?: continue
            if (found && k == "sys" && e["n"]?.jsonPrimitive?.contentOrNull?.startsWith(LogRecorder.SYS_FOUND) == true) {
                out.add(Rx(t, "", "", ByteArray(0), found = true)); continue
            }
            if (k != "rx") continue
            val h = Hex.parse(e["h"]?.jsonPrimitive?.contentOrNull ?: continue) ?: continue
            out.add(Rx(t, e["s"]?.jsonPrimitive?.contentOrNull ?: "", e["u"]?.jsonPrimitive?.contentOrNull ?: "", h))
        }
        out.sortBy { it.t }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    /**
     * [path] through the phone's pipeline as the app runs it: link CONNECTED, a publish after every notification and
     * every 250 ms without one, a new connection at each «Найдено сервисов» (the tracker's restartStreams, then
     * [restart]); [decorate] as ScooterRepo sets it. [onPublish] gets every published snapshot.
     */
    fun publish(path: String, decorate: ((Telemetry, Boolean) -> Telemetry)? = null, restart: () -> Unit = {}, onPublish: (Telemetry) -> Unit) {
        val tracker = TelemetryTracker(decoder()).apply { link = Link.CONNECTED }
        val es = rx(path, found = true)
        var now = es.first().t
        val p = LivePipeline(tracker, { now }, { true })
        p.decorate = decorate
        for (e in es) {
            while (now + 250 < e.t) { now += 250; onPublish(p.publishNow()) }
            now = e.t
            if (e.found) { tracker.restartStreams(); restart(); continue }
            p.onRx(e.s, e.u, e.b, e.t)
            onPublish(p.publishNow())
        }
    }
}
