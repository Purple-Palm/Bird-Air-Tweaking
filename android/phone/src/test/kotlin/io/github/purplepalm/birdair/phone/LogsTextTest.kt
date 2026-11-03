package io.github.purplepalm.birdair.phone

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** «Логи»: the rows, their tags while «Отправить все» runs, the pinned button, the headers, the GitHub screen's answers. */
class LogsTextTest {
    private val zone = TimeZone.getDefault()
    @Before fun la() = TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    @After fun back() = TimeZone.setDefault(zone)

    private val day = 1_792_557_741_000L // 20.10.2026 21:42:21 in LA
    private val cur = LocalLog("20261020-214221-do-otsechki-do-nulya.json.gz", 473_088, day, current = true)
    private val app = LocalLog("20261020-195512-app.json.gz", 120_832, day - 3_000_000)
    private val charge = LocalLog("20261020-181544-zaryadka.json.gz", 1_153_434, day - 6_000_000)
    private val rows = listOf(cur, app, charge)

    @Test fun sizes() {
        assertEquals("462 КБ", LogsText.size(473_088))
        assertEquals("118 КБ", LogsText.size(120_832))
        assertEquals("1,1 МБ", LogsText.size(1_153_434))
        assertEquals("0 КБ", LogsText.size(0))
        assertEquals("from 1000 КБ in МБ", "1,0 МБ", LogsText.size(1_024_000))
    }

    @Test fun entries() {
        assertEquals("9806 записей", LogsText.entries(9806))
        assertEquals("92${Fmt.NBSP}417 записей", LogsText.entries(92_417))
        assertEquals("39${Fmt.NBSP}251 запись", LogsText.entries(39_251))
        assertEquals("2 записи", LogsText.entries(2))
        assertEquals("11 записей", LogsText.entries(11))
    }

    @Test fun startFromTheName() {
        assertEquals(LogsText.Start("20.10 21:42", "21:42"), LogsText.start(cur.name))
        assertNull(LogsText.start("log.json.gz"))
        assertNull("not a date", LogsText.start("20261340-256199-x.json.gz"))
    }

    @Test fun subLines() {
        assertEquals("the running one: since", "462 КБ · с 21:42", LogsText.sub(cur, null))
        assertEquals("118 КБ · 20.10 19:55 · 9806 записей", LogsText.sub(app, 9806))
        assertEquals("count not read yet", "1,1 МБ · 20.10 18:15", LogsText.sub(charge, null))
        assertEquals("stopped: the day", "462 КБ · 20.10 21:42 · 39${Fmt.NBSP}251 запись", LogsText.sub(cur.copy(current = false), 39_251))
    }

    @Test fun tagsNotSending() {
        assertEquals(listOf(LogsText.Tag.REC, LogsText.Tag.NONE, LogsText.Tag.NONE), LogsText.tags(rows, rows, busy = false) { false })
    }

    @Test fun tagsWhileSendingGoOldestFirst() {
        val none = LogsText.tags(rows, rows, busy = true) { false }
        assertEquals("the oldest first; the running one keeps its tag", listOf(LogsText.Tag.REC, LogsText.Tag.NONE, LogsText.Tag.SENDING), none)
        val one = LogsText.tags(rows, rows, busy = true) { it.name == charge.name }
        assertEquals(listOf(LogsText.Tag.REC, LogsText.Tag.SENDING, LogsText.Tag.SENT), one)
        // the recording's own part left the unsent list after its upload: still listed, «отправлен»
        val run = LogsText.runRows(rows, listOf(app, charge))
        assertEquals(rows, run)
        val gone = LogsText.tags(run, listOf(app, charge), busy = true) { it.name == charge.name }
        assertEquals(listOf(LogsText.Tag.SENT, LogsText.Tag.SENDING, LogsText.Tag.SENT), gone)
        // saved again meanwhile (a newer copy in the list): the fresh one counts
        val again = cur.copy(size = 480_000, mtime = day + 5_000)
        assertEquals(listOf(again, app, charge), LogsText.runRows(rows, listOf(again, app, charge)))
    }

    @Test fun pinnedButton() {
        val unsent = DashText.LogsTile(DashText.LogsKind.UNSENT, 3, 0, null)
        val sending = DashText.LogsTile(DashText.LogsKind.SENDING, 3, 1, null)
        val sent = DashText.LogsTile(DashText.LogsKind.SENT, 3, 3, "7c1e2a9")
        assertEquals(LogsText.Send(LogsText.SendKind.ALL, "Отправить все (3)", true), LogsText.send(unsent, null, true, false))
        val s = LogsText.send(sending, null, true, false)
        assertEquals("Отправляю 2 из 3…", s.label)
        assertEquals(1f / 3, s.progress!!, 1e-6f)
        assertEquals(LogsText.SendKind.COMMIT, LogsText.send(sent, "https://example.org/c", false, false).kind)
        assertEquals("no link: nothing to open", LogsText.SendKind.DONE, LogsText.send(sent, null, false, false).kind)
        assertEquals(LogsText.Send(LogsText.SendKind.CURRENT, "Отправить текущий", true), LogsText.send(null, null, true, false))
        assertEquals(LogsText.Send(LogsText.SendKind.CURRENT, "Отправляю…", false), LogsText.send(null, null, true, true))
        assertEquals(LogsText.Send(LogsText.SendKind.DONE, "Всё на GitHub", false), LogsText.send(null, null, false, false))
    }

    @Test fun headers() {
        assertEquals("38${Fmt.NBSP}120 записей · часть 1", LogsText.recording(true, 38_120, 1, day, false))
        assertEquals("лог 20.10 21:42 сохранён", LogsText.recording(false, 0, 1, day, true))
        assertEquals("лог 20.10 21:42", LogsText.recording(false, 0, 1, day, false))
        assertNull(LogsText.recording(false, 0, 1, null, false))
        assertEquals("в этой записи: 3", LogsText.marks(3))
        val gh = GhConfig()
        assertEquals("Purple-Palm/Bird-Air-Tweaking · main/logs · токен сохранён", LogsText.ghSub(gh, true))
        assertEquals("Purple-Palm/Bird-Air-Tweaking · main · нет токена", LogsText.ghSub(gh.copy(folder = "/"), false))
    }

    @Test fun fileHint() {
        assertEquals("Попадёт в лог и в имя файла: do-otsechki-do-nulya", LogsText.fileHint("до отсечки, до нуля", null, "scooter", null))
        assertEquals("blank: the default", "Попадёт в лог и в имя файла: log", LogsText.fileHint("  ", null, null, null))
        assertEquals("the device name stays out of the file name", "Попадёт в лог и в имя файла: scooter-fly",
            LogsText.fileHint("BirdAir-7731 FLY", null, "BirdAir-7731", "AA:BB"))
        assertEquals("Имя файла закреплено при первой отправке: 20261020-214221-x", LogsText.fileHint("y", "20261020-214221-x", null, null))
    }

    @Test fun emptyList() {
        val gh = GhConfig()
        val now = day + 600_000
        val done = LogStatus(bulkDone = 3, bulkTotal = 3, bulkCommit = "7c1e2a9", bulkUrl = "u", bulkAt = now - 10_000)
        assertEquals(LogsText.Result(LogsText.Tone.GOOD, "Отправлено 3 лога · коммит 7c1e2a9", "main/logs · только что"), LogsText.empty(done, gh, now, now))
        assertEquals("main/logs · в 21:50", LogsText.empty(done.copy(bulkAt = now - 120_000), gh, now, now).text)
        val info = LogsText.empty(done.copy(bulkAt = now - DashText.SENT_SHOW_MS - 1), gh, now, day)
        assertEquals(LogsText.Result(LogsText.Tone.INFO, "Все сохранённые логи уже на GitHub", "main/logs · проверено 21:42"), info)
    }

    @Test fun checkAnswers() {
        val ok = LogsText.checked("Доступ есть: Purple-Palm/Bird-Air-Tweaking, ветка main. Право записи проверится при первой отправке.")
        assertEquals(LogsText.Tone.GOOD, ok.tone)
        assertEquals("Доступ есть", ok.title)
        assertEquals("Purple-Palm/Bird-Air-Tweaking, ветка main. Право записи проверится при первой отправке.", ok.text)
        assertEquals(LogsText.Tone.WARN, LogsText.checked("Доступ есть: a/b, ветка main. Но у твоей учётной записи нет права записи.").tone)
        assertEquals(LogsText.Result(LogsText.Tone.BAD, "Сначала вставь токен."), LogsText.checked("Сначала вставь токен."))
    }

    @Test fun savingTheToken() {
        val gh = GhConfig()
        val fake = "abcDEF123_xyz789"
        assertEquals(LogsText.Tone.BAD, LogsText.saveToken("", false, gh).result.tone)
        val keep = LogsText.saveToken(" ", true, gh)
        assertNull("nothing pasted keeps the saved one", keep.token)
        assertEquals(LogsText.Tone.GOOD, keep.result.tone)
        val pasted = LogsText.saveToken(" $fake​\n", false, gh)
        assertEquals("cleaned as before", fake, pasted.token)
        assertEquals("Токен сохранён", pasted.result.title)
        val bad = LogsText.saveToken("abcЖ123", true, gh)
        assertNull(bad.token)
        assertTrue(bad.result.title.startsWith("В токене есть буквы не из латиницы"))
    }
}
