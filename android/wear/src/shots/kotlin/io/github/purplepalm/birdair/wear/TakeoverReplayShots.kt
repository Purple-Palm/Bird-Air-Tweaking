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
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle
import io.github.purplepalm.birdair.dial.TakeoverLayout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * «Захват экрана» painted on real rides ([DialReplay]) every [EVERY_MS] of the log at 480×480 px, as the watch draws it:
 * no exception anywhere; the moments worth a look (the first takeover, its calm red layout, the first slot, the end) go to
 * wear/build/shots/faces/takeover-replay/<ride>-<s>.png. One ride: --tests '*TakeoverReplayShots*zero*'.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w240dp-h240dp-round-watch-xhdpi")
class TakeoverReplayShots(private val name: String, private val path: String) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun paints() {
        RideReplay.assume(path)
        val states = DialReplay.states(path, everyMs = EVERY_MS)
        val shown = mutableStateOf(states.first())
        val at = mutableLongStateOf(states.first().nowMs)
        rule.setContent {
            WatchTheme {
                CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        DialFace(DialFace.TAKEOVER, shown.value, Modifier.fillMaxSize(), nowMs = at.longValue)
                    }
                }
            }
        }
        val dir = File(System.getProperty("shotDir") ?: "build/shots", "faces/takeover-replay").apply { mkdirs() }
        val keep = moments(states)
        var painted = 0
        for (s in states) for (dt in listOf(0L, 1_500L)) {
            shown.value = s
            at.longValue = s.nowMs + dt
            rule.waitForIdle()
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            painted++
            keep[s.nowMs]?.takeIf { dt == 1_500L }?.let { tag ->
                File(dir, "$name-${s.nowMs / 1000}-$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bmp.recycle()
        }
        assertTrue("$name: $painted frames", painted > 1_000)
    }

    /** The states worth a picture: the first takeover, the calm red layout after it, the first slot, the last state. */
    private fun moments(states: List<DialState>): Map<Long, String> {
        val out = LinkedHashMap<Long, String>()
        val m = TakeoverLayout.ROUGH
        var takeAt: Long? = null
        for (s in states) {
            val f = TakeoverLayout.frame(s, s.nowMs + 1_500, m)
            if (f.takeover > 0.99f && "take" !in out.values) { out[s.nowMs] = "take"; takeAt = s.nowMs }
            if (takeAt != null && s.nowMs > takeAt + 10_000 && f.takeover == 0f && s.lamps.anyRed && "red" !in out.values) out[s.nowMs] = "red"
            if (f.slot.isNotEmpty() && s.lamps.top?.level == LampLevel.AMBER && "amber" !in out.values) out[s.nowMs] = "amber"
            if (f.slot.size >= 2 && "two" !in out.values) out[s.nowMs] = "two"
            if (s.status != null && s.lamps.lit.isNotEmpty() && "status" !in out.values) out[s.nowMs] = "status"
        }
        out[states.last().nowMs] = "end"
        return out
    }

    companion object {
        const val EVERY_MS = 1_000L

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            arrayOf("zero", RideReplay.TO_ZERO),
            arrayOf("hot", TakeoverReplayTest.CUTOFF_HOT),
            arrayOf("twoup", TakeoverReplayTest.TWO_UP),
        )
    }
}
