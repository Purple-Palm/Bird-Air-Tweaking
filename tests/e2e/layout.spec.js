const { test, expect, openApp } = require('./helpers');

const noSideScroll = page => page.evaluate(() => {
  const d = document.documentElement;
  return { scroll: d.scrollWidth, client: d.clientWidth, body: document.body.scrollWidth };
});

// Fonts are stubbed in tests and fallback fonts differ between machines, so the second pass
// widens every glyph: a layout that only fits with narrow fonts fails here, not on the phone.
for (const spacing of [null, '0.08em']) test(`no horizontal scroll at 390 px on any tab${spacing ? ', wide glyphs' : ''}`, async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openApp(page, { storage: { 'bal.settings': { allowWrite: true } } });
  if (spacing) await page.addStyleTag({ content: `* { letter-spacing: ${spacing} !important; }` });
  await page.waitForTimeout(1500);
  for (const tab of ['dash', 'data', 'decoder', 'guide']) {
    await page.click('#t-' + tab);
    if (tab === 'dash') await page.click('#btnTable');
    if (tab === 'data') {
      await page.locator('#anGrid th[data-off="5"]').click();
      await page.fill('#ghLabel', 'очень длинная подпись для имени файла лога');
    }
    if (tab === 'decoder') for (const d of await page.locator('details').all()) await d.evaluate(el => { el.open = true; });
    if (tab === 'guide') for (const d of await page.locator('details').all()) await d.evaluate(el => { el.open = true; });
    await page.waitForTimeout(250);
    const m = await noSideScroll(page);
    expect(m.scroll, tab).toBeLessThanOrEqual(m.client);
    expect(m.body, tab).toBeLessThanOrEqual(m.client);
  }
});

test('the decoder update banner fits too', async ({ page }) => {
  await page.route('**/decoders/current.json', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    version: 2, updatedAt: '2026-10-15T10:00:00Z', note: 'длинное описание изменений декодера, чтобы проверить перенос строк на узком экране',
    fields: [{ key: 'speed_kmh', offset: 9, type: 'u16le', scale: 0.1 }] }) }));
  await openApp(page);
  await expect(page.locator('#decUpd')).toBeVisible();
  const m = await noSideScroll(page);
  expect(m.scroll).toBeLessThanOrEqual(m.client);
});
