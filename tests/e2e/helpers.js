// Shared setup: no network fonts, console errors fail the test, optional fake Bluetooth.
const path = require('path');
const zlib = require('zlib');
const { test: base, expect } = require('@playwright/test');

const test = base.extend({
  // Regexps for console errors a test provokes on purpose (e.g. mocked HTTP 4xx).
  allowedErrors: [[], { option: true }],
  page: async ({ page, allowedErrors }, use) => {
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('pageerror', e => errors.push('pageerror: ' + e.message));
    await page.route(/^https:\/\/fonts\.(googleapis|gstatic)\.com\//, r => r.fulfill({ status: 200, contentType: 'text/css', body: '' }));
    await use(page);
    expect(errors.filter(t => !allowedErrors.some(re => re.test(t))), 'console errors').toEqual([]);
  },
});

const uuid16 = u => '0000' + u + '-0000-1000-8000-00805f9b34fb';

/* storage is written once per test (not again on reload). */
async function openApp(page, { bluetooth = false, storage = null, noCompression = false, tab = null } = {}) {
  if (bluetooth) await page.addInitScript({ path: path.join(__dirname, 'fake-bluetooth.js') });
  if (noCompression) await page.addInitScript(() => { delete window.CompressionStream; });
  if (storage) {
    await page.addInitScript(st => {
      if (sessionStorage.getItem('__seeded')) return;
      sessionStorage.setItem('__seeded', '1');
      for (const [k, v] of Object.entries(st)) localStorage.setItem(k, JSON.stringify(v));
    }, storage);
  }
  await page.goto(tab ? '/#' + tab : '/');
  await page.waitForFunction(() => window.__bal && window.__bal.state.session);
}

const entries = (page, filter = {}) => page.evaluate(f => window.__bal.state.session.entries
  .filter(e => (!f.k || e.k === f.k) && (!f.u || e.u === f.u))
  .map(e => ({ t: e.t, k: e.k, u: e.u, h: e.h, n: e.n })), filter);

const decodeContent = body => {
  const buf = Buffer.from(body.content, 'base64');
  return JSON.parse((buf[0] === 0x1f && buf[1] === 0x8b ? zlib.gunzipSync(buf) : buf).toString('utf8'));
};

module.exports = { test, expect, openApp, entries, uuid16, decodeContent };
