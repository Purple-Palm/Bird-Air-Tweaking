package io.github.purplepalm.birdair.phone.ui.logs

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.GhConfig
import io.github.purplepalm.birdair.phone.LogStore
import io.github.purplepalm.birdair.phone.LogsText
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.TextField56
import kotlinx.coroutines.launch

/**
 * Where the logs go (the shell draws the back bar and handles Back; [onBack] is not needed here): owner, repository,
 * branch and folder take effect as typed; the token only through «Сохранить» (or «Проверить», which saves a pasted one
 * first: the check reads the saved token). Pinned under the body: «Проверить» · «Сохранить», above the keyboard too.
 */
@Composable
fun GitHubScreen(@Suppress("UNUSED_PARAMETER") onBack: () -> Unit) {
    val c = LocalBal.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val gh by ScooterRepo.gh.collectAsStateWithLifecycle()
    val hasToken by ScooterRepo.hasToken.collectAsStateWithLifecycle()
    // never saveable: the token stays out of the saved instance state
    var token by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LogsText.Result?>(null) }
    fun save(): Boolean {
        val r = LogsText.saveToken(token, hasToken, ScooterRepo.gh.value)
        r.token?.let { ScooterRepo.setToken(it); token = "" }
        result = r.result
        return r.result.tone != LogsText.Tone.BAD
    }
    val check = {
        if (token.isBlank() || save()) {
            result = LogsText.Result(LogsText.Tone.INFO, "Проверяю…")
            scope.launch { result = LogsText.checked(LogStore.check()) }
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp)) {
            Text("Куда приложение кладёт логи. Репозиторий публичный: логи уходят обезличенными (имя и id самоката → scooter).",
                color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp)
            GhField("Владелец", gh.owner, { v -> ScooterRepo.updateGh { it.copy(owner = v) } }, Modifier.padding(top = 16.dp))
            GhField("Репозиторий", gh.repo, { v -> ScooterRepo.updateGh { it.copy(repo = v) } }, Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhField("Ветка", gh.branch, { v -> ScooterRepo.updateGh { it.copy(branch = v) } }, Modifier.weight(1f))
                GhField("Папка", gh.folder, { v -> ScooterRepo.updateGh { it.copy(folder = v) } }, Modifier.weight(1f))
            }
            TokenField(
                if (hasToken) "Токен сохранён — вставь новый, чтобы заменить" else "Fine-grained токен (Contents: Read and write)",
                token, { token = it }, saved = hasToken, onDone = { if (save()) focus.clearFocus() }, modifier = Modifier.padding(top = 16.dp),
            )
            Text("Токен хранится только на телефоне и нигде не показывается.", color = c.muted, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp))
            result?.let { r -> Plate(plateTone(r.tone), r.title, Modifier.padding(top = 14.dp), text = r.text) }
            if (hasToken) BigButton("Забыть токен", {
                ScooterRepo.setToken(""); token = ""
                result = LogsText.Result(LogsText.Tone.INFO, "Токен удалён из приложения.")
            }, Modifier.fillMaxWidth().padding(top = 22.dp), style = BtnStyle.TEXT_RED)
        }
        Row(
            Modifier.fillMaxWidth().drawBehind { drawLine(c.hair, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BigButton("Проверить", check, Modifier.weight(1f), icon = AppIcons.Check, style = BtnStyle.OUTLINED)
            BigButton("Сохранить", { save() }, Modifier.weight(1f), icon = AppIcons.Key)
        }
    }
}

/** A setting field: its own text (so the cursor stays put), each change trimmed into [GhConfig] at once. */
@Composable
private fun GhField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    var v by rememberSaveable(label) { mutableStateOf(value) }
    TextField56(label, v, { v = it; onChange(it.trim()) }, modifier, onDone = { focus.moveFocus(FocusDirection.Down) }, maxLen = 100)
}

private const val DOTS = "••••••••••••"

/**
 * The token: [TextField56]'s look, hidden as typed. With a token [saved] the empty field shows dots under its label (the
 * label stays up, the prototype's look); they are not the token, only that one is there. Focused, the dots go: what is
 * typed replaces the saved one.
 */
@Composable
private fun TokenField(label: String, value: String, onChange: (String) -> Unit, saved: Boolean, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val dots = remember(c.muted) { SavedDots(c.muted) }
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(500)) }, singleLine = true, interactionSource = src,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = if (saved) { { Text(DOTS, color = c.muted) } } else null,
        visualTransformation = if (saved && value.isEmpty() && !focused) dots else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.focus, unfocusedBorderColor = c.base, focusedLabelColor = c.focus, unfocusedLabelColor = c.muted,
            cursorColor = c.focus, focusedTextColor = c.ink, unfocusedTextColor = c.ink,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
    )
}

/** An empty value shown as [DOTS] in [color]; every offset maps to 0 (there is nothing to put a cursor in). */
private class SavedDots(private val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText = TransformedText(
        AnnotatedString(DOTS, SpanStyle(color = color)),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = 0
            override fun transformedToOriginal(offset: Int): Int = 0
        },
    )

    override fun equals(other: Any?): Boolean = other is SavedDots && other.color == color
    override fun hashCode(): Int = color.hashCode()
}
