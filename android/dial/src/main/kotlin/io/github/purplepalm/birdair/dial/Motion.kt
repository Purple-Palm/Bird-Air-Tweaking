package io.github.purplepalm.birdair.dial

import kotlin.math.abs
import kotlin.math.exp

/* How the dial's arc and dot move between two messages: pure, so the JVM tests check the maths. */

/**
 * A critically damped spring (x'' = −ω²·(x − target) − 2ω·x'), stepped with its exact solution, so any frame time
 * gives the same path and nothing can blow up. A step from rest covers 95 % in [SETTLE_MS].
 *
 * The velocity is kept across a new [target] (a value arriving mid-move continues smoothly), but limited so the
 * value only ever moves towards the newest target and never past it: velocity away from it is dropped and
 * velocity towards it is capped at ω·distance, which is exactly the condition for a critically damped path not to
 * cross its target. No overshoot, whatever the messages do.
 */
class Spring(val omega: Double = OMEGA, x: Double = 0.0) {
    var x: Double = x
        private set
    var v: Double = 0.0
        private set
    var target: Double = x

    /** Close enough to [target] and slow enough to stop drawing frames. */
    val settled: Boolean get() = abs(x - target) < EPS && abs(v) < EPS * omega

    /** Jump to [to] and stop: smoothing off, ambient, the first value. */
    fun snap(to: Double) {
        x = to; v = 0.0; target = to
    }

    /** Advance by [dtS] seconds towards [target]; settled values land on it exactly. */
    fun step(dtS: Double) {
        if (!x.isFinite() || !target.isFinite()) { snap(if (target.isFinite()) target else 0.0); return }
        val d0 = x - target
        // c = v0 + ω·d0 must have the sign of d0 (or be 0) for the path not to cross; v0 must point at the target
        var v0 = v
        if (d0 > 0) v0 = v0.coerceIn(-omega * d0, 0.0)
        else if (d0 < 0) v0 = v0.coerceIn(0.0, -omega * d0)
        else v0 = 0.0
        val c = v0 + omega * d0
        val dt = if (dtS.isFinite() && dtS > 0) dtS else 0.0
        val e = exp(-omega * dt)
        val d = (d0 + c * dt) * e
        x = target + d
        v = (c - omega * (d0 + c * dt)) * e
        if (settled) { x = target; v = 0.0 }
    }

    companion object {
        /** A step from rest covers 95 % in this long (the phone's old tween took 150 ms to 100 %). */
        const val SETTLE_MS = 110.0
        /** (1 + y)·e^−y = 0.05: the 95 % point of a critically damped step is at ω·t = y. */
        const val Y95 = 4.743864518
        const val OMEGA = Y95 / (SETTLE_MS / 1000.0)
        /** Fraction of the arc scale (1 = full ±135°): 0.0005 is 0.07°, well below a pixel. */
        const val EPS = 0.0005
        /** First frame after a pause: one frame at 60 Hz (the time since the last drawn frame is meaningless). */
        const val FIRST_FRAME_S = 1.0 / 60
        /** A longer gap between frames (the app was paused) is treated as this; the exact step handles any dt. */
        const val MAX_FRAME_S = 0.25
    }
}
