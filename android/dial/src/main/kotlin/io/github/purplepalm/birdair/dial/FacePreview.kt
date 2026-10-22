package io.github.purplepalm.birdair.dial

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay

/**
 * [face] playing the demo [scenarios] one after the other, as the watch would show them (live: each state at its time,
 * the face's own clock in between) — the phone's «Циферблат часов» previews. [playing] false: the first one's state at
 * its change, still. Round, on black.
 */
@Composable
fun FacePreview(face: DialFace, modifier: Modifier = Modifier, scenarios: List<String> = DialScenarios.PREVIEW, playing: Boolean = true) {
    val list = remember(scenarios) { scenarios.map { DialScenarios[it] } }
    var state by remember(list) { mutableStateOf(list.first().let { it.at(it.changeMs + DialScenarios.FRAMES_MS.last()) }) }
    LaunchedEffect(list, playing) {
        if (!playing) return@LaunchedEffect
        while (true) for (sc in list) {
            var at = 0L
            for (s in sc.states) {
                if (s.nowMs > at) delay(s.nowMs - at)
                at = s.nowMs
                state = s
            }
            delay((sc.lengthMs - at).coerceAtLeast(0))
        }
    }
    Box(modifier.aspectRatio(1f).clip(CircleShape).background(Color.Black)) {
        DialFace(face, state, nowMs = if (playing) null else state.nowMs)
    }
}
