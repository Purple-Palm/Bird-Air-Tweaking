package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.DecoderSpec
import io.github.purplepalm.birdair.core.Decoders
import io.github.purplepalm.birdair.core.FieldSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TimingTest {
    @Test fun backoff() {
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 10_000L, 10_000L, 10_000L), (0..6).map { Backoff.delayMs(it) })
        assertEquals(1000L, Backoff.delayMs(-1))
    }

    @Test fun pulse() {
        val p = PulseState()
        val ok1 = p.after(true)
        assertEquals(2500L, ok1.delayMs); assertNull(ok1.sys)
        val f1 = p.after(false)
        assertEquals(400L, f1.delayMs); assertEquals(PulseState.FAILED, f1.sys); assertTrue(f1.err)
        assertTrue(p.failing)
        val f2 = p.after(false)
        assertEquals(400L, f2.delayMs); assertNull("only the first failure of a run is logged", f2.sys)
        val ok2 = p.after(true)
        assertEquals(2500L, ok2.delayMs); assertEquals(PulseState.RECOVERED, ok2.sys); assertFalse(ok2.err)
        assertNull(p.after(true).sys)
    }

    @Test fun decoderChoice() {
        val root = File(System.getProperty("repoRoot") ?: "../..")
        val text = File(root, "decoders/current.json").readText()
        val bundled = DecoderPick.parse(text, DecoderPick.BUNDLED)!!
        assertTrue(bundled.spec.version >= 5)
        assertEquals("colon-sum16", bundled.spec.framing)
        val newer = DecoderCandidate(bundled.spec.copy(version = bundled.spec.version + 1), DecoderPick.SITE, text)
        val same = DecoderCandidate(bundled.spec, DecoderPick.CACHED, text)
        assertSame(newer, DecoderPick.best(listOf(bundled, same, newer)))
        assertSame(bundled, DecoderPick.best(listOf(bundled, same)))
        assertSame(bundled, DecoderPick.best(listOf(null, bundled, null)))
        assertNull(DecoderPick.best(listOf(null, null)))
        assertNull(DecoderPick.parse("<html>404</html>", DecoderPick.SITE))
        assertNull(DecoderPick.parse("""{"format":"other","version":9,"fields":[{"key":"mode"}]}""", DecoderPick.SITE))
        assertNull(DecoderPick.parse("""{"format":"birdair-lab-decoder","version":9,"fields":[]}""", DecoderPick.SITE))
        assertFalse(DecoderPick.usable(DecoderSpec(fields = emptyList())))
        assertTrue(DecoderPick.usable(DecoderSpec(format = Decoders.FORMAT, fields = listOf(FieldSpec()))))
    }
}
