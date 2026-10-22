package io.github.purplepalm.birdair.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material.LocalTextStyle
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.DialScenarios
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every face × the 11 demo scenarios ([DialScenarios]) at [DialScenarios.FRAMES_MS] after each one's change, at the
 * Ultra's 480×480 px, drawn as the watch draws them (its theme's text style) at a fixed time on the scenario's clock:
 * wear/build/shots/faces/<face>/<scenario>-<ms>.png. One face: -PshotFace=stack, or the filter --tests '*FaceShots*stack*'
 * (one scenario: '*FaceShots*stack-amber*'); a contact sheet: python3 android/wear/shots_sheet.py.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class FaceShots(private val faceKey: String, private val scenario: String) {
    private val face = DialFace.of(faceKey)

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun frames() {
        val sc = DialScenarios[scenario]
        val at = mutableLongStateOf(sc.changeMs)
        rule.setContent {
            WatchTheme {
                CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        DialFace(face, sc.at(at.longValue), Modifier.fillMaxSize(), nowMs = at.longValue)
                    }
                }
            }
        }
        val dir = File(System.getProperty("shotDir") ?: "build/shots", "faces/" + face.key).apply { mkdirs() }
        for (ms in DialScenarios.FRAMES_MS) {
            at.longValue = sc.changeMs + ms
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "$scenario-$ms.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> {
            val only = System.getProperty("shotFace")?.takeIf { it.isNotBlank() }
            return DialFace.entries.filter { only == null || it.key == only }.flatMap { f -> DialScenarios.KEYS.map { arrayOf<Any>(f.key, it) } }
        }
    }
}
