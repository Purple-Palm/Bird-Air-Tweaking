package io.github.purplepalm.birdair.wear

import io.github.purplepalm.birdair.dial.*

/** «Плавно» on the details page: follow the phone's flag, or force it on or off. Kept in the watch's prefs as [key]. */
enum class SmoothPref(val key: String, val label: String) {
    PHONE("phone", "как на телефоне"),
    ON("on", "вкл"),
    OFF("off", "выкл");

    /** Tap on the chip: как на телефоне → вкл → выкл → как на телефоне. */
    fun next(): SmoothPref = entries[(ordinal + 1) % entries.size]

    /** Smoothing on? The phone's flag when following it; a phone that sends no flag animates, as before. */
    fun resolve(phone: Boolean?): Boolean = when (this) {
        PHONE -> phone ?: true
        ON -> true
        OFF -> false
    }

    /** The chip's second line: what it does now, e.g. «как на телефоне: вкл». */
    fun describe(phone: Boolean?): String =
        if (this == PHONE) label + ": " + (if (resolve(phone)) ON.label else OFF.label) else label

    companion object {
        fun of(key: String?): SmoothPref = entries.firstOrNull { it.key == key } ?: PHONE
    }
}

/**
 * The watch's own settings (SharedPreferences "bal.wear", see [Settings]). [tapOn] / [tapMph] / [tapPauseS]: one tap
 * at the limiter ([LimiterTap]), on at 15.5 mph, at most once a minute by default; [overspeedOn] / [overspeedMph]:
 * vibrate without a stop while faster than the limiter ([OverspeedAlarm]), on at 17.5 mph by default. Both mph are
 * FLY's, the other modes scale them ([CuePoints]). [lampBuzz]: «Вибрация ламп» ([LampAlarm]), on by default. [face]: the
 * dial's theme ([DialFace], «Крупные лампы» by default), [faceAtMs] when it was picked (wall clock; 0: never), so the
 * newer of the phone's and the watch's picks wins ([FaceChoice]).
 */
data class WatchPrefs(
    val keepOn: Boolean = true,
    val smooth: SmoothPref = SmoothPref.PHONE,
    val tapOn: Boolean = true,
    val tapMph: Double = SpeedPref.TAP_MPH,
    val tapPauseS: Int = TapPause.DEFAULT_S,
    val overspeedOn: Boolean = true,
    val overspeedMph: Double = SpeedPref.RUMBLE_MPH,
    val lampBuzz: Boolean = true,
    val face: DialFace = DialFace.DEFAULT,
    val faceAtMs: Long = 0,
) {
    val tapPauseMs: Long get() = TapPause.of(tapPauseS) * 1000L

    /** The watch's face as a [FaceChoice]. */
    val faceChoice: FaceChoice get() = FaceChoice(face, faceAtMs, FaceChoice.WATCH)
}
