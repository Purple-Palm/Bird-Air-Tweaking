package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.*

/* Pure logic of the watch screen (the dial's own is in :dial): no Android, no Compose, so it runs in plain JVM tests. */

/**
 * "Still riding?": the last time the data was live ([Health.LIVE]) decides how long the screen is kept on once the
 * scooter stops streaming; the ride service goes by the phone's session instead ([SessionIdle]).
 */
object Idle {
    /** No live data for this long: the screen may time out; no session on the phone for this long: the ride service stops. */
    const val AFTER_MS = 5 * 60_000L

    /** [sinceMs]: the later of the last live message and the moment the screen was opened. */
    fun expired(lastLiveMs: Long?, sinceMs: Long, nowMs: Long): Boolean =
        nowMs - maxOf(lastLiveMs ?: Long.MIN_VALUE, sinceMs) > AFTER_MS

    fun keepScreenOn(enabled: Boolean, lastLiveMs: Long?, openedAtMs: Long, nowMs: Long): Boolean =
        enabled && !expired(lastLiveMs, openedAtMs, nowMs)
}

/**
 * When the ride service stops: [Idle.AFTER_MS] after the phone last reported a session ([Health.active]: connecting,
 * reconnecting, a silent handlebar or live data), not after the last live data — a stop with the scooter off keeps
 * the service, and with it the overspeed vibration, for the rest of the ride (only the activity, while visible, could
 * start it again). IDLE or no phone for that long: the ride is over.
 */
class SessionIdle(startedAtMs: Long) {
    private var activeAtMs = startedAtMs

    /** Call about once a second with the health at [nowMs]. */
    fun expired(h: Health, nowMs: Long): Boolean {
        if (h.active) activeAtMs = nowMs
        return nowMs - activeAtMs > Idle.AFTER_MS
    }
}

/** When to (re)send [io.github.purplepalm.birdair.core.Wire.HELLO]: soon while nothing comes, rarely while data flows. */
object Hello {
    const val RETRY_MS = 5_000L
    const val EVERY_MS = 30_000L

    fun due(rxAtMs: Long?, lastHelloMs: Long?, nowMs: Long): Boolean {
        if (lastHelloMs == null) return true
        val since = nowMs - lastHelloMs
        return since >= if (Health.stale(rxAtMs, nowMs)) RETRY_MS else EVERY_MS
    }
}

/** Text of the Ongoing Activity / notification: "54 % · 3,2 км" while live, else the state. */
object RideStatus {
    /** Status updates at most this often: each one reposts the notification. */
    const val EVERY_MS = 30_000L

    /** The top lamp's line first (as on the dial): «Перегрев — сбавь газ · 11 % · 5,8 км». */
    fun text(t: Telemetry, h: Health): String {
        if (!h.live) return Cluster.statusLabel(h, Cluster.lamps(t), gauge = false) ?: "Bird Air"
        val parts = ArrayList<String>(3)
        TellTales.top(Cluster.lamps(t))?.text?.takeIf { it.isNotBlank() }?.let { parts += it }
        Battery.level(t)?.let { parts += Fmt.pct(it) }
        t.tripKm?.let { parts += Fmt.withUnit(Fmt.dist(it, t.units), 1, Fmt.distUnit(t.units)) }
        return if (parts.isEmpty()) "едем" else parts.joinToString(" · ")
    }

    /** The top lamp as it changes the status: a new or stronger one is shown at once, not after [EVERY_MS]. */
    fun warningKey(t: Telemetry, h: Health): String? =
        if (!h.live) null else TellTales.top(Cluster.lamps(t))?.let { it.id + ":" + it.level }

    fun due(text: String, shown: String?, key: String?, shownKey: String?, sinceMs: Long): Boolean =
        text != shown && (key != shownKey || sinceMs >= EVERY_MS)
}
