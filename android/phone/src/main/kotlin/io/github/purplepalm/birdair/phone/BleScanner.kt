package io.github.purplepalm.birdair.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.github.purplepalm.birdair.core.Uuids
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Finding the scooter once: a ~10 s scan with a filter on service 0xFFE0 (the dash advertises it), name and
 * RSSI listed, the owner taps one. [all] drops the filter, for a dash that would not advertise FFE0.
 * Main thread only.
 */
@SuppressLint("MissingPermission")   // called only after Perms.canScan(); SecurityException is caught
object BleScanner {
    const val SCAN_MS = 10_000L
    private val main = Handler(Looper.getMainLooper())
    private var cb: ScanCallback? = null
    private var stopAt: Runnable? = null

    fun start(ctx: Context, all: Boolean) {
        stop(ctx)
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            ScooterRepo.scan.update { it.copy(scanning = false, shown = true, message = "Bluetooth выключен — включи его и нажми «Искать» ещё раз.") }
            return
        }
        val filters = if (all) emptyList() else listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(Uuids.SERVICE))).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val c = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) = found(r)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { found(it) }
            override fun onScanFailed(errorCode: Int) {
                ScooterRepo.scan.update { it.copy(scanning = false, message = "Поиск не запустился (код $errorCode). Выключи и включи Bluetooth.") }
            }
        }
        try {
            scanner.startScan(filters, settings, c)
        } catch (e: SecurityException) {
            ScooterRepo.scan.update { it.copy(scanning = false, shown = true, message = "Нет разрешения на поиск устройств.") }
            return
        }
        cb = c
        ScooterRepo.scan.value = ScanState(scanning = true, all = all, shown = true, message = "Ищу самокат… Включи его; приложение Bird на этом телефоне должно быть закрыто.")
        val r = Runnable { stop(ctx) }
        stopAt = r
        main.postDelayed(r, SCAN_MS)
    }

    fun stop(ctx: Context) {
        stopAt?.let { main.removeCallbacks(it) }
        stopAt = null
        val c = cb ?: return
        cb = null
        try {
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner?.stopScan(c)
        } catch (e: Exception) { }
        ScooterRepo.scan.update {
            it.copy(scanning = false, message = if (it.found.isEmpty()) "Ничего не нашлось. Самокат включён и рядом? Приложение Bird закрыто? Можно искать все устройства." else "Выбери свой самокат.")
        }
    }

    private fun found(r: ScanResult) {
        val name = try { r.scanRecord?.deviceName ?: r.device.name } catch (e: SecurityException) { null }
        val f = Found(r.device.address, name, r.rssi)
        // first-seen order: a list that reshuffles on every RSSI change is easy to mis-tap
        ScooterRepo.scan.update { s ->
            val i = s.found.indexOfFirst { it.address == f.address }
            val list = if (i < 0) s.found + f else s.found.toMutableList().also { it[i] = f.copy(name = f.name ?: it[i].name) }
            s.copy(found = list)
        }
    }
}
