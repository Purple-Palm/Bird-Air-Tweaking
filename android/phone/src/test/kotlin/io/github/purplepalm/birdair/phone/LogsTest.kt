package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.LogRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class LogsTest {
    private val start = 1_792_000_000_000L   // 2026-10-14

    @Test fun partsRotateBeforeTheCapAndKeepTheNote() {
        var now = 0L
        val s = Session(start, { now }, maxEntries = 100)
        s.device("BirdAir-123456789", "AA:BB:CC:DD:EE:FF")
        s.note = "  вдвоём, FLY  "
        assertEquals("вдвоём, FLY", s.note)
        for (i in 0 until 99) { now = i * 100L; s.recorder.rx("ffe0", "ffe1", byteArrayOf(0x3A, i.toByte()), now) }
        assertNull("not yet", s.rotateIfNeeded())
        s.recorder.mk("газ", now)
        now = 10_000
        val done = s.rotateIfNeeded()!!
        assertEquals(1, done.number)
        assertTrue(done.finished)
        assertEquals(100, done.recorder.size)
        assertFalse("no trimming: the part was cut first", done.recorder.trimmed)
        val p2 = s.part
        assertEquals(2, p2.number)
        assertEquals(listOf(done, p2), s.parts)
        assertEquals("вдвоём, FLY", p2.recorder.note)
        assertEquals("the same device", "BirdAir-123456789", p2.recorder.deviceName)
        assertEquals("the next part starts at the rotation's wall clock", start + 10_000, p2.recorder.startedAtMs)
        assertTrue(p2.recorder.toJson().contains(LogRecorder.CONTINUED + done.recorder.fileBase()))
        // a late write to the old part (a BLE callback that still held it) lands in the new one
        done.recorder.rx("ffe0", "ffe1", byteArrayOf(1), 10_050)
        assertEquals(100, done.recorder.size)
        assertEquals(2, p2.recorder.size)
        // the new part's t counts from its own start
        assertTrue(p2.recorder.toJson().contains("{\"t\":50,\"k\":\"rx\""))
        // the description changes for every part
        s.note = "соло"
        assertEquals("соло", done.recorder.note)
        assertTrue(s.mark("горка"))
        assertFalse(s.mark("   "))
    }

    @Test fun nameFollowsTheNoteUntilTheFirstUpload() {
        val s = Session(start, { 0L })
        s.device("BirdAir-123456789", null)
        val p = s.part
        s.recorder.sys("x", 0)
        val stamp = LogRecorder.stamp(start)
        assertEquals("$stamp-log", p.baseNow())
        s.note = "Вдвоём до отсечки, BirdAir-123456789"
        assertEquals("the device name stays out of the file name", "$stamp-vdvoem-do-otsechki-scooter", p.baseNow())
        assertTrue(p.dirtyLocal)
        p.savedPushed = p.recorder.pushed; p.savedNote = p.note; p.savedFile = p.baseNow() + LogFiles.EXT
        assertFalse(p.dirtyLocal)
        // the first upload fixes the name; later descriptions only go into "note" and the commit
        p.base = p.baseNow()
        s.note = "соло"
        assertEquals("$stamp-vdvoem-do-otsechki-scooter", p.baseNow())
        assertTrue("another note: save again", p.dirtyLocal)
        p.gh = GhPut("logs/x", "sha", null); p.ghDest = "d"; p.ghPushed = p.recorder.pushed; p.ghNote = "соло"
        assertFalse(p.dirtyGh("d"))
        assertTrue(p.dirtyGh("other/dest"))
        s.note = "соло, FLY"
        assertTrue("another note: upload again", p.dirtyGh("d"))
        s.note = "соло"
        s.recorder.sys("y", 1)
        assertTrue("new entries: upload again", p.dirtyGh("d"))
    }

    @Test fun commitMessageCarriesTheNote() {
        assertEquals("Лог 20261015-155523-log: 1234 записей (приложение)", GitHubApi.commitMessage("20261015-155523-log", 1234))
        assertEquals("Лог b: 5 записей, часть 2 (приложение)\n\nвдвоём, FLY\nдо отсечки", GitHubApi.commitMessage("b", 5, "  вдвоём, FLY\nдо отсечки ", 2))
        assertEquals("Лог b: 5 записей (приложение)", GitHubApi.commitMessage("b", 5, "   "))
        assertEquals("scooter, вдвоём", Notes.hide("BirdAir-123456789, вдвоём", "BirdAir-123456789", "AA:BB"))
        assertEquals("серийник scooter", Notes.hide("серийник 123456789", "BirdAir-123456789", null))
        assertEquals("MTU ok", Notes.hide("MTU ok", "MT", null))
        assertNull(Notes.clean(" \n "))
    }

    @Test fun unsentAndPrune() {
        val a = LocalLog("a.json.gz", 100, 1000)
        val b = LocalLog("b.json.gz", 200, 2000)
        val c = LocalLog("c.json.gz", 300, 3000, current = true)
        val sent = mapOf(
            "a.json.gz" to SentLog("logs/a-app.json.gz", "s1", "d", 100, 1000, null),
            "b.json.gz" to SentLog("logs/b-app.json.gz", "s2", "d", 150, 1500, null),   // changed since
        )
        assertEquals(listOf(c, b), LogFiles.unsent(listOf(a, b, c, LocalLog("x.json.gz.tmp", 1, 1)), sent))
        val back = LogFiles.decode(LogFiles.encode(sent.getValue("a.json.gz")))
        assertEquals(sent.getValue("a.json.gz"), back)
        assertNull(LogFiles.decode("{}"))
        assertNull(LogFiles.decode("not json"))
        assertNull(LogFiles.decode(null))
        // prune: only sent files beyond keep; unsent ones only beyond the hard limit, never the running one
        val files = (1..10).map { LocalLog("f$it.json.gz", 1, it * 1000L, current = it == 1) }
        val allSent = files.filter { it.mtime % 2000 == 0L }.associate { it.name to SentLog("p", null, "d", 1, it.mtime, null) }
        assertEquals(listOf("f2.json.gz"), LogFiles.prune(files, allSent, keep = 8, hardKeep = 100).map { it.name })
        val hard = LogFiles.prune(files, allSent, keep = 4, hardKeep = 6).map { it.name }
        assertEquals(listOf("f2.json.gz", "f4.json.gz", "f6.json.gz", "f3.json.gz"), hard)
        assertTrue(LogFiles.prune(files, allSent, keep = 40, hardKeep = 100).isEmpty())
    }

    @Test fun peekReadsNoteAndCount() {
        val r = LogRecorder(start)
        r.deviceName = "BirdAir-123456789"
        r.note = "вдвоём \"до отсечки\"\nBirdAir-123456789"
        for (i in 0 until 1234) r.rx("ffe0", "ffe1", byteArrayOf(0x3A, 0x18), i.toLong())
        r.sys("Отключено", 2000)
        val gz = r.toGzip(anonymize = true)
        val peek = GZIPInputStream(gz.inputStream()).use { LogFiles.peek(it) }
        assertEquals(1235, peek.entries)
        assertEquals("вдвоём \"до отсечки\"\nscooter", peek.note)
        val none = LogRecorder(start).apply { sys("x", 0) }.toGzip()
        assertNull(GZIPInputStream(none.inputStream()).use { LogFiles.peek(it) }.note)
        assertNull(LogFiles.noteIn("{\"format\":\"birdair-lab-log\",\"device\":{\"name\":\",\\\"note\\\":\\\"x\"},\"gatt\":[]"))
    }

    @Test fun gitBlobShaLikeGit() {
        // git hash-object of "hello\n"
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", LogStore.gitBlobSha("hello\n".toByteArray()))
        val bo = ByteArrayOutputStream()
        GZIPOutputStream(bo).use { it.write(ByteArray(10)) }
        assertEquals(40, LogStore.gitBlobSha(bo.toByteArray()).length)
    }

    @Test fun maxEntriesIsTheRecordersCap() {
        val s = Session(start, { 0L })
        assertEquals(250_000, s.recorder.maxEntries)
        assertSame(s.part.recorder, s.recorder)
    }
}
