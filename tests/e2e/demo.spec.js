const { test, expect, openApp, entries } = require('./helpers');

test('demo starts on load and fills the dashboard', async ({ page }) => {
  await openApp(page);
  await expect(page.locator('#demoBanner')).toBeVisible();
  await expect(page.locator('#connPill')).toContainText('Демо');
  await expect(page.locator('#batVal')).toHaveText(/^\d+%$/);
  await expect(page.locator('#pwrVal')).toHaveText(/Вт$/);
  await expect(page.locator('#spdVal')).toHaveText(/mph$/);
  await expect(page.locator('#modeChip')).toBeVisible();
  await expect.poll(async () => (await entries(page, { k: 'rx' })).length).toBeGreaterThan(10);
  const hs = (await entries(page, { k: 'rx' })).map(e => e.h);
  expect(hs.every(h => h.startsWith('5A A5'))).toBe(true);
});

test('markers, tabs and stopping the demo', async ({ page }) => {
  await openApp(page);
  await page.locator('#v-dash [data-markers] button', { hasText: 'Тормоз' }).click();
  await expect.poll(async () => (await entries(page, { k: 'mk' })).map(e => e.n)).toContain('Тормоз');
  for (const tab of ['data', 'decoder', 'guide', 'dash']) {
    await page.click('#t-' + tab);
    await expect(page.locator('#v-' + tab)).toBeVisible();
  }
  await page.click('#t-data');
  await expect(page.locator('#log')).toContainText('5A A5');
  await page.click('#t-decoder');
  await expect(page.locator('#decNote')).toContainText('демо-конфиг');
  await page.click('#t-dash');
  await page.click('#btnDemoStop');
  await expect(page.locator('#demoBanner')).toBeHidden();
  await expect(page.locator('#idleBanner')).toBeVisible();
  const n = (await entries(page)).length;
  await page.waitForTimeout(400);
  expect((await entries(page)).length).toBe(n);
});

test('footer shows the build', async ({ page }) => {
  await openApp(page);
  await expect(page.locator('#build')).toHaveText('локальная копия');
});
