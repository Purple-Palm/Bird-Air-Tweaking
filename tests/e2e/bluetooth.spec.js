const { test, expect, openApp, entries, uuid16 } = require('./helpers');

const FFE1 = uuid16('ffe1');
const OTA_SVC = '00010203-0405-0607-0809-0a0b0c0d1912';
const OTA_CHR = '00010203-0405-0607-0809-0a0b0c0d2b12';

async function connect(page) {
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}
const send = (page, from, to) => page.evaluate(([a, b]) => {
  let ok = 0;
  for (let i = a; i < b; i++) ok += window.__fakeBle.notify('ffe1', [0x5a, 0xa5, i >> 8, i & 255]);
  return ok;
}, [from, to]);
const rxHex = async page => (await entries(page, { k: 'rx', u: FFE1 })).map(e => e.h);
const expected = (from, to) => Array.from({ length: to - from }, (_, k) => {
  const i = from + k;
  return ['5A', 'A5', (i >> 8).toString(16), (i & 255).toString(16)].map(x => x.toUpperCase().padStart(2, '0')).join(' ');
});

test.beforeEach(async ({ page }) => { await openApp(page, { bluetooth: true }); });

test('connects, reads 2A29, subscribes to FFE1 and never touches OTA', async ({ page }) => {
  await connect(page);
  const req = await page.evaluate(() => window.__fakeBle.calls.find(c => c[0] === 'requestDevice')[1]);
  expect(req.acceptAllDevices).toBe(true);
  expect(req.optionalServices).toEqual(expect.arrayContaining([uuid16('ffe0'), uuid16('180a'), OTA_SVC]));
  await expect(page.locator('#demoBanner')).toBeHidden();
  expect(await send(page, 0, 5)).toBe(5);
  await expect.poll(() => rxHex(page)).toEqual(expected(0, 5));

  const calls = await page.evaluate(() => window.__fakeBle.calls);
  expect(calls.some(c => c[0] === 'startNotifications' && c[1] === FFE1)).toBe(true);
  expect(calls.some(c => c[0] === 'read' && c[1] === uuid16('2a29'))).toBe(true);
  expect(calls.filter(c => c[1] === OTA_CHR)).toEqual([]);
  expect(calls.some(c => c[0] === 'write' || c[0] === 'writeData')).toBe(false);

  await page.click('#t-data');
  await expect(page.locator('#explorer')).toContainText('HL Corp');
  await expect(page.locator('#explorer .chip.danger')).toHaveText('OTA — не трогать');
  const ota = page.locator('#explorer .chr', { has: page.locator('.chip.danger') });
  for (const b of await ota.locator('button').all()) {
    const label = await b.textContent();
    if (label !== 'Разобрать байты') await expect(b).toBeDisabled();
  }
  await page.check('#setWrite');
  await expect(page.locator('#writeCard')).toBeVisible();
  const targets = await page.locator('#wrChar option').evaluateAll(os => os.map(o => o.value));
  expect(targets.some(v => v.includes(OTA_CHR))).toBe(false);
  expect(targets.some(v => v.includes(FFE1))).toBe(true);
});

test('reconnects after link loss without duplicating packets', async ({ page }) => {
  await connect(page);
  await send(page, 0, 5);
  await expect.poll(() => rxHex(page)).toHaveLength(5);
  for (const round of [1, 2]) {
    await page.evaluate(() => window.__fakeBle.drop());
    await expect(page.locator('#connPill')).toContainText('Переподключаюсь');
    expect(await send(page, 900, 901)).toBe(0);   // nothing arrives while the link is down
    await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 10000 });
    await send(page, round * 5, round * 5 + 5);
  }
  await expect.poll(() => rxHex(page)).toEqual(expected(0, 15));
  const subs = await page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'startNotifications').length);
  expect(subs).toBe(3);
  const log = (await entries(page, { k: 'sys' })).map(e => e.n);
  expect(log.filter(n => n === 'Связь потеряна')).toHaveLength(2);
});

test('link lost during service discovery: no false «connected», no duplicates', async ({ page }) => {
  await connect(page);
  await page.evaluate(() => { window.__fakeBle.discoverDelay = 1500; window.__fakeBle.drop(); });
  // the reconnect starts discovery; the link drops again while it is still running
  await expect.poll(() => page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'getPrimaryServices').length),
    { timeout: 5000, intervals: [50] }).toBe(2);
  await page.evaluate(() => { window.__fakeBle.discoverDelay = 0; window.__fakeBle.drop(); });
  await expect.poll(() => page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'getPrimaryServices').length),
    { timeout: 15000 }).toBe(3);
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 5000 });
  const sys = (await entries(page, { k: 'sys' })).map(e => e.n);
  expect(sys.filter(n => n === 'Подключено')).toHaveLength(2);   // the interrupted attempt does not count
  expect(sys.join('\n')).not.toContain('Сервисов не видно');
  await send(page, 0, 6);
  await expect.poll(() => rxHex(page)).toEqual(expected(0, 6));
  await page.waitForTimeout(300);
  expect(await rxHex(page)).toEqual(expected(0, 6));
});

test('retries when a reconnect attempt fails', async ({ page }) => {
  await connect(page);
  await page.evaluate(() => { window.__fakeBle.failConnects = 1; window.__fakeBle.drop(); });
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 15000 });
  const connects = await page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'connect').length);
  expect(connects).toBe(3);
  await send(page, 0, 3);
  await expect.poll(() => rxHex(page)).toEqual(expected(0, 3));
});

test('manual disconnect does not reconnect', async ({ page }) => {
  await connect(page);
  await page.click('#btnDisconnect');
  await expect(page.locator('#connPill')).toContainText('Не подключено');
  await page.waitForTimeout(1500);
  const connects = await page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'connect').length);
  expect(connects).toBe(1);
  await expect(page.locator('#btnConnect')).toBeVisible();
});

test('closing the chooser says so and keeps the demo running', async ({ page }) => {
  await page.evaluate(() => { window.__fakeBle.requestError = { name: 'NotFoundError', message: 'User cancelled the requestDevice() chooser.' }; });
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('Демо-режим · выбор отменён');
  await expect(page.locator('#problem')).toBeVisible();
  await expect(page.locator('#problemText')).toContainText('закрой приложение Bird');
  await expect(page.locator('#demoBanner')).toBeVisible();
  await page.evaluate(() => { window.__fakeBle.requestError = null; });
  await connect(page);
  await expect(page.locator('#problem')).toBeHidden();
});

test('Bluetooth switched off is reported, not swallowed', async ({ page }) => {
  await page.evaluate(() => { window.__fakeBle.requestError = { name: 'NotFoundError', message: 'Bluetooth adapter not available.' }; });
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('Ошибка');
  await expect(page.locator('#problemText')).toContainText('Bluetooth выключен или недоступен');
  await expect(page.locator('#problemText')).toContainText('Bluetooth adapter not available.');
});
