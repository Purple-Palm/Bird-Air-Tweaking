package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.UpdateFlow
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.Versions
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.blinkAlpha
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.rememberBlink
import io.github.purplepalm.birdair.phone.ui.rememberBulbCheck
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The dock's own height, above the navigation-bar inset; the same on the dash and with a page open. */
val DOCK_HEIGHT = 68.dp

private val TAB_ICONS: Map<Page, ImageVector>
    get() = mapOf(Page.LAMPS to AppIcons.Gauge, Page.CHARTS to AppIcons.Chart, Page.LOGS to AppIcons.Doc, Page.WATCH to AppIcons.Watch, Page.SETTINGS to AppIcons.Gear)

/**
 * The five tabs, always at the same height: a tap pulls the page up, the same tab again folds it («— свернуть к
 * поездке»). On the dash it peeks (rounded, a handle: drag it up for the last page). Badges: unsent logs (count, a
 * ring while sending, a green dot once sent), the update (dot, ring, green dot), the watch older than the phone, the
 * top lamp (blinking when red).
 */
@Composable
fun Dock(page: Page?, bottomInset: Dp) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val peek = page == null
    val shape = if (peek) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(0.dp)
    val drag = remember { floatArrayOf(0f) }
    Box(
        Modifier.fillMaxWidth().then(if (peek) Modifier.bleed(1.dp) else Modifier).height(DOCK_HEIGHT + bottomInset).testTag(TAG_DOCK).clip(shape).background(c.surface)
            .then(
                if (peek) Modifier.border(1.dp, c.hair, shape)
                else Modifier.drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
            )
            .then(
                if (peek) Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { drag[0] = 0f }, onDragEnd = {
                        if (drag[0] < -24.dp.toPx()) nav.open(runCatching { Page.valueOf(ScooterRepo.settings.value.lastPage) }.getOrDefault(Page.LAMPS))
                    }) { ch, dy -> drag[0] += dy; ch.consume() }
                } else Modifier,
            ),
    ) {
        if (peek) Box(Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(36.dp, 4.dp).clip(CircleShape).background(c.base))
        Row(Modifier.fillMaxWidth().height(DOCK_HEIGHT).padding(start = 4.dp, end = 4.dp, top = 10.dp)) {
            for (p in Page.entries) DockTab(p, page == p, Modifier.weight(1f).fillMaxHeight()) { nav.tab(p) }
        }
    }
}

@Composable
private fun DockTab(p: Page, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalBal.current
    Column(
        modifier.selectable(selected = on, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = p.title + if (on) " — свернуть к поездке" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(56.dp, 30.dp)) {
            Box(
                Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp)).background(if (on) c.flow.copy(alpha = 0.22f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { Icon(if (on) AppIcons.Down else TAB_ICONS.getValue(p), contentDescription = null, tint = if (on) c.ink else c.muted, modifier = Modifier.size(22.dp)) }
            TabBadge(p)
        }
        TabLabel(p.title, if (on) c.ink else c.muted)
    }
}

/** The tab's name on one line, a size smaller when it would not fit whole («Настройки» at 360 dp with a big font). */
@Composable
private fun TabLabel(text: String, color: Color) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val m = rememberTextMeasurer()
        val base = LocalTextStyle.current.merge(TextStyle(fontWeight = FontWeight.Medium))
        val max = constraints.maxWidth
        val size = remember(text, max, base) {
            var s = LABEL_SP
            while (s > MIN_LABEL_SP && m.measure(text, base.merge(TextStyle(fontSize = s.sp)), maxLines = 1, softWrap = false).size.width > max) s -= 0.5f
            s
        }
        Text(text, color = color, fontSize = size.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

private const val LABEL_SP = 12f
private const val MIN_LABEL_SP = 9f

/** What a tab carries: Логи the unsent count / a ring / a green dot, Настройки the update, Часы an old watch, Приборка the top lamp. */
@Composable
private fun TabBadge(p: Page) {
    val c = LocalBal.current
    when (p) {
        Page.LOGS -> {
            val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
            val log by ScooterRepo.log.collectAsStateWithLifecycle()
            val tile = DashText.logsTile(unsent, log, System.currentTimeMillis()) ?: return
            when (tile.kind) {
                DashText.LogsKind.UNSENT -> Box(
                    Modifier.offset(x = 34.dp, y = (-3).dp).heightIn(min = 17.dp).widthIn(min = 17.dp).clip(CircleShape).border(2.dp, c.surface, CircleShape)
                        .background(c.draw).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(if (tile.n > 99) "99+" else tile.n.toString(), color = Color.White, fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold) }
                DashText.LogsKind.SENDING -> Box(Modifier.offset(x = 32.dp, y = (-4).dp)) { Ring(tile.k.toFloat() / tile.n, 18.dp) }
                DashText.LogsKind.SENT -> Dot(c.good)
            }
        }
        Page.SETTINGS -> {
            val u by ScooterRepo.update.collectAsStateWithLifecycle()
            when (UpdateFlow.phase(u, Updater.code)) {
                UpdPhase.AVAILABLE -> Dot(c.flow)
                UpdPhase.DOWNLOADING -> Box(Modifier.offset(x = 32.dp, y = (-4).dp)) { Ring(UpdateFlow.fraction(u) ?: 0f, 18.dp) }
                UpdPhase.READY -> Dot(c.good)
                else -> {}
            }
        }
        Page.WATCH -> {
            val w by ScooterRepo.watch.collectAsStateWithLifecycle()
            if (Versions.watchHint(w.version, w.helloAt > 0, Updater.code) != null) Dot(c.warn)
        }
        Page.LAMPS -> {
            // the dot follows the top lamp only: the 15–20 Hz snapshots do not recompose the dock
            val flow = remember { ScooterRepo.telemetry.map { TellTales.top(it.lamps)?.level }.distinctUntilChanged() }
            val level by flow.collectAsStateWithLifecycle(topLevelNow())
            val bulb = rememberBulbCheck()
            val st by ScooterRepo.status.collectAsStateWithLifecycle()
            val l = level
            // the last lamps of a stopped recording are not lit any more
            if (l != null && !bulb && st.running) {
                val blink = rememberBlink()
                Dot(lampColor(l, c.dark), Modifier.graphicsLayer { alpha = blinkAlpha(blink.value, l == LampLevel.RED) })
            }
        }
        Page.CHARTS -> {}
    }
}

/** The top lamp's level right now: the first frame before the flow delivers. */
private fun topLevelNow(): LampLevel? = TellTales.top(ScooterRepo.telemetry.value.lamps)?.level

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    Box(modifier.offset(x = 37.dp, y = 1.dp).size(13.dp).clip(CircleShape).background(c.surface).padding(2.dp).clip(CircleShape).background(color))
}
