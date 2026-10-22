package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Decoders
import io.github.purplepalm.birdair.core.Hex
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.LogRecorder
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.dial.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assume
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.max

/**
 * The repo's logs through the phone's pipeline (TelemetryTracker with decoders/current.json, as the core tests feed
 * them: rx notifications, a new stream after each «Найдено сервисов»), as the phone sends them to the watch: a
 * snapshot for each notification that moved [Telemetry.seq] (fresh), at its log time, and one a second while none
 * does (the heartbeat: same seq, lamps and «streaming» as of then).
 */
object RideReplay {
    val root: File = File(System.getProperty("repoRoot") ?: File("../..").absolutePath)

    /** The FLY rides (with TAKEOFF stretches on 14.10, 16.10 and 20.10). */
    val FLY = listOf(
        "logs/20261014-185422-log.json.gz",
        "logs/20261015-155523-log-app.json.gz",
        "logs/20261016-135138-log-app.json.gz",
        "logs/20261016-190042-log-app.json.gz",
        "logs/20261019-174739-log-app.json.gz",
        "logs/20261019-210801-log-app.json.gz",
        "logs/20261020-214221-do-otsechki-do-nulya-app.json.gz",
    )
    const val TO_ZERO = "logs/20261020-214221-do-otsechki-do-nulya-app.json.gz"

    private class Entry(val t: Long, val k: String, val s: String?, val u: String?, val b: ByteArray?, val n: String?)

    fun assume(path: String) = Assume.assumeTrue("$path not here", File(root, path).isFile && File(root, "decoders/current.json").isFile)

    private fun entries(path: String): List<Entry> {
        val raw = File(root, path).readBytes()
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
        val o = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        return o["entries"]!!.jsonArray.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val t = e["t"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: return@mapNotNull null
            Entry(
                t, e["k"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null, e["s"]?.jsonPrimitive?.contentOrNull,
                e["u"]?.jsonPrimitive?.contentOrNull, e["h"]?.jsonPrimitive?.contentOrNull?.let { Hex.parse(it) }, e["n"]?.jsonPrimitive?.contentOrNull,
            )
        }.sortedBy { it.t }
    }

    /** Every message of [path] to the watch, in order; [each] gets whether it is fresh (its seq moved). */
    fun snapshots(path: String, each: (Telemetry, Boolean) -> Unit) {
        val tr = TelemetryTracker(Decoder(Decoders.parse(File(root, "decoders/current.json").readText())))
        tr.link = Link.CONNECTED
        var seq = -1L
        var sentAt: Long? = null
        for (e in entries(path)) {
            // the heartbeats before this entry
            var at = sentAt
            while (at != null && e.t - at > HEARTBEAT_MS) {
                at += HEARTBEAT_MS
                each(tr.snapshot(at), false)
            }
            sentAt = at
            if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) tr.restartStreams()
            if (e.k != "rx" || e.b == null || e.s == null || e.u == null) continue
            tr.onNotification(e.s, e.u, e.b, e.t)
            val s = tr.snapshot(e.t)
            if (s.seq != seq) { seq = s.seq; sentAt = e.t; each(s, true) }
        }
    }

    /** The phone sends at least this often while its service runs. */
    const val HEARTBEAT_MS = 1_000L
}

/**
 * The watch's speed cues over a replay, as [RideService] runs them: each message received at once, the fresh ones
 * moving [Feed.freshAtMs]; the service's stale check [OverspeedAlarm.FRESH_MS] after the last fresh one while the
 * rumble runs. Counts the taps (time, mode) and the rumble's episodes and seconds.
 */
class CueRun(val prefs: WatchPrefs = WatchPrefs()) {
    /** One rumble: from [startMs] to [endMs], in [mode] (as it started), its top speed. */
    data class Episode(val startMs: Long, val endMs: Long, val mode: Int?, val topKmh: Double)

    val cues = SpeedCues()
    val taps = ArrayList<Pair<Long, Int?>>()
    val rumbles = ArrayList<Episode>()
    val onMs: Long get() = rumbles.sumOf { it.endMs - it.startMs }
    private var onAt: Long? = null
    private var onMode: Int? = null
    private var top = 0.0
    private var freshAt: Long? = null
    private var last: Telemetry? = null

    fun sample(t: Telemetry, fresh: Boolean = true) {
        val f = freshAt
        val l = last
        if (cues.rumble.on && f != null && l != null && t.t - f > OverspeedAlarm.FRESH_MS) step(l, f, f + OverspeedAlarm.FRESH_MS + 1, false)
        if (fresh) freshAt = t.t
        last = t
        step(t, freshAt, t.t, Health.of(t, t.t, t.t).live && OverspeedAlarm.fresh(freshAt, t.t))
    }

    fun end(): CueRun {
        val l = last ?: return this
        step(l, freshAt, l.t + OverspeedAlarm.FRESH_MS + 1, false)
        return this
    }

    private fun step(t: Telemetry, sampleAt: Long?, now: Long, live: Boolean) {
        val o = cues.update(prefs, t, live, sampleAt, now)
        if (o.tap) taps += now to t.mode
        val on = cues.rumble.on
        val at = onAt
        if (on && at == null) { onAt = now; onMode = t.mode; top = 0.0 }
        if (on) top = max(top, t.speedKmh ?: 0.0)
        if (!on && at != null) { rumbles += Episode(at, now, onMode, top); onAt = null }
    }
}

/** v107's rumble for the comparison: on above 16 mph at once (no confirm), off below 15.5, every mode alike. */
class OldRumble {
    var episodes = 0
        private set
    var onMs = 0L
        private set
    private var onAt: Long? = null
    private var freshAt: Long? = null

    fun sample(t: Telemetry, fresh: Boolean = true) {
        val f = freshAt
        if (onAt != null && f != null && t.t - f > OverspeedAlarm.FRESH_MS) off(f + OverspeedAlarm.FRESH_MS + 1)
        if (fresh) freshAt = t.t
        val mph = t.speedKmh?.div(Fmt.MILE)
        val want = Health.of(t, t.t, t.t).live && OverspeedAlarm.fresh(freshAt, t.t) && mph != null &&
            (mph > SpeedPref.OLD_RUMBLE_MPH || (onAt != null && mph >= SpeedPref.OLD_RUMBLE_MPH - 0.5))
        if (want && onAt == null) { onAt = t.t; episodes++ }
        if (!want) off(t.t)
    }

    fun end(): OldRumble { freshAt?.let { off(it + OverspeedAlarm.FRESH_MS + 1) }; return this }

    private fun off(now: Long) { onAt?.let { onMs += now - it }; onAt = null }
}
