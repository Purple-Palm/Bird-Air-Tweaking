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

    /** [atMs]: how long the screen has been up; red lamps are lit on whole seconds, dark on the halves. */
    private fun shot(name: String, atMs: Long = 1100, content: @Composable () -> Unit) {
        rule.setContent(content)
        rule.mainClock.advanceTimeBy(atMs)
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shotDir") ?: "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun gauge(
        t: Telemetry, h: Health = Health.LIVE, ambient: AmbientState? = null, flags: PhoneFlags = PhoneFlags(),
        bulb: Boolean = false, overspeed: Boolean = false,
    ) = WatchApp(Screen(t, h, flags, bulb = bulb, overspeed = overspeed), ambient)

    @Composable
    private fun details(t: Telemetry, h: Health = Health.LIVE, prefs: WatchPrefs = WatchPrefs(), flags: PhoneFlags = PhoneFlags()) =
        WatchTheme { DetailsScreen(Screen(t, h, flags), prefs) {} }

    // the approved mock's three dials
    @Test fun normal() = shot("normal") { gauge(Samples.normal) }
    @Test fun attention() = shot("attention") { gauge(Samples.attention) }
    @Test fun critical() = shot("critical") { gauge(Samples.critical) }
    // the red lamps in their dark half-second
    @Test fun criticalBlinkOff() = shot("critical-blink-off", atMs = 1600) { gauge(Samples.critical) }
    // ambient: black, outlines only, lit lamps only, no glow, no blink
    @Test fun ambient() = shot("ambient") { gauge(Samples.critical, ambient = AmbientState(burnIn = true, tick = 2)) }
    @Test fun ambientNormal() = shot("ambient-normal") { gauge(Samples.normal, ambient = AmbientState(burnIn = true, tick = 5)) }
    // «проверка ламп»: all 12 in their strongest colour, at start and on each (re)connection
    @Test fun bulbCheck() = shot("bulb-check") { gauge(Samples.normal, bulb = true) }
    // above the watch's threshold (16 mph): the vibration runs, the dot is red; the blue lamp past the limiter
    @Test fun overspeed() = shot("overspeed") { gauge(Samples.overspeed, overspeed = true) }
    @Test fun overspeedMph() = shot("overspeed-mph") { gauge(Samples.overspeed.copy(units = "mph"), overspeed = true) }

    @Test fun riding() = shot("riding") { gauge(Samples.riding) }
    @Test fun regen() = shot("regen") { gauge(Samples.regen) }
    @Test fun lowBattery() = shot("low-battery") { gauge(Samples.lowBattery) }
    @Test fun warning() = shot("warning") { gauge(Samples.warning) }
    @Test fun full() = shot("full") {
        gauge(Samples.normal.copy(speedKmh = 25.2, powerW = 690.0, powerFastW = 700.0, batteryPct = 100.0, batteryPctFine = 100.0))
    }
    @Test fun noPhone() = shot("no-phone") { gauge(Samples.attention, Health.NO_PHONE) }
    @Test fun idle() = shot("idle") { gauge(Samples.riding, Health.IDLE) }
    @Test fun reconnecting() = shot("reconnecting") { gauge(Samples.critical, Health.RECONNECTING) }
    @Test fun empty() = shot("empty") { gauge(Telemetry(), Health.NO_PHONE) }
    // the range: the rough default at the start of a ride; in miles
    @Test fun rangeRough() = shot("range-rough") { gauge(Samples.roughRange) }
    @Test fun rangeMph() = shot("range-mph") { gauge(Samples.warning.copy(units = "mph")) }
    // an older phone: no lamps, its battery warnings stand in
    @Test fun oldPhone() = shot("old-phone") { gauge(Samples.critical.copy(lamps = emptyList())) }

    @Test fun details() = shot("details") { details(Samples.silent, Health.SILENT) }
    @Test fun detailsLamps() = shot("details-lamps") { details(Samples.critical, flags = PhoneFlags(fast = true, smooth = false)) }
    // the bottom of the details page: «Экран не гаснет», «Плавно» (forced off here), the overspeed vibration
    @Test fun detailsSettings() = shot("details-settings") {
        val s = Screen(Samples.riding, Health.LIVE, PhoneFlags(smooth = true))
        WatchTheme { DetailsScreen(s, WatchPrefs(smooth = SmoothPref.OFF), rememberScalingLazyListState(smoothChipIndex(s))) {} }
    }
    @Test fun detailsOverspeed() = shot("details-overspeed") {
        val s = Screen(Samples.riding, Health.LIVE)
        WatchTheme { DetailsScreen(s, WatchPrefs(overspeedMph = 17.5), rememberScalingLazyListState(overspeedChipIndex(s) + 1)) {} }
    }
    @Test fun detailsLampBuzz() = shot("details-lamp-buzz") {
        val s = Screen(Samples.riding, Health.LIVE)
        WatchTheme { DetailsScreen(s, WatchPrefs(lampBuzz = false), rememberScalingLazyListState(lampBuzzChipIndex(s))) {} }
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
