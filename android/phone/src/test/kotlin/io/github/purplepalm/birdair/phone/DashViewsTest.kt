package io.github.purplepalm.birdair.phone

import io.github.purplepalm.birdair.core.Link
import io.github.purplepalm.birdair.phone.ui.SystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dash's view: «Еду» / «Стою» by speed with hysteresis, the view for every link state, «Отключить»'s hold, the tiles' guard, the first-launch steps. */
class DashViewsTest {
    private fun moving(vararg samples: Pair<Long, Double?>, streaming: Boolean = true): MotionView {
        val m = MotionView()
        for ((t, v) in samples) m.update(v, streaming, t)
        return m
    }

    @Test fun rideAboveThreeAtOnce() {
        val m = MotionView()
        assertFalse("a new session starts parked", m.moving)
        assertFalse("2,9 km/h is not riding", m.update(2.9, true, 0))
        assertFalse("3,0 neither (above 3)", m.update(3.0, true, 100))
        assertTrue("3,1 km/h: riding at once", m.update(3.1, true, 200))
    }

    @Test fun parkAfterTenSecondsBelowOne() {
        val m = moving(0L to 20.0)
        assertTrue(m.update(0.9, true, 1_000))
        assertTrue("9,999 s below 1 km/h: still riding", m.update(0.0, true, 10_999))
        assertFalse("10 s: parked", m.update(0.0, true, 11_000))
        // and back on the first fast sample
        assertTrue(m.update(3.1, true, 12_000))
    }

    @Test fun oneKmhAndAboveCancelsTheWait() {
        val m = moving(0L to 20.0, 1_000L to 0.9)
        assertTrue("exactly 1,0 is not below 1: the wait starts over", m.update(1.0, true, 6_000))
        assertTrue(m.update(0.9, true, 7_000))
        assertTrue("10 s from 7 s, not from 1 s", m.update(0.5, true, 16_999))
        assertFalse(m.update(0.5, true, 17_000))
        // walking pace cancels too
        val w = moving(0L to 20.0, 1_000L to 0.2, 5_000L to 1.5, 9_000L to 0.2)
        assertTrue(w.update(0.2, true, 18_999))
        assertFalse(w.update(0.2, true, 19_000))
        // between 1 and 3 km/h a rider stays a rider, a parked one stays parked
        assertTrue(moving(0L to 20.0, 1_000L to 2.0, 60_000L to 2.0).moving)
        assertFalse(moving(0L to 0.0, 1_000L to 2.9).moving)
    }

    @Test fun noDataChangesNothing() {
        val m = moving(0L to 20.0, 1_000L to 0.0)
        assertTrue("no speed", m.update(null, true, 20_000))
        assertTrue("not streaming", m.update(0.0, false, 30_000))
        assertTrue("NaN", m.update(Double.NaN, true, 30_500))
        // the wait went on: the next slow sample after it parks
        assertFalse(m.update(0.0, true, 31_000))
        assertFalse("a stale fast value while silent does not ride", m.update(25.0, false, 32_000))
        m.reset()
        assertFalse(m.moving)
    }

    @Test fun viewForEveryState() {
        val C = Link.CONNECTED
        fun of(running: Boolean = true, link: Link = C, streaming: Boolean = true, streamed: Boolean = true, moving: Boolean = false, pin: ViewPin = ViewPin.AUTO) =
            DashViews.of(running, link, streaming, streamed, moving, pin)
        assertEquals("nothing running", DashView.IDLE, of(running = false, link = Link.IDLE, streaming = false))
        assertEquals("not running: a pin is not a ride", DashView.IDLE, of(running = false, pin = ViewPin.RIDE))
        assertEquals("connecting, nothing yet", DashView.IDLE, of(link = Link.CONNECTING, streaming = false, streamed = false))
        assertEquals("reconnecting before any frame", DashView.IDLE, of(link = Link.RECONNECTING, streaming = false, streamed = false))
        assertEquals("up, the dash silent from the start", DashView.SILENT, of(streaming = false, streamed = false))
        assertEquals("the dash went silent", DashView.SILENT, of(streaming = false))
        assertEquals("a reconnect mid-ride", DashView.SILENT, of(link = Link.RECONNECTING, streaming = false))
        assertEquals("silent beats a pin", DashView.SILENT, of(streaming = false, pin = ViewPin.RIDE))
        assertEquals(DashView.PARKED, of())
        assertEquals(DashView.RIDE, of(moving = true))
        assertEquals("pinned «Еду» at a stop", DashView.RIDE, of(pin = ViewPin.RIDE))
        assertEquals("pinned «Стою» while moving", DashView.PARKED, of(moving = true, pin = ViewPin.PARKED))
    }

    @Test fun thePinEndsWithTheRide() {
        ScooterRepo.setViewPin(ViewPin.PARKED)
        ScooterRepo.ended(EndCause.OWNER)
        assertEquals(ViewPin.AUTO, ScooterRepo.viewPin.value)
        assertFalse(ScooterRepo.moving.value)
        assertFalse(ScooterRepo.streamed.value)
    }

    @Test fun holdToDisconnect() {
        assertEquals(700L, DashViews.HOLD_MS)
        assertEquals("stopped: a tap", DashViews.Press.CONFIRM, DashViews.press(hold = false, heldMs = 0))
        assertEquals("moving: a tap only hints", DashViews.Press.HINT, DashViews.press(hold = true, heldMs = 120))
        assertEquals(DashViews.Press.HINT, DashViews.press(hold = true, heldMs = 699))
        assertEquals("held 0,7 s", DashViews.Press.CONFIRM, DashViews.press(hold = true, heldMs = 700))
        assertEquals(DashViews.Press.CONFIRM, DashViews.press(hold = true, heldMs = 2_000))
    }

    @Test fun tilesIgnoreTapsRightAfterAppearing() {
        assertEquals(600L, DashViews.TILE_GUARD_MS)
        assertFalse(DashViews.armed(shownAtMs = 1_000, nowMs = 1_000))
        assertFalse(DashViews.armed(shownAtMs = 1_000, nowMs = 1_599))
        assertTrue(DashViews.armed(shownAtMs = 1_000, nowMs = 1_600))
    }

    @Test fun firstLaunchOneStepAtATime() {
        val scan = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")
        val notify = "android.permission.POST_NOTIFICATIONS"
        val s = UiSettings()
        fun step(sys: SystemState, ui: UiSettings = s) = Setup.step(sys, ui, scan)
        assertEquals(SetupStep.PERMS, step(SystemState(missingPerms = scan + notify, bluetoothOn = false, ignoringBattery = false)))
        assertEquals(SetupStep.PERMS, step(SystemState(missingPerms = listOf("android.permission.BLUETOOTH_CONNECT"))))
        assertEquals("only notifications", SetupStep.NOTIF, step(SystemState(missingPerms = listOf(notify))))
        assertEquals("«Позже» on notifications", SetupStep.DONE, step(SystemState(missingPerms = listOf(notify)), s.copy(notifHintHidden = true)))
        assertEquals(SetupStep.BLUETOOTH, step(SystemState(bluetoothOn = false)))
        assertEquals(SetupStep.BLUETOOTH, step(SystemState(missingPerms = listOf(notify), bluetoothOn = false), s.copy(notifHintHidden = true)))
        assertEquals(SetupStep.BACKGROUND, step(SystemState(ignoringBattery = false)))
        assertEquals("a Samsung sleeps apps anyway", SetupStep.BACKGROUND, step(SystemState(samsung = true)))
        assertEquals("«Позже» on background", SetupStep.DONE, step(SystemState(samsung = true, ignoringBattery = false), s.copy(batteryHintHidden = true)))
        assertEquals(SetupStep.DONE, step(SystemState()))
    }
}
