// Controller frames relayed by the dashboard over FFE1 («:» addr cmd len data sum16 CR LF), the decoder v3
// that reads them, waking the silent dashboard and the KEY? block. Replays real chunks from
// logs/20261014-174148-log.json through the fake dashboard in fake-bluetooth.js.
const { test, expect, openApp, entries, uuid16 } = require('./helpers');
const { LOG_PATH, V3, V3_STORAGE, realDash, chunk, frame, hx, hexOf, voltData } = require('./real-dash');

const FFE1 = uuid16('ffe1');
const SVGT = hexOf('+SVGT=?');
const writesTo = (page, u = FFE1) => page.evaluate(uu => window.__fakeBle.calls.filter(c => c[0] === 'writeData' && c[1] === uu).map(c => c[2]), u);
const tel = (page, k) => page.evaluate(key => { const x = window.__bal.state.tel.vals.get(key); return x ? x.v : null; }, k);
const frames = page => page.evaluate(() => { const F = window.__bal.state.tel.frames; return { total: F.total, bad: F.bad, types: Array.from(F.types.keys()).sort() }; });
const inject = (page, chunks) => page.evaluate(cs => { for (const c of cs) window.__fakeBle.notify('ffe1', c); }, chunks);
const noSideScroll = page => page.evaluate(() => {
  const d = document.documentElement;
  return { scroll: d.scrollWidth, client: d.clientWidth, body: document.body.scrollWidth };
});

async function connect(page, dash) {
  if (dash) await page.evaluate(d => { window.__fakeBle.dash = d; }, dash);
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}

test('a mode tap starts the stream: voltage, current, SoC and mode on the gauges', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'control', storage: { 'bal.settings': { allowWrite: true }, ...V3_STORAGE } });
  await connect(page, realDash());
  await expect(page.locator('#ctlModes .bigbtn[data-mode="1"]')).toHaveAttribute('aria-pressed', 'false');
  await page.click('#ctlModes .bigbtn[data-mode="1"]');
  await expect(page.locator('#ctlMsg')).toContainText('Руль подтвердил');      // the dashboard echoed «+MODE=1»
  await expect(page.locator('#ctlModes .bigbtn[data-mode="1"]')).toHaveAttribute('aria-pressed', 'true');   // mode from frames
  await expect(page.locator('#ctlModes .bigbtn[data-mode="3"]')).toHaveAttribute('aria-pressed', 'false');
  expect(await writesTo(page)).toEqual([hexOf('+MODE=1')]);
  await page.click('#ctlVer');
  await expect(page.locator('#ctlProbeMsg')).toContainText('Руль ответил: +SVGT=0');

  await page.click('#t-dash');
  await expect(page.locator('#mVoltage .val')).toHaveText('39,8В');
  await expect(page.locator('#batVal')).toHaveText('78%');                      // estimated from 39,85 V
  await expect(page.locator('#batSub')).toContainText('оценка по напряжению');
  await expect(page.locator('#modeName')).toContainText('TAKEOFF');
  await expect(page.locator('#mCurrent .val')).toHaveText(/^0,\dА$/);
  await expect(page.locator('#pwrVal')).toHaveText(/^\d+Вт$/);
  await expect(page.locator('#spdVal')).toHaveText('—');                         // no speed field yet
  await expect(page.locator('#mOdo .val')).toHaveText('—');
  await expect(page.locator('#rawBanner')).toBeHidden();
  await expect(page.locator('#wakeBanner')).toBeHidden();
  const v = await tel(page, 'voltage');
  expect(v).toBeGreaterThan(39.84);
  expect(v).toBeLessThan(39.86);
  expect(await tel(page, 'mode')).toBe(1);
  // frames are derived: the log keeps the raw notifications
  const rx = await entries(page, { k: 'rx', u: FFE1 });
  expect(rx.some(e => e.h === '0A 2B 4D 4F 44 45 3D 31')).toBe(true);
  expect(rx.every(e => e.h.split(' ').length <= 20)).toBe(true);
});

test('a frame that lost its 0x3A right after CR LF is recovered, anywhere else it is not', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });
  await connect(page);
  // log entries 78–80: «3A 17 … 0D», «0A 18 20 10 …» (the 0x3A is gone), «42 05 0D 0A»
  await inject(page, [chunk(78), chunk(79), chunk(80)]);
  await expect.poll(() => frames(page)).toEqual({ total: 2, bad: 0, types: ['17 00 0D', '18 20 10'] });
  expect(await tel(page, 'voltage')).toBeCloseTo(39.848, 3);
  expect(await tel(page, 'mode')).toBe(2);
  // the same kind of frame after junk instead of CR LF: not accepted
  const lost = frame(0x18, 0x20, voltData(39900)).slice(1);
  await inject(page, [[0x5A, 0x5A], hx(lost.slice(0, 20)), hx(lost.slice(20))]);
  const good = frame(0x18, 0x20, voltData(39700));
  await inject(page, [hx(good.slice(0, 20)), hx(good.slice(20))]);
  await expect.poll(() => tel(page, 'voltage')).toBeCloseTo(39.7, 3);
  expect((await frames(page)).total).toBe(3);
});

test('text and junk between frames do not break decoding', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE, tab: 'data' });
  await connect(page);
  // at the stream start: «OK OK OK », «+SVGT=0», stray colons; then entries 68–70 of the log:
  // a frame end, «0A» + «+MODE=2» + the start of a 3A 18 frame in one notification, the rest of it
  await inject(page, [hexOf('OK OK OK '), hexOf('+SVGT=0'), '3A 3A 3A', chunk(68), chunk(69), chunk(70)]);
  await expect.poll(() => frames(page)).toEqual({ total: 2, bad: 0, types: ['17 00 0D', '18 20 10'] });
  expect(await tel(page, 'voltage')).toBeCloseTo(39.850, 3);
  // a long run of junk is capped and the next frame still decodes
  await inject(page, Array.from({ length: 60 }, () => hexOf('AT+SETNAME=xxxx\r\n')));
  const good = frame(0x18, 0x20, voltData(39500));
  await inject(page, [hx(good.slice(0, 20)), hx(good.slice(20))]);
  await expect.poll(() => tel(page, 'voltage')).toBeCloseTo(39.5, 3);
  expect(await page.evaluate(() => Math.max(...Array.from(window.__bal.state.tel.frames.asm.values(), a => a.buf.length)))).toBeLessThan(700);
});

test('a frame with a wrong checksum is ignored and counted', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE, tab: 'data' });
  await connect(page);
  const bad = frame(0x18, 0x20, voltData(39100), { badSum: true });
  await inject(page, [hx(bad.slice(0, 20)), hx(bad.slice(20))]);
  await expect.poll(() => frames(page)).toEqual({ total: 0, bad: 1, types: ['18 20 10'] });
  expect(await tel(page, 'voltage')).toBeNull();
  await expect(page.locator('#frameCard')).toBeVisible();
  await expect(page.locator('#frameNote')).toContainText('сбоев суммы: 1');
  const good = frame(0x18, 0x20, voltData(39200));
  await inject(page, [hx(good.slice(0, 20)), hx(good.slice(20))]);
  await expect.poll(() => tel(page, 'voltage')).toBeCloseTo(39.2, 3);
  await expect(page.locator('.frm[data-type="18 20 10"]')).toContainText('сбоев суммы: 1');
});

test('silent dashboard: the wake banner sends exactly +SVGT=? and hides when frames arrive', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });   // «Разрешить отправку» stays off
  await connect(page, realDash());
  await page.waitForTimeout(1000);
  await expect(page.locator('#wakeBanner')).toBeHidden();                   // not before ~3 s
  await expect(page.locator('#wakeBanner')).toBeVisible({ timeout: 6000 });
  await expect(page.locator('#wakeText')).toHaveText('Руль молчит: данные он начинает слать после команды с телефона.');
  await expect(page.locator('#rawBanner')).toBeHidden();
  for (const spacing of [null, '0.08em']) {
    if (spacing) await page.addStyleTag({ content: `* { letter-spacing: ${spacing} !important; }` });
    const m = await noSideScroll(page);
    expect(m.scroll).toBeLessThanOrEqual(m.client);
    expect(m.body).toBeLessThanOrEqual(m.client);
  }
  expect(await writesTo(page)).toEqual([]);
  await page.click('#btnWake');
  await expect(page.locator('#wakeBanner')).toBeHidden();
  expect(await writesTo(page)).toEqual([SVGT]);
  expect((await entries(page, { k: 'tx' })).map(e => e.h)).toEqual([SVGT]);
  await expect(page.locator('#mVoltage .val')).toHaveText('39,8В');
  await expect(page.locator('#modeName')).toContainText('TAKEOFF');
});

test('frames with an empty decoder: the hint points to the repository decoder instead of «not decoded»', async ({ page }) => {
  await openApp(page, { bluetooth: true });
  await expect(page.locator('#decUpd')).toBeVisible();                     // v3 from decoders/current.json
  await connect(page, realDash());
  await page.evaluate(() => window.__bal.sendWake(false));
  await expect(page.locator('#rawBanner')).toBeVisible();
  await expect(page.locator('#rawText')).toContainText('кадров контроллера');
  await expect(page.locator('#rawText')).toContainText('В репозитории есть декодер v' + V3.version);
  await expect(page.locator('#rawText')).not.toContainText('не расшифрован');
  await expect(page.locator('#mVoltage .val')).toHaveText('—');
  await page.click('#decUpdApply');                                          // applying it fills the gauges at once
  await expect(page.locator('#rawBanner')).toBeHidden();
  await expect(page.locator('#mVoltage .val')).toHaveText('39,8В');
});

test('when +SVGT=? does not wake it, the banner says to press the mode', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });
  await page.evaluate(() => { Object.assign(window.__bal.wakeLimits, { silent: 600, retry: 600 }); });
  await connect(page, realDash());
  await page.evaluate(() => { window.__fakeBle.dash.wakes = t => !t.startsWith('+SVGT'); });
  await expect(page.locator('#wakeBanner')).toBeVisible();
  await page.click('#btnWake');
  await expect(page.locator('#wakeMsg')).toContainText('Руль ответил: +SVGT=0');
  await expect(page.locator('#wakeText')).toContainText('Не помогло. Открой «Управление» и нажми свой текущий режим');
  expect(await writesTo(page)).toEqual([SVGT]);
});

test('«Запрашивать сам» sends +SVGT=? once per connect, only while no frames come', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: { 'bal.settings': { autoWake: true }, ...V3_STORAGE } });
  await connect(page, realDash());
  await expect.poll(() => writesTo(page)).toEqual([SVGT]);
  await expect.poll(() => tel(page, 'voltage')).not.toBeNull();
  await page.waitForTimeout(1500);
  expect(await writesTo(page)).toEqual([SVGT]);
  await expect(page.locator('#wakeBanner')).toBeHidden();
  await page.evaluate(() => window.__fakeBle.drop());                      // a reconnect needs a new wake
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 10000 });
  await expect.poll(() => writesTo(page)).toEqual([SVGT, SVGT]);
  // the next reconnect finds the stream already running: nothing is sent
  await page.evaluate(() => window.__fakeBle.drop());
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 10000 });
  await inject(page, [chunk(8), chunk(9)]);
  await page.waitForTimeout(1500);
  expect(await writesTo(page)).toEqual([SVGT, SVGT]);
  await expect(page.locator('#setAutoWake')).toBeChecked();
});

test('KEY? is blocked in the write console, its repeat and the command API', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'data', storage: { 'bal.settings': { allowWrite: true } } });
  await connect(page, realDash());
  for (const v of ['AE 4B 45 59 3F D6', 'ae4b4559', 'AE 4B 45 59 0A 2D', '2B AE 4B 45 59 3F D6']) {
    await page.fill('#wrHex', v);
    await page.click('#wrSend');
    await expect(page.locator('#wrMsg')).toHaveText('Заблокировано: KEY? останавливает связь руля с контроллером (ошибка 37).');
  }
  await page.fill('#wrHex', 'AE 4B 45 59 3F D6');
  await page.click('#wrRepeat');
  await expect(page.locator('#wrRepeat')).not.toBeChecked();
  await expect(page.locator('#wrMsg')).toContainText('Заблокировано');
  const api = await page.evaluate(() => window.__bal.sendBleCommand(new Uint8Array([0xAE, 0x4B, 0x45, 0x59, 0x3F, 0xD6]), 'тест'));
  expect(api).toBe(false);
  await page.waitForTimeout(500);
  expect(await page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'writeData'))).toEqual([]);
  expect(await entries(page, { k: 'tx' })).toEqual([]);
  // a documented command still goes through the console
  await page.fill('#wrHex', SVGT);
  await page.click('#wrSend');
  await expect.poll(() => writesTo(page)).toEqual([SVGT]);
});

test('frame card: types, rates, changing bytes; fits 390 px', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'data', storage: { 'bal.settings': { allowWrite: true }, ...V3_STORAGE } });
  await expect(page.locator('#frameCard')).toBeHidden();                    // the demo has no frames
  await connect(page, realDash());
  await page.evaluate(() => window.__bal.sendWake(false));
  await expect(page.locator('#frameCard')).toBeVisible();
  await expect(page.locator('.frm')).toHaveCount(2);
  const volt = page.locator('.frm[data-type="18 20 10"]');
  await expect(volt.locator('code')).toHaveText('3A 18 20 10');
  await expect(volt.locator('.frm-bytes .b')).toHaveCount(16);
  await expect(volt.locator('.frm-bytes .b.chg').first()).toBeVisible({ timeout: 5000 });   // the voltage low byte moves
  await expect(page.locator('.frm[data-type="17 00 0D"] .frm-head')).toContainText(/\d+,\d в с/);
  for (const spacing of [null, '0.08em']) {
    if (spacing) await page.addStyleTag({ content: `* { letter-spacing: ${spacing} !important; }` });
    await page.waitForTimeout(600);
    const m = await noSideScroll(page);
    expect(m.scroll).toBeLessThanOrEqual(m.client);
    expect(m.body).toBeLessThanOrEqual(m.client);
  }
  await volt.locator('button', { hasText: 'Разобрать байты' }).click();
  await expect(page.locator('#anPat')).toHaveValue('3A 18 20 10');
  await expect(page.locator('#anMsg')).toContainText('Пакетов под фильтр');
});

test('the real log with decoder v3 applied from the repository', async ({ page }) => {
  await openApp(page);
  await expect(page.locator('#decUpd')).toBeVisible();
  await expect(page.locator('#decUpdText')).toContainText('v' + V3.version);
  await page.click('#decUpdApply');
  await page.setInputFiles('#fileImport', LOG_PATH);
  await expect(page.locator('#connPill')).toContainText('Лог из файла');
  await expect(page.locator('#mVoltage .val')).toHaveText('39,8В');
  await expect(page.locator('#batVal')).toHaveText('78%');
  await expect(page.locator('#modeName')).toContainText('TAKEOFF');        // the last mode tapped was +MODE=1
  await expect(page.locator('#spdVal')).toHaveText('—');
  expect(await tel(page, 'mode')).toBe(1);
  // every frame of the log: 268 complete ones and 16 that lost their 0x3A
  expect(await frames(page)).toEqual({ total: 284, bad: 0, types: ['17 00 0D', '18 20 10'] });
  await page.click('#t-data');
  await expect(page.locator('#frameNote')).toContainText('кадров: 284');
  // a rebuild (decoder edit) gives the same frames: assemblers start over
  await page.evaluate(() => window.__bal.rebuildTelemetry());
  expect((await frames(page)).total).toBe(284);
});

test('decoder format: framing and mask through the editor, text config, backups and storage; old decoders unchanged', async ({ page }) => {
  await openApp(page, { tab: 'decoder' });
  await page.evaluate(() => window.__bal.stopDemo(false));
  await page.click('#t-decoder');
  // an old decoder serializes exactly as before
  const old = { json: false, fields: [{ key: 'speed_kmh', uuid: 'b1a1', match: '5A A5', offset: 9, type: 'u16le', scale: 0.1 }] };
  const oldJson = await page.evaluate(o => window.__bal.decoderToJson(window.__bal.normalizeDecoder(o)), old);
  expect(JSON.parse(oldJson)).toEqual({ json: false, fields: [{ key: 'speed_kmh', uuid: uuid16('b1a1'), match: '5A A5', offset: 9, type: 'u16le', scale: 0.1 }] });
  expect(oldJson).not.toContain('framing');
  expect(oldJson).not.toContain('mask');
  // v3 through «Конфиг текстом»
  const ls = k => page.evaluate(key => JSON.parse(localStorage.getItem(key)), k);
  await page.locator('#v-decoder details summary').click();
  await page.fill('#decText', JSON.stringify(V3));
  await page.click('#decApply');
  await expect(page.locator('#decFraming')).toBeChecked();
  await expect(page.locator('#decFields [data-k="mask"]').first()).toHaveValue('0x03');
  await expect(page.locator('#decText')).toHaveValue(/"framing": "colon-sum16"/);
  const saved = await ls('bal.decoder');
  expect(saved.framing).toBe('colon-sum16');
  expect(saved.fields[0].mask).toBe(3);
  expect(saved.fields.slice(1).every(f => !('mask' in f))).toBe(true);
  // replacing it keeps v3 in the backups, with framing and mask; restoring brings them back
  await page.fill('#decText', JSON.stringify(old));
  await page.click('#decApply');
  await expect(page.locator('#decFraming')).not.toBeChecked();
  const backups = await ls('bal.decoder.backups');
  expect(backups[0].decoder.framing).toBe('colon-sum16');
  expect(backups[0].decoder.fields[0].mask).toBe(3);
  await page.click('#decBackup');
  await expect(page.locator('#decFraming')).toBeChecked();
  await expect(page.locator('#decFields [data-k="mask"]').first()).toHaveValue('0x03');
  // the mask is applied: bits 2–3 of 0x0B = 2 (framing off: the raw packet is read)
  await page.locator('#decFields [data-k="mask"]').first().fill('0b1100');
  await page.locator('#decFraming').uncheck();
  await expect.poll(async () => (await ls('bal.decoder')).fields[0].mask).toBe(12);
  expect('framing' in await ls('bal.decoder')).toBe(false);
  await page.evaluate(u => window.__bal.pushEntry({ k: 'rx', s: '0000ffe0-0000-1000-8000-00805f9b34fb', u, h: '3A 17 00 0D 00 00 00 00 00 0B 00 00 00 37' }), uuid16('ffe1'));
  expect(await tel(page, 'mode')).toBe(2);
  await page.locator('#decFields [data-k="mask"]').first().fill('нет');
  await expect(page.locator('#decFields .fval').first()).toContainText('Маска — число');
});

test('a CR LF that ends a notification still lets a frame without its 0x3A in (same as tools/uart_frames.py)', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });
  await connect(page);
  // the streams of test_crlf_at_a_chunk_end_still_lets_a_lost_colon_frame_in in tests/test_uart_frames.py
  const bad = frame(0x18, 0x20, voltData(39100), { badSum: true }), good = frame(0x18, 0x20, voltData(39854));
  const stream = [...bad, ...good.slice(1)];
  const got = await page.evaluate(s => {
    const F = window.__bal.state.tel.frames, out = [];
    for (let i = 1; i < s.length; i++) {
      window.__bal.pushEntry({ k: 'sys', n: 'Найдено сервисов: 2, характеристик: 5' });   // a fresh stream for each split
      const n = F.total;
      window.__fakeBle.notify('ffe1', s.slice(0, i));
      window.__fakeBle.notify('ffe1', s.slice(i));
      out.push(F.total - n);
    }
    return out;
  }, stream);
  expect(got).toEqual(Array(stream.length - 1).fill(1));
  expect(await tel(page, 'voltage')).toBeCloseTo(39.854, 3);
  // text ending in CR LF, then a frame without its 0x3A, in one notification
  const mode = frame(0x17, 0x00, [0, 0, 0, 0, 0, 0x0A, 0, 0, 0, 0x37, 0xA8, 0x01, 0x01]), volt = frame(0x18, 0x20, voltData(39600));
  const total = (await frames(page)).total;
  await inject(page, [[...mode, ...Array.from(Buffer.from('+SVGT=0\r\n')), ...volt.slice(1), ...mode]]);
  await expect.poll(async () => (await frames(page)).total).toBe(total + 3);
  expect(await tel(page, 'voltage')).toBeCloseTo(39.6, 3);
});

test('KEY? split over two writes or made by a repeat is blocked; hex with a Cyrillic letter is refused', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'data', storage: { 'bal.settings': { allowWrite: true } } });
  await connect(page, realDash());
  const KEY_MSG = 'Заблокировано: KEY? останавливает связь руля с контроллером (ошибка 37).';
  const send = async (v, text, exact = false) => {
    await page.fill('#wrHex', v);
    await page.click('#wrSend');
    if (exact) await expect(page.locator('#wrMsg')).toHaveText(text);
    else await expect(page.locator('#wrMsg')).toContainText(text);
  };
  await send('AE', 'Отправлено 1 байт в 0xFFE1: AE.');
  await send('4B 45 59 3F D6', KEY_MSG, true);                                 // AE + 4B 45 59 … on the UART
  expect(await page.evaluate(() => window.__bal.sendBleCommand(new Uint8Array([0x4B, 0x45, 0x59, 0x3F, 0xD6]), 'тест'))).toBe(false);
  await send(SVGT, 'Отправлено 7 байт в 0xFFE1: ' + SVGT + '.');
  await send('4B 45 59', 'Отправлено 3 байт');                                  // harmless after «+SVGT=?»
  // a rotated KEY?: twice in a row it holds AE 4B 45 59 3F D6
  await page.fill('#wrHex', '59 3F D6 AE 4B 45');
  await page.click('#wrRepeat');
  await expect(page.locator('#wrRepeat')).not.toBeChecked();
  await expect(page.locator('#wrMsg')).toHaveText(KEY_MSG);
  await send('59 3F D6 AE 4B 45', 'Отправлено 6 байт');                         // once is fine…
  await send('59 3F D6 AE 4B 45', KEY_MSG, true);                               // …the second time is not
  // strict hex: nothing is silently dropped
  await send('2B 4D 4F 44 45 3D 3С', 'Не hex: «С» в «3С» — проверь раскладку.', true);   // Cyrillic С
  await send('A0x5 12', 'Не hex: «x»');
  await send('2B 4D 4', 'не хватает цифры');
  await send('0x2B,0x53 5647543D3F', 'Отправлено 7 байт в 0xFFE1: ' + SVGT + '.');
  expect(await writesTo(page)).toEqual(['AE', SVGT, '4B 45 59', '59 3F D6 AE 4B 45', SVGT]);
  expect((await entries(page, { k: 'tx' })).map(e => e.h)).toEqual(['AE', SVGT, '4B 45 59', '59 3F D6 AE 4B 45', SVGT]);
});

test('a JSON board streaming on FFE1 is not a silent dashboard: no wake banner, no +SVGT=?', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: { 'bal.decoder': { json: true, fields: [] }, 'bal.settings': { autoWake: true } } });
  await page.evaluate(() => {
    Object.assign(window.__bal.wakeLimits, { silent: 800, retry: 800 });
    const line = Array.from(new TextEncoder().encode('{"v":38.2,"i":-2.1,"spd":18.5,"soc":64}\n'));
    setInterval(() => window.__fakeBle.notify('ffe1', line), 100);             // sends nothing until connected
  });
  await connect(page);
  await expect.poll(() => tel(page, 'speed_kmh')).toBeCloseTo(18.5, 3);
  await page.waitForTimeout(2500);
  await expect(page.locator('#wakeBanner')).toBeHidden();
  expect(await writesTo(page)).toEqual([]);
});
