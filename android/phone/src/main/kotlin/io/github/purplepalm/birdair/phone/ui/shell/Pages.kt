package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.purplepalm.birdair.phone.ui.charts.ChartsPage
import io.github.purplepalm.birdair.phone.ui.charts.ChartsPinned
import io.github.purplepalm.birdair.phone.ui.lamps.LampsPage
import io.github.purplepalm.birdair.phone.ui.lamps.LampsTool
import io.github.purplepalm.birdair.phone.ui.logs.LogsPage
import io.github.purplepalm.birdair.phone.ui.logs.LogsPinned
import io.github.purplepalm.birdair.phone.ui.settings.SettingsPage
import io.github.purplepalm.birdair.phone.ui.settings.SettingsPinned
import io.github.purplepalm.birdair.phone.ui.watch.WatchPage
import io.github.purplepalm.birdair.phone.ui.watch.WatchPinned

/** The page's body, filling the sheet under its title; it owns its scrolling. */
@Composable
fun PageBody(page: Page) {
    val m = Modifier.fillMaxSize()
    when (page) {
        Page.LAMPS -> LampsPage(m)
        Page.CHARTS -> ChartsPage(m)
        Page.LOGS -> LogsPage(m)
        Page.WATCH -> WatchPage(m)
        Page.SETTINGS -> SettingsPage(m)
    }
}

/** Whether the page pins an action above the dock (Приборка does not). */
fun pagePinned(page: Page): Boolean = page != Page.LAMPS

/** The page's one pinned action (above the dock, on a surface with a hairline). */
@Composable
fun PagePinned(page: Page) {
    when (page) {
        Page.LAMPS -> {}
        Page.CHARTS -> ChartsPinned()
        Page.LOGS -> LogsPinned()
        Page.WATCH -> WatchPinned()
        Page.SETTINGS -> SettingsPinned()
    }
}

/** What sits right of the page's title (Приборка: «проверка ламп» while it runs). */
@Composable
fun PageTool(page: Page) {
    when (page) {
        Page.LAMPS -> LampsTool()
        else -> {}
    }
}
