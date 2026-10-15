package io.github.purplepalm.birdair.phone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The page's colour tokens (assets/app.css :root), light and dark. */
@Immutable
data class Bal(
    val dark: Boolean,
    val bg: Color, val surface: Color, val well: Color,
    val ink: Color, val ink2: Color, val muted: Color, val hair: Color, val base: Color,
    /** Energy out of the battery: the motor. */
    val draw: Color,
    /** Energy into the battery: regen, charging. */
    val flow: Color,
    val good: Color, val warn: Color, val crit: Color,
    val goodInk: Color, val warnInk: Color, val critInk: Color,
    val modeTake: Color, val modeGlide: Color, val modeFly: Color,
    val btn: Color, val btnInk: Color, val focus: Color,
)

val DarkBal = Bal(
    dark = true,
    bg = Color(0xFF0C100F), surface = Color(0xFF151A19), well = Color(0xFF101514),
    ink = Color(0xFFE8EEEC), ink2 = Color(0xFFB3BCB8), muted = Color(0xFF8A938F), hair = Color(0xFF252B29), base = Color(0xFF3A413E),
    draw = Color(0xFFD95926), flow = Color(0xFF3987E5),
    good = Color(0xFF0CA30C), warn = Color(0xFFFAB219), crit = Color(0xFFD03B3B),
    goodInk = Color(0xFF3FC93F), warnInk = Color(0xFFFAB219), critInk = Color(0xFFF07A7A),
    modeTake = Color(0xFF9AA5A1), modeGlide = Color(0xFFF0BF2A), modeFly = Color(0xFFEF5A5A),
    btn = Color(0xFFE8EEEC), btnInk = Color(0xFF0C100F), focus = Color(0xFF6AA8F0),
)

val LightBal = Bal(
    dark = false,
    bg = Color(0xFFEEF1F0), surface = Color(0xFFFBFCFC), well = Color(0xFFF3F6F5),
    ink = Color(0xFF101614), ink2 = Color(0xFF4B5451), muted = Color(0xFF6B7471), hair = Color(0xFFDCE1DF), base = Color(0xFFBFC6C3),
    draw = Color(0xFFEB6834), flow = Color(0xFF2A78D6),
    good = Color(0xFF0CA30C), warn = Color(0xFFFAB219), crit = Color(0xFFD03B3B),
    goodInk = Color(0xFF0B6B0B), warnInk = Color(0xFF855600), critInk = Color(0xFFB42323),
    modeTake = Color(0xFF7A8582), modeGlide = Color(0xFFE0A800), modeFly = Color(0xFFD63A3A),
    btn = Color(0xFF101614), btnInk = Color(0xFFFBFCFC), focus = Color(0xFF2A78D6),
)

val LocalBal = staticCompositionLocalOf { DarkBal }

@Composable
fun BirdTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkBal else LightBal
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = c.btn, onPrimary = c.btnInk,
        secondary = c.flow, onSecondary = Color.White,
        tertiary = c.draw, onTertiary = Color.White,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink,
        surfaceVariant = c.well, onSurfaceVariant = c.ink2,
        surfaceContainer = c.surface, surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.well, surfaceContainerLowest = c.bg,
        outline = c.base, outlineVariant = c.hair,
        error = c.crit, onError = Color.White,
    )
    CompositionLocalProvider(LocalBal provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
