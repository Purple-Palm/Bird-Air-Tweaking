package io.github.purplepalm.birdair.wear

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text

/** The watch's own update, one item of the details page: the chip, «Отменить» while downloading, the last message. */
@Composable
fun WatchUpdateItem() {
    val ctx = LocalContext.current
    val s by WatchUpdater.ui.collectAsState()
    LifecycleResumeEffect(Unit) {
        WatchUpdater.resumed(ctx)
        onPauseOrDispose { }
    }
    UpdateBlock(s, HelloInfo.THIS.versionCode, onAction = { WatchUpdater.act(ctx, it) }, onCancel = WatchUpdater::cancel)
}

@Composable
fun UpdateBlock(s: UpdateState, installedCode: Int, onAction: (UpdateAction) -> Unit, onCancel: () -> Unit) {
    val v = UpdateView.of(s, installedCode)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Chip(
            onClick = { onAction(v.action) },
            enabled = v.action != UpdateAction.NONE || v.progress != null,
            label = { Text(v.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            secondaryLabel = v.secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            icon = v.progress?.let { p -> { CircularProgressIndicator(progress = p, modifier = Modifier.size(24.dp), strokeWidth = 3.dp) } },
            colors = if (v.primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (v.cancel) {
            CompactChip(
                onClick = onCancel,
                label = { Text("Отменить", maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        v.hint?.let {
            Text(it, color = argb(Palette.WARN), fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp))
        }
        s.message?.let {
            Text(
                it,
                color = argb(if (s.err) Palette.CRIT_INK else Palette.INK2),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}
