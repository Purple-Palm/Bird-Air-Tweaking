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
import io.github.purplepalm.birdair.core.BatteryWarning
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.WarningLevel

/**
 * The «Поездка» channel and the service's ongoing notification (phone only: setLocalOnly, the watch has its app),
 * and the «Предупреждения» channel: low battery and overheating, high priority with vibration ([WarningAlerts]
 * decides when it buzzes). Warnings are not local-only: on the wrist the buzz is what one feels while riding.
 */
object Notifications {
    const val CHANNEL = "ride"
    const val WARN_CHANNEL = "warnings"
    const val ID = 1
    private const val ALERT_ID = 2
    private const val WARN_ID = 3
    private val VIBRATE = longArrayOf(0, 400, 200, 400, 200, 700)

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(CHANNEL, "Поездка", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Связь с самокатом и живые данные, пока приложение работает в фоне"
            setShowBadge(false)
        }
        val warn = NotificationChannel(WARN_CHANNEL, "Предупреждения", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Батарея на исходе или перегрета: с вибрацией, одно и то же — не чаще раза в 5 минут"
            enableVibration(true)
            vibrationPattern = VIBRATE
        }
        nm.createNotificationChannel(ch)
        nm.createNotificationChannel(warn)
    }

    /** The active battery warnings, CRITICAL first; [loud]: buzz (new or stepped up), else the text changes silently. */
    fun warning(ctx: Context, ws: List<BatteryWarning>, loud: Boolean) {
        if (ws.isEmpty()) { cancelWarning(ctx); return }
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val crit = ws.any { it.level == WarningLevel.CRITICAL }
        val hint = WarnText.hint(ws.first())
        val n = NotificationCompat.Builder(ctx, WARN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scooter)
            .setContentTitle(WarnText.title(ws))
            .setContentText(WarnText.line(ws.first()))
            .setStyle(NotificationCompat.BigTextStyle().bigText(WarnText.body(ws) + if (hint.isNotEmpty()) "\n$hint" else ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(if (crit) CRIT else WARN)
            .setVibrate(VIBRATE)
            .setOnlyAlertOnce(!loud)
            .setSilent(!loud)
            .setContentIntent(open)
            .build()
        show(ctx, n, WARN_ID)
    }

    fun cancelWarning(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(WARN_ID) }

    private const val CRIT = 0xFFD03B3B.toInt()
    private const val WARN = 0xFFFAB219.toInt()

    /** [fast]: the line shows the fast power, like the bar. */
    fun build(ctx: Context, t: Telemetry, fast: Boolean = true): Notification {
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
            .setContentText(StatusText.notification(t, fast))
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
    fun show(ctx: Context, n: Notification, id: Int = ID) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ctx.getSystemService(NotificationManager::class.java)?.notify(id, n)
    }

    /** Why the service stopped by itself (scooter gone for long, no permission): stays after the service is gone. */
    fun alert(ctx: Context, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scooter)
            .setContentTitle("Связь с самокатом остановлена")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setLocalOnly(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .build()
        show(ctx, n, ALERT_ID)
    }

    fun cancelAlert(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID) }
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
