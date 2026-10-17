"""GPS speed entries ({k: 'gps', v: m/s, a: accuracy m}) survive loading, cleaning and the analyzer."""
import contextlib
import io
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools'))

import analyze_log  # noqa: E402
import birdlog as bl  # noqa: E402

REAL = os.path.join(ROOT, 'logs', '20261014-175429-log.json')


class GpsEntries(unittest.TestCase):
    def with_gps(self):
        o = json.load(open(REAL, encoding='utf-8'))
        o['entries'] += [{'t': 5000 + i * 1000, 'k': 'gps', 'v': 3.0 + i, 'a': 5} for i in range(5)]
        return o

    def test_load_keeps_gps_and_clean_entry_keeps_v_a(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'x-log.json')
            json.dump(self.with_gps(), open(p, 'w', encoding='utf-8'))
            log = bl.load_file(p)
        gps = [e for e in log['entries'] if e['k'] == 'gps']
        self.assertEqual([(e['v'], e['a']) for e in gps], [(3.0 + i, 5) for i in range(5)])
        self.assertEqual(bl.clean_entry(gps[0]), {'t': 5000, 'k': 'gps', 'v': 3.0, 'a': 5})

    def test_analyzer_runs_on_a_log_with_gps(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, 'x-log.json')
            json.dump(self.with_gps(), open(p, 'w', encoding='utf-8'))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                analyze_log.analyze([p], os.path.join(d, 'candidate.json'), stream=out)
            self.assertTrue(os.path.exists(os.path.join(d, 'candidate.json')))


if __name__ == '__main__':
    unittest.main()
