package io.github.purplepalm.birdair.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppOdometerTest {
    private val h = 3_600_000L

    /** The logs in order through one odometer: total = sum of the rides, whatever the scooter's own odometer did. */
    @Test fun logsInSequence() {
        var od = AppOdometer()
        val added = ArrayList<Double>()
        var lastScooter = 0.0
        for (path in RideLogs.ALL) {
            val ride = RideLogs.ride(path)
            od = AppOdometer(od.state)                           // the app restarted between the rides
            var before: Double? = od.totalKm
            for (e in ride.events) if (e is ScooterEvent.Motion) {
                od.update(e.f.tripKm, e.f.odometerKm, ride.startedAtMs + e.t)
                if (before == null) before = od.totalKm
                lastScooter = e.f.odometerKm
            }
            added.add(Math.round((od.totalKm!! - before!!) * 10) / 10.0)
        }
        // 8.5 + 6.3 + 2.9 + 0 (car charging) + 4.8 + 6.0 (the trimmed part of the charging log) + 6.1 + 9.9 (20.10: 9.6 to
        // the BMS cutoff, 0.3 after the reboot)
        assertEquals(listOf(8.5, 6.3, 2.9, 0.0, 4.8, 6.0, 6.1, 9.9), added)
        assertEquals(1424.8 + 44.5, od.totalKm!!, 1e-9)
        assertEquals(1455.3, lastScooter, 1e-9)                  // the scooter lost 8.3 km by 19.10 and 20.10's 9.6
        assertEquals(AppOdometer.State(1469.3, 1455.0, 0.3, od.state!!.wallMs), od.state)
        if (Py.available) assertEquals(RideLogs.python.getValue(RideLogs.CUTOFF)["odo"]!!.jsonPrimitive.double, 1451.1, 1e-9)
    }

    @Test fun reconnectsAreNotCountedTwice() {
        val ride = RideLogs.ride(RideLogs.CUTOFF_HOT)
        var od = AppOdometer()
        for (e in ride.events) when (e) {
            is ScooterEvent.Reset -> od = AppOdometer(od.state)
            is ScooterEvent.Motion -> od.update(e.f.tripKm, e.f.odometerKm, ride.startedAtMs + e.t)
            else -> {}
        }
        assertEquals(1439.6, od.totalKm!!, 1e-9)
    }

    @Test fun sessions() {
        val od = AppOdometer()
        assertNull(od.state)
        assertEquals(100.0, od.update(0.0, 100.0, 0))
        assertEquals(0.0, od.lastAddedKm)                              // the seed adds nothing
        assertEquals(101.2, od.update(1.2, 101.2, h / 10))
        assertEquals(1.2, od.lastAddedKm)
        assertEquals(101.2, od.update(1.1, 101.1, h / 10 + 1000))     // a glitch 1 s later: ignored
        assertEquals(0.0, od.lastAddedKm)
        assertEquals(101.3, od.update(0.1, 101.3, h / 10 + 31000))    // a real reset half a minute later
        assertEquals(0.1, od.lastAddedKm)
        // the cutoff: the scooter forgets 5.4 km, then the app connects late into a power-on with 6 km on it
        val o2 = AppOdometer()
        o2.update(0.0, 1433.3, 0)
        o2.update(6.3, 1439.6, h)
        assertEquals(1439.6, o2.update(0.0, 1434.2, 20 * h))
        assertEquals(1442.5, o2.update(2.9, 1437.1, 21 * h))
        assertEquals(1442.5, o2.update(0.0, 1434.2, 25 * h))
        assertEquals(1447.3, o2.update(4.8, 1439.0, 26 * h))
        assertEquals(1453.3, o2.update(6.0, 1445.0, 30 * h))
        assertEquals(AppOdometer.State(1453.3, 1439.0, 6.0, 30 * h), o2.state)
        // the odometer glitches mid-ride: not a new session
        val o3 = AppOdometer()
        o3.update(5.0, 105.0, 0)
        assertEquals(105.1, o3.update(5.1, 205.1, 10_000))
        // 30 km in a minute: capped at 45 km/h · 1 min + 0.3 km
        val o4 = AppOdometer()
        o4.update(0.0, 50.0, 0)
        assertEquals(51.1, o4.update(30.0, 80.0, 60_000)!!, 1e-9)
        val o5 = AppOdometer()
        o5.update(0.0, 50.0)
        assertEquals(80.0, o5.update(30.0, 80.0))
        // the state as JSON, as the app keeps it
        val js = Json.encodeToString(AppOdometer.State.serializer(), o2.state!!)
        assertEquals(o2.state, Json.decodeFromString(AppOdometer.State.serializer(), js))
        assertEquals(14396L, AppOdometer.tenths(1439.6000000000001))
    }
}
