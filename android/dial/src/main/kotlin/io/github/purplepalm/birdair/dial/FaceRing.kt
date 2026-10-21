package io.github.purplepalm.birdair.dial

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * DialFace.RING — «Кольцо» (gallery/ring.html): the 12 lamps are 12 «hours» of a ring (r 171–213, red 165–213) inside the
 * power arc (r 230, 14 px); a lit one swells into a tile on its place (red 34° × 48 px, amber 28° × 42 px solid, yellow /
 * blue / green outlined), the top lamp pinned in the centre — an 80 px icon, its title 23 px, the number 42 px + unit 22 px,
 * the detail 16 px; the speed track r 148. Port it here, in this file only, with FaceKit (see FaceStack.kt for the
 * contract). Until then: the classic.
 */
@Composable
internal fun FaceRing(state: DialState, modifier: Modifier = Modifier, nowMs: Long? = null) = FaceClassic(state, modifier, nowMs)
