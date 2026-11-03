package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.LampsText
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.shots.Scenes.Logs
import io.github.purplepalm.birdair.phone.shots.Scenes.Upd
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import io.github.purplepalm.birdair.phone.ui.shell.Page
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * «Приборка» pulled up, as the prototype draws it (gallery/ride.html, lamps-*): riding at 1800 s of the 20.10 log, and
 * after the whole recording; plus the BMS's own conditions of that ride (the cut at 1813,5 s, the silent BMS at 1826 s).
 * 390×844 ([LampsShots]) and 360×800 ([LampsShotsS21]), dark and light, into build/shots/. Each checks the glove targets.
 * ./gradlew :phone:testDebugUnitTest -Pshots --tests '*LampsShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LampsShotsBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var shot: Shooter
    private val zone = TimeZone.getDefault()

    @Before fun setUp() {
        shot = Shooter(rule)
        Scenes.base(rule.activity.applicationContext)
    }

    @After fun tearDown() {
        Updater.installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        TimeZone.setDefault(zone)
    }

    private val page = NavStart(page = Page.LAMPS)

    @Test fun lampsRide() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", page, SystemState())
        shot.both("lamps-ride")
        rule.onNodeWithText("⛔ Сейчас отключится", substring = true).assertExists()
        rule.onNodeWithText("СОВЕТЧИК").assertExists()
        rule.onNodeWithContentDescription("Лампа «Заряд»: горит красным", substring = true).assertExists()
        rule.onNodeWithContentDescription("Лампа «ECO»: не горит").assertExists()
        targets(rule)
    }

    @Test fun lampsIdle() {
        Scenes.idle(); Scenes.update(Upd.NONE); Scenes.logs(Logs.NONE)
        shot.setContent("22:25", page, SystemState())
        shot.both("lamps-idle")
        rule.onNodeWithText("Нет живых данных — подключись к самокату").assertExists()
        rule.onNodeWithText("Батарея (последнее)").assertExists()
        rule.onNodeWithText("после отключения BMS", substring = true).assertExists()
        rule.onNodeWithText("ЖУРНАЛ ЛАМП", substring = true).assertExists()
        rule.onNodeWithText("· 20.10", substring = true).assertExists()
        targets(rule)
    }

    /** 20.10, 1.1 s after the BMS's last frame: «BMS отключает батарею» (b5 0x02) on the charge lamp. */
    @Test fun lampsBmsCut() {
        Scenes.replay(1_813_500L); Scenes.update(Upd.NONE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", page, SystemState())
        shot.both("lamps-bms-cut")
        rule.onNodeWithText("⛔ BMS отключает батарею", substring = true).assertExists()
    }

    /** 20.10, 1826 s: the controller's 0x26 («Нет связи с BMS») and the dash answering with no frames on the link lamp. */
    @Test fun lampsBmsSilent() {
        Scenes.replay(1_826_000L); Scenes.update(Upd.NONE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", page, SystemState())
        shot.both("lamps-bms-silent")
        rule.onNodeWithContentDescription("Лампа «Связь»: горит", substring = true).assertExists()
    }

    /** A lamp of the grid opens its sheet: what it means and when it was lit. */
    @Test fun tapOpensTheSheet() {
        Scenes.ride()
        shot.setContent("22:12", page, SystemState())
        rule.onNodeWithContentDescription("Лампа «Заряд»", substring = true).performClick()
        shot.settle(4)
        rule.onNodeWithText("КОГДА ГОРЕЛА").assertExists()
    }

    /** «Весь журнал · N» opens the rest, «Свернуть журнал» folds it again. */
    @Test fun wholeJournal() {
        Scenes.ride()
        shot.setContent("22:12", page, SystemState())
        val n = ScooterRepo.journal.value.size
        assertTrue("the 20.10 ride lit more than ${LampsText.JOURNAL_ROWS} lamps by 1800 s: $n", n > LampsText.JOURNAL_ROWS)
        // below the fold; performScrollTo would spin with the frame clock paused: the click action itself
        rule.onNodeWithText("Весь журнал · $n").performSemanticsAction(SemanticsActions.OnClick)
        shot.settle(2)
        rule.onNodeWithText("Свернуть журнал").assertExists()
    }
}

@Config(sdk = [34], qualifiers = Size.P390)
class LampsShots : LampsShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class LampsShotsS21 : LampsShotsBase()

/** The whole page at once on a tall 390 dp screen (lamps-*-full), for looking below the fold: battery and journal. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1900dp-xhdpi")
class LampsShotsFull {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var shot: Shooter
    private val zone = TimeZone.getDefault()

    @Before fun setUp() {
        shot = Shooter(rule)
        Scenes.base(rule.activity.applicationContext)
    }

    @After fun tearDown() {
        Updater.installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        TimeZone.setDefault(zone)
    }

    @Test fun ride() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(page = Page.LAMPS), SystemState())
        shot.both("lamps-ride-full")
        rule.onNodeWithText("Ток", substring = true).assertExists()
    }

    @Test fun idle() {
        Scenes.idle(); Scenes.update(Upd.NONE); Scenes.logs(Logs.NONE)
        shot.setContent("22:25", NavStart(page = Page.LAMPS), SystemState())
        shot.both("lamps-idle-full")
    }
}

/** Every clickable at least 48 dp both ways (its touch bounds). */
private fun targets(rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    val density = rule.activity.resources.displayMetrics.density
    for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
        val b = n.touchBoundsInRoot
        if (b.width <= 0f || b.height <= 0f) continue
        val what = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
        assertTrue("target «$what» ${b.width / density}×${b.height / density} dp", b.width / density >= 47.5f && b.height / density >= 47.5f)
    }
}
