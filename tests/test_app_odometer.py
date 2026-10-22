"""Tests for tools/app_odometer.py: the app's own odometer that survives the scooter losing progress."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))

import app_odometer as ao  # noqa: E402
import bird_frames as bf  # noqa: E402
import ride_logs as rl  # noqa: E402

H = 3600 * 1000


class RealLogsTest(unittest.TestCase):
    def test_logs_in_sequence(self):
        logs = sorted((rl.log(n) for n in rl.ALL), key=lambda lg: lg['startedAt'])
        od, rows = ao.replay(logs, events=lambda lg: rl.events(os.path.basename(lg['_files'][0])))
        # the rides: 8.5 + 6.3 + 2.9 + 0 (charging) + 4.8 + 6.0 (ridden in the trimmed part of 20261016-205717,
        # its first frame already says trip 6.0) + 6.1 + 9.9 (20.10: 9.6 to the BMS cutoff, 0.3 after the reboot)
        self.assertEqual([r[1] for r in rows], [8.5, 6.3, 2.9, 0.0, 4.8, 6.0, 6.1, 9.9])
        self.assertAlmostEqual(od.total_km, 1424.8 + 44.5, places=6)
        # the scooter's own odometer lost 8.3 km by 19.10 (restarted at 1434.2 twice) and the 9.6 km of 20.10
        # (1464.6 at the cutoff, 1455.0 after the reboot)
        self.assertAlmostEqual(rows[-1][3], 1455.3, places=6)
        st = od.state
        # 20.10 ends on the charger: the dash switched on again at 2792.9 s (trip 0.0, odo 1455.3), nothing ridden
        self.assertEqual((st['base_km'], st['trip_km']), (1455.3, 0.0))
        # the app restarted between the logs: the state goes out and comes back in
        ev = lambda lg: rl.events(os.path.basename(lg['_files'][0]))  # noqa: E731
        a, _ = ao.replay(logs[:3], events=ev)
        b, _ = ao.replay(logs[3:], state=a.state, events=ev)
        self.assertEqual(b.state, st)

    def test_reconnects_inside_a_log_do_not_double_count(self):
        ev = rl.events(rl.CUTOFF_HOT)                 # two reconnects mid-ride
        od = ao.AppOdometer()
        t0 = bf.started_ms(rl.log(rl.CUTOFF_HOT))
        for kind, t, x in ev:
            if kind == 'reset':
                od = ao.AppOdometer(od.state)        # as if the app restarted at each reconnect
            elif kind == 'motion':
                od.update(x.trip_km, x.odo_km, t0 + t)
        self.assertAlmostEqual(od.total_km, 1439.6, places=6)


class UnitTest(unittest.TestCase):
    def test_seed_and_session(self):
        od = ao.AppOdometer()
        self.assertIsNone(od.state)
        self.assertEqual(od.update(0.0, 100.0, 0), 100.0)
        self.assertEqual(od.last_added_km, 0.0)                           # the seed adds nothing
        self.assertEqual(od.update(1.2, 101.2, H // 10), 101.2)
        self.assertEqual(od.last_added_km, 1.2)
        self.assertEqual(od.update(1.2, 101.2, H // 10 + 5000), 101.2)    # same frame again
        self.assertEqual(od.last_added_km, 0.0)
        # the trip went back 1 s later: a new power-on cannot have 1.1 km on it yet — a glitch, ignored
        self.assertEqual(od.update(1.1, 101.1, H // 10 + 6000), 101.2)
        self.assertEqual(od.last_added_km, 0.0)
        self.assertEqual(od.state['trip_km'], 1.2)
        # a real reset to 0.1 km half a minute later counts
        self.assertEqual(od.update(0.1, 101.3, H // 10 + 36000), 101.3)
        self.assertEqual(od.last_added_km, 0.1)

    def test_power_cycle_and_lost_progress(self):
        od = ao.AppOdometer()
        od.update(0.0, 1433.3, 0)
        od.update(6.3, 1439.6, H)                 # cutoff, the scooter loses 5.4 km
        self.assertEqual(od.update(0.0, 1434.2, 20 * H), 1439.6)
        self.assertEqual(od.update(2.9, 1437.1, 21 * H), 1442.5)
        self.assertEqual(od.update(0.0, 1434.2, 25 * H), 1442.5)          # same base again, trip reset
        self.assertEqual(od.update(4.8, 1439.0, 26 * H), 1447.3)
        # the app connects late: 6 km already ridden in this power-on
        self.assertEqual(od.update(6.0, 1445.0, 30 * H), 1453.3)
        self.assertEqual(od.state, {'total_km': 1453.3, 'base_km': 1439.0, 'trip_km': 6.0, 'wall_ms': 30 * H})

    def test_odometer_glitch_mid_ride_is_not_a_new_session(self):
        od = ao.AppOdometer()
        od.update(5.0, 105.0, 0)
        # 10 s later the odometer says something else but the trip went on: 5 km cannot be ridden in 10 s
        self.assertEqual(od.update(5.1, 205.1, 10000), 105.1)

    def test_cap_by_time(self):
        od = ao.AppOdometer()
        od.update(0.0, 50.0, 0)
        # 30 km of trip in one minute is impossible: at most 45 km/h · 1 min + 0.3 km = 1.05 km → 1.1 (ceil)
        self.assertAlmostEqual(od.update(30.0, 80.0, 60000), 51.1, places=6)
        # without wall clocks there is no cap
        od = ao.AppOdometer()
        od.update(0.0, 50.0)
        self.assertEqual(od.update(30.0, 80.0), 80.0)

    def test_state_round_trip(self):
        od = ao.AppOdometer()
        od.update(0.3, 10.3, 1000)
        od2 = ao.AppOdometer(dict(od.state))
        self.assertEqual(od2.update(0.5, 10.5, 2000), 10.5)
        self.assertEqual(ao.tenths(1439.6000000000001), 14396)
        with self.assertRaises(ValueError):
            ao.AppOdometer(nope=1)


if __name__ == '__main__':
    unittest.main()
