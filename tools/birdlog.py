"""Чтение и запись логов Bird Air Lab (формат birdair-lab-log v1).

Общий модуль для analyze_log.py, parse_btsnoop.py и ble_logger.py. Только стандартная
библиотека Python 3.
"""
import datetime
import gzip
import json
import os
import re
import zlib

FORMAT = 'birdair-lab-log'
DECODER_FORMAT = 'birdair-lab-decoder'
KINDS = ('rx', 'rd', 'tx', 'mk', 'sys')
BASE = '-0000-1000-8000-00805f9b34fb'

# Characteristics and services the tools must never write to (firmware update).
TELINK_OTA_SVC = '00010203-0405-0607-0809-0a0b0c0d1912'
TELINK_OTA_CHR = '00010203-0405-0607-0809-0a0b0c0d2b12'


def norm_uuid(s):
    """'ffe1', '0xFFE1', 32-bit or full form -> full lower-case UUID; anything else -> None."""
    s = str(s or '').strip().lower()
    if re.fullmatch(r'0x[0-9a-f]{4}', s):
        s = s[2:]
    if re.fullmatch(r'[0-9a-f]{4}', s):
        return '0000' + s + BASE
    if re.fullmatch(r'[0-9a-f]{8}', s):
        return s + BASE
    if re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', s):
        return s
    return None


def short_uuid(u):
    u = u or ''
    if u.startswith('0000') and u.endswith(BASE):
        return '0x' + u[4:8].upper()
    return u


def is_danger(svc, chr_):
    """Same rule as the page: Telink OTA, Nordic DFU, Silabs OTA and friends."""
    svc, chr_ = str(svc or ''), str(chr_ or '')
    return (chr_ == TELINK_OTA_CHR or svc == TELINK_OTA_SVC
            or svc in (norm_uuid('fe59'), norm_uuid('fef5'))
            or chr_.startswith('8ec9') or svc.startswith('8ec9') or svc.startswith('00001530-1212'))


def hex_of(b):
    return ' '.join('%02X' % x for x in b)


def bytes_of(h):
    clean = re.sub(r'[^0-9a-fA-F]', '', str(h or '').replace('0x', ''))
    if len(clean) % 2:
        return b''
    return bytes.fromhex(clean)


def entry_bytes(e):
    b = e.get('_b')
    if b is None:
        b = bytes_of(e.get('h'))
        e['_b'] = b
    return b


def char_key(e):
    return (e.get('s') or '', e.get('u') or '')


def new_log(device=None, started_at=None, gatt=None):
    if started_at is None:
        started_at = datetime.datetime.now(datetime.timezone.utc)
    if isinstance(started_at, datetime.datetime):
        started_at = started_at.astimezone(datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    return {
        'format': FORMAT, 'version': 1, 'startedAt': started_at,
        'device': device or {'name': 'scooter', 'id': 'scooter'},
        'demo': False, 'trimmed': False, 'gatt': gatt or [],
        'decoder': {'json': False, 'fields': []}, 'entries': [],
    }


# Characteristics whose values identify one particular scooter.
NAME_CHARS = {norm_uuid('2a00'), norm_uuid('2a25')}   # Device Name, Serial Number String
ZERO_CHARS = {norm_uuid('2a23')}                      # System ID (derived from the MAC)


def secret_names(*names):
    """The strings to hide for a device name: the name and any serial-like run of 6+ digits in it."""
    out = []
    for n in names:
        if n and str(n) != 'scooter':
            out.append(str(n))
            out += re.findall(r'\d{6,}', str(n))
    return out


def mask_in_streams(entries, secrets):
    """Replace secret bytes with '*' inside packets, also when one is split across consecutive
    packets of the same characteristic (a UART bridge cuts text into 20-byte notifications)."""
    pats = sorted({s.encode('utf-8') for s in secrets if len(s.encode('utf-8')) >= 4}, key=len, reverse=True)
    if not pats:
        return
    streams = {}
    for e in entries:
        if e.get('h') and e.get('k') in ('rx', 'rd', 'tx') and e.get('u') not in NAME_CHARS | ZERO_CHARS:
            streams.setdefault((e['k'], e.get('s'), e.get('u')), []).append(e)
    for es in streams.values():
        bufs = [bytearray(bytes_of(e['h'])) for e in es]
        flat = b''.join(bufs)
        where = [(i, j) for i, b in enumerate(bufs) for j in range(len(b))]
        hit = False
        for pat in pats:
            pos = flat.find(pat)
            while pos >= 0:
                for k in range(pos, pos + len(pat)):
                    i, j = where[k]
                    bufs[i][j] = 0x2A
                hit = True
                pos = flat.find(pat, pos + 1)
        if hit:
            for e, b in zip(es, bufs):
                e['h'] = hex_of(b)
                e.pop('_b', None)


def anonymize(log, extra=()):
    """Same as the page's «Обезличить»: device name and id -> 'scooter'.

    Also replaces those strings (and any of `extra`, e.g. MAC addresses) inside text
    entries, blanks Device Name / Serial Number / System ID values and masks the device
    name and its serial digits inside packets (same length, '*' bytes), also across
    packet boundaries.
    """
    dev = log.get('device') or {}
    secrets = [s for s in secret_names(dev.get('name'), dev.get('id'), *extra) if len(s) >= 2]
    secrets.sort(key=len, reverse=True)
    log['device'] = {'name': 'scooter', 'id': 'scooter'}
    scooter = hex_of(b'scooter')
    # A short secret (a 2–3 letter name) only counts as a whole word, so «MTU» or «LE …» survive.
    words = {s: re.compile(r'(?<![0-9A-Za-zА-Яа-яЁё])' + re.escape(s) + r'(?![0-9A-Za-zА-Яа-яЁё])') for s in secrets if len(s) < 4}
    entries = log.get('entries', [])
    for e in entries:
        if e.get('n'):
            for s in secrets:
                e['n'] = words[s].sub('scooter', e['n']) if s in words else e['n'].replace(s, 'scooter')
        if e.get('h') is None:
            continue
        u = e.get('u')
        if u in NAME_CHARS:
            e['h'] = scooter
        elif u in ZERO_CHARS:
            e['h'] = hex_of(bytes(len(bytes_of(e['h']))))
        e.pop('_b', None)
    mask_in_streams(entries, secrets)
    return log


def clean_entry(e):
    out = {'t': e['t'], 'k': e['k']}
    for k in ('s', 'u', 'h', 'n'):
        if e.get(k) is not None:
            out[k] = e[k]
    return out


def dumps(log):
    o = dict(log)
    o['entries'] = [clean_entry(e) for e in log.get('entries', [])]
    return json.dumps(o, ensure_ascii=False, separators=(',', ':'))


def save_log(log, path):
    data = dumps(log).encode('utf-8')
    if path.endswith('.gz'):
        data = gzip.compress(data, mtime=0)
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    tmp = path + '.tmp'
    with open(tmp, 'wb') as fh:
        fh.write(data)
    os.replace(tmp, path)


def read_json_bytes(path):
    with open(path, 'rb') as fh:
        data = fh.read()
    if data[:2] == b'\x1f\x8b':
        try:
            data = gzip.decompress(data)
        except (EOFError, zlib.error, gzip.BadGzipFile) as ex:
            raise ValueError('архив .gz повреждён или обрезан') from ex
    return json.loads(data.decode('utf-8'))


def load_file(path):
    o = read_json_bytes(path)
    if not isinstance(o, dict) or o.get('format') != FORMAT or not isinstance(o.get('entries'), list):
        raise ValueError(f'{path}: не лог Bird Air Lab (нужен format = {FORMAT})')
    entries = []
    for e in o['entries']:
        if isinstance(e, dict) and isinstance(e.get('t'), (int, float)) and e.get('k') in KINDS:
            entries.append(e)
    entries.sort(key=lambda e: e['t'])
    o['entries'] = entries
    o['_files'] = [path]
    return o


def merge_parts(parts):
    """Join the parts of one log, ordered by part.index. Notes missing parts in _missing."""
    parts = sorted(parts, key=lambda p: (p.get('part') or {}).get('index', 0))
    out = dict(parts[0])
    out['entries'] = sorted((e for p in parts for e in p['entries']), key=lambda e: e['t'])
    out['_files'] = [f for p in parts for f in p['_files']]
    count = (parts[0].get('part') or {}).get('count') or len(parts)
    have = {(p.get('part') or {}).get('index') for p in parts}
    out['_missing'] = [i for i in range(1, count + 1) if i not in have]
    out.pop('part', None)
    return out


def expand_paths(paths):
    """Files as given; folders walked recursively for .json / .json.gz, hidden folders skipped."""
    out = []
    for p in paths:
        if os.path.isdir(p):
            for d, dirs, files in os.walk(p):
                dirs[:] = sorted(x for x in dirs if not x.startswith('.'))
                for name in sorted(files):
                    if name.endswith(('.json', '.json.gz')) and not name.startswith('.'):
                        out.append(os.path.join(d, name))
        else:
            out.append(p)
    return out


def _entry_key(e):
    return (e['t'], e['k'], e.get('u'), e.get('h'), e.get('n'))


def is_resend(short, long):
    """Is `short` an earlier copy of `long`: its first and last entries are in `long` and
    its time span lies inside that of `long`?"""
    a, b = short['entries'], long['entries']
    if not a:
        return True
    if not b or a[0]['t'] < b[0]['t'] or a[-1]['t'] > b[-1]['t']:
        return False
    have = {_entry_key(e) for e in b}
    return _entry_key(a[0]) in have and _entry_key(a[-1]) in have


def load_logs(paths, notes=None):
    """Load files, glue parts of one log together and drop re-sent copies.

    The page names a log by when the recording started, so a log sent twice (once more
    after recording a bit longer) shows up as two files with the same startedAt; only the
    longer one is kept, and only if it contains the shorter one (a different recording
    may share startedAt too). Files of a dropped copy are listed in `_dropped` of the log
    that was kept.
    """
    notes = notes if notes is not None else []
    singles, groups = [], {}
    for path in expand_paths(paths):
        try:
            o = load_file(path)
        except (OSError, ValueError) as ex:
            notes.append(f'пропущен {path}: {ex}')
            continue
        part = o.get('part')
        if isinstance(part, dict) and part.get('id'):
            # An interrupted re-send can leave parts of another packing (1of2 next to 1of3…3of3):
            # each packing is a log of its own, the re-send check below keeps the longest.
            groups.setdefault((part['id'], part.get('count')), []).append(o)
        else:
            singles.append(o)
    logs = singles + [merge_parts(g) for g in groups.values()]
    by_start = {}
    for lg in logs:
        by_start.setdefault((lg.get('startedAt'), (lg.get('device') or {}).get('name')), []).append(lg)
    kept = []
    for same in by_start.values():
        same.sort(key=lambda lg: -len(lg['entries']))
        mine = []
        for lg in same:
            big = next((k for k in mine if is_resend(lg, k)), None)
            if big is None:
                mine.append(lg)
                continue
            notes.append(f"{lg['_files'][0]}: повтор более длинного {big['_files'][0]}, пропущен")
            big.setdefault('_dropped', []).extend(lg['_files'])
        kept += mine
    for lg in kept:
        if lg.get('_missing'):
            notes.append(f"{lg['_files'][0]}: не хватает частей {lg['_missing']}")
    return sorted(kept, key=lambda lg: str(lg.get('startedAt')))


TRANSLIT = dict(zip('абвгдеёжзийклмнопрстуфхцчшщъыьэюя',
                    ['a', 'b', 'v', 'g', 'd', 'e', 'e', 'zh', 'z', 'i', 'y', 'k', 'l', 'm', 'n', 'o', 'p', 'r', 's', 't', 'u',
                     'f', 'h', 'ts', 'ch', 'sh', 'sch', '', 'y', '', 'e', 'yu', 'ya']))


def slug(label, default='log'):
    s = ''.join(TRANSLIT.get(c, c) for c in str(label or '').lower())
    s = re.sub(r'[^a-z0-9]+', '-', s).strip('-')[:40].strip('-')
    return s or default


def log_name(started, label, ext='.json'):
    """YYYYMMDD-HHMMSS-<label>.json in local time, like the page."""
    if isinstance(started, str):
        started = datetime.datetime.fromisoformat(started.replace('Z', '+00:00'))
    return started.astimezone().strftime('%Y%m%d-%H%M%S') + '-' + slug(label) + ext
