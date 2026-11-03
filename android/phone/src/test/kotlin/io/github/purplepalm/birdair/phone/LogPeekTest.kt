package io.github.purplepalm.birdair.phone

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/** «… · 9806 записей»: a saved log's entry count, read once per file version. */
class LogPeekTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("logpeek").toFile(); LogPeek.clear() }
    @After fun tearDown() { dir.deleteRecursively(); LogPeek.clear() }

    @Test fun countsARealLog() {
        val src = File(TestLogs.root, TestLogs.TO_ZERO)
        val want = Json.parseToJsonElement(GZIPInputStream(src.inputStream()).readBytes().toString(Charsets.UTF_8)).jsonObject["entries"]!!.jsonArray.size
        val f = File(dir, "20261020-214221-do-otsechki-do-nulya.json.gz")
        src.copyTo(f)
        val log = LocalLog(f.name, f.length(), f.lastModified())
        assertNull(LogPeek.cached(log))
        assertEquals(want, runBlocking { LogPeek.entries(dir, log) })
        assertEquals(want, LogPeek.cached(log))
    }

    @Test fun cachedPerVersion() {
        val f = File(dir, "a.json.gz")
        f.writeBytes(byteArrayOf(1, 2, 3))
        val log = LocalLog(f.name, 3, 1000)
        assertNull("not gzip: unreadable", runBlocking { LogPeek.entries(dir, log) })
        java.util.zip.GZIPOutputStream(f.outputStream()).use { it.write("{\"entries\":[{\"t\":0},{\"t\":5}]}".toByteArray()) }
        assertEquals(2, runBlocking { LogPeek.entries(dir, log) })
        f.delete()
        assertEquals("the same version comes from the cache", 2, runBlocking { LogPeek.entries(dir, log) })
        assertNull("another version is read again (gone now)", runBlocking { LogPeek.entries(dir, log.copy(mtime = 2000)) })
    }
}
