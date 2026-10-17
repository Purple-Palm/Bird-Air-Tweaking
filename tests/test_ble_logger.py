"""Tests for tools/ble_logger.py with a fake bleak client.  python3 -m unittest discover -s tests"""
import asyncio
import collections
import datetime
import io
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TOOLS = os.path.join(ROOT, 'tools')
sys.path.insert(0, TOOLS)

import ble_logger as bt  # noqa: E402
import birdlog as bl  # noqa: E402

ADDR = 'AA:BB:CC:DD:EE:FF'
NAME = 'BirdAir-VA0020'
FFE0, FFE1, DIS, MFR = (bl.norm_uuid(x) for x in ('ffe0', 'ffe1', '180a', '2a29'))
OTA_SVC, OTA_CHR = bl.TELINK_OTA_SVC, bl.TELINK_OTA_CHR
PAGE_MARKERS = ['Газ', 'Отпустил газ', 'Тормоз', 'Стоп', 'Сменил режим', 'Фара', 'Зарядка вкл', 'Зарядка выкл']


class Forbidden(BaseException):
    """Not an Exception, so the logger's error handling cannot swallow it: the test fails loudly."""


FakeDevice = collections.namedtuple('FakeDevice', 'address name')
FakeChar = collections.namedtuple('FakeChar', 'uuid properties service_uuid')
FakeService = collections.namedtuple('FakeService', 'uuid characteristics')
FakeAdv = collections.namedtuple('FakeAdv', 'local_name rssi service_uuids')


def fake_services():
    def svc(s, *chars):
        return FakeService(s, [FakeChar(u, props, s) for u, props in chars])
    return [
        svc(FFE0, (FFE1, ['write-without-response', 'notify'])),
        svc(DIS, (MFR, ['read'])),
        svc(OTA_SVC, (OTA_CHR, ['read', 'write', 'write-without-response', 'notify'])),
    ]


class FakeClient:
    """bleak.BleakClient look-alike that records what the logger asks of it."""

    def __init__(self, device, disconnected_callback=None):
        self.device, self.on_gone = device, disconnected_callback
        self.services = fake_services()
        self.connected = False
        self.reads, self.subscribed, self.handlers = [], [], {}

    async def connect(self):
        self.connected = True

    async def disconnect(self):
        if self.connected:
            self.drop()

    def drop(self):
        """The link is lost (scooter out of range)."""
        self.connected = False
        self.on_gone(self)

    async def read_gatt_char(self, ch):
        if bl.is_danger(ch.service_uuid, ch.uuid):
            raise Forbidden('read of an OTA characteristic')
        self.reads.append(ch.uuid)
        return bytearray(b'Bird Rides')

    async def start_notify(self, ch, callback):
        if bl.is_danger(ch.service_uuid, ch.uuid):
            raise Forbidden('subscribed to an OTA characteristic')
        self.subscribed.append(ch.uuid)
        self.handlers[ch.uuid] = (ch, callback)

    def send(self, uuid, data):
        ch, callback = self.handlers[uuid]
        callback(ch, bytearray(data))

    async def write_gatt_char(self, *a, **kw):
        raise Forbidden('write_gatt_char')

    async def write_gatt_descriptor(self, *a, **kw):
        raise Forbidden('write_gatt_descriptor')


class Rig:
    """find() and a client factory for Session/record; clients[-1] is the current link."""

    def __init__(self):
        self.clients = []
        self.rec = bt.Recorder(started='2026-10-14T19:00:00.000Z')

    async def find(self):
        return FakeDevice(ADDR, NAME)

    def make(self, device, disconnected_callback=None):
        c = FakeClient(device, disconnected_callback)
        self.clients.append(c)
        return c

    def entries(self, k):
        return [e for e in self.rec.log['entries'] if e['k'] == k]


async def until(cond, timeout=2.0):
    end = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > end:
            raise AssertionError('timed out')
        await asyncio.sleep(0.005)


class SessionTest(unittest.IsolatedAsyncioTestCase):
    async def start(self, read=True):
        self.rig = Rig()
        self.stop = asyncio.Event()
        self.sess = bt.Session(self.rig.rec, self.rig.find, self.rig.make, self.stop, read=read, backoff=(0.01, 0.05))
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: self.sess.state == bt.LIVE)
        return self.rig

    async def asyncTearDown(self):
        self.stop.set()
        await asyncio.wait_for(self.task, 2)

    async def test_reads_and_subscribes_but_leaves_ota_alone(self):
        rig = await self.start()
        c = rig.clients[0]
        self.assertEqual(c.subscribed, [FFE1])
        self.assertEqual(c.reads, [MFR])
        self.assertEqual([(e['s'], e['u'], e['h']) for e in rig.entries('rd')], [(DIS, MFR, bl.hex_of(b'Bird Rides'))])
        self.assertTrue(any(OTA_CHR in e['n'] for e in rig.entries('sys')), 'OTA skip is not mentioned')
        self.assertFalse([e for e in rig.rec.log['entries'] if e.get('u') == OTA_CHR])
        gatt = {s['uuid']: {c['uuid']: c['props'] for c in s['chars']} for s in rig.rec.log['gatt']}
        self.assertEqual(list(gatt), [FFE0, DIS, OTA_SVC])
        self.assertEqual(gatt[FFE0][FFE1], {'read': False, 'write': False, 'wnr': True, 'notify': True, 'indicate': False})
        self.assertTrue(gatt[OTA_SVC][OTA_CHR]['write'])

    async def test_notifications_become_rx(self):
        rig = await self.start()
        rig.clients[0].send(FFE1, b'\x5a\xa5\x0b\x25')
        rx = rig.entries('rx')
        self.assertEqual(len(rx), 1)
        self.assertEqual((rx[0]['s'], rx[0]['u'], rx[0]['h']), (FFE0, FFE1, '5A A5 0B 25'))
        self.assertIsInstance(rx[0]['t'], int)
        self.assertEqual(rig.rec.packets, 1)

    async def test_reconnect_does_not_duplicate(self):
        rig = await self.start()
        old = rig.clients[0]
        old.send(FFE1, b'\x01')
        old.drop()
        await until(lambda: len(rig.clients) == 2 and self.sess.state == bt.LIVE)
        new = rig.clients[1]
        old.send(FFE1, b'\x02')          # a handler of the dead link must stay quiet
        new.send(FFE1, b'\x03')
        new.send(FFE1, b'\x04')
        self.assertEqual([e['h'] for e in rig.entries('rx')], ['01', '03', '04'])
        self.assertEqual((old.subscribed, new.subscribed), ([FFE1], [FFE1]))
        self.assertEqual(len(rig.entries('rd')), 2)                  # read once after each connect
        texts = [e['n'] for e in rig.entries('sys')]
        self.assertTrue(any(t.startswith('Связь потеряна') for t in texts), texts)
        self.assertTrue(any('переподключение 1' in t for t in texts), texts)

    async def test_no_read(self):
        rig = await self.start(read=False)
        self.assertEqual(rig.clients[0].reads, [])
        self.assertEqual(rig.entries('rd'), [])
        self.assertEqual(rig.clients[0].subscribed, [FFE1])

    async def test_device_not_found_retries(self):
        rig = Rig()
        misses = []

        async def find():
            misses.append(1)
            return None if len(misses) < 5 else FakeDevice(ADDR, NAME)
        self.stop = asyncio.Event()
        self.sess = bt.Session(rig.rec, find, rig.make, self.stop, backoff=(0.01, 0.04))
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: self.sess.state == bt.LIVE)
        self.assertEqual(len(misses), 5)
        self.assertEqual(sum(e['n'].startswith('Устройство не найдено') for e in rig.entries('sys')), 4)
        rig.clients[0].drop()
        await until(lambda: len(rig.clients) == 2 and self.sess.state == bt.LIVE)
        # doubles up to the max, back to the first pause after a link that worked
        delays = [re.search(r'Повтор через (\S+) с', e['n']).group(1) for e in rig.entries('sys') if 'Повтор' in e['n']]
        self.assertEqual(delays, ['0.01', '0.02', '0.04', '0.04', '0.01'])

    async def test_reconnect_without_a_name_stays_anonymous(self):
        rig, names = Rig(), [NAME]

        async def find():            # the second scan misses the scan response that carries the name
            return FakeDevice(ADDR, names.pop() if names else None)
        self.stop = asyncio.Event()
        self.sess = bt.Session(rig.rec, find, rig.make, self.stop, backoff=(0.01, 0.02))
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: self.sess.state == bt.LIVE)
        rig.clients[0].drop()
        await until(lambda: len(rig.clients) == 2 and self.sess.state == bt.LIVE)
        self.assertEqual(rig.rec.log['device'], {'name': ADDR, 'id': ADDR})
        self.assertTrue(any(NAME in e['n'] for e in rig.entries('sys')))     # «Подключено: BirdAir-…»
        self.assertNotIn(NAME, json.dumps(rig.rec.snapshot(), ensure_ascii=False))

    async def test_address_in_bleak_errors_is_hidden(self):
        rig = Rig()

        class NoLink(FakeClient):
            async def connect(self):     # BlueZ wordings: the address and the device's D-Bus path
                raise Exception(f"Device with address {ADDR} was not found. device 'dev_AA_BB_CC_DD_EE_FF' not found")
        self.stop = asyncio.Event()
        self.sess = bt.Session(rig.rec, rig.find, NoLink, self.stop, backoff=(0.01, 0.02))
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: rig.entries('sys'))
        rig.rec.mark('Газ')              # so the log has data and would be saved
        self.assertIn(ADDR, rig.entries('sys')[0]['n'])
        saved = json.dumps(rig.rec.snapshot(), ensure_ascii=False)
        for secret in (ADDR, 'AA_BB_CC_DD_EE_FF'):
            self.assertNotIn(secret, saved)

    async def test_quit_during_a_scan(self):
        rig, cancelled = Rig(), []

        async def find():                # a real scan lasts --timeout seconds
            try:
                await asyncio.sleep(30)
            except asyncio.CancelledError:
                cancelled.append(1)
                raise
        self.stop = asyncio.Event()
        self.sess = bt.Session(rig.rec, find, rig.make, self.stop)
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: self.sess.state == 'ищу')
        self.stop.set()
        await asyncio.wait_for(self.task, 0.5)
        self.assertEqual((cancelled, rig.clients), ([1], []))

    async def test_failed_subscriptions_are_reported(self):
        class NoNotify(FakeClient):
            async def start_notify(self, ch, callback):
                await super().start_notify(ch, callback)     # still refuses OTA
                raise Exception('Not permitted')
        rig = Rig()
        self.stop = asyncio.Event()
        self.sess = bt.Session(rig.rec, rig.find, NoNotify, self.stop, backoff=(0.01, 0.02))
        self.task = asyncio.ensure_future(self.sess.run())
        await until(lambda: self.sess.state == bt.LIVE)
        texts = [e['n'] for e in rig.entries('sys')]
        self.assertIn('Подписка на 0xFFE1 не удалась: Not permitted', texts)
        self.assertEqual(texts[-1], 'Подписок нет: ни одна не удалась')


class RecordTest(unittest.IsolatedAsyncioTestCase):
    """record(): autosave, keys, quit and the final save."""

    async def run_recording(self, keep_ids):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out = os.path.join(tmp.name, 'pc.json.gz')
        rig = Rig()
        rig.rec.keep_ids = keep_ids
        got = {}

        def listen(keys, loop):
            got['keys'] = keys
            return lambda: got.setdefault('stopped', True)
        task = asyncio.ensure_future(bt.record(rig.rec, out, rig.find, rig.make, autosave_every=0.05, backoff=(0.01, 0.05),
                                               console=bt.Console(io.StringIO()), listen=listen))
        await until(lambda: rig.clients and rig.clients[-1].subscribed)
        rig.clients[-1].send(FFE1, b'\x5a\xa5\x01')
        await until(lambda: os.path.exists(out))
        autosaved = bl.load_file(out)
        self.assertTrue(any(e['k'] == 'rd' for e in autosaved['entries']))
        for ch in '13m' + 'Сво\x7fё x' + '\r' + 'q':
            got['keys'].feed(ch)
        await asyncio.wait_for(task, 2)
        self.assertTrue(got.get('stopped'))
        return rig, out, autosaved

    @staticmethod
    def text_of(path):
        return json.dumps(bl.read_json_bytes(path), ensure_ascii=False)

    async def test_anonymized_by_default(self):
        rig, out, autosaved = await self.run_recording(keep_ids=False)
        self.assertEqual(autosaved['device'], {'name': 'scooter', 'id': 'scooter'})
        final = bl.load_file(out)
        self.assertEqual([e['n'] for e in final['entries'] if e['k'] == 'mk'], ['Газ', 'Тормоз', 'Свё x'])
        self.assertEqual([e['h'] for e in final['entries'] if e['k'] == 'rx'], ['5A A5 01'])
        self.assertEqual(final['entries'][-1]['n'], 'Запись остановлена')
        for secret in (ADDR, ADDR.lower(), ADDR.replace(':', '-'), NAME):
            self.assertNotIn(secret, self.text_of(out))
        self.assertEqual(rig.rec.log['device'], {'name': NAME, 'id': ADDR})   # in memory: real ids
        self.assertTrue(final['startedAt'].endswith('Z'))

    async def test_keep_ids(self):
        rig, out, autosaved = await self.run_recording(keep_ids=True)
        self.assertEqual(autosaved['device'], {'name': NAME, 'id': ADDR})
        text = self.text_of(out)
        self.assertIn(ADDR, text)
        self.assertIn(NAME, text)

    async def test_silent_link_still_saves_the_gatt(self):
        # --no-read and a scooter that says nothing until asked: the GATT table alone is worth keeping
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out, rig, got = os.path.join(tmp.name, 'pc.json'), Rig(), {}
        task = asyncio.ensure_future(bt.record(rig.rec, out, rig.find, rig.make, read=False, autosave_every=60,
                                               console=bt.Console(io.StringIO()), listen=lambda keys, loop: got.update(keys=keys)))
        await until(lambda: rig.clients and rig.clients[-1].subscribed)
        got['keys'].feed('q')
        await asyncio.wait_for(task, 2)
        log = bl.load_file(out)
        self.assertEqual([s['uuid'] for s in log['gatt']], [FFE0, DIS, OTA_SVC])
        self.assertEqual({e['k'] for e in log['entries']}, {'sys'})

    async def test_never_connected_writes_no_file(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out, rig, got = os.path.join(tmp.name, 'pc.json'), Rig(), {}

        async def find():                # nobody around, and the user gives up
            got['keys'].feed('q')
            return None
        task = asyncio.ensure_future(bt.record(rig.rec, out, find, rig.make, console=bt.Console(io.StringIO()),
                                               listen=lambda keys, loop: got.update(keys=keys)))
        await asyncio.wait_for(task, 2)
        self.assertFalse(os.path.exists(out))

    @unittest.skipUnless(hasattr(signal, 'SIGHUP'), 'Unix signals')
    async def test_closed_terminal_still_saves(self):
        # closing the window: SIGHUP, and every write to the terminal fails with EIO
        class DeadTerminal(io.StringIO):
            def isatty(self):
                return True

            def write(self, s):
                raise OSError(5, 'Input/output error')
        old = signal.signal(signal.SIGHUP, lambda *a: None)   # if record() ignored SIGHUP, it would kill the test run
        self.addCleanup(signal.signal, signal.SIGHUP, old)
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        out = os.path.join(tmp.name, 'pc.json')
        rig = Rig()
        console = bt.Console(DeadTerminal())
        rig.rec.echo = console.say
        task = asyncio.ensure_future(bt.record(rig.rec, out, rig.find, rig.make, autosave_every=60, console=console))
        await until(lambda: rig.clients and rig.clients[-1].subscribed)
        rig.clients[-1].send(FFE1, b'\x5a\xa5\x01')
        os.kill(os.getpid(), signal.SIGHUP)
        await asyncio.wait_for(task, 2)
        self.assertEqual([e['h'] for e in bl.load_file(out)['entries'] if e['k'] == 'rx'], ['5A A5 01'])


class KeysTest(unittest.TestCase):
    def setUp(self):
        self.rec = bt.Recorder()
        self.quits = []
        self.keys = bt.Keys(self.rec, bt.Console(io.StringIO()), lambda: self.quits.append(1))

    def marks(self):
        return [e['n'] for e in self.rec.log['entries'] if e['k'] == 'mk']

    def test_hotkeys_use_the_page_labels(self):
        with open(os.path.join(ROOT, 'assets', 'app.js'), encoding='utf-8') as fh:
            m = re.search(r'const MARKERS = \[([^\]]*)\]', fh.read())
        self.assertEqual(re.findall(r"'([^']*)'", m.group(1)), PAGE_MARKERS)
        for ch in '12345678':
            self.keys.feed(ch)
        self.assertEqual(self.marks(), PAGE_MARKERS)
        self.assertEqual(self.rec.markers, 8)

    def test_own_label(self):
        for ch in 'mАБ\x7fВ \n':
            self.keys.feed(ch)
        for ch in 'ьsoon\x1b':          # Esc drops it; ь is m on the Russian layout
            self.keys.feed(ch)
        for ch in 'm  \r':               # blank: nothing
            self.keys.feed(ch)
        self.assertEqual(self.marks(), ['АВ'])
        self.assertIsNone(self.keys.line)
        self.assertEqual(self.quits, [])

    def test_quit(self):
        self.keys.feed('q')
        self.keys.feed('й')
        self.assertEqual(len(self.quits), 2)
        self.assertEqual(self.marks(), [])

    def test_piped_lines(self):
        for line in ('Тормоз\n', '3\n', '\n', '  своя  \n', 'q\n'):
            self.keys.line_in(line)
        self.assertEqual(self.marks(), ['Тормоз', 'Тормоз', 'своя'])
        self.assertEqual(self.quits, [1])


@unittest.skipUnless(bt.termios, 'Unix terminal')
class TerminalTest(unittest.IsolatedAsyncioTestCase):
    async def test_raw_keys_skip_escape_sequences(self):
        rec = bt.Recorder()
        keys = bt.Keys(rec, bt.Console(io.StringIO()), lambda: None)
        r, w = os.pipe()
        self.addCleanup(os.close, r)
        stdin = types.SimpleNamespace(fileno=lambda: r, encoding='utf-8')
        stop = bt.listen_keys(keys, asyncio.get_running_loop(), True, stdin)

        def marks():
            return [e['n'] for e in rec.log['entries'] if e['k'] == 'mk']
        try:
            # Delete, F5, F1, an arrow, Alt+1, then 1; an own marker dropped by a lone Esc; another one kept
            for chunk in ('\x1b[3~', '\x1b[15~', '\x1bOP', '\x1b[A', '\x1b1', '1', 'mтест', '\x1b', 'm', 'ок\r'):
                os.write(w, chunk.encode())
                await asyncio.sleep(0.01)    # one read per key, as from a terminal
            await until(lambda: 'ок' in marks())
        finally:
            stop()
            os.close(w)
        self.assertEqual(marks(), ['Газ', 'ок'])

    def test_cbreak_restores_the_terminal_on_errors(self):
        import pty
        termios = bt.termios
        master, slave = pty.openpty()
        self.addCleanup(os.close, master)
        with open(slave, 'r', encoding='utf-8') as stdin:
            before = termios.tcgetattr(slave)
            with self.assertRaises(ZeroDivisionError):
                with bt.cbreak(stdin) as raw:
                    self.assertTrue(raw)
                    self.assertFalse(termios.tcgetattr(slave)[3] & termios.ICANON)
                    1 / 0
            self.assertEqual(termios.tcgetattr(slave), before)


class PartsTest(unittest.TestCase):
    def test_times_are_ms_since_start(self):
        now = [100.0]
        rec = bt.Recorder(started=datetime.datetime(2026, 10, 14, 19, 2, 11, tzinfo=datetime.timezone.utc), clock=lambda: now[0])
        now[0] = 100.25
        rec.mark('Газ')
        self.assertEqual(rec.log['entries'][0]['t'], 250)
        self.assertEqual(rec.log['startedAt'], '2026-10-14T19:02:11.000Z')

    def test_default_out(self):
        started = datetime.datetime(2026, 10, 14, 19, 2, 11, tzinfo=datetime.timezone.utc)
        p = bt.default_out(started)
        self.assertEqual(os.path.dirname(p), os.path.join(ROOT, 'logs'))
        self.assertRegex(os.path.basename(p), r'^\d{8}-\d{6}-pc\.json$')
        self.assertRegex(bt.default_out(started, 'Газ', gz=True), r'-gaz\.json\.gz$')

    def test_scan_lines_strongest_first(self):
        found = {
            'A': (FakeDevice('11:11:11:11:11:11', None), FakeAdv(None, -80, [])),
            'B': (FakeDevice('22:22:22:22:22:22', 'BirdAir'), FakeAdv('BirdAir', -41, [FFE0])),
            'C': (FakeDevice('33:33:33:33:33:33', None), FakeAdv('Watch', -60, [])),
        }
        lines = bt.scan_lines(found)
        self.assertEqual([ln.split()[2] for ln in lines], ['22:22:22:22:22:22', '33:33:33:33:33:33', '11:11:11:11:11:11'])
        self.assertIn('BirdAir', lines[0])
        self.assertIn('0xFFE0', lines[0])
        self.assertIn('Watch', lines[1])

    def test_no_gatt_writes_in_the_source(self):
        with open(os.path.join(TOOLS, 'ble_logger.py'), encoding='utf-8') as fh:
            self.assertNotIn('write_gatt', fh.read())

    def test_works_without_bleak(self):
        code = ('import sys; sys.path.insert(0, %r); import ble_logger; print("bleak" in sys.modules); '
                'sys.modules["bleak"] = None; sys.exit(ble_logger.main(sys.argv[1:]))') % TOOLS
        for args in (['--name', 'Bird'], ['--scan']):
            r = subprocess.run([sys.executable, '-c', code, *args], capture_output=True, text=True, timeout=30)
            self.assertEqual(r.returncode, 2, r.stderr)
            self.assertEqual(r.stdout.strip(), 'False')      # importing the module does not import bleak
            self.assertIn('pip install bleak', r.stderr)


if __name__ == '__main__':
    unittest.main()
