package io.github.purplepalm.birdair.phone.ui.lamps

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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.purplepalm.birdair.core.Telemetry
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.Fmt
import io.github.purplepalm.birdair.phone.LampEvent
import io.github.purplepalm.birdair.phone.LampText
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.LampIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.blinkAlpha
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.rememberBlink
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck

/**
 * «Приборка»: the 12 tell-tales as the watch dial has them (in [TellTales.IDS] order: its right column, then the left),
 * the line the dial shows, a tap explains a lamp, and the journal of transitions of this recording.
 */
@Composable
fun ClusterCard(t: Telemetry, units: String) {
    val c = LocalBal.current
    val journal by ScooterRepo.journal.collectAsStateWithLifecycle()
    val bulb = rememberBulbCheck()
    val shown = if (bulb) TellTales.bulbCheck() else t.lamps
    val by = shown.associateBy { it.id }
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    // read only while drawing (graphicsLayer): the blink redraws the red lamps, nothing recomposes; tests start at 0 (on)
    val blink = rememberBlink()
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
            Box(Modifier.size(40.dp).graphicsLayer { alpha = blinkAlpha(blink.value, blinks) }, contentAlignment = Alignment.Center) {
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
