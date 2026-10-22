package io.github.purplepalm.birdair.phone.ui.lamps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.core.Lamp
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.ui.LampGlyph
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.lampDescription
import io.github.purplepalm.birdair.phone.ui.mix
import io.github.purplepalm.birdair.phone.ui.rememberBlink

/**
 * The 12 tell-tales as the watch dial has them, 3 × 4 in [TellTales.IDS] order (its right column, then the left): each
 * cell 86 dp, the icon in its colour on an 18 % glow, the red one blinking unless [steady] (the bulb check), ▲ when the
 * temperature climbs fast. A tap opens the lamp's sheet ([onLamp]).
 */
@Composable
fun LampGrid(lamps: List<Lamp>, units: String, steady: Boolean, onLamp: (String) -> Unit, modifier: Modifier = Modifier) {
    val by = lamps.associateBy { it.id }
    // read only while drawing (graphicsLayer): the blink redraws the red lamps, nothing recomposes; tests start at 0 (on)
    val blink = rememberBlink()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in TellTales.IDS.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in row) LampCell(id, by[id], blink, steady, units, { onLamp(id) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LampCell(id: String, lamp: Lamp?, blink: State<Float>, steady: Boolean, units: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val level = lamp?.level ?: LampLevel.OFF
    val on = level != LampLevel.OFF
    val col = lampColor(level, c.dark)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .height(CELL)
            .clip(shape)
            .background(if (on) mix(c.well, col, 0.10f) else c.well)
            .border(1.dp, if (on) col.copy(alpha = 0.55f) else c.hair, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = lampDescription(id, lamp, units) }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LampGlyph(id, lamp, blink, steady, glow = 40.dp, icon = 28.dp)
        Spacer(Modifier.height(4.dp))
        FitName(TellTales.NAMES[id] ?: id, if (on) c.ink else c.muted)
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

private val CELL = 86.dp
private const val NAME_SP = 12f
private const val MIN_NAME_SP = 9f
