package io.github.purplepalm.birdair.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Plays a [Buzz] on the watch's motor. Usage «alarm»: the warning must be felt with the screen off and the app in
 * the background (other usages are dropped for background apps), and «Сейчас отключится» is worth it.
 */
class Buzzer(ctx: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    fun play(b: Buzz) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val effect =
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(b.timings, b.amplitudes, -1)
                else VibrationEffect.createWaveform(b.timings, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: RuntimeException) {
            // no motor, or the system refused: the banner on the gauge still shows the warning
        }
    }
}
