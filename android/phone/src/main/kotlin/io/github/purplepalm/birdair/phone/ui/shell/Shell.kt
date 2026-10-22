package io.github.purplepalm.birdair.phone.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.DashView
import io.github.purplepalm.birdair.phone.DashViews
import io.github.purplepalm.birdair.phone.Perms
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Setup
import io.github.purplepalm.birdair.phone.SetupStep
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Test tags of the shell's zones (the shots' fit checks). */
const val TAG_DOCK = "dock"
const val TAG_BOTTOM = "bottomZone"
const val TAG_SNACK = "snack"
const val TAG_MAIN = "main"

/** The snackbar when Android wants «Установка неизвестных приложений» before the first in-app update. */
const val INSTALL_ASK = "Разреши Bird Air ставить обновления — один раз"

/** System-bar heights to use instead of the window's (the shots draw their own bars: Robolectric has none). */
data class ShellInsets(val top: Dp, val bottom: Dp)

val LocalShellInsets = staticCompositionLocalOf<ShellInsets?> { null }

/**
 * The app: the dash is the home; the dock's pages pull up over it as a sheet. Top to bottom: the top bar (the mini dash
 * while a page is open), the main area (the dash or the page, the snackbar at its bottom), the bottom zone (tiles and
 * actions, or the page's pinned action), the dock — always at the same height. Above everything the GitHub layer and the
 * sheets. Only slow flows here; the dash, the mini dash and Приборка collect the 15–20 Hz telemetry themselves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppShell(ui: UiActions, sys: SystemState, start: NavStart = NavStart()) {
    val c = LocalBal.current
    val nav = rememberSaveable(saver = Nav.Saver) { Nav(start) }
    val snack = remember { SnackState() }
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val streamingFlow = remember { ScooterRepo.telemetry.map { it.streaming }.distinctUntilChanged() }
    val streaming by streamingFlow.collectAsStateWithLifecycle(streamingNow())
    val streamed by ScooterRepo.streamed.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    val pin by ScooterRepo.viewPin.collectAsStateWithLifecycle()
    val settings by ScooterRepo.settings.collectAsStateWithLifecycle()
    val scan by ScooterRepo.scan.collectAsStateWithLifecycle()
    val view = DashViews.of(st.running, st.link, streaming, streamed, moving, pin)
    val setup = Setup.step(sys, settings, Perms.scan(), running = st.running)

    // starting the service shows the dash; a tap on a notification shows it with nothing over it
    var wasRunning by rememberSaveable { mutableStateOf(st.running) }
    LaunchedEffect(st.running) {
        if (st.running && !wasRunning) nav.fold()
        if (!st.running) snack.dismissMark()
        wasRunning = st.running
    }
    LaunchedEffect(sys.dashTick) {
        if (sys.dashTick > 0) { nav.home(); if (ScooterRepo.scan.value.shown) ui.closeScan() }
    }
    LaunchedEffect(Unit) { ScooterRepo.toasts.collect { snack.text(it) } }
    // «Установить» held back by Android: the way on, right where the tap was (the update sheet has its own button)
    LaunchedEffect(sys.installAsk) {
        if (sys.installAsk > 0 && nav.sheet != SheetKind.UPDATE) snack.text(INSTALL_ASK, "Разрешить") { ui.allowInstall() }
    }

    val fake = LocalShellInsets.current
    val top = fake?.top ?: WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = fake?.bottom ?: WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // the keyboard is up: the bottom zone and the dock step aside (isImeVisible reads true before the first insets arrive)
    val ime = fake == null && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // the line height follows each text's size (the page's line-height: normal), not bodyLarge's 24 sp
    val text = LocalTextStyle.current.copy(lineHeight = TextUnit.Unspecified)
    CompositionLocalProvider(LocalNav provides nav, LocalUi provides ui, LocalSys provides sys, LocalSnack provides snack, LocalTextStyle provides text) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            // the lowest Back: an open page folds (sheets, the GitHub layer, the marker question and the chart readout come first)
            if (nav.page != null) BackHandler { nav.fold() }
            Column(Modifier.fillMaxSize().padding(top = top).imePadding()) {
                CappedFont { if (nav.page == null) TopBar(view, streaming) else MiniDash(onClick = nav::fold) }
                Box(Modifier.weight(1f).fillMaxWidth().testTag(TAG_MAIN)) {
                    PageLayer(nav, view, setup)
                    SnackHost(snack, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp))
                }
                if (!ime) CappedFont {
                    Column {
                        BottomZone(nav.page, view, setup)
                        Dock(nav.page, bottom)
                    }
                }
            }
            SubHost(nav.sub, top, bottom)
            SheetHost(nav.sheet, scan.shown, bottom)
        }
    }
}

/** The dash, and the page sliding up over it; the dash is not composed while a page is fully open. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun BoxScope.PageLayer(nav: Nav, view: DashView, setup: SetupStep) {
    val page = nav.page
    var last by remember { mutableStateOf(page) }
    if (page != null) last = page
    val holder = rememberSaveableStateHolder()
    val open = updateTransition(page != null, label = "page")
    if (!(open.currentState && open.targetState)) DashHost(view, setup)
    open.AnimatedVisibility(
        visible = { it }, modifier = Modifier.matchParentSize(),
        enter = slideInVertically(tween(260)) { it * 55 / 100 } + fadeIn(tween(260), initialAlpha = 0.4f),
        exit = slideOutVertically(tween(220)) { it * 55 / 100 } + fadeOut(tween(220)),
    ) {
        last?.let { PagePanel(it, holder, nav) }
    }
}

/** The sheet a page lives in: 24 dp top corners, a handle (drag it down to fold), the title row, the body. */
@Composable
private fun PagePanel(page: Page, holder: SaveableStateHolder, nav: Nav) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(Modifier.fillMaxSize().bleed(1.dp).clip(shape).background(c.surface).border(1.dp, c.hair, shape)) {
        val drag = remember { floatArrayOf(0f) }
        Box(
            Modifier.fillMaxWidth().height(20.dp).pointerInput(Unit) {
                detectVerticalDragGestures(onDragStart = { drag[0] = 0f }, onDragEnd = { if (drag[0] > 24.dp.toPx()) nav.fold() }) { ch, dy -> drag[0] += dy; ch.consume() }
            },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(c.base)) }
        Row(Modifier.fillMaxWidth().height(40.dp).padding(start = 17.dp, end = 9.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(page.title, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
            PageTool(page)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 1.dp)) {
            holder.SaveableStateProvider(page.name) { PageBody(page) }
        }
    }
}

/**
 * The fixed-height chrome (top bar, dock, tiles, action row, mini dash) keeps its labels whole with a big system font:
 * text there grows at most to [max] × (the dash itself scales fully and scrolls).
 */
@Composable
fun CappedFont(max: Float = 1.15f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    if (d.fontScale <= max) content()
    else CompositionLocalProvider(LocalDensity provides androidx.compose.ui.unit.Density(d.density, max), content = content)
}

/** Whether data flows right now: the first frame before the flow delivers. */
private fun streamingNow(): Boolean = ScooterRepo.telemetry.value.streaming

/** [by] wider on each side, so the side hairlines of a full-width surface fall off the screen. */
fun Modifier.bleed(by: Dp): Modifier = layout { m, cs ->
    val extra = by.roundToPx()
    if (!cs.hasBoundedWidth) return@layout m.measure(cs).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
    val w = cs.maxWidth + 2 * extra
    val p = m.measure(cs.copy(minWidth = w, maxWidth = w))
    layout(cs.maxWidth, p.height) { p.place(-extra, 0) }
}
