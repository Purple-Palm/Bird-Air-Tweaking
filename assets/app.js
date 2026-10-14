'use strict';
(() => {
/* ============================================================
   helpers
   ============================================================ */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k === 'value') el.value = v;
    else if (k === 'checked') el.checked = !!v;
    else if (k === 'selected') el.selected = !!v;
    else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v);
    else if (k === 'dataset') Object.assign(el.dataset, v);
    else if (v === true) el.setAttribute(k, '');
    else el.setAttribute(k, String(v));
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return el;
}
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
  del(k) { try { localStorage.removeItem(k); } catch { /* storage unavailable */ } },
};
const NF = {};
function nf(v, d = 1) {
  if (v == null || !isFinite(v)) return '—';
  const f = NF[d] || (NF[d] = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: d, maximumFractionDigits: d }));
  return f.format(v);
}
function tfmt(ms) {
  const s = Math.max(0, ms) / 1000;
  const m = Math.floor(s / 60);
  const ss = (s - m * 60).toFixed(1).padStart(4, '0');
  return String(m).padStart(2, '0') + ':' + ss;
}
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const hex2 = b => b.toString(16).padStart(2, '0');
function bytesToHex(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i++) s += (i ? ' ' : '') + hex2(u8[i]);
  return s.toUpperCase();
}
function hexToBytes(str) {
  const clean = String(str || '').replace(/0x/gi, '').replace(/[^0-9a-fA-F]/g, '');
  if (clean.length % 2) return null;
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16);
  return out;
}
function dvToBytes(dv) { return new Uint8Array(dv.buffer, dv.byteOffset, dv.byteLength).slice(); }
/* "5A A5 ?? 25" -> [0x5a,0xa5,null,0x25]; '' -> []; invalid -> undefined */
function parsePattern(str) {
  const toks = String(str || '').trim().split(/[\s,]+/).filter(Boolean);
  const out = [];
  for (const t of toks) {
    if (t === '??' || t === '?' || t === '**' || t === 'xx' || t === 'XX') { out.push(null); continue; }
    const m = t.replace(/^0x/i, '');
    if (!/^[0-9a-f]{1,2}$/i.test(m)) return undefined;
    out.push(parseInt(m, 16));
  }
  return out;
}
function matchPrefix(b, pat) {
  if (!pat || !pat.length) return true;
  if (!b || b.length < pat.length) return false;
  for (let i = 0; i < pat.length; i++) if (pat[i] != null && b[i] !== pat[i]) return false;
  return true;
}
function printable(b) {
  if (!b || !b.length) return null;
  for (const x of b) if (!((x >= 0x20 && x <= 0x7e) || x === 10 || x === 13 || x === 0)) return null;
  return String.fromCharCode(...b).replace(/\0+$/, '');
}
function num(x, d) { const v = parseFloat(String(x ?? '').replace(',', '.')); return isFinite(v) ? v : d; }

/* ============================================================
   UUIDs and what we know about them
   ============================================================ */
const BASE = '-0000-1000-8000-00805f9b34fb';
function normUuid(s) {
  s = String(s ?? '').trim().toLowerCase();
  if (!s) return null;
  if (/^0x[0-9a-f]{4}$/.test(s)) s = s.slice(2);
  if (/^[0-9a-f]{4}$/.test(s)) return '0000' + s + BASE;
  if (/^[0-9a-f]{8}$/.test(s)) return s + BASE;
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)) return s;
  return null;
}
function shortUuid(u) {
  if (u && u.startsWith('0000') && u.endsWith(BASE)) return '0x' + u.slice(4, 8).toUpperCase();
  return u || '';
}
const TELINK_SPP = '00010203-0405-0607-0809-0a0b0c0d1910';
const TELINK_OTA_SVC = '00010203-0405-0607-0809-0a0b0c0d1912';
const TELINK_OTA_CHR = '00010203-0405-0607-0809-0a0b0c0d2b12';
const NUS = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const DEMO_SVC = normUuid('b1a0');
const DEMO_CHR = normUuid('b1a1');
const SVC_NAMES = {
  [normUuid('1800')]: 'Generic Access', [normUuid('1801')]: 'Generic Attribute',
  [normUuid('180a')]: 'Сведения об устройстве', [normUuid('180f')]: 'Батарея (стандартный сервис)',
  [normUuid('ffe0')]: 'Прозрачный UART (тип HM-10)', [normUuid('fff0')]: 'Вендорский FFF0',
  [normUuid('ffd0')]: 'Вендорский FFD0', [normUuid('ffc0')]: 'Вендорский FFC0', [normUuid('ff00')]: 'Вендорский FF00',
  [normUuid('fee0')]: 'Вендорский FEE0', [normUuid('fee7')]: 'Вендорский FEE7', [normUuid('fe95')]: 'Xiaomi',
  [normUuid('ae00')]: 'Вендорский AE00', [normUuid('ae30')]: 'Вендорский AE30',
  [NUS]: 'Nordic UART', [TELINK_SPP]: 'Telink SPP (UART)', [TELINK_OTA_SVC]: 'Telink OTA — обновление прошивки',
  '49535343-fe7d-4ae5-8fa9-9fafd205e455': 'Microchip UART', '0783b03e-8535-b5a0-7140-a304d2495cb7': 'Dialog SPS (UART)',
  [DEMO_SVC]: 'Демо: UART самоката',
};
const CHR_NAMES = {
  [normUuid('2a00')]: 'Имя устройства', [normUuid('2a01')]: 'Внешний вид', [normUuid('2a04')]: 'Параметры соединения',
  [normUuid('2a19')]: 'Уровень батареи', [normUuid('2a23')]: 'System ID', [normUuid('2a24')]: 'Модель',
  [normUuid('2a26')]: 'Версия прошивки', [normUuid('2a27')]: 'Версия железа', [normUuid('2a28')]: 'Версия ПО',
  [normUuid('2a29')]: 'Производитель', [normUuid('2a50')]: 'PnP ID', [normUuid('ffe1')]: 'UART данные',
  '6e400002-b5a3-f393-e0a9-e50e24dcca9e': 'UART: запись в устройство', '6e400003-b5a3-f393-e0a9-e50e24dcca9e': 'UART: данные от устройства',
  [TELINK_OTA_CHR]: 'OTA — обновление прошивки', [DEMO_CHR]: 'Демо: поток телеметрии',
};
const DEFAULT_SERVICES = ['1800', '1801', '180a', '180f', 'ffe0', 'fff0', 'ffd0', 'ffc0', 'ff00', 'fee0', 'fee7', 'fe95', 'ae00', 'ae30']
  .map(normUuid).concat([NUS, TELINK_SPP, TELINK_OTA_SVC, '49535343-fe7d-4ae5-8fa9-9fafd205e455', '0783b03e-8535-b5a0-7140-a304d2495cb7']);
function isDanger(svc, chr) {
  return chr === TELINK_OTA_CHR || svc === TELINK_OTA_SVC || svc === normUuid('fe59') || svc === normUuid('fef5')
    || String(chr).startsWith('8ec9') || String(svc).startsWith('8ec9') || String(svc).startsWith('00001530-1212');
}

/* ============================================================
   decoder model
   ============================================================ */
const TYPE_LEN = { u8: 1, s8: 1, u16le: 2, s16le: 2, u16be: 2, s16be: 2, u24le: 3, u32le: 4, s32le: 4, u32be: 4, bit0: 1, bit1: 1, bit2: 1, bit3: 1, bit4: 1, bit5: 1, bit6: 1, bit7: 1 };
function readNum(b, off, type) {
  const need = TYPE_LEN[type];
  if (!b || !need || off < 0 || off + need > b.length) return null;
  switch (type) {
    case 'u8': return b[off];
    case 's8': return (b[off] << 24) >> 24;
    case 'u16le': return b[off] | (b[off + 1] << 8);
    case 's16le': return ((b[off] | (b[off + 1] << 8)) << 16) >> 16;
    case 'u16be': return (b[off] << 8) | b[off + 1];
    case 's16be': return (((b[off] << 8) | b[off + 1]) << 16) >> 16;
    case 'u24le': return b[off] | (b[off + 1] << 8) | (b[off + 2] << 16);
    case 'u32le': return (b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24)) >>> 0;
    case 's32le': return b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24);
    case 'u32be': return ((b[off] << 24) | (b[off + 1] << 16) | (b[off + 2] << 8) | b[off + 3]) >>> 0;
    default: if (type.startsWith('bit')) return (b[off] >> Number(type.slice(3))) & 1;
  }
  return null;
}
const METRICS = {
  battery_pct: 'Заряд, %', voltage: 'Напряжение, В', current: 'Ток, А (+ из батареи)', power: 'Мощность, Вт',
  speed_kmh: 'Скорость, км/ч', speed_mph: 'Скорость, mph', temp: 'Температура, °C', mode: 'Режим (1, 2, 3)',
  odometer_km: 'Пробег, км', odometer_mi: 'Пробег, mi', error: 'Код ошибки', custom: 'Своё поле',
};
const TYPE_LABELS = { u8: 'u8', s8: 's8', u16le: 'u16le', s16le: 's16le', u16be: 'u16be', s16be: 's16be', u24le: 'u24le', u32le: 'u32le', s32le: 's32le', u32be: 'u32be', bit0: 'бит 0', bit1: 'бит 1', bit2: 'бит 2', bit3: 'бит 3', bit4: 'бит 4', bit5: 'бит 5', bit6: 'бит 6', bit7: 'бит 7' };
let fieldSeq = 1;
function mkField(o = {}) {
  return { id: 'f' + (fieldSeq++) + Math.random().toString(36).slice(2, 5), key: 'custom', name: '', uuid: '', match: '', offset: 0, type: 'u8', scale: 1, add: 0, ...o };
}
function normalizeDecoder(d) {
  if (!d || !Array.isArray(d.fields)) return null;
  return { json: !!d.json, fields: d.fields.filter(f => f && typeof f === 'object').map(f => mkField({
    key: METRICS[f.key] ? f.key : 'custom', name: String(f.name || ''), uuid: f.uuid ? (normUuid(f.uuid) || '') : '',
    match: String(f.match || ''), offset: Math.max(0, parseInt(f.offset, 10) || 0), type: TYPE_LEN[f.type] ? f.type : 'u8',
    scale: num(f.scale, 1), add: num(f.add, 0),
  })) };
}
function decoderToJson(d) {
  return JSON.stringify({ json: d.json, fields: d.fields.map(f => ({ key: f.key, name: f.name || undefined, uuid: f.uuid || undefined, match: f.match || undefined, offset: f.offset, type: f.type, scale: f.scale, add: f.add || undefined })) }, null, 1);
}
const DEMO_DECODER = () => ({ json: false, fields: [
  mkField({ key: 'battery_pct', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 4, type: 'u8' }),
  mkField({ key: 'voltage', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 5, type: 'u16le', scale: 0.01 }),
  mkField({ key: 'current', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 7, type: 's16le', scale: 0.01 }),
  mkField({ key: 'speed_kmh', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 9, type: 'u16le', scale: 0.1 }),
  mkField({ key: 'temp', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 11, type: 's8' }),
  mkField({ key: 'mode', uuid: DEMO_CHR, match: '5A A5 ?? 25', offset: 12, type: 'u8' }),
  mkField({ key: 'odometer_km', uuid: DEMO_CHR, match: '5A A5 ?? 31', offset: 4, type: 'u32le', scale: 0.01 }),
] });
const MODES = { 1: ['TAKEOFF', 'до 9 mph'], 2: ['GLIDE', 'до 12,5 mph'], 3: ['FLY', 'до 16 mph'] };
const PACK_AH = 5.2;

/* ============================================================
   state
   ============================================================ */
const settings = Object.assign({ extraUuids: '', namePrefix: '', autoSubscribe: true, autoReconnect: true, wakeLock: true, units: 'mph', allowWrite: false, collapse: true },
  store.get('bal.settings', {}));
/* Two decoder slots: «мой» (bal.decoder, what the user edits) and «из репозитория» (bal.decoder.repo,
   the last decoders/current.json the user applied). bal.decoder.active says which one runs. */
const decSrc = { active: store.get('bal.decoder.active', 'mine'), repo: store.get('bal.decoder.repo', null), remote: null, forked: false };
const emptyDecoder = () => ({ json: false, fields: [] });
const mineDecoder = () => normalizeDecoder(store.get('bal.decoder', null)) || emptyDecoder();
const repoDecoder = () => (decSrc.repo && normalizeDecoder(decSrc.repo)) || null;
if (decSrc.active === 'repo' && !repoDecoder()) decSrc.active = 'mine';
let decoder = (decSrc.active === 'repo' && repoDecoder()) || mineDecoder();
const state = {
  session: null, device: null, gatt: [], chars: new Map(), charStats: new Map(),
  conn: 'idle', manual: false, reconnectN: 0, reconnectTimer: null, wakeLock: null,
  demo: null, savedDecoder: null, tel: null, series: [], rxTimes: [],
  win: '60', hoverT: null, showTable: false, tab: 'dash',
  an: { key: '', pattern: '', off: null, type: 'u8', mk: '', win: 3 },
  writeTimer: null, geo: null, rollMax: 300,
};
function saveSettings() { store.set('bal.settings', settings); }
/* Every call is a user edit. Editing the repository decoder turns it into «мой»; the previous «мой»
   goes to bal.decoder.backup so nothing is lost silently. */
function saveDecoder() {
  if (state.demo) return;
  if (decSrc.active === 'repo') {
    const old = store.get('bal.decoder', null), oldN = normalizeDecoder(old), rep = repoDecoder();
    if (oldN && oldN.fields.length && (!rep || decoderToJson(oldN) !== decoderToJson(rep))) store.set('bal.decoder.backup', { savedAt: new Date().toISOString(), decoder: old });
    setActiveSource('mine');
    decSrc.forked = true;
  }
  store.set('bal.decoder', JSON.parse(decoderToJson(decoder)));
  renderDecSource();
}

/* ============================================================
   session & telemetry
   ============================================================ */
const MAX_ENTRIES = 80000;
function newTel() { return { vals: new Map(), whOut: 0, whIn: 0, lastPT: null, powerTouched: false, jsonBuf: new Map() }; }
function newSession(o) {
  state.session = { startedAt: Date.now(), t0: performance.now(), entries: [], exported: false, trimmed: false, live: false, offline: false, demo: false, device: null, ...o };
  state.tel = newTel(); state.series = []; state.charStats = new Map(); state.rxTimes = []; state.hoverT = null; state.rollMax = 300;
  state.an.off = null;
}
function nowT() {
  const S = state.session;
  if (!S) return 0;
  if (S.offline) { const e = S.entries[S.entries.length - 1]; return e ? e.t : 0; }
  return performance.now() - S.t0;
}
function setTel(key, v, t) {
  if (!isFinite(v)) return;
  state.tel.vals.set(key, { v, t });
  if (key === 'voltage' || key === 'current' || key === 'power') state.tel.powerTouched = true;
}
function telV(key) { const x = state.tel && state.tel.vals.get(key); return x ? x.v : null; }
function telStale(key) {
  const S = state.session, x = state.tel && state.tel.vals.get(key);
  if (!x) return true;
  if (!S || S.offline) return false;
  return nowT() - x.t > 5000;
}
function powerNow() {
  const p = telV('power');
  if (p != null) return p;
  const v = telV('voltage'), i = telV('current');
  return v != null && i != null ? v * i : null;
}
function socFromVoltage(pack) {
  const cell = pack / 10;
  const T = [[3.0, 0], [3.4, 5], [3.55, 10], [3.65, 20], [3.7, 30], [3.75, 40], [3.8, 50], [3.85, 60], [3.92, 70], [4.0, 80], [4.1, 90], [4.2, 100]];
  if (cell <= T[0][0]) return 0;
  if (cell >= 4.2) return 100;
  for (let k = 1; k < T.length; k++) if (cell <= T[k][0]) { const [v0, s0] = T[k - 1], [v1, s1] = T[k]; return s0 + (s1 - s0) * (cell - v0) / (v1 - v0); }
  return 100;
}
function socNow() {
  const s = telV('battery_pct');
  if (s != null) return { v: s, est: false };
  const v = telV('voltage');
  return v == null ? null : { v: socFromVoltage(v), est: true };
}
function flowState() {
  const i = telV('current'), spd = telV('speed_kmh'), p = powerNow();
  if (i == null && p == null && spd == null) return { id: 'none', label: 'Нет данных', tone: 'neutral' };
  const into = i != null ? i < -0.15 : (p != null && p < -5);
  if (into) {
    if (spd != null && spd > 1) return { id: 'regen', label: 'Рекуперация', tone: 'flow' };
    if (spd != null) return { id: 'charge', label: 'Зарядка', tone: 'flow' };
    return { id: 'in', label: 'В батарею', tone: 'flow' };
  }
  const moving = (spd != null && spd > 1) || (i != null && i > 0.6) || (p != null && p > 20);
  return moving ? { id: 'drive', label: 'Тяга', tone: 'draw' } : { id: 'idle', label: 'Стоит', tone: 'neutral' };
}

const JSON_KEYS = { v: 'voltage', voltage: 'voltage', i: 'current', a: 'current', current: 'current', p: 'power', w: 'power', power: 'power', soc: 'battery_pct', bat: 'battery_pct', battery: 'battery_pct', spd: 'speed_kmh', speed: 'speed_kmh', kmh: 'speed_kmh', mph: 'speed_mph', t: 'temp', temp: 'temp', mode: 'mode', odo: 'odometer_km', err: 'error', error: 'error' };
let utf8;
try { utf8 = new TextDecoder('utf-8', { fatal: true }); } catch { utf8 = null; }
function applyJsonLine(line, t) {
  let o;
  try { o = JSON.parse(line); } catch { return false; }
  if (!o || typeof o !== 'object') return false;
  let any = false;
  for (const [k, val] of Object.entries(o)) {
    const m = JSON_KEYS[k.toLowerCase()], n = Number(val);
    if (!m || !isFinite(n)) continue;
    if (m === 'speed_mph') setTel('speed_kmh', n * 1.609344, t); else setTel(m, n, t);
    any = true;
  }
  return any;
}
function decodeJson(e) {
  if (!utf8 || !e.b) return false;
  let txt;
  try { txt = utf8.decode(e.b); } catch { return false; }
  const key = e.s + '|' + e.u;
  let buf = (state.tel.jsonBuf.get(key) || '') + txt, touched = false, nl;
  while ((nl = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, nl).trim();
    buf = buf.slice(nl + 1);
    if (line.startsWith('{')) touched = applyJsonLine(line, e.t) || touched;
  }
  const tb = buf.trim();
  if (tb.startsWith('{') && tb.endsWith('}') && applyJsonLine(tb, e.t)) { touched = true; buf = ''; }
  if (buf.length > 4000) buf = '';
  state.tel.jsonBuf.set(key, buf);
  return touched;
}
function compileField(f) {
  const pat = parsePattern(f.match);
  f._bad = pat === undefined;
  f._pat = pat || [];
}
function decodeEntry(e) {
  let touched = false;
  if (decoder.json) touched = decodeJson(e) || touched;
  for (const f of decoder.fields) {
    if (f._pat === undefined) compileField(f);
    if (f._bad) continue;
    if (f.uuid && f.uuid !== e.u) continue;
    if (!matchPrefix(e.b, f._pat)) continue;
    const raw = readNum(e.b, f.offset | 0, f.type);
    if (raw == null) continue;
    const v = raw * num(f.scale, 1) + num(f.add, 0);
    f._last = v;
    if (f.key === 'speed_mph') setTel('speed_kmh', v * 1.609344, e.t);
    else if (f.key === 'odometer_mi') setTel('odometer_km', v * 1.609344, e.t);
    else if (f.key === 'custom') setTel('c:' + f.id, v, e.t);
    else setTel(f.key, v, e.t);
    touched = true;
  }
  if (touched) afterTelemetry(e.t);
}
function afterTelemetry(t) {
  const tel = state.tel;
  if (!tel.powerTouched) return;
  tel.powerTouched = false;
  const p = powerNow();
  if (p == null) return;
  const pt = { t, p, i: telV('current'), v: telV('voltage'), s: telV('speed_kmh') };
  const last = state.series[state.series.length - 1];
  if (last && t - last.t < 40) Object.assign(last, pt); else state.series.push(pt);
  if (tel.lastPT != null) {
    const dt = clamp((t - tel.lastPT) / 1000, 0, 2);
    if (p >= 0) tel.whOut += p * dt / 3600; else tel.whIn += -p * dt / 3600;
  }
  tel.lastPT = t;
  if (!state.session.offline && state.series.length > 40000) state.series.splice(0, state.series.length - 40000);
}
function pushEntry(e) {
  const S = state.session;
  if (!S) return;
  if (e.t == null) e.t = Math.round(nowT());
  if (e.b && !e.h) e.h = bytesToHex(e.b);
  if (e.h && !e.b) e.b = hexToBytes(e.h) || new Uint8Array(0);
  S.entries.push(e);
  if (S.entries.length > MAX_ENTRIES) { S.entries.splice(0, S.entries.length - MAX_ENTRIES); S.trimmed = true; }
  if (e.k === 'rx' || e.k === 'rd') {
    const key = e.s + '|' + e.u;
    const st = state.charStats.get(key) || { s: e.s, u: e.u, count: 0 };
    st.count++; st.last = e;
    state.charStats.set(key, st);
    const info = state.chars.get(key);
    if (info) { info.last = e; info.count = st.count; }
    decodeEntry(e);
    if (!S.offline) state.rxTimes.push(performance.now());
  }
  requestRender();
}
function sys(text, err) { pushEntry({ k: 'sys', n: text, err: !!err }); }
function addMarker(label) {
  if (!state.session) { flash($('#logMsg'), 'Сначала подключи самокат или включи демо.', true); return; }
  pushEntry({ k: 'mk', n: String(label).slice(0, 60) });
}
function rebuildTelemetry() {
  const S = state.session;
  if (!S) return;
  state.tel = newTel(); state.series = []; state.rollMax = 300;
  for (const f of decoder.fields) { f._pat = undefined; f._last = undefined; }
  for (const e of S.entries) if (e.k === 'rx' || e.k === 'rd') decodeEntry(e);
  requestRender(true);
}

/* ============================================================
   connection
   ============================================================ */
const CONN = {
  idle: ['Не подключено', 'neutral', '○'], connecting: ['Подключаюсь…', 'warn', '◌'], connected: ['На связи', 'good', '●'],
  reconnecting: ['Переподключаюсь…', 'warn', '◌'], demo: ['Демо-режим', 'neutral', '◇'], offline: ['Лог из файла', 'neutral', '▤'],
  unsupported: ['Bluetooth недоступен', 'crit', '⚠'], error: ['Ошибка', 'crit', '⚠'],
};
function setConn(id, detail) {
  state.conn = id;
  const [label, tone, icon] = CONN[id];
  const pill = $('#connPill');
  pill.dataset.tone = tone;
  $('.ico', pill).textContent = icon;
  $('.txt', pill).textContent = detail ? label + ' · ' + detail : label;
  const busy = id === 'connected' || id === 'connecting' || id === 'reconnecting';
  $('#btnConnect').hidden = busy;
  $('#btnDisconnect').hidden = !busy;
  $('#btnDisconnect').textContent = id === 'reconnecting' ? 'Не переподключаться' : 'Отключить';
  renderBanners();
  renderWriteCard();
}
function bleSupported() { return !!(navigator.bluetooth && navigator.bluetooth.requestDevice) && window.isSecureContext; }
function errText(e) {
  const m = String(e && e.message || e || '');
  if (/authenticat|encrypt|insufficient/i.test(m)) return 'нужна привязка (pairing) — подтверди запрос на телефоне';
  switch (e && e.name) {
    case 'NotFoundError': return 'не найдено';
    case 'SecurityError': return 'браузер запретил доступ (нужен https, или UUID в чёрном списке Chrome)';
    case 'NetworkError': return 'связь не установилась — самокат далеко, выключен или занят приложением Bird';
    case 'NotSupportedError': return 'эта характеристика так не умеет';
    case 'InvalidStateError': return 'нет соединения';
    case 'NotAllowedError': return 'нет разрешения — проверь «Устройства поблизости» для Chrome';
    default: return m || 'неизвестная ошибка';
  }
}
function serviceList() {
  const extra = String(settings.extraUuids || '').split(/\n+/).map(normUuid).filter(Boolean);
  return Array.from(new Set(DEFAULT_SERVICES.concat(extra)));
}
async function connect() {
  if (!bleSupported()) { showTab('guide'); setConn('unsupported'); return; }
  const optionalServices = serviceList();
  const opts = settings.namePrefix
    ? { filters: [{ namePrefix: settings.namePrefix }], optionalServices }
    : { acceptAllDevices: true, optionalServices };
  let dev;
  try { dev = await navigator.bluetooth.requestDevice(opts); }
  catch (e) {
    if (e && e.name === 'NotFoundError') return; /* chooser cancelled */
    setConn('error', errText(e));
    return;
  }
  stopDemo(true);
  stopWriteRepeat();
  if (state.device && state.device !== dev) { try { state.manual = true; state.device.gatt.disconnect(); } catch { /* ignore */ } }
  state.device = dev;
  state.manual = false;
  state.reconnectN = 0;
  newSession({ live: true, device: { name: dev.name || '(без имени)', id: dev.id } });
  state.gatt = []; state.chars = new Map();
  dev.addEventListener('gattserverdisconnected', onDisconnected);
  sys('Выбрано устройство: ' + (dev.name || '(без имени)'));
  try { await openGatt(); }
  catch (e) { sys('Не удалось подключиться: ' + errText(e), true); scheduleReconnect(); }
}
async function openGatt() {
  const dev = state.device;
  setConn('connecting', dev.name || '');
  await dev.gatt.connect();
  await discover();
  setConn('connected', dev.name || '(без имени)');
  sys('Подключено');
  acquireWakeLock();
}
async function discover() {
  const server = state.device.gatt;
  let services = [];
  try { services = await server.getPrimaryServices(); }
  catch (e) { sys('Список сервисов не получен: ' + errText(e), true); }
  const prev = state.chars;
  state.gatt = []; state.chars = new Map();
  for (const s of services) {
    const entry = { uuid: s.uuid, chars: [] };
    let chars = [];
    try { chars = await s.getCharacteristics(); } catch (e) { sys('Характеристики ' + shortUuid(s.uuid) + ': ' + errText(e), true); }
    for (const c of chars) {
      const key = s.uuid + '|' + c.uuid;
      const p = c.properties;
      const old = prev.get(key);
      const info = {
        key, service: s.uuid, uuid: c.uuid, obj: c, danger: isDanger(s.uuid, c.uuid),
        props: { read: !!p.read, write: !!p.write, wnr: !!p.writeWithoutResponse, notify: !!p.notify, indicate: !!p.indicate },
        subscribed: false, handler: null, last: old ? old.last : null, count: old ? old.count : 0, wanted: old ? old.wanted : null,
      };
      state.chars.set(key, info);
      entry.chars.push(info);
    }
    state.gatt.push(entry);
  }
  sys('Найдено сервисов: ' + state.gatt.length + ', характеристик: ' + state.chars.size);
  if (!state.gatt.length) sys('Сервисов не видно. Добавь UUID из nRF Connect в «Настройки → Свои UUID» и переподключись.', true);
  renderExplorer();
  renderCharSelects();
  for (const info of state.chars.values()) if (info.props.read && !info.danger) await readChar(info, true);
  for (const info of state.chars.values()) {
    if (!(info.props.notify || info.props.indicate) || info.danger) continue;
    const want = info.wanted == null ? settings.autoSubscribe : info.wanted;
    if (want) await subscribe(info, true);
  }
  renderExplorer();
}
async function readChar(info, quiet) {
  if (!info.obj) return;
  try {
    const dv = await info.obj.readValue();
    if (!info.subscribed) pushEntry({ k: 'rd', s: info.service, u: info.uuid, b: dvToBytes(dv) });
  } catch (e) { if (!quiet) sys('Чтение ' + shortUuid(info.uuid) + ': ' + errText(e), true); }
}
async function subscribe(info, quiet) {
  if (!info.obj || info.subscribed || info.danger) return;
  if (!info.handler) info.handler = ev => pushEntry({ k: 'rx', s: info.service, u: info.uuid, b: dvToBytes(ev.target.value) });
  info.obj.addEventListener('characteristicvaluechanged', info.handler);
  try {
    await info.obj.startNotifications();
    info.subscribed = true; info.wanted = true;
    if (!quiet) sys('Подписка: ' + shortUuid(info.uuid));
  } catch (e) {
    info.obj.removeEventListener('characteristicvaluechanged', info.handler);
    sys('Подписка на ' + shortUuid(info.uuid) + ' не удалась: ' + errText(e), true);
  }
  renderExplorerButtons();
}
async function unsubscribe(info) {
  if (!info.obj || !info.subscribed) return;
  try { await info.obj.stopNotifications(); } catch { /* already gone */ }
  info.obj.removeEventListener('characteristicvaluechanged', info.handler);
  info.subscribed = false; info.wanted = false;
  sys('Отписка: ' + shortUuid(info.uuid));
  renderExplorerButtons();
}
function onDisconnected() {
  for (const info of state.chars.values()) {
    if (info.obj && info.handler) info.obj.removeEventListener('characteristicvaluechanged', info.handler);
    info.subscribed = false;
  }
  releaseWakeLock();
  if (state.manual) { setConn('idle'); sys('Отключено'); renderExplorerButtons(); return; }
  sys('Связь потеряна', true);
  renderExplorerButtons();
  scheduleReconnect();
}
function scheduleReconnect() {
  clearTimeout(state.reconnectTimer);
  if (state.manual || !state.device) { setConn('idle'); return; }
  if (!settings.autoReconnect) { setConn('idle', 'связь потеряна'); return; }
  state.reconnectN++;
  const delay = Math.min(10000, 1000 * Math.pow(2, state.reconnectN - 1));
  setConn('reconnecting', 'через ' + Math.round(delay / 1000) + ' с');
  state.reconnectTimer = setTimeout(async () => {
    if (state.manual) return;
    try { await openGatt(); state.reconnectN = 0; }
    catch (e) { sys('Переподключение не удалось: ' + errText(e), true); scheduleReconnect(); }
  }, delay);
}
function disconnect() {
  state.manual = true;
  clearTimeout(state.reconnectTimer);
  stopWriteRepeat();
  if (state.device && state.device.gatt && state.device.gatt.connected) state.device.gatt.disconnect();
  else { setConn('idle'); }
  releaseWakeLock();
}
async function acquireWakeLock() {
  if (!settings.wakeLock || !('wakeLock' in navigator) || state.wakeLock) return;
  try {
    state.wakeLock = await navigator.wakeLock.request('screen');
    state.wakeLock.addEventListener('release', () => { state.wakeLock = null; });
  } catch { state.wakeLock = null; }
}
function releaseWakeLock() { if (state.wakeLock) { try { state.wakeLock.release(); } catch { /* ignore */ } state.wakeLock = null; } }
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && (state.conn === 'connected' || state.conn === 'demo')) acquireWakeLock();
});

/* ============================================================
   demo scooter
   ============================================================ */
function startDemo() {
  if (state.conn === 'connected' || state.conn === 'connecting' || state.conn === 'reconnecting') return;
  stopDemo(true);
  state.savedDecoder = decoder;
  decoder = DEMO_DECODER();
  newSession({ live: true, demo: true, device: { name: 'Демо: Bird Air', id: 'demo' } });
  const info = { key: DEMO_SVC + '|' + DEMO_CHR, service: DEMO_SVC, uuid: DEMO_CHR, obj: null, danger: false, props: { read: false, write: false, wnr: false, notify: true, indicate: false }, subscribed: true, last: null, count: 0 };
  state.gatt = [{ uuid: DEMO_SVC, chars: [info] }];
  state.chars = new Map([[info.key, info]]);
  state.demo = { k: 0, soc: 0.74, odo: 641.27, temp: 27, spd: 0, mode: 3, timer: setInterval(demoTick, 100) };
  sys('Демо: имитация самоката, 10 пакетов в секунду');
  setConn('demo');
  renderExplorer(); renderCharSelects(); renderDecoder();
  state.an.key = info.key; state.an.pattern = '5A A5 ?? 25';
  $('#anPat').value = state.an.pattern;
  acquireWakeLock();
}
function stopDemo(silent) {
  if (!state.demo) return;
  clearInterval(state.demo.timer);
  state.demo = null;
  if (state.savedDecoder) { decoder = state.savedDecoder; state.savedDecoder = null; }
  if (!silent) { setConn('idle'); sys('Демо остановлено'); }
  renderDecoder();
  releaseWakeLock();
}
function demoFrame(cmd, payload) {
  const b = [0x5a, 0xa5, payload.length + 2, cmd, ...payload];
  let x = 0;
  for (let i = 2; i < b.length; i++) x ^= b[i];
  b.push(x);
  return new Uint8Array(b);
}
function demoTick() {
  const d = state.demo;
  if (!d) return;
  const dt = 0.1, sec = (d.k % 1000) / 10;
  d.k++;
  const noise = (a) => (Math.random() - 0.5) * a;
  let target = 0, I = 0.25;
  const mk = (s, label) => { if (Math.abs(sec - s) < 0.05) addMarker(label); };
  mk(6, 'Газ'); mk(30, 'Тормоз'); mk(38, 'Сменил режим'); mk(40, 'Газ'); mk(52, 'Тормоз'); mk(60, 'Зарядка вкл'); mk(80, 'Зарядка выкл'); mk(90, 'Сменил режим');
  if (Math.abs(sec - 38) < 0.05) d.mode = 2;
  if (Math.abs(sec - 90) < 0.05) d.mode = 3;
  if (sec >= 6 && sec < 30) { target = 25.4; I = d.spd < 22 ? 10.5 + noise(1.5) : 6.2 + noise(1.6); }
  else if (sec >= 30 && sec < 34) { target = 4; I = -3.6 + noise(0.6); }
  else if (sec >= 40 && sec < 52) { target = 19.6; I = d.spd < 17 ? 7.5 + noise(1.2) : 4.4 + noise(1.0); }
  else if (sec >= 52 && sec < 55) { target = 0; I = -2.6 + noise(0.5); }
  else if (sec >= 60 && sec < 80) { target = 0; I = -1.95 + noise(0.06); }
  const rate = target > d.spd ? 2.6 : 5.5;
  d.spd += clamp(target - d.spd, -rate * dt, rate * dt);
  if (d.spd < 0.2 && target === 0) d.spd = 0;
  if (sec >= 34 && sec < 40) { d.spd = Math.max(0, d.spd - 6 * dt); I = d.spd > 0.5 ? 0.6 : 0.25; }
  d.soc = clamp(d.soc - (I * dt / 3600 / PACK_AH) * 30, 0.06, 1);
  const ocv = 10 * (3.0 + 1.2 * Math.pow(d.soc, 0.85));
  const V = ocv - I * 0.18 + noise(0.04);
  d.temp += (26 + 2.6 * Math.abs(I) - d.temp) * 0.004;
  d.odo += d.spd * dt / 3600;
  const v16 = Math.round(V * 100), i16 = Math.round(I * 100) & 0xffff, s16 = Math.round(d.spd * 10);
  const f = demoFrame(0x25, [Math.round(d.soc * 100), v16 & 255, v16 >> 8, i16 & 255, i16 >> 8, s16 & 255, s16 >> 8, Math.round(d.temp) & 255, d.mode]);
  pushEntry({ k: 'rx', s: DEMO_SVC, u: DEMO_CHR, b: f });
  if (d.k % 10 === 0) {
    const o = Math.round(d.odo * 100);
    pushEntry({ k: 'rx', s: DEMO_SVC, u: DEMO_CHR, b: demoFrame(0x31, [o & 255, (o >> 8) & 255, (o >> 16) & 255, (o >>> 24) & 255, 0, 0]) });
  }
}

/* ============================================================
   rendering scheduler
   ============================================================ */
let queued = false, lastRender = 0;
const lastPart = { log: 0, an: 0, exp: 0, dec: 0 };
function requestRender(now) {
  if (queued) return;
  queued = true;
  const wait = now ? 0 : Math.max(0, 110 - (performance.now() - lastRender));
  setTimeout(() => requestAnimationFrame(() => { queued = false; lastRender = performance.now(); renderAll(now); }), wait);
}
function due(part, ms, force) {
  const t = performance.now();
  if (!force && t - lastPart[part] < ms) return false;
  lastPart[part] = t;
  return true;
}
function renderAll(force) {
  const cut = performance.now() - 5000;
  while (state.rxTimes.length && state.rxTimes[0] < cut) state.rxTimes.shift();
  if (state.tab === 'dash') { renderDashboard(); drawPowerChart(); if (state.showTable && due('log', 1000, force)) renderChartTable(); }
  if (state.tab === 'data') {
    if (due('exp', 400, force)) refreshExplorerValues();
    if (due('log', 350, force)) renderLog();
    if (due('an', 900, force)) renderAnalyzer();
    renderGhName();
  }
  if (state.tab === 'decoder' && due('dec', 400, force)) renderDecoderValues();
}
setInterval(() => { if (state.session && !state.session.offline) requestRender(); }, 500);
window.addEventListener('resize', () => requestRender(true));

/* ============================================================
   dashboard
   ============================================================ */
function setVal(el, text, unit) {
  el.replaceChildren(document.createTextNode(text));
  if (unit && text !== '—') el.append(h('span', { class: 'u', text: unit }));
}
function renderBanners() {
  const S = state.session;
  $('#demoBanner').hidden = !state.demo;
  const idle = !state.demo && state.conn !== 'connected' && state.conn !== 'connecting' && state.conn !== 'reconnecting';
  $('#idleBanner').hidden = !idle;
  if (idle) {
    $('#idleText').textContent = S && S.entries.length
      ? (S.offline ? 'Показан лог из файла. Подключи самокат или включи демо.' : 'Связи нет — показаны последние полученные значения.')
      : 'Подключи самокат, загрузи записанный лог или включи демо.';
  }
}
function renderDashboard() {
  const mph = settings.units === 'mph';
  /* battery */
  const soc = socNow();
  const bt = $('#tBattery');
  bt.classList.toggle('stale', !soc || telStale(soc && !soc.est ? 'battery_pct' : 'voltage'));
  setVal($('#batVal'), soc ? nf(soc.v, 0) : '—', '%');
  const meter = $('#batMeter');
  meter.firstElementChild.style.width = (soc ? clamp(soc.v, 0, 100) : 0) + '%';
  const tone = !soc ? 'good' : soc.v < 15 ? 'crit' : soc.v < 30 ? 'warn' : 'good';
  meter.dataset.tone = tone;
  $('#batState').textContent = !soc ? '' : tone === 'crit' ? '⚠ почти пусто' : tone === 'warn' ? '▲ мало' : '✓ норма';
  const V = telV('voltage');
  $('#batSub').textContent = soc
    ? (V != null ? nf(V, 1) + ' В' : '') + (soc.est ? ' · оценка по напряжению' : '') + ' · 10S2P, 5,2 А·ч'
    : '36 В · 10S2P · 5,2 А·ч';
  /* power */
  const P = powerNow(), I = telV('current'), fs = flowState();
  const pt = $('#tPower');
  pt.classList.toggle('stale', P == null || (telStale('power') && telStale('current')));
  const ps = $('#pwrState');
  ps.dataset.tone = fs.tone;
  $('.txt', ps).textContent = fs.label;
  setVal($('#pwrVal'), P == null ? '—' : nf(Math.abs(P), 0), 'Вт');
  if (P != null) state.rollMax = Math.max(state.rollMax * 0.999, Math.abs(P) * 1.1, 300);
  const frac = P == null ? 0 : clamp(Math.abs(P) / state.rollMax, 0, 1) * 50;
  const [neg, pos] = $('#pwrMeter').children;
  neg.style.width = (P != null && P < 0 ? frac : 0) + '%';
  pos.style.width = (P != null && P > 0 ? frac : 0) + '%';
  let sub = '';
  if (I != null) sub = 'ток ' + nf(I, 1) + ' А';
  if (fs.id === 'charge' && soc && I != null && I < -0.15) {
    const hrs = PACK_AH * (1 - clamp(soc.v, 0, 100) / 100) / Math.abs(I) + (soc.v < 95 ? 0.25 : 0);
    const hh = Math.floor(hrs), mm = Math.round((hrs - hh) * 60);
    sub += ' · до полной ≈ ' + (hh ? hh + ' ч ' : '') + mm + ' мин';
  }
  if (P == null && state.session) sub = 'нужен ток или мощность в декодере';
  $('#pwrSub').textContent = sub;
  /* speed */
  const kmh = telV('speed_kmh');
  $('#tSpeed').classList.toggle('stale', kmh == null || telStale('speed_kmh'));
  setVal($('#spdVal'), kmh == null ? '—' : nf(mph ? kmh / 1.609344 : kmh, 1), mph ? 'mph' : 'км/ч');
  const mode = telV('mode');
  const chip = $('#modeChip');
  chip.hidden = mode == null;
  if (mode != null) {
    const mm = MODES[Math.round(mode)];
    chip.dataset.m = mm ? String(Math.round(mode)) : '0';
    $('#modeName').replaceChildren(document.createTextNode(mm ? mm[0] : 'режим ' + nf(mode, 0)), mm ? h('small', { text: ' ' + mm[1] }) : '');
  }
  $('#spdSub').textContent = kmh == null ? 'лимит FLY — 16 mph (26 км/ч)' : (mph ? nf(kmh, 1) + ' км/ч' : nf(kmh / 1.609344, 1) + ' mph');
  /* minis */
  const mini = (id, v, d, unit, key) => { const el = $(id); el.classList.toggle('stale', v == null || (key && telStale(key))); setVal($('.val', el), v == null ? '—' : nf(v, d), unit); };
  mini('#mCurrent', I, 1, 'А', 'current');
  mini('#mVoltage', V, 1, 'В', 'voltage');
  mini('#mTemp', telV('temp'), 0, '°C', 'temp');
  const odo = telV('odometer_km');
  mini('#mOdo', odo == null ? null : (mph ? odo / 1.609344 : odo), 1, mph ? 'mi' : 'км', 'odometer_km');
  const tel = state.tel;
  const hasE = tel && (tel.whOut > 0 || tel.whIn > 0);
  mini('#mEnergy', hasE ? tel.whOut : null, 1, 'Вт·ч');
  $('#mEnergySub').textContent = hasE ? 'возвращено ' + nf(tel.whIn, 1) + ' Вт·ч' + (tel.whOut > 0 ? ' (' + nf(100 * tel.whIn / tel.whOut, 0) + '%)' : '') : 'потрачено и возвращено';
  const S = state.session;
  const rate = state.rxTimes.length / 5;
  const live = S && !S.offline;
  setVal($('#mRate .val'), S ? (live ? nf(rate, 1) : nf(S.entries.length, 0)) : '—', S ? (live ? 'пак/с' : 'записей') : '');
  $('#mRate').classList.toggle('stale', !S || (live && rate === 0));
  $('#mRateSub').textContent = S ? 'всего ' + nf(S.entries.filter(e => e.k === 'rx' || e.k === 'rd').length, 0) + ' пакетов' : 'пакеты от самоката';
  /* custom fields */
  const cust = decoder.fields.filter(f => f.key === 'custom');
  const box = $('#customs');
  box.hidden = !cust.length;
  if (cust.length) box.replaceChildren(h('div', { class: 'lbl' }, 'Свои поля'), ...cust.map(f => {
    const v = telV('c:' + f.id);
    return h('div', null, h('span', { text: f.name || ('байт ' + f.offset) }), h('b', { text: v == null ? '—' : nf(v, Math.abs(v) < 10 && v % 1 ? 2 : 0) }));
  }));
}

/* ============================================================
   charts (canvas)
   ============================================================ */
function niceStep(span, n) {
  const raw = span / Math.max(1, n);
  const p = Math.pow(10, Math.floor(Math.log10(raw)));
  const m = raw / p;
  return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 2.5 ? 2.5 : m <= 5 ? 5 : 10) * p;
}
function niceTicks(lo, hi, n) {
  if (!(hi > lo)) { hi = lo + 1; }
  const st = niceStep(hi - lo, n);
  const a = Math.floor(lo / st) * st, b = Math.ceil(hi / st) * st, out = [];
  for (let v = a; v <= b + st * 0.5; v += st) out.push(Math.abs(v) < st * 1e-6 ? 0 : v);
  return { ticks: out, step: st };
}
function tokens() {
  const cs = getComputedStyle(document.documentElement);
  const g = n => cs.getPropertyValue(n).trim();
  return { ink: g('--ink'), ink2: g('--ink-2'), muted: g('--muted'), hair: g('--hair'), base: g('--base'), surface: g('--surface'), draw: g('--draw'), flow: g('--flow'), raw: g('--raw') };
}
function nearestIdx(pts, t) {
  let lo = 0, hi = pts.length - 1;
  if (hi < 0) return -1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (pts[mid].t < t) lo = mid + 1; else hi = mid; }
  if (lo > 0 && Math.abs(pts[lo - 1].t - t) < Math.abs(pts[lo].t - t)) lo--;
  return lo;
}
/* o: { start, end, signed, color, colorNeg, markers, hoverT, rel, empty, minSpan, decimals } ; pts: [{t, y}] */
function drawSeries(cv, pts, o) {
  const W = cv.clientWidth, H = cv.clientHeight;
  if (!W || !H) return null;
  const dpr = window.devicePixelRatio || 1;
  if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) { cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr); }
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, W, H);
  const C = tokens();
  const font = '"IBM Plex Sans Condensed", "IBM Plex Sans", system-ui, sans-serif';
  const padL = 50, padR = 12, padT = 20, padB = 24, pw = W - padL - padR, ph = H - padT - padB;
  const geo = { padL, pw, start: o.start, end: o.end, vis: [] };
  const span = Math.max(1, o.end - o.start);
  const X = t => padL + (t - o.start) / span * pw;
  let vis = pts.filter(p => p.t >= o.start && p.t <= o.end && isFinite(p.y));
  geo.vis = vis;
  /* y domain */
  let lo, hi;
  if (vis.length) {
    lo = Infinity; hi = -Infinity;
    for (const p of vis) { if (p.y < lo) lo = p.y; if (p.y > hi) hi = p.y; }
  } else { lo = 0; hi = 1; }
  if (o.signed) { lo = Math.min(0, lo); hi = Math.max(0, hi); if (hi - lo < (o.minSpan || 100)) hi = lo + (o.minSpan || 100); }
  else if (hi - lo < (o.minSpan || 1)) { const c = (hi + lo) / 2; lo = c - (o.minSpan || 1) / 2; hi = c + (o.minSpan || 1) / 2; }
  const { ticks, step } = niceTicks(lo, hi, 4);
  const y0d = ticks[0], y1d = ticks[ticks.length - 1];
  const Y = v => padT + (y1d - v) / (y1d - y0d) * ph;
  geo.Y = Y; geo.X = X;
  /* grid + y labels */
  ctx.font = '11px ' + font;
  ctx.lineWidth = 1;
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'right';
  const dec = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
  for (const tk of ticks) {
    const y = Math.round(Y(tk)) + 0.5;
    ctx.strokeStyle = o.signed && tk === 0 ? C.base : C.hair;
    ctx.beginPath(); ctx.moveTo(padL, y); ctx.lineTo(padL + pw, y); ctx.stroke();
    ctx.fillStyle = C.muted;
    ctx.fillText(nf(tk, dec), padL - 6, y);
  }
  /* x labels */
  ctx.textBaseline = 'alphabetic';
  ctx.textAlign = 'center';
  ctx.fillStyle = C.muted;
  if (o.rel) {
    const secs = span / 1000, stepS = secs <= 90 ? 15 : secs <= 400 ? 60 : 300;
    for (let s = 0; s <= secs + 0.001; s += stepS) {
      const x = X(o.end - s * 1000);
      const lab = s === 0 ? 'сейчас' : secs <= 90 ? '−' + s + ' с' : '−' + (s / 60) + ' мин';
      ctx.textAlign = s === 0 ? 'right' : 'center';
      ctx.fillText(lab, s === 0 ? Math.min(x, padL + pw) : x, H - 6);
    }
  } else {
    const cand = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600];
    const secs = span / 1000;
    const stepS = cand.find(c => secs / c <= 6) || 3600;
    const first = Math.ceil(o.start / 1000 / stepS) * stepS;
    for (let s = first; s * 1000 <= o.end; s += stepS) {
      const x = X(s * 1000);
      if (x < padL + 14 || x > padL + pw - 14) continue;
      ctx.fillText(tfmt(s * 1000).replace(/\.\d$/, ''), x, H - 6);
    }
  }
  /* markers */
  const mks = (o.markers || []).filter(m => m.t >= o.start && m.t <= o.end);
  let lastEnd = -Infinity;
  ctx.font = '10.5px ' + font;
  ctx.textAlign = 'left';
  for (const m of mks) {
    const x = Math.round(X(m.t)) + 0.5;
    ctx.strokeStyle = C.ink2; ctx.globalAlpha = 0.35;
    ctx.beginPath(); ctx.moveTo(x, padT - 2); ctx.lineTo(x, padT + ph); ctx.stroke();
    ctx.globalAlpha = 1;
    const lab = m.n || '';
    const w = ctx.measureText(lab).width;
    let lx = x + 3;
    if (lx + w > padL + pw) lx = x - 3 - w;
    if (lx > lastEnd + 4) { ctx.fillStyle = C.ink2; ctx.fillText(lab, lx, padT - 6); lastEnd = lx + w; }
  }
  if (!vis.length) {
    ctx.fillStyle = C.muted; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.font = '13px ' + font;
    ctx.fillText(o.empty || 'Нет данных', padL + pw / 2, padT + ph / 2);
    return geo;
  }
  /* runs split on gaps */
  const runs = [];
  let run = [vis[0]];
  for (let k = 1; k < vis.length; k++) {
    if (vis[k].t - vis[k - 1].t > (o.gap || 3000)) { runs.push(run); run = []; }
    run.push(vis[k]);
  }
  runs.push(run);
  ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  const base = o.signed ? Y(0) : padT + ph;
  for (const r of runs) {
    const line = new Path2D();
    r.forEach((p, k) => { const x = X(p.t), y = Y(p.y); if (k) line.lineTo(x, y); else line.moveTo(x, y); });
    if (r.length === 1) line.lineTo(X(r[0].t) + 1, Y(r[0].y));
    const area = new Path2D(line);
    area.lineTo(X(r[r.length - 1].t), base); area.lineTo(X(r[0].t), base); area.closePath();
    const paint = (clipTop, clipBot, color) => {
      ctx.save();
      ctx.beginPath(); ctx.rect(0, clipTop, W, clipBot - clipTop); ctx.clip();
      ctx.globalAlpha = 0.12; ctx.fillStyle = color; ctx.fill(area);
      ctx.globalAlpha = 1; ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.stroke(line);
      ctx.restore();
    };
    if (o.signed) { const y0 = Y(0); paint(0, y0, o.color); paint(y0, H, o.colorNeg); }
    else paint(0, H, o.color);
  }
  /* crosshair */
  if (o.hoverT != null) {
    const k = nearestIdx(vis, o.hoverT);
    if (k >= 0) {
      const p = vis[k], x = Math.round(X(p.t)) + 0.5, y = Y(p.y);
      ctx.strokeStyle = C.ink2; ctx.globalAlpha = 0.7;
      ctx.beginPath(); ctx.moveTo(x, padT); ctx.lineTo(x, padT + ph); ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.fillStyle = C.surface; ctx.beginPath(); ctx.arc(x, y, 6, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = o.signed && p.y < 0 ? o.colorNeg : o.color; ctx.beginPath(); ctx.arc(x, y, 4, 0, Math.PI * 2); ctx.fill();
      geo.hover = { p, x, y };
    }
  }
  return geo;
}
function chartRange() {
  const S = state.session, end = nowT();
  if (state.win === 'all' || (S && S.offline && state.win === 'all')) {
    const first = state.series.length ? state.series[0].t : 0;
    const mk = S ? S.entries.find(e => e.k === 'mk') : null;
    const start = Math.min(first, mk ? mk.t : first);
    return { start, end: Math.max(end, start + 10000), rel: false };
  }
  const w = Number(state.win) * 1000;
  return { start: end - w, end, rel: !(S && S.offline) };
}
function drawPowerChart() {
  const cv = $('#chart');
  const C = tokens();
  const S = state.session;
  const r = chartRange();
  const pts = state.series.map(p => ({ t: p.t, y: p.p, src: p }));
  const markers = S ? S.entries.filter(e => e.k === 'mk') : [];
  const geo = drawSeries(cv, pts, {
    start: r.start, end: r.end, rel: r.rel, signed: true, color: C.draw, colorNeg: C.flow, markers, hoverT: state.hoverT, minSpan: 100,
    empty: !S ? 'Подключи самокат или включи демо' : 'Нет мощности: в декодере нет тока или мощности',
  });
  state.geo = geo;
  const tip = $('#chartTip');
  if (!geo || !geo.hover) { tip.hidden = true; return; }
  const p = geo.hover.p.src;
  const mph = settings.units === 'mph';
  const rows = [
    ['Время', tfmt(p.t)],
    ['Ток', p.i == null ? '—' : nf(p.i, 1) + ' А'],
    ['Напряжение', p.v == null ? '—' : nf(p.v, 1) + ' В'],
    ['Скорость', p.s == null ? '—' : (mph ? nf(p.s / 1.609344, 1) + ' mph' : nf(p.s, 1) + ' км/ч')],
  ];
  tip.replaceChildren(
    h('div', { class: 'tv' }, h('i', { style: 'background:' + (p.p < 0 ? 'var(--flow)' : 'var(--draw)') }), nf(Math.abs(p.p), 0) + ' Вт ' + (p.p < 0 ? 'в батарею' : 'из батареи')),
    ...rows.map(([a, b]) => h('div', { class: 'tr' }, h('span', { text: a }), h('b', { text: b }))),
  );
  tip.hidden = false;
  const boxW = $('#chartBox').clientWidth, tw = tip.offsetWidth || 170;
  let left = geo.hover.x + 14;
  if (left + tw > boxW) left = geo.hover.x - 14 - tw;
  tip.style.left = clamp(left, 0, Math.max(0, boxW - tw)) + 'px';
}
function renderChartTable() {
  const r = chartRange();
  const rows = state.series.filter(p => p.t >= r.start && p.t <= r.end).slice(-40).reverse();
  const mph = settings.units === 'mph';
  const t = $('#chartTable');
  t.replaceChildren(
    h('thead', null, h('tr', null, h('th', { text: 'Время' }), h('th', { text: 'Мощность, Вт' }), h('th', { text: 'Ток, А' }), h('th', { text: 'Напряжение, В' }), h('th', { text: mph ? 'Скорость, mph' : 'Скорость, км/ч' }))),
    h('tbody', null, rows.length ? rows.map(p => h('tr', null,
      h('td', { text: tfmt(p.t) }), h('td', { text: nf(p.p, 0) }), h('td', { text: p.i == null ? '—' : nf(p.i, 1) }),
      h('td', { text: p.v == null ? '—' : nf(p.v, 1) }), h('td', { text: p.s == null ? '—' : nf(mph ? p.s / 1.609344 : p.s, 1) }))) : h('tr', null, h('td', { colspan: 5, text: 'Нет точек в выбранном окне' }))),
  );
}
(function chartEvents() {
  const cv = $('#chart');
  const move = ev => {
    const g = state.geo;
    if (!g) return;
    const rect = cv.getBoundingClientRect();
    const x = ev.clientX - rect.left;
    state.hoverT = clamp(g.start + (x - g.padL) / g.pw * (g.end - g.start), g.start, g.end);
    requestRender(true);
  };
  cv.addEventListener('pointermove', move);
  cv.addEventListener('pointerdown', move);
  cv.addEventListener('pointerleave', () => { state.hoverT = null; requestRender(true); });
  cv.addEventListener('keydown', ev => {
    const g = state.geo;
    if (!g || (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight')) return;
    ev.preventDefault();
    const stepT = (g.end - g.start) / 60;
    const cur = state.hoverT == null ? g.end : state.hoverT;
    state.hoverT = clamp(cur + (ev.key === 'ArrowLeft' ? -stepT : stepT), g.start, g.end);
    requestRender(true);
  });
  cv.addEventListener('blur', () => { state.hoverT = null; requestRender(true); });
})();

/* ============================================================
   explorer
   ============================================================ */
function propChips(p) {
  const out = [];
  if (p.read) out.push(['R', 'чтение']);
  if (p.write) out.push(['W', 'запись']);
  if (p.wnr) out.push(['WnR', 'запись без ответа']);
  if (p.notify) out.push(['N', 'уведомления']);
  if (p.indicate) out.push(['I', 'индикации']);
  return h('span', { class: 'chips' }, out.map(([a, b]) => h('span', { class: 'chip', title: b, text: a })));
}
function renderExplorer() {
  const box = $('#explorer');
  const S = state.session;
  $('#devName').textContent = S && S.device ? S.device.name : '';
  if (!state.gatt.length) {
    box.replaceChildren(h('p', { class: 'empty', text: S && S.offline
      ? 'В логе из файла нет списка сервисов. Пакеты всё равно можно разбирать ниже.'
      : 'Подключи самокат, и здесь появятся его сервисы и характеристики. Если после подключения список пуст, добавь UUID из nRF Connect в настройках ниже.' }));
    return;
  }
  box.replaceChildren(...state.gatt.map(s => h('div', { class: 'svc' },
    h('div', { class: 'svc-head' }, h('b', { text: SVC_NAMES[s.uuid] || 'Неизвестный сервис' }), h('code', { text: shortUuid(s.uuid) })),
    s.chars.length ? s.chars.map(info => {
      const hex = h('div', { class: 'hexbox' });
      const sub = h('button', { class: 'btn btn-sm', type: 'button', onclick: () => (info.subscribed ? unsubscribe(info) : subscribe(info)) });
      const rd = h('button', { class: 'btn btn-sm', type: 'button', text: 'Прочитать', onclick: () => readChar(info, false) });
      const an = h('button', { class: 'btn btn-sm', type: 'button', text: 'Разобрать байты', onclick: () => { state.an.key = info.key; renderCharSelects(); renderAnalyzer(true); $('#anCard').scrollIntoView({ behavior: 'smooth', block: 'start' }); } });
      const wr = h('button', { class: 'btn btn-sm', type: 'button', text: 'Писать сюда', onclick: () => { $('#wrChar').value = info.key; $('#writeCard').scrollIntoView({ behavior: 'smooth', block: 'start' }); } });
      const cnt = h('span', { class: 'empty' });
      info.ui = { hex, sub, rd, an, wr, cnt };
      return h('div', { class: 'chr' },
        h('div', { class: 'chr-top' }, h('b', { text: CHR_NAMES[info.uuid] || 'Характеристика' }), h('code', { text: shortUuid(info.uuid) }), propChips(info.props),
          info.danger ? h('span', { class: 'chip danger', text: 'OTA — не трогать' }) : null, cnt),
        hex,
        h('div', { class: 'row' }, info.props.notify || info.props.indicate ? sub : null, info.props.read ? rd : null, an, (info.props.write || info.props.wnr) ? wr : null));
    }) : h('div', { class: 'chr' }, h('span', { class: 'empty', text: 'Характеристик нет или они закрыты' })))));
  renderExplorerButtons();
  refreshExplorerValues();
}
function renderExplorerButtons() {
  const live = state.conn === 'connected';
  for (const info of state.chars.values()) {
    const u = info.ui;
    if (!u) continue;
    u.sub.textContent = info.subscribed ? 'Отписаться' : 'Подписаться';
    u.sub.disabled = !info.obj || !live || info.danger;
    u.rd.disabled = !info.obj || !live || info.danger;
    u.wr.hidden = !settings.allowWrite;
    u.wr.disabled = !info.obj || !live || info.danger;
  }
}
function refreshExplorerValues() {
  for (const info of state.chars.values()) {
    const u = info.ui;
    if (!u) continue;
    const e = info.last;
    u.cnt.textContent = info.count ? info.count + ' пак.' : '';
    u.an.disabled = !info.count;
    if (!e) { u.hex.replaceChildren(h('em', { text: info.subscribed ? 'ждём данные…' : 'данных пока нет' })); continue; }
    const txt = printable(e.b);
    u.hex.replaceChildren(document.createTextNode(e.h || '(пусто)'), txt && txt.trim() ? h('em', { text: '  «' + txt.slice(0, 80) + '»' }) : '');
  }
}
function renderCharSelects() {
  const keys = new Map();
  for (const info of state.chars.values()) keys.set(info.key, { s: info.service, u: info.uuid, count: info.count, props: info.props, danger: info.danger });
  for (const [k, st] of state.charStats) if (!keys.has(k)) keys.set(k, { s: st.s, u: st.u, count: st.count });
  const label = (k, st) => (CHR_NAMES[st.u] ? CHR_NAMES[st.u] + ' ' : '') + shortUuid(st.u) + (st.count ? ' · ' + st.count + ' пак.' : '');
  /* analyzer */
  const an = $('#anChar');
  const withData = Array.from(keys).filter(([, st]) => st.count).sort((a, b) => b[1].count - a[1].count);
  if (!state.an.key || !keys.has(state.an.key)) state.an.key = withData.length ? withData[0][0] : '';
  an.replaceChildren(...(withData.length ? withData.map(([k, st]) => h('option', { value: k, text: label(k, st), selected: k === state.an.key })) : [h('option', { value: '', text: 'Пока нет пакетов' })]));
  if (state.an.key) an.value = state.an.key;
  /* log filter */
  const lf = $('#logFilter'), cur = lf.value;
  lf.replaceChildren(h('option', { value: '', text: 'Все записи' }), h('option', { value: 'mk', text: 'Только метки' }),
    ...withData.map(([k, st]) => h('option', { value: k, text: label(k, st) })));
  lf.value = Array.from(lf.options).some(o => o.value === cur) ? cur : '';
  /* write target */
  const wr = $('#wrChar'), wcur = wr.value;
  const writable = Array.from(state.chars.values()).filter(i => (i.props.write || i.props.wnr) && !i.danger);
  wr.replaceChildren(...(writable.length ? writable.map(i => h('option', { value: i.key, text: (CHR_NAMES[i.uuid] ? CHR_NAMES[i.uuid] + ' ' : '') + shortUuid(i.uuid) })) : [h('option', { value: '', text: 'Нет характеристик для записи' })]));
  if (writable.some(i => i.key === wcur)) wr.value = wcur;
  renderDecoderCharOptions();
}

/* ============================================================
   log
   ============================================================ */
const KIND = { rx: '↓', rd: '●', tx: '↑', mk: '⚑', sys: 'i' };
function renderLog() {
  const S = state.session, box = $('#log');
  if (!S || !S.entries.length) { box.replaceChildren(h('div', { class: 'lr sys' }, h('span', { class: 't' }), h('span'), h('span'), h('span', { class: 'd', text: 'Лог пуст.' }))); return; }
  const f = $('#logFilter').value, collapse = $('#logCollapse').checked;
  const rows = [];
  let lastKey = null, lastRow = null, n = 0;
  for (let k = S.entries.length - 1; k >= 0 && rows.length < 250; k--) {
    const e = S.entries[k];
    if (f === 'mk' && e.k !== 'mk') continue;
    if (f && f !== 'mk' && e.k !== 'mk' && (e.s + '|' + e.u) !== f) continue;
    const key = e.k + '|' + e.u + '|' + (e.h || e.n);
    if (collapse && key === lastKey && lastRow) { n++; lastRow.rep.textContent = '  ×' + (n + 1); lastRow.t.textContent = tfmt(e.t); continue; }
    n = 0; lastKey = key;
    const tEl = h('span', { class: 't', text: tfmt(e.t) });
    const rep = h('em');
    const d = e.k === 'mk' || e.k === 'sys' ? h('span', { class: 'd', text: e.n || '' }) : h('span', { class: 'd' }, e.h || '(пусто)', rep);
    const row = h('div', { class: 'lr ' + e.k + (e.err ? ' err' : '') }, tEl, h('span', { class: 'k', text: KIND[e.k] || '', title: e.k }), h('span', { class: 'u', text: e.u ? shortUuid(e.u) : '' }), d);
    rows.push(row);
    lastRow = { rep, t: tEl };
  }
  box.replaceChildren(...rows);
}
function sessionObject() {
  const S = state.session;
  return {
    format: 'birdair-lab-log', version: 1, startedAt: new Date(S.startedAt).toISOString(), device: S.device, demo: !!S.demo, trimmed: !!S.trimmed,
    gatt: state.gatt.map(s => ({ uuid: s.uuid, chars: s.chars.map(c => ({ uuid: c.uuid, props: c.props })) })),
    decoder: JSON.parse(decoderToJson(decoder)),
    entries: S.entries.map(e => ({ t: e.t, k: e.k, s: e.s, u: e.u, h: e.h, n: e.n })),
  };
}
function sessionJson() { return JSON.stringify(sessionObject()); }
function flash(el, text, err) { el.textContent = text; el.classList.toggle('err', !!err); }
function exportLog() {
  const S = state.session;
  if (!S || !S.entries.length) { flash($('#logMsg'), 'Лог пуст — нечего сохранять.', true); return; }
  const d = new Date(S.startedAt), p = n => String(n).padStart(2, '0');
  const name = 'birdair-log-' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + (S.demo ? '-demo' : '') + '.json';
  const blob = new Blob([sessionJson()], { type: 'application/json' });
  const a = h('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 2000);
  S.exported = true;
  flash($('#logMsg'), 'Сохранено: ' + name + ' (' + nf(blob.size / 1024, 0) + ' КБ).');
}
async function copyLog() {
  const S = state.session;
  if (!S || !S.entries.length) { flash($('#logMsg'), 'Лог пуст — нечего копировать.', true); return; }
  const json = sessionJson();
  try { await navigator.clipboard.writeText(json); S.exported = true; flash($('#logMsg'), 'Лог скопирован (' + nf(json.length / 1024, 0) + ' КБ).'); }
  catch { flash($('#logMsg'), 'Браузер не дал скопировать. Используй «Скачать лог».', true); }
}
async function readLogFile(file) {
  let bytes = new Uint8Array(await file.arrayBuffer());
  if (bytes[0] === 0x1f && bytes[1] === 0x8b) {
    if (typeof DecompressionStream !== 'function') throw new Error(file.name + ': браузер не умеет распаковывать .gz — открой в свежем Chrome.');
    try { bytes = await gunzipBytes(bytes); } catch { throw new Error(file.name + ': архив .gz повреждён.'); }
  }
  let o;
  try { o = JSON.parse(new TextDecoder().decode(bytes)); } catch { throw new Error(file.name + ': это не JSON. Нужен файл, сохранённый кнопкой «Скачать лог».'); }
  if (!o || o.format !== 'birdair-lab-log' || !Array.isArray(o.entries)) throw new Error(file.name + ': формат не тот, нужен лог Bird Air Lab.');
  return o;
}
/* One log, or all parts of one log (part.id), possibly gzipped. */
async function importLogs(files) {
  if (state.conn === 'connected' || state.conn === 'connecting' || state.conn === 'reconnecting') { flash($('#logMsg'), 'Сначала отключись от самоката.', true); return; }
  let logs;
  try { logs = await Promise.all(files.map(readLogFile)); } catch (e) { flash($('#logMsg'), e.message, true); return; }
  let o = logs[0], note = '';
  const name = files.length > 1 ? files[0].name.replace(/-part\d+of\d+(\.json)?(\.gz)?$/, '') : files[0].name;
  if (logs.length > 1 || o.part) {
    const ids = new Set(logs.map(l => l.part && l.part.id));
    if (ids.size !== 1 || ids.has(undefined)) { flash($('#logMsg'), 'Выбери один лог или все части одного лога.', true); return; }
    logs.sort((a, b) => a.part.index - b.part.index);
    const count = o.part.count || logs.length, have = new Set(logs.map(l => l.part.index)), missing = [];
    for (let i = 1; i <= count; i++) if (!have.has(i)) missing.push(i);
    o = { ...logs[0], entries: logs.flatMap(l => l.entries).sort((a, b) => a.t - b.t) };
    delete o.part;
    if (missing.length) note = ' Не хватает частей ' + missing.join(', ') + ' из ' + count + ': выбери все части сразу.';
  }
  loadLogObject(o, name, note);
}
function loadLogObject(o, fileName, note) {
  stopDemo(true);
  newSession({ offline: true, startedAt: Date.parse(o.startedAt) || Date.now(), device: o.device || { name: fileName }, demo: !!o.demo });
  state.gatt = Array.isArray(o.gatt) ? o.gatt.map(s => ({ uuid: s.uuid, chars: (s.chars || []).map(c => ({ key: s.uuid + '|' + c.uuid, service: s.uuid, uuid: c.uuid, obj: null, danger: isDanger(s.uuid, c.uuid), props: c.props || {}, count: 0, last: null })) })) : [];
  state.chars = new Map(state.gatt.flatMap(s => s.chars.map(c => [c.key, c])));
  const fileDec = normalizeDecoder(o.decoder);
  if (fileDec && fileDec.fields.length && !decoder.fields.length) { decoder = fileDec; saveDecoder(); }
  for (const e of o.entries) {
    if (!e || typeof e.t !== 'number' || !KIND[e.k]) continue;
    pushEntry({ t: e.t, k: e.k, s: e.s, u: e.u, h: e.h, n: e.n });
  }
  state.session.exported = true;
  state.win = 'all';
  syncWinSeg();
  setConn('offline', fileName);
  renderExplorer(); renderCharSelects(); renderDecoder();
  rebuildTelemetry();
  flash($('#logMsg'), 'Загружено записей: ' + state.session.entries.length + (fileDec && fileDec.fields.length && decoder !== fileDec ? '. В файле есть свой декодер — его можно взять через «Декодер → Конфиг текстом».' : '.') + (note || ''), !!note);
}
function clearLog() {
  const S = state.session;
  if (!S) return;
  S.entries = []; S.exported = false; S.gh = null;
  state.series = []; state.tel = newTel(); state.charStats = new Map();
  for (const i of state.chars.values()) { i.count = 0; i.last = null; }
  renderCharSelects(); requestRender(true);
  flash($('#logMsg'), 'Лог очищен.');
}

/* ============================================================
   GitHub upload
   ============================================================ */
/* fileMax: bytes per file before base64 — the contents API reads files up to 1 MB back. */
const GH = { api: 'https://api.github.com', fileMax: 1024 * 1024, maxParts: 200 };
function ghDefaults() {
  const m = location.hostname.match(/^([a-z0-9-]+)\.github\.io$/i);
  const seg = location.pathname.split('/').filter(Boolean)[0];
  if (!m) return { owner: 'Purple-Palm', repo: 'Bird-Air-Tweaking', branch: 'main', folder: 'logs' };
  return { owner: m[1], repo: seg && !/\.html?$/i.test(seg) ? seg : m[1] + '.github.io', branch: 'main', folder: 'logs' };
}
/* Repo settings live in bal.gh; the token alone in bal.gh.token, never in settings or logs. */
const ghCfg = Object.assign(ghDefaults(), store.get('bal.gh', {}));
const ghToken = () => String(store.get('bal.gh.token', '') || '');
const TRANSLIT = Object.fromEntries(Array.from('абвгдеёжзийклмнопрстуфхцчшщъыьэюя').map((c, i) =>
  [c, ['a', 'b', 'v', 'g', 'd', 'e', 'e', 'zh', 'z', 'i', 'y', 'k', 'l', 'm', 'n', 'o', 'p', 'r', 's', 't', 'u', 'f', 'h', 'ts', 'ch', 'sh', 'sch', '', 'y', '', 'e', 'yu', 'ya'][i]]));
function slug(label, dflt) {
  const s = Array.from(String(label || '').toLowerCase()).map(c => TRANSLIT[c] ?? c).join('')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40).replace(/-+$/, '');
  return s || dflt;
}
function stamp(ms) {
  const d = new Date(ms), p = n => String(n).padStart(2, '0');
  return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + '-' + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}
function ghBase() { const S = state.session; return stamp(S.startedAt) + '-' + slug($('#ghLabel').value, S.demo ? 'demo' : 'log'); }
function ghPath(name) { const f = String(ghCfg.folder || '').replace(/^\/+|\/+$/g, ''); return (f ? f + '/' : '') + name; }
function renderGhName() {
  const el = $('#ghName'), S = state.session;
  const t = S && S.entries.length ? 'Файл: ' + ghPath(ghBase()) + '.json (большой — .json.gz)' : '';
  if (el.textContent !== t) el.textContent = t;
}

/* Same rules as tools/birdlog.py anonymize(). Mutates o, which is a fresh sessionObject(). */
const NAME_CHARS = new Set([normUuid('2a00'), normUuid('2a25')]), ZERO_CHARS = new Set([normUuid('2a23')]);
function anonymizeLog(o) {
  const dev = o.device || {};
  const secrets = [dev.name, dev.id].filter(x => x && String(x).length >= 2 && x !== 'scooter').map(String).sort((a, b) => b.length - a.length);
  o.device = { name: 'scooter', id: 'scooter' };
  const enc = new TextEncoder(), scooter = bytesToHex(enc.encode('scooter'));
  const inPackets = secrets.map(s => enc.encode(s)).filter(b => b.length >= 4).map(b => [bytesToHex(b), bytesToHex(new Uint8Array(b.length).fill(0x2a))]);
  for (const e of o.entries) {
    if (e.n) for (const s of secrets) e.n = e.n.split(s).join('scooter');
    if (e.h == null) continue;
    if (NAME_CHARS.has(e.u)) e.h = scooter;
    else if (ZERO_CHARS.has(e.u)) e.h = bytesToHex(new Uint8Array((hexToBytes(e.h) || []).length));
    else for (const [hx, star] of inPackets) e.h = e.h.split(hx).join(star);
  }
  return o;
}

async function gzipBytes(u8) { return new Uint8Array(await new Response(new Blob([u8]).stream().pipeThrough(new CompressionStream('gzip'))).arrayBuffer()); }
async function gunzipBytes(u8) { return new Uint8Array(await new Response(new Blob([u8]).stream().pipeThrough(new DecompressionStream('gzip'))).arrayBuffer()); }
function b64(u8) {
  let s = '';
  for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
  return btoa(s);
}
/* Plain .json while it fits, then .json.gz, then parts that are each a complete log with part {id, index, count}. */
async function packLog(o, base) {
  const raw = new TextEncoder().encode(JSON.stringify(o));
  if (raw.length <= GH.fileMax) return [{ name: base + '.json', bytes: raw }];
  const gz = typeof CompressionStream === 'function';
  let size = raw.length;
  if (gz) {
    const z = await gzipBytes(raw);
    if (z.length <= GH.fileMax) return [{ name: base + '.json.gz', bytes: z }];
    size = z.length;
  }
  const ext = gz ? '.json.gz' : '.json';
  for (let n = Math.max(2, Math.ceil(size / (GH.fileMax * 0.85))); n <= GH.maxParts; n = Math.ceil(n * 1.3) + 1) {
    const per = Math.ceil(o.entries.length / n), count = Math.ceil(o.entries.length / per), files = [];
    for (let i = 0; i < count; i++) {
      const part = { ...o, part: { id: base, index: i + 1, count }, entries: o.entries.slice(i * per, (i + 1) * per) };
      const json = new TextEncoder().encode(JSON.stringify(part));
      const bytes = gz ? await gzipBytes(json) : json;
      if (bytes.length > GH.fileMax) break;
      files.push({ name: base + '-part' + (i + 1) + 'of' + count + ext, bytes });
    }
    if (files.length === count) return files;
  }
  throw new Error('Лог слишком большой даже по частям. Скачай его кнопкой «Скачать лог».');
}

function ghError(status, message) {
  const repo = ghCfg.owner + '/' + ghCfg.repo;
  let text;
  if (!status) text = 'Нет связи с api.github.com. Проверь интернет и нажми ещё раз.';
  else if ((status === 404 || status === 422) && /branch/i.test(message)) text = 'Ветка «' + ghCfg.branch + '» не найдена в ' + repo + ' (' + status + '). Проверь ветку в настройках GitHub.';
  else if (status === 401) text = 'Токен не принят (401): он неверный, отозван или истёк. Создай новый и вставь в настройках GitHub.';
  else if (status === 403 && /rate limit/i.test(message)) text = 'GitHub временно ограничил запросы (403). Подожди пару минут.';
  else if (status === 403) text = 'Доступ запрещён (403): токену не выдано право Contents: Read and write на ' + repo + '.';
  else if (status === 404) text = 'Репозиторий ' + repo + ' не найден (404). Проверь owner и repo; ещё 404 бывает, когда токен не допущен к этому репозиторию.';
  else if (status === 409) text = 'Конфликт (409): ветка изменилась во время записи. Нажми «Отправить» ещё раз.';
  else if (status === 422 && /sha/i.test(message)) text = 'Конфликт (422): такой файл уже есть в репозитории.';
  else if (status === 422) text = 'GitHub отклонил запись (422): ' + (message || 'неверный запрос') + '.';
  else text = 'Ошибка GitHub ' + status + (message ? ': ' + message : '') + '.';
  return Object.assign(new Error(text), { status, ghMessage: message || '' });
}
async function ghFetch(method, path, body) {
  const headers = { Accept: 'application/vnd.github+json', Authorization: 'Bearer ' + ghToken(), 'X-GitHub-Api-Version': '2022-11-28' };
  if (body) headers['Content-Type'] = 'application/json';
  let r;
  try {
    r = await fetch(GH.api + '/repos/' + encodeURIComponent(ghCfg.owner) + '/' + encodeURIComponent(ghCfg.repo) + path,
      { method, headers, body: body ? JSON.stringify(body) : undefined, cache: 'no-store' });
  } catch { throw ghError(0); }
  let data = null;
  try { data = await r.json(); } catch { data = null; }
  if (!r.ok) throw ghError(r.status, data && data.message);
  return data;
}
const ghPut = (path, bytes, message) =>
  ghFetch('PUT', '/contents/' + path.split('/').map(encodeURIComponent).join('/'), { message, content: b64(bytes), branch: ghCfg.branch });

function showGhSettings() {
  showTab('data');
  const card = $('#ghCard');
  card.scrollIntoView({ behavior: 'smooth', block: 'start' });
  const empty = ['#ghOwner', '#ghRepo', '#ghBranch', '#ghToken'].map(s => $(s)).find(el => !el.value.trim());
  (empty || $('#ghToken')).focus({ preventScroll: true });
}
async function sendToGitHub() {
  const S = state.session, msg = $('#ghMsg'), btn = $('#btnGhSend');
  if (!S || !S.entries.length) { flash(msg, 'Лог пуст — нечего отправлять.', true); return; }
  if (!ghCfg.owner || !ghCfg.repo || !ghCfg.branch) { flash(msg, 'Заполни owner, репозиторий и ветку в настройках GitHub.', true); showGhSettings(); return; }
  if (!ghToken()) { flash(msg, 'Нет токена: вставь fine-grained токен в настройках GitHub.', true); showGhSettings(); return; }
  const anon = $('#ghAnon').checked, n = S.entries.length;
  if (S.gh && S.gh.entries === n && S.gh.anon === anon) { ghDone(msg, 'Этот лог уже отправлен: ', S.gh.files); return; }
  btn.disabled = true;
  try {
    const o = sessionObject();
    if (anon) anonymizeLog(o);
    flash(msg, 'Готовлю файл…');
    /* Names come from the recording's start time; if one is taken (sent before a reload), add -2, -3… */
    for (let attempt = 1; ; attempt++) {
      const base = ghBase() + (attempt > 1 ? '-' + attempt : '');
      const files = await packLog(o, base), sent = [];
      try {
        for (const [i, f] of files.entries()) {
          if (files.length > 1) flash(msg, 'Отправляю часть ' + (i + 1) + ' из ' + files.length + '…');
          else flash(msg, 'Отправляю ' + nf(f.bytes.length / 1024, 0) + ' КБ…');
          const path = ghPath(f.name);
          const r = await ghPut(path, f.bytes, 'Лог ' + base + ': ' + n + ' записей' + (files.length > 1 ? ', часть ' + (i + 1) + ' из ' + files.length : ''));
          sent.push({ path, url: r && r.content && r.content.html_url, size: f.bytes.length });
        }
      } catch (e) {
        if (e.status === 422 && /sha/i.test(e.ghMessage) && !sent.length && attempt < 9) continue;
        throw e;
      }
      S.gh = { entries: n, anon, files: sent };
      S.exported = true;
      ghDone(msg, 'Отправлено: ', sent);
      return;
    }
  } catch (e) {
    flash(msg, e.message || String(e), true);
  } finally {
    btn.disabled = false;
  }
}
function ghDone(msg, lead, files) {
  msg.classList.remove('err');
  msg.replaceChildren(lead, ...files.flatMap((f, i) => [i ? ', ' : '', f.url ? h('a', { href: f.url, target: '_blank', rel: 'noopener', text: f.path }) : f.path]),
    files.length === 1 && files[0].size ? ' (' + nf(files[0].size / 1024, 0) + ' КБ)' : '', '.');
}
async function checkGitHub() {
  const msg = $('#ghSetMsg');
  if (!ghToken()) { flash(msg, 'Сначала вставь токен.', true); return; }
  flash(msg, 'Проверяю…');
  try {
    const repo = await ghFetch('GET', '');
    await ghFetch('GET', '/branches/' + encodeURIComponent(ghCfg.branch));
    const note = repo && repo.permissions && repo.permissions.push === false ? ' Но у твоей учётной записи нет права записи.' : ' Право записи проверится при первой отправке.';
    flash(msg, 'Доступ есть: ' + (repo && repo.full_name || ghCfg.owner + '/' + ghCfg.repo) + ', ветка ' + ghCfg.branch + (repo && repo.private ? ' (репозиторий приватный)' : '') + '.' + note);
  } catch (e) { flash(msg, e.message, true); }
}
function bindGitHub() {
  const save = () => store.set('bal.gh', { owner: ghCfg.owner, repo: ghCfg.repo, branch: ghCfg.branch, folder: ghCfg.folder });
  for (const k of ['owner', 'repo', 'branch', 'folder']) {
    const el = $('#gh' + k[0].toUpperCase() + k.slice(1));
    el.value = ghCfg[k] || '';
    el.addEventListener('input', () => { ghCfg[k] = el.value.trim(); save(); renderGhName(); });
  }
  const tok = $('#ghToken');
  tok.value = ghToken();
  tok.addEventListener('input', () => { const v = tok.value.trim(); if (v) store.set('bal.gh.token', v); else store.del('bal.gh.token'); });
  $('#ghForget').addEventListener('click', () => { store.del('bal.gh.token'); tok.value = ''; flash($('#ghSetMsg'), 'Токен удалён из этого браузера.'); });
  $('#ghCheck').addEventListener('click', checkGitHub);
  $('#btnGhSend').addEventListener('click', sendToGitHub);
  $('#btnGhSetup').addEventListener('click', showGhSettings);
  $('#ghLabel').addEventListener('input', renderGhName);
}

/* ============================================================
   byte analyzer
   ============================================================ */
function analyzerPackets() {
  const S = state.session;
  if (!S || !state.an.key) return { pk: [], bad: false };
  const pat = parsePattern(state.an.pattern);
  const bad = pat === undefined;
  const pk = S.entries.filter(e => (e.k === 'rx' || e.k === 'rd') && (e.s + '|' + e.u) === state.an.key && (bad || matchPrefix(e.b, pat)));
  return { pk, bad };
}
function renderAnalyzer(force) {
  const { pk, bad } = analyzerPackets();
  flash($('#anMsg'), bad ? 'Фильтр не разобран: пиши байты в hex через пробел, ?? — любой байт.' : pk.length ? 'Пакетов под фильтр: ' + pk.length : (state.an.key ? 'Под фильтр не попал ни один пакет.' : ''), bad);
  const grid = $('#anGrid');
  const recent = pk.slice(-24).reverse();
  const window = pk.slice(-200);
  const cols = Math.min(64, recent.reduce((m, e) => Math.max(m, e.b.length), 0));
  if (!recent.length) { grid.replaceChildren(); $('#anSel').hidden = true; renderMarkerDiff(pk); return; }
  const variab = [];
  for (let c = 0; c < cols; c++) {
    const seen = new Set();
    for (const e of window) if (c < e.b.length) seen.add(e.b[c]);
    variab.push(window.length > 1 ? clamp((seen.size - 1) / Math.min(window.length - 1, 15), 0, 1) : 0);
  }
  const off = state.an.off;
  const head = h('tr', null, h('th', { class: 'rowh', text: '№' }), ...variab.map((v, c) => h('th', {
    'aria-pressed': off === c ? 'true' : 'false', 'data-off': c, tabindex: 0, title: 'Байт ' + c + (v ? ' — меняется' : ' — постоянный'),
    style: v ? 'background:color-mix(in srgb, var(--raw) ' + Math.round(10 + v * 38) + '%, var(--surface))' : null, text: String(c),
  })));
  const body = recent.map(e => h('tr', null, h('th', { class: 'rowh', text: tfmt(e.t) }), ...Array.from({ length: cols }, (_, c) => h('td', {
    class: c === off ? 'sel' : null,
    style: variab[c] ? 'background:color-mix(in srgb, var(--raw) ' + Math.round(6 + variab[c] * 26) + '%, transparent)' : null,
    text: c < e.b.length ? hex2(e.b[c]).toUpperCase() : '',
  }))));
  grid.replaceChildren(h('thead', null, head), h('tbody', null, body));
  /* selected offset */
  const sel = $('#anSel');
  sel.hidden = off == null;
  if (off != null) {
    const type = state.an.type;
    const pts = pk.map(e => ({ t: e.t, y: readNum(e.b, off, type) })).filter(p => p.y != null);
    let mn = Infinity, mx = -Infinity;
    for (const p of pts) { if (p.y < mn) mn = p.y; if (p.y > mx) mx = p.y; }
    $('#anSelTitle').textContent = 'Байт ' + off;
    $('#anStats').textContent = pts.length ? 'мин ' + mn + ' · макс ' + mx + ' · сейчас ' + pts[pts.length - 1].y : 'нет значений такого размера';
    const C = tokens();
    const S = state.session;
    const start = pts.length ? pts[0].t : 0, end = pts.length ? Math.max(pts[pts.length - 1].t, start + 2000) : 1;
    drawSeries($('#anPlot'), pts, { start, end, rel: false, signed: false, color: C.raw, markers: S ? S.entries.filter(e => e.k === 'mk') : [], minSpan: 2, gap: 5000, empty: 'Нет значений' });
  }
  renderMarkerDiff(pk);
}
function renderMarkerDiff(pk) {
  const S = state.session;
  const sel = $('#anMk');
  const mks = S ? S.entries.filter(e => e.k === 'mk') : [];
  const cur = sel.value;
  sel.replaceChildren(...(mks.length ? mks.map((m, k) => h('option', { value: String(k), text: tfmt(m.t) + ' · ' + m.n })) : [h('option', { value: '', text: 'Меток пока нет' })]));
  if (cur && Number(cur) < mks.length) sel.value = cur; else if (mks.length) sel.value = String(mks.length - 1);
  const list = $('#anDiff');
  const m = mks[Number(sel.value)];
  if (!m || !pk.length) { list.replaceChildren(h('li', { class: 'empty', text: mks.length ? 'Нет пакетов вокруг этой метки.' : 'Поставь метку в момент действия — здесь появятся байты, которые на неё отреагировали.' })); return; }
  const w = clamp(num($('#anWin').value, 3), 1, 30) * 1000;
  const before = pk.filter(e => e.t >= m.t - w && e.t < m.t), after = pk.filter(e => e.t > m.t && e.t <= m.t + w);
  if (!before.length || !after.length) { list.replaceChildren(h('li', { class: 'empty', text: 'Нужны пакеты и до, и после метки. Увеличь окно.' })); return; }
  const len = Math.min(64, ...pk.slice(-200).map(e => e.b.length));
  const res = [];
  for (const type of ['u8', 'u16le']) {
    for (let off = 0; off + TYPE_LEN[type] <= len; off++) {
      const mean = arr => { let s = 0, n = 0; for (const e of arr) { const v = readNum(e.b, off, type); if (v != null) { s += v; n++; } } return n ? s / n : null; };
      const a = mean(before), b = mean(after);
      if (a == null || b == null || a === b) continue;
      let mn = Infinity, mx = -Infinity;
      for (const e of pk) { const v = readNum(e.b, off, type); if (v != null) { if (v < mn) mn = v; if (v > mx) mx = v; } }
      res.push({ off, type, a, b, score: Math.abs(b - a) / Math.max(1, mx - mn) });
    }
  }
  res.sort((x, y) => y.score - x.score);
  const top = res.filter(r => r.score > 0.02).slice(0, 8);
  if (!top.length) { list.replaceChildren(h('li', { class: 'empty', text: 'Вокруг этой метки ничего заметно не поменялось.' })); return; }
  list.replaceChildren(...top.map(r => h('li', null, h('div', { class: 'row' },
    h('span', null, h('b', { text: 'байт ' + r.off }), ' ' + TYPE_LABELS[r.type] + ': ' + nf(r.a, 1) + ' → ' + nf(r.b, 1)),
    h('button', { class: 'btn btn-sm', type: 'button', text: 'Показать', onclick: () => { state.an.off = r.off; state.an.type = r.type; $('#anType').value = r.type; renderAnalyzer(true); } }),
    h('button', { class: 'btn btn-sm', type: 'button', text: '→ В декодер', onclick: () => toDecoder(r.off, r.type) })))));
}
function toDecoder(off, type) {
  const st = state.charStats.get(state.an.key) || state.chars.get(state.an.key);
  const u = st ? (st.u || st.uuid) : '';
  const f = mkField({ key: 'custom', name: 'Байт ' + off, uuid: u, match: state.an.pattern, offset: off, type });
  decoder.fields.push(f);
  saveDecoder(); renderDecoder(); rebuildTelemetry();
  showTab('decoder');
  const el = $('[data-id="' + f.id + '"] select');
  if (el) { el.focus(); el.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
}
$('#anChar').addEventListener('change', e => { state.an.key = e.target.value; state.an.off = null; renderAnalyzer(true); });
$('#anPat').addEventListener('input', e => { state.an.pattern = e.target.value; renderAnalyzer(true); });
$('#anType').addEventListener('change', e => { state.an.type = e.target.value; renderAnalyzer(true); });
$('#anMk').addEventListener('change', () => renderAnalyzer(true));
$('#anWin').addEventListener('input', () => renderAnalyzer(true));
$('#anToDecoder').addEventListener('click', () => { if (state.an.off != null) toDecoder(state.an.off, state.an.type); });
const pickCol = ev => {
  const th = ev.target.closest('th[data-off]');
  if (!th) return;
  if (ev.type === 'keydown' && ev.key !== 'Enter' && ev.key !== ' ') return;
  ev.preventDefault();
  const c = Number(th.dataset.off);
  state.an.off = state.an.off === c ? null : c;
  renderAnalyzer(true);
};
$('#anGrid').addEventListener('pointerdown', pickCol);
$('#anGrid').addEventListener('keydown', pickCol);

/* ============================================================
   write console
   ============================================================ */
function renderWriteCard() {
  $('#writeCard').hidden = !settings.allowWrite;
  const live = state.conn === 'connected';
  $('#wrSend').disabled = !live;
  renderExplorerButtons();
}
async function sendWrite() {
  const info = state.chars.get($('#wrChar').value);
  const msg = $('#wrMsg');
  if (!settings.allowWrite) return;
  if (!info || !info.obj || state.conn !== 'connected') { flash(msg, 'Нет подключения или не выбрана характеристика.', true); stopWriteRepeat(); return; }
  if (info.danger) { flash(msg, 'Это характеристика обновления прошивки. Писать в неё нельзя.', true); stopWriteRepeat(); return; }
  const bytes = hexToBytes($('#wrHex').value);
  if (!bytes || !bytes.length) { flash(msg, 'Введи байты в hex, например: 5A A5 01 3E 20 01 B0', true); stopWriteRepeat(); return; }
  try {
    const c = info.obj;
    if (info.props.wnr && c.writeValueWithoutResponse) await c.writeValueWithoutResponse(bytes);
    else if (c.writeValueWithResponse) await c.writeValueWithResponse(bytes);
    else await c.writeValue(bytes);
    pushEntry({ k: 'tx', s: info.service, u: info.uuid, b: bytes });
    flash(msg, 'Отправлено ' + bytes.length + ' байт в ' + shortUuid(info.uuid) + '.');
  } catch (e) { flash(msg, 'Не отправилось: ' + errText(e), true); stopWriteRepeat(); }
}
function stopWriteRepeat() { clearInterval(state.writeTimer); state.writeTimer = null; $('#wrRepeat').checked = false; }
$('#wrSend').addEventListener('click', sendWrite);
$('#wrRepeat').addEventListener('change', e => {
  clearInterval(state.writeTimer); state.writeTimer = null;
  if (e.target.checked) { const every = Math.max(200, num($('#wrEvery').value, 1000)); sendWrite(); state.writeTimer = setInterval(sendWrite, every); }
});

/* ============================================================
   decoder editor
   ============================================================ */
let decTimer = null;
function decoderChanged() {
  clearTimeout(decTimer);
  decTimer = setTimeout(() => { saveDecoder(); rebuildTelemetry(); $('#decText').value = decoderToJson(decoder); }, 250);
}
function charOptionList() {
  const m = new Map();
  for (const info of state.chars.values()) m.set(info.uuid, (CHR_NAMES[info.uuid] ? CHR_NAMES[info.uuid] + ' ' : '') + shortUuid(info.uuid));
  for (const st of state.charStats.values()) if (!m.has(st.u)) m.set(st.u, shortUuid(st.u));
  return m;
}
function renderDecoderCharOptions() {
  const opts = charOptionList();
  for (const sel of $$('#decFields select[data-k="uuid"]')) {
    const cur = sel.value;
    const list = new Map(opts);
    if (cur && !list.has(cur)) list.set(cur, shortUuid(cur));
    sel.replaceChildren(h('option', { value: '', text: 'любая' }), ...Array.from(list).map(([u, l]) => h('option', { value: u, text: l })));
    sel.value = cur;
  }
}
function renderDecoder() {
  $('#decJson').checked = !!decoder.json;
  $('#decNote').textContent = state.demo ? 'Сейчас работает демо-конфиг. Он не сохраняется.' : (decoder.fields.length ? 'Полей: ' + decoder.fields.length : '');
  const box = $('#decFields');
  if (!decoder.fields.length) {
    box.replaceChildren(h('p', { class: 'empty', text: 'Полей нет. Добавь первое кнопкой выше или из «Разбора байтов» кнопкой «→ В декодер».' }));
  } else {
    box.replaceChildren(...decoder.fields.map(f => {
      const inp = (k, attrs) => h('input', { 'data-k': k, value: f[k] == null ? '' : String(f[k]), autocomplete: 'off', ...attrs });
      const sel = (k, map) => { const s = h('select', { 'data-k': k }, Object.entries(map).map(([v, l]) => h('option', { value: v, text: l }))); s.value = f[k]; return s; };
      const uuidSel = h('select', { 'data-k': 'uuid' });
      const val = h('span', { class: 'fval', text: '—' });
      f._ui = val;
      return h('div', { class: 'field', 'data-id': f.id },
        h('div', { class: 'fgrid' },
          h('label', { class: 'f' }, 'Показатель', sel('key', METRICS)),
          h('label', { class: 'f' }, 'Название (для своих полей)', inp('name', { placeholder: 'например: флаг фары' }))),
        h('div', { class: 'fgrid' },
          h('label', { class: 'f' }, 'Характеристика', uuidSel),
          h('label', { class: 'f' }, 'Начало пакета', inp('match', { class: 'mono', placeholder: '5A A5 ?? 25 — пусто: любой' }))),
        h('div', { class: 'fgrid4' },
          h('label', { class: 'f' }, 'Номер байта', inp('offset', { type: 'number', min: 0, step: 1, inputmode: 'numeric' })),
          h('label', { class: 'f' }, 'Как читать', sel('type', TYPE_LABELS)),
          h('label', { class: 'f' }, 'Умножить на', inp('scale', { inputmode: 'decimal' })),
          h('label', { class: 'f' }, 'Прибавить', inp('add', { inputmode: 'decimal' }))),
        h('div', { class: 'fout' }, val, h('button', { class: 'btn btn-sm btn-danger', type: 'button', text: 'Удалить поле', 'data-del': f.id })));
    }));
    renderDecoderCharOptions();
    for (const f of decoder.fields) { const s = $('[data-id="' + f.id + '"] select[data-k="uuid"]'); if (s) { if (f.uuid && !Array.from(s.options).some(o => o.value === f.uuid)) s.append(h('option', { value: f.uuid, text: shortUuid(f.uuid) })); s.value = f.uuid || ''; } }
  }
  $('#decText').value = decoderToJson(decoder);
  renderDecoderValues();
  renderDecSource();
}
function renderDecoderValues() {
  for (const f of decoder.fields) {
    if (!f._ui) continue;
    if (f._pat === undefined) compileField(f);
    if (f._bad) { f._ui.className = 'fval bad'; f._ui.textContent = 'Ошибка в «Начале пакета»: байты в hex, ?? — любой'; continue; }
    f._ui.className = 'fval';
    const unit = { battery_pct: '%', voltage: 'В', current: 'А', power: 'Вт', speed_kmh: 'км/ч', speed_mph: 'mph', temp: '°C', odometer_km: 'км', odometer_mi: 'mi' }[f.key] || '';
    f._ui.textContent = f._last == null ? 'пока нет совпадений' : nf(f._last, Math.abs(f._last) < 100 && f._last % 1 ? 2 : 0) + (unit ? ' ' + unit : '');
  }
}
$('#decFields').addEventListener('input', ev => {
  const el = ev.target.closest('[data-k]'), card = ev.target.closest('[data-id]');
  if (!el || !card) return;
  const f = decoder.fields.find(x => x.id === card.dataset.id);
  if (!f) return;
  const k = el.dataset.k;
  if (k === 'offset') f.offset = Math.max(0, parseInt(el.value, 10) || 0);
  else if (k === 'scale') f.scale = num(el.value, 1);
  else if (k === 'add') f.add = num(el.value, 0);
  else f[k] = el.value;
  f._pat = undefined;
  decoderChanged();
});
$('#decFields').addEventListener('change', ev => { if (ev.target.matches('select')) ev.target.dispatchEvent(new Event('input', { bubbles: true })); });
$('#decFields').addEventListener('click', ev => {
  const id = ev.target.dataset && ev.target.dataset.del;
  if (!id) return;
  decoder.fields = decoder.fields.filter(f => f.id !== id);
  renderDecoder(); decoderChanged();
});
$('#decAdd').addEventListener('click', () => {
  const firstU = state.an.key ? (state.charStats.get(state.an.key) || {}).u || '' : '';
  decoder.fields.push(mkField({ uuid: firstU }));
  renderDecoder(); decoderChanged();
});
$('#decLoadDemo').addEventListener('click', () => {
  if (state.demo) { flash($('#decMsg'), 'Демо-конфиг уже работает.'); return; }
  decoder = DEMO_DECODER(); renderDecoder(); decoderChanged();
});
$('#decClear').addEventListener('click', ev => {
  const b = ev.currentTarget;
  if (b.dataset.armed !== '1') { b.dataset.armed = '1'; b.textContent = 'Точно удалить все?'; setTimeout(() => { b.dataset.armed = ''; b.textContent = 'Удалить все поля'; }, 3000); return; }
  b.dataset.armed = ''; b.textContent = 'Удалить все поля';
  decoder.fields = []; renderDecoder(); decoderChanged();
});
$('#decJson').addEventListener('change', e => { decoder.json = e.target.checked; decoderChanged(); });
$('#decCopy').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('#decText').value); flash($('#decMsg'), 'Конфиг скопирован.'); }
  catch { $('#decText').select(); flash($('#decMsg'), 'Выделил текст — скопируй вручную.'); }
});
$('#decApply').addEventListener('click', () => {
  let o;
  try { o = JSON.parse($('#decText').value); } catch { flash($('#decMsg'), 'Текст не разобран как JSON.', true); return; }
  const d = normalizeDecoder(o);
  if (!d) { flash($('#decMsg'), 'Нет списка fields — это не конфиг декодера.', true); return; }
  if (state.demo) stopDemo(false);
  decoder = d; saveDecoder(); renderDecoder(); rebuildTelemetry();
  flash($('#decMsg'), 'Применено полей: ' + d.fields.length + '.');
});

/* ============================================================
   decoder source: «мой» / «из репозитория», updates from decoders/current.json
   ============================================================ */
function setActiveSource(src) { decSrc.active = src; store.set('bal.decoder.active', src); }
/* The decoder for real data; while the demo runs it waits in savedDecoder. */
function setRealDecoder(d) {
  if (state.demo) state.savedDecoder = d;
  else { decoder = d; rebuildTelemetry(); }
  renderDecoder();
}
function useSource(src) {
  if (src === 'repo' && !repoDecoder()) return;
  setActiveSource(src);
  decSrc.forked = false;
  setRealDecoder(src === 'repo' ? repoDecoder() : mineDecoder());
}
/* > 0 when a is newer: by version (numbers compared part by part), then by updatedAt. */
function cmpDecVersion(a, b) {
  if (!a) return b ? -1 : 0;
  if (!b) return 1;
  const va = String(a.version ?? ''), vb = String(b.version ?? '');
  if (va !== vb && va && vb) {
    const pa = va.split('.').map(Number), pb = vb.split('.').map(Number);
    if (pa.concat(pb).every(Number.isFinite)) {
      for (let i = 0; i < Math.max(pa.length, pb.length); i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0);
    } else return va.localeCompare(vb, 'en', { numeric: true });
  } else if (va !== vb) return va ? 1 : -1;
  return (Date.parse(a.updatedAt) || 0) - (Date.parse(b.updatedAt) || 0);
}
function decLabel(d) {
  const date = Date.parse(d && d.updatedAt);
  return 'v' + (d && d.version != null ? d.version : '?') + (date ? ' от ' + new Date(date).toLocaleDateString('ru-RU') : '');
}
/* Newer than the repository decoder last applied. «Не сейчас» only hides the banner (dismissed). */
function remoteIsNewer(ignoreDismissed) {
  const r = decSrc.remote;
  if (!r || !(r.fields.length || r.json) || cmpDecVersion(r, decSrc.repo) <= 0) return false;
  return ignoreDismissed || cmpDecVersion(r, store.get('bal.decoder.dismissed', null)) > 0;
}
async function checkDecoderUpdate() {
  decSrc.checkedAt = Date.now();
  try {
    const r = await fetch('decoders/current.json', { cache: 'no-cache' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const o = await r.json();
    const d = normalizeDecoder(o);
    if (!d) throw new Error('в файле нет списка fields');
    decSrc.remote = { ...o, fields: o.fields, json: !!o.json };
    decSrc.fetchErr = null;
  } catch (e) {
    decSrc.remote = null;
    decSrc.fetchErr = location.protocol === 'file:' ? 'страница открыта из файла' : (e && e.message) || String(e);
  }
  renderDecSource();
}
function applyRemote() {
  const r = decSrc.remote;
  if (!r) return;
  decSrc.repo = r;
  store.set('bal.decoder.repo', r);
  useSource('repo');
  flash($('#decRepoText'), 'Применён декодер из репозитория ' + decLabel(r) + (state.demo ? '. На приборах он заработает, когда выключишь демо.' : '.'));
}
function dismissRemote() {
  const r = decSrc.remote;
  if (r) store.set('bal.decoder.dismissed', { version: r.version, updatedAt: r.updatedAt });
  renderDecSource();
}
function restoreBackup() {
  const b = store.get('bal.decoder.backup', null);
  if (!b || !b.decoder) return;
  const cur = store.get('bal.decoder', null), curN = normalizeDecoder(cur);
  store.set('bal.decoder', b.decoder);
  if (curN && curN.fields.length) store.set('bal.decoder.backup', { savedAt: new Date().toISOString(), decoder: cur });
  else store.del('bal.decoder.backup');
  useSource('mine');
  flash($('#decRepoText'), 'Вернул прежний «мой» декодер.' + (curN && curN.fields.length ? ' Тот, что был, теперь в резервной копии.' : ''));
}
function renderDecSource() {
  const real = state.demo ? state.savedDecoder : decoder;
  const offer = remoteIsNewer(false), newer = remoteIsNewer(true), r = decSrc.remote, mine = mineDecoder();
  /* banner above all tabs */
  $('#decUpd').hidden = !offer;
  if (offer) {
    $('#decUpdText').textContent = 'В репозитории новый декодер ' + decLabel(r) + ', полей: ' + r.fields.length + (r.note ? ' — ' + r.note : '') + '.'
      + (decSrc.active === 'mine' && mine.fields.length ? ' Твой декодер сохранится: вернуться к нему можно в разделе «Декодер».' : '');
  }
  /* decoder tab */
  for (const b of $$('#decSrcSeg button')) {
    b.setAttribute('aria-pressed', b.dataset.src === decSrc.active ? 'true' : 'false');
    if (b.dataset.src === 'repo') { b.disabled = !decSrc.repo; b.textContent = 'Из репозитория' + (decSrc.repo ? ' v' + decSrc.repo.version : ''); }
  }
  const n = real ? real.fields.length : 0;
  $('#decSrcText').textContent = (state.demo ? 'Сейчас демо: на приборах демо-конфиг, выбранный декодер включится после демо. ' : '')
    + (decSrc.active === 'repo'
      ? 'Работает декодер из репозитория ' + decLabel(decSrc.repo) + (decSrc.repo.note ? ': ' + decSrc.repo.note : '') + '. Если поправить любое поле, он станет «моим», а этот останется в репозитории.'
      : 'Работает твой декодер' + (n ? ', полей: ' + n : ', пока пустой') + '.' + (decSrc.forked ? ' Он сделан правкой декодера из репозитория.' : ''));
  const rt = $('#decRepoText');
  if (newer) flash(rt, 'В репозитории новее: ' + decLabel(r) + ', полей ' + r.fields.length + (r.note ? ' — ' + r.note : '') + '.');
  else if (r) flash(rt, 'В репозитории ' + decLabel(r) + (r.fields.length || r.json ? '' : ', полей пока нет') + ' — новее нет.');
  else if (decSrc.fetchErr) flash(rt, 'Не получилось проверить декодер в репозитории: ' + decSrc.fetchErr + '.');
  const ap = $('#decRepoApply');
  ap.hidden = !newer;
  if (newer) ap.textContent = 'Применить ' + decLabel(r);
  const bk = store.get('bal.decoder.backup', null);
  $('#decBackup').hidden = !(bk && bk.decoder);
}
$('#decUpdApply').addEventListener('click', applyRemote);
$('#decRepoApply').addEventListener('click', applyRemote);
$('#decUpdLater').addEventListener('click', dismissRemote);
$('#decBackup').addEventListener('click', restoreBackup);
for (const b of $$('#decSrcSeg button')) b.addEventListener('click', () => { if (b.dataset.src !== decSrc.active) useSource(b.dataset.src); });
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && Date.now() - (decSrc.checkedAt || 0) > 10 * 60 * 1000) checkDecoderUpdate();
});

/* ============================================================
   markers UI
   ============================================================ */
const MARKERS = ['Газ', 'Отпустил газ', 'Тормоз', 'Стоп', 'Сменил режим', 'Фара', 'Зарядка вкл', 'Зарядка выкл'];
for (const box of $$('[data-markers]')) {
  const input = h('input', { type: 'text', placeholder: 'своя метка', 'aria-label': 'Своя метка', maxlength: 60 });
  const addCustom = () => { const v = input.value.trim(); if (v) { addMarker(v); input.value = ''; } };
  input.addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); addCustom(); } });
  box.append(...MARKERS.map(m => h('button', { class: 'btn btn-sm', type: 'button', text: m, onclick: () => addMarker(m) })), input, h('button', { class: 'btn btn-sm', type: 'button', text: 'Добавить', onclick: addCustom }));
}

/* ============================================================
   settings
   ============================================================ */
function bindSettings() {
  const u = $('#setUuids');
  u.value = settings.extraUuids;
  const checkUuids = () => {
    const bad = String(u.value).split(/\n+/).map(s => s.trim()).filter(s => s && !normUuid(s));
    flash($('#setUuidsMsg'), bad.length ? 'Не похоже на UUID: ' + bad.slice(0, 3).join(', ') : (u.value.trim() ? 'Будут запрошены при следующем подключении.' : ''), bad.length > 0);
  };
  checkUuids();
  u.addEventListener('input', () => { settings.extraUuids = u.value; saveSettings(); checkUuids(); });
  $('#setName').value = settings.namePrefix;
  $('#setName').addEventListener('input', e => { settings.namePrefix = e.target.value.trim(); saveSettings(); });
  $('#setUnits').value = settings.units;
  $('#setUnits').addEventListener('change', e => { settings.units = e.target.value; saveSettings(); requestRender(true); });
  const bool = (id, key, after) => { const el = $(id); el.checked = !!settings[key]; el.addEventListener('change', () => { settings[key] = el.checked; saveSettings(); if (after) after(); }); };
  bool('#setSub', 'autoSubscribe');
  bool('#setReconnect', 'autoReconnect');
  bool('#setWake', 'wakeLock', () => { if (!settings.wakeLock) releaseWakeLock(); else if (state.conn === 'connected') acquireWakeLock(); });
  bool('#setWrite', 'allowWrite', () => { if (!settings.allowWrite) stopWriteRepeat(); renderWriteCard(); });
  $('#logCollapse').checked = settings.collapse;
  $('#logCollapse').addEventListener('change', e => { settings.collapse = e.target.checked; saveSettings(); renderLog(); });
  $('#logFilter').addEventListener('change', () => renderLog());
}

/* ============================================================
   tabs, chart controls, buttons
   ============================================================ */
const TABS = ['dash', 'data', 'decoder', 'guide'];
function showTab(id) {
  if (!TABS.includes(id)) id = 'dash';
  state.tab = id;
  for (const t of TABS) {
    $('#v-' + t).hidden = t !== id;
    $('#t-' + t).setAttribute('aria-selected', t === id ? 'true' : 'false');
  }
  store.set('bal.tab', id);
  if (id === 'guide') renderChecks();
  if (id === 'decoder') renderDecoder();
  if (id === 'data') { renderExplorer(); renderCharSelects(); }
  requestRender(true);
}
for (const t of $$('.tab')) t.addEventListener('click', () => showTab(t.dataset.tab));
$('.tabs').addEventListener('keydown', e => {
  if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
  const i = TABS.indexOf(state.tab), n = (i + (e.key === 'ArrowRight' ? 1 : TABS.length - 1)) % TABS.length;
  showTab(TABS[n]); $('#t-' + TABS[n]).focus();
});
function syncWinSeg() { for (const b of $$('#winSeg button')) b.setAttribute('aria-pressed', b.dataset.win === state.win ? 'true' : 'false'); }
for (const b of $$('#winSeg button')) b.addEventListener('click', () => { state.win = b.dataset.win; syncWinSeg(); state.hoverT = null; requestRender(true); });
$('#btnTable').addEventListener('click', e => {
  state.showTable = !state.showTable;
  e.currentTarget.setAttribute('aria-pressed', state.showTable ? 'true' : 'false');
  $('#chartTableWrap').hidden = !state.showTable;
  if (state.showTable) renderChartTable();
});
$('#btnConnect').addEventListener('click', connect);
$('#btnDisconnect').addEventListener('click', disconnect);
$('#btnDemoStop').addEventListener('click', () => stopDemo(false));
$('#btnDemoStart').addEventListener('click', startDemo);
$('#btnExport').addEventListener('click', exportLog);
$('#btnCopyLog').addEventListener('click', copyLog);
$('#btnClear').addEventListener('click', ev => {
  const b = ev.currentTarget;
  if (b.dataset.armed !== '1') { b.dataset.armed = '1'; b.textContent = 'Точно очистить?'; setTimeout(() => { b.dataset.armed = ''; b.textContent = 'Очистить'; }, 3000); return; }
  b.dataset.armed = ''; b.textContent = 'Очистить';
  clearLog();
});
$('#fileImport').addEventListener('change', e => { const fs = Array.from(e.target.files || []); if (fs.length) importLogs(fs); e.target.value = ''; });
window.addEventListener('beforeunload', e => {
  const S = state.session;
  if (S && !S.demo && !S.offline && !S.exported && S.entries.filter(x => x.k === 'rx').length > 50) { e.preventDefault(); e.returnValue = ''; }
});

/* ============================================================
   browser checks
   ============================================================ */
async function renderChecks() {
  const box = $('#checks');
  const row = (ok, text, detail) => h('div', null, h('span', { class: ok === true ? 'ok' : ok === false ? 'no' : 'na', text: ok === true ? '✓' : ok === false ? '✗' : '?' }), h('span', null, text, detail ? h('span', { class: 'empty', text: ' — ' + detail }) : ''));
  const secure = window.isSecureContext;
  const api = !!(navigator.bluetooth && navigator.bluetooth.requestDevice);
  let avail = null;
  if (api && navigator.bluetooth.getAvailability) { try { avail = await navigator.bluetooth.getAvailability(); } catch { avail = null; } }
  const framed = (() => { try { return window.self !== window.top; } catch { return true; } })();
  box.replaceChildren(
    row(secure, 'Защищённый адрес (https или localhost)', secure ? location.protocol + '//' + location.host : 'открыто как ' + location.protocol + ' — Chrome не даст Bluetooth'),
    row(api, 'Web Bluetooth в этом браузере', api ? '' : 'нужен Chrome на Android или Chrome/Edge на компьютере'),
    row(avail, 'Bluetooth-адаптер', avail === true ? 'есть' : avail === false ? 'не найден или выключен' : 'браузер не сообщает'),
    row(!framed, 'Страница открыта напрямую', framed ? 'она внутри другой страницы — там Bluetooth обычно запрещён' : ''),
  );
}

/* ============================================================
   boot
   ============================================================ */
(() => { const b = $('#build'), v = b.dataset.build || ''; b.textContent = v && !v.startsWith('__') ? 'сборка ' + v : 'локальная копия'; })();
bindSettings();
bindGitHub();
syncWinSeg();
const initialTab = (location.hash || '').slice(1);
showTab(TABS.includes(initialTab) ? initialTab : store.get('bal.tab', 'dash'));
if (!bleSupported()) setConn('unsupported'); else setConn('idle');
startDemo();
if (!bleSupported()) { const pill = $('#connPill'); pill.dataset.tone = 'crit'; $('.txt', pill).textContent = 'Демо · Bluetooth здесь недоступен'; $('.ico', pill).textContent = '⚠'; }
renderDecoder();
checkDecoderUpdate();
requestRender(true);
window.__bal = { state, get decoder() { return decoder; }, pushEntry, startDemo, stopDemo, rebuildTelemetry, sessionObject, anonymizeLog, packLog, ghLimits: GH, decSrc, checkDecoderUpdate };
})();
