#!/usr/bin/env python3
"""Fail if any file in the repository contains a GitHub token prefix.

    python3 tools/check_secrets.py            # every tracked file (CI)
    python3 tools/check_secrets.py --staged   # what is about to be committed (pre-commit hook)
    python3 tools/check_secrets.py PATH...    # these files or folders

.gz files are decompressed before scanning, so a token cannot hide inside
an uploaded .json.gz log.
"""
import argparse
import gzip
import os
import subprocess
import sys

# Built by concatenation so this file does not match itself.
PREFIXES = ('github' + '_pat_', 'gh' + 'p_')
SKIP_DIRS = {'.git', 'node_modules', 'test-results', 'playwright-report', '__pycache__', '_site'}


def git(*args):
    return subprocess.run(['git', *args], check=True, capture_output=True).stdout


def tracked_files():
    try:
        out = git('ls-files', '-z')
    except (OSError, subprocess.CalledProcessError):
        return None
    return [p for p in out.decode('utf-8', 'surrogateescape').split('\0') if p]


def staged_blobs():
    out = git('diff', '--cached', '--name-only', '--diff-filter=ACMR', '-z')
    for path in [p for p in out.decode('utf-8', 'surrogateescape').split('\0') if p]:
        yield path, git('show', ':' + path)


def walk(paths):
    for root in paths:
        if os.path.isfile(root):
            yield root
            continue
        for d, dirs, files in os.walk(root):
            dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
            for f in files:
                yield os.path.join(d, f)


def read(path):
    try:
        with open(path, 'rb') as fh:
            return fh.read()
    except OSError:
        return b''


def scan(path, data):
    if path.endswith('.gz'):
        try:
            data = gzip.decompress(data)
        except (OSError, EOFError):
            pass
    hits = []
    for no, line in enumerate(data.split(b'\n'), 1):
        for p in PREFIXES:
            if p.encode() in line:
                hits.append(f'{path}:{no}: {p}…')
    return hits


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('--staged', action='store_true', help='scan the git index instead of the working tree')
    ap.add_argument('paths', nargs='*')
    a = ap.parse_args(argv)
    if a.staged:
        items = staged_blobs()
    else:
        files = list(walk(a.paths)) if a.paths else (tracked_files() or list(walk(['.'])))
        items = ((p, read(p)) for p in files)
    hits = [h for path, data in items for h in scan(path, data)]
    if hits:
        print('Найдены строки, похожие на токен GitHub. Убери их и отзови токен на github.com/settings/tokens:', file=sys.stderr)
        for h in hits:
            print('  ' + h, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
