#!/usr/bin/env python3
"""Разбор рекламного (advertisement) пакета Bird Air: заряд, режим, пробег, версия.

Самокат вещает эти значения в manufacturer-specific данных рекламы — их видно без
подключения, рукопожатия и серверов Bird. На телефоне Chrome их не отдаёт; смотри в nRF
Connect поле «Manufacturer data» и передай сюда его байты:

    python3 tools/decode_adv.py "4C 00 41 00 64 01 10 27 00 00"       # с company id (первые 2 байта)
    python3 tools/decode_adv.py --skip 0 "41 00 64 01 10 27 00 00"       # company id уже убран

Формат (из кода приложения Bird, Ot4.h → BirdAirBleScannedVehicle), little-endian, после
2-байтового company id:
    версия u16, заряд u8, режим u8, пробег u32.

Маппинг режима и коэффициент пробега требуют подтверждения на живом самокате — см.
docs/bird-air-protocol.md. Инструмент только читает и ничего никуда не отправляет.
"""
import argparse
import sys

MODE_ES = {0: 'POWER_SAVING', 1: 'NORMAL'}        # иначе SPORT (kU0.a, ветка es-моделей)


def parse_hex(s):
    clean = ''.join(c for c in s if c in '0123456789abcdefABCDEF')
    if len(clean) % 2:
        raise ValueError('нечётное число hex-цифр')
    return bytes.fromhex(clean)


def decode(payload):
    """payload = manufacturer-specific данные БЕЗ company id (version u16, battery u8, mode u8, distance u32)."""
    if len(payload) < 8:
        raise ValueError(f'нужно минимум 8 байт после company id, получено {len(payload)}')
    version = payload[0] | payload[1] << 8
    battery = payload[2]
    mode_raw = payload[3]
    distance = payload[4] | payload[5] << 8 | payload[6] << 16 | payload[7] << 24
    return {
        'version': version,
        'battery': battery,
        'mode_raw': mode_raw,
        'mode': MODE_ES.get(mode_raw, 'SPORT'),
        'distance_raw': distance,
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description='Разбор рекламного пакета Bird Air (только чтение)')
    ap.add_argument('hex', help='байты в hex (поле Manufacturer data из nRF Connect)')
    ap.add_argument('--skip', type=int, default=2, metavar='N',
                    help='сколько байт company id отрезать слева (nRF Connect показывает его в начале, обычно 2; '
                         '0 — если company id уже убран)')
    a = ap.parse_args(argv)
    try:
        data = parse_hex(a.hex)
    except ValueError as e:
        print('Не разобрать hex:', e, file=sys.stderr)
        return 1
    payload = data[a.skip:]
    try:
        r = decode(payload)
    except ValueError as e:
        print('Не разобрать пакет:', e, file=sys.stderr)
        return 1
    print(f'версия прошивки: {r["version"]}')
    print(f'заряд:           {r["battery"]} %')
    print(f'режим:           {r["mode"]} (байт {r["mode_raw"]}) — сопоставь с TAKEOFF/GLIDE/FLY на живом самокате')
    print(f'пробег (сырой):  {r["distance_raw"]} — коэффициент уточни по docs/bird-air-protocol.md')
    return 0


if __name__ == '__main__':
    sys.exit(main())
