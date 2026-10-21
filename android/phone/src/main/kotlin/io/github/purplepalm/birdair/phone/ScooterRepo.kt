package io.github.purplepalm.birdair.phone

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.purplepalm.birdair.core.AppOdometer
import io.github.purplepalm.birdair.core.Decoder
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TelemetryTracker
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.dial.DialFace
import io.github.purplepalm.birdair.dial.FaceChoice
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** «Отправить все»: [bulkDone] of [bulkTotal] sent so far; the last commit ([bulkCommit] short, [bulkUrl] its page) and
     *  when the run ended with it ([bulkAt], wall clock; 0: never, or it failed). */
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0,
    val bulkCommit: String? = null,
    val bulkUrl: String? = null,
    val bulkAt: Long = 0,
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

/**
 * «Циферблат часов»: [phone] the pick made here, [watch] what the watch says it draws (its DataItem, [FaceChoice.WATCH]);
 * [selected] the newest of the two — a pick on the watch shows here as well; nothing yet: the default.
 */
data class WatchFaceState(val phone: FaceChoice? = null, val watch: FaceChoice? = null) {
    val selected: DialFace get() = FaceChoice.newest(phone, watch)?.face ?: DialFace.DEFAULT
    /** The watch already draws [selected] (its report is not older than this phone's pick). */
    val applied: Boolean get() = watch != null && watch.face == selected
}

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
    /** Bytes so far and in all (Content-Length, else version.json's phoneSize). */
    val gotBytes: Long = 0,
    val totalBytes: Long? = null,
    /** versionCode of the verified APK kept in filesDir/update (it survives the process): «Установить vN». */
    val readyCode: Int? = null,
    /** The owner tapped for it and no recording runs: the activity opens the installer once it is ready. */
    val openWhenReady: Boolean = false,
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
    /** «Циферблат часов» ([WatchFaces]). */
    val face = MutableStateFlow(WatchFaceState())
    /** «Описание лога» as typed. */
    val note = MutableStateFlow("")
    /** «Неотправленные логи». */
    val unsent = MutableStateFlow<List<LocalLog>>(emptyList())

    /** Chart history of this session and a counter the chart redraws on (at most every [CHART_EVERY_MS]). */
    val samples = SampleRing()
    val chartRev = MutableStateFlow(0L)
    val markers = MutableStateFlow<List<Marker>>(emptyList())
    /** «Приборка»: the lamp transitions of this recording, newest first. */
    val journal = MutableStateFlow<List<LampEvent>>(emptyList())

    /** «Еду» / «Стою» by speed with hysteresis ([MotionView]); false when nothing is recorded. */
    val moving = MutableStateFlow(false)
    /** The dash view the owner pinned until the ride ends. */
    val viewPin = MutableStateFlow(ViewPin.AUTO)
    /** This session had live frames at least once (before that the dash says «Подключаюсь…»). */
    val streamed = MutableStateFlow(false)
    /** elapsedRealtime until which every lamp is lit: the bulb check at start and at each (re)connection. */
    val bulbUntil = MutableStateFlow(0L)
    /** The last recording, for the idle dash («до 22:22», «Батарея (последнее)», why it ended). */
    val lastRide = MutableStateFlow<LastRide?>(null)
    private val toastFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-line messages for the screen's snackbar (a download finished while riding…). */
    val toasts: SharedFlow<String> get() = toastFlow
    private val motion = MotionView()
    /** Time above 1 km/h this session, counted per sample like [TripStats.movingSeconds] (the parked trip card, 5 Hz). */
    @Volatile private var movingMs = 0L
    private var prevT = Long.MIN_VALUE
    private var prevKmh = Double.NaN
    /** The BMS's charge when it flagged the cut (the idle dash: «… 31,2 В под нагрузкой, 5 %»). */
    @Volatile private var cutPct: Double? = null
    @Volatile private var endedFor: Session? = null
    private var chartAt = -CHART_EVERY_MS
    private var chartPending = false

    private val cluster = ClusterWatch()
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
        lastRide.value = prefs.lastRide
        bulbUntil.value = SystemClock.elapsedRealtime() + TellTales.BULB_CHECK_MS
        face.value = WatchFaceState(phone = prefs.watchFace)
        pipeline.onPublish = ::published
        // «под нагрузкой» for battery_low and its lamp (screen, notification, watch): one sample per BMS frame
        pipeline.decorate = { t, _ -> sag.decorate(t) }
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
        scope.launch(Dispatchers.IO) { Updater.restore(app) }
        Updater.startDaily(app)
    }

    private fun useDecoder(c: DecoderCandidate) {
        tracker.setDecoder(Decoder(c.spec))
        decoder.value = DecoderInfo(c.spec.version, c.source, c.spec.note)
    }

    /** A new recording: values, energy, the power scale and the chart start over; the app odometer goes on.
     *  [wallMs]: its start on the wall clock (now; a replay passes the log's). */
    fun newSession(name: String?, address: String?, wallMs: Long = System.currentTimeMillis()): Session {
        val t0 = SystemClock.elapsedRealtime()
        val s = Session(wallMs, { SystemClock.elapsedRealtime() - t0 })
        s.device(name, address)
        s.note = note.value
        tracker.reset()
        tracker.epochOffsetMs = s.startedAtMs
        pipeline.reset()
        samples.clear()
        markers.value = emptyList()
        chartAt = -CHART_EVERY_MS
        chartRev.update { it + 1 }
        cluster.reset()
        journal.value = emptyList()
        sag.reset()
        motion.reset()
        moving.value = false
        viewPin.value = ViewPin.AUTO
        streamed.value = false
        cutPct = null
        movingMs = 0L
        prevT = Long.MIN_VALUE
        prevKmh = Double.NaN
        session = s
        // the last «Отправить все» stays on the tile for a while
        log.update { LogStatus(bulkDone = it.bulkDone, bulkTotal = it.bulkTotal, bulkCommit = it.bulkCommit, bulkUrl = it.bulkUrl, bulkAt = it.bulkAt) }
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

    /** A new connection (services discovered): half-received frames go ([TelemetryTracker.restartStreams]) and so does
     *  the voltage under load of the last one. */
    fun restartStreams() {
        tracker.restartStreams()
        sag.restart()
    }

    /** The pipeline's consumer, after each publish (Dispatchers.Default; never the BLE thread). */
    private fun published(t: Telemetry, fresh: Boolean) {
        val s = session ?: return
        // from the first 3A 17 of the session: what the app odometer added for a ride without the app is already in
        if (s.appOdoStartKm == null && t.odometerKm != null && t.appOdometerKm != null) s.appOdoStartKm = t.appOdometerKm
        if (fresh) {
            samples.add(t.t, t)
            chartPending = true
            val dt = t.t - prevT
            if (prevT != Long.MIN_VALUE && dt in 1..Downsample.GAP_MS && prevKmh >= 1.0) movingMs += dt
            prevT = t.t
            prevKmh = t.speedKmh ?: Double.NaN
        }
        if (t.streaming && !streamed.value) streamed.value = true
        val mv = status.value.running && motion.update(t.speedKmh, t.streaming, t.t)
        if (moving.value != mv) moving.value = mv
        if (chartPending && (t.t - chartAt >= CHART_EVERY_MS || !fresh)) { chartAt = t.t; chartPending = false; chartRev.update { it + 1 } }
        watchLink?.offer(forWatch(t))
        lamps(s, t)
        val d = cluster.alerts.update(t.lamps, t.warnings, System.currentTimeMillis(), t.units)
        // the watch app vibrates for them itself while it is active: then the notification is not mirrored there
        val phoneOnly = d.action != RideAlerts.Action.NONE && Versions.watchBuzzes(watch.value, SystemClock.elapsedRealtime())
        when (d.action) {
            RideAlerts.Action.ALERT -> Notifications.warning(app, d, loud = true, localOnly = phoneOnly)
            RideAlerts.Action.UPDATE -> Notifications.warning(app, d, loud = false, localOnly = phoneOnly)
            RideAlerts.Action.CANCEL -> Notifications.cancelWarning(app)
            RideAlerts.Action.NONE -> {}
        }
        saveOdometer(false)
        s.rotateIfNeeded()?.let { done -> rotated(s, done) }
    }

    /** The lamps of a published snapshot: the journal (every level change) and the log marks (rate-limited, [LampMarks]). */
    private fun lamps(s: Session, t: Telemetry) {
        val ch = cluster.changes(t.lamps, s.startedAtMs + t.t)
        if (ch.isNotEmpty()) {
            journal.value = cluster.journal.list()
            if (cutPct == null && ch.any { it.lamp?.text?.startsWith(LampText.BMS_CUT) == true }) cutPct = t.batteryPct ?: t.batteryPctFine
        }
        for (m in cluster.marks.update(t.lamps, t.t)) s.recorder.mk(m.text, t.t)
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
        if (link == Link.CONNECTED && status.value.link != Link.CONNECTED) bulbUntil.value = SystemClock.elapsedRealtime() + TellTales.BULB_CHECK_MS
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

    /** «Метка»: a timed marker in the running log (and on the chart); the marker, or null (nothing running, no text). */
    fun mark(label: String): Marker? {
        val s = session
        val l = label.trim().take(60)
        return when {
            s == null || !status.value.running -> { log.update { it.copy(markMessage = "Метка ставится в идущую запись — сначала «Подключить».", markErr = true) }; null }
            l.isEmpty() -> { log.update { it.copy(markMessage = "Впиши текст метки.", markErr = true) }; null }
            else -> {
                val t = s.now()
                s.recorder.mk(l, t)
                val m = Marker(t, l)
                markers.update { (it + m).takeLast(200) }
                chartRev.update { it + 1 }
                log.update { it.copy(markMessage = "Метка «$l» в ${Fmt.clock(s.startedAtMs + t)}", markErr = false) }
                m
            }
        }
    }

    /** One tap on «Метка» while riding: «Метка N» now (N: the owner's markers so far + 1), labelled later by [relabel]. */
    fun markNow(): Marker? {
        if (session == null || !status.value.running) return null
        return mark(MarkerWords.next(markers.value))
    }

    /** [m] gets [label] in the log (it keeps its time; the next save and upload carry it) and on the chart. */
    fun relabel(m: Marker, label: String): Boolean {
        val s = session ?: return false
        val l = label.trim().take(60)
        if (!status.value.running || l.isEmpty()) return false
        if (!s.relabel(m.t, m.label, l)) return false
        markers.update { list ->
            val i = list.indexOfFirst { it.t == m.t && it.label == m.label }
            if (i < 0) list else list.toMutableList().also { it[i] = Marker(m.t, l) }
        }
        chartRev.update { it + 1 }
        log.update { it.copy(markMessage = "Метка «$l» в ${Fmt.clock(s.startedAtMs + m.t)}", markErr = false) }
        return true
    }

    /** The wall-clock time of a marker of this recording: «22:12:21». */
    fun markClock(m: Marker): String = Fmt.clock((session?.startedAtMs ?: 0L) + m.t)

    fun setViewPin(p: ViewPin) { viewPin.value = p }

    /** Seconds above 1 km/h this session. */
    fun movingSeconds(): Long = movingMs / 1000

    fun toast(text: String) { toastFlow.tryEmit(text) }

    /**
     * The recording ends ([ScooterService]): what the idle dash shows next is kept ([LastRide], once per session: a lost
     * link calls this before «Отключить» does, so its reason wins); the view goes back to auto.
     */
    fun ended(cause: EndCause, reason: String? = null) {
        val s = session
        if (s != null && endedFor !== s && s.rx.get() > 0) {
            endedFor = s
            val t = telemetry.value
            val trip = TripStats.now(s, t, samples.copy())
            val r = LastRide(
                startMs = s.startedAtMs, endMs = s.startedAtMs + s.now(), km = trip.km, movingS = trip.movingS, whNet = trip.whNet,
                whPerKm = trip.whPerKm, end = RideEnds.of(cause, reason, journal.value, t, cutPct),
                pct = t.batteryPctFine ?: t.batteryPct, volts = t.voltage, mah = t.remainingMah, fullMah = t.fullMah,
            )
            lastRide.value = r
            prefs.lastRide = r
        }
        motion.reset()
        moving.value = false
        viewPin.value = ViewPin.AUTO
        streamed.value = false
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

    /** «Циферблат часов»: [f] picked now; the watch gets it through the Data Layer, now or when it next starts. */
    fun pickFace(f: DialFace) {
        val c = FaceChoice(f, System.currentTimeMillis(), FaceChoice.PHONE)
        prefs.watchFace = c
        face.update { it.copy(phone = c) }
        WatchFaces.put(app, c)
    }

    /** The watch's report of its face (its DataItem). */
    fun watchFace(c: FaceChoice) = face.update { it.copy(watch = c) }

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
