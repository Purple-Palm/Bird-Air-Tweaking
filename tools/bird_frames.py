"""Кадры контроллера Bird Air, которые читают fast_power.py, battery_advisor.py и app_odometer.py.

Смещения — от 3A, проверены по логам (docs/bird-air-protocol.md):

  3A 18 20 10 (BMS, ~3,2/с): 5 бит 0x20 «мотор тянет», 7 и 8 — температуры °C (?), 9–10 u16le
  × 0,001 В, 11–12 s16le мА (минус — разряд; здесь знак обратный: плюс — тяга), 13–14 u16le
  остаток мА·ч, 15–16 u16le полная ёмкость мА·ч, 17 u8 заряд %.
  3A 17 00 0D (ход, ~15/с): 5 u8 × 0,1 км/ч средняя с включения, 6–7 u16be × 0,1 км/ч скорость,
  9 младшие 2 бита — режим, 10–11 u16be × 0,1 км поездка с включения, 12–14 u24be × 0,1 км пробег.

Байты состояния — как есть (смысл по логам, docs/bird-air-protocol.md, «Биты состояния»):
  Bms.b4: 0x01 всегда, 0x02 «заряд разрешён» (снимают защиты от перенапряжения и горячего заряда),
          0x40 зарядка подключена;
  Bms.b5: 0x80 идёт заряд, 0x20 «мотор тянет», 0x08 предупреждение о перенапряжении (с 42,23 В),
          0x04 защита от перенапряжения, 0x02 низкое напряжение — BMS отключает батарею (20.10: за 2,4 с);
  Bms.b6: 0x08 ток в батарею при T2 ≥ 46 °C, 0x04 защёлка «горячий заряд» (зарядка ждёт ≈37/39 °C),
          0x80 перегрев при разряде (15.10: 0x84 на тепловой отсечке);
  Bms.b18: +1 за зарядку (105 → 110 за 14–20.10) — счётчик циклов?
  Motion.code (байт 8): 0 везде, кроме 0x26 в 5 с между молчанием BMS и контроллера (20.10) и 0x21
          на стоянке 20 с (15.10).

Kotlin: android/core ScooterFrames.kt — те же формулы в том же порядке (числа совпадают до бита).
Только чтение лога.
"""
import collections
import datetime
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402
import uart_frames as uf  # noqa: E402

BMS_HEAD = bytes([0x18, 0x20, 0x10])
MOTION_HEAD = bytes([0x17, 0x00, 0x0D])
FFE1 = bl.norm_uuid('ffe1')

# the status bytes come last with defaults: Bms(t, v, i, …, pct) and Motion(t, …, odo_km) still work
Bms = collections.namedtuple('Bms', 't v i motor temp1 temp2 rem_mah full_mah pct b4 b5 b6 b18', defaults=(0, 0, 0, 0))
Motion = collections.namedtuple('Motion', 't speed_kmh avg_kmh mode trip_km odo_km code', defaults=(0,))

CHARGER = 0x40          # Bms.b4
UNDER_VOLTAGE = 0x02    # Bms.b5
OVER_VOLTAGE_WARN = 0x08
OVER_VOLTAGE_CUT = 0x04
HOT_CHARGE = 0x04       # Bms.b6
CODE_NO_BMS = 0x26      # Motion.code


def _u16le(b, o):
    return b[o] | b[o + 1] << 8


def _u16be(b, o):
    return b[o] << 8 | b[o + 1]


def parse_bms(frame, t):
    """Bms из кадра 3A 18 20 10 (с 3A в начале), иначе None."""
    if len(frame) < 18 + 4 or bytes(frame[1:4]) != BMS_HEAD:
        return None
    raw_i = _u16le(frame, 11)
    if raw_i >= 0x8000:
        raw_i -= 0x10000
    return Bms(t, _u16le(frame, 9) * 0.001, raw_i * -0.001, (frame[5] >> 5) & 1, frame[7], frame[8],
               _u16le(frame, 13), _u16le(frame, 15), frame[17], frame[4], frame[5], frame[6], frame[18])


def parse_motion(frame, t):
    """Motion из кадра 3A 17 00 0D, иначе None."""
    if len(frame) < 15 + 4 or bytes(frame[1:4]) != MOTION_HEAD:
        return None
    return Motion(t, _u16be(frame, 6) * 0.1, frame[5] * 0.1, frame[9] & 3, _u16be(frame, 10) * 0.1,
                  (frame[12] << 16 | frame[13] << 8 | frame[14]) * 0.1, frame[8])


def events(log):
    """Кадры лога по порядку: ('reset', t, None) — новое подключение (строка «Найдено сервисов»),
    ('bms', t, Bms), ('motion', t, Motion). Кадры FFE1 собирает uart_frames.Assembler; время кадра —
    уведомления, после которого сборщик его выдал, как у приложения (TelemetryTracker). Обычно это
    уведомление с последним байтом кадра; в логах 32 кадра из 360 тысяч выданы позже, на 80–150 мс."""
    asm = uf.Assembler(keep_texts=False)
    out = []
    for e in log.get('entries', []):
        if e.get('k') == 'sys' and str(e.get('n') or '').startswith(uf.SYS_FOUND):
            asm.restart()
            out.append(('reset', e['t'], None))
            continue
        if e.get('k') != 'rx' or bl.norm_uuid(e.get('u')) != FFE1:
            continue
        t = e['t']
        for f in asm.feed(bl.entry_bytes(e), t):
            b = parse_bms(f.frame, t)
            if b is not None:
                out.append(('bms', t, b))
                continue
            m = parse_motion(f.frame, t)
            if m is not None:
                out.append(('motion', t, m))
    return out


def started_ms(log):
    """startedAt лога в мс эпохи (для часов «на стене»), None — нет или не разобрать."""
    s = log.get('startedAt')
    try:
        return round(datetime.datetime.fromisoformat(str(s).replace('Z', '+00:00')).timestamp() * 1000)
    except (TypeError, ValueError):
        return None
