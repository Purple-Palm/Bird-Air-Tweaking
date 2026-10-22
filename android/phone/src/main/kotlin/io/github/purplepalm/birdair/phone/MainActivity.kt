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
import android.graphics.Color
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.purplepalm.birdair.phone.ui.BirdTheme
import io.github.purplepalm.birdair.phone.ui.MainScreen
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), UiActions {
    private var sys by mutableStateOf(SystemState())
    private var asked = false
    /** The tap that needed permissions: repeated once they came back granted (never re-asks by itself). */
    private var afterPerms: Pair<List<String>, () -> Unit>? = null
    /** A downloaded update waiting for «Установка неизвестных приложений». */
    private var pendingInstall: java.io.File? = null

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
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean("asked") ?: false
        setContent {
            val s by ScooterRepo.settings.collectAsStateWithLifecycle()
            val dark = s.theme == "dark" || isSystemInDarkTheme()
            // the bars' icons follow the app's theme (the owner picks it), not the system's
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            BirdTheme(dark) { MainScreen(this, sys) }
        }
        // a download the owner tapped for: the installer once it is ready, unless a recording runs
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScooterRepo.update.collect { u ->
                    if (u.openWhenReady && UpdateFlow.phase(u, Updater.code) == UpdPhase.READY) {
                        ScooterRepo.update.update { it.copy(openWhenReady = false) }
                        if (!ScooterRepo.status.value.running) install()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // a notification: the dash, nothing over it. singleTop: an instance Android destroyed in the background gets the
        // tap here too, after onCreate restored its page (getIntent() is not read: it would fold on every recreation)
        if (intent.getBooleanExtra(EXTRA_DASH, false)) sys = sys.copy(dashTick = sys.dashTick + 1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("asked", asked)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ScooterRepo.scope.launch(Dispatchers.IO) { ScooterRepo.refreshUnsent(applicationContext) }
        // back from «Установка неизвестных приложений»: go on with the install (install() checks again: not while moving)
        if (pendingInstall != null && Updater.canInstall(this)) {
            pendingInstall = null
            install()
        }
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
        sys = sys.copy(
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
        lifecycleScope.launch { LogStore.save(applicationContext, s, s.part, force = true); LogStore.saveAll(applicationContext, s) }
    }

    override fun shareLog() {
        lifecycleScope.launch {
            val s = ScooterRepo.session
            val f = (if (s != null && s.recorder.size > 0) LogStore.save(applicationContext, s, s.part) else null) ?: LogStore.latest(applicationContext)
            if (f == null) { ScooterRepo.log.update { it.copy(message = "Сохранённых логов пока нет.", err = true) }; return@launch }
            startSafely(LogStore.shareIntent(this@MainActivity, f))
        }
    }

    /** «Отправить в GitHub»: every part of this recording that GitHub does not have yet (the one being written last). */
    override fun sendLog() {
        val s = ScooterRepo.session
        if (s == null || s.recorder.size == 0) { ScooterRepo.log.update { it.copy(ghMessage = "Лог пуст — нечего отправлять.", ghErr = true) }; return }
        val app = applicationContext
        ScooterRepo.scope.launch {
            val dest = ScooterRepo.gh.value.dest
            for (p in s.parts) if (p !== s.part && p.dirtyGh(dest) && !LogStore.upload(app, s, p, auto = false)) return@launch
            LogStore.upload(app, s, s.part, auto = false)
        }
    }

    override fun sendAll() { ScooterRepo.scope.launch { LogStore.uploadAll(applicationContext) } }

    override fun shareFile(f: LocalLog) {
        val file = java.io.File(LogStore.dir(this), f.name)
        if (!file.isFile) { ScooterRepo.scope.launch(Dispatchers.IO) { ScooterRepo.refreshUnsent(applicationContext) }; return }
        startSafely(LogStore.shareIntent(this, file))
    }

    override fun refreshLogs() { ScooterRepo.scope.launch(Dispatchers.IO) { ScooterRepo.refreshUnsent(applicationContext) } }

    /** The one update button: check → download → install, by the state it is in ([UpdateFlow.phase]). */
    override fun updateStep() {
        val app = applicationContext
        when (Updater.phase()) {
            UpdPhase.NONE -> ScooterRepo.scope.launch { Updater.check(app, manual = true) }
            UpdPhase.AVAILABLE -> ScooterRepo.scope.launch { Updater.download(app, byTap = true) }
            UpdPhase.READY -> install()
            UpdPhase.CHECKING, UpdPhase.DOWNLOADING -> {}
        }
    }

    /**
     * «Установить»: never while moving; the running recording (every part) and the odometer are saved first. Android
     * wants «Установка неизвестных приложений» first: the screen says so where the tap came from ([SystemState.installAsk]).
     */
    private fun install() {
        if (ScooterRepo.moving.value) { ScooterRepo.toast("На ходу не ставлю: установка оборвёт связь. Остановись"); return }
        lifecycleScope.launch {
            ScooterRepo.beforeInstall(applicationContext)
            val f = Updater.readyFile(applicationContext) ?: run { ScooterRepo.update.update { it.copy(readyCode = null) }; return@launch }
            pendingInstall = f
            if (Updater.install(this@MainActivity, f)) pendingInstall = null
            else if (ScooterRepo.update.value.needsPermission) sys = sys.copy(installAsk = sys.installAsk + 1)
        }
    }

    override fun allowInstall() = startSafely(Updater.allowIntent(this))

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

    companion object {
        /** On a notification's intent: show the dash. */
        const val EXTRA_DASH = "dash"
    }
}
