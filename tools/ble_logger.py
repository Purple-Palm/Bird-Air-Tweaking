#!/usr/bin/env python3
"""Логгер Bluetooth для ПК: запасной путь, если страница в браузере не подключается.

    pip install bleak
    python3 tools/ble_logger.py --scan                        # что вокруг: RSSI, адрес, имя, сервисы
    python3 tools/ble_logger.py --name Bird                   # первое устройство с именем «Bird…»
    python3 tools/ble_logger.py --address AA:BB:CC:DD:EE:FF --label газ --gz

Только читает и подписывается: в самокат не пишет ничего, характеристики обновления
прошивки (OTA/DFU, birdlog.is_danger) не трогает. После каждого (пере)подключения один раз
читает всё, что читается, и подписывается на всё, что шлёт уведомления. Связь пропала —
переподключается сам (пауза 1 → 10 с).

Метки — клавишами, как кнопки на странице: 1 Газ, 2 Отпустил газ, 3 Тормоз, 4 Стоп,
5 Сменил режим, 6 Фара, 7 Зарядка вкл, 8 Зарядка выкл; m — своя метка (текст и Enter),
q — сохранить и выйти. Если stdin не терминал, каждая строка — метка (1–8 тоже работают).

Лог (формат birdair-lab-log v1, logs/README.md) сохраняется каждые 15 с и при выходе,
в том числе по Ctrl+C и при закрытии окна терминала. Репозиторий публичный, поэтому файл обезличен (birdlog.anonymize):
имя и адрес → «scooter». --keep-ids оставляет настоящие — такой лог не выкладывай.

Стандартная библиотека и bleak (импортируется, только когда нужен Bluetooth).
"""
import argparse
import asyncio
import codecs
import contextlib
import datetime
import os
import platform
import re
import signal
import sys
import threading
import time

try:
    import termios
    import tty
except ImportError:      # Windows
    termios = tty = None
try:
    import msvcrt
except ImportError:      # Unix
    msvcrt = None

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MARKERS = ['Газ', 'Отпустил газ', 'Тормоз', 'Стоп', 'Сменил режим', 'Фара', 'Зарядка вкл', 'Зарядка выкл']  # as on the page
HOTKEYS = {str(i): label for i, label in enumerate(MARKERS, 1)}
MAX_LABEL = 60           # the page's own-marker input has maxlength 60
AUTOSAVE_S = 15.0
BACKOFF = (1.0, 10.0)    # reconnect pause: first, max (doubles in between)
SCAN_S = 10.0
LIVE = 'связь есть'
PROMPT = 'своя метка: '      # with MAX_LABEL it still fits an 80-column line
HELP_KEYS = ('Метки: ' + ' · '.join(f'{k} {v}' for k, v in HOTKEYS.items())
             + ' · m своя (Enter — готово, Esc — отмена) · q сохранить и выйти')
HELP_LINES = 'Метки — строками в stdin (текст или 1–8, как клавиши); q — сохранить и выйти.'
# Arrows, Delete, PgUp, F-keys, Alt+key: Delete is ESC [ 3 ~, and its 3 must not become «Тормоз».
# A lone Esc (cancel an own marker) stays.
ESC_SEQ = re.compile(r'\x1b(\[[0-?]*[ -/]*[@-~]|O.|[^\x1b])')


def uuid_of(u):
    return bl.norm_uuid(u) or str(u).lower()


def props_of(names):
    """bleak property names -> the page's {read, write, wnr, notify, indicate}."""
    names = set(names or ())
    return {'read': 'read' in names, 'write': 'write' in names, 'wnr': 'write-without-response' in names,
            'notify': 'notify' in names, 'indicate': 'indicate' in names}


def err_text(ex):
    return str(ex) or type(ex).__name__


def rel(path):
    try:
        r = os.path.relpath(path, ROOT)
    except ValueError:   # another drive on Windows
        return path
    return path if r.startswith('..') else r


def default_out(started, label=None, gz=False):
    return os.path.join(ROOT, 'logs', bl.log_name(started, label or 'pc', '.json.gz' if gz else '.json'))


# ---------------------------------------------------------------- the log

class Recorder:
    """The log being written. Real ids stay here; snapshot() and save() give the anonymized copy."""

    def __init__(self, started=None, keep_ids=False, clock=time.monotonic, echo=None):
        self.clock, self.t0 = clock, clock()
        self.log = bl.new_log(started_at=started)
        self.keep_ids, self.echo = keep_ids, echo
        self.secrets = []      # spellings of the address, for anonymize(extra=...)
        self.packets = self.reads = self.markers = 0
        self.saved = 0         # entries at the last save

    def now(self):
        return int(round((self.clock() - self.t0) * 1000))

    def _add(self, text=None, **e):
        e = {'t': self.now(), **e}
        self.log['entries'].append(e)
        if text and self.echo:
            self.echo('%7.1f с  %s' % (e['t'] / 1000, text))

    def on_notify(self, service, char, data):
        self.packets += 1
        self._add(k='rx', s=service, u=char, h=bl.hex_of(data))

    def add_read(self, service, char, data):
        self.reads += 1
        self._add(k='rd', s=service, u=char, h=bl.hex_of(data))

    def mark(self, label):
        self.markers += 1
        self._add(f'метка «{label}»', k='mk', n=label)

    def sys(self, text):
        self._add(text, k='sys', n=text)

    def set_device(self, name, address):
        self.log['device'] = {'name': name or address, 'id': address}
        # names too: a later scan may come back without one, and the old name stays in sys entries;
        # AA_BB_... is how BlueZ paths in bleak's errors spell the address
        spellings = [name] if name else []
        for a in (address, address.upper(), address.lower()):
            spellings += [a, a.replace(':', '-'), a.replace(':', '_')]
        for v in spellings:
            if v not in self.secrets:
                self.secrets.append(v)

    @property
    def has_data(self):
        # a link with a silent scooter (--no-read) still gave its GATT table; a run that never connected did not
        return bool(self.packets or self.reads or self.markers or self.log['gatt'])

    @property
    def dirty(self):
        return len(self.log['entries']) != self.saved

    def snapshot(self):
        # anonymize() only rewrites 'device' and fields of entries, so shallow copies are enough
        out = dict(self.log, device=dict(self.log['device']), entries=[dict(e) for e in self.log['entries']])
        return out if self.keep_ids else bl.anonymize(out, extra=self.secrets)

    def save(self, path):
        n = len(self.log['entries'])
        bl.save_log(self.snapshot(), path)
        self.saved = n


# ---------------------------------------------------------------- Bluetooth

async def pause(stop, delay):
    """Sleep up to `delay` seconds; True if `stop` was set meanwhile."""
    try:
        await asyncio.wait_for(stop.wait(), delay)
    except asyncio.TimeoutError:
        pass
    return stop.is_set()


async def wait_any(*events):
    waits = [asyncio.ensure_future(e.wait()) for e in events]
    try:
        await asyncio.wait(waits, return_when=asyncio.FIRST_COMPLETED)
    finally:
        for w in waits:
            w.cancel()


async def until_stop(stop, coro):
    """Await `coro`, but give up (None) as soon as `stop` is set: q must not wait out a 10 s scan."""
    task, stopped = asyncio.ensure_future(coro), asyncio.ensure_future(stop.wait())
    try:
        await asyncio.wait([task, stopped], return_when=asyncio.FIRST_COMPLETED)
    finally:
        stopped.cancel()
        if not task.done():
            task.cancel()
            await asyncio.wait([task])     # let the scanner stop scanning
    return None if task.cancelled() else task.result()


class Session:
    """Connects, reads, subscribes and reconnects until `stop` is set. Never writes anything.

    find() -> device or None (one scan); make_client(device, disconnected_callback=f) -> client
    with the bleak.BleakClient interface. Tests pass fakes for both.
    """

    def __init__(self, rec, find, make_client, stop, read=True, backoff=BACKOFF):
        self.rec, self.find, self.make_client, self.stop = rec, find, make_client, stop
        self.read, self.backoff = read, backoff
        self.gen = 0       # bumped on every connect and disconnect; handlers of an old link check it
        self.links = 0     # successful connections so far
        self.state = 'старт'

    async def run(self):
        lo, hi = self.backoff
        delay = lo
        while not self.stop.is_set():
            try:
                had_link = await self.connect_once()
                why = 'Связь потеряна' if had_link else 'Устройство не найдено'
            except Exception as ex:   # bleak, OS and timeout errors differ per platform
                had_link, why = False, 'Не удалось подключиться: ' + err_text(ex)
            if self.stop.is_set():
                break
            if had_link:
                delay = lo
            self.rec.sys(f'{why}. Повтор через {delay:g} с')
            self.state = f'жду {delay:g} с'
            if await pause(self.stop, delay):
                break
            delay = min(delay * 2, hi)
        self.state = 'остановлено'

    async def connect_once(self):
        """Find, connect, read, subscribe, wait for the link to drop. False: device not found (or stopped)."""
        self.state = 'ищу'
        device = await until_stop(self.stop, self.find())
        if device is None or self.stop.is_set():
            return False
        name, address = getattr(device, 'name', None), str(getattr(device, 'address', device))
        self.rec.set_device(name, address)     # before connect: its errors name the address
        loop = asyncio.get_running_loop()
        gone = asyncio.Event()

        def on_gone(_client):
            try:
                loop.call_soon_threadsafe(gone.set)
            except RuntimeError:     # the loop is already closed on exit
                pass

        self.state = 'подключаюсь'
        client = self.make_client(device, disconnected_callback=on_gone)
        await client.connect()
        self.gen += 1
        self.links += 1
        try:
            if self.links == 1:
                self.rec.sys('Подключено: ' + (f'{name} ({address})' if name else address))
            else:
                self.rec.sys(f'Подключено снова (переподключение {self.links - 1})')
            await self.setup(client, self.gen)
            self.state = LIVE
            await wait_any(gone, self.stop)
        finally:
            self.gen += 1
            if not gone.is_set():
                with contextlib.suppress(Exception):
                    await asyncio.wait_for(client.disconnect(), 5)
        return True

    async def setup(self, client, gen):
        gatt, readable, notifying, skipped = [], [], [], []
        for svc in client.services:
            s = uuid_of(svc.uuid)
            chars = []
            for ch in svc.characteristics:
                u, props = uuid_of(ch.uuid), props_of(ch.properties)
                chars.append({'uuid': u, 'props': props})
                if bl.is_danger(s, u):
                    skipped.append(u)
                    continue
                if props['read']:
                    readable.append((s, u, ch))
                if props['notify'] or props['indicate']:
                    notifying.append((s, u, ch))
            gatt.append({'uuid': s, 'chars': chars})
        self.rec.log['gatt'] = gatt
        self.rec.sys(f'Найдено сервисов: {len(gatt)}, характеристик: {sum(len(x["chars"]) for x in gatt)}')
        if skipped:
            self.rec.sys('Не трогаем (обновление прошивки): ' + ', '.join(map(bl.short_uuid, skipped)))
        if self.read:
            for s, u, ch in readable:
                try:
                    data = await client.read_gatt_char(ch)
                except Exception as ex:
                    self.rec.sys(f'Чтение {bl.short_uuid(u)}: {err_text(ex)}')
                else:
                    self.rec.add_read(s, u, data)
        done = []
        for s, u, ch in notifying:
            try:
                await client.start_notify(ch, self.handler(s, u, gen))
            except Exception as ex:
                self.rec.sys(f'Подписка на {bl.short_uuid(u)} не удалась: {err_text(ex)}')
            else:
                done.append(bl.short_uuid(u))
        if done:
            self.rec.sys('Подписка: ' + ', '.join(done))
        else:
            self.rec.sys('Подписок нет: ' + ('ни одна не удалась' if notifying else 'ни одна характеристика не шлёт уведомлений'))

    def handler(self, s, u, gen):
        def on_data(_sender, data):
            if gen == self.gen:      # a handler left over from an earlier link stays quiet
                self.rec.on_notify(s, u, data)
        return on_data


def bleak_finder(bleak, address=None, prefix=None, timeout=SCAN_S):
    """find() for Session: one scan for the address or a name prefix (any case)."""
    async def find():
        if address:
            return await bleak.BleakScanner.find_device_by_address(address, timeout=timeout)
        p = prefix.lower()
        return await bleak.BleakScanner.find_device_by_filter(
            lambda d, ad: (d.name or ad.local_name or '').lower().startswith(p), timeout=timeout)
    return find


def scan_lines(found):
    """BleakScanner.discover(return_adv=True) result -> one line per device, strongest first."""
    rows = sorted(found.values(), key=lambda da: da[1].rssi if da[1].rssi is not None else -999, reverse=True)
    out = []
    for dev, adv in rows:
        name = dev.name or adv.local_name or '(без имени)'
        uuids = ', '.join(bl.short_uuid(uuid_of(u)) for u in adv.service_uuids or ())
        out.append(f'{adv.rssi:>4} дБм  {dev.address:<17}  {name}' + (f'  [{uuids}]' if uuids else ''))
    return out


# ---------------------------------------------------------------- terminal

class Console:
    """Messages scroll up; one status line stays at the bottom (only on a terminal)."""

    def __init__(self, stream=None):
        self.stream = stream or sys.stdout
        self.tty = self.stream.isatty()
        self.bottom = ''

    def status(self, text):
        if not self.tty:
            return
        extra = max(0, len(self.bottom) - len(text))
        # spaces wipe the rest of the old line, backspaces put the cursor after the new text
        self.write('\r' + text + ' ' * extra + '\b' * extra)
        self.bottom = text

    def say(self, text):
        bottom = self.bottom if self.tty else ''
        self.write(('\r' + ' ' * len(bottom) + '\r' if bottom else '') + text + '\n' + bottom)

    def write(self, text):
        try:
            self.stream.write(text)
            self.stream.flush()
        except (OSError, ValueError):   # the terminal window was closed: recording and saving go on
            pass


class Keys:
    """Key presses -> markers. 'm' starts a line of text for an own marker, Enter ends it."""

    def __init__(self, rec, console, quit):
        self.rec, self.console, self.quit = rec, console, quit
        self.line = None     # text typed after 'm', None when not typing

    def feed(self, ch):
        if self.line is None:
            if ch in HOTKEYS:
                self.rec.mark(HOTKEYS[ch])
            elif ch in 'mMьЬ':           # ь is m on the Russian layout
                self.line = ''
                self.console.status(PROMPT)
            elif ch in 'qQйЙ\x03':        # Ctrl+C arrives as a key from msvcrt
                self.quit()
            return
        if ch in '\r\n':
            text, self.line = self.line.strip(), None
            self.console.status('')
            if text:
                self.rec.mark(text)
            return
        if ch == '\x1b':
            self.line = None
            self.console.status('')
            return
        if ch in '\x7f\b':
            self.line = self.line[:-1]
        elif ch.isprintable() and len(self.line) < MAX_LABEL:
            self.line += ch
        self.console.status(PROMPT + self.line)

    def line_in(self, line):
        """stdin is not a terminal: a whole line is one marker, 'q' quits."""
        text = line.strip()
        if text.lower() in ('q', 'й'):
            self.quit()
        elif text:
            self.rec.mark(HOTKEYS.get(text, text)[:MAX_LABEL])


@contextlib.contextmanager
def cbreak(stdin):
    """Unix terminal: keys arrive one by one, without Enter and echo. Restored on exit."""
    fd = old = None
    if termios and stdin is not None and stdin.isatty():
        try:
            fd = stdin.fileno()
            old = termios.tcgetattr(fd)
            tty.setcbreak(fd)
        except (termios.error, OSError, ValueError):
            old = None
    try:
        yield old is not None
    finally:
        if old is not None:
            with contextlib.suppress(termios.error):     # the terminal is already gone
                termios.tcsetattr(fd, termios.TCSADRAIN, old)


async def poll_console(keys, every=0.05):
    """Windows console keys."""
    while True:
        while msvcrt.kbhit():
            ch = msvcrt.getwch()
            if ch in ('\x00', '\xe0'):   # arrows and F-keys: a second code follows
                msvcrt.getwch()
            else:
                keys.feed(ch)
        await asyncio.sleep(every)


def listen_keys(keys, loop, raw, stdin=None):
    """Feed keys from stdin; raw: a Unix terminal already in cbreak mode. Returns a stop function."""
    stdin = stdin or sys.stdin
    if stdin is None:
        return lambda: None
    if raw:
        fd = stdin.fileno()
        dec = codecs.getincrementaldecoder(stdin.encoding or 'utf-8')('replace')

        def ready():
            try:
                data = os.read(fd, 256)
            except OSError:              # EIO after a hang-up: stop, or asyncio calls us in a loop
                data = b''
            if not data:                 # terminal gone
                loop.remove_reader(fd)
            for ch in ESC_SEQ.sub('', dec.decode(data)):
                keys.feed(ch)
        loop.add_reader(fd, ready)
        return lambda: loop.remove_reader(fd)
    if msvcrt and stdin.isatty():
        return loop.create_task(poll_console(keys)).cancel

    def pump():
        # a daemon thread: a blocked readline must not keep the program alive at exit
        try:
            for line in stdin:
                loop.call_soon_threadsafe(keys.line_in, line)
        except (OSError, ValueError, RuntimeError):   # stdin closed or the loop gone
            pass
    threading.Thread(target=pump, daemon=True).start()
    return lambda: None


# ---------------------------------------------------------------- recording

def save_now(rec, out, console, final=False):
    if not rec.has_data:
        if final:
            console.say('Лог пуст: самокат не подключился, меток нет — файл не записан.')
        return
    try:
        rec.save(out)
    except OSError as ex:
        console.say(f'! Не удалось сохранить {rel(out)}: {ex}')
        return
    if final:
        console.say(f'Сохранено: {rel(out)} — записей {len(rec.log["entries"])}' + ('' if rec.keep_ids else ', обезличен'))


async def autosave(rec, out, every, console):
    while True:
        await asyncio.sleep(every)
        if rec.dirty:
            save_now(rec, out, console)


async def show_status(rec, sess, keys, console, every=1.0):
    n0, t0 = rec.packets, time.monotonic()
    while True:
        await asyncio.sleep(every)
        t = time.monotonic()
        rate = (rec.packets - n0) / max(t - t0, 1e-6)
        n0, t0 = rec.packets, t
        if keys.line is None:    # do not draw over an own marker being typed
            console.status(f'{sess.state} · пакетов {rec.packets} · {rate:.1f}/с · меток {rec.markers}')


async def record(rec, out, find, make_client, read=True, autosave_every=AUTOSAVE_S, backoff=BACKOFF,
                 console=None, listen=None):
    """The whole recording: session, autosave, status line, keys. Saves on the way out.

    listen(keys, loop) starts feeding key presses and returns a function that stops it.
    """
    console = console or Console()
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    keys = Keys(rec, console, stop.set)
    sess = Session(rec, find, make_client, stop, read=read, backoff=backoff)
    side = [loop.create_task(autosave(rec, out, autosave_every, console)),
            loop.create_task(show_status(rec, sess, keys, console))]
    unlisten = listen(keys, loop) if listen else None
    sigs = []
    for name in ('SIGTERM', 'SIGHUP'):     # `kill` and a closed terminal window save too (Unix, main thread)
        with contextlib.suppress(NotImplementedError, RuntimeError, ValueError, AttributeError):
            loop.add_signal_handler(getattr(signal, name), stop.set)
            sigs.append(getattr(signal, name))
    try:
        await sess.run()
    finally:
        for sig in sigs:
            loop.remove_signal_handler(sig)
        if unlisten:
            unlisten()
        for t in side:
            t.cancel()
        console.status('')
        rec.sys('Запись остановлена')
        save_now(rec, out, console, final=True)


def need_bleak():
    try:
        import bleak
    except ImportError:
        print('Нужен пакет bleak: pip install bleak', file=sys.stderr)
        return None
    return bleak


def bleak_version():
    try:
        from importlib import metadata
        return metadata.version('bleak')
    except ImportError:      # PackageNotFoundError is an ImportError too
        return '?'


def main(argv=None):
    ap = argparse.ArgumentParser(description='Логгер Bluetooth для ПК (bleak): только читает и подписывается, в самокат не пишет.')
    ap.add_argument('--scan', action='store_true', help='показать устройства вокруг: RSSI, адрес, имя, сервисы')
    ap.add_argument('--timeout', type=float, default=SCAN_S, help='сколько искать устройство, с (10)')
    who = ap.add_mutually_exclusive_group()
    who.add_argument('--address', help='адрес самоката из --scan (на macOS это UUID)')
    who.add_argument('--name', help='начало имени устройства, регистр не важен')
    ap.add_argument('-o', '--out', help='файл лога; .gz в конце — сжатый (по умолчанию logs/ДАТА-ВРЕМЯ-<метка>.json)')
    ap.add_argument('--label', help='метка в имени файла по умолчанию (pc)')
    ap.add_argument('--keep-ids', action='store_true', help='не обезличивать: оставить настоящие имя и адрес (такой лог не выкладывай)')
    ap.add_argument('--no-read', action='store_true', help='не читать характеристики, только подписки')
    ap.add_argument('--gz', action='store_true', help='имя по умолчанию с .json.gz (сжатый лог)')
    a = ap.parse_args(argv)
    if not (a.scan or a.address or a.name):
        ap.error('нужен --scan, --address или --name')
    bleak = need_bleak()
    if bleak is None:
        return 2

    if a.scan:
        print(f'Ищу {a.timeout:g} с…')
        try:
            found = asyncio.run(bleak.BleakScanner.discover(timeout=a.timeout, return_adv=True))
        except KeyboardInterrupt:
            return 130
        except Exception as ex:      # no adapter, Bluetooth off, no permission
            print(f'Поиск не удался: {err_text(ex)}. Bluetooth на ПК включён? На Linux нужен BlueZ.', file=sys.stderr)
            return 1
        lines = scan_lines(found)
        print('\n'.join(lines) if lines else 'Никого не нашёл. Самокат включён? Bluetooth на ПК включён?')
        if lines:
            print('\nДальше: python3 tools/ble_logger.py --address АДРЕС  (или --name НАЧАЛО_ИМЕНИ)')
        return 0

    started = datetime.datetime.now(datetime.timezone.utc)
    out = a.out or default_out(started, a.label, a.gz)
    console = Console()
    rec = Recorder(started=started, keep_ids=a.keep_ids, echo=console.say)
    rec.sys(f'Запись с ПК: tools/ble_logger.py, bleak {bleak_version()}, {platform.system()}')
    find = bleak_finder(bleak, a.address, a.name, a.timeout)
    console.say(f'Лог: {rel(out)}' + (' (настоящие имя и адрес!)' if a.keep_ids else ' (обезличенный)'))
    with cbreak(sys.stdin) as raw:
        keyboard = raw or bool(msvcrt and sys.stdin is not None and sys.stdin.isatty())
        console.say(HELP_KEYS if keyboard else HELP_LINES)
        try:
            asyncio.run(record(rec, out, find, bleak.BleakClient, read=not a.no_read, console=console,
                               listen=lambda keys, loop: listen_keys(keys, loop, raw)))
        except KeyboardInterrupt:
            if rec.dirty:            # the loop was cut short before its own final save
                save_now(rec, out, console, final=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
