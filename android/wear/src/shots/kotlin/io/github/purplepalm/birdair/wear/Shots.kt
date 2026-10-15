package io.github.purplepalm.birdair.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    private fun gauge(t: Telemetry, h: Health = Health.LIVE, ambient: AmbientState? = null) =
        WatchApp(Screen(t, h), ambient, true) {}

    @Test fun riding() = shot("riding") { gauge(Samples.riding) }
    @Test fun regen() = shot("regen") { gauge(Samples.regen) }
    @Test fun lowBattery() = shot("low-battery") { gauge(Samples.lowBattery) }
    @Test fun flatOut() = shot("flat-out") {
        gauge(Samples.riding.copy(speedKmh = 29.8, powerW = 1180.0, peakW = 1200.0, batteryPct = 100.0, mode = 3))
    }
    @Test fun noPhone() = shot("no-phone") { gauge(Samples.riding, Health.NO_PHONE) }
    @Test fun silent() = shot("silent") { gauge(Samples.silent, Health.SILENT) }
    @Test fun empty() = shot("empty") { gauge(Telemetry(), Health.NO_PHONE) }
    @Test fun ambient() = shot("ambient") { gauge(Samples.riding, ambient = AmbientState(burnIn = true, tick = 2)) }
    @Test fun ambientRegen() = shot("ambient-regen") { gauge(Samples.regen, ambient = AmbientState(burnIn = true, tick = 5)) }
    @Test fun details() = shot("details") { WatchTheme { DetailsScreen(Screen(Samples.silent, Health.SILENT), true) {} } }
}
