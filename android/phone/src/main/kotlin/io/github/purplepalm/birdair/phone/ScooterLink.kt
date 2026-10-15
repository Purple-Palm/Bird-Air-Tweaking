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
import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Uuids
import kotlinx.coroutines.CompletableDeferred
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
 * after any drop the owner did not ask for (1, 2, 4, 8, 10 s). GATT operations go one at a time through a
 * [GattQueue]; link state runs on one thread of its own, GATT callbacks only complete operations and pass
 * notifications on. Every write to the scooter goes through [write].
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
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val kick = Channel<Unit>(Channel.CONFLATED)
    /** A notification found the pulse overdue (the page checks on every notification too: timers may lag). */
    private val pulseNow = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var pulseAt = 0L
    private var job: Job? = null
    @Volatile private var fatal: String? = null

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var queue: GattQueue? = null
    @Volatile private var ffe1: BluetoothGattCharacteristic? = null
    @Volatile private var subscribed = false

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

    fun release() {
        stop()
        scope.cancel()
        executor.shutdown()
    }

    // -- the connection loop

    private suspend fun run(address: String) {
        var attempt = 0
        var first = true
        fatal = null
        while (wanted && currentCoroutineContext().isActive) {
            val wasUp = connectOnce(address, first)
            first = false
            fatal?.let { wanted = false; host.gaveUp(it); return }
            if (!wanted) break
            if (wasUp) { attempt = 0; host.lost() }
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
        host.state(if (first) Link.CONNECTING else Link.RECONNECTING, "Подключаюсь…")
        val g = try { dev.connectGatt(ctx, false, Callback(ev, q), BluetoothDevice.TRANSPORT_LE) } catch (e: SecurityException) {
            fatal = "Нет разрешения «Устройства поблизости»"; return false
        }
        if (g == null) { host.sys("Android не начал подключение", true); return false }
        gatt = g
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
            host.state(Link.CONNECTED, "Подключено, уведомления FFE1 включены")
            host.up()
            coroutineScope {
                val pulse = launch { pulseLoop() }
                val status = ev.lost.await()
                pulse.cancel()
                subscribed = false
                if (wanted) host.sys("Связь потеряна (статус $status)", true)
            }
            return true
        } finally {
            subscribed = false
            ffe1 = null
            q.close()
            if (gatt === g) gatt = null
            if (queue === q) queue = null
            try { g.disconnect() } catch (e: Exception) { }
            try { g.close() } catch (e: Exception) { }
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
        val on = try { g.setCharacteristicNotification(ch, true) } catch (e: SecurityException) { false }
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
        if (ch == null || g == null || q == null || !subscribed) return WriteResult.NotConnected
        val svc = ch.service?.uuid?.toString()
        val chr = ch.uuid.toString()
        WriteGuard.check(svc, chr, payload)?.let { host.sys("Запись заблокирована: $it", true); return WriteResult.Refused(it) }
        val r = q.run(GattQueue.Op.WRITE_CHAR, WRITE_TIMEOUT_MS) { radioWrite(g, ch, payload) }
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
            host.rx(c.service?.uuid?.toString() ?: Uuids.SERVICE, c.uuid.toString(), value)
            if (subscribed && SystemClock.elapsedRealtime() - pulseAt >= Commands.PULSE_MS) pulseNow.trySend(Unit)
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
    }
}
