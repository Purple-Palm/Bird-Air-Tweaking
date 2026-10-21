package io.github.purplepalm.birdair.wear

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.wear.compose.material.LocalTextStyle
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.GrowMotion
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * «Растущие лампы» drawn through Compose on real rides ([DialReplay]), one state a second, as the watch draws them; each
 * new look (the main lamp, its text, how many lamps) saved settled to wear/build/shots/faces/grow-replay/<ride>/<s>.png.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class FaceGrowReplayShots(private val name: String, private val path: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun everySecond() {
        RideReplay.assume(path)
        val states = DialReplay.states(path, everyMs = 1_000)
        val cur = mutableStateOf<DialState>(states.first())
        val at = mutableLongStateOf(states.first().nowMs)
        rule.setContent {
            WatchTheme {
                CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        DialFace(DialFace.GROW, cur.value, Modifier.fillMaxSize(), nowMs = at.longValue)
                    }
                }
            }
        }
        val dir = File(System.getProperty("shotDir") ?: "build/shots", "faces/grow-replay/$name").apply { mkdirs() }
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val looks = HashSet<String>()
        var drawn = 0
        val t0 = states.first().nowMs
        for (s in states) {
            cur.value = s
            at.longValue = s.nowMs
            rule.waitForIdle()
            v.draw(canvas)
            drawn++
            val top = s.lamps.top
            val look = "${top?.id}:${top?.lamp?.text}:${s.lamps.alerts.size}:${s.status}"
            if (looks.add(look) && looks.size <= 40) {
                at.longValue = s.nowMs + GrowMotion.SETTLE_MS
                rule.waitForIdle()
                bmp.eraseColor(0)
                v.draw(canvas)
                File(dir, "${(s.nowMs - t0) / 1000}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        println("$name: $drawn states drawn, ${looks.size} looks")
        assertTrue(drawn > 600)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf("to-zero", RideReplay.TO_ZERO),
            arrayOf("cutoff-hot", FaceGrowReplayTest.CUTOFF_HOT),
            arrayOf("two-up", FaceGrowReplayTest.TWO_UP_HALF),
        )
    }
}
