package io.github.purplepalm.birdair.phone.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.DashText
import io.github.purplepalm.birdair.phone.DashView
import io.github.purplepalm.birdair.phone.DashViews
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.SetupStep
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Tnum
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Under the main area: on the dash the tiles (PARKED / IDLE) and the action row, in the thumb zone; with a page open
 * its pinned action on a surface with a hairline (nothing when the page has none).
 */
@Composable
fun BottomZone(page: Page?, view: DashView, setup: SetupStep) {
    val c = LocalBal.current
    val snack = LocalSnack.current
    // the one-tap marker's question: Back keeps it unlabelled
    if (snack.current is Snack.Mark) BackHandler { snack.dismiss() }
    if (page != null) {
        if (!pagePinned(page)) return
        Column(
            Modifier.fillMaxWidth().testTag(TAG_BOTTOM).background(c.surface)
                .drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) { PagePinned(page) }
        return
    }
    Column(Modifier.fillMaxWidth().testTag(TAG_BOTTOM).padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if ((view == DashView.PARKED || view == DashView.IDLE) && setup == SetupStep.DONE) Tiles(view)
        ActionRow(view, setup)
    }
}

// -- tiles

/**
 * «Отправить · 3 лога → GitHub» and the update tile: one tap does the job. Not tappable for [DashViews.TILE_GUARD_MS]
 * after the row changes under the thumb: the view, a tile appearing or going (the other one halves or doubles), a tile
 * turning into another action («Скачиваю» → «Установить»).
 */
@Composable
fun Tiles(view: DashView) {
    val unsent by ScooterRepo.unsent.collectAsStateWithLifecycle()
    val log by ScooterRepo.log.collectAsStateWithLifecycle()
    val pin by ScooterRepo.viewPin.collectAsStateWithLifecycle()
    val ui = LocalUi.current
    val open = rememberOpenUri()
    val logs = DashText.logsTile(unsent, log, System.currentTimeMillis())
    val upd = rememberUpdateTile()
    if (logs == null && upd == null) return
    val armed = rememberArmed(listOf(view, pin, logs?.kind, upd))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (logs != null) when (logs.kind) {
            DashText.LogsKind.UNSENT -> Tile(AppIcons.Up, LocalBal.current.draw, "Отправить", DashText.logs(logs.n) + " → GitHub", armed = armed, onClick = ui::sendAll)
            DashText.LogsKind.SENDING -> Tile(null, LocalBal.current.flow, "${minOf(logs.n, logs.k + 1)} из ${logs.n}…", "отправляю", armed = armed,
                progress = logs.k.toFloat() / logs.n, spinner = true)
            DashText.LogsKind.SENT -> Tile(AppIcons.Check, LocalBal.current.goodInk, "На GitHub", "коммит " + (logs.commit ?: ""), armed = armed,
                onClick = log.bulkUrl?.let { u -> { open(u) } })
        }
        if (upd != null) UpdateTile(upd, armed)
    }
}

/** One tile, 60 dp: an icon, a bold line and a muted one, a 3 dp progress line at the bottom. */
@Composable
fun RowScope.Tile(
    icon: ImageVector?, tint: Color, title: String, sub: String, armed: Boolean, onClick: (() -> Unit)? = null, progress: Float? = null,
    spinner: Boolean = false, subColor: Color? = null,
) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.weight(1f).height(60.dp).clip(shape).background(c.surface).border(1.dp, c.hair, shape)
            .then(if (onClick != null) Modifier.clickable(enabled = armed, role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$title, $sub" },
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (spinner) CircularProgressIndicator(Modifier.size(22.dp), color = tint, strokeWidth = 2.4.dp)
            else if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = c.ink, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, color = subColor ?: c.muted, fontSize = 12.sp, lineHeight = 16.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(c.flow))
    }
}

/** False for [ms] after [key] changes (the tiles appeared, the view switched): taps go nowhere, nothing changes visually. */
@Composable
fun rememberArmed(key: Any?, ms: Long = DashViews.TILE_GUARD_MS): Boolean {
    var armed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) { armed = false; delay(ms); armed = true }
    return armed
}

// -- the action row

/**
 * 64 dp, the thumb zone. Riding / parked / silent: «Метка» (one tap, the word in the snackbar), «Управление»,
 * «Отключить» (held [DashViews.HOLD_MS] while moving, one tap when stopped). Idle: «Подключить» / «Другой», the
 * first-launch step's action, «Подключаюсь…» / «Отмена».
 */
@Composable
fun ActionRow(view: DashView, setup: SetupStep) {
    val ui = LocalUi.current
    val sys = LocalSys.current
    val nav = LocalNav.current
    val snack = LocalSnack.current
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    val scan by ScooterRepo.scan.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (view != DashView.IDLE) {
            ActionButton("Метка", AppIcons.Flag, Modifier.weight(1f)) {
                val m = ScooterRepo.markNow()
                if (m != null) snack.mark(m) else snack.text("Метка ставится в идущую запись — сначала «Подключить».")
            }
            ActionButton("Управление", AppIcons.Sliders, Modifier.weight(1f)) { nav.sheet(SheetKind.CONTROL) }
            HoldButton(
                "Отключить", AppIcons.Stop, hold = moving,
                onConfirm = {
                    ui.disconnect()
                    snack.text(DashText.disconnected(ScooterRepo.gh.value, ScooterRepo.hasToken.value))
                },
                onShortTap = { snack.text("На ходу «Отключить» нужно удержать — так запись не оборвётся случайно") },
                modifier = Modifier.weight(1f),
            )
            return@Row
        }
        when {
            setup == SetupStep.PERMS -> FillButton(if (sys.permsBlocked) "Открыть настройки" else "Разрешить", AppIcons.Key, Modifier.weight(2f)) {
                if (sys.permsBlocked) ui.openAppSettings() else ui.requestPermissions()
            }
            setup == SetupStep.NOTIF -> {
                FillButton(if (sys.permsBlocked) "Открыть настройки" else "Разрешить", AppIcons.Bell, Modifier.weight(2f)) {
                    if (sys.permsBlocked) ui.openAppSettings() else ui.requestPermissions()
                }
                ActionButton("Позже", null, Modifier.weight(1f), row = true) { ScooterRepo.updateUi { it.copy(notifHintHidden = true) } }
            }
            setup == SetupStep.BLUETOOTH -> FillButton("Включить Bluetooth", AppIcons.Bt, Modifier.weight(2f)) { ui.enableBluetooth() }
            setup == SetupStep.BACKGROUND -> {
                FillButton("Разрешить", AppIcons.Moon, Modifier.weight(2f)) {
                    if (!sys.ignoringBattery) ui.requestIgnoreBattery()
                    else { ui.openAppSettings(); ScooterRepo.updateUi { it.copy(batteryHintHidden = true) } }
                }
                ActionButton("Позже", null, Modifier.weight(1f), row = true) {
                    ScooterRepo.updateUi { it.copy(batteryHintHidden = true) }
                    snack.text("Напомню в Настройках → Работа в фоне")
                }
            }
            st.running -> {
                FillButton("Подключаюсь…", null, Modifier.weight(2f), enabled = false, spinner = true) {}
                ActionButton("Отмена", null, Modifier.weight(1.1f), row = true) { ui.disconnect() }
            }
            st.address == null -> FillButton("Найти самокат", AppIcons.Bt, Modifier.weight(2f), enabled = !scan.scanning) { ui.scan(false) }
            else -> {
                FillButton("Подключить", AppIcons.Bt, Modifier.weight(2f), enabled = !scan.scanning) { ui.connect() }
                ActionButton("Другой", AppIcons.Scooter, Modifier.weight(1.1f), row = true, enabled = !scan.scanning) { ui.scan(false) }
            }
        }
    }
}

/** An outlined action, 64 dp: icon over the label (or beside it, [row]). */
@Composable
fun ActionButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, row: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(64.dp).alpha(if (enabled) 1f else 0.45f).clip(shape).background(c.surface).border(1.dp, c.base, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (row) Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) Icon(icon, contentDescription = null, tint = c.ink, modifier = Modifier.size(24.dp))
            Text(text, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The filled primary action of the idle row (twice as wide). */
@Composable
fun FillButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, enabled: Boolean = true, spinner: Boolean = false, onClick: () -> Unit) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(64.dp).alpha(if (enabled) 1f else 0.45f).clip(shape).background(c.btn)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spinner) { CircularProgressIndicator(Modifier.size(22.dp), color = c.btnInk, strokeWidth = 2.4.dp); Spacer(Modifier.width(10.dp)) }
            else if (icon != null) { Icon(icon, contentDescription = null, tint = c.btnInk, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
            Text(text, color = c.btnInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * [hold]: fires after [holdMs] of pressing, a black 30 % fill running left to right meanwhile; a shorter press →
 * [onShortTap] (the hint). !hold: one tap. Its own pointerInput, not clickable; TalkBack: onClick = onShortTap|onConfirm,
 * onLongClick = onConfirm.
 */
@Composable
fun HoldButton(
    text: String, icon: ImageVector, hold: Boolean, onConfirm: () -> Unit, onShortTap: () -> Unit, modifier: Modifier = Modifier,
    holdMs: Long = DashViews.HOLD_MS,
) {
    val c = LocalBal.current
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirm)
    val short by rememberUpdatedState(onShortTap)
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.height(64.dp).clip(shape).background(c.crit)
            .drawBehind { if (fill.value > 0f) drawRect(Color.Black.copy(alpha = 0.3f), size = size.copy(width = size.width * fill.value)) }
            .pointerInput(hold, holdMs) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hold) {
                        if (waitForUpOrCancellation() != null) confirm()
                        return@awaitEachGesture
                    }
                    val run = scope.launch { fill.snapTo(0f); fill.animateTo(1f, tween(holdMs.toInt(), easing = LinearEasing)) }
                    var up: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    var cancelled = false
                    val ended = withTimeoutOrNull(holdMs) { up = waitForUpOrCancellation(); cancelled = up == null; true }
                    run.cancel()
                    scope.launch { fill.snapTo(0f) }
                    when {
                        ended == null -> { confirm(); waitForUpOrCancellation() }   // held: done; the finger lifts later
                        cancelled -> {}
                        else -> when (DashViews.press(true, up!!.uptimeMillis - down.uptimeMillis, holdMs)) {
                            DashViews.Press.CONFIRM -> confirm()
                            DashViews.Press.HINT -> short()
                        }
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = text + if (hold) ", удерживай" else ""
                onClick { if (hold) short() else confirm(); true }
                onLongClick { confirm(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            if (hold) Text("удерживай", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
