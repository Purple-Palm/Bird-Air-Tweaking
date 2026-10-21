package io.github.purplepalm.birdair.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's settings ([WatchPrefs]) for the whole process: the gauge changes them on the details page, [RideService]
 * reads the speed cues' ones while the screen is off, [WatchFeed] the lamp vibration switch. Kept in SharedPreferences
 * "bal.wear", with [PrefsMigration.VERSION] (v107's rumble default moves on once, see [PrefsMigration]). Main thread.
 */
object Settings {
    const val FILE = "bal.wear"
    private const val VERSION = "prefsVersion"
    private const val KEEP_ON = "keepScreenOn"
    /** «Плавно»: [SmoothPref.key] — phone / on / off. */
    private const val SMOOTH = "smooth"
    private const val TAP_ON = "tapOn"
    private const val TAP_MPH = "tapMph"
    private const val TAP_PAUSE_S = "tapPauseS"
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
        val version = p.getInt(VERSION, 0)
        val rumble = if (p.contains(OVERSPEED_MPH)) p.getFloat(OVERSPEED_MPH, 0f).toDouble() else null
        state.value = WatchPrefs(
            keepOn = p.getBoolean(KEEP_ON, true),
            smooth = SmoothPref.of(p.getString(SMOOTH, null)),
            tapOn = p.getBoolean(TAP_ON, true),
            tapMph = SpeedPref.clamp(p.getFloat(TAP_MPH, SpeedPref.TAP_MPH.toFloat()).toDouble(), SpeedPref.TAP_MPH),
            tapPauseS = TapPause.of(p.getInt(TAP_PAUSE_S, TapPause.DEFAULT_S)),
            overspeedOn = p.getBoolean(OVERSPEED_ON, true),
            overspeedMph = PrefsMigration.overspeedMph(version, rumble),
            lampBuzz = p.getBoolean(LAMP_BUZZ, true),
        )
        // once: the migrated threshold is saved with the version, so a 16 chosen later stays 16
        if (version < PrefsMigration.VERSION) set(state.value)
    }

    @MainThread
    fun set(p: WatchPrefs) {
        val v = p.copy(
            tapMph = SpeedPref.clamp(p.tapMph, SpeedPref.TAP_MPH),
            tapPauseS = TapPause.of(p.tapPauseS),
            overspeedMph = SpeedPref.clamp(p.overspeedMph, SpeedPref.RUMBLE_MPH),
        )
        state.value = v
        sp?.edit()
            ?.putInt(VERSION, PrefsMigration.VERSION)
            ?.putBoolean(KEEP_ON, v.keepOn)
            ?.putString(SMOOTH, v.smooth.key)
            ?.putBoolean(TAP_ON, v.tapOn)
            ?.putFloat(TAP_MPH, v.tapMph.toFloat())
            ?.putInt(TAP_PAUSE_S, v.tapPauseS)
            ?.putBoolean(OVERSPEED_ON, v.overspeedOn)
            ?.putFloat(OVERSPEED_MPH, v.overspeedMph.toFloat())
            ?.putBoolean(LAMP_BUZZ, v.lampBuzz)
            ?.apply()
    }
}
