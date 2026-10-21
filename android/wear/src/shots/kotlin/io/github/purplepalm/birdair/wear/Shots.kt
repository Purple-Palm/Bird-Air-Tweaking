package io.github.purplepalm.birdair.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import io.github.purplepalm.birdair.core.Telemetry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * PNGs of the screens at the Galaxy Watch Ultra's 480×480 px (240 dp, xhdpi, round), for looking at the
 * design without a watch: ./gradlew :wear:testDebugUnitTest -Pshots → wear/build/shots/. Opt-in: it needs
 * Robolectric's android-all jar. Known gap: the curved page indicator does not render under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class Shots {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent(content)
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun gauge(t: Telemetry, h: Health = Health.LIVE, ambient: AmbientState? = null, flags: PhoneFlags = PhoneFlags()) =
        WatchApp(Screen(t, h, flags), ambient)

    @Composable
    private fun details(t: Telemetry, h: Health = Health.LIVE, prefs: WatchPrefs = WatchPrefs(), flags: PhoneFlags = PhoneFlags()) =
        WatchTheme { DetailsScreen(Screen(t, h, flags), prefs) {} }

    @Test fun riding() = shot("riding") { gauge(Samples.riding) }
    @Test fun regen() = shot("regen") { gauge(Samples.regen) }
    @Test fun lowBattery() = shot("low-battery") { gauge(Samples.lowBattery) }
    @Test fun flatOut() = shot("flat-out") {
        gauge(Samples.riding.copy(speedKmh = 29.8, powerW = 1180.0, peakW = 1200.0, batteryPct = 100.0, mode = 3))
    }
    @Test fun noPhone() = shot("no-phone") { gauge(Samples.riding, Health.NO_PHONE) }
    // idle draw at a standstill: a short tip on the traction side, square at 12 o'clock
    @Test fun standstill() = shot("standstill") { gauge(Samples.riding.copy(speedKmh = 0.0, currentA = 0.12, powerW = 4.9, peakW = 300.0)) }
    @Test fun idle() = shot("idle") { gauge(Samples.riding, Health.IDLE) }
    @Test fun silent() = shot("silent") { gauge(Samples.silent, Health.SILENT) }
    @Test fun empty() = shot("empty") { gauge(Telemetry(), Health.NO_PHONE) }
    @Test fun ambient() = shot("ambient") { gauge(Samples.riding, ambient = AmbientState(burnIn = true, tick = 2)) }
    @Test fun ambientRegen() = shot("ambient-regen") { gauge(Samples.regen, ambient = AmbientState(burnIn = true, tick = 5)) }
    @Test fun details() = shot("details") { details(Samples.silent, Health.SILENT) }

    // the phone's battery warnings: a pill at the top in place of the time, the track tinted, the battery amber / red
    @Test fun warning() = shot("warning") { gauge(Samples.warning) }
    @Test fun critical() = shot("critical") { gauge(Samples.critical) }
    @Test fun ambientWarning() = shot("ambient-warning") { gauge(Samples.critical, ambient = AmbientState(burnIn = true, tick = 1)) }
    // a warning on old data is not shown: the top says what is wrong with the data
    @Test fun warningReconnecting() = shot("warning-reconnecting") { gauge(Samples.critical, Health.RECONNECTING) }
    // the range: measured (riding, warning) and the rough default at the start of a ride; in miles
    @Test fun rangeRough() = shot("range-rough") { gauge(Samples.roughRange) }
    @Test fun rangeMph() = shot("range-mph") { gauge(Samples.warning.copy(units = "mph")) }
    // the phone's «fast» off: the arc and the watts are the BMS's
    @Test fun bmsPower() = shot("bms-power") { gauge(Samples.riding, flags = PhoneFlags(fast = false)) }
    @Test fun detailsWarnings() = shot("details-warnings") { details(Samples.critical, flags = PhoneFlags(fast = true, smooth = false)) }
    // the bottom of the details page: «Экран не гаснет», «Плавно» (forced off here) and the version
    @Test fun detailsSettings() = shot("details-settings") {
        val s = Screen(Samples.riding, Health.LIVE, PhoneFlags(smooth = true))
        WatchTheme { DetailsScreen(s, WatchPrefs(smooth = SmoothPref.OFF), rememberScalingLazyListState(smoothChipIndex(s))) {} }
    }

    // the watch's own update (WatchUpdater): offered, downloading through the phone, ready to install
    private fun updates(name: String, vararg states: UpdateState) = shot(name) {
        WatchTheme {
            // about the details list's width at its middle
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                for (s in states) UpdateBlock(s, 105, onAction = {}, onCancel = {})
            }
        }
    }
    private val release = WatchRelease(106, "6-abc1234", watchSize = 25_722_553)
    @Test fun updateOffered() = updates("update-offered", UpdateState(latest = release))
    @Test fun updateDownloading() = updates(
        "update-downloading",
        UpdateState(latest = release, phase = UpdatePhase.DOWNLOADING, got = 6_430_638, total = release.watchSize, bytesPerS = 41_000.0, wifi = false),
    )
    @Test fun updateReady() = updates(
        "update-ready",
        UpdateState(latest = release, phase = UpdatePhase.READY, readyCode = 106, message = "Скачано и проверено (v106)."),
    )
}
