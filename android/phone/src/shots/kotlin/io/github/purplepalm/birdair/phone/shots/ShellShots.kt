package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.DashViews
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ViewPin
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import io.github.purplepalm.birdair.phone.ui.shell.Page
import io.github.purplepalm.birdair.phone.ui.shell.SheetKind
import io.github.purplepalm.birdair.phone.ui.shell.Sub
import io.github.purplepalm.birdair.phone.ui.shell.TAG_BOTTOM
import io.github.purplepalm.birdair.phone.ui.shell.TAG_DOCK
import io.github.purplepalm.birdair.phone.ui.shell.TAG_MAIN
import io.github.purplepalm.birdair.phone.ui.shell.TAG_SNACK
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
import java.util.TimeZone
import io.github.purplepalm.birdair.phone.shots.Scenes.Logs
import io.github.purplepalm.birdair.phone.shots.Scenes.Upd

/**
 * The shell's states as the prototype draws them (gallery/ride.html): the dash views, the sheets, the first launch, at
 * 390×844 ([ShellShots]) and the S21's 360×800 ([ShellShotsS21]), dark and light, into build/shots/. Each also checks
 * the layout: the dock at the same height, «Отключить» above it, every target ≥ 48 dp, the snackbar above the bottom
 * zone, the riding dash without scrolling on the S21. ./gradlew :phone:testDebugUnitTest -Pshots --tests '*ShellShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ShellShotsBase {
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

    private val sys = SystemState()

    // -- the dash

    @Test fun homeRide() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(), sys)
        shot.both("home-ride")
        assertTrue("moving at 1800 s", ScooterRepo.moving.value)
        checkLayout(ride = true)
    }

    @Test fun homeRideMph() {
        Scenes.ride(); Scenes.update(Upd.NONE)
        ScooterRepo.updateUi { it.copy(units = "mph") }
        shot.setContent("22:12", NavStart(), sys)
        shot.both("home-ride-mph")
    }

    @Test fun homeRideMark() {
        Scenes.ride(); Scenes.update(Upd.NONE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(), sys)
        rule.onNodeWithText("Метка").performClick()
        shot.settle(3)
        shot.both("home-ride-mark")
        rule.onNodeWithText("Метка 4", substring = true).assertExists()
        val snack = rule.onNodeWithTag(TAG_SNACK).fetchSemanticsNode().boundsInRoot
        val zone = rule.onNodeWithTag(TAG_BOTTOM).fetchSemanticsNode().boundsInRoot
        assertTrue("the snackbar is above the bottom zone: ${snack.bottom} > ${zone.top}", snack.bottom <= zone.top + 0.5f)
        checkTargets()
    }

    @Test fun homeParked() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:06", NavStart(), sys)
        shot.both("home-parked")
        assertFalse("parked at 1470 s", ScooterRepo.moving.value)
        checkLayout()
    }

    @Test fun homeParkedAvailable() {
        Scenes.parked(); Scenes.update(Upd.AVAILABLE); Scenes.logs(Logs.SENDING)
        shot.setContent("22:06", NavStart(), sys)
        shot.both("home-parked-avail")
    }

    @Test fun homeIdle() {
        Scenes.idle(); Scenes.update(Upd.AVAILABLE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:25", NavStart(), sys)
        shot.both("home-idle")
        checkLayout()
    }

    @Test fun homeIdleDownloading() {
        Scenes.idle(); Scenes.update(Upd.DOWNLOADING); Scenes.logs(Logs.SENT)
        shot.setContent("22:25", NavStart(), sys)
        shot.both("home-idle-dl")
    }

    @Test fun homeSilent() {
        Scenes.silent(); Scenes.update(Upd.NONE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:06", NavStart(), sys)
        shot.both("home-silent")
        assertFalse(ScooterRepo.telemetry.value.streaming)
        checkLayout()
    }

    @Test fun homeSetupPerms() {
        ScooterRepo.status.value = ScooterRepo.status.value.copy(address = null, deviceName = null)
        shot.setContent("22:25", NavStart(), Scenes.setupSys(perms = true))
        shot.both("home-setup-perms")
        rule.onNodeWithText("Нужны разрешения").assertExists()
        checkLayout()
    }

    @Test fun homeSetupBackground() {
        ScooterRepo.status.value = ScooterRepo.status.value.copy(address = null, deviceName = null)
        ScooterRepo.updateUi { it.copy(batteryHintHidden = false) }
        shot.setContent("22:25", NavStart(), Scenes.setupSys(perms = false))
        shot.both("home-setup-bg")
        rule.onNodeWithText("Шаг 3 из 3 · Samsung").assertExists()
    }

    @Test fun homeBulb() {
        Scenes.parked(); Scenes.update(Upd.NONE)
        ScooterRepo.bulbUntil.value = android.os.SystemClock.elapsedRealtime() + 600_000
        shot.setContent("22:06", NavStart(), sys)
        shot.both("home-bulb")
        rule.onNodeWithText("Проверка ламп…").assertExists()
    }

    // -- the sheets

    @Test fun lampSheet() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(sheet = SheetKind.LAMP, lamp = "battery"), sys)
        shot.both("lamp-sheet")
        rule.onNodeWithText("КОГДА ГОРЕЛА").assertExists()
    }

    @Test fun lampSheetTraction() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(sheet = SheetKind.LAMP, lamp = "traction"), sys)
        shot.both("lamp-sheet-traction")
    }

    @Test fun control() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        ScooterRepo.setAllowCommands(true)
        shot.setContent("22:12", NavStart(sheet = SheetKind.CONTROL), sys)
        shot.both("control")
        checkTargets()
    }

    @Test fun updateRide() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(sheet = SheetKind.UPDATE), sys)
        shot.both("update-ride")
        rule.onNodeWithText("Установить v109").assertExists()
    }

    @Test fun updateParked() {
        Scenes.parked(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:06", NavStart(sheet = SheetKind.UPDATE), sys)
        shot.both("update-parked")
    }

    @Test fun updateIdleAvailable() {
        Scenes.idle(); Scenes.update(Upd.AVAILABLE); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:25", NavStart(sheet = SheetKind.UPDATE), sys)
        shot.both("update-idle-avail")
    }

    @Test fun view() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(sheet = SheetKind.VIEW), sys)
        shot.both("view")
    }

    @Test fun scan() {
        Scenes.idle(); Scenes.update(Upd.NONE)
        Scenes.scanList()
        shot.setContent("22:25", NavStart(), sys)
        shot.both("scan")
        rule.onNodeWithText("сохранён").assertExists()
    }

    /** A page pulled up (the stub Логи): the mini dash on top, the pinned action, the dock where it was. */
    @Test fun pageOpen() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(page = Page.LOGS), sys)
        checkDock()
        rule.onNodeWithContentDescription("Свернуть к поездке").assertExists()
        rule.onNodeWithText("Отправить все (3)").assertExists()
        shot.both("shell-page-logs")
    }

    // -- behaviour

    /** While moving a short press on «Отключить» only says to hold it; held 0,7 s it disconnects. */
    @Test fun holdToDisconnect() {
        Scenes.ride()
        shot.setContent("22:12", NavStart(), sys)
        assertTrue(ScooterRepo.moving.value)
        val b = rule.onNodeWithContentDescription("Отключить", substring = true)
        b.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(300)
        b.performTouchInput { up() }
        shot.settle(2)
        assertFalse("a short press does not disconnect", "disconnect" in shot.ui.calls)
        rule.onNodeWithText("удержать", substring = true).assertExists()
        b.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(DashViews.HOLD_MS + 100)
        b.performTouchInput { up() }
        shot.settle(2)
        assertTrue("held, it disconnects", "disconnect" in shot.ui.calls)
    }

    /** Stopped, one tap disconnects. */
    @Test fun tapToDisconnectWhenParked() {
        Scenes.parked()
        shot.setContent("22:06", NavStart(), sys)
        rule.onNodeWithContentDescription("Отключить", substring = true).performClick()
        shot.settle(1)
        assertTrue("disconnect" in shot.ui.calls)
    }

    /** Tiles that just appeared (the view switched to «Стою») ignore taps for 0,6 s. */
    @Test fun tilesGuard() {
        Scenes.parked(); Scenes.logs(Logs.UNSENT)
        ScooterRepo.setViewPin(ViewPin.RIDE)
        shot.setContent("22:06", NavStart(), sys)
        ScooterRepo.setViewPin(ViewPin.PARKED)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
        rule.onNodeWithText("Отправить").performClick()
        assertFalse("a tap right after the tiles appear goes nowhere", "sendAll" in shot.ui.calls)
        rule.mainClock.advanceTimeBy(DashViews.TILE_GUARD_MS)
        rule.waitForIdle()
        rule.onNodeWithText("Отправить").performClick()
        assertTrue("then it sends", "sendAll" in shot.ui.calls)
    }

    /** Back closes the sheet, then the GitHub screen, then folds the page. */
    @Test fun backStepByStep() {
        Scenes.parked(); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:06", NavStart(page = Page.LOGS, sheet = SheetKind.VIEW, sub = Sub.GITHUB), sys)
        rule.onNodeWithText("Главный экран").assertExists()
        fun back() { rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; shot.settle(4) }
        back()
        rule.onNodeWithText("Главный экран").assertDoesNotExist()
        rule.onNodeWithText("Куда приложение кладёт логи", substring = true).assertExists()
        back()
        rule.onNodeWithText("Куда приложение кладёт логи", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Свернуть к поездке").assertExists()
        checkDock()
        back()
        rule.onNodeWithContentDescription("Вид главного экрана", substring = true).assertExists()
    }

    // -- layout checks

    private fun dp(px: Float) = px / rule.activity.resources.displayMetrics.density

    /** The dock's top is always the same: the screen less the gesture bar (18 dp) and the dock (68 dp). */
    protected fun checkDock(bottomInset: Int = 18) {
        val h = rule.activity.resources.configuration.screenHeightDp
        val top = dp(rule.onNodeWithTag(TAG_DOCK).fetchSemanticsNode().boundsInRoot.top)
        assertEquals("dock top", (h - bottomInset - 68).toFloat(), top, 1f)
    }

    /** Every clickable at least 48 dp both ways (its touch bounds). */
    protected fun checkTargets() {
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = n.touchBoundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            val what = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
            assertTrue("target «$what» ${dp(b.width)}×${dp(b.height)} dp", dp(b.width) >= 47.5f && dp(b.height) >= 47.5f)
        }
    }

    protected fun checkLayout(ride: Boolean = false, bottomInset: Int = 18) {
        checkDock(bottomInset)
        checkTargets()
        val dock = rule.onNodeWithTag(TAG_DOCK).fetchSemanticsNode().boundsInRoot
        val zone = rule.onNodeWithTag(TAG_BOTTOM).fetchSemanticsNode().boundsInRoot
        assertTrue("the bottom zone ends at the dock", zone.bottom <= dock.top + 0.5f)
        if (ride) {
            val off = rule.onNodeWithContentDescription("Отключить", substring = true).fetchSemanticsNode().boundsInRoot
            assertTrue("«Отключить» above the dock", off.bottom <= dock.top + 0.5f)
            // nothing on the riding dash scrolls
            val main = rule.onNodeWithTag(TAG_MAIN).fetchSemanticsNode().boundsInRoot
            for (n in rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes()) {
                if (!main.overlaps(n.boundsInRoot)) continue
                val r = n.config[SemanticsProperties.VerticalScrollAxisRange]
                assertEquals("the riding dash fits without scrolling", 0f, r.maxValue(), 0.5f)
            }
        }
    }
}

@Config(sdk = [34], qualifiers = Size.P390)
class ShellShots : ShellShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class ShellShotsS21 : ShellShotsBase() {
    /** 3-button navigation: 48 dp at the bottom, the riding dash still fits (compact sizes). */
    @Test fun homeRideThreeButtons() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(), SystemState(), threeButtons = true)
        shot.both("home-ride", suffix = "-s21-3btn")
        checkLayout(ride = true, bottomInset = 48)
    }

    /** A 1,3 font: the dash may scroll, nothing overlaps. */
    @Test fun homeRideFontScale() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", NavStart(), SystemState(), fontScale = 1.3f)
        shot.both("home-ride", suffix = "-s21-fs13")
        checkDock()
    }
}
