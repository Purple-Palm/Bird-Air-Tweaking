package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FramesTest {
    private fun feedAll(chunks: List<ByteArray>, cap: Int = Frames.CAP): Pair<FrameAssembler, List<Frame>> {
        val a = FrameAssembler(cap)
        val out = chunks.flatMap { a.feedFrames(it) }
        return a to out
    }

    private fun List<Frame>.hexes() = map { Hex.of(it.bytes) }

    @Test fun formatHelpers() {
        assertTrue(Frames.isValid(MODE_FRAME))
        assertTrue(Frames.isValid(VOLT_FRAME))
        assertTrue(Frames.make(0x17, 0x00, MODE_FRAME.copyOfRange(4, 17)).contentEquals(MODE_FRAME))
        assertEquals("3A 18 20 10", Frames.head(VOLT_FRAME))
        val badSum = MODE_FRAME.copyOf().also { it[9] = (it[9].toInt() xor 1).toByte() }
        for (f in listOf(badSum, MODE_FRAME.copyOf(MODE_FRAME.size - 1), MODE_FRAME.copyOfRange(1, MODE_FRAME.size), MODE_FRAME + byteArrayOf(0)))
            assertFalse(Frames.isValid(f), Hex.of(f))
    }

    @Test fun splitAcrossChunks() {
        val (a, out) = feedAll(listOf(VOLT_FRAME.copyOf(20), VOLT_FRAME.copyOfRange(20, VOLT_FRAME.size), MODE_FRAME.copyOf(20), MODE_FRAME.copyOfRange(20, MODE_FRAME.size)))
        assertEquals(listOf(VOLT_FRAME, MODE_FRAME).map(Hex::of), out.hexes())
        assertEquals(FrameStats(2, 0, 0, 0, 0), a.stats)
    }

    @Test fun byteByByte() {
        val data = VOLT_FRAME + MODE_FRAME + MODE_FRAME + MODE_FRAME
        val (_, out) = feedAll(data.map { byteArrayOf(it) })
        assertEquals(listOf(VOLT_FRAME, MODE_FRAME, MODE_FRAME, MODE_FRAME).map(Hex::of), out.hexes())
    }

    @Test fun junkTextBetweenFrames() {
        val stream = ascii("+MODE=3") + MODE_FRAME + ascii("+SVGT=0") + VOLT_FRAME + ascii("OK OK OK ") + MODE_FRAME + ascii("\r\n+HLGT=1\r\n") + VOLT_FRAME
        val (a, out) = feedAll(stream.toList().chunked(20).map { it.toByteArray() })
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME, MODE_FRAME, VOLT_FRAME).map(Hex::of), out.hexes())
        assertTrue(out.none { it.recovered })
        assertEquals(stream.size - 2 * (MODE_FRAME.size + VOLT_FRAME.size), a.stats.junkBytes)
    }

    @Test fun textAfterCrlfDoesNotDelayTheNextFrame() {
        val a = FrameAssembler()
        assertEquals(1, a.feed(MODE_FRAME).size)
        assertEquals(0, a.feed(ascii("+MODE=2")).size)   // looks like a header asking for 0x4F bytes
        assertEquals(listOf(Hex.of(VOLT_FRAME)), a.feed(VOLT_FRAME).map(Hex::of))
    }

    @Test fun missingColonAfterCrlfIsRecovered() {
        val (a, out) = feedAll(listOf(MODE_FRAME, VOLT_FRAME.copyOfRange(1, 21), VOLT_FRAME.copyOfRange(21, VOLT_FRAME.size), MODE_FRAME.copyOfRange(1, MODE_FRAME.size)))
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME, MODE_FRAME).map(Hex::of), out.hexes())
        assertEquals(listOf(false, true, true), out.map { it.recovered })
        assertEquals(2, a.stats.recovered)
        // at the stream start too
        assertEquals(listOf(true), feedAll(listOf(MODE_FRAME.copyOfRange(1, MODE_FRAME.size))).second.map { it.recovered })
    }

    @Test fun missingColonLookalikeNotAfterCrlfIsRejected() {
        val (_, out) = feedAll(listOf(ascii("+X"), MODE_FRAME.copyOfRange(1, MODE_FRAME.size), MODE_FRAME))
        assertEquals(listOf(Hex.of(MODE_FRAME)), out.hexes())
        assertEquals(listOf(false), out.map { it.recovered })
        val (a, out2) = feedAll(listOf(MODE_FRAME, byteArrayOf(0) + MODE_FRAME.copyOfRange(1, MODE_FRAME.size), VOLT_FRAME))
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME).map(Hex::of), out2.hexes())
        assertEquals(0, a.stats.recovered)
        assertEquals(MODE_FRAME.size, a.stats.junkBytes)
    }

    @Test fun badChecksumIsRejectedAndCounted() {
        val bad = VOLT_FRAME.copyOf().also { it[11] = (it[11].toInt() xor 0x10).toByte() }
        val (a, out) = feedAll(listOf(MODE_FRAME, bad, MODE_FRAME))
        assertEquals(2, out.size)
        assertEquals(1, a.stats.dropped)
        assertEquals(1, a.stats.badSum)
        val (b, out2) = feedAll(listOf(MODE_FRAME, bad.copyOfRange(1, bad.size), MODE_FRAME))
        assertEquals(2 to 0, out2.size to b.stats.recovered)
        // a wrong trailer is dropped, but it is not a checksum error
        val (c, out3) = feedAll(listOf(MODE_FRAME.copyOf(MODE_FRAME.size - 1) + byteArrayOf(0), MODE_FRAME))
        assertEquals(listOf(Hex.of(MODE_FRAME)), out3.hexes())
        assertEquals(1 to 0, c.stats.dropped to c.stats.badSum)
    }

    @Test fun crlfAtAChunkEndStillLetsALostColonFrameIn() {
        val bad = VOLT_FRAME.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val stream = bad + VOLT_FRAME.copyOfRange(1, VOLT_FRAME.size)
        for (i in 1 until stream.size) {
            val (_, out) = feedAll(listOf(stream.copyOf(i), stream.copyOfRange(i, stream.size)))
            assertEquals(listOf(Hex.of(VOLT_FRAME) to true), out.map { Hex.of(it.bytes) to it.recovered }, "split at $i")
        }
        val (_, out) = feedAll(listOf(MODE_FRAME + ascii("+SVGT=0\r\n") + VOLT_FRAME.copyOfRange(1, VOLT_FRAME.size) + MODE_FRAME))
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME, MODE_FRAME).map(Hex::of), out.hexes())
    }

    @Test fun cutFrameResyncs() {
        val (_, out) = feedAll(listOf(VOLT_FRAME.copyOf(20), MODE_FRAME, VOLT_FRAME))
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME).map(Hex::of), out.hexes())
    }

    @Test fun resetStartsANewStream() {
        val a = FrameAssembler()
        a.feed(VOLT_FRAME.copyOf(20))
        a.reset()
        val out = a.feedFrames(MODE_FRAME.copyOfRange(1, MODE_FRAME.size))
        assertEquals(listOf(Hex.of(MODE_FRAME) to true), out.map { Hex.of(it.bytes) to it.recovered })
        assertEquals(20, a.stats.junkBytes)
    }

    @Test fun bufferStaysUnderCap() {
        val rnd = Random(7)
        val junk = ByteArray(6000) { rnd.nextInt(256).toByte() }
        for (i in junk.indices step 97) {
            val head = if (i % 2 == 1) byteArrayOf(0x3A, 0x01, 0x02, 0xFF.toByte()) else byteArrayOf(0x0D, 0x0A, 0x01, 0xFE.toByte())
            head.copyInto(junk, i, 0, minOf(4, junk.size - i))
        }
        val a = FrameAssembler()
        for (i in junk.indices step 20) {
            a.feed(junk.copyOfRange(i, minOf(junk.size, i + 20)))
            assertTrue(a.buffered <= Frames.CAP, "buffer ${a.buffered}")
        }
        val got = a.feed(ascii("\r\n") + MODE_FRAME + VOLT_FRAME).map(Hex::of)
        assertEquals(listOf(MODE_FRAME, VOLT_FRAME).map(Hex::of), got.takeLast(2))
    }

    @Test fun smallCapRejectsLongFrames() {
        val long = Frames.make(0x18, 0x20, ByteArray(40) { it.toByte() })
        val short = Frames.make(0x17, 0x00, ByteArray(10))
        val (a, out) = feedAll(listOf(long, short), cap = 32)
        assertEquals(listOf(Hex.of(short)), out.hexes())
        assertTrue(a.buffered < 32)
        assertEquals(listOf(long, short).map(Hex::of), feedAll(listOf(long, short)).second.hexes())
    }

    /** logs/20261014-174148-log.json: standing still, +MODE / +HLGT / +SVGT / KEY? from the page. */
    @Test fun standstillLog() {
        val log = Repo.log(Repo.STANDSTILL)
        val a = FrameAssembler()
        val frames = ArrayList<Frame>()
        var rx = 0
        for (e in log.entries) {
            if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) a.reset()
            if (e.k == "rx" && e.u == Uuids.CHAR && e.b != null) { frames += a.feedFrames(e.b); rx += e.b.size }
        }
        val st = a.stats
        assertEquals(284, st.frames)
        assertEquals(16, st.recovered)
        assertEquals(0, st.dropped)
        assertEquals(0, st.badSum)
        assertTrue(frames.all { Frames.isValid(it.bytes) })
        assertEquals(setOf("3A 17 00 0D", "3A 18 20 10"), frames.map { Frames.head(it.bytes) }.toSet())
        // no byte lost or doubled
        a.reset()
        assertEquals(rx, frames.sumOf { it.bytes.size - if (it.recovered) 1 else 0 } + a.stats.junkBytes)
    }

    // ---------------------------------------------------------------- differential against tools/uart_frames.py

    private val pyCases = """
        import json, sys
        sys.path.insert(0, sys.argv[1])
        import birdlog as bl, uart_frames as uf
        out = []
        for case in json.load(sys.stdin):
            asm = uf.Assembler(cap=case['cap'], keep_texts=False)
            per = []
            for c in case['chunks']:
                if c is None:
                    asm.restart()
                    per.append(None)
                    continue
                per.append([[bl.hex_of(f.frame), f.recovered] for f in asm.feed(bytes.fromhex(c))])
            out.append({'chunks': per, 'frames': asm.n_frames, 'recovered': asm.recovered,
                        'dropped': asm.dropped, 'junk': asm.junk, 'buf': len(asm.buf)})
        json.dump(out, sys.stdout)
    """.trimIndent()

    private fun randomFrame(rnd: Random): ByteArray {
        val addr = listOf(0x17, 0x18, 0x3A, 0x0D, rnd.nextInt(256)).random(rnd)
        val cmd = listOf(0x00, 0x20, 0x0A, rnd.nextInt(256)).random(rnd)
        val n = when (rnd.nextInt(10)) { 0 -> rnd.nextInt(0, 256); 1, 2 -> rnd.nextInt(20, 60); else -> rnd.nextInt(0, 20) }
        val data = ByteArray(n) { listOf(0x3A, 0x0D, 0x0A, 0x00, rnd.nextInt(256), rnd.nextInt(256)).random(rnd).toByte() }
        return Frames.make(addr, cmd, data)
    }

    private fun randomStream(rnd: Random): ByteArray {
        val s = ByteArrayOutputStream()
        val texts = listOf("+MODE=3", "+SVGT=0", "OK OK OK ", "+HLGT=1\r\n", "\r\n", "AT+SETNAME=abc\r\n", ":", "\r", "\n", "::\r\n")
        repeat(rnd.nextInt(3, 40)) {
            when (rnd.nextInt(14)) {
                0, 1, 2 -> s.write(randomFrame(rnd))
                3, 4 -> randomFrame(rnd).let { s.write(it, 1, it.size - 1) }                 // 3A lost
                5 -> randomFrame(rnd).also { it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }.let { s.write(it) }
                6 -> randomFrame(rnd).let { s.write(it, 0, rnd.nextInt(1, it.size)) }        // cut off
                7 -> s.write(ascii(texts.random(rnd)))
                8 -> s.write(ascii("\r\n"))
                9 -> repeat(rnd.nextInt(1, 12)) { s.write(listOf(0x3A, 0x0D, 0x0A, rnd.nextInt(256)).random(rnd)) }
                10 -> s.write(listOf(MODE_FRAME, VOLT_FRAME).random(rnd))
                11 -> listOf(MODE_FRAME, VOLT_FRAME).random(rnd).let { s.write(it, 1, it.size - 1) }
                12 -> randomFrame(rnd).also { it[it.size - 1 - rnd.nextInt(4)] = rnd.nextInt(256).toByte() }.let { s.write(it) }
                else -> s.write(byteArrayOf(0x3A, rnd.nextInt(256).toByte(), rnd.nextInt(256).toByte(), rnd.nextInt(200, 256).toByte()))
            }
        }
        return s.toByteArray()
    }

    private fun chunks(rnd: Random, b: ByteArray): List<ByteArray?> {
        val out = ArrayList<ByteArray?>()
        // in a third of the streams every CR ends a chunk: CR LF split across notifications
        val crSplit = rnd.nextInt(3) == 0
        var i = 0
        while (i < b.size) {
            if (rnd.nextInt(60) == 0) out.add(null)                 // a reconnection
            if (rnd.nextInt(40) == 0) out.add(ByteArray(0))
            var n = when (rnd.nextInt(5)) { 0 -> 20; 1 -> rnd.nextInt(1, 4); 2 -> rnd.nextInt(30, 120); else -> rnd.nextInt(1, 25) }
            if (crSplit) for (k in i until minOf(b.size, i + n)) if (b[k].toInt() == Frames.CR) { n = k - i + 1; break }
            out.add(b.copyOfRange(i, minOf(b.size, i + n)))
            i += n
        }
        return out
    }

    @Test fun sameDecisionsAsPythonOnRandomStreams() {
        Py.assume()
        val rnd = Random(20261015)
        val cases = List(600) {
            val cap = if (rnd.nextInt(8) == 0) listOf(12, 16, 24, 40, 70).random(rnd) else Frames.CAP
            cap to chunks(rnd, randomStream(rnd))
        }
        val input = buildJsonArray {
            for ((cap, cs) in cases) add(buildJsonObject {
                put("cap", cap)
                put("chunks", buildJsonArray { for (c in cs) add(if (c == null) JsonNull else kotlinx.serialization.json.JsonPrimitive(Hex.of(c))) })
            })
        }.toString()
        val py = Json.parseToJsonElement(Py.run(pyCases, input)).jsonArray
        assertEquals(cases.size, py.size)
        var frames = 0
        var recovered = 0
        var dropped = 0
        for ((ci, case) in cases.withIndex()) {
            val (cap, cs) = case
            val want = py[ci].jsonObject
            val a = FrameAssembler(cap)
            val wantChunks = want["chunks"]!!.jsonArray
            for ((i, c) in cs.withIndex()) {
                if (c == null) { a.reset(); continue }
                val got = a.feedFrames(c).map { Hex.of(it.bytes) to it.recovered }
                val exp = (wantChunks[i] as JsonArray).map { val p = it.jsonArray; p[0].jsonPrimitive.content to p[1].jsonPrimitive.boolean }
                assertEquals(exp, got, "case $ci chunk $i")
            }
            val st = a.stats
            assertEquals(want["frames"]!!.jsonPrimitive.int, st.frames, "case $ci frames")
            assertEquals(want["recovered"]!!.jsonPrimitive.int, st.recovered, "case $ci recovered")
            assertEquals(want["dropped"]!!.jsonPrimitive.int, st.dropped, "case $ci dropped")
            assertEquals(want["junk"]!!.jsonPrimitive.int, st.junkBytes, "case $ci junk")
            assertEquals(want["buf"]!!.jsonPrimitive.int, a.buffered, "case $ci buffer")
            frames += st.frames; recovered += st.recovered; dropped += st.dropped
        }
        // the generator really exercises every path
        assertTrue(frames > 2000 && recovered > 300 && dropped > 300, "frames $frames recovered $recovered dropped $dropped")
    }

    @Test fun sameFramesAsPythonOnTheRideLog() {
        Py.assume()
        val script = """
            import hashlib, json, sys
            sys.path.insert(0, sys.argv[1])
            import birdlog as bl, uart_frames as uf
            asms = uf.assemble_log(bl.load_file(sys.argv[2]))
            a = asms[(bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1'))]
            h = hashlib.sha256()
            for f in a.frames:
                h.update(f.frame + (b'R' if f.recovered else b'S'))
            print(json.dumps({'frames': a.n_frames, 'recovered': a.recovered, 'dropped': a.dropped, 'junk': a.junk, 'sha': h.hexdigest()}))
        """.trimIndent()
        val want = Json.parseToJsonElement(Py.run(script, "", Repo.file(Repo.RIDE).absolutePath)).jsonObject
        val a = FrameAssembler()
        val md = MessageDigest.getInstance("SHA-256")
        for (e in Repo.log(Repo.RIDE).entries) {
            if (e.k == "sys" && e.n?.startsWith(LogRecorder.SYS_FOUND) == true) a.reset()
            if (e.k != "rx" || e.u != Uuids.CHAR || e.b == null) continue
            for (f in a.feedFrames(e.b)) { md.update(f.bytes); md.update(if (f.recovered) 'R'.code.toByte() else 'S'.code.toByte()) }
        }
        a.reset()
        val st = a.stats
        assertEquals(want["frames"]!!.jsonPrimitive.int, st.frames)
        assertEquals(want["recovered"]!!.jsonPrimitive.int, st.recovered)
        assertEquals(want["dropped"]!!.jsonPrimitive.int, st.dropped)
        assertEquals(want["junk"]!!.jsonPrimitive.int, st.junkBytes)
        assertEquals(want["sha"]!!.jsonPrimitive.content, md.digest().joinToString("") { "%02x".format(it) })
        assertTrue(st.frames > 40000)
    }
}
