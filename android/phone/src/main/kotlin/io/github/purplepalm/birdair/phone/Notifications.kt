package io.github.purplepalm.birdair.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.purplepalm.birdair.core.Telemetry

/** The «Поездка» channel and the service's ongoing notification (phone only: setLocalOnly, the watch has its app). */
object Notifications {
    const val CHANNEL = "ride"
    const val ID = 1

    fun channel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Поездка", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Связь с самокатом и живые данные, пока приложение работает в фоне"
            setShowBadge(false)
        }
        ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
    }

    fun build(ctx: Context, t: Telemetry): Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            ctx, 1,
            Intent(ctx, ScooterService::class.java).setAction(ScooterService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scooter)
            .setContentTitle(StatusText.title(t))
            .setContentText(StatusText.notification(t))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Отключить", stop)
            .build()
    }

    /** Updates the shown notification; without POST_NOTIFICATIONS Android would drop it anyway. */
    fun show(ctx: Context, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ctx.getSystemService(NotificationManager::class.java)?.notify(ID, n)
    }
}

/** Runtime permissions per Android version. */
object Perms {
    /** Talking to a known scooter. */
    fun connect(): List<String> = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** Finding one: Android 12+ «Устройства поблизости», before that location. */
    fun scan(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun notify(): List<String> = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun all(): List<String> = (scan() + notify()).distinct()

    fun missing(ctx: Context, list: List<String>): List<String> =
        list.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun canConnect(ctx: Context) = missing(ctx, connect()).isEmpty()
    fun canScan(ctx: Context) = missing(ctx, scan()).isEmpty()
}
