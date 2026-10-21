"""Tests for tools/bird_frames.py: the controller frames, their status bytes included."""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))

import bird_frames as bf  # noqa: E402
import ride_logs as rl  # noqa: E402
import uart_frames as uf  # noqa: E402


def frame(h):
    return bytes.fromhex(h.replace(' ', ''))


# 20.10: the last BMS frame before it went silent (b5 0x22: the motor bit and the under-voltage flag) and the
# controller's last frames after it (byte 8 = 0x26)
LAST_BMS = frame('3A 18 20 10 01 22 04 2D 32 0C 7A 15 E2 CB 00 60 0F 05 6E 00 F8 03 0D 0A')
NO_BMS = frame('3A 17 00 0D 00 BD 00 7D 26 0B 00 60 00 39 36 01 01 60 02 0D 0A')


class FramesTest(unittest.TestCase):
    def test_status_bytes(self):
        self.assertTrue(uf.is_valid(LAST_BMS))
        b = bf.parse_bms(LAST_BMS, 1812402)
        self.assertEqual((b.b4, b.b5, b.b6, b.b18), (0x01, 0x22, 0x04, 110))
        self.assertEqual((b.motor, b.temp1, b.temp2, b.rem_mah, b.full_mah, b.pct), (1, 45, 50, 203, 3936, 5))
        self.assertAlmostEqual(b.v, 31.244, places=9)
        self.assertAlmostEqual(b.i, 7.659, places=9)
        self.assertTrue(b.b5 & bf.UNDER_VOLTAGE and b.b6 & bf.HOT_CHARGE and not b.b4 & bf.CHARGER)
        self.assertTrue(uf.is_valid(NO_BMS))
        m = bf.parse_motion(NO_BMS, 1817420)
        self.assertEqual((m.code, m.mode), (bf.CODE_NO_BMS, 3))
        self.assertAlmostEqual(m.trip_km, 9.6, places=9)
        self.assertAlmostEqual(m.speed_kmh, 12.5, places=9)
        self.assertAlmostEqual(m.avg_kmh, 18.9, places=9)
        self.assertAlmostEqual(m.odo_km, 1464.6, places=9)

    def test_old_callers_keep_working(self):
        # the status bytes come last with defaults: the 9- and 6-field forms still build
        b = bf.Bms(0, 38.0, 1.0, 0, 20, 20, 2000, 4000, 50)
        self.assertEqual((b.b4, b.b5, b.b6, b.b18), (0, 0, 0, 0))
        self.assertEqual(bf.Motion(0, 10.0, 9.0, 3, 1.0, 1450.0).code, 0)
        t, v, i, motor, temp1, temp2, rem, full, pct = bf.parse_bms(LAST_BMS, 7)[:9]   # the old fields, in their order
        self.assertEqual((t, motor, temp1, temp2, rem, full, pct), (7, 1, 45, 50, 203, 3936, 5))

    def test_flags_where_the_logs_have_them(self):
        uv, codes, ov_cut, b18 = {}, {}, {}, {}
        for name in rl.ALL + (rl.EVENING,):
            for kind, t, x in rl.events(name):
                if kind == 'bms':
                    if x.b5 & bf.UNDER_VOLTAGE:
                        uv.setdefault(name, []).append(t)
                    if x.b5 & bf.OVER_VOLTAGE_CUT:
                        ov_cut.setdefault(name, []).append(t)
                    b18.setdefault(name, set()).add(x.b18)
                elif kind == 'motion' and x.code:
                    codes.setdefault((name, x.code), []).append(t)
        # under-voltage: only 20.10, in the 2.4 s before the BMS went silent
        self.assertEqual(list(uv), [rl.TO_ZERO])
        self.assertTrue(1810000 <= min(uv[rl.TO_ZERO]) and max(uv[rl.TO_ZERO]) == rl.CUTOFF_T[rl.TO_ZERO])
        # the controller's code: 0x26 in the 5 s after it on 20.10, 0x21 parked on 15.10
        self.assertEqual(sorted(codes), sorted([(rl.TO_ZERO, 0x26), (rl.CUTOFF_HOT, 0x21)]))
        t26 = codes[(rl.TO_ZERO, 0x26)]
        self.assertTrue(1814000 <= min(t26) and max(t26) <= 1818000, (min(t26), max(t26)))
        t21 = codes[(rl.CUTOFF_HOT, 0x21)]
        self.assertTrue(112000 <= min(t21) and max(t21) <= 133000, (min(t21), max(t21)))
        # over-voltage protection: regen on a full pack, 19.10 (twice) and 20.10 at 135.6–139.1 s
        self.assertEqual(sorted(ov_cut), sorted([rl.CUTOFF, rl.EVENING, rl.TO_ZERO]))
        self.assertTrue(135000 <= min(ov_cut[rl.TO_ZERO]) and max(ov_cut[rl.TO_ZERO]) <= 139200)
        # byte 18: one value per log, growing with the charges (105 on 14.10 → 110 on 20.10)
        self.assertEqual(b18[rl.SOLO], {105})
        self.assertEqual(b18[rl.TO_ZERO], {110})


if __name__ == '__main__':
    unittest.main()
