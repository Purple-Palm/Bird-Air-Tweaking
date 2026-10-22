"""Tests for tools/fast_power.py: causal battery current/power from the voltage sag."""
import math
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))

import bird_frames as bf  # noqa: E402
import fast_power as fp  # noqa: E402
import ride_logs as rl  # noqa: E402
import uart_frames as uf  # noqa: E402


def data(name):
    return fp.read_log(None, rl.events(name))


class SyntheticTest(unittest.TestCase):
    def test_tracks_steps_ahead_of_the_bms(self):
        frames = fp.synthetic_frames()
        est = fp.FastPower()
        rows = []
        for t, v, i, m in frames:
            rows.append((t, v, i, m) + est.update(t, v, i, m))
        self.assertAlmostEqual(est.r, fp.SYN_R, delta=0.03)
        # 1.5 s after each step the estimate is near the true current, while the BMS value can still be the old one
        for t0, i_true in fp.SYN_STEPS[1:]:
            r = next(x for x in rows if x[0] >= t0 + 1500)
            if abs(r[1] * i_true) >= fp.PARAMS['deadband_w']:
                self.assertAlmostEqual(r[4], i_true, delta=1.0, msg=f'step at {t0}')
        # right after the step to 10 A the first frame already shows it, the BMS only a sliver of it
        r = next(x for x in rows if x[0] >= 5000)
        self.assertGreater(r[4], 8.0)
        self.assertLess(r[2], 2.0)
        # power = V · I, the sign of traction / regen
        for r in rows:
            self.assertAlmostEqual(r[5], r[1] * r[4], places=9)
        self.assertTrue(any(r[4] < -3 for r in rows))

    def test_misaligned_bms_windows_keep_r_sane(self):
        est = fp.FastPower()
        for t, v, i, m in fp.synthetic_frames(aligned=False):
            est.update(t, v, i, m)
        self.assertGreater(est.r, 0.2)
        self.assertLess(est.r, 0.35)

    def test_gap_and_reset(self):
        est = fp.FastPower()
        est.update(0, 40.0, 0.06)
        est.update(300, 37.3, 0.06)            # sag: traction before the BMS knows
        self.assertGreater(est.update(600, 37.3, 0.06)[0], 5)
        r = est.r
        i, _ = est.update(600 + fp.PARAMS['gap_ms'] + 1, 37.3, 0.06)   # gap → new stream from this frame
        self.assertEqual(i, 0.0)                # OCV from V + R·I_bms: 37.3 + 0.25·0.06 → 2.2 W < deadband
        self.assertEqual(est.r, r)              # R is a battery property, kept
        est.update(100, 30.0, 10.0)             # time went back → reset too
        self.assertEqual(est.anchors, 0)
        est.reset(full=True)
        self.assertEqual(est.r, fp.PARAMS['r0'])

    def test_deadband_and_clamp(self):
        est = fp.FastPower()
        self.assertEqual(est.update(0, 40.0, 0.1), (0.0, 0.0))          # 4 W
        est = fp.FastPower(deadband_w=0.0)
        self.assertAlmostEqual(est.update(0, 40.0, 0.1)[0], 0.1, places=9)
        est = fp.FastPower()
        est.update(0, 40.0, 0.0)
        i, p = est.update(300, 30.0, 0.0)       # 10 V sag at R = 0.25 → 40 A, clamped
        self.assertEqual(i, fp.PARAMS['i_max'])
        self.assertAlmostEqual(p, 30.0 * fp.PARAMS['i_max'])
        with self.assertRaises(ValueError):
            fp.FastPower(nope=1)


class RealLogsTest(unittest.TestCase):
    def test_solo_ride(self):
        r = fp.validate(data(rl.SOLO))
        self.assertGreaterEqual(r['corr_best'], 0.9)
        self.assertGreaterEqual(r['corr0'], 0.9)
        self.assertGreaterEqual(r['lag_best_ms'], 300)              # the BMS current comes later
        self.assertGreaterEqual(r['lead_ms'], 300)
        self.assertGreaterEqual(r['up5_lead_ms'], 300)
        self.assertLess(r['rms_shift'], r['rms_bms_noshift'])       # closer to the truth than the stale BMS value
        self.assertLess(r['upd_rms'], 0.5 * r['upd_rms_bms'])
        self.assertLess(abs(r['wh_out_fast'] / r['wh_out_bms'] - 1), 0.02)
        self.assertLess(abs((r['wh_out_fast'] - r['wh_in_fast']) / (r['wh_out_bms'] - r['wh_in_bms']) - 1), 0.02)
        self.assertLessEqual(r['i_fast_max'], fp.PARAMS['i_max'])
        self.assertLess(r['still_p_max'], 10.0)                    # no fake traction at standstill
        self.assertTrue(0.2 < r['r_end'] < 0.35)

    def test_two_up_rides(self):
        for name in (rl.CUTOFF_HOT, rl.TWO_UP_HALF, rl.TWO_UP_EMPTY, rl.CUTOFF):
            r = fp.validate(data(name))
            self.assertGreaterEqual(r['corr_best'], 0.95, name)
            self.assertGreaterEqual(r['lead_ms'], 300, name)
            self.assertLess(abs(r['wh_out_fast'] / r['wh_out_bms'] - 1), 0.02, name)
            self.assertLess(abs(r['limit_bias']), 0.3, name)         # at the 16 A limit
            self.assertLessEqual(r['i_fast_max'], fp.PARAMS['i_max'], name)

    def test_charging_has_no_traction(self):
        for name in (rl.CHARGE, rl.CAR_CHARGE):
            d = data(name)
            rows, _ = fp.run(d)
            self.assertGreater(len(rows), 900, name)
            self.assertEqual([r for r in rows if r[4] > 0 or r[5] > 0], [], name)
            self.assertLess(min(r[4] for r in rows), -1.0, name)      # the charge current is there
            r = fp.validate(d)
            self.assertLess(abs(r['wh_in_fast'] / r['wh_in_bms'] - 1), 0.03, name)

    def test_read_log_resets_on_reconnect(self):
        d = data(rl.CUTOFF_HOT)                      # three connections
        self.assertEqual(len(d['resets']), 3)
        self.assertEqual(d['resets'][0], 0)
        rows, est = fp.run(d)
        self.assertEqual(len(rows), len(d['bms']))

    def test_bms_frame_values(self):
        f = bytes.fromhex('3A 18 20 10 03 00 00 18 18 AE 9B D2 FF D7 0C 0B 11 4B 69 00 48 05 0D 0A'.replace(' ', ''))
        self.assertTrue(uf.is_valid(f))
        b = bf.parse_bms(f, 7)
        self.assertEqual((b.t, b.motor, b.temp1, b.temp2, b.rem_mah, b.full_mah, b.pct), (7, 0, 24, 24, 3287, 4363, 75))
        self.assertAlmostEqual(b.v, 39.854, places=9)
        self.assertAlmostEqual(b.i, 0.046, places=9)
        self.assertIsNone(bf.parse_motion(f, 7))
        m = bf.parse_motion(bytes.fromhex('3A 17 00 0D 00 00 00 00 00 0B 00 00 00 37 A8 01 01 10 01 0D 0A'.replace(' ', '')), 1)
        self.assertEqual((m.mode, m.speed_kmh, m.trip_km), (3, 0.0, 0.0))
        self.assertTrue(math.isclose(m.odo_km, 1424.8))


if __name__ == '__main__':
    unittest.main()
