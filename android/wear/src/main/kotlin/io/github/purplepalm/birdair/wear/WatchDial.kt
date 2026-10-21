package io.github.purplepalm.birdair.wear

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.LocalTextStyle
import androidx.wear.compose.material.TimeTextDefaults
import io.github.purplepalm.birdair.dial.BurnIn
import io.github.purplepalm.birdair.dial.DialBuilder
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.LocalFaceTextStyle

/**
 * The gauge page: the screen as a [DialState] (one [DialBuilder] keeps the lamps' history between messages) drawn by the
 * chosen face ([WatchPrefs.face]) in the watch theme's text style. [smooth]: the resolved «Плавно».
 */
@Composable
fun WatchDial(s: Screen, ambient: AmbientState?, prefs: WatchPrefs, smooth: Boolean, modifier: Modifier = Modifier) {
    val builder = remember { DialBuilder() }
    val clock = TimeTextDefaults.timeSource(TimeTextDefaults.timeFormat()).currentTime
    val cues = prefs.tapOn to prefs.tapMph to (prefs.overspeedOn to prefs.overspeedMph)
    val state = remember(s, ambient, smooth, clock, cues) { dialState(builder, s, ambient, prefs, smooth, clock, SystemClock.elapsedRealtime()) }
    CompositionLocalProvider(LocalFaceTextStyle provides LocalTextStyle.current) {
        DialFace(prefs.face, state, modifier)
    }
}

/** What the faces draw for [s] at [nowMs] (elapsedRealtime): the rumble as the ride service runs it ([Screen.overspeed]). */
fun dialState(b: DialBuilder, s: Screen, ambient: AmbientState?, prefs: WatchPrefs, smooth: Boolean, clock: String, nowMs: Long): DialState =
    b.build(
        s.t, s.health, nowMs, fast = s.fast, smooth = smooth,
        cues = s.cues.copy(rumble = s.overspeed, rumbleSinceMs = if (s.overspeed) s.cues.rumbleSinceMs ?: nowMs else null),
        tapMph = prefs.tapMph.takeIf { prefs.tapOn }, rumbleMph = prefs.overspeedMph.takeIf { prefs.overspeedOn },
        bulbAtMs = s.bulbAtMs, bulbOn = s.bulb,
        ambient = ambient != null, lowBit = ambient?.lowBit == true,
        burnInDp = if (ambient?.burnIn == true) BurnIn.offset(ambient.tick) else 0f to 0f,
        clock = clock,
    )
