const { test, expect, openApp, entries, decodeContent, mockGitHub, pathOf, TOKEN } = require('./helpers');

const AUTO = { 'bal.gh.token': TOKEN, 'bal.gh': { owner: 'Purple-Palm', repo: 'Bird-Air-Tweaking', branch: 'main', folder: 'logs', auto: true } };
const notify = (page, from, to) => page.evaluate(([a, b]) => { for (let i = a; i < b; i++) window.__fakeBle.notify('ffe1', [0x5a, 0xa5, i]); }, [from, to]);
const tick = page => page.evaluate(() => window.__bal.autoSend());

async function connected(page, storage = AUTO) {
  const puts = await mockGitHub(page);
  await openApp(page, { bluetooth: true, storage });
  await page.evaluate(() => { window.__bal.ghLimits.autoEvery = 0; });
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
  return puts;
}

test('auto-send keeps one file per ride and overwrites it', async ({ page }) => {
  const puts = await connected(page);
  await expect(page.locator('#ghAuto')).toBeChecked();
  await notify(page, 0, 5);
  await tick(page);
  await expect.poll(() => puts.length).toBe(1);
  expect(pathOf(puts[0])).toMatch(/^logs\/\d{8}-\d{6}-log\.json$/);
  expect(puts[0].body.sha).toBeUndefined();
  expect(puts[0].body.message).toContain('автоотправка');
  expect(decodeContent(puts[0].body).device).toEqual({ name: 'scooter', id: 'scooter' });

  await notify(page, 5, 9);
  await tick(page);
  await expect.poll(() => puts.length).toBe(2);
  expect(pathOf(puts[1])).toBe(pathOf(puts[0]));
  expect(puts[1].body.sha).toBe('sha-1');
  expect(decodeContent(puts[1].body).entries.filter(e => e.k === 'rx')).toHaveLength(9);

  await tick(page);                     // nothing new: nothing sent
  await page.waitForTimeout(300);
  expect(puts).toHaveLength(2);

  await page.click('#btnDisconnect');   // leaving sends the rest at once
  await expect.poll(() => puts.length).toBe(3);
  expect(puts[2].body.sha).toBe('sha-2');
  expect(decodeContent(puts[2].body).entries.map(e => e.n)).toContain('Отключено');
  await page.click('#t-data');
  await expect(page.locator('#ghMsg')).toContainText('Автоотправка');
});

test('auto-send respects the interval and never sends the demo', async ({ page }) => {
  const puts = await mockGitHub(page);
  await openApp(page, { storage: AUTO });
  await page.evaluate(() => { window.__bal.ghLimits.autoEvery = 0; });
  await page.waitForTimeout(800);
  await tick(page);
  await page.waitForTimeout(300);
  expect(puts).toHaveLength(0);         // demo
});

test('auto-send waits for the interval', async ({ page }) => {
  const puts = await connected(page);
  await page.evaluate(() => { window.__bal.ghLimits.autoEvery = 60 * 60 * 1000; });
  await notify(page, 0, 3);
  await tick(page);
  await page.waitForTimeout(300);
  expect(puts).toHaveLength(0);
});

test('a manual re-send overwrites, and a new packing removes the old file', async ({ page }) => {
  const puts = await connected(page, { 'bal.gh.token': TOKEN });
  await notify(page, 0, 4);
  await page.click('#t-data');
  await page.click('#btnGhSend');
  await expect(page.locator('#ghMsg')).toContainText('Отправлено');
  await expect(page.locator('#ghName')).toContainText('перезапишется');
  await notify(page, 4, 8);
  await page.evaluate(() => { window.__bal.ghLimits.fileMax = 600; });
  await page.click('#btnGhSend');
  await expect.poll(() => puts.length).toBe(2);
  await expect(page.locator('#ghMsg')).toContainText('Отправлено');
  expect(pathOf(puts[1])).toBe(pathOf(puts[0]) + '.gz');
  expect(puts[1].body.sha).toBeUndefined();
  expect(puts.deletes.map(pathOf)).toEqual([pathOf(puts[0])]);
  expect(puts.deletes[0].body.sha).toBe('sha-1');
});

test('the dashboard explains empty gauges while connected', async ({ page }) => {
  await connected(page, { 'bal.gh.token': TOKEN });
  await notify(page, 0, 3);
  await expect(page.locator('#rawBanner')).toBeVisible();
  await expect(page.locator('#rawText')).toContainText('пакетов: ');
  await expect(page.locator('#rawText')).toContainText('декодер пустой');
});

test('no hint once a decoder has fields', async ({ page }) => {
  await connected(page, { 'bal.decoder': { json: false, fields: [{ key: 'custom', offset: 2, type: 'u8' }] } });
  await notify(page, 0, 3);
  await page.waitForTimeout(300);
  await expect(page.locator('#rawBanner')).toBeHidden();
});
