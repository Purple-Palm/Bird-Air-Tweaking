package io.github.purplepalm.birdair.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DecoderTest {
    private val b = hex("01 FF 80 7F 12 34 56 78")

    @Test fun readNumTypes() {
        assertEquals(0x01L, readNum(b, 0, "u8"))
        assertEquals(-1L, readNum(b, 1, "s8"))
        assertEquals(0x7F80L, readNum(b, 2, "u16le"))
        assertEquals(-255L, readNum(b, 0, "s16le"))                  // FF01
        assertEquals(0x01FFL, readNum(b, 0, "u16be"))
        assertEquals((0x807F - 0x10000).toLong(), readNum(b, 2, "s16be"))
        assertEquals(0x80FF01L, readNum(b, 0, "u24le"))
        assertEquals(0x01FF80L, readNum(b, 0, "u24be"))
        assertEquals(0x123456L, readNum(b, 4, "u24be"))
        assertEquals(0x7F80FF01L, readNum(b, 0, "u32le"))
        assertEquals(0x78563412L, readNum(b, 4, "u32le"))
        assertEquals(0x01FF807FL, readNum(b, 0, "u32be"))
        assertEquals(0xFFFFFFFFL, readNum(hex("FF FF FF FF"), 0, "u32be"))
        assertEquals(0xFFFFFFFFL, readNum(hex("FF FF FF FF"), 0, "u32le"))
        assertEquals(-1L, readNum(hex("FF FF FF FF"), 0, "s32le"))
        assertEquals(1L, readNum(b, 2, "bit7"))
        assertEquals(0L, readNum(b, 2, "bit0"))
        assertEquals(1L, readNum(hex("20"), 0, "bit5"))
        assertEquals(0L, readNum(hex("DF"), 0, "bit5"))
    }

    @Test fun readNumOutOfRange() {
        assertNull(readNum(b, 7, "u16le"))
        assertNull(readNum(b, 6, "u24be"))
        assertNull(readNum(b, 5, "u32le"))
        assertNull(readNum(b, 8, "u8"))
        assertNull(readNum(b, -1, "u8"))
        assertNull(readNum(b, 0, "f32"))
        assertNull(readNum(ByteArray(0), 0, "bit0"))
    }

    @Test fun patterns() {
        assertContentEquals(intArrayOf(0x5A, 0xA5, -1, 0x25), Decoders.parsePattern("5A A5 ?? 25"))
        assertContentEquals(intArrayOf(0x3A, 0x17, 0, 0x0D), Decoders.parsePattern(" 0x3A,17 0 d "))
        assertContentEquals(intArrayOf(), Decoders.parsePattern(""))
        assertNull(Decoders.parsePattern("3A 1G"))
        assertNull(Decoders.parsePattern("3A 123"))
        assertTrue(Decoders.matchPrefix(MODE_FRAME, Decoders.parsePattern("3A ?? 00 0D")!!))
        assertTrue(!Decoders.matchPrefix(VOLT_FRAME, Decoders.parsePattern("3A 17")!!))
        assertTrue(!Decoders.matchPrefix(hex("3A"), Decoders.parsePattern("3A 17")!!))
    }

    @Test fun masks() {
        assertEquals(3L, Decoders.applyMask(0x0B, 3))
        assertEquals(2L, Decoders.applyMask(0b1010_1000, 0x30))
        assertEquals(1L, Decoders.applyMask(-1, 0x80))           // a signed raw value keeps its two's complement bits
        assertEquals(3L, Decoders.parseMask("0x03"))
        assertEquals(3L, Decoders.parseMask("0b11"))
        assertEquals(12L, Decoders.parseMask("12"))
        assertNull(Decoders.parseMask("0"))
        assertNull(Decoders.parseMask("nope"))
        assertNull(Decoders.parseMask("0x1FFFFFFFF"))
    }

    @Test fun decoderV6OnRealFrames() {
        val d = Repo.decoder()
        assertTrue(d.framed)
        assertEquals(6, d.spec.version)
        assertEquals(18, d.fields.size)
        val mode = d.decode(MODE_FRAME, Uuids.CHAR).associate { it.first.name to it.second }
        assertEquals(3.0, mode["Режим"])                          // 0x0B & 3 = FLY
        assertEquals(0.0, mode["Скорость"])
        assertEquals(1424.8, mode["Пробег"]!!, 1e-9)               // 00 37 A8 = 14248
        assertEquals(0.0, mode["Код контроллера (байт 8)"])
        val volt = d.decode(VOLT_FRAME, Uuids.CHAR).associate { it.first.name to it.second }
        assertEquals(39.854, volt["Напряжение"]!!, 1e-9)           // AE 9B LE
        assertEquals(0.046, volt["Ток"]!!, 1e-9)                   // D2 FF = −46 mA, × −0.001
        assertEquals(75.0, volt["Заряд"])
        assertEquals(3287.0, volt["Остаток в батарее, мА·ч"])
        assertEquals(4363.0, volt["Полная ёмкость по BMS, мА·ч"])
        assertEquals(24.0, volt["Температура 1 (батарея?)"])
        assertEquals(0.0, volt["Мотор тянет (бит)"])
        // v6: the status bytes raw (b4 03, b5 00, b6 00, b18 0x69 = 105 on 14.10)
        assertEquals(listOf(3.0, 0.0, 0.0, 105.0), listOf("Статус BMS: байт 4", "Статус BMS: байт 5", "Статус BMS: байт 6", "Счётчик циклов? (байт 18)").map { volt[it] })
        assertEquals(TelemetryTracker.STATUS, d.fields.filter { it.name in TelemetryTracker.STATUS }.map { it.name }.toSet())
        // the uuid filter: another characteristic gets nothing; short forms are normalized
        assertTrue(d.decode(VOLT_FRAME, "0000ffe2-0000-1000-8000-00805f9b34fb").isEmpty())
        assertEquals(12, d.decode(VOLT_FRAME, "FFE1").size)
        assertEquals(12, d.decode(VOLT_FRAME, "0000FFE1-0000-1000-8000-00805F9B34FB").size)
    }

    @Test fun parseIsLenientLikeThePage() {
        val d = Decoder(Decoders.parse("""
            {"format":"birdair-lab-decoder","version":7,"sources":["x"],"something":{"a":1},"framing":"colon-sum16",
             "fields":[
               {"key":"voltage","uuid":"ffe1","match":"3A 18 20 10","offset":9,"type":"u16le","scale":0.001,"extra":1},
               {"key":"mode","match":"3A 17","offset":"9","type":"u8","mask":"0x03","scale":"1"},
               {"key":"weird","name":null,"offset":-4,"type":"f64"},
               {"key":"speed_kmh","match":"zz"},
               42
             ]}
        """.trimIndent()))
        assertEquals(7, d.spec.version)
        assertEquals(4, d.fields.size)
        assertEquals(Uuids.CHAR, d.fields[0].uuid)
        assertEquals(3L, d.fields[1].mask)
        assertEquals(9, d.fields[1].offset)
        assertEquals("custom", d.fields[2].key)
        assertEquals(0, d.fields[2].offset)
        assertEquals("u8", d.fields[2].type)
        val got = d.decode(MODE_FRAME, Uuids.CHAR).map { it.first.key to it.second }
        assertEquals(listOf("mode" to 3.0, "custom" to 0x3A.toDouble()), got)   // the bad "zz" pattern never matches
        assertFailsWith<IllegalArgumentException> { Decoders.parse("[]") }
        assertFailsWith<IllegalArgumentException> { Decoders.parse("{\"version\":1}") }
        assertFailsWith<IllegalArgumentException> { Decoders.parse("not json") }
        assertNull(Decoders.parseOrNull("{"))
    }

    @Test fun scaleAndAdd() {
        val d = Decoder(DecoderSpec(fields = listOf(FieldSpec("temp", offset = 0, type = "s8", scale = 0.5, add = -10.0))))
        assertEquals(listOf(-10.5), d.decode(hex("FF"), "ffe1").map { it.second })
        assertTrue(!d.framed)
    }

    @Test fun toJsonRoundTrip() {
        val spec = Decoders.parse(Repo.file(Repo.DECODER).readText())
        val again = Decoders.parse(Decoders.toJson(spec))
        assertEquals(spec, again)
    }
}
