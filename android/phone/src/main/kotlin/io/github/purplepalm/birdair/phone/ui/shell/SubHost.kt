package io.github.purplepalm.birdair.phone.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.purplepalm.birdair.phone.ui.AppIcons
import io.github.purplepalm.birdair.phone.ui.IconButton48
import io.github.purplepalm.birdair.phone.ui.LocalBal
import io.github.purplepalm.birdair.phone.ui.logs.GitHubScreen

/**
 * A full-screen layer above the dock: its own 60 dp back bar, the screen's body and footer under it ([GitHubScreen]
 * draws those). Back closes it, to the page it came from.
 */
@Composable
fun SubHost(sub: Sub?, topInset: Dp, bottomInset: Dp) {
    val c = LocalBal.current
    val nav = LocalNav.current
    var shown by remember { mutableStateOf<Sub?>(null) }
    if (sub != null) shown = sub
    if (sub != null) BackHandler { nav.closeSub() }
    AnimatedVisibility(sub != null, enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(160)), exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(160))) {
        Column(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { detectTapGestures { } }.padding(top = topInset, bottom = bottomInset)) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton48(AppIcons.Back, "Назад", { nav.closeSub() }, tint = c.ink)
                Spacer(Modifier.width(4.dp))
                Text(when (shown) { Sub.GITHUB, null -> "GitHub" }, color = c.ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (shown) {
                    Sub.GITHUB, null -> GitHubScreen(onBack = { nav.closeSub() })
                }
            }
        }
    }
}
