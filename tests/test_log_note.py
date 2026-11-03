"""The owner's description of a log: top-level "note" (logs/README.md) in tools/birdlog.py."""
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), 'tools'))

import birdlog as bl  # noqa: E402


class NoteTest(unittest.TestCase):
    def write(self, d, name, obj):
        p = os.path.join(d, name)
        with open(p, 'w', encoding='utf-8') as fh:
            json.dump(obj, fh, ensure_ascii=False)
        return p

    def test_kept_cleaned_and_dropped(self):
        with tempfile.TemporaryDirectory() as d:
            lg = bl.new_log(started_at='2026-10-20T10:00:00.000Z', note='  Вдвоём до отсечки, FLY \n')
            self.assertEqual(list(lg)[:6], ['format', 'version', 'startedAt', 'device', 'demo', 'trimmed'])
            self.assertEqual(lg['note'], 'Вдвоём до отсечки, FLY')
            lg['entries'].append({'t': 0, 'k': 'sys', 'n': 'x'})
            p = os.path.join(d, 'a.json.gz')
            bl.save_log(lg, p)
            back = bl.load_file(p)
            self.assertEqual(back['note'], 'Вдвоём до отсечки, FLY')
            self.assertEqual(bl.load_logs([p])[0]['note'], 'Вдвоём до отсечки, FLY')
            self.assertNotIn('note', bl.new_log(note='   '))
            for bad in (None, '', 5, {'x': 1}):
                o = dict(lg, note=bad)
                o['entries'] = [{'t': 0, 'k': 'sys', 'n': 'x'}]
                self.assertNotIn('note', bl.load_file(self.write(d, 'b.json', o)))

    def test_parts_keep_it(self):
        with tempfile.TemporaryDirectory() as d:
            paths = []
            for k in (1, 2):
                o = bl.new_log(started_at='2026-10-20T10:00:00.000Z', note='газ и тормоз')
                o['part'] = {'id': 'p', 'index': k, 'count': 2}
                o['entries'] = [{'t': k, 'k': 'mk', 'n': 'Газ'}]
                paths.append(self.write(d, f'x-part{k}of2.json', o))
            self.assertEqual(bl.load_logs(paths)[0]['note'], 'газ и тормоз')

    def test_anonymize_hides_the_name_in_it(self):
        lg = bl.new_log(device={'name': 'BirdAir 12345678', 'id': 'AA:BB'}, note='самокат BirdAir 12345678, серийник 12345678')
        bl.anonymize(lg)
        self.assertEqual(lg['note'], 'самокат scooter, серийник scooter')
        self.assertEqual(bl.slug('Вдвоём до отсечки'), 'vdvoem-do-otsechki')


if __name__ == '__main__':
    unittest.main()
