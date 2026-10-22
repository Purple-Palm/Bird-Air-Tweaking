package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandsTest {
    @Test fun exactlySixCommands() {
        val want = mapOf(
            Commands.SVGT to "2B 53 56 47 54 3D 3F", Commands.MODE1 to "2B 4D 4F 44 45 3D 31",
            Commands.MODE2 to "2B 4D 4F 44 45 3D 32", Commands.MODE3 to "2B 4D 4F 44 45 3D 33",
            Commands.LIGHT_OFF to "2B 48 4C 47 54 3D 30", Commands.LIGHT_ON to "2B 48 4C 47 54 3D 31",
        )
        for ((b, h) in want) {
            assertEquals(h, Hex.of(b))                         // docs/bird-air-protocol.md, «Команды»
            assertTrue(Commands.isAllowed(b))
            assertTrue(Commands.canWrite(Uuids.SERVICE, Uuids.CHAR, b))
        }
        assertContentEquals(Commands.MODE2, Commands.mode(2))
        assertNull(Commands.mode(4))
        assertContentEquals(Commands.LIGHT_ON, Commands.light(true))
        for (s in listOf("+MODE=4", "+MODE=3\r\n", " +MODE=3", "+HVGT=?", "+SVGT=", "+mode=3", "", "+HLGT=2"))
            assertFalse(Commands.isAllowed(ascii(s)), s)
        assertFalse(Commands.isAllowed(hex("AE 4B 45 59 3F D6")))
    }

    @Test fun whitelistCannotBeChanged() {
        val b = Commands.SVGT
        b[0] = 0xAE.toByte()
        assertEquals("2B 53 56 47 54 3D 3F", Hex.of(Commands.SVGT))
        assertFalse(Commands.isAllowed(b))
    }

    @Test fun keyAnywhere() {
        assertTrue(Commands.containsKey(hex("AE 4B 45 59 3F D6")))
        assertTrue(Commands.containsKey(hex("2B 4D AE 4B 45 59")))
        assertTrue(Commands.containsKey(hex("00 AE 4B 45 59 00")))
        assertFalse(Commands.containsKey(hex("AE 4B 45")))
        assertFalse(Commands.containsKey(hex("AE 4B 45 58 AE 4B")))
        assertFalse(Commands.containsKey(ByteArray(0)))
        for (b in listOf(Commands.SVGT, Commands.MODE1, Commands.MODE2, Commands.MODE3, Commands.LIGHT_ON, Commands.LIGHT_OFF))
            assertFalse(Commands.containsKey(b))
    }

    @Test fun onlyFfe1() {
        assertTrue(Commands.canWrite("ffe0", "FFE1", Commands.SVGT))
        assertFalse(Commands.canWrite(Uuids.SERVICE, "ffe2", Commands.SVGT))
        assertFalse(Commands.canWrite("1800", Uuids.CHAR, Commands.SVGT))
        assertFalse(Commands.canWrite(Uuids.TELINK_OTA_SERVICE, Uuids.TELINK_OTA_CHAR, Commands.SVGT))
        assertFalse(Commands.canWrite(Uuids.SERVICE, Uuids.CHAR, hex("AE 4B 45 59 3F D6")))
        assertFalse(Commands.canWrite(Uuids.SERVICE, Uuids.CHAR, hex("00 01 02")))
    }

    @Test fun uuids() {
        assertEquals(Uuids.CHAR, Uuids.full("ffe1"))
        assertEquals(Uuids.CHAR, Uuids.full("0xFFE1"))
        assertEquals(Uuids.CHAR, Uuids.full("0000FFE1"))
        assertEquals(Uuids.CHAR, Uuids.full(" 0000FFE1-0000-1000-8000-00805F9B34FB "))
        assertEquals("handle-0x0012", Uuids.full("handle-0x0012"))
        assertNull(Uuids.norm("ffe"))
        assertEquals("0xFFE1", Uuids.short(Uuids.CHAR))
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", Uuids.CCCD)
    }

    @Test fun otaAndDfuAreDangerous() {
        assertTrue(Uuids.isDanger(Uuids.TELINK_OTA_SERVICE, "1234"))
        assertTrue(Uuids.isDanger("ffe0", Uuids.TELINK_OTA_CHAR))
        assertTrue(Uuids.isDanger("00010203-0405-0607-0809-0A0B0C0D1912", "00010203-0405-0607-0809-0A0B0C0D2B12"))
        assertTrue(Uuids.isDanger("fe59", "8ec90001-f315-4f60-9fb8-838830daea50"))
        assertTrue(Uuids.isDanger("fef5", "1234"))
        assertTrue(Uuids.isDanger("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0", "8ec90003-f315-4f60-9fb8-838830daea50"))
        assertTrue(Uuids.isDanger("00001530-1212-efde-1523-785feabcd123", "00001532-1212-efde-1523-785feabcd123"))
        assertTrue(Uuids.isDanger("ffe0", "00001531-1212-efde-1523-785feabcd123"))
        assertFalse(Uuids.isDanger(Uuids.SERVICE, Uuids.CHAR))
        assertFalse(Uuids.isDanger("1800", "2a00"))
        assertFalse(Uuids.isDanger("00010203-0405-0607-0809-0a0b0c0d1910", "00010203-0405-0607-0809-0a0b0c0d2b10"))   // Telink SPP
    }

    @Test fun labels() {
        assertEquals("«+MODE=3»", Commands.label(Commands.MODE3))
        assertEquals("AE 4B 45 59 3F D6", Commands.label(hex("AE 4B 45 59 3F D6")))
    }
}
