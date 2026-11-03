package io.github.purplepalm.birdair.phone.ui

import android.os.SystemClock
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.LampEvent
import io.github.purplepalm.birdair.phone.LampText
import io.github.purplepalm.birdair.phone.ScooterRepo
import kotlinx.coroutines.delay

/** The tell-tale colours of the watch dial (lit, and the dim «off»); on a light background a shade deeper. */
fun lampColor(level: LampLevel, dark: Boolean): Color = if (dark) when (level) {
    LampLevel.RED -> Color(0xFFFF3B30)
    LampLevel.AMBER -> Color(0xFFFFB020)
    LampLevel.YELLOW -> Color(0xFFFFE14D)
    LampLevel.GREEN -> Color(0xFF2ECC40)
    LampLevel.BLUE -> Color(0xFF4AA3FF)
    LampLevel.OFF -> Color(0xFF2C3532)
} else when (level) {
    LampLevel.RED -> Color(0xFFD92D20)
    LampLevel.AMBER -> Color(0xFFDB8A00)
    LampLevel.YELLOW -> Color(0xFFB39200)
    LampLevel.GREEN -> Color(0xFF1F9E2C)
    LampLevel.BLUE -> Color(0xFF2A78D6)
    LampLevel.OFF -> Color(0xFFC3CAC7)
}

/** The 12 tell-tale icons, the watch dial's (24-unit viewport, drawn in black and tinted). */
object LampIcons {
    private class P(val d: String, val fill: Boolean = false, val w: Float = 2f, val cap: Boolean = false, val join: Boolean = false)

    private fun icon(name: String, vararg ps: P): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in ps) b.addPath(
            pathData = addPathNodes(p.d),
            fill = if (p.fill) SolidColor(Color.Black) else null,
            stroke = if (p.fill) null else SolidColor(Color.Black),
            strokeLineWidth = p.w,
            strokeLineCap = if (p.cap) StrokeCap.Round else StrokeCap.Butt,
            strokeLineJoin = if (p.join) StrokeJoin.Round else StrokeJoin.Miter,
        )
        return b.build()
    }

    /** By lamp id ([TellTales.IDS]). */
    val ALL: Map<String, ImageVector> by lazy {
        mapOf(
            TellTales.TEMP to icon("temp", P("M9.5 4a2.5 2.5 0 0 1 5 0v9.2a4.5 4.5 0 1 1-5 0z"), P("M9.8 17a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0-4.4 0z", fill = true), P("M12 15V8")),
            TellTales.BATTERY to icon("battery", P("M5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z"),
                P("M21.5 10a1 1 0 0 1 1 1v2a1 1 0 0 1-2 0v-2a1 1 0 0 1 1-1z", fill = true), P("M5.5 9.5h5v5h-5z", fill = true)),
            TellTales.TRACTION to icon("traction", P("M4.5 12a7.5 7.5 0 1 0 15 0a7.5 7.5 0 1 0-15 0z"), P("M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0z", fill = true),
                P("M12 4.5v3M12 16.5v3M4.5 12h3M16.5 12h3")),
            TellTales.CONSUMPTION to icon("consumption", P("M12 3c3 4 6 7 6 11a6 6 0 0 1-12 0c0-4 3-7 6-11z"), P("M12 10v6M9.5 13.5l2.5 2.5 2.5-2.5", w = 1.8f)),
            TellTales.WEAK to icon("weak", P("M3 5l5 7 4-3 9 10", w = 2.2f, cap = true, join = true), P("M16 19h5v-5")),
            TellTales.OVERVOLT to icon("overvolt", P("M13 2L5 13h6l-1 9 8-11h-6z", fill = true)),
            TellTales.ECO to icon("eco", P("M5 19c0-9 6-14 15-14 0 9-5 15-14 15M5 19l8-8", cap = true)),
            TellTales.SERVICE to icon("service", P("M14.5 3.5a5 5 0 0 0-6 6.5L3 15.5 5.5 18 11 12.5a5 5 0 0 0 6.5-6l-3 3-2.5-.5-.5-2.5z", join = true)),
            TellTales.LINK to icon("link", P("M7 7l10 10-5 4V3l5 4L7 17", join = true)),
            TellTales.OVERSPEED to icon("overspeed", P("M4 17a8 8 0 1 1 16 0"), P("M12 17l5-6", w = 2.2f, cap = true)),
            TellTales.CHARGING to icon("charging", P("M8 3v5M16 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4", cap = true)),
            TellTales.COLD to icon("cold", P("M12 2v20M3.3 7l17.4 10M3.3 17L20.7 7", cap = true)),
        )
    }

    fun of(id: String): ImageVector = ALL[id] ?: ALL.getValue(TellTales.SERVICE)
}

/** The blink's phase, 0…1 once a second: red lamps are on for the first [BLINK_ON] of it, dim the rest, like a car's. */
private val BLINK: InfiniteRepeatableSpec<Float> = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart)
private const val BLINK_ON = 0.6f
private const val BLINK_DIM = 0.22f

/** The blink phase for [blinkAlpha]; read it only while drawing (graphicsLayer), so nothing recomposes. Tests start at 0 (on). */
@Composable
fun rememberBlink(): State<Float> = rememberInfiniteTransition(label = "lamps").animateFloat(0f, 1f, BLINK, label = "blink")

/** 1 while a red lamp is on in its blink, [BLINK_DIM] the rest of the second; 1 for anything that does not blink. */
fun blinkAlpha(phase: Float, blinks: Boolean): Float = if (blinks && phase >= BLINK_ON) BLINK_DIM else 1f

/**
 * True while [ScooterRepo.bulbUntil] (elapsedRealtime) is ahead: every lamp lit, like a car's at start and after each
 * (re)connection. The repo keeps the time, so a page composed later does not replay the check.
 */
@Composable
fun rememberBulbCheck(): Boolean {
    val until by ScooterRepo.bulbUntil.collectAsStateWithLifecycle()
    var on by remember(until) { mutableStateOf(until > SystemClock.elapsedRealtime()) }
    LaunchedEffect(until) {
        val left = until - SystemClock.elapsedRealtime()
        if (left > 0) { on = true; delay(left); on = false } else on = false
    }
    return on
}

/**
 * One tell-tale: the icon in its colour on a soft [glow] circle (18 %) when lit, the red one blinking unless [steady]
 * (the bulb check), ▲ in the corner when the temperature climbs fast.
 */
@Composable
fun LampGlyph(id: String, lamp: Lamp?, blink: State<Float>, steady: Boolean, glow: Dp = 34.dp, icon: Dp = 24.dp) {
    val c = LocalBal.current
    val level = lamp?.level ?: LampLevel.OFF
    val on = level != LampLevel.OFF
    val col = lampColor(level, c.dark)
    val blinks = level == LampLevel.RED && !steady
    Box(Modifier.size(glow), contentAlignment = Alignment.Center) {
        Box(Modifier.size(glow).graphicsLayer { alpha = blinkAlpha(blink.value, blinks) }, contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(glow).background(col.copy(alpha = 0.18f), CircleShape))
            Icon(LampIcons.of(id), contentDescription = null, tint = col, modifier = Modifier.size(icon))
        }
        if (lamp?.rising == true) Canvas(Modifier.size(glow * 0.24f, glow * 0.2f).align(Alignment.TopEnd)) {
            drawPath(Path().apply { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height); close() }, col)
        }
    }
}

/** «Лампа «Заряд»: горит красным, мигает — Сейчас отключится · 31,5 В под нагрузкой», «Лампа «ECO»: не горит». */
fun lampDescription(id: String, lamp: Lamp?, units: String): String {
    val name = TellTales.NAMES[id] ?: id
    val level = lamp?.level ?: LampLevel.OFF
    if (lamp == null || level == LampLevel.OFF) return "Лампа «$name»: не горит"
    return "Лампа «$name»: " + LampText.state(level).lowercase() + " — " + LampText.full(lamp, units)
}

/** One line of the lamp journal: the time, a dot in the new colour (a ring in the old one when it went off), the text. */
@Composable
fun JournalRow(e: LampEvent, units: String) {
    val c = LocalBal.current
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Fmt.clock(e.atMs), color = c.muted, fontSize = 12.sp, style = Tnum, modifier = Modifier.width(62.dp))
        Box(
            Modifier.size(8.dp).then(
                if (e.to == LampLevel.OFF) Modifier.border(1.5.dp, lampColor(e.from, c.dark), CircleShape) else Modifier.background(lampColor(e.to, c.dark), CircleShape),
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(LampText.journal(e, units), color = c.ink2, fontSize = 13.sp, lineHeight = 17.sp, style = Tnum, modifier = Modifier.weight(1f))
    }
}
