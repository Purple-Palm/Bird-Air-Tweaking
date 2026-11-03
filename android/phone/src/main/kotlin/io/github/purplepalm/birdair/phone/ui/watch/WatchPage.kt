package io.github.purplepalm.birdair.phone.ui.watch

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.WatchText
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Plate
import io.github.purplepalm.birdair.phone.ui.PlateTone
import io.github.purplepalm.birdair.phone.ui.Tnum
import io.github.purplepalm.birdair.phone.ui.WellCard
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi
import kotlinx.coroutines.delay

/**
 * «Часы»: the watch on the link and what it shows, the dial face — five live previews, a tap sends the pick to the
 * watch — and the version hint when the watch is behind. «Открыть на часах» is pinned under the page ([WatchPinned]).
 */
@Composable
fun WatchPage(modifier: Modifier = Modifier) {
    val c = LocalBal.current
    val w by ScooterRepo.watch.collectAsStateWithLifecycle()
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val f by ScooterRepo.face.collectAsStateWithLifecycle()
    // «open» ages without a new hello: look again every few seconds while the page is up
    val tick by produceState(0) { while (true) { delay(5_000); value = value + 1 } }
    val open = remember(w, tick) { WatchText.open(w, SystemClock.elapsedRealtime()) }
    val status = WatchText.status(w, open, st.running)
    val hint = WatchText.hint(w.version, w.helloAt > 0, Updater.code)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding)) {
        StatusCard(status)
        Header("ЦИФЕРБЛАТ", WatchText.faceLine(f, status.linked, faceUpdateNeeded(f, w)))
        FacePicker(f.selected, { ScooterRepo.pickFace(it) })
        Text(
            buildAnnotatedString {
                val about = WatchText.about(f.selected)
                val head = about.indexOf(':') + 1
                withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(about.take(head)) }
                append(about.drop(head))
            },
            color = c.ink2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp),
        )
        if (!f.applied) Line(faceStatus(f, w), if (faceUpdateNeeded(f, w)) c.warnInk else c.muted, Modifier.padding(top = 6.dp))
        if (hint != null) Plate(PlateTone.WARN, hint.title, Modifier.padding(top = 12.dp), text = hint.text)
        WatchText.version(w.version, behind = hint != null)?.let { Line(it, c.muted, Modifier.padding(top = 8.dp)) }
        if (w.message.isNotEmpty()) Line(w.message, if (w.err) c.critInk else c.ink2, Modifier.padding(top = 8.dp))
        Line("Выбор — здесь или на часах в «Подробно»; побеждает последний. Экран, вибрация и тап у ограничителя — на часах, «Подробно».",
            c.muted, Modifier.padding(top = 8.dp))
    }
}

/** Pinned above the dock: «Открыть на часах», while a watch is connected. */
@Composable
fun WatchPinned() {
    val ui = LocalUi.current
    val w by ScooterRepo.watch.collectAsStateWithLifecycle()
    BigButton("Открыть на часах", ui::openOnWatch, Modifier.fillMaxWidth(), icon = AppIcons.Watch, enabled = w.nodes.isNotEmpty())
}

/**
 * Kit's SectionHeader with its right side: the title whole first, [trailing] in what is left (cut at its end). Kit's
 * SectionHeader halves the rest between the two, so «ЦИФЕРБЛАТ» broke in two at 360 dp beside a long trailing line.
 */
@Composable
private fun Header(title: String, trailing: String) {
    val c = LocalBal.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(8.dp))
        Text(trailing, color = c.muted, fontSize = 13.sp, style = Tnum, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) =
    Text(text, color = color, fontSize = 13.sp, lineHeight = 18.sp, style = Tnum, modifier = modifier)

/** The watch: its name, «● на связи» and what its app does now. */
@Composable
private fun StatusCard(s: WatchText.Status) {
    val c = LocalBal.current
    WellCard(padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(c.well, CircleShape).border(1.dp, c.hair, CircleShape), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Watch, contentDescription = null, tint = if (s.linked) c.goodInk else c.ink2, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = c.ink, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildAnnotatedString {
                        if (s.linked) {
                            withStyle(SpanStyle(color = c.goodInk)) { append("● на связи") }
                            append(" · ")
                        }
                        append(s.line)
                    },
                    color = c.muted, fontSize = 13.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}
