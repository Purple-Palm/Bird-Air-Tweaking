package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import io.github.purplepalm.birdair.phone.ui.watch.faceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Циферблат часов»: the newest pick of the phone and the watch, and what the card says about the watch. */
class FacesTest {
    private fun p(f: DialFace, at: Long) = FaceChoice(f, at, FaceChoice.PHONE)
    private fun w(f: DialFace, at: Long) = FaceChoice(f, at, FaceChoice.WATCH)

    @Test fun theNewestPickWins() {
        // nothing picked, the watch not heard: «Крупные лампы»
        assertEquals(DialFace.STACK, WatchFaceState().selected)
        assertFalse(WatchFaceState().applied)
        // a v108 watch updated: it reports the default, never picked
        assertEquals(DialFace.STACK, WatchFaceState(watch = w(DialFace.STACK, 0)).selected)
        assertTrue(WatchFaceState(watch = w(DialFace.STACK, 0)).applied)
        // picked here; the watch still draws the old one until it hears
        val s = WatchFaceState(phone = p(DialFace.RING, 2_000), watch = w(DialFace.STACK, 0))
        assertEquals(DialFace.RING, s.selected)
        assertFalse(s.applied)
        assertEquals("На часах пока «Крупные лампы» — сменится, как только часы на связи", faceStatus(s))
        // it took it (its report carries the pick's time)
        val ok = s.copy(watch = w(DialFace.RING, 2_000))
        assertTrue(ok.applied)
        assertEquals("На часах: «Кольцо»", faceStatus(ok))
        // picked later on the watch: the phone shows that one
        val later = ok.copy(watch = w(DialFace.CLASSIC, 3_000))
        assertEquals(DialFace.CLASSIC, later.selected)
        assertTrue(later.applied)
        assertEquals("Часы ещё не ответили — возьмут выбор, когда включатся", faceStatus(WatchFaceState(phone = p(DialFace.GROW, 1))))
    }
}
