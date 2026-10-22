import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
import decode_adv  # noqa: E402


class DecodeAdvTest(unittest.TestCase):
    def test_layout(self):
        # version=0x0041, battery=0x64=100, mode=1→NORMAL, distance=0x00002710=10000
        r = decode_adv.decode(bytes([0x41, 0x00, 0x64, 0x01, 0x10, 0x27, 0x00, 0x00]))
        self.assertEqual(r, {'version': 65, 'battery': 100, 'mode_raw': 1, 'mode': 'NORMAL', 'distance_raw': 10000})

    def test_mode_mapping(self):
        self.assertEqual(decode_adv.decode(bytes([0, 0, 50, 0, 0, 0, 0, 0]))['mode'], 'POWER_SAVING')
        self.assertEqual(decode_adv.decode(bytes([0, 0, 50, 2, 0, 0, 0, 0]))['mode'], 'SPORT')

    def test_too_short(self):
        with self.assertRaises(ValueError):
            decode_adv.decode(bytes([1, 2, 3]))

    def test_cli_skips_company_id(self):
        self.assertEqual(decode_adv.main(['4C 00 41 00 64 01 10 27 00 00']), 0)          # default --skip 2
        self.assertEqual(decode_adv.main(['--skip', '0', '41 00 64 01 10 27 00 00']), 0)

    def test_cli_bad_hex(self):
        self.assertEqual(decode_adv.main(['ZZ']), 1)


if __name__ == '__main__':
    unittest.main()
