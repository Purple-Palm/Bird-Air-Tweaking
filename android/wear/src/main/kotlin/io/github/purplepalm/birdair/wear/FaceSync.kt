package io.github.purplepalm.birdair.wear

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import io.github.purplepalm.birdair.core.Wire
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The dial face between the phone and the watch over the Wear Data Layer ([Wire.FACE]): the phone's pick is a DataItem
 * of its node, the watch's one of its own, and the newest pick wins ([FaceChoice.newest]). The watch reads the items when
 * [WatchFeed] starts (it may have been off when the phone picked), follows them while it runs, and writes its own — its
 * pick, or the phone's once it shows it — so the phone shows what the watch draws. Main thread.
 */
object FaceSync {
    @SuppressLint("StaticFieldLeak") private var data: DataClient? = null   // holds the application context only
    private val scope = MainScope()

    private val listener = DataClient.OnDataChangedListener { events ->
        for (e in events) if (e.type == DataEvent.TYPE_CHANGED) consider(e.dataItem)
        events.release()
    }

    @MainThread
    fun start(ctx: Context) {
        if (data != null) return
        val dc = Wearable.getDataClient(ctx.applicationContext)
        data = dc
        try { dc.addListener(listener, Uri.parse("wear://*" + Wire.FACE), DataClient.FILTER_LITERAL) } catch (e: RuntimeException) { /* no Wear API */ }
        scope.launch {
            try {
                val items = dc.getDataItems(Uri.parse("wear://*" + Wire.FACE), DataClient.FILTER_LITERAL).await()
                try { for (it in items) consider(it) } finally { items.release() }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { /* no phone yet: its change comes to the listener */ }
            report()
        }
    }

    @MainThread
    fun stop() {
        data?.removeListener(listener)
        data = null
    }

    /** The owner picked [face] on the watch: it is the newest pick now. */
    @MainThread
    fun pick(face: DialFace) {
        Settings.set(Settings.prefs.value.copy(face = face, faceAtMs = System.currentTimeMillis()))
        report()
    }

    /** Another node's pick ([FaceChoice.PHONE]), applied when newer than the watch's. */
    private fun consider(item: DataItem) {
        if (item.uri.path != Wire.FACE) return
        val m = try { DataMapItem.fromDataItem(item).dataMap } catch (e: RuntimeException) { return }
        val c = FaceChoice.of(m.getString(FaceChoice.KEY_FACE), m.getLong(FaceChoice.KEY_AT), m.getString(FaceChoice.KEY_BY)) ?: return
        if (c.by == FaceChoice.WATCH) return
        val p = Settings.prefs.value
        if (FaceChoice.newest(p.faceChoice, c) !== c) return
        Settings.set(p.copy(face = c.face, faceAtMs = c.atMs))
        report()
    }

    /** What the watch draws, as its own item: the phone shows it. */
    private fun report() {
        val dc = data ?: return
        val p = Settings.prefs.value
        val req = PutDataMapRequest.create(Wire.FACE).apply {
            dataMap.putString(FaceChoice.KEY_FACE, p.face.key)
            dataMap.putLong(FaceChoice.KEY_AT, p.faceAtMs)
            dataMap.putString(FaceChoice.KEY_BY, FaceChoice.WATCH)
        }.asPutDataRequest().setUrgent()
        scope.launch {
            try { dc.putDataItem(req).await() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* again at the next start */ }
        }
    }
}
