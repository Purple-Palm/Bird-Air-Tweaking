package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.Uuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class WriteGuardTest {
    private val svc = Uuids.SERVICE
    private val chr = Uuids.CHAR
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private val key = byteArrayOf(0xAE.toByte(), 0x4B, 0x45, 0x59)

    @Test fun theSixCommandsPassOnFfe1InAnyUuidForm() {
        for (s in listOf("+SVGT=?", "+MODE=1", "+MODE=2", "+MODE=3", "+HLGT=0", "+HLGT=1")) {
            assertNull(s, WriteGuard.check(svc, chr, ascii(s)))
            assertNull(s, WriteGuard.check("ffe0", "0xFFE1", ascii(s)))
            assertNull(s, WriteGuard.check(svc.uppercase(), chr.uppercase(), ascii(s)))
        }
        for (b in listOf(Commands.SVGT, Commands.MODE1, Commands.MODE2, Commands.MODE3, Commands.LIGHT_ON, Commands.LIGHT_OFF)) {
            assertNull(WriteGuard.check(svc, chr, b))
        }
    }

    @Test fun keyIsRefusedWhereverItIs() {
        assertEquals(WriteGuard.KEY, WriteGuard.check(svc, chr, key))
        assertEquals(WriteGuard.KEY, WriteGuard.check(svc, chr, key + byteArrayOf(0x3F, 0xD6.toByte())))   // KEY?
        assertEquals(WriteGuard.KEY, WriteGuard.check(svc, chr, key + byteArrayOf(0x12, 0x34)))            // access code
        assertEquals(WriteGuard.KEY, WriteGuard.check(svc, chr, ascii("+MODE=1") + key))
        assertEquals(WriteGuard.KEY, WriteGuard.checkPayload(byteArrayOf(0x00) + key + byteArrayOf(0x00)))
    }

    @Test fun anythingElseIsRefused() {
        val bad = listOf(
            ByteArray(0), ascii("+MODE=4"), ascii("+MODE=0"), ascii("+HLGT=2"), ascii("+HVGT=?"), ascii("+MODE=1\r\n"),
            ascii(" +MODE=1"), ascii("+mode=1"), ascii("+SVGT=?+SVGT=?"), ascii("AT+RESET"), ascii("AT+SETNAME=x"),
            byteArrayOf(0x3A, 0x17, 0x00, 0x0D),
        )
        for (b in bad) assertEquals(String(b, Charsets.ISO_8859_1), WriteGuard.NOT_LISTED, WriteGuard.check(svc, chr, b))
        for (i in 0..255) assertNotNull(WriteGuard.check(svc, chr, byteArrayOf(i.toByte())))
        val rnd = Random(37)
        repeat(2000) {
            val b = rnd.nextBytes(rnd.nextInt(1, 21))
            if (!Commands.isAllowed(b)) assertNotNull(WriteGuard.check(svc, chr, b))
        }
    }

    @Test fun onlyFfe1OfFfe0() {
        val ok = ascii("+MODE=3")
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check(svc, "ffe2", ok))
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check(svc, "2a00", ok))
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check("180a", chr, ok))
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check(null, chr, ok))
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check(svc, null, ok))
        assertEquals(WriteGuard.NOT_FFE1, WriteGuard.check("handle-0x0012", "handle-0x0013", ok))
    }

    @Test fun otaAndDfuAreNeverWritten() {
        val ok = ascii("+SVGT=?")
        assertEquals(WriteGuard.DANGER, WriteGuard.check(Uuids.TELINK_OTA_SERVICE, Uuids.TELINK_OTA_CHAR, ok))
        assertEquals(WriteGuard.DANGER, WriteGuard.check(svc, Uuids.TELINK_OTA_CHAR, ok))
        assertEquals(WriteGuard.DANGER, WriteGuard.check(Uuids.TELINK_OTA_SERVICE, chr, ok))
        assertEquals(WriteGuard.DANGER, WriteGuard.check("fe59", "8ec90001-f315-4f60-9fb8-838830daea50", ok))
        assertEquals(WriteGuard.DANGER, WriteGuard.check("00001530-1212-efde-1523-785feabcd123", "00001532-1212-efde-1523-785feabcd123", ok))
        // the payload check comes first: KEY? on an OTA characteristic still says KEY?
        assertEquals(WriteGuard.KEY, WriteGuard.check(Uuids.TELINK_OTA_SERVICE, Uuids.TELINK_OTA_CHAR, key))
    }

    @Test fun theWhitelistCannotBeChangedFromOutside() {
        val b = Commands.SVGT
        b[1] = 'K'.code.toByte()
        assertNull(WriteGuard.check(svc, chr, Commands.SVGT))
        assertEquals(WriteGuard.NOT_LISTED, WriteGuard.check(svc, chr, b))
    }
}
