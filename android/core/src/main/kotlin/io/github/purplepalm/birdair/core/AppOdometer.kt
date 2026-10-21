package io.github.purplepalm.birdair.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The app's own odometer, which survives the scooter losing progress (after the 15.10 cutoff its odometer
 * restarted at 1434.2 km instead of 1439.6, then once more at 1434.2, later at 1445.0). Fed from every
 * 3A 17 00 0D frame: the trip counter (since power-on) and the odometer; base = odometer − trip is constant
 * within one power-on. A step-by-step port of tools/app_odometer.py (AppOdometerTest replays the logs through
 * both); everything inside is in tenths of a km, like the scooter.
 *
 * [update]: the first frame ever seeds the total with the scooter's odometer. Then
 *  - the same power-on (base the same ±0.1 km, trip not lower): + the trip's increase — a reconnect mid-ride
 *    is not counted twice;
 *  - otherwise, if that much could not have been ridden since the last frame (trip > maxKmh·Δt + slack, both
 *    clocks known): a glitch — trip lower: the frame is ignored; base different: + the trip's increase;
 *  - otherwise a new power-on (trip reset, or the odometer jumped, backwards too): + the whole trip, even what
 *    was ridden without the app;
 *  - an increase is at most maxKmh·Δt + slack when both clocks are known.
 * [lastAddedKm]: what the last [update] added (0 for the seed and for an ignored frame), for consumers that need the
 * distance ridden frame by frame rather than the total (BatteryAdvisor: a correction of the total is not a ride).
 * The app keeps [state] (e.g. as JSON) and passes it back in next time. Not thread-safe.
 */
class AppOdometer(state: State? = null, val params: Params = Params()) {
    /** What the app saves: [totalKm] the app's odometer; [baseKm] and [tripKm] the power-on session of the last
     *  frame; [wallMs] that frame's wall-clock time (epoch ms), null if unknown. */
    @Serializable
    data class State(val totalKm: Double, val baseKm: Double, val tripKm: Double, val wallMs: Long? = null)

    data class Params(val maxKmh: Double = 45.0, val slackKm: Double = 0.3, val sameBaseKm: Double = 0.1)

    private var total: Long? = null
    private var base = 0L
    private var trip = 0L
    private var wall: Long? = null

    /** km the last [update] added to [totalKm]; 0 for the seed and for an ignored frame. */
    var lastAddedKm: Double = 0.0
        private set

    init {
        if (state != null) {
            total = tenths(state.totalKm); base = tenths(state.baseKm); trip = tenths(state.tripKm); wall = state.wallMs
        }
    }

    /** null until the first frame. */
    val state: State?
        get() = total?.let { State(it / 10.0, base / 10.0, trip / 10.0, wall) }

    /** The app's odometer, km; null until the first frame. */
    val totalKm: Double?
        get() = total?.let { it / 10.0 }

    private fun maxTenths(w: Long?): Long? {
        val last = wall
        if (w == null || last == null || w < last) return null
        return ceil(params.maxKmh * (w - last).toDouble() / 360000.0).toLong() + tenths(params.slackKm)
    }

    /** A 3A 17 frame's trip counter and odometer (km), [wallMs] the phone's clock (epoch ms). Returns [totalKm]. */
    fun update(tripKm: Double, odometerKm: Double, wallMs: Long? = null): Double? {
        lastAddedKm = 0.0
        val tr = tenths(tripKm)
        val odo = tenths(odometerKm)
        if (tr < 0 || odo < 0) return totalKm
        val b = odo - tr
        val tot = total
        if (tot == null) {
            total = odo; base = b; trip = tr; wall = wallMs
            return totalKm
        }
        val cap = maxTenths(wallMs)
        var delta: Long
        if (tr >= trip && abs(b - base) <= tenths(params.sameBaseKm)) {
            delta = tr - trip
        } else if (cap != null && tr > cap) {
            if (tr < trip) return totalKm
            delta = tr - trip
        } else {
            delta = tr
        }
        if (cap != null && delta > cap) delta = cap
        total = tot + delta
        lastAddedKm = delta / 10.0
        base = b; trip = tr
        if (wallMs != null) wall = wallMs
        return totalKm
    }

    companion object {
        /** km → tenths of a km, half up (Python: floor(x·10 + 0.5)). */
        fun tenths(km: Double): Long = floor(km * 10 + 0.5).toLong()
    }
}
