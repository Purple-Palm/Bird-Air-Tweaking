"""Tests for tools/: log format, analyzer, token check.  python3 -m unittest discover -s tests"""
import contextlib
import gzip
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools'))
sys.path.insert(0, HERE)

import analyze_log  # noqa: E402
import birdlog as bl  # noqa: E402
import check_secrets  # noqa: E402
import fixtures  # noqa: E402


def run_analyze(paths, out=None):
    buf = io.StringIO()
    res = analyze_log.analyze(paths, out, write_candidate=bool(out), stream=buf)
    return res, buf.getvalue()


class CrcTest(unittest.TestCase):
    CHECK = {
        'CRC-8': 0xF4, 'CRC-8/ITU': 0xA1, 'CRC-8/MAXIM': 0xA1, 'CRC-8/ROHC': 0xD0, 'CRC-8/DVB-S2': 0xBC,
        'CRC-8/CDMA2000': 0xDA, 'CRC-8/WCDMA': 0x25, 'CRC-8/SAE-J1850': 0x4B, 'CRC-8/AUTOSAR': 0xDF,
        'CRC-8/BLUETOOTH': 0x26, 'CRC-16/MODBUS': 0x4B37, 'CRC-16/ARC': 0xBB3D, 'CRC-16/USB': 0xB4C8,
        'CRC-16/CCITT-FALSE': 0x29B1, 'CRC-16/XMODEM': 0x31C3, 'CRC-16/KERMIT': 0x2189, 'CRC-16/X-25': 0x906E,
        'CRC-16/MCRF4XX': 0x6F91, 'CRC-16/GENIBUS': 0xD64E,
    }

    def test_check_values(self):
        algos = {**analyze_log.CRC8, **analyze_log.CRC16}
        for name, want in self.CHECK.items():
            self.assertEqual(algos[name](b'123456789'), want, name)

    def test_finds_modbus_crc(self):
        crc = analyze_log.CRC16['CRC-16/MODBUS']
        pkts = []
        for i in range(30):
            body = bytes([0x01, 0x03, i, (i * 7) & 0xFF, 0x10])
            c = crc(body)
            pkts.append(body + bytes([c & 0xFF, c >> 8]))
        best = analyze_log.find_checksums(pkts)[0]
        self.assertEqual((best['algo'], best['start'], best['order']), ('CRC-16/MODBUS', 0, 'le'))

    def test_ninebot_style_sum(self):
        pkts = []
        for i in range(30):
            body = bytes([0x55, 0xAA, 0x04, 0x21, 0x01, i, 0x00])
            s = ~sum(body[2:]) & 0xFFFF
            pkts.append(body + bytes([s & 0xFF, s >> 8]))
        best = analyze_log.find_checksums(pkts)[0]
        self.assertEqual((best['algo'], best['start'], best['order']), ('NOT-SUM16', 2, 'le'))

    def test_identical_packets_prove_nothing(self):
        self.assertEqual(analyze_log.find_checksums([bytes([1, 2, 3, 4, 5])] * 20), [])


class DemoAnalysisTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.log = os.path.join(cls.tmp.name, '20261014-190000-demo.json')
        bl.save_log(fixtures.demo_log(200), cls.log)
        cls.out = os.path.join(cls.tmp.name, 'candidate.json')
        cls.res, cls.report = run_analyze([cls.log], cls.out)
        with open(cls.out, encoding='utf-8') as fh:
            cls.dec = json.load(fh)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def field(self, key):
        found = [f for f in self.dec['fields'] if f['key'] == key]
        self.assertTrue(found, f'{key} not in candidate: {self.dec["fields"]}')
        return found[0]

    def test_groups_and_checksums(self):
        pats = {g.pattern: g for g in self.res['groups']}
        self.assertIn('5A A5 0B 25', pats)
        self.assertIn('5A A5 08 31', pats)
        for p in ('5A A5 0B 25', '5A A5 08 31'):
            cs = pats[p].checksum
            self.assertEqual((cs['algo'], cs['start']), ('XOR', 2))

    def test_finds_demo_fields(self):
        want = {
            'battery_pct': ('5A A5 0B 25', 4, 'u8', 1),
            'voltage': ('5A A5 0B 25', 5, 'u16le', 0.01),
            'current': ('5A A5 0B 25', 7, 's16le', 0.01),
            'speed_kmh': ('5A A5 0B 25', 9, 'u16le', 0.1),
            'mode': ('5A A5 0B 25', 12, 'u8', 1),
            'odometer_km': ('5A A5 08 31', 4, 'u32le', 0.01),
        }
        for key, (match, off, typ, scale) in want.items():
            f = self.field(key)
            self.assertEqual((f['match'], f['offset'], f['type']), (match, off, typ), key)
            self.assertAlmostEqual(f['scale'], scale, msg=key)
            self.assertEqual(f['uuid'], fixtures.DEMO_CHR)

    def test_candidate_is_a_page_decoder(self):
        self.assertEqual(self.dec['format'], 'birdair-lab-decoder')
        self.assertEqual(self.dec['version'], 2)          # decoders/current.json is v1
        self.assertIn('updatedAt', self.dec)
        self.assertFalse(self.dec['json'])

    def test_report_mentions_markers(self):
        self.assertIn('«Газ»', self.report)
        self.assertIn('XOR', self.report)


class StreamTextTest(unittest.TestCase):
    def test_text_is_joined_across_packets_and_standard_services_are_not_fields(self):
        lg = bl.new_log()
        text = b'\x80\xf8x\x00AT+SETNAME=BirdAir-X1\r\nAT+RESET\r\n'
        u = bl.norm_uuid('ffe1')
        lg['entries'] = [{'t': i, 'k': 'rx', 's': bl.norm_uuid('ffe0'), 'u': u, 'h': bl.hex_of(text[i:i + 20])} for i in range(0, len(text), 20)]
        lg['entries'] += [{'t': 100 + i, 'k': 'rd', 's': bl.norm_uuid('1800'), 'u': bl.norm_uuid('2a04'), 'h': '14 00 28 00 00 00 E8 03'} for i in range(6)]
        lg['_files'] = ['x.json']
        texts = analyze_log.stream_texts([lg])
        self.assertEqual(dict(texts[('rx', u)]), {'AT+SETNAME=BirdAir-X1': 1, 'AT+RESET': 1})
        self.assertEqual([g for g in analyze_log.build_groups([lg]) if g.s == bl.norm_uuid('1800')], [])


class LogFilesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def test_gzip_roundtrip(self):
        lg = fixtures.demo_log(5)
        p = os.path.join(self.d, 'a.json.gz')
        bl.save_log(lg, p)
        with open(p, 'rb') as fh:
            self.assertEqual(fh.read(2), b'\x1f\x8b')
        back = bl.load_file(p)
        self.assertEqual(len(back['entries']), len(lg['entries']))

    def test_parts_are_merged(self):
        lg = fixtures.demo_log(30)
        es = lg['entries']
        third = len(es) // 3
        chunks = [es[:third], es[third:2 * third], es[2 * third:]]
        for i, ch in enumerate(chunks, 1):
            part = dict(lg, entries=ch, part={'id': '20261014-190000-demo', 'index': i, 'count': 3})
            ext = '.json.gz' if i % 2 else '.json'
            bl.save_log(part, os.path.join(self.d, f'20261014-190000-demo-part{i}of3{ext}'))
        logs = bl.load_logs([self.d])
        self.assertEqual(len(logs), 1)
        self.assertEqual(len(logs[0]['entries']), len(es))
        self.assertEqual(logs[0]['_missing'], [])

    def test_missing_part_is_reported(self):
        lg = fixtures.demo_log(10)
        bl.save_log(dict(lg, part={'id': 'x', 'index': 1, 'count': 2}), os.path.join(self.d, 'x-part1of2.json'))
        notes = []
        logs = bl.load_logs([self.d], notes)
        self.assertEqual(logs[0]['_missing'], [2])
        self.assertTrue(any('не хватает частей' in n for n in notes))

    def test_resent_log_keeps_longest(self):
        lg = fixtures.demo_log(20)
        short = dict(lg, entries=lg['entries'][:50])
        bl.save_log(short, os.path.join(self.d, '20261014-190000-log.json'))
        bl.save_log(lg, os.path.join(self.d, '20261014-190000-log-2.json'))
        logs = bl.load_logs([self.d])
        self.assertEqual(len(logs), 1)
        self.assertEqual(len(logs[0]['entries']), len(lg['entries']))

    def test_rejects_foreign_json(self):
        p = os.path.join(self.d, 'other.json')
        with open(p, 'w') as fh:
            json.dump({'hello': 1}, fh)
        notes = []
        self.assertEqual(bl.load_logs([p], notes), [])
        self.assertTrue(notes)

    def test_anonymize(self):
        name = 'BA593XYZ'
        lg = bl.new_log(device={'name': name, 'id': 'AbC123=='})
        lg['entries'] = [
            {'t': 0, 'k': 'sys', 'n': 'Выбрано устройство: ' + name},
            {'t': 1, 'k': 'rd', 's': bl.norm_uuid('1800'), 'u': bl.norm_uuid('2a00'), 'h': bl.hex_of(name.encode())},
            {'t': 2, 'k': 'rd', 's': bl.norm_uuid('180a'), 'u': bl.norm_uuid('2a23'), 'h': '11 22 33 44 55 66 77 88'},
            {'t': 3, 'k': 'rx', 's': 'x', 'u': 'y', 'h': '01 ' + bl.hex_of(name.encode()) + ' 02'},
            {'t': 4, 'k': 'sys', 'n': 'peer AA:BB:CC:DD:EE:FF'},
        ]
        bl.anonymize(lg, extra=['AA:BB:CC:DD:EE:FF'])
        text = bl.dumps(lg)
        self.assertEqual(lg['device'], {'name': 'scooter', 'id': 'scooter'})
        for secret in (name, 'AbC123==', 'AA:BB:CC:DD:EE:FF', bl.hex_of(name.encode()), '11 22 33'):
            self.assertNotIn(secret, text)
        self.assertEqual(bl.bytes_of(lg['entries'][1]['h']), b'scooter')
        self.assertEqual(len(bl.bytes_of(lg['entries'][3]['h'])), len(name) + 2)

    def test_anonymize_name_split_across_packets(self):
        # the real case: the dashboard sends AT+SETNAME over the UART bridge, cut into 20-byte notifications
        name = 'BirdAir-202190918'
        text = b'AT+SETNAME=' + name.encode() + b'\r\nAT+RESET\r\n'
        u = bl.norm_uuid('ffe1')
        lg = bl.new_log(device={'name': name, 'id': 'xyz123'})
        lg['entries'] = [{'t': i, 'k': 'rx', 's': bl.norm_uuid('ffe0'), 'u': u, 'h': bl.hex_of(text[i:i + 20])} for i in range(0, len(text), 20)]
        lg['entries'].append({'t': 99, 'k': 'rx', 's': 'x', 'u': 'other', 'h': bl.hex_of(b'SN 202190918')})
        bl.anonymize(lg)
        joined = b''.join(bl.bytes_of(e['h']) for e in lg['entries'][:-1])
        self.assertEqual(joined, b'AT+SETNAME=' + b'*' * len(name) + b'\r\nAT+RESET\r\n')
        self.assertEqual(bl.bytes_of(lg['entries'][-1]['h']), b'SN *********')   # the serial alone, too
        self.assertEqual([len(bl.bytes_of(e['h'])) for e in lg['entries'][:-1]], [20, 20])   # name cut between packets 1 and 2

    def test_anonymize_short_name_only_as_a_word(self):
        lg = bl.new_log(device={'name': 'LE', 'id': 'scooter'})
        lg['entries'] = [{'t': 0, 'k': 'sys', 'n': 'Выбрано устройство: LE'}, {'t': 1, 'k': 'sys', 'n': 'MTU 247, LEGACY'}]
        bl.anonymize(lg)
        self.assertEqual([e['n'] for e in lg['entries']], ['Выбрано устройство: scooter', 'MTU 247, LEGACY'])

    def test_names(self):
        self.assertEqual(bl.slug('Газ и тормоз!'), 'gaz-i-tormoz')
        self.assertEqual(bl.slug('  '), 'log')
        self.assertRegex(bl.log_name('2026-10-14T19:00:00Z', 'Зарядка'), r'^\d{8}-\d{6}-zaryadka\.json$')

    def test_new_logs_skips_sources(self):
        logs = os.path.join(self.d, 'logs')
        os.makedirs(logs)
        for name in ('a.json', 'b.json.gz', 'README.md'):
            open(os.path.join(logs, name), 'w').close()
        cur = os.path.join(self.d, 'current.json')
        with open(cur, 'w') as fh:
            json.dump({'sources': [analyze_log.rel(os.path.join(logs, 'a.json'))]}, fh)
        self.assertEqual([os.path.basename(p) for p in analyze_log.new_logs(logs, cur)], ['b.json.gz'])


class SecretsTest(unittest.TestCase):
    TOKEN = 'github' + '_pat_' + '11ABCDEFG0123456789_abcdefghijklmnop'

    def test_repository_is_clean(self):
        with contextlib.redirect_stderr(io.StringIO()) as err:
            code = check_secrets.main([])
        self.assertEqual(code, 0, err.getvalue())

    def test_finds_plain_and_gzipped_tokens(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, 'ok.txt'), 'w') as fh:
                fh.write('nothing here\n')
            self.assertEqual(check_secrets.main([d]), 0)
            with open(os.path.join(d, 'log.json.gz'), 'wb') as fh:
                fh.write(gzip.compress(json.dumps({'t': 'Bearer ' + self.TOKEN}).encode()))
            with contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(check_secrets.main([d]), 1)
            self.assertIn('log.json.gz', err.getvalue())
            self.assertNotIn(self.TOKEN[12:], err.getvalue())   # the token itself is never echoed

    def test_staged_mode_in_a_scratch_repo(self):
        with tempfile.TemporaryDirectory() as d:
            git = lambda *a: subprocess.run(['git', '-C', d, *a], check=True, capture_output=True)  # noqa: E731
            git('init', '-q')
            with open(os.path.join(d, 'a.txt'), 'w') as fh:
                fh.write('gh' + 'p_' + 'x' * 36 + '\n')
            git('add', 'a.txt')
            r = subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'check_secrets.py'), '--staged'],
                               cwd=d, capture_output=True, text=True)
            self.assertEqual(r.returncode, 1, r.stderr)


if __name__ == '__main__':
    unittest.main()
