package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assume
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** One log entry as the tools see it. */
class LogEntry(val t: Long, val k: String, val s: String?, val u: String?, val b: ByteArray?, val n: String?)

class LoadedLog(val root: JsonObject, val entries: List<LogEntry>) {
    /** rx notifications of FFE1, in order. */
    val ffe1: List<LogEntry> get() = entries.filter { it.k == "rx" && it.u == Uuids.CHAR && it.b != null }
}

object Repo {
    val root: File = File(System.getProperty("repoRoot") ?: File("../..").absolutePath)
    fun file(path: String) = File(root, path)

    val STANDSTILL = "logs/20261014-174148-log.json"
    val RIDE = "logs/20261014-185422-log.json.gz"
    val DECODER = "decoders/current.json"

    fun decoder(): Decoder = Decoder(Decoders.parse(file(DECODER).readText()))

    fun readBytes(path: String): ByteArray {
        val raw = file(path).readBytes()
        return if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) GZIPInputStream(raw.inputStream()).readBytes() else raw
    }

    private val cache = HashMap<String, LoadedLog>()

    /** A log, entries sorted by t like birdlog.load_file (stable). */
    fun log(path: String): LoadedLog = cache.getOrPut(path) { parseLog(String(readBytes(path), Charsets.UTF_8)) }

    fun parseLog(text: String): LoadedLog {
        val root = Json.parseToJsonElement(text).jsonObject
        val es = root["entries"]!!.jsonArray.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val t = o["t"]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() } ?: return@mapNotNull null
            LogEntry(
                t, o["k"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                o["s"]?.jsonPrimitive?.contentOrNull, o["u"]?.jsonPrimitive?.contentOrNull,
                o["h"]?.jsonPrimitive?.contentOrNull?.let { Hex.parse(it) }, o["n"]?.jsonPrimitive?.contentOrNull,
            )
        }.sortedBy { it.t }
        return LoadedLog(root, es)
    }
}

/** python3 for the differential tests; tests that need it are skipped without it. */
object Py {
    val available: Boolean by lazy {
        try {
            val p = ProcessBuilder("python3", "-I", "-c", "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (e: IOException) { false }
    }

    fun assume() = Assume.assumeTrue("python3 not found", available)

    /** Runs [script] with python3 -I; argv[1] is the repo's tools/ folder. Returns stdout; fails on a non-zero exit. */
    fun run(script: String, stdin: String = "", vararg args: String): String {
        val dir = Files.createTempDirectory("birdair-py").toFile()
        try {
            val f = File(dir, "helper.py").apply { writeText(script) }
            val err = File(dir, "stderr.txt")
            val p = ProcessBuilder(listOf("python3", "-I", f.absolutePath, Repo.file("tools").absolutePath) + args)
                .redirectError(err).start()
            p.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)) }
            val out = String(p.inputStream.readBytes(), Charsets.UTF_8)
            check(p.waitFor(120, TimeUnit.SECONDS)) { "python3 timed out" }
            check(p.exitValue() == 0) { "python3 failed: " + err.readText() }
            return out
        } finally { dir.deleteRecursively() }
    }
}

fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
fun hex(s: String) = Hex.parse(s)!!

/** Frames from tests/test_uart_frames.py (the standstill log). */
val MODE_FRAME = hex("3A 17 00 0D 00 00 00 00 00 0B 00 00 00 37 A8 01 01 10 01 0D 0A")
val VOLT_FRAME = hex("3A 18 20 10 03 00 00 18 18 AE 9B D2 FF D7 0C 0B 11 4B 69 00 48 05 0D 0A")

/** A controller frame of a log, in order (tools/bird_frames.py events()); [Reset]: a new connection. */
sealed class ScooterEvent(val t: Long) {
    class Reset(t: Long) : ScooterEvent(t)
    class Bms(t: Long, val f: BmsFrame) : ScooterEvent(t)
    class Motion(t: Long, val f: MotionFrame) : ScooterEvent(t)
}

/**
 * The ride logs of the fast power / battery advisor / app odometer tests (tests/ride_logs.py), their frames parsed
 * once per test JVM, and the same logs through the Python reference modules, run once.
 */
object RideLogs {
    const val SOLO = "logs/20261014-185422-log.json.gz"
    const val CUTOFF_HOT = "logs/20261015-155523-log-app.json.gz"
    const val TWO_UP_HALF = "logs/20261016-135138-log-app.json.gz"
    const val CAR_CHARGE = "logs/20261016-182924-log.json.gz"
    const val TWO_UP_EMPTY = "logs/20261016-190042-log-app.json.gz"
    const val CHARGE = "logs/20261016-205717-log-app.json.gz"
    const val CUTOFF = "logs/20261019-174739-log-app.json.gz"
    /** In startedAt order. */
    val ALL = listOf(SOLO, CUTOFF_HOT, TWO_UP_HALF, CAR_CHARGE, TWO_UP_EMPTY, CHARGE, CUTOFF)
    /** The two real cutoffs (ms of the log): the scooter went dark; traction cut and never came back. */
    val CUTOFF_T = mapOf(CUTOFF_HOT to 1_883_909L, CUTOFF to 5_487_027L)

    class Ride(val startedAtMs: Long, val events: List<ScooterEvent>)

    private val rides = HashMap<String, Ride>()

    /** Not cached as entries (the charging log has 258k of them), only as frames. */
    @Synchronized
    fun ride(path: String): Ride = rides.getOrPut(path) {
        val log = Repo.parseLog(String(Repo.readBytes(path), Charsets.UTF_8))
        val asm = FrameAssembler()
        val out = ArrayList<ScooterEvent>()
        for (e in log.entries) {
            if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) { asm.reset(); out.add(ScooterEvent.Reset(e.t)); continue }
            if (e.k != "rx" || e.b == null || Uuids.full(e.u ?: "") != Uuids.CHAR) continue
            for (f in asm.feed(e.b)) {
                val b = ScooterFrames.bms(f)
                if (b != null) { out.add(ScooterEvent.Bms(e.t, b)); continue }
                ScooterFrames.motion(f)?.let { out.add(ScooterEvent.Motion(e.t, it)) }
            }
        }
        val started = log.root["startedAt"]!!.jsonPrimitive.content
        Ride(java.time.Instant.parse(started).toEpochMilli(), out)
    }

    /** {log: {"fast": [[t, v, i, iFast, pFast]…], "adv": [[t, low, hot, km|null, rangeKm|null, whPerKm|null, basis|null]…],
     *  "odo": the app odometer's total}} from tools/fast_power.py, tools/battery_advisor.py and tools/app_odometer.py. */
    val python: Map<String, JsonObject> by lazy {
        val script = """
            import json, sys
            sys.path.insert(0, sys.argv[1])
            import birdlog as bl, bird_frames as bf, fast_power as fp, battery_advisor as ba, app_odometer as ao
            for path in sys.argv[2:]:
                lg = bl.load_file(path)
                ev = bf.events(lg)
                rows, _ = fp.run(fp.read_log(lg, ev))
                adv = []
                for t, km, ws, rg, x in ba.replay(lg, events=ev):
                    lv = {w.id: (2 if w.level == ba.CRITICAL else 1) for w in ws}
                    adv.append([t, lv.get('battery_low', 0), lv.get('battery_hot', 0), km,
                                None if rg is None else rg.km, None if rg is None else rg.wh_per_km, None if rg is None else rg.basis])
                odo = ao.replay([lg], events=lambda _lg: ev)[0].total_km
                print(json.dumps({'log': path, 'fast': [[r[0], r[1], r[2], r[4], r[5]] for r in rows], 'adv': adv, 'odo': odo}))
        """.trimIndent()
        val out = Py.run(script, "", *ALL.map { Repo.file(it).absolutePath }.toTypedArray())
        out.lines().filter { it.isNotBlank() }.associate { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            val p = o["log"]!!.jsonPrimitive.content
            ALL.single { Repo.file(it).absolutePath == p } to o
        }
    }
}
