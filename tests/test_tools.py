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
        with open(os.path.join(ROOT, 'decoders', 'current.json'), encoding='utf-8') as fh:
            self.assertEqual(self.dec['version'], json.load(fh)['version'] + 1)   # proposes the next version
        self.assertIn('updatedAt', self.dec)
        self.assertFalse(self.dec['json'])

    def test_report_mentions_markers(self):
        self.assertIn('«Газ»', self.report)
        self.assertIn('XOR', self.report)


class LongDemoTest(unittest.TestCase):
    def test_voltage_low_byte_is_not_speed(self):
        # over 1600 s the voltage drifts through many 2.56 V steps, so its low byte wraps
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, '20261014-190000-demo.json')
            bl.save_log(fixtures.demo_log(1600), p)
            res, _ = run_analyze([p])
        got = {c['key']: (c['off'], c['type'], c['scale']) for c in res['chosen']}
        self.assertEqual(got.get('speed_kmh'), (9, 'u16le', 0.1), got)
        self.assertEqual(got.get('voltage'), (5, 'u16le', 0.01), got)


class HandleUuidTest(unittest.TestCase):
    def test_fields_without_a_uuid_are_set_aside(self):
        # parse_btsnoop without GATT discovery names characteristics by handle
        lg = fixtures.demo_log(200)
        lg['gatt'] = []
        for e in lg['entries']:
            if e['k'] == 'rx':
                e['s'], e['u'] = 'unknown', 'handle-0x0012'
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, '20261014-190000-snoop.json')
            bl.save_log(lg, p)
            res, _ = run_analyze([p])
        dec = res['decoder']
        self.assertEqual(dec['fields'], [])
        self.assertIn('speed_kmh', {f['key'] for f in dec['needs_uuid']})
        for f in dec['needs_uuid']:
            self.assertEqual((f['uuid'], f['handle'], f['confidence']), ('', 'handle-0x0012', 'низкая'))
            self.assertIn('UUID', f['evidence'][0])


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

    def test_chunked_frames_get_distinct_patterns(self):
        # 30-byte frames over a UART bridge arrive as 20 + 10 byte notifications
        lg = bl.new_log()
        u = bl.norm_uuid('ffe1')
        for i in range(60):
            f = fixtures.demo_frame(0x25, [(i * (j + 3) + j * 11) & 0xFF for j in range(25)])
            self.assertEqual((len(f), f[2]), (30, 0x1B))
            lg['entries'] += [{'t': i * 100, 'k': 'rx', 's': bl.norm_uuid('ffe0'), 'u': u, 'h': bl.hex_of(f[:20])},
                              {'t': i * 100 + 1, 'k': 'rx', 's': bl.norm_uuid('ffe0'), 'u': u, 'h': bl.hex_of(f[20:])}]
        groups = {g.length: g for g in analyze_log.build_groups([lg])}
        self.assertEqual(sorted(groups), [10, 20])
        self.assertEqual(groups[20].pattern, '5A A5 1B 25')
        self.assertNotEqual(groups[10].pattern, groups[20].pattern)


class NeedsMatchTest(unittest.TestCase):
    def test_field_without_a_match_on_a_mixed_characteristic_is_set_aside(self):
        g1 = analyze_log.Group(bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1'), {}, 10, [(0, i, bytes(10)) for i in range(6)])
        g2 = analyze_log.Group(bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1'), {0: 0x5A}, 20, [(0, i, b'\x5a' + bytes(19)) for i in range(6)])
        chosen = [{'group': g1, 'key': 'speed_kmh', 'name': '', 'off': 3, 'type': 'u8', 'scale': 0.1, 'score': 1.0, 'why': ['x']},
                  {'group': g2, 'key': 'current', 'name': '', 'off': 4, 'type': 's16le', 'scale': 0.01, 'score': 1.0, 'why': ['y']}]
        dec = analyze_log.candidate_decoder(chosen, [g1, g2], [], {'version': 1})
        self.assertEqual([f['key'] for f in dec['fields']], ['current'])
        self.assertEqual([f['key'] for f in dec['needs_match']], ['speed_kmh'])
        self.assertIn('Начало пакета', dec['needs_match'][0]['evidence'][0])


REAL_FRAMED = os.path.join(ROOT, 'logs', '20261014-174148-log.json')


class FramedRealLogTest(unittest.TestCase):
    """The owner's log: after +MODE=3 the dashboard streams frames «: addr cmd len data sum16 CR LF»."""

    @classmethod
    def setUpClass(cls):
        cls.res, cls.report = run_analyze([REAL_FRAMED])
        cls.dec = cls.res['decoder']

    def test_groups_are_frames(self):
        pats = {g.pattern: g for g in self.res['groups'] if g.u == bl.norm_uuid('ffe1')}
        self.assertEqual(set(pats), {'3A 17 00 0D', '3A 18 20 10'})
        self.assertEqual((pats['3A 17 00 0D'].length, pats['3A 18 20 10'].length), (21, 24))
        for g in pats.values():
            self.assertEqual(g.framing, 'colon-sum16')
            self.assertEqual((g.checksum['algo'], g.checksum['start']), ('SUM16', 1))
        self.assertGreaterEqual(sum(len(g.items) for g in pats.values()), 268)

    def test_proposes_mode_and_voltage(self):
        self.assertEqual(self.dec['framing'], 'colon-sum16')
        got = {f['key']: f for f in self.dec['fields']}
        mode = got['mode']
        self.assertEqual((mode['match'], mode['offset'], mode['type'], mode.get('mask'), mode['add']), ('3A 17 00 0D', 9, 'u8', 3, 0))
        self.assertEqual(mode['confidence'], 'высокая')
        self.assertIn('+MODE=1 → 09', mode['evidence'][0])
        volt = got['voltage']
        self.assertEqual((volt['match'], volt['offset'], volt['type']), ('3A 18 20 10', 9, 'u16le'))
        self.assertAlmostEqual(volt['scale'], 0.001)
        self.assertEqual(bl.check_decoder(self.dec), [])

    def test_phone_commands_are_markers(self):
        got = {(k, n) for _, _, k, n in analyze_log.collect_markers(self.res['logs'])}
        self.assertEqual(got, {('mode', '+MODE=3'), ('mode', '+MODE=2'), ('mode', '+MODE=1'),
                               ('light', '+HLGT=1'), ('light', '+HLGT=0'), (None, '+SVGT=?')})

    def test_report(self):
        self.assertIn('colon-sum16', self.report)
        self.assertIn('ОПАСНО', self.report)      # the KEY? handshake write
        self.assertIn('«+SVGT=0»', self.report)    # text between frames


class FramedSyntheticTest(unittest.TestCase):
    def test_mode_by_shift_and_plain_fields_set_aside(self):
        import uart_frames as uf
        s, u = bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1')
        lg = bl.new_log()
        es = lg['entries']
        mode = 2
        for k in range(300):
            t = k * 100
            if k in (50, 120, 200, 260):
                mode = {50: 1, 120: 3, 200: 2, 260: 1}[k]
                es.append({'t': t - 50, 'k': 'tx', 's': s, 'u': u, 'h': bl.hex_of(b'+MODE=%d' % mode)})
            f = uf.make_frame(0x17, 0x00, bytes([0, 0x55, mode + 10, (k * 7) & 0xFF, 0]))
            es += [{'t': t, 'k': 'rx', 's': s, 'u': u, 'h': bl.hex_of(f[:6])}, {'t': t, 'k': 'rx', 's': s, 'u': u, 'h': bl.hex_of(f[6:])}]
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, '20261014-190000-frames.json')
            bl.save_log(lg, p)
            res, _ = run_analyze([p])
        got = {f['key']: f for f in res['decoder']['fields']}
        self.assertEqual((got['mode']['match'], got['mode']['offset'], got['mode']['add']), ('3A 17 00 05', 6, -10))
        self.assertNotIn('mask', got['mode'])
        g1 = next(g for g in res['groups'] if g.framing)
        g2 = analyze_log.Group(s, bl.norm_uuid('ffe2'), {0: 0x5A}, 10, [(0, i, b'\x5a' + bytes(9)) for i in range(6)])
        chosen = [{'group': g1, 'key': 'mode', 'name': '', 'off': 6, 'type': 'u8', 'scale': 1, 'score': 1.3, 'why': ['x'], 'add': -10},
                  {'group': g2, 'key': 'speed_kmh', 'name': '', 'off': 3, 'type': 'u8', 'scale': 0.1, 'score': 1.0, 'why': ['y']}]
        dec = analyze_log.candidate_decoder(chosen, [g1, g2], [], {'version': 3})
        self.assertEqual((dec['framing'], dec['version']), ('colon-sum16', 4))
        self.assertEqual([f['key'] for f in dec['fields']], ['mode'])
        self.assertEqual([f['key'] for f in dec['unframed']], ['speed_kmh'])

    def test_plain_logs_have_no_framing(self):
        res, _ = run_analyze([os.path.join(ROOT, 'logs', '20261014-160502-log.json')])
        self.assertNotIn('framing', res['decoder'])
        self.assertFalse(any(g.framing for g in res['groups']))


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

    def test_different_recording_with_same_start_is_kept(self):
        # «Очистить» used to keep startedAt, so another recording got the same start and name
        lg = fixtures.demo_log(20)
        bl.save_log(lg, os.path.join(self.d, '20261014-190000-log.json'))
        other = fixtures.demo_log(10)
        inside = dict(other, entries=[dict(e, t=e['t'] + 50) for e in other['entries']])     # within lg's time span
        after = dict(other, entries=[dict(e, t=e['t'] + 30000) for e in other['entries']])   # t went on from the old start
        bl.save_log(inside, os.path.join(self.d, '20261014-190000-log-2.json'))
        bl.save_log(after, os.path.join(self.d, '20261014-190000-log-3.json'))
        notes = []
        logs = bl.load_logs([self.d], notes)
        self.assertEqual(sorted(len(x['entries']) for x in logs), sorted(len(x['entries']) for x in (lg, inside, after)))
        self.assertFalse([n for n in notes if 'повтор' in n])

    def test_dropped_resend_stays_in_sources(self):
        lg = fixtures.demo_log(20)
        short = os.path.join(self.d, '20261014-190000-log.json')
        bl.save_log(dict(lg, entries=lg['entries'][:50]), short)
        bl.save_log(lg, os.path.join(self.d, '20261014-190000-log-2.json'))
        res, _ = run_analyze([self.d])
        self.assertEqual(len(res['logs']), 1)
        self.assertIn(analyze_log.rel(short), res['decoder']['sources'])

    def test_interrupted_resend_with_other_part_count(self):
        # first send: 2 parts, only part 1 arrived; re-sent later, longer, in 3 parts
        lg = fixtures.demo_log(30)
        es = lg['entries']
        names = []
        first = es[:len(es) // 3]
        packings = [(2, [first[:len(first) // 2]]), (3, [es[:len(es) // 3], es[len(es) // 3:2 * len(es) // 3], es[2 * len(es) // 3:]])]
        for count, chunks in packings:
            for i, ch in enumerate(chunks, 1):
                names.append(os.path.join(self.d, f'20261014-190000-log-part{i}of{count}.json.gz'))
                bl.save_log(dict(lg, entries=ch, part={'id': '20261014-190000-log', 'index': i, 'count': count}), names[-1])
        res, _ = run_analyze([self.d])
        self.assertEqual(len(res['logs']), 1)
        self.assertEqual(len(res['logs'][0]['entries']), len(es))
        self.assertEqual(res['logs'][0]['_missing'], [])
        for p in names:
            self.assertIn(analyze_log.rel(p), res['decoder']['sources'])

    def test_broken_gzip_is_skipped_with_a_note(self):
        z = gzip.compress(bl.dumps(fixtures.demo_log(5)).encode())
        broken = {'cut.json.gz': z[:-12], 'garbage.json.gz': z[:10] + b'\xff' * 40,
                  'crc.json.gz': z[:-8] + bytes(x ^ 0xFF for x in z[-8:-4]) + z[-4:]}
        for name, data in broken.items():
            with open(os.path.join(self.d, name), 'wb') as fh:
                fh.write(data)
        notes = []
        self.assertEqual(bl.load_logs([self.d], notes), [])
        self.assertEqual(sum('повреждён' in n for n in notes), 3, notes)

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

    def test_new_logs_walks_subfolders(self):
        logs = os.path.join(self.d, 'logs')
        for sub in ('sub/deeper', '.hidden'):
            os.makedirs(os.path.join(logs, sub))
        for name in ('a.json', 'sub/c.json.gz', 'sub/deeper/d.json', 'sub/e.json.tmp', 'sub/e.json.gz.tmp', '.hidden/f.json'):
            open(os.path.join(logs, name), 'w').close()
        got = [os.path.relpath(p, logs) for p in analyze_log.new_logs(logs, os.path.join(self.d, 'missing.json'))]
        self.assertEqual(got, ['a.json', os.path.join('sub', 'c.json.gz'), os.path.join('sub', 'deeper', 'd.json')])


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

    def test_broken_gzip_neither_crashes_nor_hides_a_token(self):
        z = gzip.compress(json.dumps({'t': 'Bearer ' + self.TOKEN, 'pad': 'x' * 2000}).encode())
        with tempfile.TemporaryDirectory() as d:
            # last bytes lost in an upload; trailer checksum damaged
            for name, data in (('cut.json.gz', z[:-6]), ('crc.json.gz', z[:-8] + bytes(x ^ 0xFF for x in z[-8:-4]) + z[-4:])):
                p = os.path.join(d, name)
                with open(p, 'wb') as fh:
                    fh.write(data)
                with contextlib.redirect_stderr(io.StringIO()) as err:
                    self.assertEqual(check_secrets.main([p]), 1, name)
                self.assertIn(name, err.getvalue())
            p = os.path.join(d, 'garbage.json.gz')
            with open(p, 'wb') as fh:
                fh.write(z[:10] + b'\xff' * 40)
            self.assertEqual(check_secrets.main([p]), 0)   # no traceback either

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
