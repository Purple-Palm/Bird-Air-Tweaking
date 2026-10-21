package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogRecorderTest {
    private val start = Instant.parse("2026-10-15T01:54:22.971Z").toEpochMilli()
    private val gatt = listOf(GattService(Uuids.SERVICE, listOf(GattChar(Uuids.CHAR, GattProps(wnr = true, notify = true)))))

    /** The standstill log recorded again through LogRecorder. */
    private fun rerecord(path: String): LogRecorder {
        val r = LogRecorder(start)
        for (e in Repo.log(path).entries) when (e.k) {
            "rx" -> r.rx(e.s!!, e.u!!, e.b!!, e.t)
            "rd" -> r.rd(e.s!!, e.u!!, e.b!!, e.t)
            "tx" -> r.tx(e.s!!, e.u!!, e.b!!, e.t)
            "sys" -> r.sys(e.n!!, e.t)
            "mk" -> r.mk(e.n!!, e.t)
        }
        return r
    }

    @Test fun shapeLikeThePage() {
        val r = LogRecorder(start)
        r.sys("Выбрано устройство: scooter", 0)
        r.servicesFound(3, 5, 693)
        r.rx("ffe0", "ffe1", MODE_FRAME.copyOf(20), 4850)
        r.tx(Uuids.SERVICE, Uuids.CHAR, Commands.MODE3, 4689)
        r.mk("Газ \"до упора\"\n", 5000)
        r.gps(5.257, 4.4, 5100)
        r.rd("1800", "2a01", ByteArray(0), 5200)
        val o = Json.parseToJsonElement(r.toJson(anonymize = false, gatt = gatt, decoder = Repo.decoder().spec)).jsonObject
        assertEquals(listOf("format", "version", "startedAt", "device", "demo", "trimmed", "gatt", "decoder", "entries"), o.keys.toList())
        assertEquals("birdair-lab-log", o["format"]!!.jsonPrimitive.content)
        assertEquals(1, o["version"]!!.jsonPrimitive.int)
        assertEquals("2026-10-15T01:54:22.971Z", o["startedAt"]!!.jsonPrimitive.content)
        assertFalse(o["trimmed"]!!.jsonPrimitive.boolean)
        assertEquals(Uuids.CHAR, o["gatt"]!!.jsonArray[0].jsonObject["chars"]!!.jsonArray[0].jsonObject["uuid"]!!.jsonPrimitive.content)
        assertEquals("colon-sum16", o["decoder"]!!.jsonObject["framing"]!!.jsonPrimitive.content)
        assertEquals(18, o["decoder"]!!.jsonObject["fields"]!!.jsonArray.size)           // decoder v6
        val es = o["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("t", "k", "n"), es[0].keys.toList())
        assertEquals("Найдено сервисов: 3, характеристик: 5", es[1]["n"]!!.jsonPrimitive.content)
        assertEquals(listOf("t", "k", "s", "u", "h"), es[2].keys.toList())
        assertEquals(4850L, es[2]["t"]!!.jsonPrimitive.long)
        assertEquals(Uuids.SERVICE, es[2]["s"]!!.jsonPrimitive.content)
        assertEquals(Uuids.CHAR, es[2]["u"]!!.jsonPrimitive.content)
        assertEquals("3A 17 00 0D 00 00 00 00 00 0B 00 00 00 37 A8 01 01 10 01 0D", es[2]["h"]!!.jsonPrimitive.content)
        assertEquals("2B 4D 4F 44 45 3D 33", es[3]["h"]!!.jsonPrimitive.content)
        assertEquals("Газ \"до упора\"\n", es[4]["n"]!!.jsonPrimitive.content)
        assertEquals(listOf("t", "k", "v", "a"), es[5].keys.toList())
        assertEquals(5.26, es[5]["v"]!!.jsonPrimitive.content.toDouble())
        assertEquals(4, es[5]["a"]!!.jsonPrimitive.int)
        assertEquals("", es[6]["h"]!!.jsonPrimitive.content)
        assertEquals(7, r.size)
        // the device is written only when not anonymized
        r.deviceName = "BirdAir-X"
        val dev = { a: Boolean -> Json.parseToJsonElement(r.toJson(a)).jsonObject["device"]!!.jsonObject }
        assertEquals("BirdAir-X", dev(false)["name"]!!.jsonPrimitive.content)
        assertEquals("scooter", dev(true)["name"]!!.jsonPrimitive.content)
        assertEquals("scooter", dev(true)["id"]!!.jsonPrimitive.content)
    }

    @Test fun fileNames() {
        val r = LogRecorder(start)
        assertEquals("20261015-015422-moya-poezdka", r.fileBase("Моя поездка!", ZoneOffset.UTC))
        assertEquals("20261014-185422-log", r.fileBase("", ZoneId.of("America/Los_Angeles")))
        assertEquals("20261015-015422-ride-1.json.gz", r.fileName("  Ride #1 ", gz = true, zone = ZoneOffset.UTC))
        assertEquals("a".repeat(40), LogRecorder.slug("a".repeat(50)))
        assertEquals("schet-ezh", LogRecorder.slug("Щёт-Ёж"))
    }

    @Test fun trimsTheOldest() {
        val r = LogRecorder(start, maxEntries = 100)
        repeat(250) { r.rx("ffe0", "ffe1", byteArrayOf(it.toByte()), it.toLong()) }
        assertTrue(r.size in 100..104, "${r.size}")
        assertTrue(r.trimmed)
        assertEquals(250L, r.pushed)
        val o = Json.parseToJsonElement(r.toJson()).jsonObject
        assertTrue(o["trimmed"]!!.jsonPrimitive.boolean)
        assertEquals(249L, o["entries"]!!.jsonArray.last().jsonObject["t"]!!.jsonPrimitive.long)
    }

    @Test fun recordedBytesAreCopied() {
        val r = LogRecorder(start)
        val b = byteArrayOf(1, 2, 3)
        r.rx("ffe0", "ffe1", b, 0)
        b[0] = 9
        assertTrue(r.toJson().contains("\"h\":\"01 02 03\""))
    }

    /** The name the dash sends in «AT+SETNAME=…», split over notifications, around frames. */
    private fun withName(name: String?): LogRecorder {
        val r = LogRecorder(start)
        r.deviceName = name
        r.deviceId = "AA:BB:CC:DD:EE:FF"
        r.sys("Выбрано устройство: BirdAir 12345678", 0)
        r.rd("1800", "2a00", ascii("BirdAir 12345678"), 10)
        r.rd("180a", "2a23", hex("01 02 03 04 05 06 07 08"), 11)
        r.rx("ffe0", "ffe1", ascii("AT+SETNAME=Bird"), 20)
        r.rx("ffe0", "ffe1", ascii("Air 1234"), 21)
        r.rx("ffe0", "ffe1", ascii("5678\r\n") + MODE_FRAME.copyOf(10), 22)
        r.rx("ffe0", "ffe1", MODE_FRAME.copyOfRange(10, MODE_FRAME.size), 23)
        r.rx("ffe0", "ffe1", ascii("AT+RESET\r\n"), 24)
        r.mk("ID 12345678 и AA:BB:CC:DD:EE:FF", 30)
        return r
    }

    private fun entries(json: String) = Json.parseToJsonElement(json).jsonObject["entries"]!!.jsonArray.map { it.jsonObject }

    @Test fun anonymizationMasksASplitSetName() {
        for (name in listOf("BirdAir 12345678", null)) {
            val es = entries(withName(name).toJson())
            val rx = es.filter { it["k"]!!.jsonPrimitive.content == "rx" }.map { Hex.parse(it["h"]!!.jsonPrimitive.content)!! }
            val stream = String(rx.fold(ByteArray(0)) { a, b -> a + b }, Charsets.ISO_8859_1)
            assertTrue(stream.startsWith("AT+SETNAME=" + "*".repeat(16) + "\r\n"), "$name: $stream")
            assertFalse(stream.contains("Bird") || stream.contains("1234"), stream)
            assertTrue(stream.contains("AT+RESET\r\n"))
            // frames are untouched
            val a = FrameAssembler()
            assertEquals(listOf(Hex.of(MODE_FRAME)), rx.flatMap { a.feed(it) }.map(Hex::of))
            assertEquals("73 63 6F 6F 74 65 72", es[1]["h"]!!.jsonPrimitive.content)      // Device Name → scooter
            assertEquals("00 00 00 00 00 00 00 00", es[2]["h"]!!.jsonPrimitive.content)   // System ID → zeros
            // the name read from AT+SETNAME is hidden in text too, the MAC by its own
            assertEquals("Выбрано устройство: scooter", es[0]["n"]!!.jsonPrimitive.content)
            assertEquals("ID scooter и scooter", es.last()["n"]!!.jsonPrimitive.content)
        }
        // not anonymized: as recorded
        val raw = entries(withName("BirdAir 12345678").toJson(anonymize = false))
        assertEquals("AT+SETNAME=Bird", String(Hex.parse(raw[3]["h"]!!.jsonPrimitive.content)!!, Charsets.ISO_8859_1))
    }

    @Test fun anonymizationMatchesBirdlog() {
        Py.assume()
        val r = withName("BirdAir 12345678")
        r.note = "Еду на BirdAir 12345678, ID 12345678"
        val dir = Files.createTempDirectory("birdair-log").toFile()
        try {
            val f = File(dir, "raw.json").apply { writeText(r.toJson(anonymize = false, gatt = gatt)) }
            val script = """
                import json, sys
                sys.path.insert(0, sys.argv[1])
                import birdlog as bl
                lg = bl.anonymize(bl.load_file(sys.argv[2]))
                print(json.dumps({'device': lg['device'], 'note': lg.get('note'), 'entries': [bl.clean_entry(e) for e in lg['entries']]}, ensure_ascii=False))
            """.trimIndent()
            val py = Json.parseToJsonElement(Py.run(script, "", f.absolutePath)).jsonObject
            val mine = Json.parseToJsonElement(r.toJson(gatt = gatt)).jsonObject
            assertEquals(py["device"], mine["device"])
            assertEquals(py["note"], mine["note"])
            assertEquals("Еду на scooter, ID scooter", mine["note"]!!.jsonPrimitive.content)
            assertEquals(py["entries"], mine["entries"])
        } finally { dir.deleteRecursively() }
    }

    /** What LogRecorder writes, tools/birdlog.py load_file and tools/uart_frames.py read: .json and .json.gz. */
    @Test fun pythonToolsReadIt() {
        Py.assume()
        val r = rerecord(Repo.STANDSTILL)
        val dir = Files.createTempDirectory("birdair-log").toFile()
        try {
            val plain = File(dir, r.fileName("standstill", gz = false)).apply { writeText(r.toJson(gatt = gatt, decoder = Repo.decoder().spec)) }
            val gz = File(dir, r.fileName("standstill", gz = true)).apply { writeBytes(r.toGzip(gatt = gatt, decoder = Repo.decoder().spec)) }
            assertEquals(plain.readText(), String(GZIPInputStream(gz.inputStream()).readBytes(), Charsets.UTF_8))
            val script = """
                import json, sys
                sys.path.insert(0, sys.argv[1])
                import birdlog as bl, uart_frames as uf
                out = []
                for p in sys.argv[2:]:
                    lg = bl.load_file(p)
                    a = uf.assemble_log(lg)[(bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1'))]
                    out.append({'entries': len(lg['entries']), 'frames': a.n_frames, 'recovered': a.recovered,
                                'dropped': a.dropped, 'device': lg['device'], 'errs': bl.check_decoder(dict(lg['decoder'], format=bl.DECODER_FORMAT, version=1))})
                print(json.dumps(out, ensure_ascii=False))
            """.trimIndent()
            val res = Json.parseToJsonElement(Py.run(script, "", plain.absolutePath, gz.absolutePath)).jsonArray
            for (x in res.map { it.jsonObject }) {
                assertEquals(Repo.log(Repo.STANDSTILL).entries.size, x["entries"]!!.jsonPrimitive.int)
                assertEquals(284, x["frames"]!!.jsonPrimitive.int)
                assertEquals(16, x["recovered"]!!.jsonPrimitive.int)
                assertEquals(0, x["dropped"]!!.jsonPrimitive.int)
                assertEquals("scooter", (x["device"] as JsonObject)["name"]!!.jsonPrimitive.content)
                assertEquals(0, x["errs"]!!.jsonArray.size, x.toString())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun noteInTheFileAndItsName() {
        val r = LogRecorder(start)
        r.sys("x", 0)
        assertEquals("20261015-015422-log", r.fileBase(zone = ZoneOffset.UTC))
        r.note = "  Вдвоём до отсечки, FLY\n"
        assertEquals("20261015-015422-vdvoem-do-otsechki-fly", r.fileBase(zone = ZoneOffset.UTC))
        assertEquals("20261015-015422-log", r.fileBase("log", ZoneOffset.UTC))        // an explicit label wins
        val o = Json.parseToJsonElement(r.toJson()).jsonObject
        assertEquals(listOf("format", "version", "startedAt", "device", "demo", "trimmed", "note", "gatt", "decoder", "entries"), o.keys.toList())
        assertEquals("Вдвоём до отсечки, FLY", o["note"]!!.jsonPrimitive.content)
        r.note = "   "
        assertNull(Json.parseToJsonElement(r.toJson()).jsonObject["note"])
        assertEquals("20261015-015422-log", r.fileBase(zone = ZoneOffset.UTC))
        // anonymized: the scooter's name does not get out through the note either
        r.deviceName = "BirdAir 12345678"
        r.note = "BirdAir 12345678 (серийник 12345678) \"вдвоём\""
        assertEquals("scooter (серийник scooter) \"вдвоём\"", Json.parseToJsonElement(r.toJson()).jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals(r.note!!.trim(), Json.parseToJsonElement(r.toJson(anonymize = false)).jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals("20261015-015422-scooter-seriynik-scooter-vdvoem", r.fileBase(zone = ZoneOffset.UTC))
    }

    @Test fun rotationContinuesUnderANewName() {
        val r = LogRecorder(start, maxEntries = 100)
        r.note = "Длинная поездка"
        r.deviceName = "BirdAir-X"
        // the caller's clock: ms since the recording started
        repeat(100) { r.rx("ffe0", "ffe1", byteArrayOf(it.toByte()), 1000L + it * 50) }
        assertTrue(r.needsRotation)
        val rot = r.rotate(5_400_123L, ZoneOffset.UTC)
        assertTrue(rot.finished === r)
        val n = rot.next
        assertTrue(r.rotate(5_400_200L, ZoneOffset.UTC).next === n)                // rotating twice: the same next part
        assertFalse(r.needsRotation)
        assertEquals(start + 5_400_123L, n.startedAtMs)
        assertEquals(5_400_123L, n.tOffsetMs)
        assertEquals("Длинная поездка", n.note)
        assertEquals("BirdAir-X", n.deviceName)
        assertEquals("20261015-032423-dlinnaya-poezdka", n.fileBase(zone = ZoneOffset.UTC))
        n.rx("ffe0", "ffe1", byteArrayOf(1), 5_401_000L)
        r.mk("Газ", 5_402_000L)                                                     // a late writer of the old part
        assertEquals(100, r.size)
        assertFalse(r.trimmed)
        val es = entries(n.toJson())
        assertEquals("продолжение лога 20261015-015422-dlinnaya-poezdka", es[0]["n"]!!.jsonPrimitive.content)
        assertEquals(listOf(0L, 877L, 1877L), es.map { it["t"]!!.jsonPrimitive.long })
        assertEquals("2026-10-15T03:24:23.094Z", Json.parseToJsonElement(n.toJson()).jsonObject["startedAt"]!!.jsonPrimitive.content)
        // the first part keeps its own times
        assertEquals(1000L, entries(r.toJson()).first()["t"]!!.jsonPrimitive.long)
        // without rotation it trims, as before
        val t = LogRecorder(start, maxEntries = 100)
        repeat(250) { t.rx("ffe0", "ffe1", byteArrayOf(1), it.toLong()) }
        assertTrue(t.trimmed)
    }

    @Test fun emptyLog() {
        val o = Json.parseToJsonElement(LogRecorder(start).toJson()).jsonObject
        assertEquals(0, o["entries"]!!.jsonArray.size)
        assertEquals("{\"json\":false,\"fields\":[]}", o["decoder"].toString())
        assertNull(o["part"])
    }
}
