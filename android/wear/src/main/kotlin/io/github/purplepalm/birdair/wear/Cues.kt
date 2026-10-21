package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.dial.DialCues
import io.github.purplepalm.birdair.dial.LampBuzz
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the watch's motor did, for the dial ([DialCues]: a face's ripples and red pulse go with the vibration): the ride
 * service's tap at the limiter and its rumble, [WatchFeed]'s lamp buzz — each when it was played. Clock: elapsedRealtime.
 */
object Cues {
    private val state = MutableStateFlow(DialCues())
    val flow: StateFlow<DialCues> = state.asStateFlow()

    fun tap(atMs: Long) = state.update { it.copy(tapAtMs = atMs) }

    fun rumble(on: Boolean, atMs: Long) = state.update { if (it.rumble == on) it else it.copy(rumble = on, rumbleSinceMs = if (on) atMs else null) }

    fun buzz(atMs: Long, level: LampLevel) =
        state.update { it.copy(buzz = LampBuzz(atMs, level), redAtMs = if (level == LampLevel.RED) atMs else it.redAtMs) }
}
