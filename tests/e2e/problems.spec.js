const { test, expect, openApp } = require('./helpers');

test('a browser without Web Bluetooth gets a clear message', async ({ page }) => {
  await openApp(page);   // headless Chromium has no navigator.bluetooth
  await page.click('#btnConnect');
  await expect(page.locator('#problem')).toBeVisible();
  await expect(page.locator('#problemText')).toContainText('нет Web Bluetooth');
  await expect(page.locator('#v-guide')).toBeVisible();
  await page.click('#problemHide');
  await expect(page.locator('#problem')).toBeHidden();
});

test.describe('script errors', () => {
  test.use({ allowedErrors: [/boom/] });
  test('show up on the page, since a phone has no devtools', async ({ page }) => {
    await openApp(page);
    await page.evaluate(() => { setTimeout(() => { throw new Error('boom'); }); Promise.reject(new Error('boom in a promise')); });
    await expect(page.locator('#problemText')).toContainText('Ошибка в странице: boom');
  });
});
