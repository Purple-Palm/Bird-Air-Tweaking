package io.github.purplepalm.birdair.phone

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.purplepalm.birdair.core.AppOdometer
import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** The part being written (1 for a recording that never rotated). */
    val part: Int = 1,
    val savedName: String? = null,
    val savedBytes: Long = 0,
    val message: String = "",
    val err: Boolean = false,
    val ghBusy: Boolean = false,
    val ghMessage: String = "",
    val ghErr: Boolean = false,
    val ghUrl: String? = null,
    /** «Отправить все». */
    val bulkBusy: Boolean = false,
    val bulkMessage: String = "",
    val bulkErr: Boolean = false,
    val markMessage: String = "",
    val markErr: Boolean = false,
)

data class WatchStatus(
    val nodes: List<String> = emptyList(),
    val checked: Boolean = false,
    val available: Boolean = true,
    /** elapsedRealtime of the last «hello» from the watch app. */
    val helloAt: Long = 0,
    /** What the watch app said about its version in that hello; null: nothing (an older build) or no hello yet. */
    val version: WatchVersion? = null,
    val message: String = "",
    val err: Boolean = false,
)

data class ControlState(val allow: Boolean = false, val message: String = "", val err: Boolean = false)

data class DecoderInfo(val version: Int = 0, val source: String = "", val note: String = "")

/** A sys line for the screen. */
data class Event(val at: Long, val text: String, val err: Boolean)

/** A marker set from the screen: [t] on the session clock. */
data class Marker(val t: Long, val label: String)

/** The in-app update: what version.json said, the download, the installer. */
data class UpdateState(
    val latest: UpdateInfo? = null,
    val checkedAt: Long = 0,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    /** 0…1 while downloading, null when the size is unknown. */
    val progress: Float? = null,
    val message: String = "",
    val err: Boolean = false,
    /** Android wants «Установка неизвестных приложений» allowed for this app first. */
    val needsPermission: Boolean = false,
)

/**
 * Process-wide state: what the service knows, what the screen shows. The service writes, the UI reads
 * StateFlows and calls the few actions here. The tracker lives as long as the process; a session resets it.
 * Publishing: [pipeline] — every notification wakes it, the screen and the watch get each new value at once.
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

    val settings = MutableStateFlow(UiSettings())
    val pipeline = LivePipeline(tracker, clock = { session?.now() ?: 0L }, fast = { settings.value.fastPower })
    val telemetry: StateFlow<Telemetry> get() = pipeline.telemetry
    val status = MutableStateFlow(LinkStatus())
    val scan = MutableStateFlow(ScanState())
    val log = MutableStateFlow(LogStatus())
    val watch = MutableStateFlow(WatchStatus())
    val control = MutableStateFlow(ControlState())
    val decoder = MutableStateFlow(DecoderInfo())
    val gh = MutableStateFlow(GhConfig())
    val hasToken = MutableStateFlow(false)
    val events = MutableStateFlow<List<Event>>(emptyList())
    val update = MutableStateFlow(UpdateState())
    /** «Описание лога» as typed. */
    val note = MutableStateFlow("")
    /** «Неотправленные логи». */
    val unsent = MutableStateFlow<List<LocalLog>>(emptyList())

    /** Chart history of this session and a counter the chart redraws on (at most every [CHART_EVERY_MS]). */
    val samples = SampleRing()
    val chartRev = MutableStateFlow(0L)
    val markers = MutableStateFlow<List<Marker>>(emptyList())
    private var chartAt = -CHART_EVERY_MS
    private var chartPending = false

    private val alerts = WarningAlerts()
    private val sag = LoadSag()
    private var odoSaved: AppOdometer.State? = null
    private var odoSavedAt = 0L

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
        note.value = prefs.logNote
        tracker.units = settings.value.units
        tracker.odometerState = prefs.odometer
        odoSaved = tracker.odometerState
        update.value = UpdateState(latest = Versions.parse(prefs.updateJson), checkedAt = prefs.updateCheckedAt)
        pipeline.onPublish = ::published
        pipeline.decorate = ::decorate
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
        scope.launch(Dispatchers.IO) { refreshUnsent(app) }
        Updater.startDaily(app)
    }

    private fun useDecoder(c: DecoderCandidate) {
        tracker.setDecoder(Decoder(c.spec))
        decoder.value = DecoderInfo(c.spec.version, c.source, c.spec.note)
    }

    /** A new recording: values, energy, the power scale and the chart start over; the app odometer goes on. */
    fun newSession(name: String?, address: String?): Session {
        val t0 = SystemClock.elapsedRealtime()
        val s = Session(System.currentTimeMillis(), { SystemClock.elapsedRealtime() - t0 })
        s.device(name, address)
        s.note = note.value
        tracker.reset()
        tracker.epochOffsetMs = s.startedAtMs
        pipeline.reset()
        samples.clear()
        markers.value = emptyList()
        chartAt = -CHART_EVERY_MS
        chartRev.update { it + 1 }
        alerts.reset()
        sag.reset()
        session = s
        log.value = LogStatus()
        return s
    }

    /** The current values; when the service is not running, the last ones with the link idle. */
    fun snapshotNow(): Telemetry {
        val t = telemetry.value
        return if (session != null && status.value.running) t else t.copy(link = Link.IDLE, streaming = false)
    }

    /** What goes to the watch: the snapshot and the two display settings as extras «fast» / «smooth» (1/0). */
    fun forWatch(t: Telemetry): Telemetry {
        val s = settings.value
        return t.copy(extras = t.extras + mapOf(EXTRA_FAST to if (s.fastPower) 1.0 else 0.0, EXTRA_SMOOTH to if (s.smooth) 1.0 else 0.0))
    }

    /** Before each publish: «под нагрузкой» for battery_low from the phone's [LoadSag] (screen, notification and watch). */
    private fun decorate(t: Telemetry, fresh: Boolean): Telemetry {
        if (fresh) sag.add(t.t, t.voltage, t.currentFastA ?: t.currentA)
        return if (t.warnings.isEmpty()) t else t.copy(warnings = sag.fix(t.warnings, t.t))
    }

    /** The pipeline's consumer, after each publish (Dispatchers.Default; never the BLE thread). */
    private fun published(t: Telemetry, fresh: Boolean) {
        val s = session ?: return
        // from the first 3A 17 of the session: what the app odometer added for a ride without the app is already in
        if (s.appOdoStartKm == null && t.odometerKm != null && t.appOdometerKm != null) s.appOdoStartKm = t.appOdometerKm
        if (fresh) { samples.add(t.t, t); chartPending = true }
        if (chartPending && (t.t - chartAt >= CHART_EVERY_MS || !fresh)) { chartAt = t.t; chartPending = false; chartRev.update { it + 1 } }
        watchLink?.offer(forWatch(t))
        val d = alerts.update(t.warnings, System.currentTimeMillis())
        // the watch app vibrates for them itself while it is active: then the notification is not mirrored there
        val phoneOnly = d.action != WarningAlerts.Action.NONE && Versions.watchBuzzes(watch.value, SystemClock.elapsedRealtime())
        when (d.action) {
            WarningAlerts.Action.ALERT -> Notifications.warning(app, d.warnings, loud = true, localOnly = phoneOnly)
            WarningAlerts.Action.UPDATE -> Notifications.warning(app, d.warnings, loud = false, localOnly = phoneOnly)
            WarningAlerts.Action.CANCEL -> Notifications.cancelWarning(app)
            WarningAlerts.Action.NONE -> {}
        }
        saveOdometer(false)
        s.rotateIfNeeded()?.let { done -> rotated(s, done) }
    }

    /** A part reached [Session.MAX_ENTRIES]: it is saved (and sent, with auto-send) as a log of its own. */
    private fun rotated(s: Session, done: LogPart) {
        sys("Лог: часть ${done.number} закрыта (${Fmt.n(done.recorder.size.toDouble())} записей), пишу часть ${s.part.number}")
        log.update { it.copy(part = s.part.number) }
        scope.launch(Dispatchers.IO) {
            LogStore.save(app, s, done, force = true)
            if (LogStore.autoDue(s, done, force = true)) LogStore.upload(app, s, done, auto = true)
        }
    }

    /** Keeps the app odometer: when it moved (0,1 km, another power-on), at most every 5 s; [force]: now (stop). */
    fun saveOdometer(force: Boolean) {
        val st = tracker.odometerState ?: return
        val old = odoSaved
        val moved = old == null || old.totalKm != st.totalKm || old.baseKm != st.baseKm || old.tripKm != st.tripKm
        if (!moved && (!force || old?.wallMs == st.wallMs)) return
        val now = System.currentTimeMillis()
        if (!force && now - odoSavedAt < ODO_SAVE_MS) return
        odoSaved = st
        odoSavedAt = now
        prefs.odometer = st
    }

    /** «Исправить одометр»: the app odometer is [km] from now on; the scooter's power-on goes on where it was.
     *  The session's «Расход» start moves with it: a correction is not distance ridden. */
    fun setOdometer(km: Double) {
        if (!km.isFinite() || km < 0) return
        val cur = tracker.odometerState
        val st = cur?.copy(totalKm = AppOdometer.tenths(km) / 10.0) ?: AppOdometer.State(AppOdometer.tenths(km) / 10.0, 0.0, 0.0, null)
        tracker.odometerState = st
        session?.let { s -> val a = s.appOdoStartKm; if (a != null && cur != null) s.appOdoStartKm = a + (st.totalKm - cur.totalKm) }
        odoSaved = st
        odoSavedAt = System.currentTimeMillis()
        prefs.odometer = st
        sys("Одометр приложения: " + Fmt.n(st.totalKm, 1) + " км (исправлен вручную)")
        pipeline.set(telemetry.value.copy(appOdometerKm = st.totalKm))
        pipeline.signal()
    }

    /**
     * Right before the system installer replaces this app: Android kills the process without onDestroy and does not
     * restart the service, so the running recording (every changed part) and the app odometer are saved now.
     */
    suspend fun beforeInstall(ctx: Context) {
        saveOdometer(true)
        val s = session ?: return
        if (status.value.running) {
            sys("Обновление: сохраняю лог перед установкой, связь с самокатом прервётся")
            LogStore.saveAll(ctx.applicationContext, s)
        }
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
        pipeline.set(telemetry.value.copy(link = link))
        pipeline.signal()
    }

    fun updateUi(f: (UiSettings) -> UiSettings) {
        val v = f(settings.value)
        settings.value = v
        prefs.ui = v
        tracker.units = v.units
        pipeline.signal()
    }

    /** «Описание лога»: the running (or last) recording gets it at once, the next one starts with it. */
    fun setNote(text: String) {
        val t = text.take(NOTE_MAX)
        note.value = t
        prefs.logNote = t
        session?.note = t
    }

    /** «Метка»: a timed marker in the running log (and on the chart). */
    fun mark(label: String) {
        val s = session
        val l = label.trim().take(60)
        when {
            s == null || !status.value.running -> log.update { it.copy(markMessage = "Метка ставится в идущую запись — сначала «Подключить».", markErr = true) }
            l.isEmpty() -> log.update { it.copy(markMessage = "Впиши текст метки.", markErr = true) }
            else -> {
                val t = s.now()
                s.recorder.mk(l, t)
                markers.update { (it + Marker(t, l)).takeLast(200) }
                chartRev.update { it + 1 }
                log.update { it.copy(markMessage = "Метка «$l» в ${Fmt.clock(s.startedAtMs + t)}", markErr = false) }
            }
        }
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

    fun watchHello(v: WatchVersion?) = watch.update { it.copy(helloAt = SystemClock.elapsedRealtime(), version = v) }

    /** File names of the running recording's parts. */
    fun currentFiles(): Set<String> {
        val s = session ?: return emptySet()
        if (!status.value.running) return emptySet()
        return s.parts.mapNotNull { it.savedFile }.toSet()
    }

    /** Re-reads «Неотправленные логи» (blocking: IO). Returns the list, newest first. */
    fun refreshUnsent(ctx: Context): List<LocalLog> {
        val list = LogFiles.unsent(LogStore.localLogs(ctx, currentFiles()), prefs.sentLogs())
        unsent.value = list
        return list
    }

    const val EXTRA_FAST = "fast"
    const val EXTRA_SMOOTH = "smooth"
    const val CHART_EVERY_MS = 200L
    const val ODO_SAVE_MS = 5000L
    const val NOTE_MAX = 500
}
