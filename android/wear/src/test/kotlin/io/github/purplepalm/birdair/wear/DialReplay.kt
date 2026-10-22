package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.dial.Cluster
import io.github.purplepalm.birdair.dial.DialBuilder
import io.github.purplepalm.birdair.dial.DialCues
import io.github.purplepalm.birdair.dial.DialState
import io.github.purplepalm.birdair.dial.Health
import io.github.purplepalm.birdair.dial.LampBuzz

/**
 * A ride log as the [DialState]s the watch would draw, for the faces' tests on real rides: [RideReplay] (the phone's
 * pipeline, every message to the watch at its log time), the health as the watch judges it, the speed cues as
 * [RideService] runs them ([SpeedCues]: the tap, the rumble, its stop on stale data) and the lamp buzzes as [WatchFeed]
 * plays them ([LampAlarm]) — one [DialBuilder] keeping the lamps' history. The clock is the log's ms.
 *
 *     DialReplay.states(RideReplay.TO_ZERO, everyMs = 250).forEach { s -> … }
 *     DialReplay.replay(path) { s -> assertTrue(myLayout(s).fits) }
 */
object DialReplay {
    /** Every state of [path] in order ([each] gets each one), with the watch's [prefs]. */
    fun replay(path: String, prefs: WatchPrefs = WatchPrefs(), each: (DialState) -> Unit) {
        val b = DialBuilder()
        val cues = SpeedCues()
        val alarm = LampAlarm()
        var c = DialCues()
        var freshAt: Long? = null
        var last: Telemetry? = null
        fun step(t: Telemetry, now: Long, live: Boolean) {
            val o = cues.update(prefs, t, live, freshAt, now)
            if (o.tap) c = c.copy(tapAtMs = now)
            val on = cues.rumble.on
            if (on != c.rumble) c = c.copy(rumble = on, rumbleSinceMs = if (on) now else null)
        }
        RideReplay.snapshots(path) { t, fresh ->
            val f = freshAt
            val l = last
            // the ride service's stale check while the rumble runs
            if (cues.rumble.on && f != null && l != null && t.t - f > OverspeedAlarm.FRESH_MS) step(l, f + OverspeedAlarm.FRESH_MS + 1, false)
            if (fresh) freshAt = t.t
            last = t
            val now = t.t
            step(t, now, Health.of(t, now, now).live && OverspeedAlarm.fresh(freshAt, now))
            alarm.onMessage(Cluster.lamps(t), t.link == Link.CONNECTED && t.streaming, now, t.speedKmh)?.let {
                c = c.copy(buzz = LampBuzz(now, it.level), redAtMs = if (it.level == LampLevel.RED) now else c.redAtMs)
            }
            each(
                b.build(
                    t, Health.of(t, now, now), now, cues = c,
                    tapMph = prefs.tapMph.takeIf { prefs.tapOn }, rumbleMph = prefs.overspeedMph.takeIf { prefs.overspeedOn },
                ),
            )
        }
    }

    /** The states of [path], one per [everyMs] of the log at most (0: all of them), each kept whole. */
    fun states(path: String, prefs: WatchPrefs = WatchPrefs(), everyMs: Long = 0): List<DialState> {
        val out = ArrayList<DialState>()
        replay(path, prefs) { s -> if (everyMs <= 0 || out.isEmpty() || s.nowMs - out.last().nowMs >= everyMs) out += s }
        return out
    }
}
