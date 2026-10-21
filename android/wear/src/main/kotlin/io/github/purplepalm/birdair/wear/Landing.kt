package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.dial.Stack
import io.github.purplepalm.birdair.dial.StackStage

/**
 * When a lamp's buzz goes with «Крупные лампы»: as its chip lands ([Stack.LAND_MS] after the message) only while that
 * flight is seen — the activity started ([MainActivity]), the gauge page on screen and not ambient ([WatchApp]), the face
 * drawing it now ([stage]: not calm, not queued behind another change or the startup) — and only for a buzz that came
 * with its lamp's rise ([LampAlarm.rose]). Otherwise at once: the screen off with only the ride service alive, «Подробно»,
 * a red repeat. Main thread.
 */
object Landing {
    /** The gauge's face shows its timeline here ([io.github.purplepalm.birdair.dial.LocalStackStage]). */
    val stage = StackStage()
    var started = false
    var gaugeShown = false

    /** How long the buzz of a message at [nowMs] waits; [rose]: it came with its lamp's rise. */
    fun waitMs(nowMs: Long, rose: Boolean): Long = if (rose && started && gaugeShown) stage.landingMs(nowMs) else 0L
}
