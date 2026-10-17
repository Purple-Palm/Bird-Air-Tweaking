#!/usr/bin/env python3
"""Быстрый ток и мощность батареи Bird Air по просадке напряжения (эталон для приложения).

    python3 tools/fast_power.py logs/*.json.gz              # таблица проверки по логам
    python3 tools/fast_power.py logs/X.json.gz --plots DIR  # плюс окна в PNG (нужен matplotlib)
    python3 tools/fast_power.py logs/X.json.gz --set k_ocv=0.7 --smooth

Зачем. Ток BMS (кадр 3A 18 20 10, байты 11–12) обновляется раз в ~1 с (то же сырое значение
приходит ~3 кадра подряд) и описывает среднее за ~0,7 с до кадра, где он сменился, — на
экране он отстаёт на 0,6–0,9 с. Напряжение (байты 9–10) свежее в каждом кадре (~3,2/с), а
батарея — источник с внутренним сопротивлением R ≈ 0,25 Ом: V = OCV − R·I. Значит
I = (OCV − V)/R можно считать в каждом кадре, а ток BMS использовать только как «якорь»
уровня: OCV ≈ V̄ + R·I_bms, где V̄ — среднее напряжение за то же окно, к которому относится
ток BMS. Всё причинно: каждый выход зависит только от прошлых кадров.

Вход — только кадры 3A 18 20 10 по порядку времени: t (мс, любые одни часы), V (В,
u16le × 0,001), I_bms (А, s16le × −0,001: плюс — тяга, разряд; минус — рекуперация и
зарядка), motor (бит 0x20 байта 5; оценка его не использует — см. «Ограничения»).
Выход на каждый кадр: I_fast (А, плюс — тяга) и P_fast = V·I_fast (Вт).

Алгоритм (Kotlin-порт повторяет его по шагам; все параметры — PARAMS ниже):

  Состояние между сбросами: R (Ом), суммы Sxx, Sxy, Syy (сохраняются и при сбросе, это
  свойство батареи; reset(full=True) возвращает R = r0 и обнуляет суммы).
  Сбрасывается: ocv (В, None — нет), slope (В/с), t_ocv (мс), hist — кадры (t, V) за
  последние window_ms, last_t, last_i, anchor_t, prev = (t, V̄, I) прошлого якоря или None,
  vlo/vhi — мин./макс. V с начала окна прошлого якоря.

  update(t, v, i):
   1. Разрыв: если last_t есть и (t − last_t > gap_ms или t < last_t) — сброс (как reset()).
      Переподключение (новый поток кадров) — тоже reset() снаружи.
   2. last_t = t; hist += (t, v); из начала hist убрать кадры с t' < t − window_ms
      (текущий кадр остаётся всегда).
   3. Если ocv = None (первый кадр после сброса): ocv = v + R·i, slope = 0, t_ocv = t,
      last_i = i, anchor_t = t, prev = None, vlo = vhi = v. Якоря на этом кадре нет.
      Иначе vlo = min(vlo, v), vhi = max(vhi, v), и якорь, если
        i ≠ last_i (BMS прислала новое значение; в Kotlin сравнивать сырые мА), или
        t − anchor_t ≥ hold_ms и vhi − vlo ≤ hold_dv (значение не менялось, а напряжение с
        прошлого якоря стоит — значит, ток тот же; нужно на стоянке и на зарядке, где V
        ползёт само. Если V сдвинулось, старое значение BMS описывает прошлое — не якорь).
      Якорь:
        n = |hist|;  V̄ = Σv/n;  tc = Σt/n                      (простое среднее кадров)
        R: если prev ≠ None и t − prev.t ≤ pair_max_ms:
             dI = i − prev.I;  dV = V̄ − prev.V̄
             если |dI| ≥ di_min:
               Sxx = λ·Sxx + dI²;  Sxy = λ·Sxy − dV·dI;  Syy = λ·Syy + dV²
               если Sxy > 0:  R = clamp(√((Syy + P·r0²)/(Sxx + P)), r_min, r_max)
             (λ = r_lambda, P = r_prior в А²; это «приведённая главная ось» — среднее
             геометрическое прямой и обратной регрессий: у прямой МНК R занижен, потому что
             окно BMS и окно V̄ совпадают неточно — шум и в dI, и в dV.)
        OCV (уровень + наклон, фильтр Холта):
             dtc = (tc − t_ocv)/1000;  pred = ocv + slope·dtc
             e = (V̄ + R·i) − pred
             k = k_steady, если prev ≠ None и |i − prev.I| < di_min (ток BMS не сменился), иначе k_ocv
             ocv = pred + k·e
             если dtc > 0,2 с:  slope = clamp(slope + k_slope·e/dtc, −slope_max, slope_max)
             t_ocv = tc
        prev = (t, V̄, i);  vlo = min V в hist;  vhi = max V в hist;  last_i = i;  anchor_t = t
   4. ocv_now = ocv + slope·min(t − t_ocv, horizon_ms)/1000
      I_fast = (ocv_now − v)/R;  P_fast = v·I_fast
      если |P_fast| < deadband_w: I_fast = P_fast = 0   (по умолчанию deadband_w = 0)

  Порядок в якоре важен: сначала R (новый R уже в e), потом OCV. Все времена — мс, наклон —
  В/с. Сглаживание для экрана (переключатель) — отдельный Smoother ниже, оценку оно не трогает.
  synthetic_frames() — детерминированная последовательность для сверки порта (tests/test_fast_power.py
  держит выходы на ней с точностью 1e-9).

Почему так (проверено на логах 14–16.10.2026, см. validate() и CLI):
  * Окно 700 мс, кончающееся кадром, где сменился ток BMS, лучше всех сдвигов и ширин
    объясняет связь ΔV̄ ↔ ΔI_bms (корреляция 0,95 на всех четырёх поездках); BMS обновляется
    с периодом ≈ 998 мс по своим часам.
  * RC-цепочка поляризации на шаге 1 с почти ничего не даёт (остаток 0,224 → 0,221 В):
    медленную поляризацию (V ползёт вверх минутами после стоянки) поглощает якорь раз в
    секунду, а наклон slope и полный вес «спокойных» якорей (k_steady = 1) убирают отставание
    якоря на долгой нагрузке (на упоре 16 А смещение +0,39 → +0,03…0,19 А) и после остановки.
  * R по поездке: 0,29–0,32 Ом в начале (холодная батарея), 0,23–0,25 Ом в конце — так же
    дают чистые отпускания газа (0,24–0,30 Ом). Поэтому R отслеживается, а не задан.
  * Параметры подобраны на первой поездке (20261014-185422), проверены на остальных: целевая
    функция плоская (среднеквадратичная ошибка у обновлений BMS 0,95–0,97 А на всей сетке
    window 600–800, k_ocv 0,4–0,7, k_slope 0–0,2; R-параметры λ 0,95–0,995, вес 10–200 А²,
    di_min 0,5–2 А, hold 1,1–2 с меняют её меньше чем на 0,02 А), на других логах те же
    параметры дают 1,02 / 1,66 / 0,70 А против 2,9 / 4,6 / 1,9 А у «последнего тока BMS».
  * Синтетика с окном BMS, сдвинутым на 0–300 мс от кадра (synthetic_frames(aligned=False)),
    без правила hold_dv роняла R до r_min (якорь со старым током после броска V); с ним R
    сходится к истинным 0,27 Ом.

Ограничения:
  * Уровень держит только ток BMS. Если он застынет, а кадры пойдут: на ходу якорей нет (V
    гуляет), OCV держится, наклон — не дальше horizon_ms, оценка медленно уплывает вместе с
    настоящим OCV; на стоянке якоря с застывшим значением подтянут к нему за ~2 с.
  * Сразу после резкого броска (газ ↔ сброс за один кадр) якорь иногда ловит окно BMS
    наполовину до, наполовину после броска; ошибка до ~2–4 А на один-два кадра, следующий
    якорь её убирает. Отсюда среднеквадратичная ошибка ~1 А у обновлений BMS.
  * Бит «мотор тянет» опережает напряжение на кадр, но ток после него спадает 0,3–0,6 с
    (рампа контроллера) — ограничивать по нему оценку нельзя, она станет врать на рампе.
  * Первый кадр после сброса берёт ток BMS как есть (может быть на секунду старым).
  * Валовая рекуперация по оценке на 6–18 % больше, чем по току BMS (из батареи и нетто —
    до 0,5 %): BMS усредняет короткие всплески вместе с тягой, часть разницы — шум оценки.
  * Сразу после остановки V ещё секунды поднимается (поляризация): до −5 Вт «рекуперации»
    на стоянке, пока якоря не догонят. Ложной тяги на стоянке и на зарядке нет: на стоянке
    оценка 0…+9 Вт при 1,4…5,7 Вт по BMS; для полосы на экране хватит мёртвой зоны ~10 Вт.

Только стандартная библиотека. Ничего не пишет ни в самокат, ни в логи.
"""
import argparse
import collections
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import birdlog as bl  # noqa: E402
import uart_frames as uf  # noqa: E402

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
    'hold_dv': 0.1,        # В, …и только если размах V с начала окна прошлого якоря не больше этого
    'k_ocv': 0.5,          # доля ошибки якоря в уровень OCV, когда ток BMS сменился на ≥ di_min…
    'k_steady': 1.0,       # …и когда не сменился (окна BMS и V̄ совпадают надёжнее)
    'k_slope': 0.2,        # доля ошибки якоря (В) / dtc (с) в наклон OCV
    'slope_max': 0.05,     # В/с, предел наклона OCV
    'horizon_ms': 2000,    # мс, дальше этого от последнего якоря наклон не продолжается
    'gap_ms': 2500,        # мс, разрыв между кадрами больше этого — сброс
    'deadband_w': 0.0,     # Вт, |P| меньше этого — показывать 0 (0 — выкл.)
}

SMOOTH = {                 # Smoother (One Euro filter), только для экрана
    'min_cutoff': 0.3,     # Гц, срез при медленных изменениях (сильное сглаживание дрожи)
    'beta': 0.2,           # Гц на А/с: чем быстрее меняется ток, тем выше срез (меньше задержка)
    'd_cutoff': 1.0,       # Гц, срез для оценки скорости изменения
    'gap_ms': 2500,        # мс, разрыв — начать заново
}

BMS_HEAD = bytes([0x18, 0x20, 0x10])
SPEED_HEAD = bytes([0x17, 0x00, 0x0D])


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
        self.t_ocv = None
        self.hist = collections.deque()
        self.last_t = None
        self.last_i = None
        self.anchor_t = None
        self.prev = None
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
        vbar = sum(x[1] for x in h) / n
        tc = sum(x[0] for x in h) / n
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
        self.vlo = min(x[1] for x in h)
        self.vhi = max(x[1] for x in h)
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
            self.t_ocv = t
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
        i_fast = (ocv_now - v) / self.r
        p_fast = v * i_fast
        if abs(p_fast) < p['deadband_w']:
            i_fast = p_fast = 0.0
        return i_fast, p_fast


class Smoother:
    """Сглаживание для экрана (переключатель «плавно»): One Euro filter. update(t_ms, x) -> x_smooth.

    Медленная дрожь (±0,5 А от кадра к кадру на ровном ходу) режется срезом min_cutoff, а на
    резком броске срез растёт как min_cutoff + beta·|dx/dt|, так что задержка на броске мала.

        te = (t − t_prev)/1000;  α(fc) = 1/(1 + 1/(2π·fc·te))
        dx = (x − x̂)/te;  d̂x += α(d_cutoff)·(dx − d̂x)
        fc = min_cutoff + beta·|d̂x|;  x̂ += α(fc)·(x − x̂)
    Первый кадр и кадр после разрыва > gap_ms (или t назад) возвращаются как есть; te = 0 — x̂ без изменений."""

    def __init__(self, **params):
        unknown = set(params) - set(SMOOTH)
        if unknown:
            raise ValueError(f'неизвестные параметры: {sorted(unknown)}')
        self.p = dict(SMOOTH, **params)
        self.reset()

    def reset(self):
        self.x = None
        self.dx = 0.0
        self.t = None

    @staticmethod
    def _alpha(te, fc):
        return 1.0 / (1.0 + 1.0 / (2.0 * math.pi * fc * te))

    def update(self, t, x):
        p = self.p
        if self.x is None or t < self.t or t - self.t > p['gap_ms']:
            self.x, self.dx, self.t = x, 0.0, t
            return x
        te = (t - self.t) / 1000.0
        self.t = t
        if te <= 0:
            return self.x
        dx = (x - self.x) / te
        self.dx += self._alpha(te, p['d_cutoff']) * (dx - self.dx)
        fc = p['min_cutoff'] + p['beta'] * abs(self.dx)
        self.x += self._alpha(te, fc) * (x - self.x)
        return self.x


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
    return sum(synthetic_current(x) * (y - x) for x, y in zip(edges, edges[1:])) / (b - a)


def synthetic_frames(aligned=True):
    """Deterministic BMS frames [(t, v, i_bms, motor)] for tests and the Kotlin port.

    Frame k at t = 300·k + (37·k mod 61) ms while t < SYN_END_MS. True current — SYN_STEPS;
    OCV = 40 − 0.00002·t V (−0.02 V/s); V = OCV − SYN_R·I_true(t), rounded half up to 1 mV.
    BMS refreshes at every whole second tr = 1000·floor(t/1000): i_bms = mean of I_true over
    [tr − 700, tr], rounded half up to 1 mA. motor = 1 when I_true > 1 A."""
    out = []
    k = 0
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

def read_log(log):
    """Кадры одного лога: {'bms': [(t, v, i_bms, motor)], 'speed': [(t, км/ч)], 'resets': [t]}.

    resets — строки «Найдено сервисов» (новое подключение, поток кадров начинается заново)."""
    asm = uf.Assembler(keep_texts=False)
    ffe1 = bl.norm_uuid('ffe1')
    bms, speed, resets = [], [], []
    for e in log.get('entries', []):
        if e.get('k') == 'sys' and str(e.get('n') or '').startswith(uf.SYS_FOUND):
            asm.restart()
            resets.append(e['t'])
            continue
        if e.get('k') != 'rx' or bl.norm_uuid(e.get('u')) != ffe1:
            continue
        for f in asm.feed(bl.entry_bytes(e), e['t']):
            fr = f.frame
            if fr[1:4] == BMS_HEAD:
                bms.append((f.t, bl.read_value(fr, 9, 'u16le') * 0.001,
                            bl.read_value(fr, 11, 's16le') * -0.001, (fr[5] >> 5) & 1))
            elif fr[1:4] == SPEED_HEAD:
                speed.append((f.t, bl.read_value(fr, 6, 'u16be') * 0.1))
    return {'bms': bms, 'speed': speed, 'resets': resets}


def run(data, params=None, smooth=None):
    """FastPower по кадрам лога. Возвращает [(t, v, i_bms, motor, i_fast, p_fast)];
    smooth — параметры Smoother (dict) или None; тогда i_fast/p_fast — сглаженные."""
    est = FastPower(**(params or {}))
    sm = Smoother(**smooth) if smooth is not None else None
    resets = list(data.get('resets') or [])
    k = 0
    out = []
    for t, v, i, m in data['bms']:
        while k < len(resets) and resets[k] <= t:
            est.reset()
            if sm:
                sm.reset()
            k += 1
        i_f, p_f = est.update(t, v, i, m)
        if sm:
            i_f = sm.update(t, i_f)
            p_f = v * i_f
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


def _pct(xs, q):
    s = sorted(xs)
    if not s:
        return float('nan')
    k = (len(s) - 1) * q / 100
    a = int(math.floor(k))
    b = min(a + 1, len(s) - 1)
    return s[a] + (s[b] - s[a]) * (k - a)


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
    """(Вт·ч из батареи, Вт·ч в батарею) прямоугольниками по кадрам; разрывы > gap_ms пропущены."""
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


def validate(data, params=None, smooth=None, grid_ms=100, max_lag_ms=2000):
    """Все цифры проверки по одному логу (dict)."""
    rows, est = run(data, params, smooth)
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
    d = [gf[k] - gb[k + best] for k in range(max(0, -best), min(len(gf), len(gf) - best))
         if gf[k] is not None and gb[k + best] is not None]
    res['rms_shift'] = math.sqrt(sum(x * x for x in d) / len(d)) if d else float('nan')
    res['bias_shift'] = sum(d) / len(d) if d else float('nan')
    ue = update_errors(rows)
    ub = update_errors(rows, col=2)          # то же для «последнего тока BMS» на экране
    res['upd_n'] = len(ue)
    res['upd_rms'] = math.sqrt(sum(e[1] ** 2 for e in ue) / len(ue)) if ue else float('nan')
    res['upd_bias'] = sum(e[1] for e in ue) / len(ue) if ue else float('nan')
    res['upd_rms_bms'] = math.sqrt(sum(e[1] ** 2 for e in ub) / len(ub)) if ub else float('nan')
    lim = [e[1] for e in ue if e[2] > 14.5 and e[3] > 14.5]
    res['limit_n'] = len(lim)
    res['limit_bias'] = sum(lim) / len(lim) if lim else float('nan')
    for key, thr, up in (('up5', 5.0, True), ('dn2', -2.0, False)):
        leads, n = lead_times(rows, thr, up)
        res[key + '_n'] = n
        res[key + '_hit'] = len(leads)
        res[key + '_lead_ms'] = _median(leads)
    eb, ef = energy(rows, 'bms'), energy(rows, 5)
    res.update(wh_out_bms=eb[0], wh_out_fast=ef[0], wh_in_bms=eb[1], wh_in_fast=ef[1])
    st = standstill(rows, data.get('speed') or [])
    res['still_n'] = len(st)
    if st:
        ps = [r[5] for r in st]
        res.update(still_p_min=min(ps), still_p_max=max(ps), still_fake_traction=sum(1 for x in ps if x > 10.0) / len(ps))
    ib = [r[2] for r in rows]
    res['charging'] = _median(ib) < -0.5 and max(ib) < 0
    if res['charging']:
        res['charge_i_fast_max'] = max(r[4] for r in rows)
        res['charge_i_fast_min'] = min(r[4] for r in rows)
    jit = [abs(b[4] - a[4]) for a, b in zip(rows, rows[1:]) if a[3] and b[3] and abs(b[2] - a[2]) < 0.5]
    res['jitter_a'] = _median(jit) if jit else float('nan')
    return res


def _f(x, fmt):
    return '—' if x is None or x != x else format(x, fmt)


def _pct_diff(a, b):
    return (b - a) / a * 100 if a else float('nan')


def print_table(results, stream=None):
    p = lambda *a: print(*a, file=stream or sys.stdout)  # noqa: E731
    p('лог                          кадры  corr0 corrMax@сдвиг  опережение 5А / −2А     ош.обновл. (BMS)  смещ.  упор16А  '
      'Вт·ч из бат. BMS/оценка   в бат. BMS/оценка   стоянка P, Вт  дрожь   R конец')
    for name, r in results:
        if r.get('frames', 0) < 10:
            p(f'{name[:28]:28s} {r.get("frames", 0):6d}  мало кадров')
            continue
        lead = (f'{_f(r["up5_lead_ms"], ".0f"):>4s} мс ({r["up5_hit"]}/{r["up5_n"]}) '
                f'{_f(r["dn2_lead_ms"], ".0f"):>4s} мс ({r["dn2_hit"]}/{r["dn2_n"]})')
        still = (f'{_f(r.get("still_p_min"), "+.0f")}…{_f(r.get("still_p_max"), "+.0f")}'
                 if r.get('still_n') else '—')
        p(f'{name[:28]:28s} {r["frames"]:6d}  {_f(r["corr0"], ".3f")} {_f(r["corr_best"], ".3f")}@{r["lag_best_ms"]:+5d}мс  '
          f'{lead:24s} {_f(r["upd_rms"], ".2f")} А ({_f(r["upd_rms_bms"], ".2f")})  {_f(r["upd_bias"], "+.3f")}  '
          f'{_f(r["limit_bias"], "+.2f")}  '
          f'{r["wh_out_bms"]:6.1f}/{r["wh_out_fast"]:6.1f} ({_f(_pct_diff(r["wh_out_bms"], r["wh_out_fast"]), "+.1f")} %)  '
          f'{r["wh_in_bms"]:5.2f}/{r["wh_in_fast"]:5.2f} ({_f(_pct_diff(r["wh_in_bms"], r["wh_in_fast"]), "+.0f")} %)  '
          f'{still:>11s}  {_f(r.get("jitter_a"), ".2f"):>4s} А  {r["r_end"]:.3f}')
        if r.get('charging'):
            p(f'{"":28s} зарядка: I_fast {r["charge_i_fast_min"]:+.2f}…{r["charge_i_fast_max"]:+.2f} А (тяги нет)')
    p('\ncorr0 / corrMax — корреляция I_fast с током BMS на сетке 100 мс без сдвига / при лучшем сдвиге (плюс — BMS позже).')
    p('Опережение — медиана, на сколько I_fast раньше тока BMS пересекает 5 А вверх и −2 А вниз (найдено/всего).')
    p('Ош. обновл. — СКО среднего I_fast за 0,7 с до кадра, где BMS прислала новый ток, от этого тока; в скобках — то же')
    p('для «последнего тока BMS» на экране. Смещ. — среднее этой ошибки; упор 16 А — среднее, когда ток BMS > 14,5 А.')
    p('Стоянка — мин…макс P_fast там, где скорость 0 не меньше 3 с в обе стороны. Дрожь — медиана |ΔI_fast| между')
    p('соседними кадрами под тягой при неизменном токе BMS. R конец — оценка R в конце лога, Ом.')


def plot_windows(name, data, rows, out_dir, windows):
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        print('matplotlib нет — графики пропущены')
        return []
    os.makedirs(out_dir, exist_ok=True)
    made = []
    sm_rows, _ = run(data, smooth={})
    for t0, t1 in windows:
        sel = [k for k, r in enumerate(rows) if t0 * 1000 <= r[0] <= t1 * 1000]
        if not sel:
            continue
        ts = [rows[k][0] / 1000 for k in sel]
        fig, ax = plt.subplots(2, 1, figsize=(14, 7), sharex=True, gridspec_kw={'height_ratios': [3, 1]})
        ax[0].step(ts, [rows[k][2] for k in sel], where='post', lw=1.4, label='ток BMS, А')
        ax[0].plot(ts, [rows[k][4] for k in sel], '.-', ms=3, lw=1, label='I_fast, А')
        ax[0].plot(ts, [sm_rows[k][4] for k in sel], lw=1, alpha=.8, label='I_fast + Smoother')
        ax[0].plot(ts, [rows[k][3] * 1.5 - 3 for k in sel], '.', ms=2, color='gray', label='бит «мотор тянет»')
        ax[0].axhline(0, color='k', lw=.5)
        ax[0].grid(alpha=.3)
        ax[0].legend(loc='upper right', fontsize=8)
        ax[0].set_title(f'{name}  {t0:g}–{t1:g} с')
        sp = [(t / 1000, s) for t, s in data['speed'] if t0 * 1000 <= t <= t1 * 1000]
        if sp:
            ax[1].plot([x[0] for x in sp], [x[1] for x in sp], lw=.8, color='tab:green')
        ax[1].set_ylabel('км/ч')
        a2 = ax[1].twinx()
        a2.plot(ts, [rows[k][1] for k in sel], lw=.7, color='tab:red')
        a2.set_ylabel('В')
        plt.tight_layout()
        fn = os.path.join(out_dir, f'{bl.slug(name, "log")}-{int(t0)}-{int(t1)}.png')
        plt.savefig(fn, dpi=80)
        plt.close(fig)
        made.append(fn)
    return made


def _auto_windows(rows, n=3, span=40.0):
    """Окна вокруг самых резких смен тока BMS (разнесённых не меньше чем на span)."""
    jumps = sorted(((abs(b[2] - a[2]), b[0] / 1000) for a, b in zip(rows, rows[1:])), reverse=True)
    picked = []
    for _, t in jumps:
        if all(abs(t - x) > span for x in picked):
            picked.append(t)
        if len(picked) >= n:
            break
    return [(max(0.0, t - span / 2), t + span / 2) for t in sorted(picked)]


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
    ap.add_argument('--smooth', action='store_true', help='проверять I_fast после Smoother (как на экране с «плавно»)')
    ap.add_argument('--plots', metavar='DIR', help='нарисовать окна в PNG (нужен matplotlib)')
    ap.add_argument('--window', action='append', metavar='T0:T1', help='окно для --plots, секунды лога (иначе 3 авто)')
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
        data = read_log(lg)
        r = validate(data, params, {} if a.smooth else None)
        results.append((name, r))
        if a.plots:
            rows, _ = run(data, params)
            wins = [tuple(float(x) for x in w.split(':')) for w in a.window] if a.window else _auto_windows(rows)
            for fn in plot_windows(name, data, rows, a.plots, wins):
                print('график: ' + fn)
    print_table(results)
    return 0


if __name__ == '__main__':
    sys.exit(main())
