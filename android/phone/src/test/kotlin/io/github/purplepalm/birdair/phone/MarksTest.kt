package io.github.purplepalm.birdair.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The owner's markers: the analyzer's words, one tap now and the word after, kept at the first tap's time. */
class MarksTest {
    /** tools/analyze_log.py MARKER_KINDS, read from the file: (kind, its keys) in its order. */
    private val kinds: List<Pair<String, List<String>>> by lazy {
        val py = File(TestLogs.root, "tools/analyze_log.py").readText()
        val block = Regex("MARKER_KINDS = \\[(.*?)\\n]", RegexOption.DOT_MATCHES_ALL).find(py)!!.groupValues[1]
        Regex("\\('(\\w+)', \\(([^)]*)\\)\\)").findAll(block).map { m ->
            m.groupValues[1] to Regex("'([^']*)'").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
        }.toList()
    }

    /** analyze_log.marker_kind(): the first kind with a key in the label. */
    private fun kind(label: String): String? {
        val s = label.trim().lowercase()
        return kinds.firstOrNull { (_, keys) -> keys.any { it in s } }?.first
    }

    @Test fun presetsAreTheAnalyzersWordsOneKindEach() {
        assertTrue("MARKER_KINDS read: $kinds", kinds.size >= 6)
        val got = MarkerWords.PRESETS.map { it to kind(it) }
        for ((w, k) in got) assertTrue("«$w» is a kind for tools/analyze_log.py", k != null)
        assertEquals("every word its own kind: $got", MarkerWords.PRESETS.size, got.map { it.second }.distinct().size)
        assertEquals(listOf("throttle", "release", "brake", "stop", "charge_on", "charge_off"), got.map { it.second })
    }

    @Test fun theOneTapMarkIsNoKind() {
        for (n in 1..12) assertNull(MarkerWords.auto(n), kind(MarkerWords.auto(n)))
        assertEquals("Метка 1", MarkerWords.next(emptyList()))
        assertEquals("Метка 4", MarkerWords.next(listOf(Marker(791_775, "55%, возвращаюсь домой"), Marker(1_475_798, "дома"), Marker(1_718_313, "11% еду домой"))))
    }

    @Test fun relabelKeepsTheTimeAcrossARotation() {
        var now = 0L
        val s = Session(1_792_000_000_000L, { now }, maxEntries = 50)
        for (i in 0 until 40) { now = i * 100L; s.recorder.rx("ffe0", "ffe1", byteArrayOf(0x3A, i.toByte()), now) }
        now = 4_000
        assertTrue(s.mark(MarkerWords.auto(1)))
        val first = s.part
        for (i in 41 until 50) { now = i * 100L; s.recorder.rx("ffe0", "ffe1", byteArrayOf(0x3A, i.toByte()), now) }
        assertTrue("the part rotated after the marker", s.rotateIfNeeded() != null)
        val pushed = first.recorder.pushed
        assertTrue("the word goes to the part that has the marker", s.relabel(4_000, "Метка 1", "Тормоз"))
        assertEquals("a change the next save and upload carry", pushed + 1, first.recorder.pushed)
        val json = first.recorder.toJson()
        assertTrue(json.contains("{\"t\":4000,\"k\":\"mk\",\"n\":\"Тормоз\"}"))
        assertFalse(json.contains("Метка 1"))
        assertFalse("only once", s.relabel(4_000, "Метка 1", "Газ"))
        assertFalse("another time is another marker", s.relabel(4_100, "Тормоз", "Газ"))
        // in the part being written: its own clock offset
        now = 7_000
        assertTrue(s.mark(MarkerWords.auto(2)))
        assertTrue(s.relabel(7_000, "Метка 2", "Газ"))
        assertTrue(s.part.recorder.toJson().contains("\"k\":\"mk\",\"n\":\"Газ\""))
    }
}
