#!/usr/bin/env python3
"""Предупреждения о батарее и честный запас хода (эталон для приложения).

    python3 tools/battery_advisor.py logs/*.json.gz     # прогон логов: предупреждения и ошибки запаса хода

Вход — кадры по порядку: on_bms(t, V, I, temp1, temp2, остаток мА·ч, полная мА·ч) на каждый
3A 18 20 10 (I — ток BMS, А, плюс — тяга) и on_distance(t, км) на каждый 3A 17 00 0D — сколько на нём
добавил свой одометр приложения (app_odometer.py, last_added_km), а не его итог: исправление итога
(«Задать», «Взять с руля») — не поездка. Выход — warnings() (список BatteryWarning) и range() (Range или None).

1. «Заряд на исходе» / «Сейчас отключится» (id battery_low, уровни warning / critical).
   Под нагрузкой (I ≥ load_a) напряжение садится тем сильнее, чем пустее батарея, — так её и видно
   внизу, где процент BMS оптимистичен. Порог — по кадрам под нагрузкой подряд, не меньше sustain_ms:
     warning:  V10 = V + r_ref·(I − i_ref) < low_v — напряжение, приведённое к 10 А (не зависит от
               того, сколько тянешь: на 54 % при 15,9 А вдвоём V10 не ниже 33,0 В, внизу 32,0–32,4);
     critical: само напряжение под нагрузкой V < crit_v. Единого порога отсечки по напряжению нет:
               19.10 контроллер снял тягу на 31,45 В при 8,6 А на 1 %, 20.10 BMS отключила батарею на 31,24 В
               после 12 с ниже 31,45 В, а 14.10 ехали 31,6 с ниже 31,45 В (до 31,12 В) без отсечки; в нижних
               ~30 % контроллер урезает ток по напряжению (≈ 4,7 А/В · (V − 29,5 В), 16 → 8–12 А) и держит
               батарею у 31,2–31,7 В под нагрузкой, поэтому crit_v = 31,6, а не 32,0
               (с 32,0 «Сейчас отключится» загоралось уже на 16–34 % BMS, в трёх разрядах из четырёх —
               за 7–15 мин до конца).
   critical → warning, если под нагрузкой V ≥ crit_clear_v держится clear_ms подряд (сбросил газ —
   отсечка отступила). Оба снимаются только зарядкой: V ≥ charged_v при I ≤ rest_a charged_ms подряд.
2. «Батарея греется» / «Батарея перегрета — скоро отключится, сбавь газ» (battery_hot):
   max(temp1, temp2) ≥ hot_c / ≥ overheat_c, снимаются на hyst_c ниже. 15.10 самокат погас на 57 / 61 °C
   (датчики 7 / 8) при 31,9 В под нагрузкой — выше, чем 31,45–31,5 В, до которых доезжали другие
   разряды: это защита батареи от перегрева, а не отсечка по напряжению.
3. Запас хода. Пусто — не 0 % BMS, а наблюдённый конец: reserve_pct % полной ёмкости по BMS
   (по логам самокат доехал до 1,9 % (19.10, там отсечка), 0,4 % (16.10) и 2,2 % (14.10); отсечку
   15.10 на 11,9 % при 61 °C объясняет перегрев, а не заряд — её ловит battery_hot).
     энергия, Вт·ч = (остаток − резерв, мА·ч) · wh_per_mah (на мА·ч счётчика BMS по логам
     приходится 29,6–32,5 мВт·ч: счётчик идёт на 8 % быстрее ∫I, поэтому не V·мА·ч);
     расход, Вт·ч/км — за последние window_km (не меньше min_window_km), иначе за весь заезд
     (не меньше min_session_km), иначе default_wh_km; рекуперация вычитается;
     км = энергия / расход, мили = км / 1,609344.
   Энергия — V·I_bms по кадрам (левые прямоугольники), разрывы > gap_ms пропущены. Путь — свой, с 0:
   приращения одометра приложения, только пока поток непрерывен (прошлый 3A 17 и последний кадр BMS не
   дальше gap_ms) — то, что одометр добавляет за разрыв (пропущено без связи, новое включение), без энергии.

Проверено на логах (CLI): до отсечки 19.10 по напряжению (31,45 В при 8,6 А, BMS 1 %) «Заряд на исходе» /
«Сейчас отключится» — за 7,8 / 1,7 мин (на 32 / 7 %); до отключения BMS 20.10 (низкое напряжение, 31,24 В при
7,7 А, BMS 5 %) — за 8,8 / 2,6 мин (2,7 / 0,7 км); до тепловой отсечки 15.10 (57 / 61 °C, 31,9 В, BMS 11 %)
«Батарея перегрета» — за 18,6 мин, «Заряд на исходе» — за 5,4 мин, «Сейчас отключится» не нужно и не горит
(ниже 31,70 В под нагрузкой не было). В разрядах до конца без отсечки — за 15,1 / 3,1 мин (14.10, один) и
8,7 мин / — (16.10, вдвоём: ниже 31,61 В не было). Ни разу при BMS ≥ 40 % и ни разу вдвоём на 100 → 54 %
при упоре 16 А; «Сейчас отключится» — ни разу, пока под нагрузкой ≥ 31,6 В. Запас хода на четырёх разрядах
до конца (отметки 5, 4, 3, 2, 1,5, 1, 0,5 км до конца): ошибка в среднем 0,39 км, смещение −0,03 км;
до отсечки 19.10 — не больше 0,57 км на всех отметках, до отсечки 15.10 — оптимистичен до +1,2 км
(она на 11,9 %).

Kotlin: android/core BatteryAdvisor.kt — то же по шагам. Только чтение логов.
"""
import argparse
import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import app_odometer as ao  # noqa: E402
import bird_frames as bf  # noqa: E402
import birdlog as bl  # noqa: E402

PARAMS = {
    'load_a': 5.0,             # А, кадр «под нагрузкой»
    'sustain_ms': 2000,        # мс, порог под нагрузкой подряд — не меньше этого
    'r_ref': 0.25,             # Ом, V10 = V + r_ref·(I − i_ref)
    'i_ref': 10.0,             # А
    'low_v': 32.4,             # В, V10 ниже — «Заряд на исходе»
    'crit_v': 31.6,            # В, V под нагрузкой ниже — «Сейчас отключится» (отсечки были на 31,45 и 31,24)
    'crit_clear_v': 32.5,      # В, V под нагрузкой не ниже этого clear_ms подряд — обратно «на исходе»
    'clear_ms': 20000,         # мс
    'charged_v': 37.5,         # В, V на отдыхе/зарядке не ниже этого charged_ms подряд — снять всё
    'rest_a': 1.0,             # А, «на отдыхе»: I не больше этого
    'charged_ms': 10000,       # мс
    'hot_c': 50.0,             # °C, «Батарея греется»
    'overheat_c': 55.0,        # °C, «Батарея перегрета» (тепловая отсечка 15.10 — 57 / 61 °C)
    'hyst_c': 2.0,             # °C, снимается на столько ниже
    'reserve_pct': 3.0,        # % полной ёмкости BMS — это уже «пусто»
    'window_km': 2.0,          # км, окно расхода
    'min_window_km': 1.0,      # км, окно короче — расход за заезд
    'min_session_km': 0.3,     # км, заезд короче — расход по умолчанию
    'default_wh_km': 15.0,     # Вт·ч/км (один 11,4, вдвоём 18–19,5)
    'wh_per_mah': 0.031,       # Вт·ч на мА·ч счётчика BMS
    'gap_ms': 2500,            # мс, разрыв между кадрами BMS — не интегрировать и сбросить «подряд»
}

MILE_KM = 1.609344
WARNING, CRITICAL = 'warning', 'critical'

BatteryWarning = collections.namedtuple('BatteryWarning', 'id level text value')
Range = collections.namedtuple('Range', 'km mi wh_left wh_per_km basis')

TEXTS = {
    ('battery_low', WARNING): 'Заряд на исходе',
    ('battery_low', CRITICAL): 'Сейчас отключится',
    ('battery_hot', WARNING): 'Батарея греется',
    ('battery_hot', CRITICAL): 'Батарея перегрета — скоро отключится, сбавь газ',
}


class BatteryAdvisor:
    def __init__(self, **params):
        unknown = set(params) - set(PARAMS)
        if unknown:
            raise ValueError(f'неизвестные параметры: {sorted(unknown)}')
        self.p = dict(PARAMS, **params)
        self.reset()

    def reset(self):
        """Всё с начала (новая запись)."""
        self.low = 0                 # 0 — нет, 1 — warning, 2 — critical
        self.hot = 0
        self.low_since = self.crit_since = self.clear_since = self.charged_since = None
        self.loaded_v = None         # V последнего кадра под нагрузкой
        self.tmax = None
        self.last_t = None
        self.last_p = None
        self.e_wh = 0.0              # энергия из батареи с начала, Вт·ч (рекуперация вычтена)
        self.rem = self.full = None
        self.km = None               # свой путь с энергией, км с начала (None — ещё не было 3A 17)
        self.dist_t = None           # t прошлого 3A 17
        self.cps = collections.deque()   # (км, Вт·ч) на каждом приросте км, окно window_km
        self.first = None

    # -- input

    def on_bms(self, t, v, i, temp1=None, temp2=None, rem_mah=None, full_mah=None):
        p = self.p
        gap = self.last_t is not None and (t - self.last_t > p['gap_ms'] or t < self.last_t)
        if self.last_t is not None and not gap and t > self.last_t:
            self.e_wh += self.last_p * (t - self.last_t) / 3.6e6
        if gap:
            self.low_since = self.crit_since = self.clear_since = self.charged_since = None
        self.last_t = t
        self.last_p = v * i
        if rem_mah is not None and full_mah:
            self.rem, self.full = rem_mah, full_mah
        self._voltage(t, v, i)
        self._temperature(temp1, temp2)

    def on_distance(self, t, added_km):
        """Кадр 3A 17; added_km — сколько на нём добавил одометр приложения (last_added_km)."""
        p = self.p
        dt, bt = self.dist_t, self.last_t
        self.dist_t = t
        if self.km is None:              # the first frame: the distance starts at 0
            self.km = 0.0
            self.first = (0.0, self.e_wh)
            self.cps.append(self.first)
            return
        if added_km is None or added_km <= 0:
            return
        if dt is None or t < dt or t - dt > p['gap_ms']:
            return                       # over a gap: ridden without the energy measured
        if bt is None or t < bt or t - bt > p['gap_ms']:
            return
        km = self.km + added_km
        self.km = km
        self.cps.append((km, self.e_wh))
        w = p['window_km']
        while len(self.cps) > 1 and self.cps[1][0] <= km - w:
            self.cps.popleft()

    def _voltage(self, t, v, i):
        p = self.p
        loaded = i >= p['load_a']
        if loaded:
            self.loaded_v = v
        if loaded and v + p['r_ref'] * (i - p['i_ref']) < p['low_v']:
            if self.low_since is None:
                self.low_since = t
            if t - self.low_since >= p['sustain_ms'] and self.low < 1:
                self.low = 1
        else:
            self.low_since = None
        if loaded and v < p['crit_v']:
            if self.crit_since is None:
                self.crit_since = t
            if t - self.crit_since >= p['sustain_ms']:
                self.low = 2
        else:
            self.crit_since = None
        if self.low == 2 and loaded and v >= p['crit_clear_v']:
            if self.clear_since is None:
                self.clear_since = t
            if t - self.clear_since >= p['clear_ms']:
                self.low = 1
                self.clear_since = None
        else:
            self.clear_since = None
        if self.low and i <= p['rest_a'] and v >= p['charged_v']:
            if self.charged_since is None:
                self.charged_since = t
            if t - self.charged_since >= p['charged_ms']:
                self.low = 0
                self.charged_since = None
        else:
            self.charged_since = None

    def _temperature(self, t1, t2):
        ts = [x for x in (t1, t2) if x is not None and -30 <= x <= 120]
        if not ts:
            return
        p = self.p
        x = max(ts)
        self.tmax = x
        if x >= p['overheat_c'] or (self.hot == 2 and x > p['overheat_c'] - p['hyst_c']):
            self.hot = 2
        elif x >= p['hot_c'] or (self.hot >= 1 and x > p['hot_c'] - p['hyst_c']):
            self.hot = 1
        else:
            self.hot = 0

    # -- output

    def warnings(self):
        """Действующие предупреждения, сначала critical."""
        out = []
        for wid, lvl, val in (('battery_low', self.low, self.loaded_v), ('battery_hot', self.hot, self.tmax)):
            if lvl:
                level = CRITICAL if lvl == 2 else WARNING
                out.append(BatteryWarning(wid, level, TEXTS[(wid, level)], val))
        out.sort(key=lambda w: 0 if w.level == CRITICAL else 1)
        return out

    def range(self):
        """Запас хода до наблюдённой отсечки, или None, пока BMS не прислала остаток."""
        p = self.p
        if self.rem is None or not self.full:
            return None
        basis = 'default'
        wh_km = p['default_wh_km']
        if self.km is not None and self.cps:
            w, f = self.cps[0], self.first
            if self.km - w[0] >= p['min_window_km']:
                basis, wh_km = 'recent', (self.e_wh - w[1]) / (self.km - w[0])
            elif self.km - f[0] >= p['min_session_km']:
                basis, wh_km = 'session', (self.e_wh - f[1]) / (self.km - f[0])
        if wh_km < 1.0:                  # downhill: no meaningful consumption, use the default
            wh_km = p['default_wh_km']
        usable = self.rem - p['reserve_pct'] * self.full / 100.0
        wh_left = usable * p['wh_per_mah'] if usable > 0 else 0.0
        km = wh_left / wh_km
        return Range(km, km / MILE_KM, wh_left, wh_km, basis)


# ---------------------------------------------------------------- replay and validation

def replay(log, params=None, every=None, events=None):
    """Лог через AppOdometer и BatteryAdvisor. Возвращает список шагов
    (t, км, warnings, range, bms) на каждом кадре BMS (км — путь советчика с начала лога; every — функция
    t → bool, чтобы реже).
    events — уже разобранные bird_frames.events(log), если есть."""
    adv = BatteryAdvisor(**(params or {}))
    od = ao.AppOdometer()
    t0 = bf.started_ms(log)
    out = []
    for kind, t, x in (events if events is not None else bf.events(log)):
        if kind == 'motion':
            od.update(x.trip_km, x.odo_km, None if t0 is None else t0 + t)
            adv.on_distance(t, od.last_added_km)
        elif kind == 'bms':
            adv.on_bms(t, x.v, x.i, x.temp1, x.temp2, x.rem_mah, x.full_mah)
            if every is None or every(t):
                out.append((t, adv.km, adv.warnings(), adv.range(), x))
    return out


def first_times(steps):
    """{(id, level): первое t, когда предупреждение этого уровня или выше появилось}."""
    out = {}
    for t, _, ws, _, _ in steps:
        for w in ws:
            out.setdefault((w.id, w.level), t)
            if w.level == CRITICAL:
                out.setdefault((w.id, WARNING), t)
    return out


def power_on_times(events):
    """t кадров 3A 17, на которых поездка с включения (байты 10–11) пошла заново: руль включили снова."""
    out, prev = [], None
    for kind, t, x in events:
        if kind == 'motion':
            if prev is not None and x.trip_km < prev - 0.05:
                out.append(t)
            prev = x.trip_km
    return out


def end_of_traction(steps, load_a=5.0, events=None, gap_ms=60000):
    """t последнего кадра с током BMS ≥ load_a (конец езды) и км в этот момент — до первого нового включения:
    20.10 после отсечки BMS руль включили снова и доехали домой в TAKEOFF, это уже не та езда, о конце которой
    предупреждали. Новое включение — сброс поездки руля (events — bird_frames.events(лог)), без events — разрыв
    кадров BMS дольше gap_ms после тяги."""
    ons = power_on_times(events) if events is not None else []
    stop = ons[0] if ons else None
    last, prev_t = None, None
    for t, km, _, _, x in steps:
        if stop is not None and t >= stop:
            break
        if events is None and gap_ms is not None and last is not None and prev_t is not None and t - prev_t > gap_ms:
            break
        prev_t = t
        if x.i >= load_a:
            last = (t, km)
    return last


def range_errors(steps, km_end, marks=(5.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.5)):
    """[(осталось на деле, предсказано)] в первый момент, когда на деле осталось ≤ отметки."""
    out = []
    for m in marks:
        for t, km, _, rg, _ in steps:
            if km is not None and rg is not None and km_end - km <= m:
                out.append((km_end - km, rg.km))
                break
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description='Предупреждения о батарее и запас хода: прогон логов')
    ap.add_argument('paths', nargs='+', help='логи (.json, .json.gz) или папки')
    a = ap.parse_args(argv)
    notes = []
    logs = bl.load_logs(a.paths, notes)
    for n in notes:
        print('! ' + n)
    allerr = []
    for lg in logs:
        name = os.path.basename((lg.get('_files') or ['лог'])[0])
        ev = bf.events(lg)
        steps = replay(lg, events=ev)
        if not steps:
            continue
        last = steps[-1]
        end = end_of_traction(steps, events=ev)
        print(f'== {name}: заряд {steps[0][4].pct}→{last[4].pct} %, км {_f(steps[0][1])}→{_f(last[1])}')
        if end is None:
            print('   езды нет')
        ft = first_times(steps)
        for (wid, lvl), t in sorted(ft.items(), key=lambda kv: kv[1]):
            line = f'   {TEXTS[(wid, lvl)]:18s} с {t / 1000:7.1f} с'
            if end is not None and t <= end[0]:
                kms = [s[1] for s in steps if s[0] == t]
                line += f' — за {(end[0] - t) / 60000:4.1f} мин'
                if kms and kms[0] is not None and end[1] is not None:
                    line += f' / {end[1] - kms[0]:.2f} км до конца езды'
            print(line)
        if end is not None and last[4].pct <= 12:
            errs = range_errors(steps, last[1])
            allerr += [p - a for a, p in errs]
            print('   запас хода, осталось на деле → предсказано, км: ' + ', '.join(f'{x:.1f}→{y:.2f}' for x, y in errs))
    if allerr:
        mae = sum(abs(x) for x in allerr) / len(allerr)
        print(f'\nзапас хода по разряженным до конца: ошибка в среднем {mae:.2f} км, смещение {sum(allerr) / len(allerr):+.2f} км')
    return 0


def _f(x):
    return '—' if x is None else f'{x:.1f}'


if __name__ == '__main__':
    sys.exit(main())
