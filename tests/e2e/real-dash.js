// Real FFE1 traffic from the owner's scooter (logs/20261014-174148-log.json) for the fake dashboard
// in fake-bluetooth.js, plus the repository decoder and a frame builder.
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const LOG_PATH = path.join(ROOT, 'logs', '20261014-174148-log.json');
const LOG = JSON.parse(fs.readFileSync(LOG_PATH, 'utf8'));
const V3 = JSON.parse(fs.readFileSync(path.join(ROOT, 'decoders', 'current.json'), 'utf8'));
const FFE1 = '0000ffe1-0000-1000-8000-00805f9b34fb';
const text = hex => Buffer.from(hex.replace(/ /g, ''), 'hex').toString('latin1');
const hexOf = s => Array.from(Buffer.from(s, 'latin1'), b => b.toString(16).padStart(2, '0')).join(' ').toUpperCase();

/* For each command the Bird app page sent in the log: the notifications that followed it until the next
   command, split into the reply (up to the echo or answer) and the stream after it. */
function realDash(extra = {}) {
  const es = LOG.entries.filter(e => e.u === FFE1 && (e.k === 'tx' || e.k === 'rx'));
  const after = {};
  es.forEach((e, i) => {
    if (e.k !== 'tx') return;
    const cmd = text(e.h), rx = [];
    for (let j = i + 1; j < es.length && es[j].k !== 'tx'; j++) rx.push(es[j].h);
    const ans = cmd.startsWith('+') ? cmd.slice(0, cmd.indexOf('=') + 1) : 'OK';
    const k = rx.findIndex(h => text(h).includes(ans));
    after[cmd] = { reply: rx.slice(0, k + 1), loop: rx.slice(k + 1) };
  });
  return { after, every: 15, ...extra };
}
/* The notification recorded at index i of the log. */
const chunk = i => LOG.entries[i].h;

/* A complete controller frame: 3A addr cmd len data sum16le 0D 0A. badSum: spoil the checksum. */
function frame(addr, cmd, data, { badSum = false } = {}) {
  let s = addr + cmd + data.length;
  for (const x of data) s += x;
  s = (s + (badSum ? 1 : 0)) & 0xffff;
  return [0x3a, addr, cmd, data.length, ...data, s & 255, s >> 8, 0x0d, 0x0a];
}
const hx = bytes => bytes.map(x => x.toString(16).padStart(2, '0')).join(' ').toUpperCase();
/* Data of the real 3A 18 20 10 frame with another voltage (mV). */
const voltData = mv => [0x03, 0x00, 0x00, 0x18, 0x18, mv & 255, mv >> 8, 0xD2, 0xFF, 0xD7, 0x0C, 0x0B, 0x11, 0x4B, 0x69, 0x00];

/* localStorage that makes v3 the active repository decoder (no update banner). */
const V3_STORAGE = { 'bal.decoder.repo': V3, 'bal.decoder.active': 'repo' };

module.exports = { LOG, LOG_PATH, V3, V3_STORAGE, FFE1, realDash, chunk, frame, hx, hexOf, voltData };
