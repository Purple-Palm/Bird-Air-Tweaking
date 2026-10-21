package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.phone.Marker
import io.github.purplepalm.birdair.phone.MarkerWords
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BtnStyle
import io.github.purplepalm.birdair.phone.ui.Chip48
import io.github.purplepalm.birdair.phone.ui.IconButton48
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Tnum
import kotlinx.coroutines.delay

/** What the snackbar shows: a line with an optional 48 dp action, or the one-tap marker asking for its word. */
sealed interface Snack {
    data class Text(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null) : Snack
    /** «Метка 4 · 22:12:21 — подписать?» with the analyzer's words; [clock] the marker's time (the first tap's). */
    data class Mark(val marker: Marker, val clock: String) : Snack
}

/** The screen's one snackbar; a new one replaces the old. Text goes after [TEXT_MS], the marker after [MARK_MS]. */
@Stable
class SnackState {
    var current: Snack? by mutableStateOf(null)
        private set
    /** Bumped on every show: the same text twice restarts its timer. */
    var shownAt by mutableIntStateOf(0)
        private set

    fun text(text: String, action: String? = null, onAction: (() -> Unit)? = null) = show(Snack.Text(text, action, onAction))
    fun mark(m: Marker) = show(Snack.Mark(m, ScooterRepo.markClock(m)))
    fun dismiss() { current = null }
    fun dismissMark() { if (current is Snack.Mark) current = null }

    private fun show(s: Snack) { current = s; shownAt++ }

    companion object {
        const val TEXT_MS = 3400L
        const val MARK_MS = 8000L
    }
}

val LocalSnack = staticCompositionLocalOf { SnackState() }

/**
 * Bottom of the main area, so always right above the tiles, the actions or the page's pinned action, never over them.
 * The inverse surface (the snackbar's own tokens), actions 48 dp.
 */
@Composable
fun SnackHost(snack: SnackState, modifier: Modifier = Modifier) {
    val s = snack.current ?: return
    LaunchedEffect(s, snack.shownAt) {
        delay(if (s is Snack.Mark) SnackState.MARK_MS else SnackState.TEXT_MS)
        snack.dismiss()
    }
    val c = LocalBal.current
    Surface(
        modifier = modifier.fillMaxWidth().testTag(TAG_SNACK).semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(14.dp), color = c.inv, contentColor = c.invInk, shadowElevation = 8.dp,
    ) {
        when (s) {
            is Snack.Text -> TextSnack(s, snack)
            is Snack.Mark -> MarkSnack(s, snack)
        }
    }
}

@Composable
private fun TextSnack(s: Snack.Text, snack: SnackState) {
    val c = LocalBal.current
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(s.text, color = c.invInk, fontSize = 14.sp, lineHeight = 18.sp, style = Tnum, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        val a = s.action
        if (a != null) io.github.purplepalm.birdair.phone.ui.BigButton(a, { s.onAction?.invoke(); snack.dismiss() }, style = BtnStyle.TEXT, small = true)
    }
}

/** The one-tap marker: its time is the first tap's; a word renames it in the log (2 taps in all), × keeps «Метка N». */
@Composable
fun MarkSnack(s: Snack.Mark, snack: SnackState) {
    val c = LocalBal.current
    val m = s.marker
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.label) }
                    append(" · " + s.clock + " — подписать?")
                },
                color = c.invInk, fontSize = 14.sp, style = Tnum, modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton48(AppIcons.Close, "Оставить без подписи", { snack.dismiss() }, tint = c.invInk.copy(alpha = 0.8f))
        }
        Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (row in MarkerWords.PRESETS.chunked(3)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (w in row) Chip48(w, {
                    if (ScooterRepo.relabel(m, w)) snack.text("«$w» · ${s.clock} — подписано") else snack.text("Не подписал: запись уже остановлена")
                }, Modifier.weight(w.length + 4f), onInverse = true)
            }
        }
    }
}

/** A commit link etc. from a snack action. */
@Composable
fun rememberOpenUri(): (String) -> Unit {
    val u = LocalUriHandler.current
    return { url -> try { u.openUri(url) } catch (e: Exception) { } }
}
