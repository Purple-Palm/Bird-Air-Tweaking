package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.phone.UpdateCardText.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Настройки' words: the version lines, the update card in every phase and the ride, the odometer field. */
class SettingsTextTest {
    private val v108 = UpdateInfo(108, "8-a41290d", "a41290d")
    private val v109 = UpdateInfo(109, "9-c3f19d2", "c3f19d2e41b7", phoneSize = 19_293_798, notes = "циферблаты часов")
    private val apk = 19_293_798L

    @Test fun versionLines() {
        assertEquals("Bird Air 8-a41290d (v108)", UpdateCardText.app("8-a41290d", 108))
        assertEquals("a local build has no name", "Bird Air v1", UpdateCardText.app("", 1))
        assertEquals("В релизе android-latest: v109 (9-c3f19d2) · проверено 21.10 19:28", UpdateCardText.release(v109, 108, "21.10 19:28"))
        assertEquals("В релизе android-latest: v108 — она и стоит · проверено 21.10 19:28", UpdateCardText.release(v108, 108, "21.10 19:28"))
        assertEquals("В релизе android-latest: ещё не проверено", UpdateCardText.release(null, 108, null))
        assertEquals("read from prefs without a time", "В релизе android-latest: v109 (9-c3f19d2)", UpdateCardText.release(v109, 108, null))
    }

    @Test fun titleAndChange() {
        assertEquals("Есть обновление: v109", UpdateCardText.title(UpdPhase.AVAILABLE, 109))
        assertEquals("Скачано: v109", UpdateCardText.title(UpdPhase.READY, 109))
        assertEquals("Сейчас 8-a41290d (v108) → 9-c3f19d2 (v109) · 18,4 МБ", UpdateCardText.change("8-a41290d", 108, 109, v109, apk))
        // a kept APK from a new process: version.json not read yet
        assertEquals("Сейчас 8-a41290d (v108) → v109", UpdateCardText.change("8-a41290d", 108, 109, null, null))
        // the kept APK is newer than what the release says: no foreign name on it
        assertEquals("Сейчас 8-a41290d (v108) → v110 · 18,4 МБ", UpdateCardText.change("8-a41290d", 108, 110, v109, apk))
        assertEquals(109, UpdateCardText.code(UpdPhase.READY, UpdateState(readyCode = 109)))
        assertEquals(109, UpdateCardText.code(UpdPhase.AVAILABLE, UpdateState(latest = v109, readyCode = 108)))
    }

    @Test fun availableSaysWhenItInstalls() {
        val u = UpdateState(latest = v109)
        fun n(running: Boolean, moving: Boolean) = UpdateCardText.note(UpdPhase.AVAILABLE, u, running, moving)!!.title
        assertEquals("Скачаю 18,4 МБ и сразу открою установщик.", n(running = false, moving = false))
        assertEquals("Скачаю сейчас; поставишь, когда будешь готов: связь оборвётся.", n(running = true, moving = false))
        assertEquals("Скачаю сейчас — поставишь на стоянке.", n(running = true, moving = true))
        assertEquals("Скачаю и сразу открою установщик.", UpdateCardText.note(UpdPhase.AVAILABLE, UpdateState(latest = v108.copy(versionCode = 109)), false, false)!!.title)
    }

    @Test fun downloadingShowsMegabytes() {
        val u = UpdateState(latest = v109, downloading = true, gotBytes = apk * 63 / 100, totalBytes = apk)
        assertEquals(Note(Tone.TEXT, "bird-air-phone.apk: 11,6 из 18,4 МБ"), UpdateCardText.note(UpdPhase.DOWNLOADING, u, running = false, moving = false))
        assertEquals("bird-air-phone.apk: 11,6 из 18,4 МБ · установщик сам не откроется, пока идёт запись",
            UpdateCardText.note(UpdPhase.DOWNLOADING, u, running = true, moving = false)!!.title)
        // the server sends no length and version.json has no size
        val blind = UpdateState(latest = v108.copy(versionCode = 109), downloading = true, gotBytes = 3 * 1048576L)
        assertEquals("bird-air-phone.apk: 3,0 МБ", UpdateCardText.note(UpdPhase.DOWNLOADING, blind, false, false)!!.title)
    }

    @Test fun readyByTheRide() {
        val u = UpdateState(latest = v109, readyCode = 109)
        val ride = UpdateCardText.note(UpdPhase.READY, u, running = true, moving = true)!!
        assertEquals(Tone.CRIT, ride.tone)
        assertEquals("Ты едешь — установка недоступна", ride.title)
        assertEquals("Она закроет приложение и оборвёт связь с самокатом. Остановись: на стоянке появится плитка «Установить».", ride.text)
        val parked = UpdateCardText.note(UpdPhase.READY, u, running = true, moving = false)!!
        assertEquals(Tone.WARN, parked.tone)
        assertEquals("Связь с самокатом оборвётся", parked.title)
        val idle = UpdateCardText.note(UpdPhase.READY, u, running = false, moving = false)!!
        assertEquals(Note(Tone.OK, "Скачано (v109). Закрыл установщик? «Установить» возьмёт скачанный файл."), idle)
        assertNull(UpdateCardText.note(UpdPhase.NONE, UpdateState(), false, false))
        assertNull(UpdateCardText.note(UpdPhase.CHECKING, UpdateState(checking = true), false, false))
    }

    @Test fun theLineWhereThereIsNoCard() {
        assertEquals("Новее нет: v108 — последняя сборка." to false,
            UpdateCardText.line(UpdPhase.NONE, UpdateState(latest = v108, message = "Новее нет: v108 — последняя сборка.")))
        assertEquals("the button says «Проверяю…»", null, UpdateCardText.line(UpdPhase.CHECKING, UpdateState(checking = true, message = "Проверяю обновления…")))
        val offline = "Не удалось прочитать version.json релиза android-latest: нет сети или CI его ещё не выложил."
        assertEquals(offline to true, UpdateCardText.line(UpdPhase.NONE, UpdateState(message = offline, err = true)))
        assertEquals("the card says it", null, UpdateCardText.line(UpdPhase.AVAILABLE, UpdateState(latest = v109, message = "Есть обновление: v109.")))
        assertNull(UpdateCardText.line(UpdPhase.NONE, UpdateState()))
    }

    @Test fun odometerInput() {
        assertEquals(1464.6, OdometerInput.parse("1464,6")!!, 1e-9)
        assertEquals(1464.6, OdometerInput.parse(" 1464.6 ")!!, 1e-9)
        // the field is filled with Fmt.n: a no-break space between thousands from 10 000
        assertEquals(12345.6, OdometerInput.parse(Fmt.n(12345.6, 1))!!, 1e-9)
        assertEquals(0.0, OdometerInput.parse("0")!!, 0.0)
        assertNull(OdometerInput.parse(""))
        assertNull(OdometerInput.parse("−5"))
        assertNull(OdometerInput.parse("-5"))
        assertNull(OdometerInput.parse("1,2,3"))
        assertNull(OdometerInput.parse("2000000"))
        assertNull(OdometerInput.parse("NaN"))
    }

    private fun Note(tone: Tone, title: String) = UpdateCardText.Note(tone, title)
}
