package io.github.purplepalm.birdair.phone.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The screen's own symbols (the prototype's i-*: 24-unit viewport, drawn in black and tinted by Icon). */
object AppIcons {
    private class S(val d: String, val fill: Boolean = false, val stroke: Boolean = true, val w: Float = 2f, val cap: Boolean = false, val join: Boolean = false)

    private fun stroke(d: String, w: Float = 2f, cap: Boolean = false, join: Boolean = false) = S(d, w = w, cap = cap, join = join)
    private fun round(d: String, w: Float = 2f) = S(d, w = w, cap = true, join = true)
    private fun filled(d: String, alsoStroke: Boolean = false, w: Float = 2f) = S(d, fill = true, stroke = alsoStroke, w = w)

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, rx: Float = 0f): String =
        if (rx <= 0f) "M$x ${y}h${w}v${h}h${-w}z"
        else "M${x + rx} ${y}h${w - 2 * rx}a$rx $rx 0 0 1 $rx ${rx}v${h - 2 * rx}a$rx $rx 0 0 1 ${-rx} ${rx}h${-(w - 2 * rx)}" +
            "a$rx $rx 0 0 1 ${-rx} ${-rx}v${-(h - 2 * rx)}a$rx $rx 0 0 1 $rx ${-rx}z"

    private fun icon(name: String, vararg ss: S): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (s in ss) b.addPath(
            pathData = addPathNodes(s.d),
            fill = if (s.fill) SolidColor(Color.Black) else null,
            stroke = if (s.stroke) SolidColor(Color.Black) else null,
            strokeLineWidth = s.w,
            strokeLineCap = if (s.cap) StrokeCap.Round else StrokeCap.Butt,
            strokeLineJoin = if (s.join) StrokeJoin.Round else StrokeJoin.Miter,
        )
        return b.build()
    }

    val Gauge by lazy {
        icon("gauge", stroke(rect(3f, 4f, 7.5f, 7f, 2f)), stroke(rect(13.5f, 4f, 7.5f, 7f, 2f)), stroke(rect(3f, 14f, 7.5f, 7f, 2f)),
            filled(rect(13.5f, 14f, 7.5f, 7f, 2f), alsoStroke = true))
    }
    val Chart by lazy { icon("chart", round("M4 4v16h16"), round("M7.5 15l3.5-5 3 3 5-7")) }
    val Doc by lazy { icon("doc", stroke("M6.5 3h7.5l4.5 4.5V21h-12z", join = true), stroke("M13.5 3v5h5M9.5 12.5h5.5M9.5 16.5h5.5", join = true)) }
    val Watch by lazy { icon("watch", stroke(rect(6f, 6f, 12f, 12f, 3f)), round("M9 6l.7-3h4.6l.7 3M9 18l.7 3h4.6l.7-3M12 9.5V12l1.8 1.2")) }
    val Gear by lazy {
        icon("gear", stroke(circle(12f, 12f, 3f)), stroke(
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09" +
                "A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3" +
                "a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9" +
                "a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06" +
                "a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
            w = 1.8f, join = true,
        ))
    }
    val Flag by lazy { icon("flag", round("M5.5 21V4M5.5 4.5h11.5l-2.4 4.2 2.4 4.3H5.5")) }
    val Sliders by lazy { icon("sliders", stroke("M4 7h9M18 7h2M4 17h3M12 17h8", cap = true), stroke(circle(15.5f, 7f, 2.5f)), stroke(circle(9.5f, 17f, 2.5f))) }
    val Stop by lazy { icon("stop", filled(rect(6.5f, 6.5f, 11f, 11f, 2f))) }
    val Chev by lazy { icon("chev", round("M9.5 6l6 6-6 6")) }
    val Down by lazy { icon("down", round("M6 9.5l6 6 6-6")) }
    val Close by lazy { icon("close", stroke("M6.5 6.5l11 11M17.5 6.5l-11 11", cap = true)) }
    val Share by lazy {
        icon("share", stroke(circle(18f, 5.5f, 2.5f)), stroke(circle(6f, 12f, 2.5f)), stroke(circle(18f, 18.5f, 2.5f)), stroke("M8.3 10.8l7.4-4.1M8.3 13.2l7.4 4.1"))
    }
    val Check by lazy { icon("check", round("M5 12.5l4.5 4.5L19 7.5", w = 2.4f)) }
    val Back by lazy { icon("back", round("M19 12H5.5M11 6l-6 6 6 6")) }
    val Up by lazy {
        icon("up", stroke("M7 18.5h-.5a4.5 4.5 0 0 1-.4-8.98A6 6 0 0 1 17.7 8.6 4.5 4.5 0 0 1 17.5 18.5H17", cap = true), round("M12 20.5v-9M8.6 14.6 12 11.2l3.4 3.4"))
    }
    val Dl by lazy { icon("dl", round("M12 3.5v11M7.3 10 12 14.7 16.7 10M5 19.5h14")) }
    val Refresh by lazy { icon("refresh", round("M20 11.5A8 8 0 1 0 17.7 17.7M20 4.5v7h-7")) }
    val Key by lazy { icon("key", stroke(circle(8f, 12f, 4f)), stroke("M12 12h9M18 12v3M15 12v2", cap = true)) }
    val Bird by lazy { icon("bird", filled("M3 14c4 0 6-3 8-6 1.5-2.2 3.5-3 6-3 0 2-1 3.5-2.5 4.5 2 .5 4 2 5.5 4.5-3 0-5 .5-7 2.5-2.5 2.5-6 3.5-10 3.5 1.5-1 2.5-2.5 2.5-4z")) }
    val Park by lazy { icon("park", stroke(rect(3.5f, 3.5f, 17f, 17f, 4f)), round("M9.5 17V7.5h3.6a3 3 0 0 1 0 6H9.5")) }
    val Ride by lazy { icon("ride", stroke("M4 16.5a8 8 0 1 1 16 0", cap = true), stroke("M12 16.5l4.2-5.2", w = 2.2f, cap = true), filled(circle(12f, 16.5f, 1.6f))) }
    val Lock by lazy { icon("lock", stroke(rect(5f, 10.5f, 14f, 10f, 2.5f)), stroke("M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")) }
    val Bell by lazy { icon("bell", stroke("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0", join = true)) }
    val Moon by lazy { icon("moon", stroke("M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z", join = true)) }
    val Bt by lazy { icon("bt", stroke("M7 7l10 10-5 4V3l5 4L7 17", join = true)) }
    val Scooter by lazy {
        icon("scooter", stroke(circle(5.5f, 17.5f, 2.5f)), stroke(circle(18.5f, 17.5f, 2.5f)), round("M8 17.5h8M16 17.5 13.2 5H16.5M11.3 5h2"))
    }
    val Save by lazy { icon("save", round("M12 3.5v11M7.3 10 12 14.7 16.7 10M4.5 16.5v3h15v-3")) }
    val Ext by lazy { icon("ext", round("M14 4h6v6M20 4l-9 9M18 14v6H4V6h6")) }
    val Light by lazy { icon("light", stroke("M11 6.5C7.5 6.5 5 8.9 5 12s2.5 5.5 6 5.5h1.5v-11z", join = true), stroke("M15.5 8.5 20 7.3M15.5 12H20.5M15.5 15.5 20 16.7", cap = true)) }
    val Sig by lazy { icon("sig", filled("M3 21h3v-4H3zM9 21h3v-8H9zM15 21h3V9h-3zM21 21V4", alsoStroke = true, w = 1.5f)) }
    val Bat by lazy { icon("bat", stroke(rect(7.5f, 3.5f, 9f, 18f, 2f), w = 1.8f), filled(rect(10f, 1.5f, 4f, 2f)), filled(rect(9.3f, 9f, 5.4f, 10.6f, 1f))) }
}
