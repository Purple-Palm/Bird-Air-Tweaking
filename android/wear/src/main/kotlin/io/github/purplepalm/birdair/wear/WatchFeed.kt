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
 * (link CONNECTED and streaming) — both on the watch's elapsedRealtime clock.
 */
data class Feed(val t: Telemetry = Telemetry(), val rxAtMs: Long? = null, val liveAtMs: Long? = null) {
    /** The feed after a message on [Wire.PATH]; null (keep the old one) for a malformed message. */
    fun received(bytes: ByteArray, nowMs: Long): Feed? {
        val t = Wire.decode(bytes) ?: return null
        val live = t.link == Link.CONNECTED && t.streaming
        return Feed(t, nowMs, if (live) nowMs else liveAtMs)
    }
}

/**
 * The phone's stream, shared by [MainActivity] and [RideService]: a MessageClient listener on [Wire.PATH]
 * registered while at least one of them exists (so it keeps updating in ambient, and with the activity gone
 * while the ride service runs), and [Wire.HELLO] to every connected node on the first [acquire], then as
 * [Hello] says. Main thread only.
 */
object WatchFeed {
    private val state = MutableStateFlow(Feed())
    val feed: StateFlow<Feed> = state.asStateFlow()

    private val scope = MainScope()
    private var users = 0
    // the clients hold the application context only
    @SuppressLint("StaticFieldLeak") private var messages: MessageClient? = null
    @SuppressLint("StaticFieldLeak") private var nodes: NodeClient? = null
    private var job: Job? = null
    private var lastHelloMs: Long? = null

    private val listener = MessageClient.OnMessageReceivedListener { ev ->
        if (ev.path == Wire.PATH) state.value.received(ev.data, SystemClock.elapsedRealtime())?.let { state.value = it }
    }

    @MainThread
    fun acquire(ctx: Context) {
        if (users++ > 0) return
        val mc = Wearable.getMessageClient(ctx.applicationContext)
        val nc = Wearable.getNodeClient(ctx.applicationContext)
        messages = mc
        nodes = nc
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

    private suspend fun sendHello(mc: MessageClient, nc: NodeClient, now: Long) {
        lastHelloMs = now
        try {
            for (n in nc.connectedNodes.await()) {
                try { mc.sendMessage(n.id, Wire.HELLO, ByteArray(0)).await() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { /* that node is gone; the others still get it */ }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { /* no Wear API or no phone: the next due hello retries */ }
    }
}
