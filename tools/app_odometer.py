#!/usr/bin/env python3
"""Собственный одометр приложения: переживает, когда самокат теряет пробег.

    python3 tools/app_odometer.py logs/*.json.gz     # прогнать логи по порядку, итог и поездки

Зачем. Одометр самоката (кадр 3A 17 00 0D, байты 12–14) теряет пробег при выключении: после
отсечки 15.10 он снова начался с 1434,2 (было 1439,6), потом ещё раз с 1434,2, позже с 1445,0.
Счётчик поездки (байты 10–11) обнуляется при каждом включении, а «пробег при включении»
base = одометр − поездка в одном включении постоянен (проверено по всем логам: одно значение
на лог). Поэтому приложение считает своё: по приращениям счётчика поездки внутри включения.

Состояние (приложение хранит его само и отдаёт обратно): total_km — свой одометр, base_km и
trip_km — включение, в котором был последний кадр, wall_ms — время этого кадра по часам
телефона (мс эпохи, None — неизвестно). Всё внутри — в десятых км (целые), как у самоката.

update(trip_km, odo_km, wall_ms):
  1. Первый раз (состояния нет): total = одометр самоката (засев), включение = (base, trip).
  2. То же включение, если base совпадает (±0,1 км) и trip не меньше прошлого: + (trip − прошлый trip).
     Переподключение на ходу так и считается — без двойного счёта.
  3. Иначе, если с прошлого кадра столько не проехать (trip > max_kmh·Δt + slack_km, оба времени
     известны): это не новое включение, а сбой — trip меньше прошлого: кадр пропускается; base другой:
     + (trip − прошлый trip).
  4. Иначе новое включение (trip сбросился или base другой — одометр прыгнул, в том числе назад):
     + trip целиком — всё, что проехано с включения, даже без приложения.
  5. Приращение не больше max_kmh·Δt + slack_km, если оба времени известны и идут вперёд.

Kotlin: android/core AppOdometer.kt — то же по шагам. Только чтение логов.
"""
import argparse
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bird_frames as bf  # noqa: E402
import birdlog as bl  # noqa: E402

PARAMS = {
    'max_kmh': 45.0,       # км/ч, быстрее самокат не едет (FLY 16 mph ≈ 26 км/ч, накатом до 30)
    'slack_km': 0.3,       # км, запас к max_kmh·Δt (округление счётчиков, часы)
    'same_base_km': 0.1,   # км, base в пределах этого — то же включение
}


def tenths(km):
    """км → десятые км, округление половины вверх (Kotlin: floor(x·10 + 0,5))."""
    return int(math.floor(km * 10 + 0.5))


class AppOdometer:
    """Свой одометр. state — dict {total_km, base_km, trip_km, wall_ms} или None (ещё не засеян)."""

    def __init__(self, state=None, **params):
        unknown = set(params) - set(PARAMS)
        if unknown:
            raise ValueError(f'неизвестные параметры: {sorted(unknown)}')
        self.p = dict(PARAMS, **params)
        self.total = self.base = self.trip = None
        self.wall = None
        if state:
            self.total = tenths(state['total_km'])
            self.base = tenths(state['base_km'])
            self.trip = tenths(state['trip_km'])
            self.wall = state.get('wall_ms')

    @property
    def state(self):
        if self.total is None:
            return None
        return {'total_km': self.total / 10, 'base_km': self.base / 10, 'trip_km': self.trip / 10, 'wall_ms': self.wall}

    @property
    def total_km(self):
        return None if self.total is None else self.total / 10

    def _max_tenths(self, wall):
        """Сколько десятых км можно проехать с прошлого кадра; None — неизвестно."""
        if wall is None or self.wall is None or wall < self.wall:
            return None
        return int(math.ceil(self.p['max_kmh'] * (wall - self.wall) / 360000.0)) + tenths(self.p['slack_km'])

    def update(self, trip_km, odo_km, wall_ms=None):
        """Кадр 3A 17: поездка и одометр самоката (км). Возвращает свой одометр, км."""
        trip, odo = tenths(trip_km), tenths(odo_km)
        if trip < 0 or odo < 0:
            return self.total_km
        base = odo - trip
        if self.total is None:
            self.total, self.base, self.trip, self.wall = odo, base, trip, wall_ms
            return self.total_km
        cap = self._max_tenths(wall_ms)
        if trip >= self.trip and abs(base - self.base) <= tenths(self.p['same_base_km']):
            delta = trip - self.trip                 # the same power-on session
        elif cap is not None and trip > cap:         # a new session could not have ridden that much yet
            if trip < self.trip:
                return self.total_km                 # the trip counter glitched back: ignore the frame
            delta = trip - self.trip                 # the odometer glitched: same stream
        else:
            delta = trip                             # a new power-on: all of its trip is new
        if cap is not None and delta > cap:
            delta = cap
        self.total += delta
        self.base, self.trip = base, trip
        if wall_ms is not None:
            self.wall = wall_ms
        return self.total_km


def replay(logs, state=None, params=None, events=None):
    """Логи по порядку → (одометр, [(файл, +км за лог, свой одометр в конце, одометр самоката в конце)]).
    events — функция лог → bird_frames.events(лог), если они уже разобраны."""
    od = AppOdometer(state, **(params or {}))
    rows = []
    for lg in logs:
        t0 = bf.started_ms(lg)
        before = od.total_km
        last_odo = None
        for kind, t, x in (events(lg) if events else bf.events(lg)):
            if kind == 'motion':
                od.update(x.trip_km, x.odo_km, None if t0 is None else t0 + t)
                if before is None:          # the seed: the scooter's odometer at the very first frame
                    before = od.total_km
                last_odo = x.odo_km
        name = os.path.basename((lg.get('_files') or ['лог'])[0])
        added = 0.0 if before is None else round(od.total_km - before, 1)
        rows.append((name, added, od.total_km, last_odo))
    return od, rows


def main(argv=None):
    ap = argparse.ArgumentParser(description='Свой одометр приложения: прогон логов по порядку')
    ap.add_argument('paths', nargs='+', help='логи (.json, .json.gz) или папки')
    a = ap.parse_args(argv)
    notes = []
    logs = bl.load_logs(a.paths, notes)
    for n in notes:
        print('! ' + n)
    od, rows = replay(logs)
    print('лог                                  +км   свой одометр  одометр самоката')
    for name, d, tot, odo in rows:
        print(f'{name[:34]:34s} {d:6.1f}  {_f(tot):>12s}  {_f(odo):>16s}')
    print(f'итого: свой {_f(od.total_km)} км; состояние {od.state}')
    return 0


def _f(x):
    return '—' if x is None else f'{x:.1f}'


if __name__ == '__main__':
    sys.exit(main())
