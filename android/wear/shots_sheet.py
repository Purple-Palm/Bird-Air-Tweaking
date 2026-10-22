#!/usr/bin/env python3
"""Contact sheets of the dial faces' shots (FaceShots: ./gradlew :wear:testDebugUnitTest -Pshots).

Reads android/wear/build/shots/faces/<face>/<scenario>-<ms>.png and writes next to them:
  <face>.png      one face: a row per scenario, a column per frame (ms after the change);
  <scenario>.png  one scenario: a row per face, the same columns (--by scenario);
  index.html      every sheet, no dependencies (it works without Pillow too).

    python3 android/wear/shots_sheet.py                 # every face
    python3 android/wear/shots_sheet.py --face stack    # one
    python3 android/wear/shots_sheet.py --by scenario   # faces side by side, per scenario
    python3 android/wear/shots_sheet.py --size 160      # cell size in px (240 by default)
"""
import argparse
import html
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FACES = ['stack', 'takeover', 'grow', 'ring', 'classic']
SCENARIOS = ['norm', 'warm', 'amber', 'critical', 'bms', 'many', 'limiter', 'overspeed', 'bulb', 'ambient', 'charging']
SHOT = re.compile(r'^([a-z]+)-(\d+)\.png$')


def scan(root):
    """{face: {scenario: {ms: path}}} of what is there."""
    out = {}
    for face in sorted(os.listdir(root)) if os.path.isdir(root) else []:
        d = os.path.join(root, face)
        # <face>-replay/: the faces' own ride shots (TakeoverReplayShots …), not scenarios
        if not os.path.isdir(d) or face.endswith('-replay'):
            continue
        for name in os.listdir(d):
            m = SHOT.match(name)
            if m:
                out.setdefault(face, {}).setdefault(m.group(1), {})[int(m.group(2))] = os.path.join(d, name)
    return out


def ordered(keys, order):
    return [k for k in order if k in keys] + sorted(k for k in keys if k not in order)


def sheet(rows, cols, cell, title, path):
    """rows: [(label, {col: png path})]; a PNG with Pillow, else None."""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        return None
    try:
        font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 14)
    except OSError:
        font = ImageFont.load_default()
    left, top, gap = 96, 44, 6
    w = left + len(cols) * (cell + gap)
    h = top + len(rows) * (cell + gap)
    img = Image.new('RGB', (w, h), (20, 22, 21))
    dr = ImageDraw.Draw(img)
    dr.text((8, 6), title, fill=(232, 238, 236), font=font)
    for j, c in enumerate(cols):
        dr.text((left + j * (cell + gap) + 4, 24), '%d ms' % c, fill=(138, 147, 143), font=font)
    for i, (label, shots) in enumerate(rows):
        y = top + i * (cell + gap)
        dr.text((8, y + cell // 2 - 7), label, fill=(232, 238, 236), font=font)
        for j, c in enumerate(cols):
            p = shots.get(c)
            if p:
                img.paste(Image.open(p).convert('RGB').resize((cell, cell), Image.LANCZOS), (left + j * (cell + gap), y))
    img.save(path)
    return path


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--dir', default=os.path.join(HERE, 'build', 'shots', 'faces'), help='the FaceShots folder')
    ap.add_argument('--face', action='append', help='only this face (repeatable)')
    ap.add_argument('--by', choices=['face', 'scenario'], default='face')
    ap.add_argument('--size', type=int, default=240)
    a = ap.parse_args(argv)
    shots = scan(a.dir)
    if not shots:
        print('no shots in %s: ./gradlew :wear:testDebugUnitTest -Pshots (-PshotFace=stack)' % a.dir, file=sys.stderr)
        return 1
    faces = ordered([f for f in shots if not a.face or f in a.face], FACES)
    scenarios = ordered({s for f in faces for s in shots[f]}, SCENARIOS)
    cols = sorted({ms for f in faces for s in shots[f].values() for ms in s})
    made = []
    if a.by == 'face':
        for f in faces:
            rows = [(s, shots[f].get(s, {})) for s in scenarios]
            made.append((f, rows, sheet(rows, cols, a.size, 'face: ' + f, os.path.join(a.dir, f + '.png'))))
    else:
        for s in scenarios:
            rows = [(f, shots[f].get(s, {})) for f in faces]
            made.append((s, rows, sheet(rows, cols, a.size, 'scenario: ' + s, os.path.join(a.dir, s + '.png'))))
    # index.html: the sheets, or the frames themselves without Pillow
    parts = ['<!doctype html><meta charset="utf-8"><title>Циферблаты</title>',
             '<style>body{background:#000;color:#e8eeec;font:14px sans-serif;margin:16px}img{width:%dpx;height:%dpx;'
             'border-radius:50%%;margin:2px}td{vertical-align:middle;padding:0 6px}h2{font-weight:500}</style>' % (a.size // 2, a.size // 2)]
    for name, rows, png in made:
        parts.append('<h2>%s</h2>' % html.escape(name))
        if png:
            parts.append('<p><a href="%s">%s</a></p>' % (os.path.basename(png), os.path.basename(png)))
        parts.append('<table><tr><td></td>' + ''.join('<td>%d ms</td>' % c for c in cols) + '</tr>')
        for label, frames in rows:
            cells = ''.join('<td>%s</td>' % ('<img src="%s">' % html.escape(os.path.relpath(frames[c], a.dir)) if c in frames else '') for c in cols)
            parts.append('<tr><td>%s</td>%s</tr>' % (html.escape(label), cells))
        parts.append('</table>')
    index = os.path.join(a.dir, 'index.html')
    with open(index, 'w', encoding='utf-8') as fh:
        fh.write('\n'.join(parts))
    for name, _, png in made:
        print(png or '(Pillow not installed: see index.html)')
    print(index)
    return 0


if __name__ == '__main__':
    sys.exit(main())
