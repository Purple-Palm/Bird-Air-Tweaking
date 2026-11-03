package io.github.purplepalm.birdair.phone

import kotlin.math.abs
import kotlin.math.exp

/**
 * A critically damped spring (ζ = 1): x'' = −ω²·(x − target) − 2ω·x', stepped once per display frame with the exact
 * solution for that frame's dt (so 60, 90 and 120 Hz move the same). From rest a step settles to 2 % in
 * 5.83/ω ([SETTLE_MS] ≈ 110 ms at [OMEGA]) without overshoot. The target moves with every data point, so two rules
 * keep it from ever overshooting then too: a velocity pointing away from the target (it jumped the other way) is
 * dropped, and a step that would cross the target stops on it.
 */
class CriticalSpring(value: Double = 0.0, val omega: Double = OMEGA) {
    var value: Double = value
        private set
    var velocity: Double = 0.0
        private set

    fun snap(x: Double) { value = x; velocity = 0.0 }

    fun settled(target: Double): Boolean = value == target && velocity == 0.0

    /** One frame of [dtS] seconds toward [target]; returns the new value. */
    fun step(target: Double, dtS: Double): Double {
        if (!target.isFinite()) return value
        if (!value.isFinite() || !velocity.isFinite()) { snap(target); return value }
        if (!dtS.isFinite() || dtS <= 0.0) return value
        val d = value - target
        if (d == 0.0) { snap(target); return value }
        var v = velocity
        if (d * v > 0) v = 0.0
        val e = exp(-omega * dtS)
        val k = v + omega * d
        val nd = (d + k * dtS) * e
        val nv = (v - omega * k * dtS) * e
        if (nd * d <= 0.0) snap(target)
        else { value = target + nd; velocity = nv }
        if (abs(value - target) < EPS && abs(velocity) < EPS_V) snap(target)
        return value
    }

    companion object {
        /** rad/s: 2 % settling in 5.834/ω = 110 ms. */
        const val OMEGA = 53.0
        const val SETTLE_MS = 5834.0 / OMEGA
        /** The bar runs −1…1: closer than this to the target is on it. */
        const val EPS = 1e-4
        const val EPS_V = 1e-3
        /** dt of the first frame after a new target arrived on a settled spring. */
        const val FIRST_FRAME_S = 1.0 / 90
    }
}
