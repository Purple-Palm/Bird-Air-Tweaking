package io.github.purplepalm.birdair.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.Wire
import io.github.purplepalm.birdair.dial.FaceChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The watch over the Wear Data Layer: connected nodes, «Часы» status, opening the watch app. */
object Watch {
    /** The watch app's MainActivity answers this (an intent filter: VIEW + BROWSABLE, birdair://open). */
    const val OPEN_URI = "birdair://open"

    /** Connected Wear nodes, nearby first; updates [ScooterRepo.watch]. */
    suspend fun nodes(ctx: Context): List<Node> {
        val list = try {
            Wearable.getNodeClient(ctx).connectedNodes.await().sortedByDescending { it.isNearby }
        } catch (e: Exception) {
            ScooterRepo.watch.update { it.copy(nodes = emptyList(), checked = true, available = false) }
            return emptyList()
        }
        ScooterRepo.watch.update { it.copy(nodes = list.map { n -> n.displayName }, checked = true, available = true) }
        return list
    }

    /** «Открыть на часах»: RemoteActivityHelper with birdair://open on the first connected watch. */
    fun open(ctx: Context, scope: CoroutineScope) {
        scope.launch {
            val node = nodes(ctx).firstOrNull()
            if (node == null) { say("Часы не на связи: проверь Bluetooth и Galaxy Wearable.", true); return@launch }
            val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(OPEN_URI))
            val exec = Executors.newSingleThreadExecutor()
            try {
                val f = RemoteActivityHelper(ctx, exec).startRemoteActivity(intent, node.id)
                f.addListener({
                    try {
                        f.get()
                        say("Открываю на часах «${node.displayName}»…")
                    } catch (e: Exception) {
                        say("Часы не открыли приложение: поставь на часы bird-air-watch.apk из того же релиза (android/README.md) или открой Bird Air там сам.", true)
                    } finally { exec.shutdown() }
                }, exec)
            } catch (e: Exception) {
                exec.shutdown()
                say("Не получилось: ${e.message ?: "ошибка Wear OS"}", true)
            }
        }
    }

    private fun say(text: String, err: Boolean = false) = ScooterRepo.watch.update { it.copy(message = text, err = err) }
}

/**
 * While the service runs: every published snapshot to every connected node at once. A snapshot that comes while a
 * send is in flight waits, and only the newest one goes next (conflated StateFlow); sends are at least
 * [MIN_GAP_MS] apart (≤ 25 a second). The pipeline publishes at least once a second, so an idle link still gets a
 * 1 s heartbeat. Nodes are refreshed every 10 s and after a failed send.
 */
class WatchLink(private val ctx: Context, private val scope: CoroutineScope) {
    private val messages = Wearable.getMessageClient(ctx)
    private val latest = MutableStateFlow<Telemetry?>(null)
    @Volatile private var nodes: List<Node> = emptyList()
    @Volatile private var refreshedAt = 0L
    private val jobs = ArrayList<Job>()

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch(Dispatchers.IO) {
            latest.filterNotNull().collect {
                val at = SystemClock.elapsedRealtime()
                send(it)
                val left = MIN_GAP_MS - (SystemClock.elapsedRealtime() - at)
                if (left > 0) delay(left)
            }
        }
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) { refresh(); delay(NODES_EVERY_MS) }
        }
    }

    fun stop() { jobs.forEach { it.cancel() }; jobs.clear() }

    fun offer(t: Telemetry) { latest.value = t }

    /** A watch said hello: it may be new, look again now. */
    fun hello() { scope.launch(Dispatchers.IO) { refresh() } }

    private suspend fun refresh() {
        refreshedAt = SystemClock.elapsedRealtime()
        nodes = Watch.nodes(ctx)
    }

    private suspend fun send(t: Telemetry) {
        val ns = nodes
        if (ns.isEmpty()) return
        val b = Wire.encode(t)
        var failed = false
        for (n in ns) {
            try { messages.sendMessage(n.id, Wire.PATH, b).await() } catch (e: Exception) { failed = true }
        }
        if (failed && SystemClock.elapsedRealtime() - refreshedAt > 2000) refresh()
    }

    companion object {
        const val NODES_EVERY_MS = 10_000L
        /** At most 25 messages a second. */
        const val MIN_GAP_MS = 40L
    }
}

/**
 * «Циферблат часов» over the Wear Data Layer ([Wire.FACE]): the phone's pick is a DataItem of this node (the watch reads
 * it when it starts, so it gets it even if it was off), the watch's report one of its own ([FaceChoice.WATCH]): what it
 * draws, its own pick included — the newer one wins on both sides.
 */
object WatchFaces {
    fun put(ctx: Context, c: FaceChoice) {
        val req = PutDataMapRequest.create(Wire.FACE).apply {
            dataMap.putString(FaceChoice.KEY_FACE, c.face.key)
            dataMap.putLong(FaceChoice.KEY_AT, c.atMs)
            dataMap.putString(FaceChoice.KEY_BY, FaceChoice.PHONE)
        }.asPutDataRequest().setUrgent()
        ScooterRepo.scope.launch {
            try { Wearable.getDataClient(ctx.applicationContext).putDataItem(req).await() }
            catch (e: Exception) { ScooterRepo.watch.update { it.copy(message = "Циферблат не ушёл на часы: ${e.message ?: "Wear OS недоступен"}", err = true) } }
        }
    }

    /** The watch's report, from the items there are (the screen opened, the app started). */
    suspend fun read(ctx: Context) {
        try {
            val items = Wearable.getDataClient(ctx.applicationContext).getDataItems(Uri.parse("wear://*" + Wire.FACE), DataClient.FILTER_LITERAL).await()
            try { for (it in items) consider(it) } finally { items.release() }
        } catch (e: Exception) { /* no Wear API: the card says so */ }
    }

    /** One DataItem on [Wire.FACE]: the watch's is its report. */
    fun consider(item: DataItem) {
        if (item.uri.path != Wire.FACE) return
        val m = try { DataMapItem.fromDataItem(item).dataMap } catch (e: RuntimeException) { return }
        val c = FaceChoice.of(m.getString(FaceChoice.KEY_FACE), m.getLong(FaceChoice.KEY_AT), m.getString(FaceChoice.KEY_BY)) ?: return
        if (c.by == FaceChoice.WATCH) ScooterRepo.watchFace(c)
    }
}

/**
 * Answers the watch app's «hello» at once with the current snapshot, whether the service runs or not. The hello may
 * carry the watch app's version ([Versions.watch]); an empty one is a watch build from before that. Also hears the
 * watch's face report ([WatchFaces]).
 */
class WatchListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        ScooterRepo.init(applicationContext)
        for (e in events) if (e.type == DataEvent.TYPE_CHANGED) WatchFaces.consider(e.dataItem)
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != Wire.HELLO) return
        ScooterRepo.init(applicationContext)
        ScooterRepo.watchHello(Versions.watch(e.data))
        ScooterRepo.watchLink?.hello()
        val t = ScooterRepo.forWatch(ScooterRepo.snapshotNow())
        try {
            Tasks.await(Wearable.getMessageClient(this).sendMessage(e.sourceNodeId, Wire.PATH, Wire.encode(t)), 5, TimeUnit.SECONDS)
        } catch (x: Exception) { /* the next publish (at least once a second) will try again while the service runs */ }
    }
}
