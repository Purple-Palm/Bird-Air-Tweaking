"""Real logs for tests/test_fast_power.py, test_battery_advisor.py and test_app_odometer.py, parsed once
per process (unittest discover runs all modules in one)."""
import functools
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(ROOT, 'tools'))

import bird_frames as bf  # noqa: E402
import birdlog as bl  # noqa: E402

LOGS = os.path.join(ROOT, 'logs')
SOLO = '20261014-185422-log.json.gz'          # solo FLY, 8.5 km, 75 → 2 %
CUTOFF_HOT = '20261015-155523-log-app.json.gz'  # two-up, the scooter switched off at 11 % (61 °C)
TWO_UP_HALF = '20261016-135138-log-app.json.gz'  # two-up, 100 → 54 %, current-limited 16 A
TWO_UP_EMPTY = '20261016-190042-log-app.json.gz'  # two-up, 72 → 0 %
CUTOFF = '20261019-174739-log-app.json.gz'    # two-up FLY 98 → 1 %, traction cut at 1 %
CHARGE = '20261016-205717-log-app.json.gz'    # charging 45 → 100 % (trimmed at 250k entries)
CAR_CHARGE = '20261016-182924-log.json.gz'    # charging in a car, −1.7 A
EVENING = '20261019-210801-log-app.json.gz'   # charging 64 → 87 %, then two-up FLY 87 → 29 %, regen to 44 V
TO_ZERO = '20261020-214221-do-otsechki-do-nulya-app.json.gz'  # solo FLY 100 → 5 %, the BMS cut the pack off; reboots
ALL = (SOLO, CUTOFF_HOT, TWO_UP_HALF, CAR_CHARGE, TWO_UP_EMPTY, CHARGE, CUTOFF, TO_ZERO)

# t (ms of the log) of the real cutoffs: the last frame before the scooter went dark (15.10: the battery's
# over-temperature protection at 57 / 61 °C, 31.9 V under load), the frame where the motor bit dropped under
# 8.6 A at 31.45 V and traction never came back (19.10: the controller's low-voltage cutoff) and the last BMS frame
# (20.10: the BMS's under-voltage, b5 0x02 from 1810.0 s, 31.24 V at 7.7 A, BMS 5 %).
CUTOFF_T = {CUTOFF_HOT: 1883909, CUTOFF: 5487027, TO_ZERO: 1812402}


@functools.lru_cache(maxsize=None)
def log(name):
    return bl.load_file(os.path.join(LOGS, name))


@functools.lru_cache(maxsize=None)
def events(name):
    return tuple(bf.events(log(name)))
