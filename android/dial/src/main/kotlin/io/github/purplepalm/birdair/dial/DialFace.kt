package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The watch's dial themes, «как watch faces, только внутри приложения»: chosen on the phone («Циферблат часов») or on the
 * watch («Подробно»), the newest choice wins ([FaceChoice]). [key]: how it is stored and sent; [title] / [about]: the
 * pickers' name and one line. Each is one file (FaceStack.kt …) drawing a [DialState]; [DialFace] picks it.
 */
enum class DialFace(val key: String, val title: String, val about: String) {
    STACK("stack", "Крупные лампы", "Погасших ламп нет; загоревшаяся раскрывается крупной фишкой с заголовком и числом, остальные — «улыбкой» снизу"),
    TAKEOVER("takeover", "Захват экрана", "Спокойно — привычная приборка; оранжевая выходит в центр, красная занимает весь экран и пульсирует"),
    GROW("grow", "Растущие лампы", "12 ламп живут точками на своих местах; загоревшаяся вырастает на месте в большой значок с табличкой"),
    RING("ring", "Кольцо", "12 ламп — 12 «часов» кольца; горящая раздувается в плитку, главная — в центре: значок, число и что случилось"),
    CLASSIC("classic", "Классика", "Прежний циферблат: 12 ламп столбиками по краям, батарея с процентом и строка лампы в центре");

    companion object {
        /** New installs and v108's (it had the classic dial only): «Крупные лампы». */
        val DEFAULT = STACK

        fun of(key: String?): DialFace = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * [state] drawn by [face]. [nowMs]: a fixed time on the state's clock (shots, tests); null: live, the face's own clock
 * runs on from [DialState.nowMs] while something moves ([rememberFaceClock]).
 */
@Composable
fun DialFace(face: DialFace, state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) {
    when (face) {
        DialFace.STACK -> FaceStack(state, modifier, nowMs)
        DialFace.TAKEOVER -> FaceTakeover(state, modifier, nowMs)
        DialFace.GROW -> FaceGrow(state, modifier, nowMs)
        DialFace.RING -> FaceRing(state, modifier, nowMs)
        DialFace.CLASSIC -> FaceClassic(state, modifier, nowMs)
    }
}

/**
 * A face picked at [atMs] (the wall clock, epoch ms) on [by] ([PHONE] or [WATCH]); 0: the default, never picked. Both
 * apps keep the newest ([newest]) — the watch gets the phone's pick through the Data Layer even if it was off then, and
 * tells back what it shows. Sent as a DataItem on [io.github.purplepalm.birdair.core.Wire.FACE] with [KEY_FACE],
 * [KEY_AT], [KEY_BY].
 */
data class FaceChoice(val face: DialFace, val atMs: Long, val by: String) {
    companion object {
        const val KEY_FACE = "face"
        const val KEY_AT = "at"
        const val KEY_BY = "by"
        const val PHONE = "phone"
        const val WATCH = "wear"

        /** The later pick; a tie keeps [a]. */
        fun newest(a: FaceChoice?, b: FaceChoice?): FaceChoice? = when {
            a == null -> b
            b == null -> a
            b.atMs > a.atMs -> b
            else -> a
        }

        /** From a DataItem's fields; null without a face. An unknown key (a newer build's face) is the default. */
        fun of(face: String?, atMs: Long?, by: String?): FaceChoice? =
            if (face.isNullOrBlank()) null else FaceChoice(DialFace.of(face), atMs ?: 0L, by ?: "")
    }
}
