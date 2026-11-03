package io.github.purplepalm.birdair.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * MY_PACKAGE_REPLACED: the in-app update went in. The kept APK goes ([Updater.cleanup]); if a recording ran when the
 * install began, at most 15 min ago ([Prefs.resumeAfterUpdateAt], [UpdateFlow.resumeTo]), the service connects again
 * (the installer killed it without onDestroy). Not on [Prefs.wanted]: a service the system ended leaves that on, and an
 * update installed with nothing running would start a recording and hold the wake lock (only the update from v111,
 * which kept nothing, goes by it, once). Android may still refuse a foreground start from here (Samsung): then a
 * notification says to open the app.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        ScooterRepo.init(app)
        Updater.cleanup(app)
        val p = ScooterRepo.prefs
        val at = p.resumeAfterUpdateAt
        // once: the next update (by hand, nothing running) must not find it on
        p.resumeAfterUpdateAt = 0L
        val a = UpdateFlow.resumeTo(at, p.wanted, System.currentTimeMillis(), p.address, Perms.canConnect(app)) ?: return
        try {
            ScooterService.connect(app, a, p.deviceName)
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException, SecurityException
            Notifications.channel(app)
            Notifications.alert(app, "Bird Air обновлён — открой и нажми «Подключить»")
        }
    }
}
