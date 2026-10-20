#!/usr/bin/env python3
"""Быстрый ток и мощность батареи Bird Air по просадке напряжения (эталон для приложения).

    python3 tools/fast_power.py logs/*.json.gz              # таблица проверки по логам
    python3 tools/fast_power.py logs/X.json.gz --set k_ocv=0.7

Зачем. Ток BMS (кадр 3A 18 20 10, байты 11–12) обновляется раз в ~1 с (одно и то же значение
приходит ~3 кадра подряд) и описывает среднее за ~0,7 с до кадра, где сменился, — на экране он
отстаёт на 0,6 с. Напряжение (байты 9–10) свежее в каждом кадре (~3,2/с), а батарея —
источник с внутренним сопротивлением R ≈ 0,25 Ом: V = OCV − R·I. Значит I = (OCV − V)/R можно
считать в каждом кадре, а ток BMS брать только как «якорь» уровня: OCV ≈ V̄ + R·I_bms, где V̄ —
среднее напряжение за те же 0,7 с, к которым относится ток BMS (поправка на его запаздывание).
Всё причинно: выход на кадре зависит только от этого и прошлых кадров.

Вход — кадры 3A 18 20 10 по порядку времени: t (мс, любые одни часы), V (В), I_bms (А, плюс —
тяга, разряд; минус — рекуперация и зарядка), motor (бит 0x20 байта 5; не используется, см.
«Ограничения»). Выход на каждый кадр: I_fast (А, плюс — тяга) и P_fast = V·I_fast (Вт).

Алгоритм (Kotlin-порт FastPower.kt повторяет его по шагам, параметры — PARAMS):

  Свойства батареи (переживают reset(); reset(full=True) — R = r0, суммы = 0): R (Ом), Sxx, Sxy, Syy.
  Поток (reset() обнуляет): ocv (None — нет), slope (В/с), t_ocv (мс), hist — кадры (t, V) за
  последние window_ms, last_t, last_i, anchor_t, prev = (t, V̄, I) прошлого якоря или None,
  vlo/vhi — мин./макс. V с начала окна прошлого якоря.

  update(t, v, i):
   1. Разрыв: last_t есть и (t − last_t > gap_ms или t < last_t) — reset(). Новое подключение —
      reset() снаружи.
   2. last_t = t; hist += (t, v); из начала hist убрать кадры с t' < t − window_ms.
   3. ocv = None (первый кадр потока): ocv = v + R·i, slope = 0, t_ocv = t, last_i = i, anchor_t = t,
      prev = None, vlo = vhi = v; якоря нет.
      Иначе vlo = min(vlo, v), vhi = max(vhi, v) и якорь, если
        i ≠ last_i (BMS прислала новое значение), или
        t − anchor_t ≥ hold_ms и vhi − vlo ≤ hold_dv (значение то же, а напряжение стоит — значит,
        и ток тот же: стоянка, зарядка; если V сдвинулось, старое значение BMS описывает прошлое).
      Якорь (суммы — по порядку кадров, обычным сложением):
        n = |hist|;  V̄ = Σv/n;  tc = Σt/n
        R: если prev и t − prev.t ≤ pair_max_ms и |dI| ≥ di_min, где dI = i − prev.I, dV = V̄ − prev.V̄:
             Sxx = λ·Sxx + dI²;  Sxy = λ·Sxy − dV·dI;  Syy = λ·Syy + dV²
             если Sxy > 0:  R = clamp(√((Syy + P·r0²)/(Sxx + P)), r_min, r_max)
           (λ = r_lambda, P = r_prior, А²: среднее геометрическое прямой и обратной регрессий с
           априорным r0 — у прямой МНК R занижен: шум и в dI, и в dV.)
        OCV (уровень и наклон, фильтр Холта):
             dtc = (tc − t_ocv)/1000;  pred = ocv + slope·dtc;  e = (V̄ + R·i) − pred
             k = k_steady, если prev и |i − prev.I| < di_min, иначе k_ocv;  ocv = pred + k·e
             если dtc > 0,2:  slope = clamp(slope + k_slope·e/dtc, −slope_max, slope_max)
             t_ocv = tc
        prev = (t, V̄, i);  vlo, vhi = мин., макс. V в hist;  last_i = i;  anchor_t = t
   4. ocv_now = ocv + slope·min(t − t_ocv, horizon_ms)/1000
      I_fast = clamp((ocv_now − v)/R, −i_max, i_max);  P_fast = v·I_fast
      |P_fast| < deadband_w → I_fast = P_fast = 0

Проверено на всех логах (validate(), CLI): корреляция с током BMS 0,96–0,99 при сдвиге +0,6 с
(BMS позже) и 0,90–0,97 без сдвига, медиана опережения на порогах 5 А и −2 А 0,3–0,9 с,
энергия из батареи та же с точностью 1,2 %. Почему такие параметры:
  * Окно 700 мс, кончающееся кадром со свежим током BMS, лучше других сдвигов и ширин объясняет
    связь ΔV̄ ↔ ΔI_bms (корреляция 0,95 на всех поездках).
  * R по поездке 0,29–0,32 Ом в начале (холодная батарея), 0,22–0,25 Ом в конце — поэтому R
    отслеживается. Внизу заряда (меньше 25 %) R растёт, ошибка у обновлений BMS там та же
    (0,7–0,8 А), смещения нет — хвост с низким напряжением отдельного правила не требует.
  * Наклон slope и полный вес «спокойных» якорей (k_steady = 1) убирают отставание на долгой
    нагрузке: на упоре 16 А смещение +0,03…+0,19 А. Броски оценки после резкой смены тока
    (якорь поймал окно наполовину до, наполовину после) — до 21 А при упоре 16 А, поэтому
    |I_fast| ≤ i_max = 17 А.
  * На зарядке (−1,1…−1,7 А) и стоянке тяги нет: на стоянке −5…+9 Вт при 1,4…5,7 Вт по BMS
    (сразу после остановки V ещё поднимается — поляризация). Мёртвая зона 5 Вт убирает дрожь
    около нуля; энергия из батареи от неё меньше на 0,3–1,2 % (потребление на стоянке 2–4 Вт).
  * Параметры подобраны на первой поездке (20261014-185422), целевая функция плоская (ошибка у
    обновлений BMS 0,95–0,97 А на сетке window 600–800, k_ocv 0,4–0,7, k_slope 0–0,2; R-параметры
    меняют её меньше чем на 0,02 А); на других логах те же параметры.

Ограничения:
  * Уровень держит только ток BMS. Застынет он при живых кадрах — на ходу якорей нет, OCV держится,
    наклон — не дальше horizon_ms; на стоянке якоря подтянут оценку к застывшему значению за ~2 с.
  * Сразу после резкого броска газа оценка может ошибаться на 2–4 А один-два кадра (см. выше).
  * Бит «мотор тянет» опережает напряжение на кадр, но ток после него спадает 0,3–0,6 с (рампа
    контроллера) — резать оценку по нему нельзя.
  * Первый кадр после сброса берёт ток BMS как есть (может быть на секунду старым).
  * Валовая рекуперация по оценке на 5–15 % больше, чем по току BMS (нетто — до 0,5 %): BMS
    усредняет короткие всплески вместе с тягой.

Только стандартная библиотека. Ничего не пишет ни в самокат, ни в логи.
"""
import argparse
import collections
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bird_frames as bf  # noqa: E402
import birdlog as bl  # noqa: E402

PARAMS = {
    'r0': 0.25,            # Ом, начальное R и априорное значение
    'r_min': 0.08,         # Ом
    'r_max': 0.6,          # Ом
    'r_lambda': 0.98,      # забывание сумм R на каждую пару якорей (~50 пар ≈ минута езды)
    'r_prior': 50.0,       # А², вес априорного r0 в суммах
    'di_min': 1.0,         # А, пара якорей с меньшим |dI| не учит R
    'pair_max_ms': 2500,   # мс, пара якорей дальше этого не учит R
    'window_ms': 700,      # мс, окно V̄ перед кадром со свежим током BMS
    'hold_ms': 1200,       # мс, ток BMS не менялся столько — якорь с тем же значением…
    'hold_dv': 0.1,        # В, …если размах V с начала окна прошлого якоря не больше этого
    'k_ocv': 0.5,          # доля ошибки якоря в уровень OCV, когда ток BMS сменился на ≥ di_min…
    'k_steady': 1.0,       # …и когда не сменился (окна BMS и V̄ совпадают надёжнее)
    'k_slope': 0.2,        # доля ошибки якоря (В) / dtc (с) в наклон OCV
    'slope_max': 0.05,     # В/с, предел наклона OCV
    'horizon_ms': 2000,    # мс, дальше этого от последнего якоря наклон не продолжается
    'gap_ms': 2500,        # мс, разрыв между кадрами больше этого — сброс
    'i_max': 17.0,         # А, |I_fast| не больше (контроллер держит ~16 А)
    'deadband_w': 5.0,     # Вт, |P| меньше этого — 0
}


def _clamp(x, lo, hi):
    return lo if x < lo else hi if x > hi else x


class FastPower:
    """Причинная оценка тока и мощности по напряжению. update(t_ms, v, i_bms, motor) -> (i_fast, p_fast).

    i_bms и i_fast — А, плюс — тяга (из батареи), минус — рекуперация и зарядка."""

    def __init__(self, **params):
        unknown = set(params) - set(PARAMS)
        if unknown:
            raise ValueError(f'неизвестные параметры: {sorted(unknown)}')
        self.p = dict(PARAMS, **params)
        self.reset(full=True)

    def reset(self, full=False):
        """Новый поток кадров (переподключение, разрыв). full=True — ещё и R с суммами."""
        if full:
            self.r = float(self.p['r0'])
            self.sxx = self.sxy = self.syy = 0.0
        self.ocv = None
        self.slope = 0.0
        self.t_ocv = 0.0
        self.hist = collections.deque()
        self.last_t = None
        self.last_i = 0.0
        self.anchor_t = 0
        self.prev = None
        self.vlo = self.vhi = 0.0
        self.anchors = 0

    def _learn_r(self, t, vbar, i):
        p = self.p
        prev = self.prev
        if prev is None or t - prev[0] > p['pair_max_ms']:
            return
        di = i - prev[2]
        dv = vbar - prev[1]
        if abs(di) < p['di_min']:
            return
        lam = p['r_lambda']
        self.sxx = lam * self.sxx + di * di
        self.sxy = lam * self.sxy - dv * di
        self.syy = lam * self.syy + dv * dv
        if self.sxy > 0:
            pr, r0 = p['r_prior'], p['r0']
            r = math.sqrt((self.syy + pr * r0 * r0) / (self.sxx + pr))
            self.r = _clamp(r, p['r_min'], p['r_max'])

    def _anchor(self, t, i):
        p = self.p
        h = self.hist
        n = len(h)
        vs = 0.0
        ts = 0
        lo = hi = h[0][1]
        for x in h:                      # plain sums in order (sum() of floats is compensated since 3.12)
            ts += x[0]
            vs += x[1]
            lo = min(lo, x[1])
            hi = max(hi, x[1])
        vbar = vs / n
        tc = ts / n
        self._learn_r(t, vbar, i)
        dtc = (tc - self.t_ocv) / 1000.0
        pred = self.ocv + self.slope * dtc
        e = vbar + self.r * i - pred
        steady = self.prev is not None and abs(i - self.prev[2]) < p['di_min']
        self.ocv = pred + (p['k_steady'] if steady else p['k_ocv']) * e
        if dtc > 0.2:
            self.slope = _clamp(self.slope + p['k_slope'] * e / dtc, -p['slope_max'], p['slope_max'])
        self.t_ocv = tc
        self.prev = (t, vbar, i)
        self.vlo, self.vhi = lo, hi
        self.last_i = i
        self.anchor_t = t
        self.anchors += 1

    def update(self, t, v, i, motor=None):  # noqa: ARG002 — motor: see «Ограничения»
        p = self.p
        if self.last_t is not None and (t - self.last_t > p['gap_ms'] or t < self.last_t):
            self.reset()
        self.last_t = t
        h = self.hist
        h.append((t, v))
        while h[0][0] < t - p['window_ms']:
            h.popleft()
        if self.ocv is None:
            self.ocv = v + self.r * i
            self.slope = 0.0
            self.t_ocv = float(t)
            self.last_i = i
            self.anchor_t = t
            self.prev = None
            self.vlo = self.vhi = v
        else:
            self.vlo = min(self.vlo, v)
            self.vhi = max(self.vhi, v)
            if i != self.last_i or (t - self.anchor_t >= p['hold_ms'] and self.vhi - self.vlo <= p['hold_dv']):
                self._anchor(t, i)
        ocv_now = self.ocv + self.slope * min(t - self.t_ocv, p['horizon_ms']) / 1000.0
        i_fast = _clamp((ocv_now - v) / self.r, -p['i_max'], p['i_max'])
        p_fast = v * i_fast
        if abs(p_fast) < p['deadband_w']:
            i_fast = p_fast = 0.0
        return i_fast, p_fast


# ---------------------------------------------------------------- synthetic sequence (tests, Kotlin port)

SYN_STEPS = ((0, 0.06), (5000, 10.0), (13000, -4.0), (16000, 15.5), (22000, 0.06), (27000, 6.0))
SYN_END_MS = 32000
SYN_R = 0.27


def _q(x, step):
    """Round to a multiple of step, half up (same in Kotlin: floor(x / step + 0.5) * step)."""
    return math.floor(x / step + 0.5) * step


def synthetic_current(t):
    """True current of the synthetic ride at t ms (A, + traction): steps SYN_STEPS."""
    cur = SYN_STEPS[0][1]
    for t0, i in SYN_STEPS:
        if t >= t0:
            cur = i
    return cur


def _syn_mean(a, b):
    """Mean of synthetic_current over [a, b] (steps hold before 0 too)."""
    edges = [a] + [t0 for t0, _ in SYN_STEPS if a < t0 < b] + [b]
    s = 0.0
    for x, y in zip(edges, edges[1:]):
        s += synthetic_current(x) * (y - x)
    return s / (b - a)


def synthetic_frames(aligned=True):
    """Deterministic BMS frames [(t, v, i_bms, motor)] for tests and the Kotlin port.

    Frame k at t = 300·k + (37·k mod 61) ms while t < SYN_END_MS. True current — SYN_STEPS;
    OCV = 40 − 0.00002·t V (−0.02 V/s); V = OCV − SYN_R·I_true(t), rounded half up to 1 mV.
    aligned: the BMS refreshes on the first frame of each whole second, i_bms = mean of I_true over
    the 700 ms before that frame; otherwise at every whole second tr = 1000·floor(t/1000), mean over
    [tr − 700, tr]. Rounded half up to 1 mA. motor = 1 when I_true > 1 A."""
    out = []
    k = 0
    i_bms = 0.0
    while True:
        t = 300 * k + (37 * k) % 61
        if t >= SYN_END_MS:
            return out
        i_true = synthetic_current(t)
        v = _q(40.0 - 0.00002 * t - SYN_R * i_true, 0.001)
        if aligned:
            if k == 0 or t // 1000 != out[-1][0] // 1000:
                i_bms = _q(_syn_mean(t - 700, t), 0.001)
        else:
            tr = 1000 * (t // 1000)
            i_bms = _q(_syn_mean(tr - 700, tr), 0.001)
        out.append((t, v, i_bms, 1 if i_true > 1 else 0))
        k += 1


# ---------------------------------------------------------------- reading logs

def read_log(log, events=None):
    """Кадры одного лога: {'bms': [(t, v, i_bms, motor)], 'speed': [(t, км/ч)], 'resets': [k]}.

    resets — номера кадров bms, перед которыми было новое подключение («Найдено сервисов»).
    events — уже разобранные bird_frames.events(log), если есть."""
    bms, speed, resets = [], [], []
    for kind, t, x in (events if events is not None else bf.events(log)):
        if kind == 'reset':
            resets.append(len(bms))
        elif kind == 'bms':
            bms.append((t, x.v, x.i, x.motor))
        else:
            speed.append((t, x.speed_kmh))
    return {'bms': bms, 'speed': speed, 'resets': resets}


def run(data, params=None):
    """FastPower по кадрам лога. Возвращает ([(t, v, i_bms, motor, i_fast, p_fast)], оценщик)."""
    est = FastPower(**(params or {}))
    resets = set(data.get('resets') or [])
    out = []
    for k, (t, v, i, m) in enumerate(data['bms']):
        if k in resets:
            est.reset()
        i_f, p_f = est.update(t, v, i, m)
        out.append((t, v, i, m, i_f, p_f))
    return out, est


# ---------------------------------------------------------------- validation metrics (pure Python)

def _corr(xs, ys):
    n = len(xs)
    if n < 3:
        return float('nan')
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    syy = sum((y - my) ** 2 for y in ys)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    return sxy / math.sqrt(sxx * syy) if sxx > 0 and syy > 0 else float('nan')


def _median(xs):
    s = sorted(xs)
    n = len(s)
    if not n:
        return float('nan')
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def zoh_grid(rows, col, dt=100, gap_ms=2500):
    """Значение колонки col как на экране (держится до следующего кадра) на сетке dt мс.
    Точки внутри разрывов > gap_ms — None."""
    if not rows:
        return []
    out = []
    t = rows[0][0]
    k = 0
    end = rows[-1][0]
    while t <= end:
        while k + 1 < len(rows) and rows[k + 1][0] <= t:
            k += 1
        nxt = rows[k + 1][0] if k + 1 < len(rows) else None
        out.append(None if nxt is not None and nxt - rows[k][0] > gap_ms else rows[k][col])
        t += dt
    return out


def lag_corr(a, b, lags):
    """{lag: corr(a(t), b(t + lag))} по точкам, где обе не None. lag в шагах сетки; lag > 0 —
    b позже, т.е. a опережает b."""
    res = {}
    n = len(a)
    for L in lags:
        xs, ys = [], []
        for k in range(max(0, -L), min(n, n - L)):
            x, y = a[k], b[k + L]
            if x is not None and y is not None:
                xs.append(x)
                ys.append(y)
        res[L] = _corr(xs, ys)
    return res


def crossings(rows, col, thr, up, hyst=1.0, gap_ms=2500):
    """Времена кадров, где колонка пересекла порог thr (up — снизу вверх) после того, как была
    по другую сторону от thr ∓ hyst. Разрыв > gap_ms снимает взвод."""
    out = []
    armed = False
    last_t = None
    for r in rows:
        t, x = r[0], r[col]
        if last_t is not None and t - last_t > gap_ms:
            armed = False
        last_t = t
        if up:
            if x < thr - hyst:
                armed = True
            elif armed and x >= thr:
                out.append(t)
                armed = False
        else:
            if x > thr + hyst:
                armed = True
            elif armed and x <= thr:
                out.append(t)
                armed = False
    return out


def lead_times(rows, thr, up, hyst=1.0, before_ms=3000, after_ms=1000):
    """На сколько мс I_fast раньше тока BMS пересекает порог: для каждого пересечения BMS берётся
    последнее пересечение I_fast того же направления в (t_bms − before_ms, t_bms + after_ms].
    Возвращает (список опережений, число пересечений BMS)."""
    cb = crossings(rows, 2, thr, up, hyst)
    cf = crossings(rows, 4, thr, up, hyst)
    leads = []
    for tb in cb:
        c = [tf for tf in cf if tb - before_ms < tf <= tb + after_ms]
        if c:
            leads.append(tb - c[-1])
    return leads, len(cb)


def update_errors(rows, window_ms=700, gap_ms=2500, col=4):
    """Честная ошибка у обновлений BMS: для каждого кадра k, где ток BMS сменился, среднее колонки
    col по кадрам в (t_k − window_ms, t_k) (они посчитаны до того, как BMS это значение прислала)
    минус I_bms(k). Возвращает [(t_k, ошибка, I_bms(k), I_bms(k−1))]."""
    out = []
    for k in range(1, len(rows)):
        if rows[k][2] == rows[k - 1][2] or rows[k][0] - rows[k - 1][0] > gap_ms:
            continue
        tk = rows[k][0]
        xs = []
        j = k - 1
        while j >= 0 and rows[j][0] > tk - window_ms:
            xs.append(rows[j][col])
            j -= 1
        if xs:
            out.append((tk, sum(xs) / len(xs) - rows[k][2], rows[k][2], rows[k - 1][2]))
    return out


def energy(rows, col_p, gap_ms=2500):
    """(Вт·ч из батареи, Вт·ч в батарею) прямоугольниками по кадрам; разрывы > gap_ms пропущены.
    col_p — колонка мощности или 'bms' (V·I_bms)."""
    e_out = e_in = 0.0
    for a, b in zip(rows, rows[1:]):
        dt = b[0] - a[0]
        if dt > gap_ms or dt <= 0:
            continue
        pw = a[col_p] if col_p != 'bms' else a[1] * a[2]
        if pw >= 0:
            e_out += pw * dt / 3.6e6
        else:
            e_in -= pw * dt / 3.6e6
    return e_out, e_in


def standstill(rows, speed, quiet_ms=3000):
    """Кадры, вокруг которых скорость была 0 не меньше quiet_ms в обе стороны."""
    moving = [t for t, s in speed if s > 0]
    out = []
    j = 0
    for r in rows:
        t = r[0]
        while j < len(moving) and moving[j] < t:
            j += 1
        prev_m = moving[j - 1] if j > 0 else None
        next_m = moving[j] if j < len(moving) else None
        if (prev_m is None or t - prev_m >= quiet_ms) and (next_m is None or next_m - t >= quiet_ms):
            out.append(r)
    return out


def validate(data, params=None, grid_ms=100, max_lag_ms=2000):
    """Все цифры проверки по одному логу (dict от read_log)."""
    rows, est = run(data, params)
    res = {'frames': len(rows), 'r_end': est.r}
    if len(rows) < 10:
        return res
    gf = zoh_grid(rows, 4, grid_ms)
    gb = zoh_grid(rows, 2, grid_ms)
    lags = range(-max_lag_ms // grid_ms, max_lag_ms // grid_ms + 1)
    cl = lag_corr(gf, gb, lags)
    finite = {k: v for k, v in cl.items() if v == v}
    best = max(finite, key=finite.get) if finite else 0
    res.update(corr0=cl.get(0, float('nan')), corr_best=cl.get(best, float('nan')), lag_best_ms=best * grid_ms)
    for key, lag in (('shift', best), ('noshift', 0)):
        d = [gf[k] - gb[k + lag] for k in range(max(0, -lag), min(len(gf), len(gf) - lag))
             if gf[k] is not None and gb[k + lag] is not None]
        res['rms_' + key] = math.sqrt(sum(x * x for x in d) / len(d)) if d else float('nan')
    # the same for «the last BMS current» as the screen shows it today, at lag 0
    d = [gb[k] - gb[k + best] for k in range(max(0, -best), min(len(gb), len(gb) - best))
         if gb[k] is not None and gb[k + best] is not None]
    res['rms_bms_noshift'] = math.sqrt(sum(x * x for x in d) / len(d)) if d else float('nan')
    ue = update_errors(rows)
    ub = update_errors(rows, col=2)
    res['upd_n'] = len(ue)
    res['upd_rms'] = math.sqrt(sum(e[1] ** 2 for e in ue) / len(ue)) if ue else float('nan')
    res['upd_bias'] = sum(e[1] for e in ue) / len(ue) if ue else float('nan')
    res['upd_rms_bms'] = math.sqrt(sum(e[1] ** 2 for e in ub) / len(ub)) if ub else float('nan')
    lim = [e[1] for e in ue if e[2] > 14.5 and e[3] > 14.5]
    res['limit_n'] = len(lim)
    res['limit_bias'] = sum(lim) / len(lim) if lim else float('nan')
    leads_all = []
    for key, thr, up in (('up5', 5.0, True), ('dn2', -2.0, False)):
        leads, n = lead_times(rows, thr, up)
        leads_all += leads
        res[key + '_n'] = n
        res[key + '_hit'] = len(leads)
        res[key + '_lead_ms'] = _median(leads)
    res['lead_ms'] = _median(leads_all)
    eb, ef = energy(rows, 'bms'), energy(rows, 5)
    res.update(wh_out_bms=eb[0], wh_out_fast=ef[0], wh_in_bms=eb[1], wh_in_fast=ef[1])
    st = standstill(rows, data.get('speed') or [])
    res['still_n'] = len(st)
    if st:
        ps = [r[5] for r in st]
        res.update(still_p_min=min(ps), still_p_max=max(ps), still_fake_traction=sum(1 for x in ps if x > 10.0) / len(ps))
    ib = [r[2] for r in rows]
    res['charging'] = _median(ib) < -0.5 and max(ib) < 0
    res['i_fast_max'] = max(r[4] for r in rows)
    res['i_fast_min'] = min(r[4] for r in rows)
    res['traction_frames'] = sum(1 for r in rows if r[4] > 0)
    return res


def _f(x, fmt):
    return '—' if x is None or x != x else format(x, fmt)


def _pct_diff(a, b):
    return (b - a) / a * 100 if a else float('nan')


def print_table(results, stream=None):
    p = lambda *a: print(*a, file=stream or sys.stdout)  # noqa: E731
    p('лог                          кадры  corr0  corrMax@сдвиг   СКО сдвиг/0 (BMS)     опережение: медиана 5А/−2А        '
      'ош.обновл. (BMS)  упор16А  Вт·ч из бат. BMS/оценка   в бат. BMS/оценка   стоянка P, Вт  R конец')
    for name, r in results:
        if r.get('frames', 0) < 10:
            p(f'{name[:28]:28s} {r.get("frames", 0):6d}  мало кадров')
            continue
        lead = (f'{_f(r["lead_ms"], ".0f"):>4s} мс: {_f(r["up5_lead_ms"], ".0f"):>4s} ({r["up5_hit"]}/{r["up5_n"]}) '
                f'{_f(r["dn2_lead_ms"], ".0f"):>4s} ({r["dn2_hit"]}/{r["dn2_n"]})')
        still = (f'{_f(r.get("still_p_min"), "+.0f")}…{_f(r.get("still_p_max"), "+.0f")}' if r.get('still_n') else '—')
        p(f'{name[:28]:28s} {r["frames"]:6d}  {_f(r["corr0"], ".3f")}  {_f(r["corr_best"], ".3f")}@{r["lag_best_ms"]:+5d}мс  '
          f'{_f(r["rms_shift"], ".2f")}/{_f(r["rms_noshift"], ".2f")} А ({_f(r["rms_bms_noshift"], ".2f")})  {lead:32s} '
          f'{_f(r["upd_rms"], ".2f")} А ({_f(r["upd_rms_bms"], ".2f")})  {_f(r["limit_bias"], "+.2f"):>6s}  '
          f'{r["wh_out_bms"]:6.1f}/{r["wh_out_fast"]:6.1f} ({_f(_pct_diff(r["wh_out_bms"], r["wh_out_fast"]), "+.1f")} %)  '
          f'{r["wh_in_bms"]:5.2f}/{r["wh_in_fast"]:5.2f} ({_f(_pct_diff(r["wh_in_bms"], r["wh_in_fast"]), "+.0f")} %)  '
          f'{still:>11s}  {r["r_end"]:.3f}')
        if r.get('charging'):
            p(f'{"":28s} зарядка: I_fast {r["i_fast_min"]:+.2f}…{r["i_fast_max"]:+.2f} А, кадров с тягой {r["traction_frames"]}')
    p('\ncorr0 / corrMax — корреляция I_fast с током BMS на сетке 100 мс без сдвига / при лучшем сдвиге (плюс — BMS позже).')
    p('СКО сдвиг/0 — среднеквадратичная разница I_fast и тока BMS, сдвинутого на лучший сдвиг / без сдвига; в скобках —')
    p('то же для «последнего тока BMS» на экране (насколько он отстаёт от себя же, сдвинутого).')
    p('Опережение — медиана, на сколько I_fast раньше тока BMS пересекает 5 А вверх и −2 А вниз (найдено/всего).')
    p('Ош. обновл. — СКО среднего I_fast за 0,7 с до кадра, где BMS прислала новый ток, от этого тока; в скобках — то же')
    p('для «последнего тока BMS». Упор 16 А — среднее этой ошибки, когда ток BMS > 14,5 А. Стоянка — мин…макс P_fast там,')
    p('где скорость 0 не меньше 3 с в обе стороны. R конец — оценка R в конце лога, Ом.')


def _parse_set(items):
    out = {}
    for s in items or []:
        k, _, v = s.partition('=')
        if k not in PARAMS:
            raise SystemExit(f'неизвестный параметр {k}; есть: {", ".join(PARAMS)}')
        out[k] = float(v)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description='Быстрый ток/мощность по просадке напряжения: проверка на логах')
    ap.add_argument('paths', nargs='+', help='логи (.json, .json.gz, части) или папки')
    ap.add_argument('--set', action='append', metavar='ключ=значение', help='параметр FastPower (можно несколько)')
    a = ap.parse_args(argv)
    params = _parse_set(a.set)
    notes = []
    logs = bl.load_logs(a.paths, notes)
    for n in notes:
        print('! ' + n)
    if not logs:
        print('Нет логов для разбора.')
        return 1
    results = []
    for lg in logs:
        name = os.path.basename((lg.get('_files') or ['лог'])[0])
        results.append((name, validate(read_log(lg), params)))
    print_table(results)
    return 0


if __name__ == '__main__':
    sys.exit(main())
