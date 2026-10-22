package io.github.purplepalm.birdair.phone

import android.Manifest
import android.annotation.SuppressLint
import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.phone.ui.SystemState

/** What the home screen (the dash) shows: riding, parked, nothing running, the dash silent. */
enum class DashView { RIDE, PARKED, IDLE, SILENT }

/** «Еду · авто», or a view the owner pinned until the ride ends. */
enum class ViewPin { AUTO, RIDE, PARKED }

/**
 * «Еду» above [rideAboveKmh] at once; «Стою» after [parkAfterMs] below [parkBelowKmh] (any sample ≥ it cancels the wait);
 * null speed or !streaming: no change. Conservative on purpose: a dropout or a walk keeps «Еду», so «Отключить» keeps
 * asking for the hold and the install stays blocked. Times on the session clock. Not thread-safe: the publisher feeds it.
 */
class MotionView(val parkBelowKmh: Double = 1.0, val rideAboveKmh: Double = 3.0, val parkAfterMs: Long = 10_000) {
    var moving: Boolean = false
        private set
    private var slowSince: Long? = null

    fun update(speedKmh: Double?, streaming: Boolean, nowMs: Long): Boolean {
        if (speedKmh == null || !speedKmh.isFinite() || !streaming) return moving
        when {
            speedKmh > rideAboveKmh -> { moving = true; slowSince = null }
            speedKmh < parkBelowKmh -> if (moving) {
                val since = slowSince ?: nowMs.also { slowSince = it }
                if (nowMs - since >= parkAfterMs) { moving = false; slowSince = null }
            }
            else -> slowSince = null
        }
        return moving
    }

    fun reset() { moving = false; slowSince = null }
}

object DashViews {
    /** «Отключить» while moving: held this long. */
    const val HOLD_MS = 700L
    /** Tiles ignore taps right after they appear (a view switch must not take a tap meant for what was there). */
    const val TILE_GUARD_MS = 600L

    /**
     * !running → IDLE; running, nothing streamed yet this session and the link not up yet → IDLE («Подключаюсь…»);
     * link != CONNECTED or !streaming → SILENT (the dash quiet, a reconnect); else a pinned RIDE / PARKED wins, AUTO →
     * [moving] ? RIDE : PARKED.
     */
    fun of(running: Boolean, link: Link, streaming: Boolean, streamed: Boolean, moving: Boolean, pin: ViewPin): DashView = when {
        !running -> DashView.IDLE
        !streamed && link != Link.CONNECTED -> DashView.IDLE
        link != Link.CONNECTED || !streaming -> DashView.SILENT
        pin == ViewPin.RIDE -> DashView.RIDE
        pin == ViewPin.PARKED -> DashView.PARKED
        moving -> DashView.RIDE
        else -> DashView.PARKED
    }

    /** What a press on a [HoldButton][io.github.purplepalm.birdair.phone.ui.shell.HoldButton] did. */
    enum class Press { CONFIRM, HINT }

    /** [hold] (moving): a press of [heldMs] confirms only from [HOLD_MS], a shorter one shows the hint; else any tap confirms. */
    fun press(hold: Boolean, heldMs: Long, holdMs: Long = HOLD_MS): Press = if (!hold || heldMs >= holdMs) Press.CONFIRM else Press.HINT

    /** Tiles shown at [shownAtMs] take taps from [TILE_GUARD_MS] on. */
    fun armed(shownAtMs: Long, nowMs: Long, guardMs: Long = TILE_GUARD_MS): Boolean = nowMs - shownAtMs >= guardMs
}

/** The first-launch card, one step at a time. */
enum class SetupStep { PERMS, NOTIF, BLUETOOTH, BACKGROUND, DONE }

object Setup {
    /**
     * PERMS: any of [scanPerms] missing; NOTIF: only POST_NOTIFICATIONS missing and not put off («Позже»); BLUETOOTH: off;
     * BACKGROUND: battery optimisation still on (or a Samsung, which sleeps apps anyway) and not put off; else DONE.
     * Only while nothing runs: [running] (connecting, the IDLE view too) → DONE, so «Подключаюсь…» / «Отмена» show.
     */
    fun step(sys: SystemState, s: UiSettings, scanPerms: List<String>, running: Boolean = false): SetupStep = when {
        running -> SetupStep.DONE
        scanPerms.any { it in sys.missingPerms } -> SetupStep.PERMS
        sys.missingPerms.isNotEmpty() && sys.missingPerms.all { it == NOTIFY } && !s.notifHintHidden -> SetupStep.NOTIF
        !sys.bluetoothOn -> SetupStep.BLUETOOTH
        (!sys.ignoringBattery || sys.samsung) && !s.batteryHintHidden -> SetupStep.BACKGROUND
        else -> SetupStep.DONE
    }

    // a string compared against the missing list; asked only on 33+
    @SuppressLint("InlinedApi")
    private const val NOTIFY = Manifest.permission.POST_NOTIFICATIONS
}
