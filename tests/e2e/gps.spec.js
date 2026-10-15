// «Скорость по GPS»: the phone's GPS speed goes into the log (speed and accuracy only, never
// coordinates), shows on the speed tile until a decoder speed field exists, and stops with the session.
const { test, expect, openApp } = require('./helpers');
const { V3, realDash } = require('./real-dash');

/* The repository decoder without its speed field: the speed tile falls back to GPS. */
const NO_SPEED = { 'bal.decoder.repo': { ...V3, fields: V3.fields.filter(f => f.key !== 'speed_kmh') }, 'bal.decoder.active': 'repo' };

/* A scripted navigator.geolocation: the test pushes positions with window.__gps.fix(). */
const fakeGeo = () => {
  const watchers = new Map();
  let next = 1;
  window.__gps = {
    watchers, cleared: 0,
    fix(speed, extra = {}) {
      const coords = { latitude: 55.7512, longitude: 37.6184, altitude: null, accuracy: 4, altitudeAccuracy: null, heading: 90, speed, ...extra };
      for (const [ok] of watchers.values()) ok({ coords, timestamp: Date.now() });
    },
    deny() { for (const [, err] of watchers.values()) err && err({ code: 1, message: 'denied' }); },
  };
  Object.defineProperty(navigator, 'geolocation', { configurable: true, value: {
    watchPosition(ok, err) { const id = next++; watchers.set(id, [ok, err]); return id; },
    clearWatch(id) { if (watchers.delete(id)) window.__gps.cleared++; },
    getCurrentPosition() {},
  } });
};

async function connect(page) {
  await page.evaluate(d => { window.__fakeBle.dash = d; }, realDash());
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}

test('GPS speed: only speed and accuracy reach the log; speed tile shows it «по GPS»', async ({ page }) => {
  await page.addInitScript(fakeGeo);
  await openApp(page, { bluetooth: true, storage: { 'bal.settings': { units: 'kmh' }, ...NO_SPEED } });
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(0);    // the demo never asks for GPS
  await connect(page);
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(0);    // off by default
  await page.click('#btnWake');                                              // «Живые данные»
  await page.check('#gpsSpeed');
  await expect(page.locator('#gpsMsg')).toContainText('только скорость');
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(1);
  await page.evaluate(() => { window.__gps.fix(5); window.__gps.fix(null); window.__gps.fix(5.5, { accuracy: 6.4 }); });
  const gps = await page.evaluate(() => window.__bal.state.session.entries.filter(e => e.k === 'gps'));
  expect(gps.map(e => [e.v, e.a])).toEqual([[5, 4], [5.5, 6]]);             // a fix without speed is skipped
  await expect(page.locator('#spdVal')).toHaveText('19,8км/ч');
  await expect(page.locator('#spdSub')).toContainText('по GPS');
  // the saved log: no coordinates anywhere, and the entry survives a round trip
  const json = await page.evaluate(() => JSON.stringify(window.__bal.sessionObject()));
  expect(json).not.toContain('55.75');
  expect(json).not.toContain('37.61');
  expect(json).not.toMatch(/latitude|longitude|heading/);
  const o = JSON.parse(json);
  expect(o.entries.filter(e => e.k === 'gps')).toEqual([expect.objectContaining({ k: 'gps', v: 5, a: 4 }), expect.objectContaining({ k: 'gps', v: 5.5, a: 6 })]);
  // «Отключить» stops the GPS watch
  await page.click('#btnDisconnect');
  await expect(page.locator('#connPill')).toContainText('Не подключено');
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(0);
  await page.evaluate(() => window.__gps.fix(9));
  expect(await page.evaluate(() => window.__bal.state.session.entries.filter(e => e.k === 'gps').length)).toBe(2);
});

test('GPS denied: the box unticks and says how to allow it', async ({ page }) => {
  await page.addInitScript(fakeGeo);
  await openApp(page, { bluetooth: true, storage: { 'bal.settings': { gpsSpeed: true }, ...NO_SPEED } });
  await connect(page);
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(1);    // on from the settings
  await page.evaluate(() => window.__gps.deny());
  await expect(page.locator('#gpsMsg')).toContainText('разреши сайту «Местоположение»');
  await expect(page.locator('#setGps')).not.toBeChecked();
  expect(await page.evaluate(() => window.__gps.watchers.size)).toBe(0);
});

test('a log with GPS entries loads back from a file', async ({ page }) => {
  await openApp(page, { storage: { 'bal.settings': { units: 'kmh' } } });
  const log = { format: 'birdair-lab-log', version: 1, startedAt: '2026-10-15T02:00:00.000Z', device: { name: 'scooter', id: 'scooter' }, demo: false, trimmed: false, gatt: [], entries: [
    { t: 0, k: 'sys', n: 'Подключено' }, { t: 1000, k: 'gps', v: 4.2, a: 5 }, { t: 2000, k: 'gps', v: 6, a: 3 },
  ] };
  await page.setInputFiles('#fileImport', { name: 'gps.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(log)) });
  await expect(page.locator('#connPill')).toContainText('Лог из файла');
  const back = await page.evaluate(() => window.__bal.state.session.entries.filter(e => e.k === 'gps').map(e => [e.t, e.v, e.a]));
  expect(back).toEqual([[1000, 4.2, 5], [2000, 6, 3]]);
  await expect(page.locator('#spdVal')).toHaveText('21,6км/ч');
});
