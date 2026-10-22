package io.github.purplepalm.birdair.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * The PackageInstaller session's status (not exported: only the system sends it, through our PendingIntent).
 * STATUS_PENDING_USER_ACTION carries the system's confirmation dialog in EXTRA_INTENT; [WatchUpdater] starts it.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        try {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION)
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) else null
            WatchUpdater.onStatus(context, status, message, confirm)
        } catch (e: Exception) {
            // a malformed status must not crash the app; the details page keeps «Установить»
        }
    }

    companion object {
        const val ACTION = "io.github.purplepalm.birdair.wear.INSTALL_STATUS"
    }
}
