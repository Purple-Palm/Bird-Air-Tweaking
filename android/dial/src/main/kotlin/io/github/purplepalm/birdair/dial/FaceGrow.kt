package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * DialFace.GROW — «Растущие лампы» (gallery/grow.html): the 12 lamps live on their places as dim 8 px dots (r 175); a lit
 * one grows on its place into a big disc touching r 183 from inside — red 126 px, amber 108, yellow / blue / green 104
 * (the second ×0,8, the third ×0,68, the rest mini icons), with a capsule tablet (Lamp.valueText or the title); the red
 * pulses 1 Hz with a ±6 % scale and a ripple; pops on a spring (FaceKit.pop, ω 16, ζ 0,45). Port it here, in this file
 * only, with FaceKit (see FaceStack.kt for the contract); its icons: Glyphs.BATTERY_CUT / BATTERY_LOW / TURTLE. Until
 * then: the classic.
 */
@Composable
internal fun FaceGrow(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) = FaceClassic(state, modifier, nowMs)
