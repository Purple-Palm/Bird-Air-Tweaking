package io.github.purplepalm.birdair.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Uuids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The BLE link to the dash: connect by address, discover, subscribe to FFE1, pulse +SVGT=? and reconnect
 * after any drop the owner did not ask for (1, 2, 4, 8, 10 s). A link that gets no notification for
 * [SILENCE_MS] counts as dropped too; after [GIVE_UP_MS] without any data the loop stops ([Host.gaveUp]).
 * GATT operations go one at a time through a [GattQueue]; link state runs on one thread of its own, GATT
 * callbacks only complete operations and pass notifications on. Every write to the scooter goes through [write].
 */
@SuppressLint("MissingPermission")   // the service starts only with BLUETOOTH_CONNECT granted; SecurityException is caught
class ScooterLink(private val ctx: Context, private val host: Host) {
    interface Host {
        fun now(): Long
        fun state(link: Link, message: String, err: Boolean = false)
        fun sys(text: String, err: Boolean = false)
        fun rx(service: String, char: String, bytes: ByteArray)
        fun tx(service: String, char: String, bytes: ByteArray)
        fun servicesFound(gatt: BluetoothGatt)
        /** Subscribed: data will flow. */
        fun up()
        /** An unintended drop of a link that was up. */
        fun lost()
        /** Reconnecting cannot help (bad address, no permission): the loop has stopped. */
        fun gaveUp(reason: String)
    }

    sealed class WriteResult {
        object Ok : WriteResult()
        object NotConnected : WriteResult()
        class Refused(val reason: String) : WriteResult()
        class Failed(val reason: String) : WriteResult()
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "scooter-link") }
    // run() catches what the Bluetooth API throws; this is the last line, so a bug never kills the process mid-ride
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "link loop failed", e)
        try { host.sys("Сбой связи (${e.javaClass.simpleName}) — нажми «Отключить» и «Подключить»", true) } catch (x: Exception) { }
    })
    private val kick = Channel<Unit>(Channel.CONFLATED)
    /** A notification found the pulse overdue (the page checks on every notification too: timers may lag). */
    private val pulseNow = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pulseAt = 0L
    private var job: Job? = null
    @Volatile private var fatal: String? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var queue: GattQueue? = null
    @Volatile private var events: Events? = null
    @Volatile private var ffe1: BluetoothGattCharacteristic? = null
    @Volatile private var subscribed = false
    /** elapsedRealtime of the last notification. */
    @Volatile private var rxAt = 0L

    /** The owner wants the link up. */
    @Volatile var wanted = false
        private set
    @Volatile var address: String? = null
        private set

    fun start(address: String) {
        stop()
        this.address = address
        wanted = true
        job = scope.launch { run(address) }
    }

    /** «Отключить»: no more reconnects, the link closes now. */
    fun stop() {
        wanted = false
        job?.cancel()
        job = null
        closeGatt()
    }

    /** Bluetooth came back on: skip the rest of the reconnect wait. */
    fun kick() { kick.trySend(Unit) }

    /**
     * Bluetooth is going off. Android may never report the drop (with «BLE scanning» on, the stack stays half up
     * and unregisters the app silently), so the connection ends here and the loop waits for [kick].
     */
    fun adapterOff() {
        val ev = events ?: return
        if (ev.status == null) ev.status = ADAPTER_OFF
        ev.up.complete(false)
        ev.lost.complete(ADAPTER_OFF)
        queue?.close()
    }

    fun release() {
        stop()
        scope.cancel()
        executor.shutdown()
    }

    // -- the connection loop

    private suspend fun run(address: String) {
        var attempt = 0
        var first = true
        val startAt = SystemClock.elapsedRealtime()
        fatal = null
        while (wanted && currentCoroutineContext().isActive) {
            val wasUp = try { connectOnce(address, first) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                host.sys("Сбой Bluetooth (${e.javaClass.simpleName}) — переподключаюсь", true); false
            }
            first = false
            fatal?.let { wanted = false; host.gaveUp(it); return }
            if (!wanted) break
            if (wasUp) { attempt = 0; host.lost() }
            // scooter switched off and forgotten: no wake lock and radio all night
            if (SystemClock.elapsedRealtime() - maxOf(startAt, rxAt) >= GIVE_UP_MS) { wanted = false; host.gaveUp(GONE); return }
            val wait = Backoff.delayMs(attempt++)
            host.state(Link.RECONNECTING, "Нет связи — переподключаюсь через ${wait / 1000} с", true)
            while (kick.tryReceive().isSuccess) { /* drop old kicks */ }
            withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    private class Events {
        /** First state change: true connected, false failed. */
        val up = CompletableDeferred<Boolean>()
        /** Disconnect status. */
        val lost = CompletableDeferred<Int>()
        @Volatile var status: Int? = null
    }

    /** One connection, up to its end. True if it got as far as subscribed. */
    private suspend fun connectOnce(address: String, first: Boolean): Boolean {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) { host.sys("Bluetooth недоступен на этом телефоне", true); return false }
        if (!adapter.isEnabled) { host.sys("Bluetooth выключен — жду, когда включится", true); return false }
        val dev: BluetoothDevice = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            fatal = "Неверный адрес самоката: выбери его заново"; return false
        }
        val ev = Events()
        val q = GattQueue()
        queue = q
        events = ev
        host.state(if (first) Link.CONNECTING else Link.RECONNECTING, "Подключаюсь…")
        val g = try { dev.connectGatt(ctx, false, Callback(ev, q), BluetoothDevice.TRANSPORT_LE) } catch (e: SecurityException) {
            fatal = "Нет разрешения «Устройства поблизости»"; return false
        }
        if (g == null) { host.sys("Android не начал подключение", true); return false }
        gatt = g
        var wasUp = false
        try {
            val up = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ev.up.await() }
            if (up != true) {
                host.sys(if (up == null) "Самокат не ответил за ${CONNECT_TIMEOUT_MS / 1000} с" else "Не подключилось (статус ${ev.status})", true)
                return false
            }
            host.sys("Подключено, ищу сервисы")
            delay(300)   // let the connection settle (parameter update) before discovery
            val d = q.run(GattQueue.Op.DISCOVER, DISCOVER_TIMEOUT_MS) { g.discoverServices() }
            if (!d.ok) { host.sys("Сервисы не нашлись (${d.describe()}) — переподключаюсь", true); return false }
            host.servicesFound(g)
            val ch = g.getService(SERVICE)?.getCharacteristic(CHAR)
            if (ch == null) { host.sys("Нет характеристики FFE1 в сервисе FFE0 — это не руль Bird Air? Переподключаюсь", true); return false }
            if (!enableNotify(g, q, ch)) return false
            ffe1 = ch
            subscribed = true
            wasUp = true
            host.state(Link.CONNECTED, "Подключено, уведомления FFE1 включены")
            host.up()
            coroutineScope {
                val pulse = launch { pulseLoop() }
                val dog = launch { watchdog(ev) }
                val status = ev.lost.await()
                pulse.cancel()
                dog.cancel()
                subscribed = false
                if (wanted) host.sys(lostText(status), true)
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {   // the Bluetooth API throws when its process died (Bluetooth off, a stack crash)
            host.sys("Сбой Bluetooth (${e.javaClass.simpleName}) — переподключаюсь", true)
            return wasUp
        } finally {
            subscribed = false
            ffe1 = null
            q.close()
            if (gatt === g) gatt = null
            if (queue === q) queue = null
            if (events === ev) events = null
            try { g.disconnect() } catch (e: Exception) { }
            try { g.close() } catch (e: Exception) { }
        }
    }

    private fun lostText(status: Int): String = when (status) {
        ADAPTER_OFF -> "Bluetooth выключается — связь закрыта"
        SILENCE -> "Нет данных ${SILENCE_MS / 1000} с — переподключаюсь"
        GattQueue.THREW -> "Сбой Bluetooth при записи — переподключаюсь"
        else -> "Связь потеряна (статус $status)"
    }

    /** Subscribed, yet nothing comes (no drop callback, a wedged write queue): count it as a drop. */
    private suspend fun watchdog(ev: Events) {
        val since = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            if (SystemClock.elapsedRealtime() - maxOf(since, rxAt) >= SILENCE_MS) { ev.lost.complete(SILENCE); return }
        }
    }

    private fun closeGatt() {
        subscribed = false
        ffe1 = null
        queue?.close()
        val g = gatt
        gatt = null
        if (g != null) {
            try { g.disconnect() } catch (e: Exception) { }
            try { g.close() } catch (e: Exception) { }
        }
    }

    /** Notifications on FFE1: local switch, then the CCCD write; subscribed only once that write succeeded. */
    private suspend fun enableNotify(g: BluetoothGatt, q: GattQueue, ch: BluetoothGattCharacteristic): Boolean {
        val on = try { g.setCharacteristicNotification(ch, true) } catch (e: Exception) { false }
        if (!on) { host.sys("Android не включил уведомления FFE1 — переподключаюсь", true); return false }
        val cccd = ch.getDescriptor(CCCD)
        if (cccd == null) { host.sys("У FFE1 нет дескриптора 0x2902 — переподключаюсь", true); return false }
        val r = q.run(GattQueue.Op.WRITE_DESC, CCCD_TIMEOUT_MS) { writeCccd(g, cccd) }
        if (!r.ok) { host.sys("Не включились уведомления FFE1 (${r.describe()}) — переподключаюсь", true); return false }
        return true
    }

    /** Only the CCCD of FFE1, only «notifications on» (01 00). */
    private fun writeCccd(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        if (Uuids.full(d.uuid.toString()) != Uuids.CCCD || Uuids.full(d.characteristic.uuid.toString()) != Uuids.CHAR) return false
        val v = byteArrayOf(0x01, 0x00)
        return if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { d.value = v; g.writeDescriptor(d) }
    }

    private suspend fun pulseLoop() {
        val p = PulseState()
        pulseAt = SystemClock.elapsedRealtime()
        delay(FIRST_PULSE_MS)
        while (currentCoroutineContext().isActive && subscribed) {
            pulseAt = SystemClock.elapsedRealtime()
            val step = p.after(write(Commands.SVGT) is WriteResult.Ok)
            step.sys?.let { host.sys(it, step.err) }
            while (pulseNow.tryReceive().isSuccess) { /* stale */ }
            withTimeoutOrNull(step.delayMs) { pulseNow.receive() }
        }
    }

    // -- the one write

    /**
     * Every write to the scooter, the pulse and the owner's taps alike. Refuses anything [WriteGuard] refuses
     * (not one of the six commands, KEY?, not FFE1, OTA/DFU), then writes without response through the queue.
     */
    suspend fun write(bytes: ByteArray): WriteResult {
        val payload = bytes.copyOf()
        WriteGuard.checkPayload(payload)?.let { host.sys("Запись заблокирована: $it", true); return WriteResult.Refused(it) }
        val ch = ffe1
        val g = gatt
        val q = queue
        val ev = events
        if (ch == null || g == null || q == null || !subscribed) return WriteResult.NotConnected
        val svc = ch.service?.uuid?.toString()
        val chr = ch.uuid.toString()
        WriteGuard.check(svc, chr, payload)?.let { host.sys("Запись заблокирована: $it", true); return WriteResult.Refused(it) }
        val r = q.run(GattQueue.Op.WRITE_CHAR, WRITE_TIMEOUT_MS) { radioWrite(g, ch, payload) }
        if (r.status == GattQueue.THREW) ev?.lost?.complete(GattQueue.THREW)   // this gatt is dead: reconnect
        if (!r.ok) return WriteResult.Failed(r.describe())
        host.tx(svc!!, chr, payload)
        return WriteResult.Ok
    }

    /** The radio step of [write]; checks the guard once more right before the call. */
    private fun radioWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, payload: ByteArray): Boolean {
        if (WriteGuard.check(ch.service?.uuid?.toString(), ch.uuid.toString(), payload) != null) return false
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(ch, payload, type) == BluetoothStatusCodes.SUCCESS
        else @Suppress("DEPRECATION") run { ch.writeType = type; ch.value = payload; g.writeCharacteristic(ch) }
    }

    // -- callbacks (binder threads): complete operations, pass notifications on, never block

    private inner class Callback(private val ev: Events, private val q: GattQueue) : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                ev.up.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                // 133 (GATT_ERROR) and friends land here too: the loop closes this gatt and retries
                if (ev.status == null) ev.status = status
                ev.up.complete(false)
                ev.lost.complete(status)
                q.close()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { q.complete(GattQueue.Op.DISCOVER, status) }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { q.complete(GattQueue.Op.WRITE_DESC, status) }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { q.complete(GattQueue.Op.WRITE_CHAR, status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { notified(c, value) }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return   // the value overload above gets it
            @Suppress("DEPRECATION") val v = c.value ?: return
            notified(c, v.copyOf())
        }

        private fun notified(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (ev.lost.isCompleted) return
            val now = SystemClock.elapsedRealtime()
            rxAt = now
            host.rx(c.service?.uuid?.toString() ?: Uuids.SERVICE, c.uuid.toString(), value)
            if (subscribed && now - pulseAt >= Commands.PULSE_MS) pulseNow.trySend(Unit)
        }
    }

    companion object {
        val SERVICE: UUID = UUID.fromString(Uuids.SERVICE)
        val CHAR: UUID = UUID.fromString(Uuids.CHAR)
        val CCCD: UUID = UUID.fromString(Uuids.CCCD)
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val DISCOVER_TIMEOUT_MS = 15_000L
        const val CCCD_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 2_000L
        /** The Bird app waits 500 ms after subscribing before its first +SVGT=?. */
        const val FIRST_PULSE_MS = 500L
        /** Subscribed with no notification this long (4 pulses; the ride log never went 1.5 s without one): reconnect. */
        const val SILENCE_MS = 10_000L
        /** No data at all this long, reconnects included: stop trying. */
        const val GIVE_UP_MS = 10 * 60_000L
        /** Synthetic drop statuses (GATT statuses are ≥ 0, [GattQueue]'s −1…−4). */
        const val ADAPTER_OFF = -10
        const val SILENCE = -11
        const val GONE = "Самокат 10 минут не на связи — перестал подключаться. Нажми «Подключить», когда будешь рядом с ним"
        private const val TAG = "BirdAir"
    }
}
