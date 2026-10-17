package io.github.purplepalm.birdair.core

/**
 * Controller frames in the FFE1 stream ("framing": "colon-sum16"):
 *
 *     3A ':' | addr u8 | cmd u8 | len L u8 | data[L] | sum u16 LE | 0D 0A
 *     sum = (addr + cmd + L + Σdata) & 0xFFFF
 *
 * Offsets count from the 3A (byte 0): header 0–3, data 4…4+L−1, sum 4+L and 5+L.
 */
object Frames {
    const val FRAMING = "colon-sum16"
    const val COLON = 0x3A
    const val CR = 0x0D
    const val LF = 0x0A
    const val HEAD = 4
    const val TAIL = 4
    /** Longest frame accepted, and so about the most an assembler buffers. */
    const val CAP = 300

    fun checksum(addr: Int, cmd: Int, data: ByteArray): Int {
        var s = addr + cmd + data.size
        for (x in data) s += x.toInt() and 0xFF
        return s and 0xFFFF
    }

    /** A valid frame: 3A addr cmd len data sum16le 0D 0A. */
    fun make(addr: Int, cmd: Int, data: ByteArray): ByteArray {
        require(data.size <= 255) { "data longer than 255 bytes" }
        val s = checksum(addr, cmd, data)
        val out = ByteArray(HEAD + data.size + TAIL)
        out[0] = COLON.toByte(); out[1] = addr.toByte(); out[2] = cmd.toByte(); out[3] = data.size.toByte()
        data.copyInto(out, HEAD)
        val e = HEAD + data.size
        out[e] = s.toByte(); out[e + 1] = (s shr 8).toByte(); out[e + 2] = CR.toByte(); out[e + 3] = LF.toByte()
        return out
    }

    /** Is [f] (starting with 3A) exactly one frame with the right length, sum and CR LF? */
    fun isValid(f: ByteArray): Boolean {
        if (f.size < HEAD + TAIL || f[0].toInt() != COLON) return false
        val n = f[3].toInt() and 0xFF
        if (f.size != HEAD + n + TAIL) return false
        val e = HEAD + n
        var s = 0
        for (k in 1 until e) s += f[k].toInt() and 0xFF
        return (s and 0xFFFF) == ((f[e].toInt() and 0xFF) or ((f[e + 1].toInt() and 0xFF) shl 8)) &&
            f[e + 2].toInt() == CR && f[e + 3].toInt() == LF
    }

    /** "3A 17 00 0D": the frame's head, its kind. */
    fun head(f: ByteArray): String = Hex.of(f, 0, minOf(HEAD, f.size))

    /** The data bytes of a frame. */
    fun data(f: ByteArray): ByteArray = f.copyOfRange(HEAD, minOf(f.size, HEAD + (f[3].toInt() and 0xFF)))
}

/**
 * Counters of a [FrameAssembler] (or a sum of several).
 * [frames] accepted frames, [recovered] of them those whose 0x3A was lost, [badSum] 3A starts with
 * CR LF in place but a wrong sum (the page's «bad»), [junkBytes] bytes skipped outside frames,
 * [dropped] every 3A start rejected: wrong sum, end or length, or cut off (uart_frames.py `dropped`).
 */
data class FrameStats(
    var frames: Int = 0,
    var recovered: Int = 0,
    var badSum: Int = 0,
    var junkBytes: Int = 0,
    var dropped: Int = 0,
) {
    operator fun plus(o: FrameStats) =
        FrameStats(frames + o.frames, recovered + o.recovered, badSum + o.badSum, junkBytes + o.junkBytes, dropped + o.dropped)
}

/** One assembled frame, normalized to start with 0x3A; [recovered]: its 3A was lost on the way. */
class Frame(val bytes: ByteArray, val recovered: Boolean)

/**
 * Frame assembler for one characteristic. The same accept/reject decisions as tools/uart_frames.py
 * Assembler (and the page's assemble()), byte for byte, whatever the notification boundaries:
 *
 *  - chunks are glued into one stream; a frame starts with 3A and is accepted only when its length,
 *    sum and the closing 0D 0A check out;
 *  - a frame that lost its 3A is accepted only right after CR LF (or at the stream start), with the
 *    same checks; it gets its 3A back, so offsets stay the same;
 *  - anything else (the echo «+MODE=3», «+SVGT=0», «OK OK OK ») is skipped byte by byte; a candidate
 *    still short of bytes is dropped once a whole valid 3A frame already follows it in the buffer;
 *  - a frame longer than [cap] bytes is never accepted, so the buffer stays under [cap] bytes.
 *
 * Not thread-safe: one caller (or lock) per assembler.
 */
class FrameAssembler(val cap: Int = Frames.CAP) {
    private var buf = ByteArray(256)
    private var len = 0
    /** last byte consumed, -1: none since the stream start */
    private var prev = -1
    /** the bytes consumed so far end in CR LF, or nothing consumed yet: a lost 3A may be recovered here */
    private var edge = true
    private val st = FrameStats()

    /** A copy of the counters. */
    val stats: FrameStats get() = st.copy()

    /** Bytes waiting for the rest of a frame. */
    val buffered: Int get() = len

    /** Complete checksum-valid frames finished by [chunk], normalized to start with 0x3A. */
    fun feed(chunk: ByteArray): List<ByteArray> = feedFrames(chunk).map { it.bytes }

    /** Like [feed], with the recovered flag. */
    fun feedFrames(chunk: ByteArray): List<Frame> {
        append(chunk)
        var out: ArrayList<Frame>? = null
        var pos = 0
        while (pos < len) {
            val colon = u(pos) == Frames.COLON
            if (!colon && !edge) { skip(pos); pos++; continue }
            val r = tryAt(pos, if (colon) 1 else 0)
            if (r == NEED) {
                if (!strictLater(pos + 1)) break
                if (colon) st.dropped++
                skip(pos); pos++; continue
            }
            if (r < 0) {
                if (colon) { st.dropped++; if (r == BAD_SUM) st.badSum++ }
                skip(pos); pos++; continue
            }
            val f: ByteArray
            if (colon) f = buf.copyOfRange(pos, pos + r)
            else {
                f = ByteArray(r + 1)
                f[0] = Frames.COLON.toByte()
                buf.copyInto(f, 1, pos, pos + r)
            }
            pos += r
            edge = true
            prev = Frames.LF
            st.frames++
            if (!colon) st.recovered++
            (out ?: ArrayList<Frame>(2).also { out = it }).add(Frame(f, !colon))
        }
        if (pos > 0) {
            buf.copyInto(buf, 0, pos, len)
            len -= pos
        }
        return out ?: emptyList()
    }

    /** A new stream (a new connection): what is buffered counts as junk, the next byte is a stream start again. */
    fun reset() {
        for (i in 0 until len) skip(i)
        len = 0
        prev = -1
        edge = true
    }

    private fun u(i: Int) = buf[i].toInt() and 0xFF

    private fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        if (len + chunk.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + chunk.size))
        chunk.copyInto(buf, len)
        len += chunk.size
    }

    private fun skip(i: Int) {
        val x = u(i)
        edge = prev == Frames.CR && x == Frames.LF
        prev = x
        st.junkBytes++
    }

    /** Frame at buf[i]; its addr byte at buf[i + h] (h = 1 after a 3A, 0 when the 3A was lost).
     *  Its length in the buffer, or NEED (wait for bytes), BAD_SUM (end fine, sum wrong) or BAD. */
    private fun tryAt(i: Int, h: Int): Int {
        if (len - i < h + 3) return NEED
        val n = u(i + h + 2)
        val total = h + 3 + n + Frames.TAIL
        if (1 + 3 + n + Frames.TAIL > cap) return BAD
        if (len - i < total) return NEED
        val end = i + h + 3 + n
        if (u(end + 2) != Frames.CR || u(end + 3) != Frames.LF) return BAD
        var s = 0
        for (k in i + h until end) s += u(k)
        if ((s and 0xFFFF) != (u(end) or (u(end + 1) shl 8))) return BAD_SUM
        return total
    }

    /** Is there a whole valid frame with its 3A at buf[from…]? */
    private fun strictLater(from: Int): Boolean {
        for (k in from until len) if (u(k) == Frames.COLON && tryAt(k, 1) > 0) return true
        return false
    }

    private companion object {
        const val NEED = -1
        const val BAD = -2
        const val BAD_SUM = -3
    }
}
