package io.github.purplepalm.birdair.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A page's body inside the pulled-up sheet: 16 dp gutters, 18 dp under the last block. */
val PagePadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 18.dp)

/** [b] over [a] by [t], opaque (the prototype's color-mix). */
fun mix(a: Color, b: Color, t: Float): Color = lerp(a, b, t)

/** «НЕОТПРАВЛЕННЫЕ (3)»: 13 sp SemiBold muted, as given (pass it uppercase); [trailing] in the same line, regular. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: String? = null, first: Boolean = false) {
    val c = LocalBal.current
    Row(
        modifier.fillMaxWidth().padding(top = if (first) 4.dp else 20.dp, bottom = 8.dp).heightIn(min = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // the title first; the trailing text takes the rest, right-aligned (a weighted title got only half of it)
        Text(text, color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, style = Tnum)
        if (trailing != null) {
            Text(trailing, color = c.muted, fontSize = 13.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }
    }
}

enum class PlateTone { CRIT, WARN, INFO, GOOD }

/** A tinted plate with a dot: «Опасно: …», «Конец: BMS отключила батарею», «Все логи уже на GitHub». */
@Composable
fun Plate(tone: PlateTone, title: String, modifier: Modifier = Modifier, text: String? = null, blink: Boolean = false) {
    val c = LocalBal.current
    val (bg, border, dot) = when (tone) {
        PlateTone.CRIT -> Triple(mix(c.surface, c.crit, 0.22f), BorderStroke(2.dp, c.crit), c.crit)
        PlateTone.WARN -> Triple(mix(c.surface, c.warn, 0.16f), BorderStroke(1.dp, c.warn), c.warn)
        PlateTone.INFO -> Triple(c.well, BorderStroke(1.dp, c.hair), c.flow)
        PlateTone.GOOD -> Triple(mix(c.surface, c.good, 0.12f), BorderStroke(1.dp, c.good.copy(alpha = 0.5f)), c.good)
    }
    val phase = if (blink) rememberBlink() else null
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(bg).border(border, shape).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 4.dp).size(12.dp).graphicsLayer { alpha = blinkAlpha(phase?.value ?: 0f, blink) }.background(dot, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp, style = Tnum)
            if (!text.isNullOrEmpty()) Text(text, color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** A card: well on a page (surface on the dash, [onDash]), hairline, 12 dp corners, the whole card a target with [onClick]. */
@Composable
fun WellCard(
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, onDash: Boolean = false, padding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(if (onDash) c.surface else c.well).border(1.dp, c.hair, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(padding),
        content = content,
    )
}

/** A switch row: the whole row (≥ 60 dp) toggles; one-line hint under the title. */
@Composable
fun SettingSwitch(title: String, checked: Boolean, onChange: (Boolean) -> Unit, hint: String? = null, enabled: Boolean = true) {
    val c = LocalBal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp).alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.flow, checkedBorderColor = c.flow,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.well, uncheckedBorderColor = c.base,
            ),
        )
    }
}

/** A row that opens something: ≥ 60 dp, an icon, title and sub, a chevron (or the external-link sign). */
@Composable
fun NavRow(title: String, sub: String? = null, icon: ImageVector? = null, iconTint: Color? = null, external: Boolean = false, onClick: () -> Unit) {
    val c = LocalBal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint ?: c.muted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(8.dp))
        Icon(if (external) AppIcons.Ext else AppIcons.Chev, contentDescription = null, tint = c.muted, modifier = Modifier.size(24.dp))
    }
}

/** A list row, 64 dp, a hairline above: the unsent logs, the scan results. */
@Composable
fun ListRow(title: String, sub: @Composable () -> Unit, icon: ImageVector? = null, trailing: @Composable (() -> Unit)? = null) {
    val c = LocalBal.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.muted, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub()
        }
        trailing?.invoke()
    }
}

/** Segmented choice, 50 dp, a check on the selected one; [weights] per option (the last «вся поездка» wider). */
@Composable
fun Seg(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, weights: List<Float>? = null) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(25.dp)
    Row(modifier.fillMaxWidth().height(50.dp).clip(shape).border(1.dp, c.base, shape)) {
        options.forEachIndexed { i, (key, label) ->
            val on = key == selected
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.base))
            Row(
                Modifier.weight(weights?.getOrNull(i) ?: 1f).fillMaxHeight()
                    .background(if (on) c.flow.copy(alpha = 0.18f) else Color.Transparent)
                    .selectable(selected = on, role = Role.RadioButton) { onPick(key) }
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (on) { Icon(AppIcons.Check, contentDescription = null, tint = c.ink, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
                Text(label, color = c.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

enum class BtnStyle { FILLED, OUTLINED, TEXT, RED, TEXT_RED }

/** A pill button, 56 dp (48 [small]); [progress] 0…1 fills behind the label (a download, a send) and keeps it opaque. */
@Composable
fun BigButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, style: BtnStyle = BtnStyle.FILLED,
    enabled: Boolean = true, small: Boolean = false, progress: Float? = null,
) {
    val c = LocalBal.current
    val h = if (small) 48.dp else 56.dp
    val shape = RoundedCornerShape(h / 2)
    val (bg, ink) = when (style) {
        BtnStyle.FILLED -> c.btn to c.btnInk
        BtnStyle.OUTLINED -> Color.Transparent to c.ink
        BtnStyle.TEXT -> Color.Transparent to c.flow
        BtnStyle.RED -> c.crit to Color.White
        BtnStyle.TEXT_RED -> Color.Transparent to c.critInk
    }
    val outline = style == BtnStyle.OUTLINED || style == BtnStyle.TEXT_RED
    Box(
        modifier.height(h).alpha(if (enabled || progress != null) 1f else 0.4f).clip(shape).background(bg)
            .then(if (outline) Modifier.border(1.dp, c.base, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .drawBehind { if (progress != null) drawRect(c.flow.copy(alpha = 0.45f), size = size.copy(width = size.width * progress.coerceIn(0f, 1f))) },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = if (style == BtnStyle.TEXT) 12.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, color = ink, fontSize = if (small) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Tnum)
        }
    }
}

/** A 48 dp chip: the marker words; [onInverse] inside the snackbar. */
@Composable
fun Chip48(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onInverse: Boolean = false) {
    val c = LocalBal.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(48.dp).alpha(if (enabled) 1f else 0.4f).clip(shape)
            .border(1.dp, if (onInverse) c.invInk.copy(alpha = 0.35f) else c.base, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = if (onInverse) 2.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (onInverse) c.invInk else c.ink, fontSize = if (onInverse) 13.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A round 48 dp icon target. */
@Composable
fun IconButton48(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null) {
    val c = LocalBal.current
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint ?: c.ink2, modifier = Modifier.size(24.dp)) }
}

enum class TagTone { REC, OK, FLOW }

/** «● идёт запись», «сохранён», «проверка ламп». */
@Composable
fun Tag(text: String, tone: TagTone) {
    val c = LocalBal.current
    val (bg, ink) = when (tone) {
        TagTone.REC -> c.crit.copy(alpha = 0.20f) to c.critInk
        TagTone.OK -> c.good.copy(alpha = 0.18f) to c.goodInk
        TagTone.FLOW -> c.flow.copy(alpha = 0.18f) to c.flow
    }
    Text(text, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(bg).padding(horizontal = 7.dp, vertical = 1.dp))
}

/** A 56 dp outlined field with a floating label; [password] hides it, [decimal] the number pad, [onDone] the IME action. */
@Composable
fun TextField56(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String? = null,
    password: Boolean = false, decimal: Boolean = false, onDone: (() -> Unit)? = null, maxLen: Int = 500,
) {
    val c = LocalBal.current
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(maxLen)) }, singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = placeholder?.let { { Text(it, color = c.muted) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { password -> KeyboardType.Password; decimal -> KeyboardType.Decimal; else -> KeyboardType.Text },
            imeAction = ImeAction.Done, autoCorrectEnabled = !password,
        ),
        // no onDone: the default Done (hides the keyboard)
        keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone() }) else KeyboardActions.Default,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.focus, unfocusedBorderColor = c.base, focusedLabelColor = c.focus, unfocusedLabelColor = c.muted,
            cursorColor = c.focus, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}
