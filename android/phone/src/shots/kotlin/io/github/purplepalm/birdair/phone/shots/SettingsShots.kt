package io.github.purplepalm.birdair.phone.shots

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import io.github.purplepalm.birdair.phone.BuildConfig
import io.github.purplepalm.birdair.phone.Event
import io.github.purplepalm.birdair.phone.Installed
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.TestLogs
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.shots.Scenes.Logs
import io.github.purplepalm.birdair.phone.shots.Scenes.Upd
import io.github.purplepalm.birdair.phone.ui.BirdTheme
import io.github.purplepalm.birdair.phone.ui.MainScreen
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
 * «Настройки» as the prototype draws it (gallery/ride.html, settings-*): nothing new, downloading 63 %, downloaded while
 * riding; and further down — the odometer editor open, the page's end (background work, the decoder, the link's
 * events). 390×844 and the S21's 360×800, dark and light: the theme row says «Тёмная» in the dark picture and «Как в
 * системе» in the light one, as the owner's phone would. ./gradlew :phone:testDebugUnitTest -Pshots --tests '*SettingsShots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class SettingsShotsBase {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    protected lateinit var shot: ThemedShooter
    private val zone = TimeZone.getDefault()

    @Before fun setUp() {
        shot = ThemedShooter(rule)
        Scenes.base(rule.activity.applicationContext)
    }

    @After fun tearDown() {
        Updater.installed = Installed(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)
        TimeZone.setDefault(zone)
    }

    protected val settings = NavStart(page = Page.SETTINGS)

    @Test fun settingsNone() {
        Scenes.parked(); Scenes.update(Upd.NONE)
        shot.setContent("22:06", settings)
        shot.both("settings-none")
        rule.onNodeWithText("Bird Air 8-a41290d (v108)", substring = true).assertExists()
        rule.onNodeWithText("Проверить обновления").assertExists()
        checkTargets()
    }

    @Test fun settingsDownloading() {
        Scenes.parked(); Scenes.update(Upd.DOWNLOADING)
        shot.setContent("22:06", settings)
        shot.both("settings-dl")
        rule.onNodeWithText("bird-air-phone.apk: 11,6 из 18,4 МБ · установщик сам не откроется, пока идёт запись").assertExists()
        rule.onNodeWithText("Скачиваю 63 %").assertExists()
    }

    @Test fun settingsReadyRiding() {
        Scenes.ride(); Scenes.update(Upd.READY); Scenes.logs(Logs.UNSENT)
        shot.setContent("22:12", settings)
        shot.both("settings-ready-ride")
        assertTrue("moving at 1800 s", ScooterRepo.moving.value)
        rule.onNodeWithText("Ты едешь — установка недоступна").assertExists()
        rule.onNodeWithText("Установить v109").assertExists()
        checkTargets()
    }

    /** The odometer editor open (the field holds the app's value now): «Задать», «Взять с руля». */
    @Test fun settingsOdometer() {
        Scenes.parked(); Scenes.update(Upd.NONE)
        shot.setContent("22:06", settings)
        scrollTo("Исправить").performClick()
        shot.settle(3)
        scrollTo("Взять с руля")
        shot.settle(3)
        shot.both("settings-odo")
        rule.onNodeWithText("Задать").assertExists()
        checkTargets()
    }

    /** The page's end: background work as on a fresh Samsung (no exemption, no notifications), the decoder, the link's events. */
    @Test fun settingsEnd() {
        Scenes.parked(); Scenes.update(Upd.NONE)
        val t0 = TestLogs.startedAt(Scenes.LOG)
        ScooterRepo.events.value = listOf(
            Event(t0 + 4_000, "Подключаюсь к scooter…", false),
            Event(t0 + 6_000, "Подключено — жду данные", false),
            Event(t0 + 7_000, "Декодер v6 с сайта", false),
            Event(t0 + 1_200_000, "Связь пропала (GATT 8) — переподключаюсь", true),
            Event(t0 + 1_204_000, "Подключено — жду данные", false),
        )
        shot.setContent("22:06", settings, SystemState(samsung = true, ignoringBattery = false, missingPerms = listOf("android.permission.POST_NOTIFICATIONS")))
        // to the very end: two flings
        rule.mainClock.autoAdvance = true
        repeat(2) {
            rule.onNode(hasScrollAction() and hasAnyDescendant(hasText("СОБЫТИЯ СВЯЗИ"))).performTouchInput { swipeUp(durationMillis = 200) }
            rule.waitForIdle()
        }
        rule.mainClock.autoAdvance = false
        shot.settle(3)
        shot.both("settings-end")
        rule.onNodeWithText("Подключаюсь к scooter…", substring = true).assertIsDisplayed()
        checkTargets()
    }

    /** «Задать» sets the app odometer; a word that is not a mileage stays in the field with a red line. */
    @Test fun odometerEdit() {
        Scenes.parked(); Scenes.update(Upd.NONE)
        shot.setContent("22:06", settings)
        scrollTo("Исправить").performClick()
        shot.settle(2)
        scrollTo("Взять с руля")
        val field = rule.onNodeWithText("Исправить, км")
        field.performTextReplacement("много")
        rule.onNodeWithText("Задать").performClick()
        shot.settle(2)
        rule.onNodeWithText("Впиши пробег в километрах", substring = true).assertExists()
        field.performTextReplacement("1464,6")
        rule.onNodeWithText("Задать").performClick()
        shot.settle(2)
        assertEquals(1464.6, ScooterRepo.tracker.odometerState!!.totalKm, 1e-9)
        rule.onNodeWithText("Задать").assertDoesNotExist()
        // the page's line and the snackbar both say it
        assertTrue(rule.onAllNodesWithText("1464,6 км", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    /** The pinned button is THE update button: one tap is one step. */
    @Test fun pinnedButtonSteps() {
        Scenes.parked(); Scenes.update(Upd.AVAILABLE)
        shot.setContent("22:06", settings)
        rule.onNodeWithText("Обновить до v109").performClick()
        assertTrue("updateStep" in shot.ui.calls)
    }

    /** performScrollTo animates: frames must run for it (the shots hold the clock otherwise). */
    private fun scrollTo(text: String): SemanticsNodeInteraction {
        rule.mainClock.autoAdvance = true
        val n = rule.onNodeWithText(text, substring = true).performScrollTo()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        return n
    }

    private fun dp(px: Float) = px / rule.activity.resources.displayMetrics.density

    /** Every clickable at least 48 dp both ways: its touch bounds, or its own size where the page's edge cuts it. */
    protected fun checkTargets() {
        for (n in rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
            val b = n.touchBoundsInRoot
            if (b.width <= 0f || b.height <= 0f) continue
            val w = maxOf(dp(b.width), dp(n.size.width.toFloat()))
            val h = maxOf(dp(b.height), dp(n.size.height.toFloat()))
            assertTrue("target «${what(n)}» $w×$h dp", w >= 47.5f && h >= 47.5f)
        }
    }

    private fun what(n: SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "?"
}

@Config(sdk = [34], qualifiers = Size.P390)
class SettingsShots : SettingsShotsBase()

@Config(sdk = [34], qualifiers = Size.S21)
class SettingsShotsS21 : SettingsShotsBase() {
    /** A 1,3 font: the page scrolls, the update card and the plate wrap, every row stays a whole target. */
    @Test fun settingsFontScale() {
        Scenes.ride(); Scenes.update(Upd.READY)
        shot.setContent("22:12", settings, fontScale = 1.3f)
        shot.both("settings-ready-ride", suffix = "-s21-fs13")
        checkTargets()
    }
}

/**
 * [Shooter] for «Настройки»: the light picture also has «Как в системе» chosen (the prototype's light settings), so the
 * theme row matches the theme drawn. Captures through [Shooter.capture], settles like it.
 */
class ThemedShooter(private val rule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    private val inner = Shooter(rule)
    val ui get() = inner.ui
    private var dark by mutableStateOf(true)
    private val s21: Boolean get() = rule.activity.resources.configuration.screenWidthDp <= 360

    fun setContent(clock: String, start: NavStart, sys: SystemState = SystemState(), fontScale: Float? = null) {
        rule.mainClock.autoAdvance = false
        ScooterRepo.updateUi { it.copy(theme = "dark") }
        rule.setContent { BirdTheme(dark) { ShotFrame(clock, fontScale = fontScale) { MainScreen(inner.ui, sys, start) } } }
        settle()
    }

    fun settle(steps: Int = 12) = inner.settle(steps)

    private fun theme(isDark: Boolean) {
        rule.runOnIdle { dark = isDark; ScooterRepo.updateUi { it.copy(theme = if (isDark) "dark" else "system") } }
        repeat(3) { rule.mainClock.advanceTimeBy(16); rule.waitForIdle() }
    }

    fun both(name: String, suffix: String = if (s21) "-s21" else "") {
        inner.capture("$name$suffix-dark")
        theme(false)
        inner.capture("$name$suffix-light")
        theme(true)
    }
}
