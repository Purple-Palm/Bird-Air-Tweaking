package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.WatchFaceState
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
 * «Часы» as the prototype draws it (gallery/ride.html, watch): the watch on v106, «Крупные лампы» chosen and drawn,
 * the five faces as live previews from :dial; and a pick on its way (the watch still on «Классика»). 390×844 and the
 * S21's 360×800, dark and light. ./gradlew :phone:testDebugUnitTest -Pshots --tests '*WatchShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class WatchShotsBase {
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

    private val watch = NavStart(page = Page.WATCH)
    protected fun pick(phone: DialFace, watch: DialFace, watchAt: Long) =
        WatchFaceState(phone = FaceChoice(phone, 2_000, FaceChoice.PHONE), watch = FaceChoice(watch, watchAt, FaceChoice.WATCH))

    @Test fun watchPage() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        Scenes.watch()   // its hello is fresh: the replay moved the clock 1470 s on
        ScooterRepo.face.value = pick(DialFace.STACK, DialFace.STACK, 2_000)
        shot.setContent("22:06", watch, SystemState())
        shot.both("watch")
        rule.onNodeWithText("на часах: Крупные лампы ✓").assertExists()
        rule.onNodeWithText("На часах v106, на телефоне v108").assertExists()
        rule.onNodeWithText("Galaxy Watch Ultra").assertExists()
        rule.onNodeWithText("показывает поездку", substring = true).assertExists()
        checkTargets()
    }

    /** The page's end: the version plate, the watch's version, where else the face is chosen. */
    @Test fun watchEnd() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        Scenes.watch()
        ScooterRepo.face.value = pick(DialFace.STACK, DialFace.STACK, 2_000)
        shot.setContent("22:06", watch, SystemState())
        rule.mainClock.autoAdvance = true
        rule.onNodeWithText("Выбор — здесь", substring = true).performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        shot.settle(3)
        shot.both("watch-end")
        rule.onNodeWithText("Версия на часах: 6-b71d0e3 (v106)", substring = true).assertIsDisplayed()
        checkTargets()
    }

    /** Picked here, the watch still draws the old one (it hears on its next connection). */
    @Test fun watchPending() {
        Scenes.idle(); Scenes.update(Upd.NONE)
        Scenes.watch(old = false)
        ScooterRepo.face.value = pick(DialFace.RING, DialFace.CLASSIC, 1_000)
        shot.setContent("22:25", watch, SystemState())
        shot.both("watch-pending")
        rule.onNodeWithText("отправляю на часы…").assertExists()
        rule.onNodeWithText("На часах пока «Классика» — сменится, как только часы на связи").assertExists()
    }

    /** A tile is the pick: one tap, and the watch gets it. */
    @Test fun tapPicksTheFace() {
        Scenes.parked()
        shot.setContent("22:06", watch, SystemState())
        rule.onNodeWithContentDescription("Циферблат «Кольцо»").performClick()
        shot.settle(2)
        assertEquals(DialFace.RING, ScooterRepo.face.value.selected)
        rule.onNodeWithText("Кольцо: ", substring = true).assertExists()
        rule.onNodeWithText("Открыть на часах").performClick()
        assertTrue("openOnWatch" in shot.ui.calls)
    }

    private fun dp(px: Float) = px / rule.activity.resources.displayMetrics.density

    protected fun checkTargets() {
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = n.touchBoundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            // a row the page's edge cuts counts by its own size
            val w = maxOf(dp(b.width), dp(n.size.width.toFloat()))
            val h = maxOf(dp(b.height), dp(n.size.height.toFloat()))
            val what = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
            assertTrue("target «$what» $w×$h dp", w >= 47.5f && h >= 47.5f)
        }
    }
}

@Config(sdk = [34], qualifiers = Size.P390)
class WatchShots : WatchShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class WatchShotsS21 : WatchShotsBase() {
    /** A 1,3 font: the tiles grow with their names, nothing is cut. */
    @Test fun watchFontScale() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        Scenes.watch()
        ScooterRepo.face.value = pick(DialFace.STACK, DialFace.STACK, 2_000)
        shot.setContent("22:06", NavStart(page = Page.WATCH), SystemState(), fontScale = 1.3f)
        shot.both("watch", suffix = "-s21-fs13")
        checkTargets()
    }
}
