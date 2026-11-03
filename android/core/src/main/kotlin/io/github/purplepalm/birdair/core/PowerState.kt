package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable

/** What the energy does while riding, for the power line: traction, coasting (no current to speak of) or regen. */
@Serializable
enum class PowerState { TRACTION, COAST, REGEN }

/**
 * [PowerState] from the fast current ([FastPower], else the BMS one) with a deadband and hysteresis, one step per BMS
 * frame. Letting go of the throttle is coasting, not regen: 20.10 the current sat at −0.2 / −0.3 A on 22 % of the
 * coasting frames and read «рекуп.»; real braking returns ≥ 1 A from ~6 km/h up (0.55 A per km/h, up to 13–15 A).
 *  - REGEN: I ≤ [Params.regenOnA] on [Params.onFrames] frames in a row at ≥ [Params.regenMinKmh]; left once
 *    I > [Params.regenOffA] has held [Params.offMs];
 *  - TRACTION: I > [Params.tractionOnA] on [Params.onFrames] frames in a row; left once I ≤ [Params.tractionOffA]
 *    has held [Params.offMs];
 *  - else COAST. Straight from one to the other when the other's entry holds.
 * The first frame of a stream (nothing to hold to yet) goes by its own current. A gap over [Params.gapMs] (or time going
 * back) starts over. Not thread-safe: one caller (TelemetryTracker).
 */
class PowerStateTracker(val params: Params = Params()) {
    data class Params(
        val regenOnA: Double = -1.0,
        val regenOffA: Double = -0.5,
        val regenMinKmh: Double = 6.0,
        val tractionOnA: Double = 0.6,
        val tractionOffA: Double = 0.3,
        val onFrames: Int = 2,
        val offMs: Long = 1000,
        val gapMs: Long = 2500,
    )

    /** null until the first frame of a stream. */
    var state: PowerState? = null
        private set
    private var lastT: Long? = null
    private var regenRun = 0
    private var tractionRun = 0
    private var leaving: Long? = null

    fun reset() {
        state = null; lastT = null; regenRun = 0; tractionRun = 0; leaving = null
    }

    /** One BMS frame: [currentA] + traction, [speedKmh] the last 3A 17's. */
    fun update(tMs: Long, currentA: Double, speedKmh: Double): PowerState {
        val p = params
        lastT?.let { if (tMs - it > p.gapMs || tMs < it) reset() }
        lastT = tMs
        regenRun = if (currentA <= p.regenOnA && speedKmh >= p.regenMinKmh) regenRun + 1 else 0
        tractionRun = if (currentA > p.tractionOnA) tractionRun + 1 else 0
        val regen = regenRun >= p.onFrames
        val traction = tractionRun >= p.onFrames
        val s = state
        val next = when {
            s == null -> when {
                currentA <= p.regenOnA && speedKmh >= p.regenMinKmh -> PowerState.REGEN
                currentA > p.tractionOnA -> PowerState.TRACTION
                else -> PowerState.COAST
            }
            s != PowerState.REGEN && regen -> PowerState.REGEN
            s != PowerState.TRACTION && traction -> PowerState.TRACTION
            s == PowerState.REGEN -> if (held(tMs, currentA > p.regenOffA)) PowerState.COAST else s
            s == PowerState.TRACTION -> if (held(tMs, currentA <= p.tractionOffA)) PowerState.COAST else s
            else -> PowerState.COAST
        }
        if (next != s) leaving = null
        state = next
        return next
    }

    /** [c] has been true on every frame for [Params.offMs]. */
    private fun held(t: Long, c: Boolean): Boolean {
        if (!c) { leaving = null; return false }
        val since = leaving ?: t.also { leaving = it }
        return t - since >= params.offMs
    }
}
