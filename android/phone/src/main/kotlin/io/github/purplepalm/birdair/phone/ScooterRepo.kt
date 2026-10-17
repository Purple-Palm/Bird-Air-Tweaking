package io.github.purplepalm.birdair.phone

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.GattService
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.LogRecorder
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One recording: from «Подключить» to «Отключить», reconnects included. Times: ms since [t0] (elapsedRealtime). */
class Session(val startedAtMs: Long, private val t0: Long) {
    val recorder = LogRecorder(startedAtMs)
    @Volatile var gatt: List<GattService> = emptyList()
    /** Odometer at the first frame of the session: the distance for Wh/km. */
    @Volatile var odoStartKm: Double? = null
    val rx = AtomicLong()

    // log files
    @Volatile var savedPushed = -1L
    // GitHub: the file this recording went to, its sha for the next overwrite, what was in it
    @Volatile var gh: GhPut? = null
    @Volatile var ghDest: String? = null
    @Volatile var ghPushed = -1L
    @Volatile var ghAt = 0L
    @Volatile var ghFailedAt = 0L
    /** When the last upload was asked for (wall clock): spaces out uploads after drops. */
    @Volatile var ghTryAt = 0L

    fun now(): Long = SystemClock.elapsedRealtime() - t0
    fun base(): String = recorder.fileBase("log")

    companion object {
        fun start() = Session(System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }
}

data class LinkStatus(
    val running: Boolean = false,
    val link: Link = Link.IDLE,
    val deviceName: String? = null,
    val address: String? = null,
    val message: String = "",
    val err: Boolean = false,
)

data class Found(val address: String, val name: String?, val rssi: Int)

data class ScanState(val scanning: Boolean = false, val all: Boolean = false, val found: List<Found> = emptyList(), val message: String = "", val shown: Boolean = false)

data class LogStatus(
    val entries: Int = 0,
    val savedName: String? = null,
    val savedBytes: Long = 0,
    val message: String = "",
    val err: Boolean = false,
    val ghBusy: Boolean = false,
    val ghMessage: String = "",
    val ghErr: Boolean = false,
    val ghUrl: String? = null,
)

data class WatchStatus(
    val nodes: List<String> = emptyList(),
    val checked: Boolean = false,
    val available: Boolean = true,
    /** elapsedRealtime of the last «hello» from the watch app. */
    val helloAt: Long = 0,
    val message: String = "",
    val err: Boolean = false,
)

data class ControlState(val allow: Boolean = false, val message: String = "", val err: Boolean = false)

data class DecoderInfo(val version: Int = 0, val source: String = "", val note: String = "")

/** A sys line for the screen. */
data class Event(val at: Long, val text: String, val err: Boolean)

/**
 * Process-wide state: what the service knows, what the screen shows. The service writes, the UI reads
 * StateFlows and calls the few actions here. The tracker lives as long as the process; a session resets it.
 */
object ScooterRepo {
    lateinit var prefs: Prefs
        private set
    private lateinit var app: Context

    // saves, uploads, taps: a throw that slipped through is logged, it does not end the process mid-ride
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("BirdAir", "background task failed", e) })
    val tracker = TelemetryTracker(Decoder.EMPTY)

    @Volatile var session: Session? = null
        private set
    /** The service's link and watch sender while it runs. */
    @Volatile var link: ScooterLink? = null
    @Volatile var watchLink: WatchLink? = null

    val telemetry = MutableStateFlow(Telemetry())
    val status = MutableStateFlow(LinkStatus())
    val scan = MutableStateFlow(ScanState())
    val log = MutableStateFlow(LogStatus())
    val watch = MutableStateFlow(WatchStatus())
    val control = MutableStateFlow(ControlState())
    val decoder = MutableStateFlow(DecoderInfo())
    val settings = MutableStateFlow(UiSettings())
    val gh = MutableStateFlow(GhConfig())
    val hasToken = MutableStateFlow(false)
    val events = MutableStateFlow<List<Event>>(emptyList())

    private var inited = false

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        inited = true
        app = ctx.applicationContext
        prefs = Prefs(app)
        settings.value = prefs.ui
        gh.value = prefs.ghConfig
        hasToken.value = prefs.token.isNotEmpty()
        tracker.units = settings.value.units
        status.update { it.copy(deviceName = prefs.deviceName, address = prefs.address) }
        DecoderStore.local(app)?.let { useDecoder(it) }
        scope.launch(Dispatchers.IO) {
            val site = DecoderStore.fetch() ?: return@launch
            if (site.spec.version > tracker.decoder.spec.version) {
                DecoderStore.save(app, site)
                useDecoder(site)
                sys("Декодер v${site.spec.version} с сайта")
            }
        }
    }

    private fun useDecoder(c: DecoderCandidate) {
        tracker.setDecoder(Decoder(c.spec))
        decoder.value = DecoderInfo(c.spec.version, c.source, c.spec.note)
    }

    /** A new recording: values, energy and the power scale start over. */
    fun newSession(name: String?, address: String?): Session {
        val s = Session.start()
        s.recorder.deviceName = name
        s.recorder.deviceId = address
        tracker.reset()
        session = s
        log.value = LogStatus()
        return s
    }

    /** The current values; when the service is not running, the last ones with the link idle. */
    fun snapshotNow(): Telemetry {
        val s = session
        val t = if (s != null && status.value.running) tracker.snapshot(s.now()) else telemetry.value.copy(link = Link.IDLE, streaming = false)
        if (s != null && s.odoStartKm == null && t.odometerKm != null) s.odoStartKm = t.odometerKm
        return t
    }

    /** A service line: into the log and onto the screen. */
    fun sys(text: String, err: Boolean = false) {
        val s = session
        s?.recorder?.sys(text, s.now(), err)
        note(text, err)
    }

    /** Onto the screen only (the log has it already). */
    fun note(text: String, err: Boolean = false) {
        events.update { (it + Event(System.currentTimeMillis(), text, err)).takeLast(6) }
    }

    fun setLink(link: Link, message: String? = null, err: Boolean = false) {
        tracker.link = link
        status.update { it.copy(link = link, message = message ?: it.message, err = if (message != null) err else it.err) }
        telemetry.update { it.copy(link = link) }
    }

    fun updateUi(f: (UiSettings) -> UiSettings) {
        val v = f(settings.value)
        settings.value = v
        prefs.ui = v
        tracker.units = v.units
    }

    fun updateGh(f: (GhConfig) -> GhConfig) {
        val v = f(gh.value)
        gh.value = v
        prefs.ghConfig = v
    }

    fun setToken(raw: String) {
        val t = GitHubApi.cleanToken(raw)
        prefs.token = t
        hasToken.value = t.isNotEmpty()
    }

    fun choose(f: Found) {
        prefs.address = f.address
        prefs.deviceName = f.name
        status.update { it.copy(address = f.address, deviceName = f.name) }
    }

    fun setAllowCommands(on: Boolean) = control.update { ControlState(allow = on) }

    /** A tap on «Управление»: only the whitelisted payloads, only with «Разрешить команды» on. */
    fun command(bytes: ByteArray) {
        if (!control.value.allow) { control.update { it.copy(message = "Сначала включи «Разрешить команды».", err = true) }; return }
        val l = link
        if (l == null || status.value.link != Link.CONNECTED) { control.update { it.copy(message = "Нет связи с самокатом.", err = true) }; return }
        scope.launch {
            val r = l.write(bytes)
            val label = io.github.purplepalm.birdair.core.Commands.label(bytes)
            control.update {
                when (r) {
                    is ScooterLink.WriteResult.Ok -> it.copy(message = "Отправлено: $label", err = false)
                    is ScooterLink.WriteResult.Refused -> it.copy(message = "Не отправлено: ${r.reason}", err = true)
                    is ScooterLink.WriteResult.Failed -> it.copy(message = "Не ушло ($label): ${r.reason}", err = true)
                    ScooterLink.WriteResult.NotConnected -> it.copy(message = "Нет связи с самокатом.", err = true)
                }
            }
        }
    }

    fun watchHello() = watch.update { it.copy(helloAt = SystemClock.elapsedRealtime()) }
}
