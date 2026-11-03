package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.LogPeek
import io.github.purplepalm.birdair.phone.LogStore
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.SentLog
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.shots.Scenes.Logs
import io.github.purplepalm.birdair.phone.shots.Scenes.Upd
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import io.github.purplepalm.birdair.phone.ui.shell.Page
import io.github.purplepalm.birdair.phone.ui.shell.Sub
import io.github.purplepalm.birdair.phone.ui.shell.TAG_BOTTOM
import io.github.purplepalm.birdair.phone.ui.shell.TAG_SNACK
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.TimeZone
import java.util.zip.GZIPOutputStream

/**
 * «Логи» and its GitHub screen as the prototype draws them (gallery/ride.html: logs-ride, logs-sending, logs-sent, github),
 * at 390×844 ([LogsShots]) and 360×800 ([LogsShotsS21]), dark and light; each checks its targets (≥ 48 dp), the markers
 * and the pinned buttons. ./gradlew :phone:testDebugUnitTest -Pshots --tests '*LogsShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LogsShotsBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var shot: Shooter
    private val zone = TimeZone.getDefault()
    private val sys = SystemState()
    private val written = ArrayList<File>()
    private val sentNames = ArrayList<String>()

    @Before fun setUp() {
        shot = Shooter(rule)
        Scenes.base(rule.activity.applicationContext)
        LogPeek.clear()
        // the two earlier files on disk, so their rows read their entry counts like the prototype's
        disk("20261020-195512-app.json.gz", 9806)
        disk("20261020-181544-zaryadka.json.gz", 92_417)
    }

    @After fun tearDown() {
        for (f in written) f.delete()
        for (n in sentNames) ScooterRepo.prefs.setSent(n, null)
        LogPeek.clear()
        Updater.installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        TimeZone.setDefault(zone)
    }

    /** A gzipped log of [n] entries in the app's log folder. */
    private fun disk(name: String, n: Int) {
        val f = File(LogStore.dir(rule.activity.applicationContext), name)
        GZIPOutputStream(f.outputStream()).bufferedWriter().use { w ->
            w.write("{\"format\":\"birdair-lab-log\",\"entries\":[")
            for (i in 0 until n) { if (i > 0) w.write(","); w.write("{\"t\":$i,\"k\":\"rx\"}") }
            w.write("]}")
        }
        written += f
    }

    /** The recording's header as the service keeps it: entries and part of the part being written. */
    private fun recorded() {
        val s = ScooterRepo.session ?: return
        ScooterRepo.log.update { it.copy(entries = s.recorder.size, part = s.part.number) }
    }

    /** «Отправить все» half way: the oldest file is on GitHub, the next one going. */
    private fun oldestSent() {
        val f = ScooterRepo.unsent.value.minBy { it.mtime }
        ScooterRepo.prefs.setSent(f.name, SentLog("logs/${f.base}-app.json.gz", "0".repeat(40), ScooterRepo.gh.value.dest, f.size, f.mtime, null))
        sentNames += f.name
    }

    /** A tap through the click action: below the fold too (scrolling there animates, and the frame clock is held for the snackbars). */
    private fun SemanticsNodeInteraction.tap() = performSemanticsAction(SemanticsActions.OnClick)

    private fun field(label: String): SemanticsNodeInteraction = rule.onNode(hasSetTextAction() and hasText(label, substring = true))

    // -- the prototype's states

    @Test fun logsRide() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT); recorded()
        shot.setContent("22:12", NavStart(page = Page.LOGS), sys)
        shot.both("logs-ride")
        rule.onNodeWithText("НЕОТПРАВЛЕННЫЕ (3)").assertExists()
        rule.onNodeWithText("● идёт запись").assertExists()
        rule.onNodeWithText("462 КБ · с 21:42", substring = true).assertExists()
        rule.onNodeWithText("9806 записей", substring = true).assertExists()
        rule.onNodeWithText("ИДЁТ ЗАПИСЬ").assertExists()
        rule.onNodeWithText("Попадёт в лог и в имя файла: do-otsechki-do-nulya").assertExists()
        rule.onNodeWithText("Отправить все (3)").performClick()
        assertTrue("sendAll" in shot.ui.calls)
        assertTrue("the page re-reads the list when it opens", "refreshLogs" in shot.ui.calls)
        checkTargets()
    }

    @Test fun logsSending() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.SENDING); recorded(); oldestSent()
        shot.setContent("22:06", NavStart(page = Page.LOGS), sys)
        shot.both("logs-sending")
        rule.onNodeWithText("отправлен").assertExists()
        rule.onNodeWithText("отправляю…").assertExists()
        rule.onNodeWithText("Отправляю 2 из 3…").assertIsNotEnabled()
    }

    @Test fun logsSent() {
        Scenes.idle(); Scenes.update(Upd.NONE); Scenes.logs(Logs.SENT)
        ScooterRepo.log.update { it.copy(savedName = "20261020-214221-do-otsechki-do-nulya.json.gz") }
        shot.setContent("22:25", NavStart(page = Page.LOGS), sys)
        shot.both("logs-sent")
        rule.onNodeWithText("Отправлено 3 лога · коммит 7c1e2a9").assertExists()
        rule.onNodeWithText("ЗАПИСЬ ОСТАНОВЛЕНА").assertExists()
        rule.onNodeWithText("лог 20.10 21:42 сохранён").assertExists()
        rule.onNodeWithText("Открыть коммит").assertExists()
        rule.onNodeWithText("Метка").assertDoesNotExist()
        checkTargets()
    }

    @Test fun github() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:06", NavStart(page = Page.LOGS, sub = Sub.GITHUB), sys)
        shot.both("github")
        rule.onNodeWithText("Забыть токен").assertExists()
        checkTargets()
    }

    // -- behaviour

    /** While recording a word is one tap: a marker now, the snackbar says when; the own marker goes the same way. */
    @Test fun markerWords() {
        Scenes.ride(); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(page = Page.LOGS), sys)
        val before = ScooterRepo.markers.value.size
        rule.onNodeWithText("в этой записи: $before").assertExists()
        rule.onNodeWithText("Тормоз").tap()
        shot.settle(2)
        assertEquals("Тормоз", ScooterRepo.markers.value.last().label)
        rule.onNodeWithText("«Тормоз» ·", substring = true).assertExists()
        rule.onNodeWithText("в этой записи: ${before + 1}").assertExists()
        field("Своя метка").performTextInput("горка")
        rule.onNodeWithText("Поставить").tap()
        shot.settle(2)
        assertEquals("горка", ScooterRepo.markers.value.last().label)
        checkSnackAbovePin()
    }

    /** The pinned «Метка»: one tap, then the snackbar asks for the word above the pinned row. */
    @Test fun pinnedMark() {
        Scenes.ride(); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(page = Page.LOGS), sys)
        val n = ScooterRepo.markers.value.size
        rule.onNodeWithText("Метка").performClick()
        shot.settle(2)
        assertEquals(n + 1, ScooterRepo.markers.value.size)
        rule.onNodeWithText("— подписать?", substring = true).assertExists()
        checkSnackAbovePin()
    }

    /** Stopped: the words wait for the next recording. */
    @Test fun noMarkersWhenStopped() {
        Scenes.idle(); Scenes.logs(Logs.NONE)
        shot.setContent("22:25", NavStart(page = Page.LOGS), sys)
        val n = ScooterRepo.markers.value.size
        rule.onNodeWithText("Газ").assertIsNotEnabled()
        rule.onNodeWithText("Поставить").assertIsNotEnabled()
        assertEquals(n, ScooterRepo.markers.value.size)
        rule.onNodeWithText("Все сохранённые логи уже на GitHub").assertExists()
        rule.onNodeWithText("Всё на GitHub").assertIsNotEnabled()
    }

    /** The GitHub screen: a token with Cyrillic is refused; «Забыть токен» forgets it. */
    @Test fun githubToken() {
        Scenes.parked()
        shot.setContent("22:06", NavStart(page = Page.LOGS, sub = Sub.GITHUB), sys)
        field("Токен сохранён").performTextInput("abcЖ123")
        // the page's own «Сохранить» (the log) lies under this layer: the screen's is the last one
        rule.onAllNodesWithText("Сохранить").onLast().performClick()
        shot.settle(1)
        rule.onNodeWithText("В токене есть буквы не из латиницы", substring = true).assertExists()
        assertTrue(ScooterRepo.hasToken.value)
        rule.onNodeWithText("Забыть токен").tap()
        shot.settle(1)
        assertFalse(ScooterRepo.hasToken.value)
        rule.onNodeWithText("Токен удалён из приложения.").assertExists()
        rule.onNodeWithText("Забыть токен").assertDoesNotExist()
        field("Fine-grained токен").assertExists()
    }

    // -- checks

    private fun dp(px: Float) = px / rule.activity.resources.displayMetrics.density

    /** Every clickable at least 48 dp both ways: its touch bounds, or its own size where the page's scroll cuts it off. */
    private fun checkTargets() {
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = n.touchBoundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            val cut = n.boundsInRoot.height < n.size.height - 0.5f || n.boundsInRoot.width < n.size.width - 0.5f
            val w = if (cut) n.size.width.toFloat() else b.width
            val h = if (cut) n.size.height.toFloat() else b.height
            val what = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
            assertTrue("target «$what» ${dp(w)}×${dp(h)} dp", dp(w) >= 47.5f && dp(h) >= 47.5f)
        }
    }

    private fun checkSnackAbovePin() {
        val snack = rule.onNodeWithTag(TAG_SNACK).fetchSemanticsNode().boundsInRoot
        val pin = rule.onNodeWithTag(TAG_BOTTOM).fetchSemanticsNode().boundsInRoot
        assertTrue("the snackbar is above the pinned row: ${snack.bottom} > ${pin.top}", snack.bottom <= pin.top + 0.5f)
    }
}

@Config(sdk = [34], qualifiers = Size.P390)
class LogsShots : LogsShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class LogsShotsS21 : LogsShotsBase()
