package io.github.purplepalm.birdair.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A tiny foreground service behind the Ongoing Activity: while the phone is on a ride (from «подключаюсь»
 * on), the app sits on the watch face (one tap back to the gauge) and its process — with [WatchFeed]'s
 * listener — stays alive. It also runs the overspeed vibration ([OverspeedAlarm]): it keeps running while the screen
 * is dim or off, the activity may not.
 * It writes nothing anywhere and stops itself [Idle.AFTER_MS] after the phone's session ended (IDLE or no phone,
 * [SessionIdle]; a pause with the scooter off does not end it), or on «Скрыть».
 */
class RideService : Service() {
    private val scope = MainScope()
    private var notification: NotificationCompat.Builder? = null
    private var ongoing: OngoingActivity? = null
    private var shownText: String? = null
    private var shownKey: String? = null
    private var shownAtMs = 0L
    private val overspeed = OverspeedAlarm()
    private var buzzer: Buzzer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Settings.init(this)
        buzzer = Buzzer(applicationContext)
        WatchFeed.acquire(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            hiddenByUser = true
            finish()
            return START_NOT_STICKY
        }
        if (ongoing == null) {
            goForeground()
            scope.launch { watch() }
            scope.launch { overspeedLoop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // the service goes: so does the vibration, at once
        act(overspeed.stop())
        buzzer = null
        WatchFeed.release()
        running = false
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_ride), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hide = PendingIntent.getService(
            this, 1, Intent(this, RideService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = statusNow()
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ride)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_hide, getString(R.string.ride_hide), hide)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val oa = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, b)
            .setStaticIcon(R.drawable.ic_ride)
            .setTouchIntent(open)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
        oa.apply(applicationContext)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, b.build(), type)
        notification = b
        ongoing = oa
        shownText = text
        shownKey = WatchFeed.feed.value.let { f -> RideStatus.warningKey(f.t, Health.of(f.t, f.rxAtMs, SystemClock.elapsedRealtime())) }
        shownAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Once a second: stop when the ride is over, refresh the status text at most every [RideStatus.EVERY_MS] — at once
     * when the top lamp changes.
     */
    private suspend fun watch() {
        val idle = SessionIdle(SystemClock.elapsedRealtime())
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val f = WatchFeed.feed.value
            val h = Health.of(f.t, f.rxAtMs, now)
            if (idle.expired(h, now)) { finish(); return }
            val text = RideStatus.text(f.t, h)
            val key = RideStatus.warningKey(f.t, h)
            if (RideStatus.due(text, shownText, key, shownKey, now - shownAtMs)) {
                notification?.setContentText(text)
                ongoing?.update(applicationContext, Status.Builder().addTemplate(text).build())
                shownText = text
                shownKey = key
                shownAtMs = now
            }
            delay(1000)
        }
    }

    /**
     * The overspeed vibration: judged on every message and on every change of the setting; while it runs, again when
     * no new data came for [OverspeedAlarm.FRESH_MS] (then it stops at once) and every [OverspeedAlarm.rearmMs] to arm
     * it again.
     */
    private suspend fun overspeedLoop() {
        combine(WatchFeed.feed, Settings.prefs) { f, p -> f to p }.collectLatest { (f, p) ->
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val live = Health.of(f.t, f.rxAtMs, now).live && OverspeedAlarm.fresh(f.freshAtMs, now)
                act(overspeed.update(p.overspeedOn, p.overspeedMph, f.t.speedKmh, live, now))
                if (!overspeed.on) return@collectLatest
                val untilStale = (f.freshAtMs ?: now) + OverspeedAlarm.FRESH_MS + 1 - now
                delay(minOf(overspeed.rearmMs, untilStale).coerceAtLeast(1))
            }
        }
    }

    private fun act(a: OverspeedAlarm.Action) {
        when (a) {
            OverspeedAlarm.Action.START -> buzzer?.rumble()
            OverspeedAlarm.Action.STOP -> buzzer?.stop()
            OverspeedAlarm.Action.NONE -> Unit
        }
        overspeeding.value = overspeed.on
    }

    private fun statusNow(): String {
        val f = WatchFeed.feed.value
        return RideStatus.text(f.t, Health.of(f.t, f.rxAtMs, SystemClock.elapsedRealtime()))
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "ride"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_HIDE = "io.github.purplepalm.birdair.wear.HIDE"

        @Volatile var running = false
            private set

        /** «Скрыть» was tapped: not started again until the gauge is opened anew. */
        @Volatile var hiddenByUser = false

        private val overspeeding = MutableStateFlow(false)

        /** The overspeed vibration is on now: the dial turns its speed dot red. */
        val vibrating: StateFlow<Boolean> = overspeeding.asStateFlow()
    }
}
