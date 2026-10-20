package io.github.purplepalm.birdair.wear

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import io.github.purplepalm.birdair.core.Telemetry
import kotlinx.coroutines.delay

/** What the screen shows: the phone's telemetry, what the watch makes of its freshness, and the phone's flags. */
data class Screen(val t: Telemetry = Telemetry(), val health: Health = Health.NO_PHONE, val flags: PhoneFlags = PhoneFlags()) {
    /** The arc and the watts use the fast estimate. */
    val fast: Boolean get() = PowerView.fast(flags)
}

/** Ambient mode is on: [tick] counts onUpdateAmbient() calls (about once a minute), for the pixel shift. */
data class AmbientState(val burnIn: Boolean = true, val lowBit: Boolean = false, val tick: Int = 0)

internal fun argb(v: Long) = Color(v)

private val WatchColors = Colors(
    primary = argb(Palette.FLOW),
    primaryVariant = argb(Palette.FLOW),
    secondary = argb(Palette.DRAW),
    secondaryVariant = argb(Palette.DRAW),
    error = argb(Palette.CRIT_INK),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    surface = argb(0xFF1C2120),
    onSurface = argb(Palette.INK),
    onSurfaceVariant = argb(Palette.INK2),
    background = Color.Black,
    onBackground = argb(Palette.INK),
)

@Composable
fun WatchTheme(content: @Composable () -> Unit) = MaterialTheme(colors = WatchColors, content = content)

/** The details page goes back to the gauge after this long without a touch: the bar is what matters while riding. */
const val DETAILS_TIMEOUT_MS = 20_000L

/** Two pages: the gauge, and (swipe left) the details. Ambient always shows the gauge. */
@OptIn(ExperimentalWearFoundationApi::class) // HierarchicalFocusCoordinator: rotary input goes to the visible page
@Composable
fun WatchApp(screen: Screen, ambient: AmbientState?, prefs: WatchPrefs = WatchPrefs(), onPrefs: (WatchPrefs) -> Unit = {}) {
    WatchTheme {
        val pager = rememberPagerState { 2 }
        var touchedAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(ambient != null) { if (ambient != null) pager.scrollToPage(0) }
        LaunchedEffect(pager.currentPage, touchedAt) {
            if (pager.currentPage != 0) {
                delay(DETAILS_TIMEOUT_MS)
                pager.animateScrollToPage(0)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touchedAt = SystemClock.uptimeMillis()
                        }
                    }
                },
        ) {
            HorizontalPager(state = pager, userScrollEnabled = ambient == null, modifier = Modifier.fillMaxSize()) { page ->
                HierarchicalFocusCoordinator(requiresFocus = { pager.currentPage == page }) {
                    if (page == 0) GaugeScreen(screen, ambient, smooth = prefs.smooth.resolve(screen.flags.smooth))
                    else DetailsScreen(screen, prefs, onPrefs = onPrefs)
                }
            }
            if (ambient == null) {
                val indicator = remember(pager) { PagerIndicator(pager) }
                HorizontalPageIndicator(indicator, Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp))
            }
        }
    }
}

private class PagerIndicator(private val s: PagerState) : PageIndicatorState {
    override val pageOffset: Float get() = s.currentPageOffsetFraction
    override val selectedPage: Int get() = s.currentPage
    override val pageCount: Int get() = s.pageCount
}
