package io.github.purplepalm.birdair.phone.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.purplepalm.birdair.phone.ScooterRepo
import io.github.purplepalm.birdair.phone.ui.SystemState
import io.github.purplepalm.birdair.phone.ui.UiActions

/** The dock's pages; each pulls up over the dash as a sheet. */
enum class Page(val title: String) { LAMPS("Приборка"), CHARTS("Графики"), LOGS("Логи"), WATCH("Часы"), SETTINGS("Настройки") }

/** The bottom sheets over everything (the scan sheet is [ScooterRepo.scan]'s, not here). */
enum class SheetKind { LAMP, CONTROL, UPDATE, VIEW }

/** Full-screen layers above the dock. */
enum class Sub { GITHUB }

/** Where the screen starts (the shots and tests open a page, a sheet or the GitHub screen straight away). */
data class NavStart(val page: Page? = null, val sheet: SheetKind? = null, val lamp: String? = null, val sub: Sub? = null)

/**
 * What is open over the dash: [page] (null: the dash itself), a [sheet] ([lamp]: the lamp id of [SheetKind.LAMP]), the
 * GitHub [sub] screen. No navigation library: four names in a [Saver]. Read by the shell, changed by taps.
 */
@Stable
class Nav(start: NavStart = NavStart()) {
    var page: Page? by mutableStateOf(start.page)
        private set
    var sheet: SheetKind? by mutableStateOf(start.sheet)
        private set
    var lamp: String? by mutableStateOf(start.lamp)
        private set
    var sub: Sub? by mutableStateOf(start.sub)
        private set

    /** A dock tab: the open page again folds it back to the ride, another one opens. */
    fun tab(p: Page) { if (page == p) fold() else open(p) }

    /** [p] pulled up (any sheet and the sub screen close); remembered for the dock handle. */
    fun open(p: Page) {
        sheet = null
        sub = null
        page = p
        if (ScooterRepo.settings.value.lastPage != p.name) ScooterRepo.updateUi { it.copy(lastPage = p.name) }
    }

    /** Back to the dash: the page and the GitHub screen fold. */
    fun fold() { page = null; sub = null }

    /** The dash with nothing over it (a notification tap): the page, the GitHub screen and the sheet go. */
    fun home() { fold(); sheet = null }

    /** The lamp sheet of [id] ([io.github.purplepalm.birdair.core.TellTales.IDS]). */
    fun lamp(id: String) { lamp = id; sheet = SheetKind.LAMP }

    fun sheet(k: SheetKind) { sheet = k }
    fun closeSheet() { sheet = null }

    fun sub(s: Sub) { sub = s }
    fun closeSub() { sub = null }

    companion object {
        val Saver: Saver<Nav, *> = listSaver(
            save = { listOf(it.page?.name, it.sheet?.name, it.lamp, it.sub?.name) },
            restore = { l ->
                Nav(NavStart(l[0]?.let { Page.valueOf(it) }, l[1]?.let { SheetKind.valueOf(it) }, l[2], l[3]?.let { Sub.valueOf(it) }))
            },
        )
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("Nav") }
val LocalUi = staticCompositionLocalOf<UiActions> { error("UiActions") }
val LocalSys = compositionLocalOf { SystemState() }
