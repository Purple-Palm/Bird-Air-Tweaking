"""Tests for tools/battery_advisor.py: low-battery / overheat warnings and the range estimate."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))

import app_odometer as ao  # noqa: E402
import battery_advisor as ba  # noqa: E402
import bird_frames as bf  # noqa: E402
import ride_logs as rl  # noqa: E402

LOW, HOT = 'battery_low', 'battery_hot'
W, C = ba.WARNING, ba.CRITICAL


def steps(name):
    return ba.replay(rl.log(name), events=rl.events(name))


class RealLogsTest(unittest.TestCase):
    def test_warnings_before_the_real_cutoffs(self):
        for name, t_cut in rl.CUTOFF_T.items():
            ft = ba.first_times(steps(name))
            self.assertGreaterEqual(t_cut - ft[(LOW, W)], 5 * 60000, name)    # «Заряд на исходе» ≥ 5 min before
        for name in (rl.CUTOFF_HOT, rl.CUTOFF):                              # both were hot (61 / 58 °C)
            self.assertGreaterEqual(rl.CUTOFF_T[name] - ba.first_times(steps(name))[(HOT, C)], 5 * 60000, name)
        # 20.10: the BMS's under-voltage cutoff (31.24 V at 7.7 A, BMS 5 %) solo on a cooler pack (≤ 50 °C): «Сейчас
        # отключится» leads it by 2.6 min / 0.7 km, «перегрета» is not needed and does not light
        ft = ba.first_times(steps(rl.TO_ZERO))
        crit = rl.CUTOFF_T[rl.TO_ZERO] - ft[(LOW, C)]
        self.assertTrue(2 * 60000 <= crit <= 3 * 60000, crit)
        self.assertNotIn((HOT, C), ft)
        # 19.10: the low-voltage cutoff (31.45 V at 8.6 A): «Сейчас отключится» ≥ 1 min before
        ft = ba.first_times(steps(rl.CUTOFF))
        self.assertGreaterEqual(rl.CUTOFF_T[rl.CUTOFF] - ft[(LOW, C)], 60000)
        self.assertLessEqual(ft[(LOW, W)], ft[(LOW, C)])
        # 15.10: the thermal cutoff (57 / 61 °C at 31.9 V): «перегрета» leads it by minutes
        ft = ba.first_times(steps(rl.CUTOFF_HOT))
        self.assertGreaterEqual(rl.CUTOFF_T[rl.CUTOFF_HOT] - ft[(HOT, C)], 15 * 60000)

    def test_end_of_traction_stops_at_a_new_power_on(self):
        # 20.10: after the BMS cut the pack off the dash was switched on again (the trip back to 0.0) and the ride
        # home went on in TAKEOFF at 9–10 A: the end of the ride the warnings were about is the cutoff
        lg, ev = rl.log(rl.TO_ZERO), rl.events(rl.TO_ZERO)
        st = steps(rl.TO_ZERO)
        ons = ba.power_on_times(ev)
        self.assertEqual(len(ons), 1)
        self.assertTrue(2162000 <= ons[0] <= 2163000, ons)              # boot C, the first frame after 350 s
        t, km = ba.end_of_traction(st, events=ev)
        self.assertEqual(t, rl.CUTOFF_T[rl.TO_ZERO])
        self.assertEqual(ba.end_of_traction(st), (t, km))                 # without the trip: the 350 s frame gap
        self.assertGreater(ba.end_of_traction(st, gap_ms=None)[0], 2250000)   # the TAKEOFF ride home, as it was
        ft = ba.first_times(st)
        km_crit = next(s[1] for s in st if s[0] == ft[(LOW, C)])
        self.assertAlmostEqual((t - ft[(LOW, C)]) / 60000, 2.6, delta=0.05)
        self.assertAlmostEqual(km - km_crit, 0.71, delta=0.05)
        # the other rides had one power-on: the same end as before
        for name in (rl.SOLO, rl.TWO_UP_EMPTY, rl.CUTOFF):
            st = steps(name)
            self.assertEqual(ba.end_of_traction(st, events=rl.events(name)), ba.end_of_traction(st, gap_ms=None), name)

    def test_full_discharges_warn_too(self):
        for name in (rl.SOLO, rl.TWO_UP_EMPTY):
            st = steps(name)
            end = ba.end_of_traction(st)[0]
            ft = ba.first_times(st)
            self.assertGreaterEqual(end - ft[(LOW, W)], 5 * 60000, name)
            self.assertLessEqual(end - ft[(LOW, W)], 20 * 60000, name)      # not from the start of the ride
        st = steps(rl.SOLO)                                               # solo it sagged to 31.1 V at the end
        self.assertGreaterEqual(ba.end_of_traction(st)[0] - ba.first_times(st)[(LOW, C)], 60000)

    def test_critical_only_below_its_threshold(self):
        # «Сейчас отключится» never while the voltage under load holds ≥ 31.6 V: not on 15.10 (≥ 31.70 V, the
        # thermal cutoff), 16.10 to 0 % (≥ 31.61 V) or the 19.10 evening ride (≥ 31.90 V); and when it fires,
        # the 2 s before it were all under load below 31.6 V
        for name in (rl.CUTOFF_HOT, rl.TWO_UP_EMPTY, rl.TWO_UP_HALF, rl.EVENING):
            self.assertNotIn((LOW, C), ba.first_times(steps(name)), name)
        for name in (rl.SOLO, rl.CUTOFF):
            st = steps(name)
            t = ba.first_times(st)[(LOW, C)]
            run = [s[4] for s in st if t - 2000 <= s[0] <= t]
            self.assertTrue(all(x.i >= 5 and x.v < 31.6 for x in run), name)

    def test_no_low_battery_alarm_above_half(self):
        # two-up at the 16 A limit 100 → 54 %: the voltage under load sags to 32.5 V, but not «на исходе»
        for name in (rl.TWO_UP_HALF, rl.CHARGE, rl.CAR_CHARGE):
            self.assertEqual([w for s in steps(name) for w in s[2]], [], name)
        # and in the rides, never while the BMS says ≥ 40 %
        for name in (rl.SOLO, rl.CUTOFF_HOT, rl.TWO_UP_EMPTY, rl.CUTOFF):
            self.assertEqual([s[4].pct for s in steps(name) if s[4].pct >= 40 and any(w.id == LOW for w in s[2])], [], name)

    def test_warnings_latch_until_charged(self):
        for name in rl.CUTOFF_T:
            st = steps(name)
            ft = ba.first_times(st)
            for lvl in (W, C):
                if (LOW, lvl) not in ft:
                    continue
                first = ft[(LOW, lvl)]
                self.assertTrue(all(any(w.id == LOW and (lvl == W or w.level == C) for w in s[2]) for s in st if s[0] >= first), name)

    def test_range_on_full_discharges(self):
        errs = []
        for name in (rl.SOLO, rl.CUTOFF_HOT, rl.TWO_UP_EMPTY, rl.CUTOFF):
            st = steps(name)
            e = ba.range_errors(st, st[-1][1])
            self.assertEqual(len(e), 7, name)
            errs += [p - a for a, p in e]
            if name == rl.CUTOFF:                                     # the cutoff at 1 %: within 0.6 km all the way
                self.assertLess(max(abs(p - a) for a, p in e), 0.6, e)
        self.assertLess(sum(abs(x) for x in errs) / len(errs), 0.5)
        self.assertLess(abs(sum(errs) / len(errs)), 0.2)

    def test_odometer_correction_is_not_distance(self):
        # «Задать» mid-ride (+8.3 km: the handlebar's 1451.1 → the logs' 1459.4; −8.3; 0.1): the range is unchanged
        lg, ev = rl.log(rl.CUTOFF), rl.events(rl.CUTOFF)
        base = steps(rl.CUTOFF)
        at = base[len(base) // 4][0]
        t0 = bf.started_ms(lg)
        for by in (8.3, -8.3, 0.1):
            adv, od, fix, out = ba.BatteryAdvisor(), ao.AppOdometer(), True, []
            for kind, t, x in ev:
                if kind == 'motion':
                    if fix and t >= at and od.state:
                        od = ao.AppOdometer(dict(od.state, total_km=od.total_km + by))
                        fix = False
                    od.update(x.trip_km, x.odo_km, t0 + t)
                    adv.on_distance(t, od.last_added_km)
                elif kind == 'bms':
                    adv.on_bms(t, x.v, x.i, x.temp1, x.temp2, x.rem_mah, x.full_mah)
                    out.append((t, adv.km, adv.range()))
            self.assertEqual(out, [(s[0], s[1], s[3]) for s in base], by)

    def test_range_units_and_basis(self):
        st = steps(rl.CUTOFF)
        r = st[-1][3]
        self.assertAlmostEqual(r.mi * ba.MILE_KM, r.km, places=9)
        self.assertEqual(r.basis, 'recent')
        self.assertEqual(r.km, 0.0)                                   # 1 % < reserve
        self.assertEqual(st[0][3].basis, 'default')
        self.assertTrue(16 < st[-1][3].wh_per_km < 22)               # two-up: 19.5 Wh/km


class UnitTest(unittest.TestCase):
    def feed(self, adv, t0, t1, v, i, step=300, **kw):
        t = t0
        while t < t1:
            adv.on_bms(t, v, i, **kw)
            t += step
        return t

    def test_sustain_and_levels(self):
        adv = ba.BatteryAdvisor()
        self.feed(adv, 0, 1800, 32.2, 9.0)              # V10 = 31.95 < 32.4, but only 1.8 s
        self.assertEqual(adv.warnings(), [])
        self.feed(adv, 1800, 1900, 32.2, 4.0)           # off load: the run breaks
        self.feed(adv, 1900, 3800, 32.2, 9.0)
        self.assertEqual(adv.warnings(), [])
        self.feed(adv, 3800, 6000, 32.2, 9.0)           # ≥ 2 s
        self.assertEqual([(w.id, w.level, w.text) for w in adv.warnings()], [(LOW, W, 'Заряд на исходе')])
        self.feed(adv, 6000, 9000, 31.7, 10.0)          # 31.7 V: still «на исходе»
        self.assertEqual(adv.warnings()[0].level, W)
        self.feed(adv, 9000, 12000, 31.5, 10.0)
        w = adv.warnings()[0]
        self.assertEqual((w.id, w.level, w.text, w.value), (LOW, C, 'Сейчас отключится', 31.5))
        self.feed(adv, 12000, 20000, 34.5, 0.1)         # rest: stays
        self.assertEqual(adv.warnings()[0].level, C)
        self.feed(adv, 20000, 45000, 32.8, 5.5)         # low load, V ≥ 32.5 for 20 s → back to «на исходе»
        self.assertEqual(adv.warnings()[0].level, W)
        self.feed(adv, 45000, 52000, 40.0, -1.5)        # charging: 7 s is not enough
        self.assertEqual(len(adv.warnings()), 1)
        self.feed(adv, 52000, 60000, 40.5, -1.5)
        self.assertEqual(adv.warnings(), [])

    def test_temperature_hysteresis(self):
        adv = ba.BatteryAdvisor()
        levels = []
        for t, c in enumerate((49, 50, 49, 48, 55, 54, 53, 52, 51, 47)):
            adv.on_bms(t * 300, 38.0, 0.1, temp1=c - 3, temp2=c)
            levels.append(adv.warnings()[0].level if adv.warnings() else None)
        self.assertEqual(levels, [None, W, W, None, C, C, W, W, W, None])
        adv.on_bms(9000, 38.0, 0.1, temp1=200, temp2=None)        # a broken sensor value is ignored
        self.assertEqual(adv.tmax, 47)
        adv.on_bms(9300, 38.0, 0.1, temp1=57, temp2=61)             # where 15.10 went dark
        self.assertEqual(adv.warnings()[0].text, 'Батарея перегрета — скоро отключится, сбавь газ')

    def test_range_default_session_recent(self):
        adv = ba.BatteryAdvisor()
        self.assertIsNone(adv.range())
        adv.on_bms(0, 38.0, 0.06, rem_mah=2000, full_mah=4000)
        r = adv.range()
        self.assertEqual(r.basis, 'default')
        self.assertAlmostEqual(r.wh_left, (2000 - 120) * 0.031)
        self.assertAlmostEqual(r.km, r.wh_left / 15.0)
        # 20 Wh/km: 720 W at 36 km/h, 100 m per 9.9 s; a 3A 17 frame with each BMS frame
        t = 0
        adv.on_distance(t, 0.0)
        self.assertEqual(adv.km, 0.0)
        for _ in range(30):
            for k in range(33):
                t += 300
                adv.on_bms(t, 36.0, 20.0, rem_mah=2000, full_mah=4000)
                adv.on_distance(t, 0.1 if k == 32 else 0.0)
        r = adv.range()
        self.assertEqual(r.basis, 'recent')
        self.assertAlmostEqual(r.wh_per_km, 19.8, delta=0.3)
        self.assertAlmostEqual(adv.km, 3.0, places=9)
        # 30 s without frames, then the trip counter is 0.5 km on: ridden without the energy measured
        t += 30000
        adv.on_distance(t, 0.5)
        adv.on_bms(t, 36.0, 20.0, rem_mah=2000, full_mah=4000)
        self.assertAlmostEqual(adv.km, 3.0, places=9)
        self.assertAlmostEqual(adv.range().wh_per_km, r.wh_per_km, places=9)
        adv.on_distance(t + 60, 0.1)                              # continuous again
        self.assertAlmostEqual(adv.km, 3.1, places=9)
        adv2 = ba.BatteryAdvisor()
        adv2.on_bms(0, 36.0, 20.0, rem_mah=100, full_mah=4000)
        self.assertEqual(adv2.range().km, 0.0)                    # below the reserve


if __name__ == '__main__':
    unittest.main()
