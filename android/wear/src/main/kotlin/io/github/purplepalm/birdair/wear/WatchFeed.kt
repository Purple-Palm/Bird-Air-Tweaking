package io.github.purplepalm.birdair.wear

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.Wearable
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The last telemetry from the phone. [rxAtMs]: when it came, [liveAtMs]: when the last live one came
 * (link CONNECTED and streaming), [freshAtMs]: when the last one with new data came (its [Telemetry.seq] moved; a
 * phone's heartbeat repeats the old seq) — all on the watch's elapsedRealtime clock. [flags]: the phone's «fast» and
 * «smooth» settings, from the same messages (see [PhoneFlags]).
 */
data class Feed(
    val t: Telemetry = Telemetry(),
    val rxAtMs: Long? = null,
    val liveAtMs: Long? = null,
    val flags: PhoneFlags = PhoneFlags(),
    val freshAtMs: Long? = null,
) {
    val live: Boolean get() = t.link == Link.CONNECTED && t.streaming

    /** The feed after a message on [Wire.PATH]; null (keep the old one) for a malformed or [late] message. */
    fun received(bytes: ByteArray, nowMs: Long): Feed? {
        val n = Wire.decode(bytes) ?: return null
        if (late(n, nowMs)) return null
        val live = n.link == Link.CONNECTED && n.streaming
        // seq 0: a phone without it (or without data yet) — every message counts as new
        val fresh = if (rxAtMs == null || n.seq == 0L || n.seq != t.seq) nowMs else freshAtMs
        return Feed(n, nowMs, if (live) nowMs else liveAtMs, flags.merge(PhoneFlags.parse(bytes)), fresh)
    }

    /**
     * An older snapshot that arrived after a newer one (the phone's answer to a hello overtaken by its stream):
     * older phone time and no newer data ([Telemetry.seq]). A phone that started again (its clock and seq restart)
     * is believed after [LATE_MS].
     */
    fun late(n: Telemetry, nowMs: Long): Boolean {
        val rx = rxAtMs ?: return false
        return n.t < t.t && n.seq <= t.seq && nowMs - rx < LATE_MS
    }

    companion object {
        const val LATE_MS = 1500L
    }
}

/**
 * The phone's stream, shared by [MainActivity] and [RideService]: a MessageClient listener on [Wire.PATH]
 * registered while at least one of them exists (so it keeps updating in ambient, and with the activity gone
 * while the ride service runs), and [Wire.HELLO] (with [HelloInfo]) to every connected node on the first [acquire],
 * then as [Hello] says. The phone's lamps vibrate here, once per [LampAlarm] event, whichever of the
 * two is alive, unless «Вибрация ламп» is off ([WatchPrefs.lampBuzz]). Main thread only.
 */
object WatchFeed {
    private val state = MutableStateFlow(Feed())
    val feed: StateFlow<Feed> = state.asStateFlow()

    private val scope = MainScope()
    private var users = 0
    // the clients and the vibrator hold the application context only
    @SuppressLint("StaticFieldLeak") private var messages: MessageClient? = null
    @SuppressLint("StaticFieldLeak") private var nodes: NodeClient? = null
    @SuppressLint("StaticFieldLeak") private var buzzer: Buzzer? = null
    private var job: Job? = null
    private var lastHelloMs: Long? = null
    // process-wide: reopening the gauge or restarting the ride service does not buzz the same warning again
    private val alarm = LampAlarm()

    /** Each message is applied as it comes (main thread): the screen redraws on it, not on a timer. */
    private val listener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path != Wire.PATH) return@OnMessageReceivedListener
        val now = SystemClock.elapsedRealtime()
        val f = state.value.received(ev.data, now) ?: return@OnMessageReceivedListener
        state.value = f
        // an older phone sends no lamps: its battery warnings stand in (Cluster.lamps); switched off, the alarm still
        // follows the lamps, so switching it on again does not buzz what is already lit
        val buzz = alarm.onMessage(Cluster.lamps(f.t), f.live, now, f.t.speedKmh)
        if (buzz != null && Settings.prefs.value.lampBuzz) buzzer?.play(buzz)
    }

    @MainThread
    fun acquire(ctx: Context) {
        if (users++ > 0) return
        val mc = Wearable.getMessageClient(ctx.applicationContext)
        val nc = Wearable.getNodeClient(ctx.applicationContext)
        messages = mc
        nodes = nc
        buzzer = Buzzer(ctx.applicationContext)
        lastHelloMs = null
        mc.addListener(listener)
        job = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (Hello.due(state.value.rxAtMs, lastHelloMs, now)) sendHello(mc, nc, now)
                delay(1000)
            }
        }
    }

    @MainThread
    fun release() {
        if (users == 0 || --users > 0) return
        job?.cancel()
        job = null
        messages?.removeListener(listener)
        messages = null
        nodes = null
        buzzer = null
    }

    /** Ask the phone for data now (the screen came back), unless a hello went out within the last second. */
    @MainThread
    fun hello() {
        val mc = messages ?: return
        val nc = nodes ?: return
        val now = SystemClock.elapsedRealtime()
        if (lastHelloMs?.let { now - it < 1000 } == true) return
        scope.launch { sendHello(mc, nc, now) }
    }

    /** The hello's payload: this build's version, for the phone's «Часы» card. */
    private val HELLO: ByteArray by lazy { HelloInfo.THIS.encode() }

    private suspend fun sendHello(mc: MessageClient, nc: NodeClient, now: Long) {
        lastHelloMs = now
        try {
            for (n in nc.connectedNodes.await()) {
                try { mc.sendMessage(n.id, Wire.HELLO, HELLO).await() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { /* that node is gone; the others still get it */ }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* no Wear API or no phone: the next due hello retries */ }
    }
}
