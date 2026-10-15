#!/usr/bin/env python3
"""Разбор HCI snoop log Android (btsnoop_hci.log) в лог Bird Air Lab.

    python3 tools/parse_btsnoop.py btsnoop_hci.log
    python3 tools/parse_btsnoop.py bugreport.zip --label bird-app
    python3 tools/parse_btsnoop.py btsnoop_hci.log --conn 0x0040 -o logs/app.json

Как снять: «Параметры разработчика» → «Журнал HCI Bluetooth» → «Включено» (не «Включено
с фильтром»), выключи и включи Bluetooth, поработай с самокатом в приложении Bird, затем
`adb bugreport bugreport.zip`. Журнал лежит в архиве в FS/data/misc/bluetooth/logs/;
архив можно передать прямо так.

Достаёт операции ATT одного подключения (и его переподключений к тому же самокату):
уведомления самоката (rx), чтения (rd), записи телефона (tx) и служебные события (sys:
подключение, MTU, шифрование, сопряжение SMP, ошибки ATT, включение уведомлений). Пишет лог
birdair-lab-log для страницы и tools/analyze_log.py и печатает сводку: карту GATT, что шлёт
приложение и признаки авторизации. Лог обезличен, если не указан --keep-ids.

Только стандартная библиотека. Самокат не трогает: читается только файл.
"""
import argparse
import collections
import datetime
import os
import re
import struct
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAGIC = b'btsnoop\0'
EPOCH_DELTA_US = 0x00dcddb30f2f8000          # 0000-01-01 -> 1970-01-01, microseconds
UNIX_EPOCH = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)
LINKS = {1001: 'HCI без обёртки (1001)', 1002: 'HCI UART/H4 (1002)'}
H4_CMD, H4_ACL, H4_EVT = 0x01, 0x02, 0x04
MAX_RECORD = 0x10100                         # an HCI packet is at most 4 + 65535 bytes; longer = corrupted
MAX_US = 4102444800 * 1000000                # 2100-01-01
CID_ATT, CID_SMP = 0x0004, 0x0006
MAX_LINES = 60                               # summary lines per list

INCLUDE, CHAR_DECL = bl.norm_uuid('2802'), bl.norm_uuid('2803')
CCCD = bl.norm_uuid('2902')
CCCD_VALUES = (b'\x00\x00', b'\x01\x00', b'\x02\x00')
CCCD_TEXT = {0: 'Выключены уведомления', 1: 'Включены уведомления', 2: 'Включены индикации',
             3: 'Включены уведомления и индикации'}

# ---------------------------------------------------------------- protocol tables

ATT_OPS = {
    0x01: 'Error Rsp', 0x02: 'Exchange MTU Req', 0x03: 'Exchange MTU Rsp', 0x04: 'Find Information Req',
    0x05: 'Find Information Rsp', 0x06: 'Find By Type Value Req', 0x07: 'Find By Type Value Rsp',
    0x08: 'Read By Type Req', 0x09: 'Read By Type Rsp', 0x0A: 'Read Req', 0x0B: 'Read Rsp',
    0x0C: 'Read Blob Req', 0x0D: 'Read Blob Rsp', 0x0E: 'Read Multiple Req', 0x0F: 'Read Multiple Rsp',
    0x10: 'Read By Group Type Req', 0x11: 'Read By Group Type Rsp', 0x12: 'Write Req', 0x13: 'Write Rsp',
    0x16: 'Prepare Write Req', 0x17: 'Prepare Write Rsp', 0x18: 'Execute Write Req', 0x19: 'Execute Write Rsp',
    0x1B: 'Notification', 0x1D: 'Indication', 0x1E: 'Confirmation', 0x20: 'Read Multiple Variable Req',
    0x21: 'Read Multiple Variable Rsp', 0x23: 'Multiple Handle Value Notification', 0x52: 'Write Cmd',
    0xD2: 'Signed Write Cmd',
}
REQUESTS = {0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x12, 0x16, 0x18, 0x20}
RESPONSES = {0x01, 0x03, 0x05, 0x07, 0x09, 0x0B, 0x0D, 0x0F, 0x11, 0x13, 0x17, 0x19, 0x21}
CLIENT_OPS = REQUESTS | {0x1E, 0x52, 0xD2}   # sent by the GATT client
SERVER_OPS = RESPONSES | {0x1B, 0x1D, 0x23}  # sent by the GATT server
DISCOVERY = {0x04, 0x06, 0x08, 0x10}         # 'Attribute Not Found' is how these end
HOW = {'req': 'Write Req', 'cmd': 'Write Cmd', 'signed': 'Signed Write', 'prepared': 'Prepare+Execute'}

ATT_ERRORS = {
    0x01: 'Invalid Handle — нет такого хэндла', 0x02: 'Read Not Permitted — чтение запрещено',
    0x03: 'Write Not Permitted — запись запрещена', 0x04: 'Invalid PDU — неверный пакет',
    0x05: 'Insufficient Authentication — нужна аутентификация (сопряжение)',
    0x06: 'Request Not Supported — запрос не поддерживается', 0x07: 'Invalid Offset — неверное смещение',
    0x08: 'Insufficient Authorization — нет авторизации', 0x0A: 'Attribute Not Found — атрибут не найден',
    0x0C: 'Insufficient Encryption Key Size — короткий ключ шифрования',
    0x0D: 'Invalid Attribute Value Length — неверная длина значения',
    0x0E: 'Unlikely Error — прошивка отказала без объяснений', 0x0F: 'Insufficient Encryption — нужно шифрование',
}
AUTH_ERRORS = {0x05, 0x08, 0x0C, 0x0F}

SMP_CODES = {
    0x01: 'запрос сопряжения (Pairing Request)', 0x02: 'ответ на сопряжение (Pairing Response)',
    0x03: 'подтверждение (Pairing Confirm)', 0x04: 'случайное число (Pairing Random)',
    0x05: 'сопряжение не удалось (Pairing Failed)', 0x06: 'ключ LTK (Encryption Information)',
    0x07: 'EDIV и Rand ключа (Central Identification)', 0x08: 'ключ IRK (Identity Information)',
    0x09: 'постоянный адрес (Identity Address Information)', 0x0A: 'ключ подписи CSRK (Signing Information)',
    0x0B: 'просит сопряжение (Security Request)', 0x0C: 'открытый ключ (Pairing Public Key)',
    0x0D: 'проверка DHKey (Pairing DHKey Check)', 0x0E: 'нажатие клавиши при вводе PIN (Keypress Notification)',
}
SMP_FAIL = {
    0x01: 'не введён PIN', 0x02: 'нет данных OOB', 0x03: 'требования к защите невыполнимы',
    0x04: 'не совпало подтверждение', 0x05: 'сопряжение не поддерживается', 0x06: 'слишком короткий ключ',
    0x07: 'команда не поддерживается', 0x08: 'причина не указана', 0x09: 'слишком много попыток',
    0x0A: 'неверные параметры', 0x0B: 'не прошла проверка DHKey', 0x0C: 'не совпали числа',
}
IO_CAPS = {0x00: 'только дисплей', 0x01: 'дисплей и да/нет', 0x02: 'только клавиатура',
           0x03: 'нет ввода и вывода', 0x04: 'клавиатура и дисплей'}
HCI_REASONS = {
    0x05: 'ошибка аутентификации', 0x06: 'нет ключа (PIN or Key Missing): сопряжение забыто на одной из сторон',
    0x08: 'связь потеряна (таймаут)', 0x13: 'самокат закрыл соединение', 0x16: 'телефон закрыл соединение',
    0x22: 'таймаут LL', 0x3B: 'неприемлемые параметры соединения', 0x3D: 'ошибка MIC: сбой шифрования',
    0x3E: 'соединение не установилось',
}


def u16(b, i=0):
    return b[i] | b[i + 1] << 8


def mac(b):
    """6 bytes little-endian -> 'AA:BB:CC:DD:EE:FF'."""
    return ':'.join('%02X' % x for x in reversed(b))


def uuid_of(b):
    """UUID as sent in ATT (little-endian) -> canonical lower-case string, or None."""
    if len(b) == 2:
        return bl.norm_uuid('%04x' % u16(b))
    if len(b) == 4:
        return bl.norm_uuid('%08x' % struct.unpack('<I', b)[0])
    if len(b) == 16:
        h = bytes(reversed(b)).hex()
        return f'{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}'
    return None


def short(u):
    return bl.short_uuid(u)


def reason(code):
    return (f'0x{code:02X} ' + HCI_REASONS.get(code, '')).strip()


def att_error(code):
    name = ATT_ERRORS.get(code) or ('ошибка приложения' if 0x80 <= code <= 0x9F else 'ошибка')
    return f'0x{code:02X} {name}'


def auth_text(a):
    yes = lambda bit: 'да' if a & bit else 'нет'  # noqa: E731
    return f"bonding {'да' if (a & 3) == 1 else 'нет'}, MITM {yes(4)}, SC {yes(8)}"


def pair_text(io, oob, auth, key):
    return f"IO «{IO_CAPS.get(io, '0x%02X' % io)}», {auth_text(auth)}, OOB {'да' if oob else 'нет'}, ключ до {key} байт"


def pair_method(req, rsp):
    """Pairing method from the IO capabilities and AuthReq of both sides (Core Vol 3 Part H 2.3.5.1)."""
    (io1, oob1, a1), (io2, oob2, a2) = req, rsp
    sc = a1 & a2 & 8
    jw = 'Just Works (без PIN и без защиты от MITM)'
    if (oob1 or oob2) if sc else (oob1 and oob2):
        how = 'OOB'
    elif not ((a1 | a2) & 4) or 3 in (io1, io2):
        how = jw
    elif sc and {io1, io2} <= {1, 4}:
        how = 'Numeric Comparison (сравнение чисел)'
    elif {2, 4} & {io1, io2}:
        how = 'Passkey (ввод PIN)'
    else:
        how = jw
    return how + (', LE Secure Connections' if sc else ', LE Legacy')


def ad_name(data):
    """Device name from advertising data: complete (0x09) wins over shortened (0x08)."""
    i, found = 0, None
    while i + 1 < len(data) and data[i]:
        n, typ = data[i], data[i + 1]
        if typ in (0x08, 0x09):
            name = data[i + 2:i + 1 + n].decode('utf-8', 'replace').strip('\0 ')
            if name and (typ == 0x09 or found is None):
                found = name
        i += 1 + n
    return found


def props_of(p):
    return {'read': bool(p & 0x02), 'write': bool(p & 0x08), 'wnr': bool(p & 0x04),
            'notify': bool(p & 0x10), 'indicate': bool(p & 0x20)}


# ---------------------------------------------------------------- one connection

class Conn:
    """One LE connection: raw ATT operations and what discovery said about the handles."""

    def __init__(self, num, handle, addr, us, role=None):
        self.num, self.handle, self.addr, self.role = num, handle, addr, role
        self.rpa = None
        self.start_us = self.end_us = us
        self.ops = []                          # resolved to UUIDs only when the log is built
        self.count = collections.Counter()     # phone as GATT client
        self.server = collections.Counter()    # the scooter as client of the phone's GATT server
        self.pending = None                    # outstanding request of the phone: (opcode, params)
        self.mtu_ask = {}
        self.services = {}                     # start -> (end, uuid)
        self.decls = {}                        # value handle -> (declaration handle, properties, uuid)
        self.uuids = {}                        # handle -> uuid from Find Information
        self.hints = {}                        # handle -> uuid from Read By Type of a characteristic
        self.queue = []                        # prepared writes (handle, offset, value)
        self.last_read = {}
        self.pairing = {}                      # SMP opcode 1/2 -> (io, oob, auth)
        self.id_addrs = set()

    def sys(self, us, text, tag=None):
        self.ops.append({'us': us, 'k': 'sys', 'n': text, 'tag': tag})

    def read(self, us, h, v):
        self.count['read'] += 1
        op = {'us': us, 'k': 'rd', 'h': h, 'v': v}
        self.ops.append(op)
        self.last_read[h] = op

    def write(self, us, h, v, how):
        self.count['write'] += 1
        self.ops.append({'us': us, 'k': 'wr', 'h': h, 'v': v, 'how': how})

    def notify(self, us, h, v, indication):
        self.count['indicate' if indication else 'notify'] += 1
        self.ops.append({'us': us, 'k': 'rx', 'h': h, 'v': v})

    def att(self, us, sent, pdu):
        op, p = pdu[0], pdu[1:]
        if op in CLIENT_OPS:
            phone = sent                       # True: the phone is the GATT client
        elif op in SERVER_OPS:
            phone = not sent
        else:
            return
        if op in (0x02, 0x03):
            return self.mtu(us, phone, op, p)
        if not phone:
            # The scooter talks to the phone's own GATT server: only counted for the summary.
            if op not in RESPONSES:
                self.server[ATT_OPS[op]] += 1
            return
        self.count['att'] += 1
        if op in REQUESTS:
            self.pending = (op, p)             # ATT allows one outstanding request per client
        if op == 0x01:
            self.pending = None
            if len(p) >= 4 and not (p[3] == 0x0A and p[0] in DISCOVERY):
                self.ops.append({'us': us, 'k': 'err', 'req': p[0], 'h': u16(p, 1), 'code': p[3]})
        elif op in RESPONSES:
            req, self.pending = self.pending, None
            if req:
                self.response(us, req[0], req[1], op, p)
        elif op in (0x12, 0x52) and len(p) >= 2:
            self.write(us, u16(p), p[2:], 'req' if op == 0x12 else 'cmd')
        elif op == 0xD2 and len(p) >= 14:
            self.write(us, u16(p), p[2:-12], 'signed')    # 12-byte signature at the end
        elif op == 0x16 and len(p) >= 4:
            self.queue.append((u16(p), u16(p, 2), p[4:]))
        elif op == 0x18 and p:
            if p[0] == 0x01:
                vals = {}
                for h, off, v in self.queue:
                    buf = vals.setdefault(h, bytearray())
                    buf.extend(bytes(max(0, off - len(buf))))   # parts may come in any order
                    buf[off:off + len(v)] = v
                for h, v in vals.items():
                    self.write(us, h, bytes(v), 'prepared')
            self.queue = []
        elif op in (0x1B, 0x1D) and len(p) >= 2:
            self.notify(us, u16(p), p[2:], op == 0x1D)
        elif op == 0x23:
            i = 0
            while i + 4 <= len(p):
                n = u16(p, i + 2)
                self.notify(us, u16(p, i), p[i + 4:i + 4 + n], False)
                i += 4 + n

    def mtu(self, us, phone, op, p):
        # Either side may ask; the result applies to both directions.
        if len(p) < 2:
            return
        if op == 0x02:
            self.mtu_ask[phone] = u16(p)
            return
        ask, got = self.mtu_ask.pop(phone, None), u16(p)
        who = ('телефон', 'самокат') if phone else ('самокат', 'телефон')
        self.sys(us, f'MTU {min(ask, got) if ask else got}: {who[0]} предложил {ask or "?"}, {who[1]} ответил {got}', 'mtu')

    def response(self, us, req, q, op, p):
        """The phone's request `req` with params q answered by `op` with params p."""
        if req == 0x10 and op == 0x11 and p and p[0] >= 6:
            n = p[0]
            for i in range(1, len(p) - n + 1, n):
                u = uuid_of(p[i + 4:i + n])
                if u:
                    self.services[u16(p, i)] = (u16(p, i + 2), u)
        elif req == 0x06 and op == 0x07 and len(q) >= 6 and u16(q, 4) in (0x2800, 0x2801):
            u = uuid_of(q[6:])
            for i in range(0, len(p) - 3, 4):
                if u:
                    self.services[u16(p, i)] = (u16(p, i + 2), u)
        elif req == 0x08 and op == 0x09 and len(q) >= 6 and p and p[0] > 2:
            typ, n = uuid_of(q[4:]), p[0]
            for i in range(1, len(p) - n + 1, n):
                h, v = u16(p, i), p[i + 2:i + n]
                if typ == CHAR_DECL:
                    u = uuid_of(v[3:])
                    if len(v) >= 5 and u:
                        self.decls[u16(v, 1)] = (h, v[0], u)
                elif typ and typ != INCLUDE:   # Read Using Characteristic UUID
                    self.hints[h] = typ
                    self.read(us, h, v)
        elif req == 0x04 and op == 0x05 and p and p[0] in (1, 2):
            n = 4 if p[0] == 1 else 18
            for i in range(1, len(p) - n + 1, n):
                self.uuids[u16(p, i)] = uuid_of(p[i + 2:i + n])
        elif req == 0x0A and op == 0x0B and len(q) >= 2:
            self.read(us, u16(q), p)
        elif req == 0x0C and op == 0x0D and len(q) >= 4:
            h, off = u16(q), u16(q, 2)
            prev = self.last_read.get(h)
            if prev is not None and off:
                prev['v'] = prev['v'][:off] + p   # long value: append to the previous read
            else:
                self.read(us, h, p)
        elif req == 0x0E and op == 0x0F:
            hs = [u16(q, i) for i in range(0, len(q) - 1, 2)]
            self.ops.append({'us': us, 'k': 'rdm', 'hs': hs, 'v': p})
        elif req == 0x20 and op == 0x21:
            i = 0
            for h in [u16(q, j) for j in range(0, len(q) - 1, 2)]:
                if i + 2 > len(p):
                    break
                n = u16(p, i)
                self.read(us, h, p[i + 2:i + 2 + n])
                i += 2 + n

    def smp(self, us, sent, p):
        code = p[0]
        text = SMP_CODES.get(code, f'команда 0x{code:02X}')
        if code in (0x01, 0x02) and len(p) >= 7:
            text += ': ' + pair_text(p[1], p[2], p[3], p[4])
            self.pairing[code] = (p[1], p[2], p[3])
        elif code == 0x05 and len(p) >= 2:
            text += ': ' + SMP_FAIL.get(p[1], f'причина 0x{p[1]:02X}')
        elif code == 0x0B and len(p) >= 2:
            text += ': ' + auth_text(p[1])
        elif code == 0x09 and len(p) >= 8:
            self.id_addrs.add(mac(p[2:8]))
        # Key values (LTK, IRK, CSRK) are never copied into the log.
        self.sys(us, f"SMP, {'телефон' if sent else 'самокат'}: {text}", 'smp')


# ---------------------------------------------------------------- the whole file

class Capture:
    """Everything parse() took from one btsnoop file."""

    def __init__(self):
        self.source = ''
        self.link = None
        self.records = self.truncated = self.broken = 0
        self.cut = False
        self.first_us = self.last_us = None
        self.conns = []                        # LE connections in order of appearance
        self.active = {}                       # handle -> Conn while connected
        self.classic = set()                   # BR/EDR handles, their ACL data is skipped
        self.partial = {}                      # (handle, sent) -> L2CAP bytes so far
        self.names = {}                        # address -> advertised name
        self.local = set()                     # the phone's own addresses

    def read(self, fh):
        head = fh.read(16)
        if len(head) < 16 or head[:8] != MAGIC:
            raise ValueError('это не btsnoop: нет заголовка «btsnoop». Нужен btsnoop_hci.log из bugreport')
        version, self.link = struct.unpack('>II', head[8:])
        if version != 1 or self.link not in LINKS:
            raise ValueError(f'btsnoop версии {version} с типом канала {self.link} не поддерживается (нужен 1001 или 1002)')
        while True:
            rh = fh.read(24)
            if len(rh) < 24:
                self.cut = self.cut or bool(rh)
                break
            orig, incl, flags, _drops, ts = struct.unpack('>IIIIq', rh)
            data = fh.read(incl) if incl <= MAX_RECORD else b''
            if len(data) < incl:
                self.cut = True
                break
            us = ts - EPOCH_DELTA_US
            if not 0 <= us < MAX_US:
                raise ValueError(f'запись {self.records + 1}: время вне 1970–2100 — файл повреждён или это не журнал Android')
            self.records += 1
            if self.first_us is None:
                self.first_us = us
            self.last_us = us
            sent = not flags & 1               # bit 0: 0 = host -> controller
            if self.link == 1002:
                if not data:
                    continue
                typ, data = data[0], data[1:]
            else:
                typ = (H4_CMD if sent else H4_EVT) if flags & 2 else H4_ACL
            if incl < orig:
                self.truncated += 1
                if typ == H4_ACL and len(data) >= 2:
                    h = u16(data) & 0xFFF
                    self.partial.pop((h, sent), None)
                    if sent and h in self.active:
                        # Maybe the phone's next request: its response must not go to the last one.
                        self.active[h].pending = None
                continue
            if typ == H4_EVT:
                self.event(us, data)
            elif typ == H4_CMD:
                self.command(us, data)
            elif typ == H4_ACL:
                self.acl(us, sent, data)

    def event(self, us, d):
        if len(d) < 2:
            return
        code, p = d[0], d[2:2 + d[1]]
        if code == 0x3E and p:
            self.le_event(us, p[0], p[1:])
        elif code == 0x05 and len(p) >= 4 and p[0] == 0:          # Disconnection Complete
            h = u16(p, 1) & 0xFFF
            self.classic.discard(h)
            for key in ((h, True), (h, False)):
                self.partial.pop(key, None)
            c = self.active.pop(h, None)
            if c:
                c.end_us = us
                c.sys(us, 'Отключение: ' + reason(p[3]), 'disconn')
        elif code in (0x08, 0x59) and len(p) >= 4:                # Encryption Change (v1, v2)
            c = self.active.get(u16(p, 1) & 0xFFF)
            if c and p[0]:
                c.sys(us, 'Шифрование не включилось: ' + reason(p[0]), 'enc_fail')
            elif c:
                c.sys(us, 'Канал зашифрован (Encryption Change)' if p[3] else 'Шифрование канала выключено',
                      'enc' if p[3] else None)
        elif code == 0x03 and len(p) >= 11 and p[0] == 0:         # BR/EDR Connection Complete
            self.classic.add(u16(p, 1) & 0xFFF)
        elif code == 0x0E and len(p) >= 10 and u16(p, 1) == 0x1009 and p[3] == 0:
            self.local.add(mac(p[4:10]))                         # Read BD_ADDR: the phone itself

    def le_event(self, us, sub, p):
        if sub in (0x01, 0x0A, 0x29) and len(p) >= 11:            # LE (Enhanced) Connection Complete
            if p[0]:
                return
            h = u16(p, 1) & 0xFFF
            c = Conn(len(self.conns) + 1, h, mac(p[5:11]), us, role=p[3])
            if sub != 0x01 and len(p) >= 23:
                if any(p[11:17]):
                    self.local.add(mac(p[11:17]))
                if any(p[17:23]):
                    c.rpa = mac(p[17:23])
            self.conns.append(c)
            self.active[h] = c
            self.classic.discard(h)
            c.ops.append({'us': us, 'k': 'conn', 'c': c})
        elif sub in (0x02, 0x0D) and p:                           # LE (Extended) Advertising Report
            ext, i = sub == 0x0D, 1
            for _ in range(p[0]):
                head = 24 if ext else 9
                if i + head > len(p):
                    return
                addr = mac(p[i + 3:i + 9] if ext else p[i + 2:i + 8])
                n = p[i + head - 1]
                name = ad_name(p[i + head:i + head + n])
                i += head + n + (0 if ext else 1)                 # legacy reports end with RSSI
                if name and (addr not in self.names or len(name) > len(self.names[addr])):
                    self.names[addr] = name

    def command(self, us, d):
        if len(d) < 3:
            return
        op, p = u16(d), d[3:3 + d[2]]
        if op == 0x2019 and len(p) >= 2:                          # LE Enable Encryption
            c = self.active.get(u16(p) & 0xFFF)
            if c:
                c.sys(us, 'Телефон запускает шифрование канала (LE Start Encryption)', 'enc_start')
        elif op == 0x2005 and len(p) >= 6:                        # LE Set Random Address
            self.local.add(mac(p[:6]))

    def acl(self, us, sent, d):
        if len(d) < 4:
            return
        hf, n = struct.unpack_from('<HH', d)
        h, pb, frag = hf & 0xFFF, (hf >> 12) & 3, d[4:4 + n]
        if h in self.classic:
            return
        key = (h, sent)
        if pb == 0b01:                                            # continuation
            if key not in self.partial:
                self.broken += 1
                return
            buf = self.partial.pop(key) + frag
        else:                                                     # start of an L2CAP PDU
            if self.partial.pop(key, None) is not None:
                self.broken += 1
            buf = frag
        if len(buf) < 4 or len(buf) < 4 + u16(buf):
            self.partial[key] = buf
            return
        ln, cid = struct.unpack_from('<HH', buf)
        pdu = buf[4:4 + ln]
        if cid not in (CID_ATT, CID_SMP) or not pdu:
            return
        c = self.active.get(h)
        if c is None:
            c = Conn(len(self.conns) + 1, h, None, us)
            c.sys(us, f'Подключение 0x{h:04X} установлено до начала журнала HCI', 'conn')
            self.conns.append(c)
            self.active[h] = c
        c.end_us = us
        if cid == CID_ATT:
            c.att(us, sent, pdu)
        else:
            c.smp(us, sent, pdu)

    def secrets(self):
        """Addresses and names that must not get into a public log."""
        out = set(self.local)
        for c in self.conns:
            out.update(x for x in (c.addr, c.rpa, self.names.get(c.addr), self.names.get(c.rpa)) if x)
            out.update(c.id_addrs)
        return sorted(out)


def parse(path):
    cap = Capture()
    with open(path, 'rb') as fh:
        is_zip = fh.read(2) == b'PK'
    if is_zip:
        with zipfile.ZipFile(path) as z:
            found = [i for i in z.infolist() if os.path.basename(i.filename) == 'btsnoop_hci.log']
            if not found:
                raise ValueError(f'{path}: в архиве нет btsnoop_hci.log — журнал HCI был выключен?')
            member = max(found, key=lambda i: i.file_size)
            cap.source = os.path.basename(path) + ':' + member.filename
            with z.open(member) as fh:
                cap.read(fh)
    else:
        cap.source = os.path.basename(path)
        with open(path, 'rb') as fh:
            cap.read(fh)
    return cap


# ---------------------------------------------------------------- building the log

class Gatt:
    """Handle -> UUIDs of one device, merged from the discovery in its connections."""

    def __init__(self, conns):
        services, self.decls, self.uuids, self.hints = {}, {}, {}, {}
        for c in conns:
            services.update(c.services)
            self.decls.update(c.decls)
            self.uuids.update(c.uuids)
            self.hints.update(c.hints)
        self.services = sorted((s, e, u) for s, (e, u) in services.items())
        self.decl_at = sorted(d[0] for d in self.decls.values())
        self.cache = {}

    @property
    def empty(self):
        return not self.services and not self.decls

    def service(self, h):
        return next((u for s, e, u in self.services if s <= h <= e), None)

    def owner(self, h):
        """Value handle of the characteristic that descriptor handle h belongs to, or None."""
        if h in self.decls or h in self.decl_at:
            return None
        v = max((x for x in self.decls if x < h), default=None)
        if v is None or self.service(v) != self.service(h) or any(v < d < h for d in self.decl_at):
            return None
        return v

    def resolve(self, h):
        """-> (service, characteristic, descriptor); descriptor is None for a value handle, '' if unknown."""
        if h not in self.cache:
            svc = self.service(h) or 'unknown'
            v = self.owner(h)
            if h in self.decls:
                res = (svc, self.decls[h][2], None)
            elif v is not None:
                res = (svc, self.decls[v][2], self.uuids.get(h) or '')
            else:
                res = (svc, self.hints.get(h) or 'handle-0x%04x' % h, None)
            self.cache[h] = res
        return self.cache[h]

    def target(self, h):
        s, u, d = self.resolve(h)
        if d is None:
            return short(u)
        return f"{short(d) if d else 'дескриптор'} 0x{h:04X} у {short(u)}"

    def spans(self):
        if self.services:
            return self.services
        return [(0, 0xFFFF, 'unknown')] if self.decls else []

    def as_log(self):
        out = []
        for s, e, su in self.spans():
            chars = []
            for v in sorted(x for x in self.decls if s <= x <= e):
                ch = {'uuid': self.decls[v][2], 'props': props_of(self.decls[v][1])}
                if bl.is_danger(su, ch['uuid']):
                    ch['danger'] = True        # OTA/DFU: parsed, but nothing may ever write there
                chars.append(ch)
            out.append({'uuid': su, 'chars': chars})
        return out


def make_entry(cap, g, op, notified):
    k = op['k']
    if k == 'sys':
        return {'k': 'sys', 'n': op['n'], '_tag': op.get('tag')}
    if k == 'conn':
        c = op['c']
        name = cap.names.get(c.addr) or cap.names.get(c.rpa)
        role = ', телефон — периферия' if c.role == 1 else ''
        return {'k': 'sys', 'n': f'Подключение к {c.addr}' + (f' «{name}»' if name else '') + f' (handle 0x{c.handle:04X}{role})',
                '_tag': 'conn'}
    if k == 'rdm':
        return {'k': 'sys', 'n': 'Чтение нескольких (Read Multiple) ' + ', '.join(g.target(h) for h in op['hs'])
                + ': ' + bl.hex_of(op['v'])}
    if k == 'err':
        what = ATT_OPS.get(op['req'], f"запрос 0x{op['req']:02X}") + (' ' + g.target(op['h']) if op['h'] else '')
        return {'k': 'sys', 'n': f"Ошибка ATT: {what} → {att_error(op['code'])}", '_tag': 'err', '_code': op['code']}
    h, v = op['h'], op['v']
    s, u, d = g.resolve(h)
    if k == 'rx':
        return {'k': 'rx', 's': s, 'u': u, 'h': bl.hex_of(v)}
    if k == 'rd':
        if d is not None:
            return {'k': 'sys', 'n': f'Чтение дескриптора {g.target(h)}: {bl.hex_of(v)}'}
        return {'k': 'rd', 's': s, 'u': u, 'h': bl.hex_of(v)}
    # A write. CCCD writes switch notifications on and off; they are not commands to the scooter.
    # Without discovery a Write Req of 01 00 right after a notifying handle is taken for a CCCD.
    unknown = d is None and u.startswith('handle-') and h - 1 in notified
    guess = d != CCCD and (d == '' or unknown) and op['how'] == 'req' and v in CCCD_VALUES
    if d == CCCD or guess:
        char = g.resolve(h - 1)[1] if unknown else u
        mode = v[0] & 3 if v else 0
        text = f'{CCCD_TEXT[mode]}: {short(char)}' + (f' (хэндл 0x{h:04X} — вероятно, CCCD)' if guess else '')
        return {'k': 'sys', 'n': text, '_tag': 'cccd', '_cccd': (char, mode)}
    if d is not None:
        return {'k': 'sys', 'n': f'Запись дескриптора {g.target(h)}: {bl.hex_of(v)}'}
    return {'k': 'tx', 's': s, 'u': u, 'h': bl.hex_of(v), '_how': op['how']}


def score(c):
    return (c.count['notify'] + c.count['indicate'] + c.count['write'], c.count['att'])


def select(cap, conn=None, addr=None):
    """Connections that go into the log: the chosen one and reconnections to the same device."""
    if not cap.conns:
        raise ValueError('в журнале нет подключений BLE')
    cands = cap.conns
    have = ', '.join(sorted({f'0x{c.handle:04X}' for c in cap.conns}))
    if conn is not None:
        cands = [c for c in cands if c.handle == conn]
        if not cands:
            raise ValueError(f'нет подключения с handle 0x{conn:04X}; есть: {have}')
    if addr:
        cands = [c for c in cands if addr in (c.addr, c.rpa)]
        if not cands:
            raise ValueError(f'нет подключения к {addr}')
    best = max(cands, key=score)
    if conn is None and addr is None and score(best) == (0, 0):
        raise ValueError(f'в журнале нет обмена ATT по BLE (подключения: {have})')
    return [c for c in cands if c is best or (best.addr and c.addr == best.addr)], best


def build_log(cap, sel, best, keep_ids=False):
    """Turn the selected connections into a birdair-lab-log. Returns (log, gatt)."""
    addrs = {c.addr for c in sel if c.addr}
    g = Gatt([c for c in cap.conns if c in sel or c.addr in addrs])
    ops = sorted((op for c in sel for op in c.ops), key=lambda op: op['us'])
    notified = {op['h'] for op in ops if op['k'] == 'rx'}
    t0 = ops[0]['us']
    entries = []
    for op in ops:
        e = make_entry(cap, g, op, notified)
        e['t'] = round((op['us'] - t0) / 1000)
        entries.append(e)
    started = UNIX_EPOCH + datetime.timedelta(microseconds=t0)
    log = bl.new_log(started_at=started, gatt=g.as_log())
    log['entries'] = entries
    extra, read_name = cap.secrets(), None
    for e in entries:
        if e['k'] == 'rd' and e['u'] in bl.NAME_CHARS:
            txt = bl.bytes_of(e['h']).decode('utf-8', 'replace').strip('\0 ')
            if txt:
                extra.append(txt)
                read_name = read_name or (txt if e['u'] == bl.norm_uuid('2a00') else None)
    name = cap.names.get(best.addr) or cap.names.get(best.rpa) or read_name
    if keep_ids:
        log['device'] = {'name': name or 'btsnoop', 'id': best.addr or 'btsnoop'}
    else:
        log['device'] = {'name': name or '', 'id': best.addr or ''}
        # anonymize() masks names inside packet values only; sys lines quote raw bytes too
        # (Read Multiple, descriptors), so mask them there the same way.
        for raw in {s.encode('utf-8') for s in (name, *extra) if s}:
            if len(raw) >= 4:
                for e in entries:
                    if e['k'] == 'sys':
                        e['n'] = e['n'].replace(bl.hex_of(raw), bl.hex_of(b'*' * len(raw)))
        bl.anonymize(log, extra=extra)
    return log, g


def default_out(first_us, label=None):
    started = UNIX_EPOCH + datetime.timedelta(microseconds=first_us)
    return os.path.join(ROOT, 'logs', bl.log_name(started, label or 'btsnoop'))


# ---------------------------------------------------------------- summary

def clock(us, date=False):
    t = (UNIX_EPOCH + datetime.timedelta(microseconds=us)).astimezone()
    return t.strftime('%Y-%m-%d %H:%M:%S' if date else '%H:%M:%S')


def mask_addr(a):
    return '**:**:**:**:' + a[-5:] if a else 'адрес неизвестен'


def spans(pos):
    """[4, 5, 6, 9] -> '4–6, 9'."""
    out, start = [], None
    for i, x in enumerate(pos):
        start = x if start is None else start
        if i + 1 == len(pos) or pos[i + 1] != x + 1:
            out.append(str(x) if start == x else f'{start}–{x}')
            start = None
    return ', '.join(out)


def common_starts(pkts):
    """Frequent first bytes as [(hex, share)], the longest prefix per share."""
    cnt = collections.Counter(bl.hex_of(b[:n]) for b in pkts for n in (1, 2, 3, 4) if len(b) >= n)
    best = {}
    for h, c in cnt.items():
        if c >= max(2, len(pkts) * 0.1) and len(h) > len(best.get(c, '')):
            best[c] = h
    return [(h, c / len(pkts)) for c, h in sorted(best.items(), reverse=True)[:4]]


def varying_writes(tx):
    """Writes with the same header whose other bytes change: a counter or challenge/response?"""
    groups = collections.OrderedDict()
    for e in tx:
        b = bl.entry_bytes(e)
        if len(b) >= 2:
            groups.setdefault((e['u'], len(b), b[:min(4, len(b) // 2)]), []).append(b)
    out = []
    for (u, n, head), bs in groups.items():
        if len(set(bs)) < 2:
            continue
        pos = [i for i in range(len(head), n) if len({b[i] for b in bs}) > 1]
        line = (f'{short(u)} «{bl.hex_of(head)} …» (длина {n}, ×{len(bs)}, разных {len(set(bs))}): '
                + (f'меняется байт {pos[0]}' if len(pos) == 1 else f'меняются байты {spans(pos)}'))
        counters = [i for i in pos if all((y[i] - x[i]) & 0xFF == 1 for x, y in zip(bs, bs[1:]))]
        if counters:
            line += f'; растут на 1 с каждой записью: {spans(counters)}'
        if n - 1 in pos and len(pos) > 1:
            line += '; последний, видимо, контрольная сумма'
        out.append(line)
    return out


def summary(cap, sel, log, g, keep_ids):
    lines = []
    p = lines.append
    es = log['entries']
    p(f'== {cap.source}: {LINKS[cap.link]}, записей {cap.records}, {clock(cap.first_us, True)} – {clock(cap.last_us)}'
      f' ({(cap.last_us - cap.first_us) / 1e6:.0f} с)')

    p('\n## Подключения BLE')
    for c in cap.conns:
        name = cap.names.get(c.addr) or cap.names.get(c.rpa)
        n = c.count
        p(f"  #{c.num} 0x{c.handle:04X} {c.addr if keep_ids and c.addr else mask_addr(c.addr)}" + (f' «{name}»' if name else '')
          + f" {clock(c.start_us)}–{clock(c.end_us)}: уведомлений {n['notify'] + n['indicate']}, записей {n['write']},"
          f" чтений {n['read']}, всего ATT {n['att']}" + ('  ← в логе' if c in sel else ''))

    p('\n## GATT самоката')
    if g.empty:
        p('  нет: обнаружения сервисов в журнале нет (см. предупреждение)')
    for s, e, su in g.spans():
        p(f'  {short(su)}  0x{s:04X}–0x{e:04X}' + ('  — OTA/DFU, не трогать' if bl.is_danger(su, '') else ''))
        for v in sorted(x for x in g.decls if s <= x <= e):
            _, props, cu = g.decls[v]
            descs = [f"{'CCCD' if du == CCCD else short(du)}@0x{h:04X}" for h, du in sorted(g.uuids.items()) if du and g.owner(h) == v]
            p(f"    {short(cu)}  0x{v:04X}  {' '.join(k for k, on in props_of(props).items() if on) or '-'}"
              + (f"  {', '.join(descs)}" if descs else '') + ('  — OTA, не трогать' if bl.is_danger(su, cu) else ''))
    srv = collections.Counter()
    for c in sel:
        srv.update(c.server)
    if srv:
        p('  самокат обращался к GATT-серверу телефона: ' + ', '.join(f'{k} ×{v}' for k, v in srv.most_common()))

    tx = [e for e in es if e['k'] == 'tx']
    p('\n## Что шлёт приложение (записи телефона)')
    groups = collections.OrderedDict()
    for e in tx:
        groups.setdefault((e['u'], e['h']), []).append(e)
    for (u, h), lst in list(groups.items())[:MAX_LINES]:
        e = lst[0]
        why = bl.danger_write(bl.bytes_of(h))
        p(f"  {e['t'] / 1000:8.2f} с  {short(u)}  {h or '(пусто)'}  ×{len(lst)}  {HOW[e['_how']]}"
          + ('  — запись в OTA!' if bl.is_danger(e['s'], u) else '') + (f'\n             ОПАСНО: {why}' if why else ''))
    if len(groups) > MAX_LINES:
        p(f'  … и ещё {len(groups) - MAX_LINES} разных')
    if not tx:
        p('  записей нет')

    p('\n## Уведомления самоката')
    by = collections.OrderedDict()
    for e in es:
        if e['k'] == 'rx':
            by.setdefault(e['u'], []).append(e)
    for u, lst in by.items():
        pkts = [bl.entry_bytes(e) for e in lst]
        span = (lst[-1]['t'] - lst[0]['t']) / 1000
        lens = collections.Counter(len(b) for b in pkts)
        p(f'  {short(u)}: {len(lst)} шт.' + (f', {len(lst) / span:.1f}/с' if span > 0 else '')
          + ', длины ' + ', '.join(f'{n}×{c}' for n, c in lens.most_common(5)))
        starts = common_starts(pkts)
        if starts:
            p('     частые начала: ' + ', '.join(f'{h} ({share:.0%})' for h, share in starts))
    if not by:
        p('  уведомлений нет')

    reads = collections.OrderedDict()
    for e in es:
        if e['k'] == 'rd':
            b = bl.entry_bytes(e)
            txt = b.decode('ascii', 'replace').rstrip('\0') if b and all(32 <= x < 127 or x == 0 for x in b) else ''
            reads[e['u']] = (e['h'], txt)
    if reads:
        p('\n## Прочитано')
        for u, (h, txt) in list(reads.items())[:MAX_LINES]:
            p(f'  {short(u)}: {h}' + (f'  «{txt}»' if txt else ''))

    p('\n## Признаки авторизации')
    signs = []
    tagged = lambda tag: [e for e in es if e.get('_tag') == tag]  # noqa: E731
    smp = tagged('smp')
    if smp:
        signs.append('сопряжение SMP')
        p('  сопряжение (SMP):')
        for e in smp[:8]:
            p(f"    {e['t'] / 1000:8.2f} с  {e['n']}")
        pairing = {}
        for c in sel:
            pairing.update(c.pairing)
        if 1 in pairing and 2 in pairing:
            p('    способ: ' + pair_method(pairing[1], pairing[2]))
    else:
        p('  сопряжения (SMP) в журнале нет')
    enc = tagged('enc')
    if enc:
        signs.append('шифрование канала')
        p(f"  канал зашифрован с {enc[0]['t'] / 1000:.2f} с"
          + ('' if smp else ' — сопряжения в журнале нет, значит ключ остался от прежнего (bonding)'))
    else:
        p('  шифрования канала нет: всё идёт открытым текстом')
    for e in tagged('enc_fail'):
        p(f"  {e['n']}")
    errs = collections.Counter(e['n'] for e in tagged('err') if e['_code'] in AUTH_ERRORS)
    other = sum(1 for e in tagged('err') if e['_code'] not in AUTH_ERRORS)
    if errs:
        signs.append('отказы ATT по доступу')
        for n, c in errs.most_common():
            p(f'  {n} ×{c}')
    else:
        p('  отказов ATT по доступу (аутентификация, шифрование, авторизация) нет')
    if other:
        p(f'  прочих ошибок ATT: {other} (строки «Ошибка ATT» в логе)')
    if any(e['_how'] == 'signed' for e in tx):
        signs.append('подписанные записи (Signed Write)')

    on = [i for i, e in enumerate(es) if e.get('_tag') == 'cccd' and e['_cccd'][1]]
    first_tx = [e for e in es[on[0] + 1 if on else 0:] if e['k'] == 'tx'][:5]
    if first_tx:
        p('  первые записи после включения уведомлений (кандидат на рукопожатие):' if on
          else '  первые записи приложения (включения уведомлений в журнале нет):')
        for e in first_tx:
            when = f"+{e['t'] - es[on[0]]['t']} мс" if on else f"{e['t'] / 1000:.2f} с"
            p(f"    {when:>10}  {short(e['u'])}  {e['h']}")
    for u in by:
        i_rx = next(i for i, e in enumerate(es) if e['k'] == 'rx' and e['u'] == u)
        i_on = next((i for i in on if i < i_rx and es[i]['_cccd'][0] == u), None)
        before = [e for e in es[(i_on + 1 if i_on is not None else 0):i_rx] if e['k'] == 'tx']
        t_rx = es[i_rx]['t']
        if before:
            w = before[-1]
            p(f"  первое уведомление {short(u)} — через {t_rx - w['t']} мс после записи {short(w['u'])} {w['h']}"
              + (f"; уведомления включены за {t_rx - es[i_on]['t']} мс до него, до этой записи самокат молчал" if i_on is not None else ''))
            if i_on is not None:
                signs.append(f'уведомления {short(u)} пошли только после записи')
        elif i_on is not None:
            p(f"  уведомления {short(u)} пошли сразу после включения (через {t_rx - es[i_on]['t']} мс), без записей")
        else:
            p(f'  уведомления {short(u)} идут с начала лога (включены до него)')
    var = varying_writes(tx)
    if var:
        signs.append('записи с меняющимися байтами')
        p('  записи с одинаковым началом, но разными байтами (счётчик или challenge/response?):')
        for line in var[:MAX_LINES]:
            p('    ' + line)
    if signs:
        p('  Итог: есть признаки — ' + '; '.join(signs) + '. Подробности выше.')
    else:
        p('  Итог: признаков авторизации не видно (нет сопряжения, шифрования, отказов ATT и меняющихся записей).')
    return lines


def check_warnings(cap, sel, log, g, keep_ids):
    out = []
    if cap.truncated:
        out.append(f'обрезано записей: {cap.truncated} — похоже, журнал HCI в режиме «Включено с фильтром» (Enabled Filtered). '
                   'В параметрах разработчика выбери «Включено» (Enabled), выключи и включи Bluetooth и запиши снова.')
    if cap.cut:
        out.append(f'журнал обрывается после записи {cap.records} (файл скопирован во время записи или повреждён), '
                   'остальное не прочитано')
    if cap.broken:
        out.append(f'не собрано пакетов L2CAP: {cap.broken} (потерялись фрагменты ACL)')
    if g.empty:
        out.append('обнаружения сервисов в журнале нет: Android взял GATT из своего кэша, поэтому хэндлы остались без UUID '
                   '(handle-0x…). «Забудь» самокат в настройках Bluetooth (или очисти кэш Bluetooth), затем запиши снова.')
    if not keep_ids:
        macs = [bytes.fromhex(a.replace(':', '')) for c in sel for a in (c.addr, c.rpa) if a]
        pats = {bl.hex_of(m) for m in macs} | {bl.hex_of(m[::-1]) for m in macs}
        if any(pat in (e.get('h') or e.get('n') or '') for e in log['entries'] for pat in pats):
            out.append('в пакетах есть байты MAC-адреса самоката: обезличивание их не прячет — проверь лог перед публикацией')
    return out


def show_path(path):
    r = os.path.relpath(os.path.abspath(path), ROOT)
    return path if r.startswith('..') else r


def convert(src, out=None, conn=None, addr=None, label=None, keep_ids=False, quiet=False, stream=None):
    """Parse src, write the log and print the summary. Returns a dict with log, path and the rest."""
    stream = stream or sys.stdout
    cap = parse(src)
    if cap.first_us is None:
        raise ValueError(f'{src}: в журнале нет ни одной записи')
    sel, best = select(cap, conn, addr)
    log, g = build_log(cap, sel, best, keep_ids)
    warnings = check_warnings(cap, sel, log, g, keep_ids)
    path = out or default_out(cap.first_us, label)
    bl.save_log(log, path)
    for w in warnings:
        print('! ' + w, file=stream)
    lines = [] if quiet else summary(cap, sel, log, g, keep_ids)
    if lines:
        print('\n'.join(lines), file=stream)
    print(f"\nЗаписан лог: {show_path(path)} — записей {len(log['entries'])}" + ('' if keep_ids else ', обезличен'), file=stream)
    return {'log': log, 'path': path, 'capture': cap, 'selected': sel, 'gatt': g, 'warnings': warnings, 'summary': lines}


def handle_arg(s):
    try:
        return int(s, 0) & 0xFFF
    except ValueError:
        raise argparse.ArgumentTypeError('нужен handle вида 0x0040')


def addr_arg(s):
    h = re.sub(r'[^0-9A-Fa-f]', '', s)
    if len(h) != 12:
        raise argparse.ArgumentTypeError('нужен адрес вида AA:BB:CC:DD:EE:FF')
    return ':'.join(h[i:i + 2] for i in range(0, 12, 2)).upper()


def main(argv=None):
    ap = argparse.ArgumentParser(description='Разбор HCI snoop log Android (btsnoop_hci.log) в лог Bird Air Lab')
    ap.add_argument('path', help='btsnoop_hci.log или bugreport.zip')
    ap.add_argument('-o', '--out', help='куда писать лог (по умолчанию logs/ГГГГММДД-ЧЧММСС-<метка>.json)')
    ap.add_argument('--conn', type=handle_arg, help='handle подключения, например 0x0040 (по умолчанию — где больше всего уведомлений и записей)')
    ap.add_argument('--addr', type=addr_arg, help='адрес самоката AA:BB:CC:DD:EE:FF')
    ap.add_argument('--label', help='метка в имени файла (по умолчанию btsnoop)')
    ap.add_argument('--keep-ids', action='store_true', help='оставить настоящие имя и адрес — такой лог не клади в публичный репозиторий')
    ap.add_argument('--quiet', action='store_true', help='не печатать сводку')
    a = ap.parse_args(argv)
    try:
        convert(a.path, a.out, a.conn, a.addr, a.label, a.keep_ids, a.quiet)
    except (OSError, ValueError, zipfile.BadZipFile) as ex:
        print(f'Ошибка: {ex}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
