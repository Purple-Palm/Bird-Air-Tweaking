package io.github.purplepalm.birdair.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * MY_PACKAGE_REPLACED: the in-app update went in. The kept APK goes ([Updater.cleanup]); if the owner had the link up
 * ([Prefs.wanted]), the service connects again (the installer killed it without onDestroy). Android may still refuse a
 * foreground start from here (Samsung): then a notification says to open the app.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = ctx.applicationContext
        ScooterRepo.init(app)
        Updater.cleanup(app)
        val p = ScooterRepo.prefs
        val a = p.address
        if (!p.wanted || a == null || !Perms.canConnect(app)) return
        try {
            ScooterService.connect(app, a, p.deviceName)
        } catch (e: Exception) {   // ForegroundServiceStartNotAllowedException, SecurityException
            Notifications.channel(app)
            Notifications.alert(app, "Bird Air обновлён — открой и нажми «Подключить»")
        }
    }
}
