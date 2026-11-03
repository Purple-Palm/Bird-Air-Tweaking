"""Tests for tools/uart_frames.py (controller frames «: addr cmd len data sum16 CR LF»)."""
import contextlib
import io
import json
import os
import random
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools'))

import birdlog as bl  # noqa: E402
import uart_frames as uf  # noqa: E402

REAL = os.path.join(ROOT, 'logs', '20261014-174148-log.json')
FFE0, FFE1 = bl.norm_uuid('ffe0'), bl.norm_uuid('ffe1')
MODE_FRAME = bytes.fromhex('3A 17 00 0D 00 00 00 00 00 0B 00 00 00 37 A8 01 01 10 01 0D 0A'.replace(' ', ''))
VOLT_FRAME = bytes.fromhex('3A 18 20 10 03 00 00 18 18 AE 9B D2 FF D7 0C 0B 11 4B 69 00 48 05 0D 0A'.replace(' ', ''))


def feed_all(chunks, **kw):
    asm = uf.Assembler(**kw)
    out = []
    for i, c in enumerate(chunks):
        out += asm.feed(c, i * 10)
    asm.finish()
    return asm, out


class FrameFormatTest(unittest.TestCase):
    def test_examples_from_the_log_validate(self):
        for f in (MODE_FRAME, VOLT_FRAME):
            self.assertTrue(uf.is_valid(f), bl.hex_of(f))
        self.assertEqual(uf.make_frame(0x17, 0x00, MODE_FRAME[4:17]), MODE_FRAME)
        self.assertEqual(uf.head(VOLT_FRAME), (0x18, 0x20, 0x10))
        self.assertEqual(bl.read_value(VOLT_FRAME, 9, 'u16le'), 0x9BAE)   # 39.854 V

    def test_broken_frames_do_not_validate(self):
        bad_sum = bytearray(MODE_FRAME)
        bad_sum[9] ^= 1
        bad_end = MODE_FRAME[:-1] + b'\x0B'
        for f in (bytes(bad_sum), bad_end, MODE_FRAME[:-1], MODE_FRAME[1:], MODE_FRAME + b'\x00'):
            self.assertFalse(uf.is_valid(f), bl.hex_of(f))


class AssemblerTest(unittest.TestCase):
    def test_frame_split_across_chunks(self):
        asm, out = feed_all([VOLT_FRAME[:20], VOLT_FRAME[20:], MODE_FRAME[:20], MODE_FRAME[20:]])
        self.assertEqual([f.frame for f in out], [VOLT_FRAME, MODE_FRAME])
        self.assertEqual([f.t for f in out], [10, 30])          # time of the chunk with the last byte
        self.assertEqual((asm.n_frames, asm.recovered, asm.dropped, asm.junk), (2, 0, 0, 0))

    def test_byte_by_byte(self):
        data = VOLT_FRAME + MODE_FRAME * 3
        _, out = feed_all([data[i:i + 1] for i in range(len(data))])
        self.assertEqual([f.frame for f in out], [VOLT_FRAME] + [MODE_FRAME] * 3)
        self.assertEqual(list(uf.iter_frames((i, data[i:i + 1]) for i in range(len(data))))[0].t, len(VOLT_FRAME) - 1)

    def test_junk_text_between_frames(self):
        stream = b'+MODE=3' + MODE_FRAME + b'+SVGT=0' + VOLT_FRAME + b'OK OK OK ' + MODE_FRAME + b'\r\n+HLGT=1\r\n' + VOLT_FRAME
        chunks = [stream[i:i + 20] for i in range(0, len(stream), 20)]
        asm, out = feed_all(chunks)
        self.assertEqual([f.frame for f in out], [MODE_FRAME, VOLT_FRAME, MODE_FRAME, VOLT_FRAME])
        self.assertFalse(any(f.recovered for f in out))
        labels = uf.junk_labels(asm.texts)
        for text in ('+MODE=3', '+SVGT=0', 'OK OK OK ', '+HLGT=1'):
            self.assertIn('«' + text + '»', labels)

    def test_text_after_crlf_does_not_delay_the_next_frame(self):
        # «+MODE=2» right after a frame looks like a header with length 0x4F; once a whole frame
        # with 3A follows, that candidate is dropped instead of waiting for 86 bytes
        asm = uf.Assembler()
        self.assertEqual(len(asm.feed(MODE_FRAME, 0)), 1)
        self.assertEqual(asm.feed(b'+MODE=2', 100), [])
        got = asm.feed(VOLT_FRAME, 200)
        self.assertEqual([(f.t, f.frame) for f in got], [(200, VOLT_FRAME)])

    def test_missing_colon_after_crlf_is_recovered(self):
        asm, out = feed_all([MODE_FRAME, VOLT_FRAME[1:21], VOLT_FRAME[21:], MODE_FRAME[1:]])
        self.assertEqual([f.frame for f in out], [MODE_FRAME, VOLT_FRAME, MODE_FRAME])
        self.assertEqual([f.recovered for f in out], [False, True, True])
        self.assertEqual(asm.recovered, 2)

    def test_missing_colon_at_stream_start(self):
        _, out = feed_all([MODE_FRAME[1:]])
        self.assertEqual([(f.frame, f.recovered) for f in out], [(MODE_FRAME, True)])

    def test_missing_colon_lookalike_not_after_crlf_is_rejected(self):
        # the same bytes without 3A, but not right after CR LF: not a frame
        asm, out = feed_all([b'+X', MODE_FRAME[1:], MODE_FRAME])
        self.assertEqual([(f.frame, f.recovered) for f in out], [(MODE_FRAME, False)])
        asm, out = feed_all([MODE_FRAME, b'\x00' + MODE_FRAME[1:], VOLT_FRAME])
        self.assertEqual([f.frame for f in out], [MODE_FRAME, VOLT_FRAME])
        self.assertEqual(asm.recovered, 0)
        self.assertEqual(asm.junk, len(MODE_FRAME))

    def test_bad_checksum_is_rejected(self):
        bad = bytearray(VOLT_FRAME)
        bad[11] ^= 0x10
        asm, out = feed_all([MODE_FRAME, bytes(bad), MODE_FRAME])
        self.assertEqual([f.frame for f in out], [MODE_FRAME, MODE_FRAME])
        self.assertEqual(asm.dropped, 1)
        # a frame without its 3A and with a wrong sum after CR LF is not recovered either
        asm, out = feed_all([MODE_FRAME, bytes(bad[1:]), MODE_FRAME])
        self.assertEqual((len(out), asm.recovered), (2, 0))

    def test_crlf_at_a_chunk_end_still_lets_a_lost_colon_frame_in(self):
        # (the page's assembler must agree: tests/e2e/frames.spec.js feeds the same streams)
        bad = bytearray(VOLT_FRAME)
        bad[-3] ^= 1                                    # wrong sum, but it ends in CR LF
        stream = bytes(bad) + VOLT_FRAME[1:]
        for i in range(1, len(stream)):
            _, out = feed_all([stream[:i], stream[i:]])
            self.assertEqual([(f.frame, f.recovered) for f in out], [(VOLT_FRAME, True)], i)
        # text ending in CR LF, then a frame without its 3A, all in one notification
        _, out = feed_all([MODE_FRAME + b'+SVGT=0\r\n' + VOLT_FRAME[1:] + MODE_FRAME])
        self.assertEqual([f.frame for f in out], [MODE_FRAME, VOLT_FRAME, MODE_FRAME])

    def test_bad_trailer_is_rejected(self):
        asm, out = feed_all([MODE_FRAME[:-1] + b'\x00', MODE_FRAME])
        self.assertEqual([f.frame for f in out], [MODE_FRAME])

    def test_cut_frame_resyncs(self):
        # the second half of a split frame was lost: the next frame still comes through
        _, out = feed_all([VOLT_FRAME[:20], MODE_FRAME, VOLT_FRAME])
        self.assertEqual([f.frame for f in out], [MODE_FRAME, VOLT_FRAME])

    def test_reconnect_starts_a_new_stream(self):
        lg = bl.new_log()
        lg['entries'] = [{'t': 1, 'k': 'rx', 's': FFE0, 'u': FFE1, 'h': bl.hex_of(VOLT_FRAME[:20])},
                         {'t': 2, 'k': 'sys', 'n': 'Найдено сервисов: 3, характеристик: 5'},
                         {'t': 3, 'k': 'rx', 's': FFE0, 'u': FFE1, 'h': bl.hex_of(MODE_FRAME[1:])}]
        asm = uf.assemble_log(lg)[(FFE0, FFE1)]
        self.assertEqual([(f.t, f.frame, f.recovered) for f in asm.frames], [(3, MODE_FRAME, True)])
        self.assertEqual(asm.junk, 20)

    def test_buffer_cap(self):
        rnd = random.Random(7)
        junk = bytearray(rnd.randrange(256) for _ in range(6000))
        for i in range(0, len(junk), 97):       # starts that ask for long frames, and CR LF
            junk[i:i + 4] = b'\x3a\x01\x02\xff' if i % 2 else b'\r\n\x01\xfe'
        asm = uf.Assembler()
        for i in range(0, len(junk), 20):
            asm.feed(bytes(junk[i:i + 20]), i)
            self.assertLessEqual(len(asm.buf), uf.CAP)
        self.assertLessEqual(asm.max_buf, uf.CAP)
        got = [f.frame for f in asm.feed(b'\r\n' + MODE_FRAME + VOLT_FRAME, 99999)]
        self.assertEqual(got[-2:], [MODE_FRAME, VOLT_FRAME])

    def test_small_cap_rejects_long_frames(self):
        long = uf.make_frame(0x18, 0x20, bytes(range(40)))
        short = uf.make_frame(0x17, 0x00, bytes(10))
        asm, out = feed_all([long, short], cap=32)
        self.assertEqual([f.frame for f in out], [short])
        self.assertLess(asm.max_buf, 32)
        _, out = feed_all([long, short])
        self.assertEqual([f.frame for f in out], [long, short])


class RealLogTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.log = bl.load_file(REAL)
        asms = uf.assemble_log(cls.log, 'ffe1')
        cls.asm = asms[(FFE0, FFE1)]
        cls.frames = cls.asm.frames
        cls.tx = [e for e in cls.log['entries'] if e['k'] == 'tx']

    def kind(self, h):
        return [f for f in self.frames if uf.head_hex(f.frame) == h]

    def test_all_frames_found(self):
        strict = [f for f in self.frames if not f.recovered]
        self.assertGreaterEqual(len(strict), 268)
        self.assertGreater(self.asm.recovered, 0)
        self.assertEqual(sum(not uf.is_valid(f.frame) for f in self.frames), 0)
        self.assertEqual(self.asm.dropped, 0)
        self.assertEqual({uf.head_hex(f.frame) for f in self.frames}, {'3A 17 00 0D', '3A 18 20 10'})
        labels = uf.junk_labels(self.asm.texts)
        for text in ('+MODE=3', '+MODE=2', '+MODE=1', '+SVGT=0', 'OK OK OK '):
            self.assertIn('«' + text + '»', labels)

    def test_no_byte_is_lost_or_doubled(self):
        rx = sum(len(bl.entry_bytes(e)) for e in self.log['entries'] if e['k'] == 'rx' and e['u'] == FFE1)
        self.assertEqual(sum(len(f.frame) - f.recovered for f in self.frames) + self.asm.junk, rx)

    def test_voltage(self):
        volts = [bl.read_value(f.frame, 9, 'u16le') * 0.001 for f in self.kind('3A 18 20 10')]
        self.assertGreater(len(volts), 40)
        self.assertAlmostEqual(sum(volts) / len(volts), 39.84, delta=0.05)
        self.assertTrue(all(39.7 < v < 40.0 for v in volts), (min(volts), max(volts)))

    def test_mode_follows_commands(self):
        mode = self.kind('3A 17 00 0D')
        seq = []
        for f in mode:
            v = f.frame[9] & 3
            if not seq or seq[-1] != v:
                seq.append(v)
        self.assertEqual(seq, [3, 2, 1])
        cmds = [(e['t'], int(bl.entry_bytes(e)[6:7])) for e in self.tx if bl.entry_bytes(e).startswith(b'+MODE=')]
        self.assertEqual([n for _, n in cmds], [3, 2, 1])
        for (t, n), nxt in zip(cmds, [c[0] for c in cmds[1:]] + [None]):
            last = [f for f in mode if t < f.t < (nxt or 10 ** 9)][-1]
            self.assertEqual(last.frame[9] & 3, n, f'+MODE={n}')

    def test_stream_starts_after_first_command_and_stops_after_key(self):
        first_tx = self.tx[0]
        self.assertEqual(bl.entry_bytes(first_tx), b'+MODE=3')
        self.assertGreater(self.frames[0].t, first_tx['t'])
        key = next(e for e in self.tx if bl.entry_bytes(e).startswith(bytes.fromhex('AE4B4559')))
        self.assertTrue(bl.danger_write(bl.entry_bytes(key)))
        self.assertFalse([f for f in self.frames if f.t > key['t']])
        # anywhere in a write, not only at its start (the module passes bytes straight to the UART)
        self.assertTrue(bl.danger_write(bytes.fromhex('2BAE4B45593FD6')))
        self.assertIsNone(bl.danger_write(b'+MODE=3'))
        self.assertIsNone(bl.danger_write(bytes.fromhex('AE4B45')))

    def test_tx_effects(self):
        eff = uf.tx_effects(self.frames, [e for e in self.tx if e['u'] == FFE1], 3000)
        by = {bl.entry_bytes(e): notes for e, notes in eff}
        self.assertEqual(by[b'+MODE=3'][0][0], 'start')
        self.assertIn(('first', '3A 17 00 0D', 9, 0x0B), by[b'+MODE=3'])
        for cmd, (v0, v1) in ((b'+MODE=2', (0x0B, 0x0A)), (b'+MODE=1', (0x0A, 0x09))):
            hits = [n for n in by[cmd] if n[0] == 'byte' and n[1] == '3A 17 00 0D' and n[2] == 9]
            self.assertEqual([(n[3], n[4], n[6]) for n in hits], [(v0, v1, True)], cmd)
        self.assertEqual(by[bytes.fromhex('AE4B45593FD6')][0][0], 'stop')

    def test_cli(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.assertEqual(uf.main([REAL]), 0)
        out = buf.getvalue()
        for s in ('Кадр 3A 17 00 0D', 'Кадр 3A 18 20 10', 'байт 9: 0B→0A', 'байт 9: 0A→09', 'ОПАСНО', 'пошёл поток кадров'):
            self.assertIn(s, out)


class RepoDecoderTest(unittest.TestCase):
    """decoders/current.json is a valid decoder, and on the real log it shows what was observed."""

    @classmethod
    def setUpClass(cls):
        with open(os.path.join(ROOT, 'decoders', 'current.json'), encoding='utf-8') as fh:
            cls.dec = json.load(fh)

    def test_format(self):
        self.assertEqual(bl.check_decoder(self.dec), [])

    def test_values_on_the_real_log(self):
        if self.dec.get('framing') != uf.FRAMING:
            self.skipTest('декодер без framing colon-sum16')
        vals = uf.decode_log(self.dec, bl.load_file(REAL))
        keys = {f['key'] for f in self.dec['fields']}
        if 'voltage' in keys:
            v = [x for _, k, x in vals if k == 'voltage']
            self.assertTrue(v and all(39.7 < x < 40.0 for x in v), v[:5])
        if 'mode' in keys:
            seq = []
            for _, k, x in vals:
                if k == 'mode' and (not seq or seq[-1] != x):
                    seq.append(x)
            self.assertEqual(seq, [3, 2, 1])
        if 'current' in keys:
            c = [x for _, k, x in vals if k == 'current']
            self.assertTrue(c and all(0 < abs(x) < 1 for x in c), c[:5])


class DecoderFormatTest(unittest.TestCase):
    def test_mask(self):
        f = {'key': 'mode', 'match': '3A 17 00 0D', 'offset': 9, 'type': 'u8', 'mask': 3, 'scale': 1}
        self.assertEqual(bl.field_value(f, MODE_FRAME), 3)
        self.assertEqual(bl.field_value(dict(f, mask=0x0C), MODE_FRAME), 2)       # 0x0B = 0b1011 -> bits 2–3 = 0b10
        self.assertEqual(bl.field_value(dict(f, match='3A 18'), MODE_FRAME), None)
        self.assertEqual(bl.apply_mask(-46, 0xFF), 0xD2)                        # same as JS (raw & mask) on a negative s16
        cur = {'key': 'current', 'match': '3A 18 20 10', 'offset': 11, 'type': 's16le', 'scale': -0.001}
        self.assertAlmostEqual(bl.field_value(cur, VOLT_FRAME), 0.046)

    def test_check_decoder(self):
        good = {'format': bl.DECODER_FORMAT, 'version': 3, 'json': False, 'framing': 'colon-sum16',
                'fields': [{'key': 'mode', 'uuid': 'ffe1', 'match': '3A 17 00 0D', 'offset': 9, 'type': 'u8', 'mask': 3, 'scale': 1}]}
        self.assertEqual(bl.check_decoder(good), [])
        self.assertEqual(bl.check_decoder({k: v for k, v in good.items() if k != 'framing'}), [])
        bad = [dict(good, framing='slip'), dict(good, version='3'),
               dict(good, fields=[dict(good['fields'][0], mask=0)]), dict(good, fields=[dict(good['fields'][0], mask=0x100)]),
               dict(good, fields=[dict(good['fields'][0], match='3A ZZ')]), dict(good, fields=[dict(good['fields'][0], type='f32')])]
        for d in bad:
            self.assertTrue(bl.check_decoder(d), d)

    def test_read_value_matches_the_analyzer(self):
        import analyze_log
        b = bytes([0x01, 0xFF, 0x80, 0x7F, 0x00, 0xAB])
        for typ in analyze_log.TYPES:
            for off in range(len(b)):
                self.assertEqual(bl.read_value(b, off, typ), analyze_log.read_num(b, off, typ), (typ, off))

    def test_plain_decoder_reads_packets(self):
        lg = bl.new_log()
        lg['entries'] = [{'t': 1, 'k': 'rx', 's': FFE0, 'u': FFE1, 'h': '5A A5 07'}]
        dec = {'fields': [{'key': 'custom', 'match': '5A A5', 'offset': 2, 'type': 'u8', 'scale': 1}]}
        self.assertEqual(uf.decode_log(dec, lg), [(1, 'custom', 7)])
        self.assertEqual(uf.decode_log(dict(dec, framing='colon-sum16'), lg), [])


if __name__ == '__main__':
    unittest.main()
