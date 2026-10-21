package io.github.purplepalm.birdair.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.purplepalm.birdair.phone.Found
import io.github.purplepalm.birdair.phone.LocalLog
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Watch
import io.github.purplepalm.birdair.phone.WatchFaces
import io.github.purplepalm.birdair.phone.ui.shell.AppShell
import io.github.purplepalm.birdair.phone.ui.shell.NavStart
import kotlinx.coroutines.delay

/** What the screen asks the activity for (permissions, system screens, the service). */
interface UiActions {
    fun requestPermissions()
    fun openAppSettings()
    fun enableBluetooth()
    fun connect()
    fun disconnect()
    fun scan(all: Boolean)
    fun closeScan()
    fun choose(f: Found)
    fun saveLog()
    fun shareLog()
    fun sendLog()
    /** «Отправить все» неотправленные. */
    fun sendAll()
    fun shareFile(f: LocalLog)
    fun refreshLogs()
    fun openOnWatch()
    fun requestIgnoreBattery()
    /** The one update button: check, download or install, by the state it is in ([io.github.purplepalm.birdair.phone.UpdateFlow.phase]). */
    fun updateStep()
    /** Settings → «Установка неизвестных приложений». */
    fun allowInstall()
}

/** Activity-side state the screen shows; [dashTick] goes up on every notification tap (the dash comes forward). */
data class SystemState(
    val missingPerms: List<String> = emptyList(),
    val permsBlocked: Boolean = false,
    val bluetoothOn: Boolean = true,
    val ignoringBattery: Boolean = true,
    val samsung: Boolean = false,
    val dashTick: Int = 0,
)

/** The app's screen ([AppShell]); [start] opens a page, a sheet or the GitHub screen right away (the shots). */
@Composable
fun MainScreen(ui: UiActions, sys: SystemState, start: NavStart = NavStart()) {
    val settings by ScooterRepo.settings.collectAsStateWithLifecycle()
    // «Не гасить экран»: only while this screen is on top and the setting is on
    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    // «Часы» status while the screen is visible (the service refreshes it itself while it runs), the watch's face report
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (!ScooterRepo.status.value.running) Watch.nodes(ctx)
                WatchFaces.read(ctx)
                delay(10_000)
            }
        }
    }
    AppShell(ui, sys, start)
}
