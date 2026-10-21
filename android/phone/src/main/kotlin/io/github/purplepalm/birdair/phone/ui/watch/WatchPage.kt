package io.github.purplepalm.birdair.phone.ui.watch

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.Updater
import io.github.purplepalm.birdair.phone.Versions
import io.github.purplepalm.birdair.phone.WatchStatus
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.BigButton
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.Msg
import io.github.purplepalm.birdair.phone.ui.PagePadding
import io.github.purplepalm.birdair.phone.ui.Panel
import io.github.purplepalm.birdair.phone.ui.shell.LocalUi

/** «Часы» (a stub hosting the old cards; P3 builds the page): the watch's status and version, the dial faces. */
@Composable
fun WatchPage(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WatchCard()
        WatchFaceCard()
    }
}

/** Pinned above the dock: «Открыть на часах», while a watch is connected. */
@Composable
fun WatchPinned() {
    val ui = LocalUi.current
    val w by ScooterRepo.watch.collectAsStateWithLifecycle()
    BigButton("Открыть на часах", ui::openOnWatch, Modifier.fillMaxWidth(), icon = AppIcons.Watch, enabled = w.nodes.isNotEmpty())
}

@Composable
private fun WatchCard() {
    val c = LocalBal.current
    val w: WatchStatus by ScooterRepo.watch.collectAsStateWithLifecycle()
    // re-evaluated with the link state
    val st by ScooterRepo.status.collectAsStateWithLifecycle()
    val seen = w.helloAt > 0 && SystemClock.elapsedRealtime() - w.helloAt < 20_000
    val status = when {
        !w.available -> "нет (Wear OS недоступен на телефоне)"
        w.nodes.isNotEmpty() -> "на связи — " + w.nodes.joinToString()
        !w.checked -> "проверяю…"
        else -> "нет"
    }
    Panel("Часы") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Часы: ", color = c.ink2, fontSize = 15.sp)
            Text(status, color = if (w.nodes.isNotEmpty()) c.goodInk else c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (seen) Msg("Приложение на часах открыто" + if (st.link == Link.IDLE) " — подключись к самокату, и оно оживёт." else ".")
        val v = w.version
        if (v != null) Msg("Версия на часах: " + (v.versionName ?: "") + (v.versionCode?.let { if (v.versionName != null) " (v$it)" else "v$it" } ?: "") +
            " · на телефоне " + Updater.name + " (v" + Updater.code + ")")
        Versions.watchHint(v, w.helloAt > 0, Updater.code)?.let { Msg(it, err = true) }
        Msg(w.message, w.err)
    }
}
