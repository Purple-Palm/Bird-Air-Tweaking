package io.github.purplepalm.birdair.wear

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The gauge. Opened from the launcher, from the phone (RemoteActivityHelper, birdair://open) or from the
 * Ongoing Activity. Data comes through [WatchFeed] from onCreate to onDestroy — not only while resumed —
 * so the screen keeps updating in ambient. The screen is kept on while the data is live (see [Idle]).
 */
class MainActivity : ComponentActivity() {
    private val ambient = MutableStateFlow<AmbientState?>(null)
    private val screen = MutableStateFlow(Screen())
    private val keepOn = MutableStateFlow(true)
    private lateinit var prefs: SharedPreferences
    @Volatile private var openedAtMs = 0L
    private var serviceTriedAtMs: Long? = null

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient.value = AmbientState(ambientDetails.burnInProtectionRequired, ambientDetails.deviceHasLowBitAmbient)
        }
        override fun onUpdateAmbient() { ambient.update { it?.copy(tick = it.tick + 1) } }
        override fun onExitAmbient() {
            ambient.value = null
            WatchFeed.hello()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        keepOn.value = prefs.getBoolean(KEEP_ON, true)
        openedAtMs = SystemClock.elapsedRealtime()
        RideService.hiddenByUser = false
        WatchFeed.acquire(this)
        askForNotifications()
        // until onDestroy, ambient included
        lifecycleScope.launch { present() }
        lifecycleScope.launch { holdScreen() }
        setContent {
            val s by screen.collectAsState()
            val a by ambient.collectAsState()
            val k by keepOn.collectAsState()
            WatchApp(s, a, k, onKeepOn = ::setKeepOn)
        }
    }

    override fun onStart() {
        super.onStart()
        openedAtMs = SystemClock.elapsedRealtime()
        WatchFeed.hello()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openedAtMs = SystemClock.elapsedRealtime()
        WatchFeed.hello()
    }

    override fun onDestroy() {
        WatchFeed.release()
        super.onDestroy()
    }

    /** Feed + clock → [screen]; in ambient at most once per [AMBIENT_REFRESH_MS]. */
    private suspend fun present() {
        var shownAt = Long.MIN_VALUE / 2
        combine(WatchFeed.feed, ticker(TICK_MS)) { f, now -> Screen(f.t, Health.of(f.t, f.rxAtMs, now)) }
            .distinctUntilChanged()
            .collectLatest { s ->
                if (ambient.value != null) {
                    val wait = AMBIENT_REFRESH_MS - (SystemClock.elapsedRealtime() - shownAt)
                    if (wait > 0) delay(wait)
                }
                screen.value = s
                shownAt = SystemClock.elapsedRealtime()
                if (s.health.live) startRideService()
            }
    }

    /** FLAG_KEEP_SCREEN_ON while «Экран не гаснет» is on and the data was live within [Idle.AFTER_MS]. */
    private suspend fun holdScreen() {
        combine(WatchFeed.feed, keepOn, ticker(5_000)) { f, k, now -> Idle.keepScreenOn(k, f.liveAtMs, openedAtMs, now) }
            .distinctUntilChanged()
            .collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
    }

    /** The Ongoing Activity: started from here (a foreground service may only start while we are visible). */
    private fun startRideService() {
        if (RideService.running || RideService.hiddenByUser) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val now = SystemClock.elapsedRealtime()
        if (serviceTriedAtMs?.let { now - it < 10_000 } == true) return
        serviceTriedAtMs = now
        try {
            ContextCompat.startForegroundService(this, Intent(this, RideService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not in the foreground after all; retried later
        }
    }

    private fun setKeepOn(on: Boolean) {
        keepOn.value = on
        prefs.edit().putBoolean(KEEP_ON, on).apply()
    }

    /** Once: without it the Ongoing Activity's notification (and its watch-face icon) stays hidden. */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ticker(ms: Long): Flow<Long> = flow {
        while (true) {
            emit(SystemClock.elapsedRealtime())
            delay(ms)
        }
    }

    private companion object {
        const val PREFS = "bal.wear"
        const val KEEP_ON = "keepScreenOn"
        const val ASKED_NOTIFICATIONS = "askedNotifications"
        const val TICK_MS = 500L
        const val AMBIENT_REFRESH_MS = 1000L
    }
}
