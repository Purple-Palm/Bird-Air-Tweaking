const fs = require('fs');
const zlib = require('zlib');
const { test, expect, openApp, entries } = require('./helpers');

async function exportDemo(page) {
  await openApp(page, { tab: 'data' });
  await page.waitForTimeout(1500);
  await page.locator('#v-data [data-markers] button', { hasText: 'Газ' }).first().click();
  await page.waitForTimeout(300);
  await page.evaluate(() => window.__bal.stopDemo(false));
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnExport')]);
  expect(dl.suggestedFilename()).toMatch(/^birdair-log-\d{8}-\d{4}-demo\.json$/);
  return { text: fs.readFileSync(await dl.path(), 'utf8'), n: (await entries(page)).length };
}
const load = (page, files) => page.setInputFiles('#fileImport', files.map(([name, buffer]) => ({ name, mimeType: 'application/octet-stream', buffer })));

test('export, then import the same log', async ({ page }) => {
  const { text, n } = await exportDemo(page);
  const log = JSON.parse(text);
  expect(log.format).toBe('birdair-lab-log');
  expect(log.version).toBe(1);
  expect(log.entries).toHaveLength(n);
  expect(log.entries.some(e => e.k === 'mk' && e.n === 'Газ')).toBe(true);
  await page.reload();
  await load(page, [['log.json', Buffer.from(text)]]);
  await expect(page.locator('#logMsg')).toContainText('Загружено записей: ' + n);
  await expect(page.locator('#connPill')).toContainText('Лог из файла');
  expect(await entries(page)).toHaveLength(n);
  await page.click('#t-dash');
  await expect(page.locator('#batVal')).toHaveText('—');     // the real (empty) decoder, not the demo one
});

test('imports .json.gz and the parts of one log', async ({ page }) => {
  const { text, n } = await exportDemo(page);
  const log = JSON.parse(text);
  await load(page, [['log.json.gz', zlib.gzipSync(Buffer.from(text))]]);
  await expect(page.locator('#logMsg')).toContainText('Загружено записей: ' + n);

  const half = Math.ceil(log.entries.length / 2);
  const part = (i, es) => Buffer.from(JSON.stringify({ ...log, part: { id: 'x', index: i, count: 2 }, entries: es }));
  await load(page, [['x-part2of2.json', part(2, log.entries.slice(half))], ['x-part1of2.json.gz', zlib.gzipSync(part(1, log.entries.slice(0, half)))]]);
  await expect(page.locator('#logMsg')).toContainText('Загружено записей: ' + n);
  const ts = (await entries(page)).map(e => e.t);
  expect(ts).toEqual([...ts].sort((a, b) => a - b));

  await load(page, [['x-part1of2.json', part(1, log.entries.slice(0, half))]]);
  await expect(page.locator('#logMsg')).toContainText('Не хватает частей 2 из 2');
  await expect(page.locator('#logMsg')).toHaveClass(/err/);
});

test('rejects files that are not logs', async ({ page }) => {
  await openApp(page, { tab: 'data' });
  await load(page, [['notes.txt', Buffer.from('hello')]]);
  await expect(page.locator('#logMsg')).toContainText('это не JSON');
  await load(page, [['other.json', Buffer.from('{"a":1}')]]);
  await expect(page.locator('#logMsg')).toContainText('формат не тот');
  await load(page, [['a.json', Buffer.from(JSON.stringify({ format: 'birdair-lab-log', entries: [] }))], ['b.json', Buffer.from(JSON.stringify({ format: 'birdair-lab-log', entries: [] }))]]);
  await expect(page.locator('#logMsg')).toContainText('Выбери один лог или все части одного лога');
});

test('a log written by the Python tools opens', async ({ page }) => {
  const log = {
    format: 'birdair-lab-log', version: 1, startedAt: '2026-10-14T19:00:00.000Z', device: { name: 'scooter', id: 'scooter' },
    gatt: [{ uuid: 'unknown', chars: [] }], decoder: { json: false, fields: [] },
    entries: [{ t: 0, k: 'sys', n: 'Подключение' }, { t: 10, k: 'tx', s: 'unknown', u: 'handle-0x0012', h: '01 02' }, { t: 20, k: 'rx', s: 'unknown', u: 'handle-0x0015', h: 'AA BB CC' }],
  };
  await openApp(page, { tab: 'data' });
  await load(page, [['btsnoop.json', Buffer.from(JSON.stringify(log))]]);
  await expect(page.locator('#logMsg')).toContainText('Загружено записей: 3');
  await expect(page.locator('#log')).toContainText('handle-0x0015');
  await expect(page.locator('#anChar')).toContainText('handle-0x0015');
});
