package io.github.purplepalm.birdair.phone.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.Telemetry
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

/** True for [TellTales.BULB_CHECK_MS] at the start and after each (re)connection: every lamp lit, like a car's. */
@Composable
private fun rememberBulbCheck(link: Link): Boolean {
    var on by remember { mutableStateOf(true) }
    var gen by remember { mutableIntStateOf(0) }
    val connected = link == Link.CONNECTED
    LaunchedEffect(connected) { if (connected) gen++ }
    LaunchedEffect(gen) { on = true; delay(TellTales.BULB_CHECK_MS); on = false }
    return on
}

/**
 * «Приборка»: the 12 tell-tales as the watch dial has them (in [TellTales.IDS] order: its right column, then the left),
 * the line the dial shows, a tap explains a lamp, and the journal of transitions of this recording.
 */
@Composable
fun ClusterCard(t: Telemetry, units: String) {
    val c = LocalBal.current
    val journal by ScooterRepo.journal.collectAsStateWithLifecycle()
    val bulb = rememberBulbCheck(t.link)
    val shown = if (bulb) TellTales.bulbCheck() else t.lamps
    val by = shown.associateBy { it.id }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    // read only while drawing (graphicsLayer): the blink redraws the red lamps, nothing recomposes; tests start at 0 (on)
    val blink = rememberInfiniteTransition(label = "lamps").animateFloat(0f, 1f, BLINK, label = "blink")
    Panel("Приборка", trailing = { if (bulb) Text("проверка ламп", color = c.muted, fontSize = 12.sp) }) {
        val top = TellTales.top(t.lamps)
        Text(
            top?.let { LampText.symbol(it.level) + " " + LampText.full(it, units) } ?: if (t.streaming) "Предупреждений нет" else "Нет живых данных",
            color = top?.let { lampColor(it.level, c.dark) } ?: c.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, style = Tnum,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in TellTales.IDS.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (id in row) LampCell(id, by[id], sel == id, blink, steady = bulb, onClick = { sel = if (sel == id) null else id }, modifier = Modifier.weight(1f))
            }
        }
        val s = sel
        if (s != null) Explain(s, t.lamps.firstOrNull { it.id == s }, units) else Msg("Коснись лампы — что она значит.")
        HorizontalDivider(color = c.hair)
        Journal(journal, units)
    }
}

@Composable
private fun LampCell(id: String, lamp: Lamp?, selected: Boolean, blink: State<Float>, steady: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val level = lamp?.level ?: LampLevel.OFF
    val on = level != LampLevel.OFF
    val col = lampColor(level, c.dark)
    val name = TellTales.NAMES[id] ?: id
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .height(92.dp)
            .clip(shape)
            .background(if (on) col.copy(alpha = 0.10f) else c.well)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.ink2 else if (on) col.copy(alpha = 0.55f) else c.hair, shape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "Лампа «$name»: " + LampText.state(level).lowercase() }
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            val blinks = level == LampLevel.RED && !steady
            Box(Modifier.size(40.dp).graphicsLayer { alpha = if (blinks && blink.value >= BLINK_ON) BLINK_DIM else 1f }, contentAlignment = Alignment.Center) {
                if (on) Box(Modifier.size(40.dp).background(col.copy(alpha = 0.18f), CircleShape))   // the soft glow
                Icon(LampIcons.of(id), contentDescription = null, tint = col, modifier = Modifier.size(28.dp))
            }
            if (lamp?.rising == true) Canvas(Modifier.size(10.dp, 8.dp).align(Alignment.TopEnd)) {
                drawPath(Path().apply { moveTo(0f, size.height); lineTo(size.width / 2, 0f); lineTo(size.width, size.height); close() }, col)
            }
        }
        FitName(name, if (on) c.ink else c.muted)
    }
}

/** The lamp's name in up to two lines, a size smaller when its longest word («Перенапряжение») would break in two. */
@Composable
private fun FitName(name: String, color: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val m = rememberTextMeasurer()
        val base = LocalTextStyle.current   // Text's own style: its letter spacing counts
        val max = constraints.maxWidth
        val size = remember(name, max, base) {
            val word = name.split(' ').maxByOrNull { it.length } ?: name
            var s = NAME_SP
            while (s > MIN_NAME_SP && m.measure(word, base.merge(TextStyle(fontSize = s.sp)), maxLines = 1, softWrap = false).size.width >= max) s -= 0.5f
            s
        }
        Text(name, color = color, fontSize = size.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private const val NAME_SP = 12f
private const val MIN_NAME_SP = 9f

/** What the tapped lamp says now and when it lights. */
@Composable
private fun Explain(id: String, lamp: Lamp?, units: String) {
    val c = LocalBal.current
    val level = lamp?.level ?: LampLevel.OFF
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.well).border(1.dp, c.hair, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(LampIcons.of(id), contentDescription = null, tint = lampColor(level, c.dark), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(TellTales.NAMES[id] ?: id, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(LampText.state(level) + (lamp?.takeIf { level != LampLevel.OFF }?.let { ": " + LampText.full(it, units) } ?: ""),
            color = c.ink, fontSize = 14.sp, style = Tnum)
        Text(LampText.help(id), color = c.ink2, fontSize = 13.sp)
    }
}

/** The lamp transitions of this recording, newest first: [SHOW] of them, «Все» for the rest. */
@Composable
private fun Journal(list: List<LampEvent>, units: String) {
    val c = LocalBal.current
    var all by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Журнал ламп" + if (list.isNotEmpty()) " (${list.size})" else "", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (list.size > SHOW) TextButton(onClick = { all = !all }) { Text(if (all) "Свернуть" else "Все") }
    }
    if (list.isEmpty()) { Msg("Пока ни одна лампа не загоралась и не гасла. Каждая смена — ещё и метка в логе."); return }
    for (e in if (all) list else list.take(SHOW)) {
        val col = lampColor(e.to, c.dark)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.clock(e.atMs), color = c.muted, fontSize = 12.sp, style = Tnum, modifier = Modifier.width(62.dp))
            Box(
                Modifier.size(8.dp).then(
                    if (e.to == LampLevel.OFF) Modifier.border(1.dp, lampColor(e.from, c.dark), CircleShape) else Modifier.background(col, CircleShape),
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(LampText.journal(e, units), color = c.ink2, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val SHOW = 8
