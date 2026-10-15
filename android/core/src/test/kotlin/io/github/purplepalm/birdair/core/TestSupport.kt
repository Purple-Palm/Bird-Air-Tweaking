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
