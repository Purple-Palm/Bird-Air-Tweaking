"""Synthetic logs for the tool tests.

demo_log() replays the page's demo scooter (assets/app.js, demoTick): frames
5A A5 len cmd payload xor, cmd 0x25 telemetry at 10 Hz and cmd 0x31 odometer at 1 Hz.
"""
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tools'))
import birdlog as bl  # noqa: E402

DEMO_SVC = bl.norm_uuid('b1a0')
DEMO_CHR = bl.norm_uuid('b1a1')
PACK_AH = 5.2
MARKS = {6: 'Газ', 30: 'Тормоз', 38: 'Сменил режим', 40: 'Газ', 52: 'Тормоз', 60: 'Зарядка вкл', 80: 'Зарядка выкл', 90: 'Сменил режим'}


def demo_frame(cmd, payload):
    b = [0x5A, 0xA5, len(payload) + 2, cmd] + list(payload)
    x = 0
    for v in b[2:]:
        x ^= v
    return bytes(b + [x])


def clamp(x, a, b):
    return min(b, max(a, x))


def demo_log(seconds=200, seed=1):
    rnd = random.Random(seed)
    noise = lambda a: (rnd.random() - 0.5) * a  # noqa: E731
    lg = bl.new_log(device={'name': 'Демо: Bird Air', 'id': 'demo'}, started_at='2026-10-14T19:00:00.000Z')
    lg['demo'] = True
    lg['gatt'] = [{'uuid': DEMO_SVC, 'chars': [{'uuid': DEMO_CHR, 'props': {'notify': True}}]}]
    es = lg['entries']
    soc, odo, temp, spd, mode = 0.74, 641.27, 27.0, 0.0, 3
    dt = 0.1
    for k in range(int(seconds * 10)):
        t = k * 100
        sec = (k % 1000) / 10
        target, cur = 0.0, 0.25
        for s, label in MARKS.items():
            if abs(sec - s) < 0.05:
                es.append({'t': t, 'k': 'mk', 'n': label})
        if abs(sec - 38) < 0.05:
            mode = 2
        if abs(sec - 90) < 0.05:
            mode = 3
        if 6 <= sec < 30:
            target, cur = 25.4, (10.5 + noise(1.5) if spd < 22 else 6.2 + noise(1.6))
        elif 30 <= sec < 34:
            target, cur = 4, -3.6 + noise(0.6)
        elif 40 <= sec < 52:
            target, cur = 19.6, (7.5 + noise(1.2) if spd < 17 else 4.4 + noise(1.0))
        elif 52 <= sec < 55:
            target, cur = 0, -2.6 + noise(0.5)
        elif 60 <= sec < 80:
            target, cur = 0, -1.95 + noise(0.06)
        rate = 2.6 if target > spd else 5.5
        spd += clamp(target - spd, -rate * dt, rate * dt)
        if spd < 0.2 and target == 0:
            spd = 0
        if 34 <= sec < 40:
            spd = max(0, spd - 6 * dt)
            cur = 0.6 if spd > 0.5 else 0.25
        soc = clamp(soc - (cur * dt / 3600 / PACK_AH) * 30, 0.06, 1)
        ocv = 10 * (3.0 + 1.2 * soc ** 0.85)
        volt = ocv - cur * 0.18 + noise(0.04)
        temp += (26 + 2.6 * abs(cur) - temp) * 0.004
        odo += spd * dt / 3600
        v16, i16, s16 = round(volt * 100), round(cur * 100) & 0xFFFF, round(spd * 10)
        f = demo_frame(0x25, [round(soc * 100), v16 & 255, v16 >> 8, i16 & 255, i16 >> 8, s16 & 255, s16 >> 8, round(temp) & 255, mode])
        es.append({'t': t, 'k': 'rx', 's': DEMO_SVC, 'u': DEMO_CHR, 'h': bl.hex_of(f)})
        if (k + 1) % 10 == 0:
            o = round(odo * 100)
            es.append({'t': t, 'k': 'rx', 's': DEMO_SVC, 'u': DEMO_CHR,
                       'h': bl.hex_of(demo_frame(0x31, [o & 255, (o >> 8) & 255, (o >> 16) & 255, (o >> 24) & 255, 0, 0]))})
    return lg
