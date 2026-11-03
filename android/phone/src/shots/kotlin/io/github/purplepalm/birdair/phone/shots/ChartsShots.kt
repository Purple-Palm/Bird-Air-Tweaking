package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.ChartWindow
import io.github.purplepalm.birdair.phone.ChartsText
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.shots.Scenes.Logs
import io.github.purplepalm.birdair.phone.shots.Scenes.Upd
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import io.github.purplepalm.birdair.phone.ui.shell.Page
import org.junit.After
import org.junit.Assert.assertEquals
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
 * «Графики» pulled up, as the prototype draws it (gallery/ride.html, charts-*): riding at 1800 s of the 20.10 log with
 * the 5-minute window and the readout at 22:12:06, and the whole recording after it ended. 390×844 ([ChartsShots]) and
 * 360×800 ([ChartsShotsS21]), dark and light, into build/shots/. Each checks the glove targets; the readout goes with
 * Back and with a new window. ./gradlew :phone:testDebugUnitTest -Pshots --tests '*ChartsShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ChartsShotsBase {
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

    private val page = NavStart(page = Page.CHARTS)

    /** A touch on the power chart [beforeNowMs] before the end of the 5-minute window that follows the ride. */
    private fun touchBefore(beforeNowMs: Long) {
        val f = 1f - beforeNowMs / 300_000f
        rule.onNodeWithContentDescription("График: Мощность, Вт").performTouchInput {
            val l = 46.dp.toPx()
            val r = 8.dp.toPx()
            down(Offset(l + (width - l - r) * f, height / 2f))
            up()
        }
        shot.settle(3)
    }

    @Test fun chartsRide() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", page, SystemState())
        assertEquals("300", ScooterRepo.settings.value.chartWindow)
        rule.onNodeWithText(ChartsText.HINT).assertExists()
        // 22:12:06,5: 14,5 s before 22:12:21 (1800 s since 21:42:21)
        touchBefore(14_500)
        shot.both("charts-ride")
        rule.onNodeWithText("22:12:06", substring = true).assertExists()
        rule.onNodeWithText("ЭНЕРГИЯ ЗА ОКНО · 5 МИН").assertExists()
        targets(rule)
        // Back drops the readout, the page stays
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        shot.settle(2)
        rule.onNodeWithText(ChartsText.HINT).assertExists()
        rule.onNodeWithText("ПОЕЗДКА · С «ПОДКЛЮЧИТЬ»").assertExists()
    }

    @Test fun chartsIdleAll() {
        Scenes.idle(); Scenes.update(Upd.NONE); Scenes.logs(Logs.NONE)
        Scenes.allWindow()
        shot.setContent("22:25", page, SystemState())
        shot.both("charts-idle-all")
        rule.onNodeWithText("ЭНЕРГИЯ ЗА ОКНО · ВСЯ ПОЕЗДКА").assertExists()
        targets(rule)
    }

    /** The pinned window: «1 мин» takes the window and drops the readout. */
    @Test fun windowDropsTheReadout() {
        Scenes.ride()
        shot.setContent("22:12", page, SystemState())
        touchBefore(20_000)
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText(ChartsText.HINT)).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("1 мин").performClick()
        shot.settle(3)
        assertEquals("60", ScooterRepo.settings.value.chartWindow)
        rule.onNodeWithText(ChartsText.HINT).assertExists()
        rule.onNodeWithText("ЭНЕРГИЯ ЗА ОКНО · 1 МИН").assertExists()
        ScooterRepo.updateUi { it.copy(chartWindow = ChartWindow.norm(null)) }
    }
}

@Config(sdk = [34], qualifiers = Size.P390)
class ChartsShots : ChartsShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class ChartsShotsS21 : ChartsShotsBase()

/** The whole page at once on a tall 390 dp screen (charts-*-full), for looking below the fold: energy and the trip. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp-xhdpi")
class ChartsShotsFull {
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
        shot.setContent("22:12", NavStart(page = Page.CHARTS), SystemState())
        shot.both("charts-ride-full")
        rule.onNodeWithText("Расход поездки").assertExists()
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
