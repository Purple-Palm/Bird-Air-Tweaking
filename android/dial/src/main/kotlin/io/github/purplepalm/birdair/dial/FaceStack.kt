package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * DialFace.STACK — «Крупные лампы», the default (gallery/stack.html; the owner: «видно издалека хорошо, это главное», a
 * little more and better motion). Off lamps are not drawn (12 dots in the power arc's bottom gap); the main lamp
 * (DialLamps.top) a chip 104 px (red 116) at (118, 272) with its title / valueText / detail right of it up to x 402; the
 * next ones 76 px in the «smile» (156,390 · 240,408 · 324,390) and «+N» 56 px; the quiet ones 36 px icons by the battery;
 * the battery 140×66 → 124×54 → 112×48 as lamps come. Port it here, in this file only: draw with FaceKit (drawPowerArc,
 * drawSpeedTrack, drawBattery, drawGlyph, drawTextAt, FaceKit.pulse / bob / ripple / back …) on its 480-px grid, run
 * the clock with rememberFaceClock(state, nowMs, ::wakeAt-of-this-face), honour DialState.ambient (static, outlines),
 * health.alpha, status (the StatusLine where the clock is), bulbOn, cues (tap, rumble, buzz). Until then: the classic.
 */
@Composable
internal fun FaceStack(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) = FaceClassic(state, modifier, nowMs)
