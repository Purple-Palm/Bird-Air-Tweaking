package io.github.purplepalm.birdair.phone.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Commands
import io.github.purplepalm.birdair.core.LampLevel
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.core.TellTales
import io.github.purplepalm.birdair.phone.Found
import io.github.purplepalm.birdair.phone.LampText
import io.github.purplepalm.birdair.phone.Mode
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.UpdPhase
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.ViewPin
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.IconButton48
import io.github.purplepalm.birdair.phone.ui.JournalRow
import io.github.purplepalm.birdair.phone.ui.LampIcons
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.SectionHeader
import io.github.purplepalm.birdair.phone.ui.SettingSwitch
import io.github.purplepalm.birdair.phone.ui.Tag
import io.github.purplepalm.birdair.phone.ui.TagTone
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.blinkAlpha
import io.github.purplepalm.birdair.phone.ui.lampColor
import io.github.purplepalm.birdair.phone.ui.rememberBlink
import io.github.purplepalm.birdair.phone.ui.settings.UpdateCard
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The bottom sheets, in the window (not a Dialog: the shots capture them, and the insets behave as the rest): a scrim, a
 * surface with 28 dp corners over everything, the dock included. [kind] from [Nav]; the scan sheet when [scanShown]
 * ([ScooterRepo.scan]). Back or the scrim closes.
 */
@Composable
fun SheetHost(kind: SheetKind?, scanShown: Boolean, bottomInset: Dp) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val ui = LocalUi.current
    val open = scanShown || kind != null
    // what was open stays drawn while the sheet slides away
    var shown by remember { mutableStateOf<Any?>(null) }
    if (open) shown = if (scanShown) "scan" else kind
    val close: () -> Unit = { if (scanShown) ui.closeScan() else nav.closeSheet() }
    if (open) BackHandler { close() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight * 0.9f
        AnimatedVisibility(open, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(c.scrim).clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Закрыть", onClick = close))
        }
        AnimatedVisibility(
            open, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(240)) { it * 4 / 10 } + fadeIn(tween(120)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxH).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(c.surface)
                    .pointerInput(Unit) { detectTapGestures { } }   // taps on the sheet do not reach the scrim
                    .semantics { paneTitle = "Лист" }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp + bottomInset),
            ) {
                Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(c.base))
                }
                when (val s = shown) {
                    "scan" -> ScanSheet(close)
                    SheetKind.LAMP -> LampSheet(nav.lamp ?: TellTales.BATTERY, close)
                    SheetKind.CONTROL -> ControlSheet(close)
                    SheetKind.UPDATE -> UpdateSheet(close)
                    SheetKind.VIEW -> ViewSheet(close)
                    else -> if (s != null) Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

/** A sheet's title row with ×. */
@Composable
private fun SheetTitle(text: String, close: () -> Unit) {
    val c = LocalBal.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = c.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton48(AppIcons.Close, "Закрыть", close)
    }
}

// -- lamp

/** One lamp: lit as it is now, what it says, what it means, when it was lit this ride; «Все лампы» / «Понятно». */
@Composable
fun LampSheet(id: String, close: () -> Unit) {
    val c = LocalBal.current
    val nav = LocalNav.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val journal by ScooterRepo.journal.collectAsStateWithLifecycle()
    val lamp = t.lamps.firstOrNull { it.id == id }?.takeIf { it.level != LampLevel.OFF }
    val level = lamp?.level ?: LampLevel.OFF
    val col = lampColor(level, c.dark)
    val blink = rememberBlink()
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.dp).graphicsLayer { alpha = blinkAlpha(blink.value, level == LampLevel.RED) }, contentAlignment = Alignment.Center) {
            if (lamp != null) Box(Modifier.size(60.dp).clip(CircleShape).background(col.copy(alpha = 0.18f)))
            Icon(LampIcons.of(id), contentDescription = null, tint = col, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(TellTales.NAMES[id] ?: id, color = c.ink, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(LampText.state(level), color = c.ink2, fontSize = 14.sp)
        }
        IconButton48(AppIcons.Close, "Закрыть", close)
    }
    if (lamp != null) {
        val value = LampText.value(lamp, s.units)?.let { it + if (id == TellTales.BATTERY) " под нагрузкой" else "" }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append(lamp.text.ifEmpty { LampText.short(lamp) })
                if (value != null) withStyleSpan(c.ink2) { append(" · $value") }
            },
            color = c.ink, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, style = Tnum,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.well).border(1.dp, c.hair, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
    Text(LampText.help(id), color = c.ink2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
    val rows = journal.filter { it.id == id }.take(4)
    SectionHeader("КОГДА ГОРЕЛА", trailing = if (rows.isEmpty()) "в этой поездке — нет" else null)
    for (e in rows) JournalRow(e, s.units)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Все лампы", { nav.open(Page.LAMPS) }, Modifier.weight(1f), icon = AppIcons.Gauge, style = BtnStyle.OUTLINED)
        BigButton("Понятно", close, Modifier.weight(1f))
    }
}

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleSpan(color: androidx.compose.ui.graphics.Color, block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit) {
    val i = pushStyle(androidx.compose.ui.text.SpanStyle(color = color, fontWeight = FontWeight.Medium))
    block()
    pop(i)
}

// -- control

/**
 * «Управление»: the explicit «Разрешить команды» switch (off at every start), the three modes (68 dp, the current one in
 * its colour) and the headlight. Everything grey unless allowed and connected; only [Commands.mode] / [Commands.light]
 * through [ScooterRepo.command] (WriteGuard behind it).
 */
@Composable
fun ControlSheet(close: () -> Unit) {
    val c = LocalBal.current
    val t by ScooterRepo.telemetry.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val s by ScooterRepo.settings.collectAsStateWithLifecycle()
    val ctl by ScooterRepo.control.collectAsStateWithLifecycle()
    val can = ctl.allow && st.link == Link.CONNECTED
    SheetTitle("Управление", close)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.well).border(1.dp, c.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 2.dp)) {
        SettingSwitch("Разрешить команды", ctl.allow, { ScooterRepo.setAllowCommands(it) },
            hint = "Только команды приложения Bird: +MODE и +HLGT. Выключается при каждом запуске.")
    }
    SectionHeader("РЕЖИМ")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in Mode.entries) {
            val cur = t.mode == m.n
            val color = when (m) { Mode.TAKEOFF -> c.modeTake; Mode.GLIDE -> c.modeGlide; Mode.FLY -> c.modeFly }
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).height(68.dp).alpha(if (can) 1f else if (cur) 0.62f else 0.38f).clip(shape)
                    .border(if (cur) 3.dp else 1.dp, if (cur) color else c.base, shape)
                    .clickable(enabled = can, role = Role.Button) { Commands.mode(m.n)?.let { ScooterRepo.command(it) } }
                    .semantics(mergeDescendants = true) { contentDescription = "Режим ${m.title}" + if (cur) ", сейчас" else "" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(m.title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
                Text(m.limit(s.units), color = c.muted, fontSize = 12.sp, style = Tnum, maxLines = 1)
            }
        }
    }
    SectionHeader("ФАРА")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Фара вкл", { ScooterRepo.command(Commands.light(true)) }, Modifier.weight(1f), icon = AppIcons.Light, style = BtnStyle.OUTLINED, enabled = can)
        BigButton("Фара выкл", { ScooterRepo.command(Commands.light(false)) }, Modifier.weight(1f), style = BtnStyle.OUTLINED, enabled = can)
    }
    val msg = ctl.message.ifEmpty {
        if (st.link != Link.CONNECTED) "Нет связи с самокатом — команды недоступны."
        else if (ctl.allow) "Команда уйдёт в самокат сразу." else "Кнопки оживут, когда включишь «Разрешить команды»."
    }
    Text(msg, color = if (ctl.err) c.critInk else c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp).heightIn(min = 18.dp))
}

// -- update

/** «Обновление приложения»: the update card (Настройки' own) and the one button under it. */
@Composable
fun UpdateSheet(close: () -> Unit) {
    val c = LocalBal.current
    val u by ScooterRepo.update.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val moving by ScooterRepo.moving.collectAsStateWithLifecycle()
    val phase = rememberUpdPhase()
    SheetTitle("Обновление приложения", close)
    when (phase) {
        UpdPhase.NONE -> Text("Новее нет: v${Updater.code} — последняя сборка.", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        UpdPhase.CHECKING -> Text("Проверяю android-latest…", color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
        else -> UpdateCard(u, phase, st.running, moving)
    }
    UpdateButton(Modifier.fillMaxWidth().padding(top = 14.dp))
}

// -- view

/** «Главный экран»: auto by speed, or always «Еду» / «Стою» until the ride ends. */
@Composable
fun ViewSheet(close: () -> Unit) {
    val c = LocalBal.current
    val pin by ScooterRepo.viewPin.collectAsStateWithLifecycle()
    SheetTitle("Главный экран", close)
    ViewOption(ViewPin.AUTO, pin, "Авто — по скорости", "«Стою» — медленнее 1 км/ч дольше 10 с; «Еду» — сразу, как быстрее 3 км/ч", close)
    ViewOption(ViewPin.RIDE, pin, "Всегда «Еду»", "Крупная скорость и лампы, без плиток — и на светофоре", close)
    ViewOption(ViewPin.PARKED, pin, "Всегда «Стою»", "Поездка, батарея подробно, плитки «Отправить» и «Обновить»", close)
    Text("Выбор держится до конца поездки; «Отключить» на ходу всё равно просит удержание.", color = c.muted, fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ViewOption(p: ViewPin, cur: ViewPin, title: String, sub: String, close: () -> Unit) {
    val c = LocalBal.current
    val on = p == cur
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .drawBehind { if (p != ViewPin.AUTO) drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .selectable(selected = on, role = Role.RadioButton) { ScooterRepo.setViewPin(p); close() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) c.flow else c.base, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(c.flow))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// -- scan

/** «Ищу самокат…» / «Найдено»: rows with RSSI, the saved one tagged; a tap remembers it and connects. */
@Composable
fun ScanSheet(close: () -> Unit) {
    val c = LocalBal.current
    val ui = LocalUi.current
    val scan by ScooterRepo.scan.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    SheetTitle(if (scan.scanning) "Ищу самокат…" else "Найдено", close)
    if (scan.scanning) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = c.flow, trackColor = c.well)
    if (scan.found.isNotEmpty()) Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(c.well).border(1.dp, c.hair, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp),
    ) {
        scan.found.forEachIndexed { i, f -> DeviceRow(f, f.address == st.address, first = i == 0) { ui.choose(f) } }
    }
    val msg = if (scan.found.isEmpty()) scan.message else "Коснись самоката — запомню и подключусь. Приложение Bird должно быть закрыто: руль держит одно подключение."
    if (msg.isNotEmpty()) Text(msg, color = c.muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("Искать ещё", { ui.scan(false) }, Modifier.weight(1f), icon = AppIcons.Refresh, style = BtnStyle.OUTLINED, small = true, enabled = !scan.scanning)
        BigButton("Все устройства", { ui.scan(true) }, Modifier.weight(1f), style = BtnStyle.OUTLINED, small = true, enabled = !scan.scanning)
    }
}

@Composable
private fun DeviceRow(f: Found, saved: Boolean, first: Boolean, onClick: () -> Unit) {
    val c = LocalBal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .drawBehind { if (!first) drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Scooter, contentDescription = null, tint = if (saved) c.goodInk else c.muted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name ?: "(без имени)", color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (saved) { Spacer(Modifier.width(6.dp)); Tag("сохранён", TagTone.OK) }
            }
            Text(f.address, color = c.muted, fontSize = 12.sp, style = Tnum)
        }
        Text(io.github.purplepalm.birdair.phone.Fmt.n(f.rssi.toDouble()) + " dBm", color = c.ink2, fontSize = 13.sp, style = Tnum)
    }
}
