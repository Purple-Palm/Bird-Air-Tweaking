#!/usr/bin/env python3
"""Разбор логов Bird Air Lab: где в пакетах лежат заряд, скорость, ток и прочее.

    python3 tools/analyze_log.py logs/20261014-190211-gaz.json
    python3 tools/analyze_log.py logs/                     # все логи в папке
    python3 tools/analyze_log.py --new                     # логи, которых нет в sources декодера

Печатает сводку по характеристикам, группы пакетов (по заголовку), контрольные суммы,
кандидатов на поля с доводами и реакцию байтов на метки. Пишет черновик декодера
decoders/candidate.json; его поля можно вставить на странице в «Декодер → Конфиг текстом».

Только стандартная библиотека. Читает .json, .json.gz и логи, разрезанные на части.
"""
import argparse
import bisect
import collections
import datetime
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAX_SERIES = 20000   # longer groups are thinned evenly before field analysis
CHECK_SAMPLE = 300   # packets per group used for checksum search

# ---------------------------------------------------------------- reading numbers

TYPES = {
    'u8': 1, 's8': 1, 'u16le': 2, 's16le': 2, 'u16be': 2, 's16be': 2,
    'u24le': 3, 'u32le': 4, 'u32be': 4,
}


def read_num(b, off, typ):
    n = TYPES[typ]
    if off + n > len(b):
        return None
    if typ == 'u8':
        return b[off]
    if typ == 's8':
        return b[off] - 256 if b[off] > 127 else b[off]
    if typ in ('u16le', 's16le'):
        v = b[off] | b[off + 1] << 8
        return v - 65536 if typ == 's16le' and v > 32767 else v
    if typ in ('u16be', 's16be'):
        v = b[off] << 8 | b[off + 1]
        return v - 65536 if typ == 's16be' and v > 32767 else v
    if typ == 'u24le':
        return b[off] | b[off + 1] << 8 | b[off + 2] << 16
    if typ == 'u32le':
        return b[off] | b[off + 1] << 8 | b[off + 2] << 16 | b[off + 3] << 24
    if typ == 'u32be':
        return b[off] << 24 | b[off + 1] << 16 | b[off + 2] << 8 | b[off + 3]
    return None


# ---------------------------------------------------------------- checksums

def _reflect(v, width):
    r = 0
    for _ in range(width):
        r = (r << 1) | (v & 1)
        v >>= 1
    return r


def make_crc(width, poly, init, ref, xorout):
    """Table-driven CRC. All presets below have refin == refout and a symmetric init."""
    mask = (1 << width) - 1
    table = []
    if ref:
        rpoly = _reflect(poly, width)
        for i in range(256):
            c = i
            for _ in range(8):
                c = (c >> 1) ^ rpoly if c & 1 else c >> 1
            table.append(c)

        def crc(data):
            c = init
            for x in data:
                c = (c >> 8) ^ table[(c ^ x) & 0xFF]
            return (c ^ xorout) & mask
    else:
        top = 1 << (width - 1)
        for i in range(256):
            c = i << (width - 8)
            for _ in range(8):
                c = ((c << 1) ^ poly) & mask if c & top else (c << 1) & mask
            table.append(c)

        def crc(data):
            c = init
            for x in data:
                c = ((c << 8) & mask) ^ table[((c >> (width - 8)) ^ x) & 0xFF]
            return (c ^ xorout) & mask
    return crc


CRC8 = {
    'CRC-8': make_crc(8, 0x07, 0x00, False, 0x00),
    'CRC-8/ITU': make_crc(8, 0x07, 0x00, False, 0x55),
    'CRC-8/MAXIM': make_crc(8, 0x31, 0x00, True, 0x00),
    'CRC-8/ROHC': make_crc(8, 0x07, 0xFF, True, 0x00),
    'CRC-8/DVB-S2': make_crc(8, 0xD5, 0x00, False, 0x00),
    'CRC-8/CDMA2000': make_crc(8, 0x9B, 0xFF, False, 0x00),
    'CRC-8/WCDMA': make_crc(8, 0x9B, 0x00, True, 0x00),
    'CRC-8/SAE-J1850': make_crc(8, 0x1D, 0xFF, False, 0xFF),
    'CRC-8/AUTOSAR': make_crc(8, 0x2F, 0xFF, False, 0xFF),
    'CRC-8/BLUETOOTH': make_crc(8, 0xA7, 0x00, True, 0x00),
}
CRC16 = {
    'CRC-16/MODBUS': make_crc(16, 0x8005, 0xFFFF, True, 0x0000),
    'CRC-16/ARC': make_crc(16, 0x8005, 0x0000, True, 0x0000),
    'CRC-16/USB': make_crc(16, 0x8005, 0xFFFF, True, 0xFFFF),
    'CRC-16/CCITT-FALSE': make_crc(16, 0x1021, 0xFFFF, False, 0x0000),
    'CRC-16/XMODEM': make_crc(16, 0x1021, 0x0000, False, 0x0000),
    'CRC-16/KERMIT': make_crc(16, 0x1021, 0x0000, True, 0x0000),
    'CRC-16/X-25': make_crc(16, 0x1021, 0xFFFF, True, 0xFFFF),
    'CRC-16/MCRF4XX': make_crc(16, 0x1021, 0xFFFF, True, 0x0000),
    'CRC-16/GENIBUS': make_crc(16, 0x1021, 0xFFFF, False, 0xFFFF),
}
SUM8 = {
    'XOR': lambda d: _xor(d),
    'SUM8': lambda d: sum(d) & 0xFF,
    'NOT-SUM8': lambda d: ~sum(d) & 0xFF,
    'NEG-SUM8': lambda d: -sum(d) & 0xFF,
}
SUM16 = {
    'SUM16': lambda d: sum(d) & 0xFFFF,
    'NOT-SUM16': lambda d: ~sum(d) & 0xFFFF,   # Ninebot/Xiaomi style
}


def _xor(d):
    x = 0
    for v in d:
        x ^= v
    return x


def find_checksums(pkts):
    """pkts: list of bytes of one group. Returns matches sorted best first.

    Tries the last byte (or last two, LE and BE) as a checksum over bytes [start, end),
    also with one constant trailer byte after it.
    """
    pkts = [p for p in pkts if len(p) >= 4]
    if len(pkts) < 5:
        return []
    if len(pkts) > CHECK_SAMPLE:
        step = len(pkts) / CHECK_SAMPLE
        pkts = [pkts[int(i * step)] for i in range(CHECK_SAMPLE)]
    n = min(len(p) for p in pkts)
    trailer = 1 if len({p[-1] for p in pkts}) == 1 else 0
    out = []
    for tr in sorted({0, trailer}):
        at8 = -1 - tr
        if len({p[at8] for p in pkts}) >= 3:
            for start in range(0, min(5, n - 2 - tr)):
                for name, fn in list(SUM8.items()) + list(CRC8.items()):
                    ok = sum(1 for p in pkts if fn(p[start:len(p) + at8]) == p[at8])
                    if ok / len(pkts) >= 0.95:
                        out.append({'algo': name, 'width': 8, 'start': start, 'at': 'last' if not tr else 'last-1',
                                    'order': None, 'ratio': ok / len(pkts)})
        hi, lo = -2 - tr, -1 - tr
        if len({(p[hi], p[lo]) for p in pkts}) >= 3:
            for start in range(0, min(5, n - 3 - tr)):
                for name, fn in list(SUM16.items()) + list(CRC16.items()):
                    for order in ('le', 'be'):
                        ok = 0
                        for p in pkts:
                            v = fn(p[start:len(p) + hi])
                            got = p[hi] | p[lo] << 8 if order == 'le' else p[hi] << 8 | p[lo]
                            ok += v == got
                        if ok / len(pkts) >= 0.95:
                            out.append({'algo': name, 'width': 16, 'start': start, 'at': 'last2' if not tr else 'last2-1',
                                        'order': order, 'ratio': ok / len(pkts)})
    # A 16-bit match explains two bytes and is far less likely by chance, so it wins ties.
    out.sort(key=lambda m: (-m['ratio'], -m['width'], m['at'].endswith('-1'), m['start']))
    return out


def describe_checksum(m, n):
    end = n - (1 if m['width'] == 8 else 2) - (1 if m['at'].endswith('-1') else 0)
    pos = f'байт {end}' if m['width'] == 8 else f"байты {end}–{end + 1} ({m['order'].upper()})"
    return f"{pos} = {m['algo']} байтов {m['start']}..{end - 1} ({m['ratio']:.0%})"


# ---------------------------------------------------------------- markers

MARKER_KINDS = [
    ('release', ('отпустил', 'release')),
    ('charge_on', ('зарядка вкл', 'charge on', 'charging on')),
    ('charge_off', ('зарядка выкл', 'charge off', 'charging off')),
    ('throttle', ('газ', 'throttle')),
    ('brake', ('тормоз', 'brake')),
    ('stop', ('стоп', 'stop')),
    ('mode', ('режим', 'mode')),
    ('light', ('фара', 'свет', 'light')),
]
KIND_RU = {'release': 'Отпустил газ', 'charge_on': 'Зарядка вкл', 'charge_off': 'Зарядка выкл', 'throttle': 'Газ',
           'brake': 'Тормоз', 'stop': 'Стоп', 'mode': 'Сменил режим', 'light': 'Фара'}


def marker_kind(label):
    s = str(label or '').strip().lower()
    for kind, keys in MARKER_KINDS:
        if any(k in s for k in keys):
            return kind
    return None


# ---------------------------------------------------------------- grouping

class Group:
    def __init__(self, s, u, fixed, length, items):
        self.s, self.u = s, u
        self.fixed = fixed            # {position: value}
        self.length = length
        self.items = items            # [(log_index, t, bytes)]
        self.label = ''
        self.checksum = None
        self.byte_info = []

    @property
    def pattern(self):
        if not self.fixed:
            return ''
        return ' '.join('%02X' % self.fixed[i] if i in self.fixed else '??' for i in range(max(self.fixed) + 1))

    @property
    def packets(self):
        return [b for _, _, b in self.items]


def _const_positions(pkts, upto):
    return sum(1 for i in range(upto) if len({p[i] for p in pkts}) == 1)


def _is_selector(pkts, p):
    """Does splitting by byte p separate different kinds of frames?"""
    vals = [b[p] for b in pkts]
    distinct = set(vals)
    if not 2 <= len(distinct) <= 16:
        return False
    lens = [len(b) for b in pkts]
    if len(set(lens)) > 1:
        by_val = collections.defaultdict(set)
        for v, n in zip(vals, lens):
            by_val[v].add(n)
        if all(len(s) == 1 for s in by_val.values()):
            return True
    # Frame kinds interleave (A B A B); a slow value such as an odometer byte changes
    # once and never comes back, so it is not a selector however tidy the split looks.
    seen, prev, returns = set(), None, 0
    for v in vals:
        if v != prev:
            returns += v in seen
            seen.add(v)
            prev = v
    if returns < 2:
        return False
    subs = collections.defaultdict(list)
    for v, b in zip(vals, pkts):
        subs[v].append(b)
    big = [s for s in subs.values() if len(s) >= 3]
    if len(big) < 2:
        return False
    # Count constant bytes other than the selector itself and the last byte (a checksum
    # turns constant whenever everything else does).
    upto = min(lens) - 1
    base = _const_positions(pkts, upto) - (p < upto)
    total = sum(len(s) for s in big)
    gain = sum(len(s) * (_const_positions(s, min(len(b) for b in s) - 1) - (p < upto) - base) for s in big) / total
    return gain >= 1


def _const_prefix(pkts, cap):
    n = min(cap, min(len(b) for b in pkts))
    c = 0
    while c < n and len({b[c] for b in pkts}) == 1:
        c += 1
    return c


def partition(items):
    """Split packets of one characteristic into frame kinds by header bytes."""
    pkts = [b for _, _, b in items if b]
    if not pkts:
        return [({}, items)]
    c0 = _const_prefix(pkts, 6)

    def rec(its, fixed, depth, start):
        ps = [b for _, _, b in its]
        p = start
        n = min(len(b) for b in ps)
        while p < min(n, 6) and len({b[p] for b in ps}) == 1:
            if depth and p < 4:      # constant right after a selector: command byte and the like
                fixed = {**fixed, p: ps[0][p]}
            p += 1
        if depth >= 2 or len(ps) < 6:
            return [(fixed, its)]
        for q in (p, p + 1):
            if q < min(n, 6) and _is_selector(ps, q):
                subs = collections.defaultdict(list)
                for it in its:
                    subs[it[2][q]].append(it)
                out = []
                for v, sub in subs.items():
                    f = dict(fixed)
                    f[q] = v
                    sp = [b for _, _, b in sub]
                    for i in range(c0, q):     # bytes between prefix and selector constant within this kind
                        if i not in f and len({b[i] for b in sp}) == 1:
                            f[i] = sp[0][i]
                    out += rec(sub, f, depth + 1, q + 1)
                return out
        return [(fixed, its)]

    leaves = rec(items, {}, 0, c0)
    # A long constant prefix may run into payload bytes that just did not change in this
    # log (charge level, say), so only its first few bytes go into the match pattern.
    cap = 4 if len(leaves) > 1 else 2
    out = []
    for fixed, its in leaves:
        f = {i: pkts[0][i] for i in range(min(c0, cap))}
        f.update(fixed)
        out.append((f, its))
    return out


# Generic Access, Generic Attribute, Device Information: names and bookkeeping, never telemetry.
STANDARD = {bl.norm_uuid(x) for x in ('1800', '1801', '180a')}


def build_groups(logs):
    by_char = collections.OrderedDict()
    for li, lg in enumerate(logs):
        for e in lg['entries']:
            if e['k'] in ('rx', 'rd') and (e.get('s') or '') not in STANDARD:
                b = bl.entry_bytes(e)
                if b:
                    by_char.setdefault(bl.char_key(e), []).append((li, e['t'], b))
    groups = []
    for (s, u), items in by_char.items():
        for fixed, its in partition(items):
            by_len = collections.defaultdict(list)
            for it in its:
                by_len[len(it[2])].append(it)
            for n, sub in sorted(by_len.items(), key=lambda kv: -len(kv[1])):
                groups.append(Group(s, u, fixed, n, sub))
    groups.sort(key=lambda g: -len(g.items))
    for i, g in enumerate(groups):
        g.label = chr(65 + i) if i < 26 else 'G%d' % i
    return groups


# ---------------------------------------------------------------- per-field analysis

class Series:
    """Values of one (offset, type) in a group, split per log."""

    def __init__(self, group, off, typ, bit=None):
        self.off, self.typ, self.bit = off, typ, bit
        self.per_log = collections.defaultdict(lambda: ([], []))
        vals = []
        items = group.items
        if len(items) > MAX_SERIES:
            step = len(items) / MAX_SERIES
            items = [items[int(i * step)] for i in range(MAX_SERIES)]
        for li, t, b in items:
            v = read_num(b, off, typ)
            if v is None:
                continue
            if bit is not None:
                v = (v >> bit) & 1
            ts, vs = self.per_log[li]
            ts.append(t)
            vs.append(v)
            vals.append(v)
        self.vals = vals
        self.n = len(vals)
        self.lo = min(vals) if vals else 0
        self.hi = max(vals) if vals else 0
        self.distinct = len(set(vals))
        ch = sum(1 for ts, vs in self.per_log.values() for a, b in zip(vs, vs[1:]) if a != b)
        steps = sum(max(0, len(vs) - 1) for ts, vs in self.per_log.values())
        self.trans = ch / steps if steps else 0.0
        # Mean step relative to the range: a physical value moves smoothly, the low byte
        # of a wider number jumps around.
        dsum = sum(abs(b - a) for ts, vs in self.per_log.values() for a, b in zip(vs, vs[1:]))
        rng = self.hi - self.lo
        self.jump = dsum / steps / rng if steps and rng else 0.0

    @property
    def name(self):
        if self.bit is not None:
            return f'байт {self.off} бит {self.bit}'
        n = TYPES[self.typ]
        return f'байт {self.off}' if n == 1 else f'байты {self.off}–{self.off + n - 1}'

    def window(self, li, t0, t1):
        ts, vs = self.per_log.get(li, ([], []))
        a, b = bisect.bisect_left(ts, t0), bisect.bisect_right(ts, t1)
        return vs[a:b]

    def effect(self, markers, kind, w):
        """Mean (after − before) / range over markers of one kind, and how many agree in sign."""
        rng = self.hi - self.lo
        if rng <= 0:
            return None
        ds = []
        for li, t, k, _ in markers:
            if k != kind:
                continue
            before = self.window(li, t - w, t - 1)
            after = self.window(li, t + 1, t + w)
            if before and after:
                ds.append((sum(after) / len(after) - sum(before) / len(before)) / rng)
        if not ds:
            return None
        mean = sum(ds) / len(ds)
        agree = sum(1 for d in ds if (d > 0) == (mean > 0) and abs(d) > 0.02) / len(ds)
        return mean, len(ds), agree

    def changed_at(self, markers, kind, w):
        """Share of markers of a kind where the value before differs from the value after."""
        hits = n = 0
        for li, t, k, _ in markers:
            if k != kind:
                continue
            before = self.window(li, t - w, t - 1)
            after = self.window(li, t + 1, t + w)
            if before and after:
                n += 1
                hits += before[-1] != after[-1] or set(before) != set(after)
        return (hits / n, n) if n else None

    def monotonic(self):
        inc = dec = 0
        for ts, vs in self.per_log.values():
            for a, b in zip(vs, vs[1:]):
                if b > a:
                    inc += 1
                elif b < a:
                    dec += 1
        return inc, dec


def clip(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def pick_scale(lo, hi, scales, lo_lim, hi_lim):
    """Largest scale that puts all values into [lo_lim, hi_lim]."""
    for s in scales:
        if lo * s >= lo_lim and hi * s <= hi_lim:
            return s
    return None


def fmt(v):
    if abs(v) >= 100 or float(v).is_integer():
        return '%g' % round(v, 1)
    return ('%.2f' % v).rstrip('0').rstrip('.')


def eff_text(e, kind):
    if not e:
        return ''
    mean, n, agree = e
    arrow = '↑' if mean > 0 else '↓'
    return f'«{KIND_RU[kind]}» {arrow}{abs(mean):.2f} ({n}×, {agree:.0%} согласны)'


def evaluate(group, markers, w, kinds_present):
    """Score every (offset, type) of a group as each known metric. Returns candidate dicts."""
    n = min(len(b) for b in group.packets)
    skip = set(group.fixed)
    if group.checksum:
        m = group.checksum
        end = n - (1 if m['width'] == 8 else 2) - (1 if m['at'].endswith('-1') else 0)
        skip |= set(range(end, n))
    cands = []

    def add(key, s, score, scale, why, typ=None, name=''):
        lo, hi = s.lo * scale, s.hi * scale
        cands.append({'group': group, 'key': key, 'name': name, 'off': s.off, 'type': typ or s.typ, 'scale': scale,
                      'score': round(score, 3), 'range': (lo, hi), 'why': why, 'series': s})

    series = {}
    for off in range(n):
        for typ, size in TYPES.items():
            if off + size > n or any(o in skip for o in range(off, off + size)):
                continue
            series[(off, typ)] = Series(group, off, typ)

    have = lambda k: k in kinds_present  # noqa: E731
    const = {off for off in range(n) if len({b[off] for b in group.packets}) == 1}
    for (off, typ), s in series.items():
        if s.n < 5:
            continue
        signed = typ.startswith('s')
        size = TYPES[typ]
        if size > 1:
            low = off if typ.endswith('le') else off + size - 1
            if low in const:         # steps of 256: the value really starts one byte later
                continue
        e = {k: s.effect(markers, k, w) for k in kinds_present}
        ev = lambda k: e[k][0] if e.get(k) else 0.0  # noqa: E731

        # speed: rises on throttle, falls on brake and stop, reaches ~0
        if typ in ('u8', 'u16le', 'u16be') and s.distinct > 2:
            sc = pick_scale(s.lo, s.hi, (1, 0.1, 0.01), 0, 45)
            if sc and s.hi * sc >= 3:
                score = 0.25 + clip(ev('throttle')) + 0.6 * clip(-ev('brake')) + 0.4 * clip(-ev('stop'))
                score += 0.2 if s.lo * sc < 0.5 else 0
                score -= 0.6 if s.jump > 0.1 else 0
                if not (have('throttle') or have('brake')):
                    score = min(score, 0.3)
                why = [f'{fmt(s.lo * sc)}…{fmt(s.hi * sc)} км/ч при ×{sc}'] + [eff_text(e.get(k), k) for k in ('throttle', 'brake', 'stop') if e.get(k)]
                add('speed_kmh', s, score, sc, why)

        # current: signed, up on throttle, down on release / brake / charge
        if typ in ('s16le', 's16be', 's8') or (typ in ('u16le', 'u16be') and s.hi < 32768):
            peak = max(abs(s.lo), abs(s.hi))
            sc = next((x for x in (0.1, 0.01, 0.001) if 0.5 <= peak * x <= 35), None)
            if sc and s.distinct > 2:
                score = 0.15 + clip(ev('throttle')) + 0.5 * clip(-ev('release')) + 0.6 * clip(-ev('brake')) + 0.6 * clip(-ev('charge_on'))
                score += 0.4 if signed and s.lo < 0 else (-0.2 if not signed else 0)
                score -= 0.6 if s.jump > 0.25 else 0
                if not (have('throttle') or have('brake') or have('charge_on')):
                    score = min(score, 0.3)
                why = [f'{fmt(s.lo * sc)}…{fmt(s.hi * sc)} А при ×{sc}'] + [eff_text(e.get(k), k) for k in ('throttle', 'release', 'brake', 'charge_on') if e.get(k)]
                add('current', s, score, sc, why)

        # pack voltage, 10S: 30–42 V
        if typ in ('u16le', 'u16be'):
            sc = pick_scale(s.lo, s.hi, (0.01, 0.1, 0.001), 26, 44)
            if sc and s.jump < 0.2:
                score = 0.45 + (0.15 if s.distinct > 1 else 0) + 0.4 * clip(-ev('throttle')) + 0.4 * clip(ev('charge_on'))
                why = [f'{fmt(s.lo * sc)}…{fmt(s.hi * sc)} В при ×{sc}'] + [eff_text(e.get(k), k) for k in ('throttle', 'charge_on') if e.get(k)]
                add('voltage', s, score, sc, why)

        # battery %: one byte 0–100 that moves slowly
        if typ == 'u8' and s.hi <= 100 and s.jump < 0.1:
            inc, dec = s.monotonic()
            score = 0.15
            if s.distinct > 1 and s.trans < 0.2:
                score += 0.3
            if inc + dec and max(inc, dec) / (inc + dec) > 0.8 and inc + dec >= 2:
                score += 0.2
            score += 0.3 * clip(ev('charge_on'))
            why = [f'{s.lo}…{s.hi} %', f'меняется в {s.trans:.1%} пакетов'] + ([eff_text(e.get('charge_on'), 'charge_on')] if e.get('charge_on') else [])
            add('battery_pct', s, score, 1, why)

        # controller temperature
        if typ in ('u8', 's8') and 5 <= s.lo and s.hi <= 90 and s.distinct > 1 and s.trans < 0.03:
            inc, dec = s.monotonic()
            score = 0.2 + (0.15 if inc > dec else 0)
            add('temp', s, score, 1, [f'{s.lo}…{s.hi} °C', f'меняется в {s.trans:.1%} пакетов'])

        # riding mode: 2–4 small values that change on «Сменил режим»
        if typ == 'u8' and s.hi <= 4 and 2 <= s.distinct <= 4:
            ch = s.changed_at(markers, 'mode', w)
            score = 0.15 + (0.8 * ch[0] if ch else 0) - clip(s.trans * 5, 0, 0.3)
            why = [f'значения {sorted(set(s.vals))}'] + ([f'меняется на «Сменил режим» в {ch[0]:.0%} случаев ({ch[1]}×)'] if ch else [])
            add('mode', s, score, 1, why)

        # odometer: monotonic counter that is not a per-packet sequence number
        if size >= 2 and typ in ('u16le', 'u16be', 'u24le', 'u32le', 'u32be'):
            inc, dec = s.monotonic()
            if inc >= 3 and dec == 0 and s.trans < 0.5:
                add('odometer_km', s, 0.35 + clip(inc / 50, 0, 0.3), 0.01,
                    [f'растёт без откатов: {inc} шагов', f'меняется в {s.trans:.0%} пакетов', 'масштаб не проверен'])

    # flags: bits that toggle on «Фара» / «Зарядка»
    for off in range(n):
        if off in skip or len({b[off] for b in group.packets}) > 8:
            continue
        for bit in range(8):
            s = Series(group, off, 'u8', bit)
            if s.distinct != 2:
                continue
            for kind, name in (('light', 'Фара (флаг)'), ('charge_on', 'Зарядка (флаг)')):
                ch = s.changed_at(markers, kind, w)
                if ch and ch[1] >= 1:
                    score = 0.2 + 0.7 * ch[0] - clip(s.trans * 5, 0, 0.4)
                    add('custom', s, score, 1, [f'бит {bit} переключается на «{KIND_RU[kind]}» в {ch[0]:.0%} случаев ({ch[1]}×)',
                                                f'меняется в {s.trans:.1%} пакетов'], typ='bit%d' % bit, name=name)
    return cands


def byte_map(group):
    """One line per byte: constant / counter / flag / slow / fast."""
    n = min(len(b) for b in group.packets)
    out = []
    for off in range(n):
        s = Series(group, off, 'u8')
        inc, dec = s.monotonic()
        if s.distinct == 1:
            kind = 'const %02X' % s.lo
        elif s.trans > 0.9 and dec <= max(1, inc // 200):
            kind = 'счётчик пакетов'
        elif s.distinct == 2:
            kind = 'флаг/2 значения'
        elif s.trans < 0.02:
            kind = f'медленный ({s.distinct} зн.)'
        else:
            kind = f'быстрый ({s.distinct} зн.)'
        out.append((off, kind, s))
    return out


def reactions(groups, markers, w, kind, top=5):
    res = []
    for g in groups:
        if len(g.items) < 5:
            continue
        n = min(len(b) for b in g.packets)
        for off in range(n):
            if off in g.fixed:
                continue
            for typ in ('u8', 'u16le', 's16le'):
                if off + TYPES[typ] > n:
                    continue
                s = Series(g, off, typ)
                e = s.effect(markers, kind, w)
                if e and abs(e[0]) > 0.05:
                    res.append((abs(e[0]), g, s, e))
    res.sort(key=lambda r: -r[0])
    return res[:top]


# ---------------------------------------------------------------- report

def rel(path):
    try:
        return os.path.relpath(path, ROOT)
    except ValueError:
        return path


def stream_texts(logs, min_len=4):
    """Printable lines in the byte stream of each characteristic, joined across packets."""
    out = collections.OrderedDict()
    for lg in logs:
        streams = collections.OrderedDict()
        for e in lg['entries']:
            if e['k'] in ('rx', 'tx') and e.get('h'):
                streams.setdefault((e['k'], e.get('u') or ''), bytearray()).extend(bl.entry_bytes(e))
        for key, buf in streams.items():
            text = ''.join(chr(b) if 32 <= b < 127 else '\n' for b in buf)
            for line in text.split('\n'):
                if len(line.strip()) >= min_len and sum(c.isalnum() for c in line) >= min_len:
                    out.setdefault(key, collections.Counter())[line.strip()] += 1
    return out


def collect_markers(logs):
    out = []
    for li, lg in enumerate(logs):
        for e in lg['entries']:
            if e['k'] == 'mk':
                out.append((li, e['t'], marker_kind(e.get('n')), e.get('n') or ''))
    return out


def choose_fields(cands, min_score):
    """Best candidate per metric; one byte range is never used twice."""
    used = set()
    chosen = []
    is_bit = lambda c: c['type'].startswith('bit')  # noqa: E731
    best = sorted(cands, key=lambda c: (is_bit(c), -c['score'], -TYPES.get(c['type'], 1)))
    seen_keys = set()
    for c in best:
        k = (c['key'], c['name'])
        if k in seen_keys or c['score'] < min_score:
            continue
        span = {(id(c['group']), o) for o in range(c['off'], c['off'] + TYPES.get(c['type'], 1))}
        if span & used:
            continue
        if is_bit(c):
            span = {(id(c['group']), c['off'], c['type'])}
            if span & used:
                continue
        used |= span
        seen_keys.add(k)
        chosen.append(c)
    return chosen


def odo_scale(chosen):
    """Fix the odometer scale by the distance the chosen speed field adds up to."""
    spd = next((c for c in chosen if c['key'] == 'speed_kmh'), None)
    odo = next((c for c in chosen if c['key'] == 'odometer_km'), None)
    if not spd or not odo:
        return
    km = raw = 0.0
    for li, (ts, vs) in spd['series'].per_log.items():
        for t0, t1, v in zip(ts, ts[1:], vs):
            if t1 - t0 < 5000:
                km += v * spd['scale'] * (t1 - t0) / 3600000
        ots, ovs = odo['series'].per_log.get(li, ([], []))
        if len(ovs) > 1:
            raw += ovs[-1] - ovs[0]
    if km < 0.05 or raw <= 0:
        return
    ratio = km / raw
    exp = round(math.log10(ratio))
    if abs(math.log10(ratio) - exp) < 0.2 and -4 <= exp <= 0:
        odo['scale'] = 10.0 ** exp if exp else 1
        odo['why'] = [w for w in odo['why'] if w != 'масштаб не проверен'] + [
            f'масштаб по скорости: {km:.2f} км на {raw:g} шагов → ×{fmt(odo["scale"])}']
        odo['score'] += 0.3
    else:
        odo['why'].append(f'по скорости выходит ×{ratio:.3g} — масштаб не круглый, проверь')


def confidence(score):
    return 'высокая' if score >= 1.2 else 'средняя' if score >= 0.75 else 'низкая'


def candidate_decoder(chosen, groups, sources, current):
    version = (current or {}).get('version', 0)
    try:
        version = int(version) + 1
    except (TypeError, ValueError):
        version = 1
    fields = []
    for c in chosen:
        g = c['group']
        u = bl.norm_uuid(g.u) or g.u
        fields.append({
            'key': c['key'], 'name': c['name'] or None, 'uuid': u, 'match': g.pattern, 'offset': c['off'],
            'type': c['type'], 'scale': c['scale'], 'add': 0,
            'confidence': confidence(c['score']), 'evidence': [x for x in c['why'] if x],
        })
    for f in fields:
        if f['name'] is None:
            del f['name']
    checks = []
    for g in groups:
        if g.checksum:
            checks.append({'uuid': bl.norm_uuid(g.u) or g.u, 'match': g.pattern, 'length': g.length,
                           'checksum': describe_checksum(g.checksum, g.length)})
    return {
        'format': bl.DECODER_FORMAT, 'version': version,
        'updatedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds').replace('+00:00', 'Z'),
        'note': 'Черновик tools/analyze_log.py. Проверь поля с низкой уверенностью перед переносом в current.json.',
        'sources': sources, 'json': False, 'fields': fields, 'checksums': checks,
    }


def advice(kinds_present, chosen):
    keys = {c['key'] for c in chosen} | {c['name'] for c in chosen}
    tips = []
    if 'throttle' not in kinds_present or 'brake' not in kinds_present:
        tips.append('Нужны метки «Газ» и «Тормоз» (лучше 3–4 раза каждую, с паузой 5–10 с между ними) — по ним ищутся скорость и ток.')
    if 'release' not in kinds_present:
        tips.append('Ставь «Отпустил газ», когда отпускаешь курок: ток падает сразу, скорость — нет, так их легче различить.')
    if 'mode' not in kinds_present:
        tips.append('Смени режим 3–4 раза (двойное нажатие кнопки), каждый раз с меткой «Сменил режим».')
    if 'light' not in kinds_present:
        tips.append('Включи и выключи фару 3 раза с меткой «Фара».')
    if 'charge_on' not in kinds_present:
        tips.append('Если Bluetooth работает на зарядке: подключи зарядку с меткой «Зарядка вкл», через минуту отключи с меткой «Зарядка выкл».')
    if 'speed_kmh' not in keys and 'throttle' in kinds_present:
        tips.append('Скорость не нашлась: проверь, что самокат реально ехал (или колесо крутилось на весу) после метки «Газ».')
    if 'battery_pct' not in keys and 'voltage' not in keys:
        tips.append('Заряд и напряжение не найдены: запиши лог подлиннее (10+ минут езды) — заряд меняется медленно.')
    return tips


def analyze(paths, out_path, write_candidate=True, window_s=3.0, min_score=0.6, stream=sys.stdout):
    notes = []
    logs = bl.load_logs(paths, notes)
    p = lambda *a: print(*a, file=stream)  # noqa: E731
    for msg in notes:
        p('! ' + msg)
    if not logs:
        p('Нет логов для разбора.')
        return None
    w = int(window_s * 1000)

    for lg in logs:
        es = lg['entries']
        dur = (es[-1]['t'] - es[0]['t']) / 1000 if es else 0
        mk = collections.Counter(e.get('n') for e in es if e['k'] == 'mk')
        p(f"== {', '.join(rel(f) for f in lg['_files'])}")
        p(f"   начало {lg.get('startedAt')}, записей {len(es)}, {dur:.1f} с" + (', демо' if lg.get('demo') else '') + (', обрезан' if lg.get('trimmed') else ''))
        p('   метки: ' + (', '.join(f'{k}×{v}' for k, v in mk.most_common()) if mk else 'нет'))

    # characteristics
    p('\n## Характеристики')
    stats = collections.OrderedDict()
    for li, lg in enumerate(logs):
        for e in lg['entries']:
            if e['k'] in ('rx', 'rd', 'tx'):
                st = stats.setdefault(bl.char_key(e), {'k': collections.Counter(), 'lens': collections.Counter(), 'ts': collections.defaultdict(list), 'pref': collections.Counter()})
                b = bl.entry_bytes(e)
                st['k'][e['k']] += 1
                st['lens'][len(b)] += 1
                st['ts'][li].append(e['t'])
                for n in (1, 2, 3, 4):
                    if len(b) >= n:
                        st['pref'][bl.hex_of(b[:n])] += 1
    for (s, u), st in stats.items():
        total = sum(st['k'].values())
        span = sum((max(ts) - min(ts)) / 1000 for ts in st['ts'].values() if len(ts) > 1)
        rate = f', {total / span:.1f} пак/с' if span > 0 else ''
        p(f"  {bl.short_uuid(u)} (сервис {bl.short_uuid(s)}): " + ', '.join(f'{k} {v}' for k, v in st['k'].items()) + rate)
        p('     длины: ' + ', '.join(f'{n}×{c}' for n, c in st['lens'].most_common(6)))
        common = [(h, c) for h, c in st['pref'].most_common(12) if c >= max(3, total * 0.1)]
        longest = {}
        for h, c in common:
            longest[c] = h if len(h) > len(longest.get(c, '')) else longest[c]
        if longest:
            p('     частые начала: ' + ', '.join(f'{h} ({c / total:.0%})' for c, h in sorted(longest.items(), reverse=True)[:4]))

    # groups
    groups = build_groups(logs)
    markers = collect_markers(logs)
    kinds_present = {k for _, _, k, _ in markers if k}
    p('\n## Группы пакетов')
    all_cands = []
    for g in groups:
        if len(g.items) < 5:
            continue
        ts = collections.defaultdict(list)
        for li, t, _ in g.items:
            ts[li].append(t)
        span = sum((max(x) - min(x)) / 1000 for x in ts.values() if len(x) > 1)
        rate = f', {len(g.items) / span:.1f}/с' if span > 0 else ''
        p(f"  [{g.label}] {bl.short_uuid(g.u)}  начало «{g.pattern or 'любое'}»  длина {g.length}  ×{len(g.items)}{rate}")
        cs = find_checksums(g.packets)
        g.checksum = cs[0] if cs else None
        if cs:
            p('      контрольная сумма: ' + describe_checksum(cs[0], g.length) + (f' (и ещё {len(cs) - 1} совпадений)' if len(cs) > 1 else ''))
        else:
            p('      контрольная сумма: не найдена среди XOR, SUM8/16 и популярных CRC8/CRC16')
        bm = byte_map(g)
        line = []
        for off, kind, s in bm:
            if off in g.fixed:
                continue
            line.append(f'{off}:{kind}')
        if line:
            p('      байты: ' + '; '.join(line))
        counters = [off for off, kind, _ in bm if kind == 'счётчик пакетов']
        if counters:
            p(f"      ! байт {', '.join(map(str, counters))} растёт с каждым пакетом — номер пакета, не телеметрия")
        all_cands += evaluate(g, markers, w, kinds_present)

    # candidates per metric
    p('\n## Кандидаты на поля')
    by_key = collections.defaultdict(list)
    for c in all_cands:
        by_key[(c['key'], c['name'])].append(c)
    order = ['speed_kmh', 'current', 'voltage', 'battery_pct', 'mode', 'temp', 'odometer_km', 'custom']
    for key, name in sorted(by_key, key=lambda k: (order.index(k[0]) if k[0] in order else 99, k[1])):
        lst = sorted(by_key[(key, name)], key=lambda c: (-c['score'], -TYPES.get(c['type'], 1)))[:3]
        p(f"  {name or key}:")
        for c in lst:
            g = c['group']
            p(f"    [{g.label}] {c['series'].name} {c['type']} ×{c['scale']}  — оценка {c['score']:.2f} ({confidence(c['score'])}); " + '; '.join(x for x in c['why'] if x))
    if not by_key:
        p('  нет: данных мало или пакеты не меняются')

    # reactions to markers
    if markers:
        p('\n## Что реагирует на метки (окно ±%g с)' % window_s)
        labels = collections.OrderedDict()
        for _, _, k, n in markers:
            labels.setdefault(k or ('custom:' + n), n)
        for k, n in labels.items():
            if k.startswith('custom:'):
                kind = k
                custom = [(li, t, k, n2) for li, t, kk, n2 in markers if not kk and n2 == n]
                rs = reactions(groups, custom, w, k)
            else:
                kind = k
                rs = reactions(groups, markers, w, kind)
            title = n if k.startswith('custom:') else KIND_RU[k]
            if rs:
                p(f'  «{title}»: ' + '; '.join(f"[{g.label}] {s.name} {s.typ} {'↑' if e[0] > 0 else '↓'}{abs(e[0]):.2f}" for _, g, s, e in rs))
            else:
                p(f'  «{title}»: ничего заметного (нужны пакеты и до, и после метки)')

    # phone writes
    tx = collections.Counter()
    first = {}
    for lg in logs:
        for e in lg['entries']:
            if e['k'] == 'tx':
                key = (bl.short_uuid(e.get('u')), e.get('h') or '')
                tx[key] += 1
                first.setdefault(key, e['t'])
    if tx:
        p('\n## Записи в самокат (tx)')
        for (u, h), c in sorted(tx.items(), key=lambda kv: first[kv[0]])[:40]:
            p(f'  {first[(u, h)] / 1000:8.2f} с  {u}  {h}  ×{c}')
        if len(tx) > 40:
            p(f'  … и ещё {len(tx) - 40} разных')

    texts = stream_texts(logs)
    if texts:
        p('\n## Текст в потоках (так выглядят AT-команды и отладочный вывод)')
        for (k, u), lines in texts.items():
            p(f"  {bl.short_uuid(u)} ({'от самоката' if k == 'rx' else 'от телефона'}):")
            for line, c in lines.most_common(12):
                p(f'    «{line}» ×{c}')

    reads = collections.OrderedDict()
    for lg in logs:
        for e in lg['entries']:
            if e['k'] == 'rd':
                b = bl.entry_bytes(e)
                txt = b.decode('ascii', 'replace') if b and all(32 <= x < 127 or x == 0 for x in b) else ''
                reads[bl.short_uuid(e.get('u'))] = (e.get('h'), txt.rstrip('\0'))
    if reads:
        p('\n## Прочитанные значения')
        for u, (h, txt) in reads.items():
            p(f'  {u}: {h}' + (f'  «{txt}»' if txt else ''))

    chosen = choose_fields(all_cands, min_score)
    odo_scale(chosen)
    tips = advice(kinds_present, chosen)
    if tips:
        p('\n## Что записать в следующий раз')
        for t in tips:
            p('  - ' + t)

    sources = sorted({rel(f) for lg in logs for f in lg['_files']})
    current = None
    cur_path = os.path.join(ROOT, 'decoders', 'current.json')
    if os.path.exists(cur_path):
        try:
            current = bl.read_json_bytes(cur_path)
        except (OSError, ValueError):
            current = None
    dec = candidate_decoder(chosen, groups, sorted(set((current or {}).get('sources', [])) | set(sources)), current)
    if write_candidate and out_path:
        os.makedirs(os.path.dirname(out_path) or '.', exist_ok=True)
        with open(out_path, 'w', encoding='utf-8') as fh:
            json.dump(dec, fh, ensure_ascii=False, indent=1)
            fh.write('\n')
        p(f'\nЧерновик декодера: {rel(out_path)} — полей {len(dec["fields"])}')
    return {'logs': logs, 'groups': groups, 'candidates': all_cands, 'chosen': chosen, 'decoder': dec, 'notes': notes}


def new_logs(logs_dir, current_path):
    try:
        cur = bl.read_json_bytes(current_path)
        known = set(cur.get('sources') or [])
    except (OSError, ValueError):
        known = set()
    out = []
    for p in bl.expand_paths([logs_dir]):
        if rel(p) not in known:
            out.append(p)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description='Разбор логов Bird Air Lab')
    ap.add_argument('paths', nargs='*', help='файлы логов или папки')
    ap.add_argument('--new', action='store_true', help='взять из logs/ файлы, которых нет в sources у decoders/current.json')
    ap.add_argument('--out', default=os.path.join(ROOT, 'decoders', 'candidate.json'), help='куда писать черновик декодера')
    ap.add_argument('--no-candidate', action='store_true', help='не писать черновик декодера')
    ap.add_argument('--window', type=float, default=3.0, help='окно до и после метки, с (3)')
    ap.add_argument('--min-score', type=float, default=0.6, help='порог оценки для черновика (0.6)')
    a = ap.parse_args(argv)
    paths = list(a.paths)
    if a.new:
        paths += new_logs(os.path.join(ROOT, 'logs'), os.path.join(ROOT, 'decoders', 'current.json'))
        if not paths:
            print('Новых логов нет: все файлы из logs/ уже есть в sources у decoders/current.json.')
            return 0
    if not paths:
        ap.error('укажи файл лога, папку или --new')
    res = analyze(paths, a.out, not a.no_candidate, a.window, a.min_score)
    return 0 if res else 1


if __name__ == '__main__':
    sys.exit(main())
