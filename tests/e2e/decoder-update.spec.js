const { test, expect, openApp } = require('./helpers');

const V2 = {
  format: 'birdair-lab-decoder', version: 2, updatedAt: '2026-10-15T10:00:00Z', note: 'скорость и ток', json: false,
  fields: [
    { key: 'speed_kmh', uuid: 'b1a1', match: '5A A5 0B 25', offset: 9, type: 'u16le', scale: 0.1 },
    { key: 'current', uuid: 'b1a1', match: '5A A5 0B 25', offset: 7, type: 's16le', scale: 0.01 },
  ],
};
const MINE = { json: false, fields: [{ key: 'custom', name: 'мой байт', offset: 3, type: 'u8', scale: 1 }] };

async function serveDecoder(page, get) {
  await page.route('**/decoders/current.json', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(get()) }));
}
const ls = (page, k) => page.evaluate(key => JSON.parse(localStorage.getItem(key)), k);
const realFields = page => page.evaluate(() => (window.__bal.state.demo ? window.__bal.state.savedDecoder : window.__bal.decoder).fields.map(f => f.name || f.key));

test('the empty v1 in the repository is not offered', async ({ page }) => {
  await openApp(page);
  await page.waitForTimeout(500);
  await expect(page.locator('#decUpd')).toBeHidden();
  await page.click('#t-decoder');
  await expect(page.locator('#decRepoText')).toContainText('В репозитории v1');
  await expect(page.locator('#decRepoText')).toContainText('полей пока нет');
});

test('a newer decoder is offered on any tab and applied only by the button', async ({ page }) => {
  await serveDecoder(page, () => V2);
  await openApp(page, { tab: 'guide', storage: { 'bal.decoder': MINE } });
  await expect(page.locator('#decUpd')).toBeVisible();
  await expect(page.locator('#decUpdText')).toContainText('v2 от 15.10.2026, полей: 2 — скорость и ток');
  await expect(page.locator('#decUpdText')).toContainText('Твой декодер сохранится');
  expect(await realFields(page)).toEqual(['мой байт']);       // nothing applied yet
  await page.click('#decUpdApply');
  await expect(page.locator('#decUpd')).toBeHidden();
  expect(await ls(page, 'bal.decoder.active')).toBe('repo');
  expect(await realFields(page)).toEqual(['speed_kmh', 'current']);
  expect((await ls(page, 'bal.decoder')).fields[0].name).toBe('мой байт');   // «мой» untouched
  await page.reload();
  await page.waitForTimeout(500);
  await expect(page.locator('#decUpd')).toBeHidden();
  expect(await realFields(page)).toEqual(['speed_kmh', 'current']);
});

test('editing the repository decoder makes it «мой» and keeps the old one', async ({ page }) => {
  await serveDecoder(page, () => V2);
  await openApp(page, { tab: 'decoder', storage: { 'bal.decoder': MINE } });
  await page.click('#decRepoApply');
  await page.evaluate(() => window.__bal.stopDemo(false));
  await page.click('#t-decoder');
  await expect(page.locator('#decSrcText')).toContainText('Работает декодер из репозитория v2');
  await page.locator('#decFields [data-k="scale"]').first().fill('0.2');
  await expect.poll(() => ls(page, 'bal.decoder.active')).toBe('mine');
  expect((await ls(page, 'bal.decoder')).fields.map(f => f.key + ':' + f.scale)).toEqual(['speed_kmh:0.2', 'current:0.01']);
  expect((await ls(page, 'bal.decoder.backup')).decoder.fields[0].name).toBe('мой байт');
  expect((await ls(page, 'bal.decoder.repo')).fields[0].scale).toBe(0.1);
  await expect(page.locator('#decSrcText')).toContainText('сделан правкой декодера из репозитория');
  await page.click('#decBackup');
  expect(await realFields(page)).toEqual(['мой байт']);
  await page.click('#decSrcSeg button[data-src="repo"]');
  expect(await realFields(page)).toEqual(['speed_kmh', 'current']);
  await expect(page.locator('#decSrcSeg button[data-src="repo"]')).toHaveAttribute('aria-pressed', 'true');
});

test('«Не сейчас» hides the banner for that version only', async ({ page }) => {
  let remote = V2;
  await serveDecoder(page, () => remote);
  await openApp(page);
  await page.click('#decUpdLater');
  await expect(page.locator('#decUpd')).toBeHidden();
  await page.reload();
  await page.waitForTimeout(500);
  await expect(page.locator('#decUpd')).toBeHidden();
  await page.click('#t-decoder');
  await expect(page.locator('#decRepoApply')).toBeVisible();      // still offered in the Decoder tab
  remote = { ...V2, updatedAt: '2026-10-16T08:00:00Z' };          // same version, later date: newer
  await page.reload();
  await expect(page.locator('#decUpd')).toBeVisible();
  remote = { ...V2, version: 10 };                                 // 10 > 2, not "10" < "2"
  await page.reload();
  await expect(page.locator('#decUpd')).toBeVisible();
  await expect(page.locator('#decUpdText')).toContainText('v10');
});

test('applying during the demo waits for it to stop', async ({ page }) => {
  await serveDecoder(page, () => V2);
  await openApp(page);
  await page.click('#decUpdApply');
  expect(await page.evaluate(() => window.__bal.decoder.fields.length)).toBe(7);   // demo config keeps running
  await page.click('#btnDemoStop');
  expect(await page.evaluate(() => window.__bal.decoder.fields.map(f => f.key))).toEqual(['speed_kmh', 'current']);
});
