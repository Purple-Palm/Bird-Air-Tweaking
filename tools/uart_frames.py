#!/usr/bin/env python3
"""Кадры контроллера в потоке FFE1 Bird Air: «: адрес команда длина данные сумма16 CR LF».

    python3 tools/uart_frames.py logs/20261014-174148-log.json
    python3 tools/uart_frames.py logs/X.json --uuid ffe1 --window 3

Руль начинает слать кадры контроллера в FFE1 после любой команды «+…» с телефона
(например +MODE=3). Формат кадра (в декодере — "framing": "colon-sum16"):

    3A ':' | адрес u8 | команда u8 | длина L u8 | данные[L] | сумма u16 LE | 0D 0A
    сумма = (адрес + команда + L + сумма байтов данных) & 0xFFFF

Сборщик (Assembler) — та же логика, что у страницы при framing = colon-sum16, отдельно
для каждой характеристики:

  * BLE режет кадр на уведомления (20 байт + «0A», 20 + 4) — кусочки склеиваются в поток.
  * Кадр начинается с 3A; принимается, только если сошлись длина, сумма и 0D 0A в конце.
  * Иногда 3A теряется («17 00 0D … 0D 0A» сразу после 0D 0A предыдущего кадра). Такой кадр
    принимается только сразу после CR LF (или в самом начале потока) и только если сошлись
    длина, сумма и 0D 0A; к нему спереди дописывается 3A, так что смещения полей и «Начало
    пакета» те же. Флаг recovered = True.
  * Всё прочее (эхо «+MODE=3», «+SVGT=0», «OK OK OK ») пропускается побайтно до следующего
    3A, не ломая следующие кадры. Кандидат, которому не хватает байтов, бросается, если
    дальше в буфере уже есть целый правильный кадр с 3A — так текст после CR LF не
    задерживает поток.
  * Кадр длиннее `cap` (по умолчанию 300 байт) не принимается, поэтому буфер держит не больше
    `cap` байт.
  * Строка «Найдено сервисов: …» в логе (страница пишет её после каждого подключения) — новый
    поток: недособранный кусок отбрасывается.

Смещения в кадре считаются от 3A (байт 0): заголовок 0–3, данные 4…4+L−1, сумма 4+L и 5+L.

Скрипт только читает лог: ничего не пишет ни в самокат, ни в файлы.
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402

FRAMING = 'colon-sum16'
COLON, CR, LF = 0x3A, 0x0D, 0x0A
HEAD = 4                    # 3A addr cmd len
TAIL = 4                    # sum16 LE, 0D 0A
MAX_FRAME = HEAD + 255 + TAIL
CAP = 300                   # longest frame accepted, and so the most the buffer keeps
NEED, BAD = -1, -2

SYS_FOUND = 'Найдено сервисов: '   # the page logs this after each (re)connection: a new stream

Frame = collections.namedtuple('Frame', 't frame recovered')


def checksum(addr, cmd, data):
    return (addr + cmd + len(data) + sum(data)) & 0xFFFF


def make_frame(addr, cmd, data):
    """A valid frame (for tests and examples): 3A addr cmd len data sum16le 0D 0A."""
    data = bytes(data)
    s = checksum(addr, cmd, data)
    return bytes([COLON, addr, cmd, len(data)]) + data + bytes([s & 0xFF, s >> 8, CR, LF])


def is_valid(frame):
    """Is `frame` (starting with 3A) one whole frame with the right length, sum and CR LF?"""
    b = bytes(frame)
    return len(b) >= HEAD + TAIL and b[0] == COLON and _try(b, 0, 1, len(b)) == len(b)


def head(frame):
    """(addr, cmd, len) of a frame."""
    return frame[1], frame[2], frame[3]


def head_hex(frame):
    return bl.hex_of(frame[:HEAD])


def data_of(frame):
    return frame[HEAD:HEAD + frame[3]]


def _try(b, i, h, cap):
    """Frame at b[i:], its addr byte at b[i + h] (h = 1 after a 3A, 0 when 3A was lost).
    Returns its length in the buffer, NEED (wait for more bytes) or BAD."""
    if len(b) - i < h + 3:
        return NEED
    n = b[i + h + 2]
    total = h + 3 + n + TAIL
    if 1 + 3 + n + TAIL > cap:
        return BAD
    if len(b) - i < total:
        return NEED
    end = i + h + 3 + n
    s = sum(b[i + h:end]) & 0xFFFF
    if b[end] | b[end + 1] << 8 != s or b[end + 2] != CR or b[end + 3] != LF:
        return BAD
    return total


class Assembler:
    """Frame assembler for one characteristic. feed(chunk, t) -> new Frame list.

    A frame gets the time of the notification that brought its last byte.
    Counters: frames, recovered, dropped (3A starts rejected: wrong sum or length, cut off),
    junk (bytes skipped). `texts` keeps the skipped runs as (t, bytes) when keep_texts.
    """

    def __init__(self, cap=CAP, keep_texts=True):
        self.cap = cap
        self.keep_texts = keep_texts
        self.buf = bytearray()
        self.base = 0                  # stream offset of buf[0]
        self.marks = collections.deque()   # (stream offset just past a chunk, its t)
        self.tail = b''                # last two bytes consumed
        self.at_start = True           # nothing consumed yet
        self.frames = []
        self.n_frames = self.recovered = self.dropped = self.junk = 0
        self.texts = []
        self._run, self._run_t = bytearray(), None
        self.max_buf = 0

    # -- helpers
    def _after_crlf(self):
        return self.at_start or self.tail == b'\r\n'

    def _time_at(self, off):
        """t of the chunk holding stream byte off - 1."""
        for end, t in self.marks:
            if end >= off:
                return t
        return self.marks[-1][1] if self.marks else None

    def _consume(self, n):
        del self.buf[:n]
        self.base += n
        while self.marks and self.marks[0][0] <= self.base and len(self.marks) > 1:
            self.marks.popleft()

    def _skip(self, n=1):
        b = bytes(self.buf[:n])
        if self.keep_texts:
            if not self._run:
                self._run_t = self._time_at(self.base + 1)
            self._run += b
        self.junk += len(b)
        self.tail = (self.tail + b)[-2:]
        self.at_start = False
        self._consume(n)

    def _close_run(self):
        if self._run:
            self.texts.append((self._run_t, bytes(self._run)))
            self._run = bytearray()

    def _strict_frame_later(self):
        b = self.buf
        k = b.find(COLON, 1)
        while k >= 0:
            if _try(b, k, 1, self.cap) > 0:
                return True
            k = b.find(COLON, k + 1)
        return False

    # -- api
    def feed(self, chunk, t=None):
        out = []
        if chunk:
            self.buf += bytes(chunk)
            self.marks.append((self.base + len(self.buf), t))
        while self.buf:
            b = self.buf
            recovered = b[0] != COLON
            if not recovered:
                r = _try(b, 0, 1, self.cap)
            elif self._after_crlf():
                r = _try(b, 0, 0, self.cap)
            else:
                self._skip()
                continue
            if r == NEED:
                if self._strict_frame_later():
                    self.dropped += not recovered
                    self._skip()
                    continue
                break
            if r == BAD:
                self.dropped += not recovered
                self._skip()
                continue
            frame = (b':' if recovered else b'') + bytes(b[:r])
            f = Frame(self._time_at(self.base + r), frame, recovered)
            self._close_run()
            self._consume(r)
            self.tail, self.at_start = b'\r\n', False
            self.n_frames += 1
            self.recovered += recovered
            self.frames.append(f)
            out.append(f)
        self.max_buf = max(self.max_buf, len(self.buf))
        return out

    def finish(self):
        """End of the stream: whatever is left in the buffer is junk."""
        if self.buf:
            self._skip(len(self.buf))
        self._close_run()
        return self

    def restart(self):
        """A new connection: the old stream ends here, the next byte is a stream start again."""
        self.finish()
        self.tail, self.at_start = b'', True


def iter_frames(chunks, cap=CAP):
    """chunks: iterable of (t, bytes) of one characteristic, in order. Yields Frame(t, frame, recovered);
    `frame` always starts with 3A."""
    asm = Assembler(cap, keep_texts=False)
    for t, b in chunks:
        yield from asm.feed(b, t)


def _char_filter(uuid):
    if uuid is None:
        return lambda u: True
    want = bl.norm_uuid(uuid)
    raw = str(uuid).strip().lower()
    return lambda u: (u or '') == want if want else (u or '').lower() == raw


def assemble_log(log, uuid='ffe1', cap=CAP):
    """{(service, characteristic): Assembler} over the rx entries of matching characteristics
    (uuid=None: every characteristic). Each Assembler has .frames, .texts and the counters."""
    ok = _char_filter(uuid)
    out = collections.OrderedDict()
    for e in log.get('entries', []):
        if e.get('k') == 'sys' and str(e.get('n') or '').startswith(SYS_FOUND):
            for asm in out.values():
                asm.restart()
        if e.get('k') != 'rx' or not ok(e.get('u')):
            continue
        b = bl.entry_bytes(e)
        if b:
            key = bl.char_key(e)
            if key not in out:
                out[key] = Assembler(cap)
            out[key].feed(b, e['t'])
    for asm in out.values():
        asm.finish()
    return out


def frames_from_log(log, uuid='ffe1', cap=CAP):
    """{(service, characteristic): [Frame]} — see assemble_log."""
    return collections.OrderedDict((k, a.frames) for k, a in assemble_log(log, uuid, cap).items())


def decode_log(decoder, log):
    """Telemetry the page would show for a log: [(t, key, value)] in order.

    With "framing": "colon-sum16" fields read whole frames assembled from the rx notifications of
    each characteristic (rd values are not frames), otherwise raw rx/rd packets. JSON lines
    (decoder.json) are not handled here."""
    fields = [f for f in decoder.get('fields') or [] if isinstance(f, dict)]
    framed = decoder.get('framing') == FRAMING
    asms = {}
    out = []

    def apply(u, t, b):
        for f in fields:
            fu = bl.norm_uuid(f.get('uuid')) if f.get('uuid') else ''
            if fu and fu != u:
                continue
            v = bl.field_value(f, b)
            if v is not None:
                out.append((t, f.get('key'), v))

    for e in log.get('entries', []):
        if e.get('k') == 'sys' and str(e.get('n') or '').startswith(SYS_FOUND):
            for asm in asms.values():
                asm.restart()
        if e.get('k') not in ('rx', 'rd'):
            continue
        b = bl.entry_bytes(e)
        if not framed:
            apply(e.get('u'), e['t'], b)
            continue
        if e['k'] != 'rx' or not b:
            continue
        key = bl.char_key(e)
        asm = asms.setdefault(key, Assembler(keep_texts=False))
        for fr in asm.feed(b, e['t']):
            apply(e.get('u'), fr.t, fr.frame)
    return out


# ---------------------------------------------------------------- report

def junk_labels(runs):
    """Bytes skipped between frames, as short labels: «text» for printable lines, hex for a few
    other bytes, «N байт не текста» for longer binary runs."""
    out = collections.Counter()
    for _, run in runs:
        for piece in bytes(run).split(b'\r\n'):
            if not piece:
                continue
            if all(32 <= x < 127 for x in piece):
                out['«' + piece.decode('ascii') + '»'] += 1
            elif len(piece) <= 12:
                out[bl.hex_of(piece)] += 1
            else:
                out[f'{len(piece)} байт не текста'] += 1
    return out


def tx_label(b):
    """«+MODE=3» for ASCII commands, hex otherwise."""
    b = bytes(b)
    if b and all(32 <= x < 127 for x in b):
        return '«' + b.decode('ascii') + '»'
    return bl.hex_of(b)


def _ranges(offs):
    out, run = [], []
    for o in offs:
        if run and o == run[-1] + 1:
            run.append(o)
        else:
            if run:
                out.append(run)
            run = [o]
    if run:
        out.append(run)
    return ', '.join(str(r[0]) if len(r) == 1 else f'{r[0]}–{r[-1]}' for r in out)


def by_type(frames):
    """{(addr, cmd, len): [Frame]} in order of first appearance."""
    out = collections.OrderedDict()
    for f in frames:
        out.setdefault(head(f.frame), []).append(f)
    return out


def byte_activity(fs, n):
    """{data offset: share of frames where the byte differs from the previous frame}."""
    out = {}
    for off in range(HEAD, HEAD + n):
        vals = [f.frame[off] for f in fs]
        out[off] = sum(1 for a, b in zip(vals, vals[1:]) if a != b) / max(1, len(vals) - 1)
    return out


SLOW = 0.1        # a byte that changes in under 10 % of frames is a setting, not a measurement


def _pair_type(fs, off):
    s16 = [bl.read_value(f.frame, off, 's16le') for f in fs]
    return 's16le' if min(s16) < 0 and max(abs(v) for v in s16) < 1000 else 'u16le'


def _mean_sd(xs):
    m = sum(xs) / len(xs)
    return m, (sum((x - m) ** 2 for x in xs) / (len(xs) - 1)) ** 0.5 if len(xs) > 1 else 0.0


def tx_effects(frames, txs, window_ms):
    """What happened in the frames right after each write. Returns [(tx_entry, notes)]; a note is

      ('start', delay_ms)                         the stream of frames began after this write
      ('stop', ms_since_last_frame)               no frames after it till the end of the log
      ('first', head_hex, offset, value)          a setting byte in the first frame of a kind
      ('byte', head_hex, offset, before, after, delay_ms, holds)   a setting byte changed
      ('mean', head_hex, offset, type, mean_before, mean_after)    a noisy value shifted (Welch t ≥ 3,
                                                                   4+ frames on each side)

    The window runs from the previous write (at most window_ms before) to the next one (at most
    window_ms after). Setting bytes are data bytes that change in under SLOW of the frames."""
    types = by_type(frames)
    act = {k: byte_activity(fs, k[2]) for k, fs in types.items()}
    times = [f.t for f in frames]
    out = []
    for i, e in enumerate(txs):
        t = e['t']
        lo = max(t - window_ms, txs[i - 1]['t']) if i else t - window_ms
        hi = min(t + window_ms, txs[i + 1]['t']) if i + 1 < len(txs) else t + window_ms
        notes = []
        before_all = [x for x in times if x < t]
        after_all = [x for x in times if x > t]
        if after_all and not before_all:
            notes.append(('start', min(after_all) - t))
            for key, fs in types.items():
                first = next((f for f in fs if f.t > t), None)
                for off, a in act[key].items():
                    if first and 0 < a < SLOW:
                        notes.append(('first', head_hex(first.frame), off, first.frame[off]))
        elif before_all and not after_all:
            notes.append(('stop', t - max(before_all)))
        for key, fs in types.items():
            before = [f for f in fs if lo <= f.t < t]
            after = [f for f in fs if t < f.t <= hi]
            if not before or not after:
                continue
            hh = head_hex(fs[0].frame)
            for off, a in act[key].items():
                if 0 < a < SLOW:
                    v0 = before[-1].frame[off]
                    if any(f.frame[off] != v0 for f in before[-3:]):
                        continue
                    changed = next((f for f in after if f.frame[off] != v0), None)
                    if changed is None:
                        continue
                    v1 = changed.frame[off]
                    holds = all(f.frame[off] == v1 for f in after if f.t >= changed.t)
                    notes.append(('byte', hh, off, v0, v1, changed.t - t, holds))
                elif a >= SLOW and len(before) >= 4 and len(after) >= 4:
                    typ = _pair_type(fs, off) if off + 1 < HEAD + key[2] else 'u8'
                    m0, s0 = _mean_sd([bl.read_value(f.frame, off, typ) for f in before])
                    m1, s1 = _mean_sd([bl.read_value(f.frame, off, typ) for f in after])
                    se = (s0 ** 2 / len(before) + s1 ** 2 / len(after)) ** 0.5
                    if se and abs(m1 - m0) / se >= 3:
                        notes.append(('mean', hh, off, typ, m0, m1))
        out.append((e, notes))
    return out


def report(log, uuid='ffe1', window_s=3.0, stream=None, top=10):
    p = lambda *a: print(*a, file=stream or sys.stdout)  # noqa: E731
    asms = assemble_log(log, uuid)
    files = ', '.join(log.get('_files') or []) or '(лог)'
    p(f'== {files}')
    if not asms:
        p(f'   нет уведомлений (rx) от {uuid or "характеристик"}')
        return {}
    ok = _char_filter(uuid)
    for (s, u), asm in asms.items():
        es = [e for e in log['entries'] if e.get('u') == u and e.get('s') == s]
        n_rx = sum(1 for e in es if e['k'] == 'rx')
        p(f'\n## {bl.short_uuid(u)} (сервис {bl.short_uuid(s)}): уведомлений {n_rx}, кадров {asm.n_frames}'
          f' (без «:», восстановлено: {asm.recovered}), отброшено начал «:» {asm.dropped}, байт вне кадров {asm.junk}')
        if not asm.frames:
            p('   кадров «: адрес команда длина данные сумма16 CR LF» нет')
        bad = sum(1 for f in asm.frames if not is_valid(f.frame))
        if bad:
            p(f'   ! {bad} принятых кадров не проходят проверку суммы — ошибка сборщика')
        texts = junk_labels(asm.texts)
        if texts:
            p('   вне кадров: ' + ', '.join(f'{x} ×{c}' if c > 1 else x for x, c in texts.most_common(12))
              + (f' … и ещё {len(texts) - 12}' if len(texts) > 12 else ''))

        for key, fs in by_type(asm.frames).items():
            addr, cmd, n = key
            span = (fs[-1].t - fs[0].t) / 1000 if len(fs) > 1 else 0
            rate = f', {(len(fs) - 1) / span:.1f}/с' if span > 0 else ''
            rec = sum(f.recovered for f in fs)
            p(f'\n  Кадр {head_hex(fs[0].frame)}  (адрес 0x{addr:02X}, команда 0x{cmd:02X}, данных {n}, всего {HEAD + n + TAIL} байт)')
            p(f'    ×{len(fs)}{rate}, {fs[0].t / 1000:.2f}…{fs[-1].t / 1000:.2f} с' + (f', восстановлено без «:» {rec}' if rec else ''))
            rows = collections.OrderedDict()
            for f in fs:
                r = rows.setdefault(bytes(data_of(f.frame)), [0, f.t, f.t])
                r[0] += 1
                r[2] = f.t
            p(f'    разных строк данных: {len(rows)}' + (f' (показаны {top} частых)' if len(rows) > top else ''))
            shown = sorted(rows.items(), key=lambda kv: -kv[1][0])[:top]
            shown.sort(key=lambda kv: kv[1][1])
            for data, (c, t0, t1) in shown:
                p(f'      {bl.hex_of(data)}  ×{c}  {t0 / 1000:.2f}…{t1 / 1000:.2f} с')
            const, vary = [], []
            for off in range(HEAD, HEAD + n):
                vals = [f.frame[off] for f in fs]
                (vary if len(set(vals)) > 1 else const).append(off)
            if const:
                p(f'    постоянные байты: {_ranges(const)}')
            for off in vary:
                vals = [f.frame[off] for f in fs]
                ch = sum(1 for a, b in zip(vals, vals[1:]) if a != b)
                line = f'    байт {off}: {min(vals):02X}…{max(vals):02X}, {len(set(vals))} зн., меняется в {ch / max(1, len(vals) - 1):.0%} кадров'
                if off + 1 < HEAD + n:
                    u16 = [bl.read_value(f.frame, off, 'u16le') for f in fs]
                    s16 = [bl.read_value(f.frame, off, 's16le') for f in fs]
                    line += f'; {off}–{off + 1} u16le {min(u16)}…{max(u16)}'
                    if min(s16) < 0:
                        line += f', s16le {min(s16)}…{max(s16)}'
                p(line)
            if not vary:
                p('    данные не менялись')

        txs = [e for e in log['entries'] if e.get('k') == 'tx' and ok(e.get('u')) and e.get('s') == s]
        if txs:
            p(f'\n  После записей в {bl.short_uuid(u)} (окно {window_s:g} с, до следующей записи):')
            for e, notes in tx_effects(asm.frames, txs, int(window_s * 1000)):
                b = bl.entry_bytes(e)
                p(f"    {e['t'] / 1000:7.2f} с  {tx_label(b)}")
                why = bl.danger_write(b)
                if why:
                    p(f'             ОПАСНО: {why}')
                if not notes:
                    p('             стабильные байты кадров не изменились')
                for nt in notes:
                    if nt[0] == 'start':
                        p(f'             пошёл поток кадров (первый через {nt[1] / 1000:.2f} с)')
                    elif nt[0] == 'first':
                        p(f'             в первом кадре {nt[1]} байт {nt[2]} = {nt[3]:02X}')
                    elif nt[0] == 'stop':
                        p(f'             кадры прекратились до конца лога (последний за {nt[1] / 1000:.2f} с до записи)')
                    elif nt[0] == 'byte':
                        _, hh, off, v0, v1, delay, holds = nt
                        p(f'             кадр {hh} байт {off}: {v0:02X}→{v1:02X} через {delay / 1000:.2f} с'
                          + ('' if holds else ' (потом ещё менялся)'))
                    else:
                        _, hh, off, typ, m0, m1 = nt
                        span = f'{off}–{off + 1}' if typ != 'u8' else f'{off}'
                        p(f'             кадр {hh} байты {span} {typ}: среднее {m0:.1f} → {m1:.1f} ({m1 - m0:+.1f}), шумный байт — проверь')
    return asms


def main(argv=None):
    ap = argparse.ArgumentParser(description='Кадры контроллера «: адрес команда длина данные сумма16 CR LF» в логе Bird Air Lab')
    ap.add_argument('paths', nargs='+', help='файлы логов (.json, .json.gz, части) или папки')
    ap.add_argument('--uuid', default='ffe1', help='характеристика (ffe1); «all» — все')
    ap.add_argument('--window', type=float, default=3.0, help='окно после записи в самокат, с (3)')
    a = ap.parse_args(argv)
    notes = []
    logs = bl.load_logs(a.paths, notes)
    for n in notes:
        print('! ' + n)
    if not logs:
        print('Нет логов для разбора.')
        return 1
    uuid = None if a.uuid.lower() == 'all' else a.uuid
    for i, lg in enumerate(logs):
        if i:
            print()
        report(lg, uuid, a.window)
    return 0


if __name__ == '__main__':
    sys.exit(main())
