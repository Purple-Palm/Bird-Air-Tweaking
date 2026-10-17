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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A tiny foreground service behind the Ongoing Activity: while the phone is on a ride (from «подключаюсь»
 * on), the app sits on the watch face (one tap back to the gauge) and its process — with [WatchFeed]'s
 * listener — stays alive.
 * It writes nothing anywhere and stops itself after [Idle.AFTER_MS] without live data, or on «Скрыть».
 */
class RideService : Service() {
    private val scope = MainScope()
    private var notification: NotificationCompat.Builder? = null
    private var ongoing: OngoingActivity? = null
    private var shownText: String? = null
    private var shownAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
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
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
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
        shownAtMs = SystemClock.elapsedRealtime()
    }

    /** Once a second: stop when the ride is over, refresh the status text at most every [RideStatus.EVERY_MS]. */
    private suspend fun watch() {
        val startedAt = SystemClock.elapsedRealtime()
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val f = WatchFeed.feed.value
            if (Idle.expired(f.liveAtMs, startedAt, now)) { finish(); return }
            val text = statusNow(now)
            if (text != shownText && now - shownAtMs >= RideStatus.EVERY_MS) {
                notification?.setContentText(text)
                ongoing?.update(applicationContext, Status.Builder().addTemplate(text).build())
                shownText = text
                shownAtMs = now
            }
            delay(1000)
        }
    }

    private fun statusNow(now: Long = SystemClock.elapsedRealtime()): String {
        val f = WatchFeed.feed.value
        return RideStatus.text(f.t, Health.of(f.t, f.rxAtMs, now))
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
    }
}
