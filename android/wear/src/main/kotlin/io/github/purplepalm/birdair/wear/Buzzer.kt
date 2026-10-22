package io.github.purplepalm.birdair.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The watch's motor. Usage «alarm»: a lamp's buzz and the speed cues must be felt with the screen off and the app in
 * the background (other usages are dropped for background apps).
 */
class Buzzer(ctx: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    /** A lamp's [Buzz], once. */
    fun play(b: Buzz) = vibrate(b.timings, b.amplitudes, -1)

    /** [LimiterTap]'s one tap. */
    fun tap() = vibrate(LimiterTap.TIMINGS, LimiterTap.AMPLITUDES, -1)

    /** [OverspeedAlarm]'s waveform, repeated until [stop]; calling it again arms it again. */
    fun rumble() = vibrate(OverspeedAlarm.TIMINGS, OverspeedAlarm.AMPLITUDES, OverspeedAlarm.REPEAT)

    fun stop() {
        try { vibrator?.cancel() } catch (e: RuntimeException) { /* no motor */ }
    }

    private fun vibrate(timings: LongArray, amplitudes: IntArray, repeat: Int) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            val effect =
                if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, repeat)
                else VibrationEffect.createWaveform(timings, repeat)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        } catch (e: RuntimeException) {
            // no motor, or the system refused: the dial still shows it
        }
    }
}
