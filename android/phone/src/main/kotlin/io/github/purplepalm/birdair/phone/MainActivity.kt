package io.github.purplepalm.birdair.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.purplepalm.birdair.phone.ui.BirdTheme
import io.github.purplepalm.birdair.phone.ui.MainScreen
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), UiActions {
    private var sys by mutableStateOf(SystemState())
    private var asked = false
    /** The tap that needed permissions: repeated once they came back granted (never re-asks by itself). */
    private var afterPerms: Pair<List<String>, () -> Unit>? = null

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        asked = true
        refresh()
        val next = afterPerms ?: return@registerForActivityResult
        afterPerms = null
        if (Perms.missing(this, next.first).isEmpty()) next.second()
    }

    /** True when [perms] are granted; otherwise asks for everything missing and runs [then] after. */
    private fun have(perms: List<String>, then: () -> Unit): Boolean {
        if (Perms.missing(this, perms).isEmpty()) return true
        afterPerms = perms to then
        requestPermissions()
        return false
    }
    private val btLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        ScooterRepo.init(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(0), navigationBarStyle = SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean("asked") ?: false
        setContent {
            val s by ScooterRepo.settings.collectAsStateWithLifecycle()
            val dark = s.theme == "dark" || isSystemInDarkTheme()
            BirdTheme(dark) { MainScreen(this, sys) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("asked", asked)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(btState)
        BleScanner.stop(this)
    }

    private fun refresh() {
        val missing = Perms.missing(this, Perms.all())
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        sys = SystemState(
            missingPerms = missing,
            permsBlocked = asked && missing.any { !shouldShowRequestPermissionRationale(it) },
            bluetoothOn = adapter?.isEnabled == true,
            ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName),
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
        )
    }

    // -- UiActions

    override fun requestPermissions() {
        val missing = Perms.missing(this, Perms.all())
        if (missing.isEmpty()) { refresh(); return }
        permLauncher.launch(missing.toTypedArray())
    }

    override fun openAppSettings() {
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("MissingPermission")   // checked just above the launch
    override fun enableBluetooth() {
        if (!have(Perms.connect()) { enableBluetooth() }) return
        try { btLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { openBluetoothSettings() }
    }

    private fun openBluetoothSettings() = startSafely(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    override fun connect() {
        val p = ScooterRepo.prefs
        val a = p.address
        if (a == null) { scan(false); return }
        if (!have(Perms.connect()) { connect() }) return
        if (!sys.bluetoothOn) { enableBluetooth(); return }
        ScooterService.connect(this, a, p.deviceName)
    }

    override fun disconnect() = ScooterService.stop(this)

    override fun scan(all: Boolean) {
        if (!have(Perms.scan()) { scan(all) }) return
        if (!sys.bluetoothOn) { enableBluetooth(); return }
        BleScanner.start(this, all)
    }

    override fun closeScan() {
        BleScanner.stop(this)
        ScooterRepo.scan.update { it.copy(shown = false) }
    }

    override fun choose(f: Found) {
        closeScan()
        ScooterRepo.choose(f)
        ScooterService.connect(this, f.address, f.name)
    }

    override fun saveLog() {
        val s = ScooterRepo.session
        if (s == null || s.recorder.size == 0) { ScooterRepo.log.update { it.copy(message = "Лог пуст — подключись к самокату.", err = true) }; return }
        lifecycleScope.launch { LogStore.save(applicationContext, s, force = true) }
    }

    override fun shareLog() {
        lifecycleScope.launch {
            val s = ScooterRepo.session
            val f = (if (s != null && s.recorder.size > 0) LogStore.save(applicationContext, s) else null) ?: LogStore.latest(applicationContext)
            if (f == null) { ScooterRepo.log.update { it.copy(message = "Сохранённых логов пока нет.", err = true) }; return@launch }
            startSafely(LogStore.shareIntent(this@MainActivity, f))
        }
    }

    override fun sendLog() {
        val s = ScooterRepo.session
        if (s == null || s.recorder.size == 0) { ScooterRepo.log.update { it.copy(ghMessage = "Лог пуст — нечего отправлять.", ghErr = true) }; return }
        ScooterRepo.scope.launch { LogStore.upload(applicationContext, s, auto = false) }
    }

    override fun openOnWatch() = Watch.open(applicationContext, lifecycleScope)

    @SuppressLint("BatteryLife")   // a personal sideloaded app whose whole job is a background BLE link
    override fun requestIgnoreBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startSafely(i: Intent) {
        try { startActivity(i) } catch (e: ActivityNotFoundException) { }
    }
}
