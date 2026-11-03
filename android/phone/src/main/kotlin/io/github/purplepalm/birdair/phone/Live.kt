package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** What the bar, the notification, the chart and the watch call «power»: the fast estimate when it is on and known. */
object Power {
    fun watts(t: Telemetry, fast: Boolean): Double? = if (fast) t.powerFastW ?: t.powerW else t.powerW
    fun amps(t: Telemetry, fast: Boolean): Double? = if (fast) t.currentFastA ?: t.currentA else t.currentA
}

/**
 * The power bar's scale for the power that is shown: the tracker's rule max(prev·0.999, |P|·1.1, 300 W), applied once
 * per new power value ([Telemetry.powerAtMs] changed). The tracker's own [Telemetry.peakW] follows the BMS power,
 * which peaks lower and later than the fast estimate.
 */
class PowerScale {
    var peakW = TelemetryTracker.PEAK_MIN
        private set
    private var lastAt: Long? = null

    fun update(t: Telemetry, fast: Boolean): Double {
        val at = t.powerAtMs ?: return peakW
        if (at == lastAt) return peakW
        lastAt = at
        val p = Power.watts(t, fast) ?: return peakW
        if (!p.isFinite()) return peakW
        peakW = maxOf(peakW * TelemetryTracker.PEAK_DECAY, abs(p) * TelemetryTracker.PEAK_HEADROOM, TelemetryTracker.PEAK_MIN)
        return peakW
    }

    fun reset() { peakW = TelemetryTracker.PEAK_MIN; lastAt = null }
}

/**
 * Publish on change instead of on a timer. The BLE callback feeds the tracker ([onRx]: a decode under the tracker's
 * lock, nothing else) and wakes one consumer coroutine ([run]) that takes the snapshot and hands it on; the wake-up is
 * a conflated channel, so a burst of notifications during one publish becomes a single next publish with the newest
 * values. With nothing new for [heartbeatMs] it publishes anyway: «streaming» turns false, the link state shows.
 *
 * [telemetry]: the latest published snapshot, its [Telemetry.peakW] replaced by the scale of the shown power
 * ([PowerScale]). [onPublish] runs on the consumer, with fresh = true when the snapshot carries new data
 * ([Telemetry.seq] moved). [publishNow] is only called by the consumer (or a test).
 */
class LivePipeline(
    private val tracker: TelemetryTracker,
    private val clock: () -> Long,
    private val fast: () -> Boolean,
    private val heartbeatMs: Long = HEARTBEAT_MS,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = state.asStateFlow()
    private val scale = PowerScale()
    /** A new tracker starts at seq 0 without data. */
    private var lastSeq = 0L
    @Volatile var onPublish: ((Telemetry, Boolean) -> Unit)? = null
    /** Adjusts a snapshot before it is published (the phone's own numbers on top of the core's); fresh as above. */
    @Volatile var decorate: ((Telemetry, Boolean) -> Telemetry)? = null
    /** Snapshots published so far (for the tests and the screen's debug line). */
    @Volatile var published = 0L
        private set

    /** A notification from the scooter at [tMs] (the tracker's clock). Never blocks. */
    fun onRx(service: String, char: String, bytes: ByteArray, tMs: Long) {
        tracker.onNotification(service, char, bytes, tMs)
        wake.trySend(Unit)
    }

    /** Something else changed (link state, settings): publish soon. */
    fun signal() { wake.trySend(Unit) }

    /** A new recording, after [TelemetryTracker.reset]: the scale starts over, and what the tracker has now is not new. */
    fun reset() {
        val seq = tracker.snapshot(clock()).seq
        synchronized(scale) { scale.reset(); lastSeq = seq }
    }

    /** Shows [t] without the tracker (link state changes; the consumer's next publish has them too). */
    fun set(t: Telemetry) { state.value = t }

    /** «Отключить»: [t] is the last value; a publish still in flight on the consumer must not overwrite it. */
    fun halt(t: Telemetry) { halted = true; state.value = t }
    @Volatile private var halted = false

    fun publishNow(): Telemetry {
        if (halted) return state.value
        val raw = tracker.snapshot(clock())
        val t0: Telemetry
        val fresh: Boolean
        synchronized(scale) {
            t0 = raw.copy(peakW = scale.update(raw, fast()))
            fresh = raw.seq != lastSeq
            lastSeq = raw.seq
        }
        val t = decorate?.invoke(t0, fresh) ?: t0
        state.value = t
        published++
        onPublish?.invoke(t, fresh)
        return t
    }

    /** The consumer: until cancelled. */
    suspend fun run() {
        halted = false
        while (currentCoroutineContext().isActive) {
            withTimeoutOrNull(heartbeatMs) { wake.receive() }
            publishNow()
        }
    }

    companion object {
        /** Publish at least this often while running, even without new data (and so the watch's heartbeat). */
        const val HEARTBEAT_MS = 1000L
    }
}
