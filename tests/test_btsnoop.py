"""Tests for tools/parse_btsnoop.py on synthetic btsnoop files.  python3 -m unittest discover -s tests"""
import datetime
import io
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools'))

import analyze_log  # noqa: E402
import birdlog as bl  # noqa: E402
import parse_btsnoop as pb  # noqa: E402

EPOCH_DELTA = 0x00dcddb30f2f8000
T0 = datetime.datetime(2026, 10, 14, 16, 0, 0, tzinfo=datetime.timezone.utc)
H1, H2 = 0x0040, 0x0041
ADDR, NAME = 'A4:C1:38:12:34:56', 'BirdAir-ABC123'
OTHER, OTHER_NAME = '11:22:33:44:55:66', 'OtherDev'
MANUF = b'Bird Rides, Inc. Santa Monica'        # longer than one Read Rsp below: needs a Read Blob
HELLO1 = bytes.fromhex('5AA5060111223344') + b'\x07'
HELLO2 = bytes.fromhex('5AA5060155667788') + b'\x0f'
CMD, CMD2 = bytes.fromhex('5AA5020301'), bytes.fromhex('5AA5020302')
TELE = bytes.fromhex('5AA50B25')
LONG = bytes(range(40))                          # one notification split over two ACL fragments
FFE0, FFE1, FFE2 = (bl.norm_uuid(x) for x in ('ffe0', 'ffe1', 'ffe2'))


def le16(*vals):
    return b''.join(struct.pack('<H', v) for v in vals)


def addr_le(a):
    return bytes.fromhex(a.replace(':', ''))[::-1]


def uuid_le(u):
    return bytes.fromhex(u.replace('-', ''))[::-1]


class Snoop:
    """Builds a btsnoop file: records, H4 events and commands, ACL/L2CAP/ATT packets."""

    def __init__(self, link=1002):
        self.link, self.recs = link, []
        self.us = int((T0 - pb.UNIX_EPOCH).total_seconds()) * 1000000

    def record(self, ptype, sent, data, gap_ms=10, keep=None):
        self.us += gap_ms * 1000
        payload = (bytes([ptype]) if self.link == 1002 else b'') + data
        flags = (0 if sent else 1) | (2 if ptype in (1, 4) else 0)
        incl = payload if keep is None else payload[:keep]
        self.recs.append(struct.pack('>IIIIq', len(payload), len(incl), flags, 0, self.us + EPOCH_DELTA) + incl)

    def event(self, code, params):
        self.record(4, False, bytes([code, len(params)]) + params)

    def command(self, opcode, params):
        self.record(1, True, le16(opcode) + bytes([len(params)]) + params)

    def connect(self, handle, addr, enhanced=False):
        p = b'\x00' + le16(handle) + b'\x00\x00' + addr_le(addr) + (bytes(12) if enhanced else b'')
        self.event(0x3E, bytes([0x0A if enhanced else 0x01]) + p + le16(24, 0, 400) + b'\x00')

    def advertise(self, addr, name, extended=False):
        ad = b'\x02\x01\x06' + bytes([len(name) + 1, 0x09]) + name.encode()
        if extended:
            rep = le16(0x0013) + b'\x00' + addr_le(addr) + b'\x01\x00\xff\x7f\xc0' + le16(0) + b'\x00' + bytes(6)
            self.event(0x3E, b'\x0d\x01' + rep + bytes([len(ad)]) + ad)
        else:
            self.event(0x3E, b'\x02\x01\x04\x00' + addr_le(addr) + bytes([len(ad)]) + ad + b'\xc0')

    def disconnect(self, handle, why=0x13):
        self.event(0x05, b'\x00' + le16(handle) + bytes([why]))

    def acl(self, handle, sent, l2cap, frags=None, keep=None):
        pos, sizes = 0, frags or []
        for i in range(len(sizes) + 1):
            chunk = l2cap[pos:pos + sizes[i]] if i < len(sizes) else l2cap[pos:]
            pbf = 0x2 if i == 0 else 0x1                    # start / continuation
            self.record(2, sent, le16(handle | pbf << 12, len(chunk)) + chunk, keep=keep)
            pos += len(chunk)

    def att(self, handle, sent, pdu, **kw):
        self.acl(handle, sent, le16(len(pdu), 4) + pdu, **kw)

    def smp(self, handle, sent, pdu):
        self.acl(handle, sent, le16(len(pdu), 6) + pdu)

    def save(self, path):
        with open(path, 'wb') as fh:
            fh.write(b'btsnoop\0' + struct.pack('>II', 1, self.link) + b''.join(self.recs))
        return path


def full_capture(link=1002, truncated=False):
    """The Bird app session: discovery, reads, CCCD, writes, notifications, SMP; plus an unrelated device."""
    s = Snoop(link)
    s.advertise(ADDR, NAME)
    s.advertise(OTHER, OTHER_NAME, extended=True)
    s.connect(H1, ADDR)
    a = lambda sent, pdu, **kw: s.att(H1, sent, pdu, **kw)  # noqa: E731
    a(True, b'\x02' + le16(517))
    a(False, b'\x03' + le16(247))
    # services: one 16-bit list, one 128-bit list, then the normal 'not found'
    a(True, b'\x10' + le16(0x0001, 0xFFFF, 0x2800))
    a(False, b'\x11\x06' + le16(0x0001, 0x0003, 0x180A, 0x0010, 0x0016, 0xFFE0))
    a(True, b'\x10' + le16(0x0017, 0xFFFF, 0x2800))
    a(False, b'\x11\x14' + le16(0x0020, 0x0023) + uuid_le(bl.TELINK_OTA_SVC))
    a(True, b'\x10' + le16(0x0024, 0xFFFF, 0x2800))
    a(False, b'\x01\x10' + le16(0x0024) + b'\x0a')
    # characteristic declarations
    a(True, b'\x08' + le16(0x0001, 0x0003, 0x2803))
    a(False, b'\x09\x07' + le16(0x0002) + b'\x02' + le16(0x0003, 0x2A29))
    a(True, b'\x08' + le16(0x0010, 0x0016, 0x2803))
    a(False, b'\x09\x07' + le16(0x0011) + b'\x12' + le16(0x0012, 0xFFE1) + le16(0x0014) + b'\x0c' + le16(0x0015, 0xFFE2))
    a(True, b'\x08' + le16(0x0016, 0x0016, 0x2803))
    a(False, b'\x01\x08' + le16(0x0016) + b'\x0a')
    a(True, b'\x08' + le16(0x0020, 0x0023, 0x2803))
    a(False, b'\x09\x15' + le16(0x0021) + b'\x06' + le16(0x0022) + uuid_le(bl.TELINK_OTA_CHR))
    # descriptors: format 1 (16-bit) and format 2 (128-bit)
    a(True, b'\x04' + le16(0x0013, 0x0013))
    a(False, b'\x05\x01' + le16(0x0013, 0x2902))
    a(True, b'\x04' + le16(0x0022, 0x0022))
    a(False, b'\x05\x02' + le16(0x0022) + uuid_le(bl.TELINK_OTA_CHR))
    a(True, b'\x04' + le16(0x0023, 0x0023))
    a(False, b'\x05\x01' + le16(0x0023, 0x2901))
    # manufacturer name: Read, then Read Blob for the rest
    a(True, b'\x0a' + le16(0x0003))
    a(False, b'\x0b' + MANUF[:22])
    a(True, b'\x0c' + le16(0x0003, 22))
    a(False, b'\x0d' + MANUF[22:])
    # notifications on, two hello writes, then data
    a(True, b'\x12' + le16(0x0013) + b'\x01\x00')
    a(False, b'\x13')
    a(True, b'\x52' + le16(0x0015) + HELLO1)
    a(True, b'\x52' + le16(0x0015) + HELLO2)
    for i in range(3):
        a(False, b'\x1b' + le16(0x0012) + TELE + bytes([i]))
    a(False, b'\x1b' + le16(0x0012) + LONG, frags=[27])
    if truncated:
        a(False, b'\x1b' + le16(0x0012) + TELE + b'\x99', keep=7)
    a(True, b'\x12' + le16(0x0015) + CMD)
    a(False, b'\x13')
    a(True, b'\x12' + le16(0x0015) + CMD2)
    a(False, b'\x01\x12' + le16(0x0015) + b'\x05')
    s.smp(H1, True, bytes([0x01, 0x03, 0x00, 0x2D, 0x10, 0x07, 0x07]))
    s.smp(H1, False, bytes([0x02, 0x03, 0x00, 0x09, 0x10, 0x01, 0x01]))
    s.command(0x2019, le16(H1) + bytes(26))
    s.event(0x08, b'\x00' + le16(H1) + b'\x01')
    # a long write in two prepared parts
    a(True, b'\x16' + le16(0x0015, 0) + b'\xaa\xbb')
    a(False, b'\x17' + le16(0x0015, 0) + b'\xaa\xbb')
    a(True, b'\x16' + le16(0x0015, 2) + b'\xcc\xdd')
    a(False, b'\x17' + le16(0x0015, 2) + b'\xcc\xdd')
    a(True, b'\x18\x01')
    a(False, b'\x19')
    for i in range(3, 6):
        a(False, b'\x1b' + le16(0x0012) + TELE + bytes([i]))
    # an unrelated device with less ATT traffic
    s.connect(H2, OTHER, enhanced=True)
    s.att(H2, False, b'\x1b' + le16(0x0030) + b'\x01\x02')
    s.disconnect(H2, 0x16)
    s.disconnect(H1, 0x13)
    return s


def cached_capture():
    """Android used its GATT cache: no discovery at all, just CCCD, a write and notifications."""
    s = Snoop()
    s.connect(H1, ADDR)
    s.att(H1, True, b'\x12' + le16(0x0013) + b'\x01\x00')
    s.att(H1, False, b'\x13')
    s.att(H1, True, b'\x52' + le16(0x0015) + HELLO1)
    for i in range(4):
        s.att(H1, False, b'\x1b' + le16(0x0012) + TELE + bytes([i]))
    return s


class BtsnoopTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.d = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def run_convert(self, snoop, name='btsnoop_hci.log', **kw):
        src = snoop.save(os.path.join(self.d, name))
        buf = io.StringIO()
        res = pb.convert(src, out=os.path.join(self.d, 'out.json'), stream=buf, **kw)
        res['report'] = buf.getvalue()
        return res

    def entries(self, res, k):
        return [e for e in res['log']['entries'] if e['k'] == k]

    def sys_texts(self, res):
        return [e['n'] for e in self.entries(res, 'sys')]

    def test_connection_and_names(self):
        cap = pb.parse(full_capture().save(os.path.join(self.d, 'a.log')))
        self.assertEqual([(c.handle, c.addr) for c in cap.conns], [(H1, ADDR), (H2, OTHER)])
        self.assertEqual(cap.names, {ADDR: NAME, OTHER: OTHER_NAME})
        self.assertEqual(cap.records, len(full_capture().recs))

    def test_mtu_and_sys_entries(self):
        res = self.run_convert(full_capture(), keep_ids=True)
        texts = self.sys_texts(res)
        self.assertIn('MTU 247: телефон предложил 517, самокат ответил 247', texts)
        self.assertTrue(texts[0].startswith(f'Подключение к {ADDR} «{NAME}»'))
        self.assertTrue(any(t.startswith('Отключение: 0x13') for t in texts))
        self.assertIn('Канал зашифрован (Encryption Change)', texts)
        self.assertIn('Телефон запускает шифрование канала (LE Start Encryption)', texts)
        # the 'Attribute Not Found' that ends discovery is not an error worth a line
        self.assertFalse(any('Attribute Not Found' in t for t in texts))

    def test_gatt_map(self):
        res = self.run_convert(full_capture())
        gatt = {s['uuid']: s for s in res['log']['gatt']}
        self.assertEqual(set(gatt), {bl.norm_uuid('180a'), FFE0, bl.TELINK_OTA_SVC})
        chars = {c['uuid']: c for c in gatt[FFE0]['chars']}
        self.assertEqual(chars[FFE1]['props'], {'read': True, 'write': False, 'wnr': False, 'notify': True, 'indicate': False})
        self.assertEqual(chars[FFE2]['props'], {'read': False, 'write': True, 'wnr': True, 'notify': False, 'indicate': False})
        ota = gatt[bl.TELINK_OTA_SVC]['chars']
        self.assertEqual([c['uuid'] for c in ota], [bl.TELINK_OTA_CHR])
        self.assertTrue(ota[0]['danger'] and bl.is_danger(bl.TELINK_OTA_SVC, ota[0]['uuid']))
        self.assertTrue(ota[0]['props']['read'] and ota[0]['props']['wnr'])
        g = res['gatt']
        self.assertEqual(g.resolve(0x0013), (FFE0, FFE1, bl.norm_uuid('2902')))
        self.assertEqual(g.uuids[0x0022], bl.TELINK_OTA_CHR)            # Find Information, format 2
        self.assertIn('OTA/DFU, не трогать', res['report'])
        self.assertIn('0xFFE1  0x0012  read notify  CCCD@0x0013', res['report'])

    def test_cccd_write_is_sys_not_tx(self):
        res = self.run_convert(full_capture())
        self.assertIn('Включены уведомления: 0xFFE1', self.sys_texts(res))
        self.assertFalse([e for e in self.entries(res, 'tx') if e['h'] == '01 00'])

    def test_writes(self):
        res = self.run_convert(full_capture())
        tx = self.entries(res, 'tx')
        self.assertEqual([e['h'] for e in tx], [bl.hex_of(x) for x in (HELLO1, HELLO2, CMD, CMD2, b'\xaa\xbb\xcc\xdd')])
        self.assertEqual({(e['s'], e['u']) for e in tx}, {(FFE0, FFE2)})
        self.assertEqual([e['_how'] for e in tx], ['cmd', 'cmd', 'req', 'req', 'prepared'])

    def test_notifications_including_fragmented(self):
        res = self.run_convert(full_capture())
        rx = self.entries(res, 'rx')
        self.assertEqual(len(rx), 7)
        self.assertEqual({(e['s'], e['u']) for e in rx}, {(FFE0, FFE1)})
        self.assertIn(bl.hex_of(LONG), [e['h'] for e in rx])
        self.assertEqual(res['capture'].broken, 0)

    def test_read_and_blob(self):
        res = self.run_convert(full_capture())
        rd = self.entries(res, 'rd')
        self.assertEqual([(e['u'], bl.bytes_of(e['h'])) for e in rd], [(bl.norm_uuid('2a29'), MANUF)])
        self.assertEqual(rd[0]['s'], bl.norm_uuid('180a'))

    def test_att_error_and_smp(self):
        res = self.run_convert(full_capture())
        texts = self.sys_texts(res)
        self.assertIn('Ошибка ATT: Write Req 0xFFE2 → 0x05 Insufficient Authentication — нужна аутентификация (сопряжение)', texts)
        req = [t for t in texts if 'Pairing Request' in t]
        self.assertEqual(len(req), 1)
        self.assertTrue(req[0].startswith('SMP, телефон: запрос сопряжения'))
        self.assertIn('IO «нет ввода и вывода», bonding да, MITM да, SC да', req[0])
        self.assertIn('Just Works', res['report'])
        self.assertIn('LE Secure Connections', res['report'])

    def test_summary_shows_handshake_hints(self):
        rep = self.run_convert(full_capture())['report']
        for part in ('## Что шлёт приложение', bl.hex_of(HELLO1), '## Уведомления самоката', '0xFFE1: 7 шт.',
                     'кандидат на рукопожатие', 'первое уведомление 0xFFE1 — через 10 мс после записи 0xFFE2 ' + bl.hex_of(HELLO2),
                     'меняются байты 4–8', 'Insufficient Authentication', 'канал зашифрован', 'Итог: есть признаки'):
            self.assertIn(part, rep)

    def test_second_connection(self):
        res = self.run_convert(full_capture())
        self.assertEqual([c.handle for c in res['selected']], [H1])
        self.assertIn('← в логе', res['report'])
        res = self.run_convert(full_capture(), conn=H2, keep_ids=True)
        self.assertEqual([c.handle for c in res['selected']], [H2])
        self.assertEqual([(e['s'], e['u']) for e in self.entries(res, 'rx')], [('unknown', 'handle-0x0030')])
        self.assertEqual(res['log']['device'], {'name': OTHER_NAME, 'id': OTHER})
        res = self.run_convert(full_capture(), addr=OTHER)
        self.assertEqual([c.handle for c in res['selected']], [H2])
        with self.assertRaises(ValueError):
            self.run_convert(full_capture(), conn=0x0099)

    def test_anonymized_by_default(self):
        res = self.run_convert(full_capture())
        text = bl.dumps(res['log'])
        self.assertEqual(res['log']['device'], {'name': 'scooter', 'id': 'scooter'})
        for secret in (ADDR, NAME, OTHER, OTHER_NAME, bl.hex_of(NAME.encode())):
            self.assertNotIn(secret, text)
        self.assertNotIn(ADDR, res['report'])
        self.assertIn('**:**:**:**:34:56', res['report'])
        res = self.run_convert(full_capture(), keep_ids=True)
        self.assertEqual(res['log']['device'], {'name': NAME, 'id': ADDR})
        self.assertIn(ADDR, bl.dumps(res['log']))

    def test_missing_discovery(self):
        res = self.run_convert(cached_capture())
        self.assertEqual({(e['s'], e['u']) for e in self.entries(res, 'rx')}, {('unknown', 'handle-0x0012')})
        self.assertEqual([(e['u'], e['h']) for e in self.entries(res, 'tx')], [('handle-0x0015', bl.hex_of(HELLO1))])
        self.assertTrue(any(t.startswith('Включены уведомления: handle-0x0012') for t in self.sys_texts(res)))
        self.assertEqual(res['log']['gatt'], [])
        self.assertTrue(any('кэш' in w and 'Забудь' in w for w in res['warnings']))
        self.assertIn('! обнаружения сервисов в журнале нет', res['report'])

    def test_other_att_operations(self):
        s = cached_capture()
        a = lambda sent, pdu: s.att(H1, sent, pdu)  # noqa: E731
        a(True, b'\x0e' + le16(0x0003, 0x0012))                       # Read Multiple: values cannot be split
        a(False, b'\x0f\xab\xcd\xef')
        a(True, b'\x20' + le16(0x0003, 0x0012))                       # Read Multiple Variable: they can
        a(False, b'\x21' + le16(2) + b'\x01\x02' + le16(1) + b'\x03')
        a(True, b'\x08' + le16(0x0001, 0xFFFF, 0x2A00))               # Read Using Characteristic UUID
        a(False, b'\x09\x07' + le16(0x0007) + b'Scoot')
        a(True, b'\x0a' + le16(0x0016))
        a(False, b'\x01\x0a' + le16(0x0016) + b'\x02')
        a(False, b'\x1d' + le16(0x0012) + b'\x11')                     # Indication + Confirmation
        a(True, b'\x1e')
        a(False, b'\x23' + le16(0x0012, 2) + b'\xaa\xbb' + le16(0x0018, 1) + b'\xcc')
        a(True, b'\xd2' + le16(0x0015) + b'\x42' + bytes(12))          # Signed Write: 12-byte signature
        a(False, b'\x10' + le16(0x0001, 0xFFFF, 0x2800))               # the scooter discovers the phone
        a(True, b'\x11\x06' + le16(0x0001, 0x0005, 0x1800))
        res = self.run_convert(s, keep_ids=True)
        texts = self.sys_texts(res)
        self.assertIn('Чтение нескольких (Read Multiple) handle-0x0003, handle-0x0012: AB CD EF', texts)
        self.assertIn('Ошибка ATT: Read Req handle-0x0016 → 0x02 Read Not Permitted — чтение запрещено', texts)
        rd = [(e['s'], e['u'], e['h']) for e in self.entries(res, 'rd')]
        self.assertEqual(rd, [('unknown', 'handle-0x0003', '01 02'), ('unknown', 'handle-0x0012', '03'),
                              ('unknown', bl.norm_uuid('2a00'), bl.hex_of(b'Scoot'))])
        self.assertEqual([(e['u'], e['h']) for e in self.entries(res, 'rx')][-3:],
                         [('handle-0x0012', '11'), ('handle-0x0012', 'AA BB'), ('handle-0x0018', 'CC')])
        self.assertEqual([(e['h'], e['_how']) for e in self.entries(res, 'tx')][-1], ('42', 'signed'))
        self.assertEqual(res['log']['gatt'], [])                        # the phone's own GATT is not the scooter's
        self.assertEqual(res['log']['device']['name'], 'Scoot')
        self.assertIn('самокат обращался к GATT-серверу телефона: Read By Group Type Req ×1', res['report'])
        self.assertIn('прочих ошибок ATT: 1', res['report'])
        self.assertIn('подписанные записи', res['report'])

    def test_prepared_writes_in_any_order(self):
        s = cached_capture()
        a = lambda sent, pdu: s.att(H1, sent, pdu)  # noqa: E731
        for off, part in ((2, b'\xcc\xdd'), (0, b'\xaa\xbb')):          # the second part first
            a(True, b'\x16' + le16(0x0015, off) + part)
            a(False, b'\x17' + le16(0x0015, off) + part)
        a(True, b'\x18\x01')
        a(False, b'\x19')
        a(True, b'\x16' + le16(0x0015, 0) + b'\xee')                   # cancelled: never written
        a(True, b'\x18\x00')
        tx = self.entries(self.run_convert(s), 'tx')
        self.assertEqual([(e['h'], e['_how']) for e in tx][1:], [('AA BB CC DD', 'prepared')])

    def test_truncated_request_is_not_paired_with_old_one(self):
        # The answer to the first read and the second request are cut: the second answer is not the first read.
        s = cached_capture()
        s.att(H1, True, b'\x0a' + le16(0x0003))
        s.att(H1, False, b'\x0b' + b'Bird Rides', keep=9)
        s.att(H1, True, b'\x0a' + le16(0x0012), keep=8)
        s.att(H1, False, b'\x0b\x55\x66')
        res = self.run_convert(s)
        self.assertEqual(res['capture'].truncated, 2)
        self.assertEqual(self.entries(res, 'rd'), [])

    def test_name_masked_in_sys_lines(self):
        # Read Multiple and descriptor values go into sys lines as hex; anonymize() does not look there.
        s = cached_capture()
        s.advertise(ADDR, NAME)
        s.att(H1, True, b'\x0e' + le16(0x0003, 0x0012))
        s.att(H1, False, b'\x0f' + NAME.encode() + b'\x01')
        res = self.run_convert(s)
        self.assertNotIn(bl.hex_of(NAME.encode()), bl.dumps(res['log']))
        self.assertIn(bl.hex_of(b'*' * len(NAME)) + ' 01', bl.dumps(res['log']))
        self.assertIn(bl.hex_of(NAME.encode()), bl.dumps(self.run_convert(s, keep_ids=True)['log']))

    def test_corrupted_files(self):
        # An impossible record length ends the reading with a warning instead of a huge allocation.
        s = full_capture()
        s.recs.append(struct.pack('>IIIIq', 0xFFFFFFF0, 0xFFFFFFF0, 1, 0, s.us + EPOCH_DELTA) + b'\x02\x40\x20')
        res = self.run_convert(s)
        self.assertEqual(len(self.entries(res, 'rx')), 7)
        self.assertTrue(any('обрывается после записи' in w for w in res['warnings']))
        # A timestamp outside any sane clock is a clear error, not an OverflowError traceback.
        s = full_capture()
        s.recs[3] = s.recs[3][:16] + struct.pack('>q', 0) + s.recs[3][24:]
        with self.assertRaisesRegex(ValueError, 'запись 4: время'):
            self.run_convert(s)

    def test_datalink_1001(self):
        want = self.run_convert(full_capture(1002))['log']['entries']
        got = self.run_convert(full_capture(1001))['log']['entries']
        self.assertEqual(self.run_convert(full_capture(1001))['capture'].link, 1001)
        self.assertEqual([(e['t'], e['k'], e.get('u'), e.get('h'), e.get('n')) for e in got],
                         [(e['t'], e['k'], e.get('u'), e.get('h'), e.get('n')) for e in want])

    def test_truncated_records_warn(self):
        res = self.run_convert(full_capture(truncated=True))
        self.assertEqual(res['capture'].truncated, 1)
        self.assertTrue(any('Включено с фильтром' in w and 'Enabled Filtered' in w for w in res['warnings']))
        self.assertEqual(len(self.entries(res, 'rx')), 7)            # the cut notification is dropped
        self.assertFalse(self.run_convert(full_capture())['warnings'])

    def test_output_loads_and_analyzes(self):
        res = self.run_convert(full_capture())
        lg = bl.load_file(res['path'])
        self.assertEqual(lg['format'], 'birdair-lab-log')
        self.assertTrue(lg['startedAt'].startswith('2026-10-14T16:00:00.'))
        self.assertEqual(lg['entries'][0]['t'], 0)
        self.assertEqual([e['t'] for e in lg['entries']], sorted(e['t'] for e in lg['entries']))
        self.assertFalse([k for e in lg['entries'] for k in e if k.startswith('_')])
        buf = io.StringIO()
        self.assertIsNotNone(analyze_log.analyze([res['path']], None, write_candidate=False, stream=buf))
        self.assertIn('0xFFE1', buf.getvalue())

    def test_bugreport_zip(self):
        log = full_capture().save(os.path.join(self.d, 'snoop.bin'))
        z = os.path.join(self.d, 'bugreport.zip')
        with zipfile.ZipFile(z, 'w') as zf:
            zf.writestr('bugreport.txt', 'dumpstate')
            zf.write(log, 'FS/data/misc/bluetooth/logs/btsnoop_hci.log')
        res = pb.convert(z, out=os.path.join(self.d, 'z.json'), quiet=True, stream=io.StringIO())
        self.assertEqual(len(self.entries(res, 'rx')), 7)
        self.assertIn('FS/data/misc/bluetooth/logs/btsnoop_hci.log', res['capture'].source)

    def test_not_a_btsnoop(self):
        p = os.path.join(self.d, 'x.log')
        with open(p, 'wb') as fh:
            fh.write(b'hello world, not a snoop log')
        with self.assertRaises(ValueError):
            pb.parse(p)

    def test_default_out_name(self):
        us = int((T0 - pb.UNIX_EPOCH).total_seconds() * 1e6)
        self.assertRegex(pb.default_out(us), r'logs[/\\]\d{8}-\d{6}-btsnoop\.json$')
        self.assertTrue(pb.default_out(us, 'Bird app').startswith(os.path.join(ROOT, 'logs', '')))
        self.assertTrue(pb.default_out(us, 'Bird app').endswith('-bird-app.json'))

    def test_cli_smoke(self):
        src = full_capture().save(os.path.join(self.d, 'btsnoop_hci.log'))
        out = os.path.join(self.d, 'sub', 'app.json')
        script = os.path.join(ROOT, 'tools', 'parse_btsnoop.py')
        r = subprocess.run([sys.executable, script, src, '-o', out, '--label', 'bird-app'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('## GATT самоката', r.stdout)
        self.assertIn('Записан лог: ' + out, r.stdout)
        self.assertEqual(len([e for e in bl.load_file(out)['entries'] if e['k'] == 'rx']), 7)
        r = subprocess.run([sys.executable, script, src, '-o', out, '--quiet', '--conn', '0x0041'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn('##', r.stdout)
        r = subprocess.run([sys.executable, script, os.path.join(self.d, 'missing.log'), '-o', out], capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn('Ошибка', r.stderr)


if __name__ == '__main__':
    unittest.main()
