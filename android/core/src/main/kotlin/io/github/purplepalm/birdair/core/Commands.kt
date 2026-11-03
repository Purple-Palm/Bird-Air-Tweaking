package io.github.purplepalm.birdair.core

/** UUIDs of the Bird Air dash and the ones the apps must never write to. */
object Uuids {
    const val BASE = "-0000-1000-8000-00805f9b34fb"
    /** The dash's transparent UART: service 0xFFE0, characteristic 0xFFE1 (write without response + notify). */
    const val SERVICE = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR = "0000ffe1-0000-1000-8000-00805f9b34fb"
    /** Client Characteristic Configuration descriptor (turns notifications on). */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
    /** Telink OTA (firmware update) of the WLT8266BM module: never written. */
    const val TELINK_OTA_SERVICE = "00010203-0405-0607-0809-0a0b0c0d1912"
    const val TELINK_OTA_CHAR = "00010203-0405-0607-0809-0a0b0c0d2b12"

    private val SHORT16 = Regex("(?:0x)?[0-9a-f]{4}")
    private val SHORT32 = Regex("[0-9a-f]{8}")
    private val LONG = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** "ffe1", "0xFFE1", 32-bit or full form -> full lower-case UUID; anything else -> null (page: normUuid). */
    fun norm(u: String?): String? {
        val s = (u ?: "").trim().lowercase()
        return when {
            s.isEmpty() -> null
            SHORT16.matches(s) -> "0000" + s.removePrefix("0x") + BASE
            SHORT32.matches(s) -> s + BASE
            LONG.matches(s) -> s
            else -> null
        }
    }

    /** Full lower-case UUID; a string that is not a UUID (e.g. "handle-0x0012") comes back trimmed and lower-cased. */
    fun full(u: String): String = norm(u) ?: u.trim().lowercase()

    /** "0xFFE1" for Bluetooth SIG base UUIDs, the full form otherwise. */
    fun short(u: String): String {
        val f = full(u)
        return if (f.startsWith("0000") && f.endsWith(BASE)) "0x" + f.substring(4, 8).uppercase() else f
    }

    /** OTA/DFU: Telink OTA, Nordic DFU (secure 0xFE59 / 8EC9…, legacy 00001530-1212…), Silabs OTA (8EC9…), 0xFEF5.
     *  The page's isDanger() plus the Nordic legacy DFU characteristics. */
    fun isDanger(service: String, char: String): Boolean {
        val s = full(service)
        val c = full(char)
        return c == TELINK_OTA_CHAR || s == TELINK_OTA_SERVICE ||
            s == norm("fe59") || s == norm("fef5") ||
            c.startsWith("8ec9") || s.startsWith("8ec9") || s.startsWith("00001530-1212") ||
            (c.startsWith("0000153") && c.endsWith("-1212-efde-1523-785feabcd123"))
    }
}

/**
 * The only bytes the apps ever write to the scooter, all ASCII, all to [Uuids.CHAR]
 * (docs/bird-air-protocol.md, «Команды»). Getters return copies, so the whitelist cannot be changed.
 */
object Commands {
    /** «Живые данные»: the dash streams ~4.5 s after each command, so a pulse every 2.5 s keeps it going. */
    const val PULSE_MS = 2500L
    /** A pulse that failed to go out is retried this soon. */
    const val FAIL_RETRY_MS = 400L

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private val svgt = ascii("+SVGT=?")
    private val mode1 = ascii("+MODE=1")
    private val mode2 = ascii("+MODE=2")
    private val mode3 = ascii("+MODE=3")
    private val lightOn = ascii("+HLGT=1")
    private val lightOff = ascii("+HLGT=0")
    private val all = listOf(svgt, mode1, mode2, mode3, lightOn, lightOff)
    /** «KEY?» / access code prefix: the firmware wizard; on this scooter it caused error 37. */
    private val key = byteArrayOf(0xAE.toByte(), 0x4B, 0x45, 0x59)

    /** Read-only dash version query, the keep-alive pulse. */
    val SVGT: ByteArray get() = svgt.copyOf()
    /** TAKEOFF, 9 mph. */
    val MODE1: ByteArray get() = mode1.copyOf()
    /** GLIDE, 12.5 mph. */
    val MODE2: ByteArray get() = mode2.copyOf()
    /** FLY, 16 mph. */
    val MODE3: ByteArray get() = mode3.copyOf()
    val LIGHT_ON: ByteArray get() = lightOn.copyOf()
    val LIGHT_OFF: ByteArray get() = lightOff.copyOf()

    /** +MODE=n for n in 1..3, null otherwise. */
    fun mode(n: Int): ByteArray? = when (n) {
        1 -> MODE1
        2 -> MODE2
        3 -> MODE3
        else -> null
    }

    fun light(on: Boolean): ByteArray = if (on) LIGHT_ON else LIGHT_OFF

    /** Exactly one of the six commands, byte for byte. */
    fun isAllowed(b: ByteArray): Boolean = all.any { it.contentEquals(b) }

    /** AE 4B 45 59 anywhere in [b]: the BLE module passes bytes straight into the dash's UART. */
    fun containsKey(b: ByteArray): Boolean {
        outer@ for (i in 0..b.size - key.size) {
            for (k in key.indices) if (b[i + k] != key[k]) continue@outer
            return true
        }
        return false
    }

    /** The last check before any GATT write: FFE0/FFE1 only, not OTA/DFU, one of the six commands, no KEY?. */
    fun canWrite(service: String, char: String, b: ByteArray): Boolean =
        Uuids.full(service) == Uuids.SERVICE && Uuids.full(char) == Uuids.CHAR &&
            !Uuids.isDanger(service, char) && isAllowed(b) && !containsKey(b)

    /** «+MODE=3» for printable ASCII, hex otherwise. */
    fun label(b: ByteArray): String =
        if (b.isNotEmpty() && b.all { it in 32..126 }) "«" + String(b, Charsets.US_ASCII) + "»" else Hex.of(b)
}

/** "3A 17 00 0D" upper-case hex with spaces, as in the logs. */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun of(b: ByteArray, from: Int = 0, to: Int = b.size): String {
        if (to <= from) return ""
        val sb = StringBuilder((to - from) * 3 - 1)
        for (i in from until to) {
            if (i > from) sb.append(' ')
            val x = b[i].toInt() and 0xFF
            sb.append(DIGITS[x shr 4]).append(DIGITS[x and 15])
        }
        return sb.toString()
    }

    /** "3A 17 0x0D" -> bytes; separators ignored; odd digit count -> null (page: hexToBytes). */
    fun parse(s: String?): ByteArray? {
        val clean = StringBuilder()
        val src = (s ?: "").replace(Regex("0x", RegexOption.IGNORE_CASE), "")
        for (c in src) if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') clean.append(c)
        if (clean.length % 2 != 0) return null
        return ByteArray(clean.length / 2) { ((Character.digit(clean[2 * it], 16) shl 4) or Character.digit(clean[2 * it + 1], 16)).toByte() }
    }
}
