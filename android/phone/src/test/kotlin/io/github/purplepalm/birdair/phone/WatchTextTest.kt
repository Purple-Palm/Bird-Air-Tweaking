package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** «Часы»' words: the status card, the face line, the face's description, the version hint. */
class WatchTextTest {
    private val ultra = WatchStatus(nodes = listOf("Galaxy Watch Ultra"), checked = true, helloAt = 100_000, version = WatchVersion(106, "6-b71d0e3"))

    @Test fun theAppOnTheWatchIsOpenWhileItSaysHello() {
        assertTrue(WatchText.open(ultra, 100_000))
        assertTrue("hello every 30 s while data flows", WatchText.open(ultra, 100_000 + 40_000))
        assertFalse(WatchText.open(ultra, 100_000 + Versions.WATCH_ACTIVE_MS + 1))
        assertFalse("never said hello", WatchText.open(ultra.copy(helloAt = 0), 5_000))
    }

    @Test fun statusCard() {
        assertEquals(WatchText.Status("Galaxy Watch Ultra", true, "показывает поездку"), WatchText.status(ultra, open = true, running = true))
        assertEquals("оживёт, когда подключишься", WatchText.status(ultra, open = true, running = false).line)
        assertEquals("открой Bird Air на часах", WatchText.status(ultra, open = false, running = true).line)
        assertEquals(WatchText.Status("Часы", false, "проверяю…"), WatchText.status(WatchStatus(), open = false, running = false))
        assertFalse(WatchText.status(WatchStatus(checked = true), false, false).linked)
        assertEquals("Wear OS недоступен на телефоне", WatchText.status(WatchStatus(available = false, checked = true), false, false).line)
    }

    @Test fun faceLine() {
        val stack = WatchFaceState(phone = FaceChoice(DialFace.STACK, 2_000, FaceChoice.PHONE), watch = FaceChoice(DialFace.STACK, 2_000, FaceChoice.WATCH))
        assertEquals("на часах: Крупные лампы ✓", WatchText.faceLine(stack, linked = true))
        val picked = stack.copy(phone = FaceChoice(DialFace.RING, 3_000, FaceChoice.PHONE))
        assertEquals("отправляю на часы…", WatchText.faceLine(picked, linked = true))
        assertEquals("часы возьмут, когда включатся", WatchText.faceLine(picked, linked = false))
    }

    @Test fun aboutTheFace() {
        assertTrue(WatchText.about(DialFace.STACK).startsWith("Крупные лампы (обычный): погасших ламп нет;"))
        assertTrue(WatchText.about(DialFace.STACK).endsWith("снизу."))
        assertTrue(WatchText.about(DialFace.RING).startsWith("Кольцо: 12 ламп"))
        for (f in DialFace.entries) assertTrue(f.name, WatchText.about(f).endsWith("."))
    }

    @Test fun hintWhenTheWatchIsBehind() {
        val h = WatchText.hint(WatchVersion(106, "6-b71d0e3"), helloSeen = true, phoneCode = 108)!!
        assertEquals("На часах v106, на телефоне v108", h.title)
        assertEquals("Обнови часы: на часах «Подробно → Обновить часы». Без этого новые циферблаты там не появятся.", h.text)
        assertNull(WatchText.hint(WatchVersion(108, "8-a41290d"), true, 108))
        assertNull("a local build compares with nothing", WatchText.hint(WatchVersion(106, null), true, 1))
        assertEquals("Часы не сообщают версию", WatchText.hint(null, helloSeen = true, phoneCode = 108)!!.title)
        assertNull("not heard at all", WatchText.hint(null, helloSeen = false, phoneCode = 108))
    }

    @Test fun versionLine() {
        assertEquals("Версия на часах: 8-a41290d (v108)", WatchText.version(WatchVersion(108, "8-a41290d"), behind = false))
        assertEquals("Версия на часах: 6-b71d0e3 (v106). Нет кнопки «Обновить часы» — один раз вручную, android/README.md",
            WatchText.version(WatchVersion(106, "6-b71d0e3"), behind = true))
        assertEquals("Версия на часах: 0.50", WatchText.version(WatchVersion(null, "0.50"), behind = false))
        assertNull(WatchText.version(null, behind = false))
    }
}
