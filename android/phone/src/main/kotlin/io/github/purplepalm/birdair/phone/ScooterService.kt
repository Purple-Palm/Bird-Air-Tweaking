package io.github.purplepalm.birdair.phone

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.purplepalm.birdair.core.GattChar
import io.github.purplepalm.birdair.core.GattProps
import io.github.purplepalm.birdair.core.GattService
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service (connectedDevice) that owns the scooter link, so it keeps running with the screen off:
 * a partial wake lock while the link is wanted (connected or reconnecting), START_STICKY, a snapshot every
 * 250 ms for the screen and the watch, the ongoing notification about once a second, the log saved every
 * 5 min and on disconnect, GitHub auto-send every 5 min while connected and after a drop (at most once a minute).
 */
class ScooterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var link: ScooterLink
    private lateinit var watch: WatchLink
    private var wake: PowerManager.WakeLock? = null
    private var loops: Job? = null
    private var stopJob: Job? = null
    private var destroyed = false
    private var foreground = false
    private var shown: String? = null
    private var savedAt = 0L

    private val bt = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> if (link.wanted) { ScooterRepo.sys("Bluetooth включён"); link.kick() }
                // the drop callback may never come: close the link now, STATE_ON reconnects
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> if (link.wanted) {
                    if (st == BluetoothAdapter.STATE_OFF) ScooterRepo.sys("Bluetooth выключен", true)
                    link.adapterOff()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ScooterRepo.init(this)
        Notifications.channel(this)
        link = ScooterLink(applicationContext, host)
        watch = WatchLink(applicationContext, scope)
        ScooterRepo.link = link
        ScooterRepo.watchLink = watch
        ContextCompat.registerReceiver(this, bt, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDRESS), intent.getStringExtra(EXTRA_NAME))
            ACTION_STOP -> stopAll()
            else -> {
                // a sticky restart after the process was killed: reconnect if the owner still wants the link
                val p = ScooterRepo.prefs
                val a = p.address
                if (p.wanted && a != null) connect(a, p.deviceName) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun connect(address: String?, name: String?) {
        // first: a service started with startForegroundService() must call startForeground() even if it stops right away
        if (!goForeground()) {
            ScooterRepo.setLink(Link.IDLE, "Android не дал запустить фоновую работу — открой приложение и нажми «Подключить»", true)
            stopSelf()
            return
        }
        if (address == null || !Perms.canConnect(this)) {
            if (address != null) ScooterRepo.setLink(Link.IDLE, "Нет разрешения «Устройства поблизости»", true)
            if (!link.wanted) finishStop()
            return
        }
        ScooterRepo.prefs.wanted = true
        Notifications.cancelAlert(this)
        val st = ScooterRepo.status.value
        if (link.wanted && link.address == address && st.running) return
        if (!st.running || ScooterRepo.session == null || link.address != address) ScooterRepo.newSession(name, address)
        ScooterRepo.status.update { it.copy(running = true, address = address, deviceName = name ?: it.deviceName) }
        acquireWake()
        ScooterRepo.sys("Подключаюсь к ${name ?: address}")
        link.start(address)
        startLoops()
    }

    /** «Отключить»: link down, no reconnects; the log is saved (and sent, with auto-send) before the service ends. */
    private fun stopAll() {
        ScooterRepo.prefs.wanted = false
        val was = ScooterRepo.status.value.running
        link.stop()
        loops?.cancel()
        loops = null
        if (was) ScooterRepo.sys("Отключено")
        ScooterRepo.setLink(Link.IDLE, "Отключено")
        val last = ScooterRepo.telemetry.value.copy(link = Link.IDLE, streaming = false)
        ScooterRepo.telemetry.value = last
        ScooterRepo.status.update { it.copy(running = false) }
        watch.offer(last)
        val s = ScooterRepo.session
        val app = applicationContext
        // in the process scope: a destroyed service does not cut the save or the upload short
        stopJob = ScooterRepo.scope.launch {
            withTimeoutOrNull(FINISH_TIMEOUT_MS) {
                if (s != null && was) {
                    LogStore.save(app, s)
                    if (LogStore.autoDue(s, force = true)) LogStore.upload(app, s, auto = true)
                }
            }
            withContext(Dispatchers.Main) { finishStop() }
        }
    }

    private fun finishStop() {
        if (destroyed || link.wanted) return   // gone already, or «Подключить» again meanwhile
        watch.stop()
        releaseWake()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        try { unregisterReceiver(bt) } catch (e: IllegalArgumentException) { }
        loops?.cancel()
        watch.stop()
        link.release()
        if (ScooterRepo.link === link) ScooterRepo.link = null
        if (ScooterRepo.watchLink === watch) ScooterRepo.watchLink = null
        if (ScooterRepo.status.value.running) {
            // ended by the system, not by «Отключить»
            ScooterRepo.status.update { it.copy(running = false) }
            ScooterRepo.setLink(Link.IDLE, "Фоновая работа остановлена системой", true)
            val s = ScooterRepo.session
            val app = applicationContext
            if (s != null) ScooterRepo.scope.launch { LogStore.save(app, s) }
        }
        releaseWake()
        scope.cancel()
        super.onDestroy()
    }

    // -- foreground, wake lock

    private fun goForeground(): Boolean {
        if (foreground) return true
        return try {
            val n = Notifications.build(this, ScooterRepo.telemetry.value.copy(link = Link.CONNECTING))
            ServiceCompat.startForeground(this, Notifications.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foreground = true
            true
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException (background start), SecurityException
            false
        }
    }

    private fun acquireWake() {
        val w = wake ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "birdair:scooter-link").also { it.setReferenceCounted(false); wake = it }
        w.acquire(WAKE_MS)
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
    }

    // -- loops

    private fun startLoops() {
        if (loops?.isActive == true) return
        watch.start()
        loops = scope.launch {
            launch { ticker() }
            launch { periodic() }
        }
    }

    private suspend fun ticker() {
        var n = 0L
        while (scope.isActive) {
            val t = ScooterRepo.snapshotNow()
            ScooterRepo.telemetry.value = t
            watch.offer(t)
            if (n % 4 == 0L) {
                notify(t)
                ScooterRepo.session?.let { s -> ScooterRepo.log.update { it.copy(entries = s.recorder.size) } }
            }
            n++
            delay(TICK_MS)
        }
    }

    private fun notify(t: Telemetry) {
        val key = StatusText.title(t) + "|" + StatusText.notification(t)
        if (key == shown) return
        shown = key
        Notifications.show(this, Notifications.build(this, t))
    }

    private suspend fun periodic() {
        savedAt = System.currentTimeMillis()
        val app = applicationContext
        while (scope.isActive) {
            delay(PERIODIC_MS)
            if (link.wanted) acquireWake()   // renews the timeout
            val s = ScooterRepo.session ?: continue
            if (System.currentTimeMillis() - savedAt >= LogStore.AUTO_EVERY_MS) {
                savedAt = System.currentTimeMillis()
                ScooterRepo.scope.launch { LogStore.save(app, s) }
            }
            if (ScooterRepo.status.value.link == Link.CONNECTED && LogStore.autoDue(s, force = false)) {
                ScooterRepo.scope.launch { LogStore.upload(app, s, auto = true) }
            }
        }
    }

    // -- the link's host: log, tracker, state

    private val host = object : ScooterLink.Host {
        override fun now(): Long = ScooterRepo.session?.now() ?: 0L

        override fun state(link: Link, message: String, err: Boolean) {
            ScooterRepo.setLink(link, message, err)
            ScooterRepo.sys(message, err)
        }

        override fun sys(text: String, err: Boolean) = ScooterRepo.sys(text, err)

        override fun rx(service: String, char: String, bytes: ByteArray) {
            val s = ScooterRepo.session ?: return
            val t = s.now()
            s.recorder.rx(service, char, bytes, t)
            s.rx.incrementAndGet()
            ScooterRepo.tracker.onNotification(service, char, bytes, t)
        }

        override fun tx(service: String, char: String, bytes: ByteArray) {
            val s = ScooterRepo.session ?: return
            s.recorder.tx(service, char, bytes, s.now())
        }

        override fun servicesFound(gatt: BluetoothGatt) {
            val s = ScooterRepo.session ?: return
            val list = gatt.services.map { svc ->
                GattService(svc.uuid.toString(), svc.characteristics.map { c -> GattChar(c.uuid.toString(), props(c.properties)) })
            }
            s.gatt = list
            val nChars = list.sumOf { it.chars.size }
            s.recorder.servicesFound(list.size, nChars, s.now())
            ScooterRepo.tracker.restartStreams()
            ScooterRepo.note("Найдено сервисов: ${list.size}, характеристик: $nChars")
        }

        override fun up() {}

        override fun lost() {
            val s = ScooterRepo.session ?: return
            val app = applicationContext
            ScooterRepo.scope.launch {
                LogStore.save(app, s)
                if (LogStore.autoDue(s, force = true, minGapMs = LogStore.DROP_EVERY_MS)) LogStore.upload(app, s, auto = true)
            }
        }

        override fun gaveUp(reason: String) {
            scope.launch {
                ScooterRepo.sys(reason, true)
                stopAll()
                ScooterRepo.setLink(Link.IDLE, reason, true)
                Notifications.alert(this@ScooterService, reason)   // the screen is likely off
            }
        }
    }

    private fun props(p: Int) = GattProps(
        read = p and BluetoothGattCharacteristic.PROPERTY_READ != 0,
        write = p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0,
        wnr = p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0,
        notify = p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
        indicate = p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
    )

    companion object {
        const val ACTION_CONNECT = "io.github.purplepalm.birdair.CONNECT"
        const val ACTION_STOP = "io.github.purplepalm.birdair.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        const val TICK_MS = 250L
        const val PERIODIC_MS = 30_000L
        /** Renewed every [PERIODIC_MS] while the link is wanted; the timeout only guards against a leak. */
        const val WAKE_MS = 15 * 60 * 1000L
        const val FINISH_TIMEOUT_MS = 60_000L

        fun connect(ctx: Context, address: String, name: String?) {
            val i = Intent(ctx, ScooterService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_ADDRESS, address).putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScooterService::class.java).setAction(ACTION_STOP))
        }
    }
}
