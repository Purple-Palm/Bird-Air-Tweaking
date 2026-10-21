package io.github.purplepalm.birdair.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's settings ([WatchPrefs]) for the whole process: the gauge changes them on the details page, [RideService]
 * reads the overspeed ones while the screen is off, [WatchFeed] the lamp vibration switch. Kept in SharedPreferences "bal.wear". Main thread.
 */
object Settings {
    const val FILE = "bal.wear"
    private const val KEEP_ON = "keepScreenOn"
    /** «Плавно»: [SmoothPref.key] — phone / on / off. */
    private const val SMOOTH = "smooth"
    private const val OVERSPEED_ON = "overspeedOn"
    private const val OVERSPEED_MPH = "overspeedMph"
    private const val LAMP_BUZZ = "lampBuzz"

    private val state = MutableStateFlow(WatchPrefs())
    val prefs: StateFlow<WatchPrefs> = state.asStateFlow()
    private var sp: SharedPreferences? = null

    @MainThread
    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = p
        state.value = WatchPrefs(
            keepOn = p.getBoolean(KEEP_ON, true),
            smooth = SmoothPref.of(p.getString(SMOOTH, null)),
            overspeedOn = p.getBoolean(OVERSPEED_ON, true),
            overspeedMph = OverspeedPref.clamp(p.getFloat(OVERSPEED_MPH, OverspeedPref.DEFAULT_MPH.toFloat()).toDouble()),
            lampBuzz = p.getBoolean(LAMP_BUZZ, true),
        )
    }

    @MainThread
    fun set(p: WatchPrefs) {
        val v = p.copy(overspeedMph = OverspeedPref.clamp(p.overspeedMph))
        state.value = v
        sp?.edit()
            ?.putBoolean(KEEP_ON, v.keepOn)
            ?.putString(SMOOTH, v.smooth.key)
            ?.putBoolean(OVERSPEED_ON, v.overspeedOn)
            ?.putFloat(OVERSPEED_MPH, v.overspeedMph.toFloat())
            ?.putBoolean(LAMP_BUZZ, v.lampBuzz)
            ?.apply()
    }
}
