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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.wear.compose.material.LocalTextStyle
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.dial.BurnIn
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.DialScenarios
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.Health
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle
import io.github.purplepalm.birdair.dial.RingLayout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * «Кольцо» painted as the watch draws it (480×480 px): every second of three real rides ([DialReplay]) at the message and
 * 350 ms into its transitions — no exception anywhere; the moments worth a look go to
 * wear/build/shots/faces/ring-replay/<ride>-<s>-<what>.png. And «tap»: the demo's «много ламп» tapped on the dial,
 * the next lamps in the centre and the main one again after 6 s, then with the phone gone and in low-bit ambient.
 * One: --tests '*RingShots*zero*'.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class RingShots(private val name: String, private val path: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("shotDir") ?: "build/shots", "faces/ring-replay").apply { mkdirs() }
    private val shown = mutableStateOf(DialState())
    private val at = mutableLongStateOf(0L)

    /** [s] at [ms] on screen; saved as [file] unless empty. */
    private fun frame(s: DialState, ms: Long, file: String = "") {
        shown.value = s
        at.longValue = ms
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        if (file.isNotEmpty()) File(dir, "$file.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    @Test fun paints() {
        val states = if (name == "tap") DialScenarios["many"].states else { RideReplay.assume(path); DialReplay.states(path, everyMs = 1_000) }
        shown.value = states.first()
        at.longValue = states.first().nowMs
        rule.setContent {
            WatchTheme {
                CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        DialFace(DialFace.RING, shown.value, Modifier.fillMaxSize(), nowMs = at.longValue)
                    }
                }
            }
        }
        if (name == "tap") { taps(); return }
        val keep = moments(states)
        var painted = 0
        for (s in states) for (dt in listOf(0L, 350L)) {
            frame(s, s.nowMs + dt, keep[s.nowMs]?.takeIf { dt == 0L }?.let { "$name-${s.nowMs / 1000}-$it" } ?: "")
            painted++
        }
        assertTrue("$name: $painted frames", painted > 1_000)
    }

    /** «Много ламп»: two taps a second apart, then 6 s on — the next lamps in the centre, then the main one again. */
    private fun taps() {
        val sc = DialScenarios["many"]
        val t0 = sc.changeMs + 1_200
        val s = sc.at(t0)
        frame(s, t0, "tap-0-before")
        rule.onRoot().performTouchInput { click() }
        frame(s, t0 + 150, "tap-1-150")
        frame(s, t0 + 600, "tap-1-600")
        frame(s, t0 + 1_000)
        rule.onRoot().performTouchInput { click() }
        frame(s, t0 + 1_600, "tap-2-600")
        frame(s, t0 + 1_000 + RingLayout.PICK_MS + 150, "tap-3-back-150")
        frame(s, t0 + 1_000 + RingLayout.PICK_MS + 600, "tap-3-back-600")
        // the phone gone (two lines where the clock is, the values dimmed); low-bit ambient
        val later = t0 + 20_000
        frame(s.copy(health = Health.NO_PHONE, status = Health.NO_PHONE.gaugeLabel), later, "status-nophone")
        frame(s.copy(ambient = true, lowBit = true, burnInDp = BurnIn.offset(5)), later, "ambient-lowbit")
    }

    /** The states worth a picture: the first red, the first amber, two or more lit, old data, the rumble, the end. */
    private fun moments(states: List<DialState>): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        for (s in states) {
            val top = s.lamps.top
            if (top?.level == LampLevel.RED && "red" !in out.values) out[s.nowMs] = "red"
            if (top?.level == LampLevel.AMBER && "amber" !in out.values) out[s.nowMs] = "amber"
            if (s.lamps.alerts.size >= 2 && "two" !in out.values) out[s.nowMs] = "two"
            if (s.status != null && s.lamps.lit.isNotEmpty() && "status" !in out.values) out[s.nowMs] = "status"
            if (s.cues.rumble && "rumble" !in out.values) out[s.nowMs] = "rumble"
        }
        out[states.last().nowMs] = "end"
        return out
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf("zero", RideReplay.TO_ZERO),
            arrayOf("hot", RingReplayTest.CUTOFF_HOT),
            arrayOf("twoup", RingReplayTest.TWO_UP),
            arrayOf("tap", ""),
        )
    }
}
