package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * DialFace.TAKEOVER — «Захват экрана» (gallery/takeover.html): calm — the usual cluster (lamps 26 px, lit 34 px in the
 * columns); yellow grows in place into a 68 px circle; amber — a dashed bezel segment by its lamp and an 80 px icon with
 * its words in the centre (two: 72 px each, more: «+N»); red — the takeover: a 150 px icon, 38 px words, the whole bezel
 * pulsing 1 Hz, after 8 s the calm red layout (and again on a repeat buzz). Port it here, in this file only, with
 * FaceKit (see FaceStack.kt for the contract). Until then: the classic.
 */
@Composable
internal fun FaceTakeover(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) = FaceClassic(state, modifier, nowMs)
