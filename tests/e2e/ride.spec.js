// A long ride: what keeps the live data going when the link or the phone misbehaves.
const { test, expect, openApp, entries, uuid16, TOKEN, pathOf } = require('./helpers');
const { V3_STORAGE, realDash, hexOf } = require('./real-dash');

const FFE1 = uuid16('ffe1');
const SVGT = hexOf('+SVGT=?');
const frames = page => page.evaluate(() => window.__bal.state.tel.frames.total);
const writes = page => page.evaluate(u => window.__fakeBle.calls.filter(c => c[0] === 'writeData' && c[1] === u).map(c => c[2]), FFE1);
const sysText = async page => (await entries(page, { k: 'sys' })).map(e => e.n).join('\n');

async function connect(page, dash) {
  await page.evaluate(d => { window.__fakeBle.dash = d; }, dash);
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}

test('a reconnect whose FFE1 subscription fails is retried instead of showing «На связи» with no data', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: { 'bal.settings': { autoWake: true }, ...V3_STORAGE } });
  await page.evaluate(() => Object.assign(window.__bal.wakeLimits, { pulse: 400 }));
  await connect(page, realDash({ window: 700 }));
  await expect.poll(() => frames(page)).toBeGreaterThan(10);
  await page.evaluate(() => { window.__fakeBle.fail = { notify: { ffe1: 1 } }; window.__fakeBle.drop(); });
  await expect.poll(() => sysText(page), { timeout: 10000 }).toContain('подписка на 0xFFE1 не удалась — переподключаюсь ещё раз');
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 15000 });
  const f = await frames(page);
  await expect.poll(() => frames(page), { timeout: 6000 }).toBeGreaterThan(f + 10);   // data again
  expect(await page.evaluate(() => window.__bal.state.chars.get([...window.__bal.state.chars.keys()].find(k => k.endsWith('ffe1-0000-1000-8000-00805f9b34fb'))).subscribed)).toBe(true);
});

test('the first connect to a device without FFE1 is not refused', async ({ page }) => {
  await openApp(page, { bluetooth: true });
  await page.evaluate(() => { window.__fakeBle.fail = { notify: { ffe1: 1 } }; });
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
});

test('a failed pulse is retried within half a second and logged once', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });
  await page.evaluate(() => {
    Object.assign(window.__bal.wakeLimits, { pulse: 1500 });
    const c = window.__fakeBle.calls, push = c.push.bind(c);
    c.push = (...xs) => { for (const x of xs) x.at = performance.now(); return push(...xs); };
  });
  await connect(page, realDash({ window: 2500 }));
  await page.click('#btnWake');
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  await page.evaluate(() => { window.__fakeBle.fail = { write: { ffe1: 2 } }; });
  await expect.poll(async () => (await writes(page)).length, { timeout: 6000 }).toBe(2);
  const t = await page.evaluate(u => window.__fakeBle.calls.filter(c => (c[0] === 'writeData' || c[0] === 'writeFailed') && c[1] === u).map(c => [c[0], c.at]), FFE1);
  expect(t.map(x => x[0])).toEqual(['writeData', 'writeFailed', 'writeFailed', 'writeData']);
  expect(t[2][1] - t[1][1]).toBeLessThan(1000);                     // not a whole pulse (1.5 s) later
  expect(t[3][1] - t[2][1]).toBeLessThan(1000);
  const log = await sysText(page);
  expect(log.split('запрос +SVGT=? не ушёл').length - 1).toBe(1);
  expect(log).toContain('Живые данные: запросы снова уходят');
  expect(new Set(await writes(page))).toEqual(new Set([SVGT]));
});

test('the screen stays on while reconnecting and is let go on «Отключить»', async ({ page }) => {
  await page.addInitScript(() => {
    window.__lock = { held: 0, log: [] };
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { async request() {
      window.__lock.held++; window.__lock.log.push('request');
      const s = new EventTarget();
      s.release = async () => { window.__lock.held--; window.__lock.log.push('release'); s.dispatchEvent(new Event('release')); };
      return s;
    } } });
  });
  await openApp(page, { bluetooth: true, storage: V3_STORAGE });
  await connect(page, realDash());
  await expect.poll(() => page.evaluate(() => window.__lock.held)).toBe(1);
  await page.evaluate(() => { window.__fakeBle.failConnects = 2; window.__fakeBle.drop(); });
  await expect(page.locator('#connPill')).toContainText('Переподключаюсь');
  for (let i = 0; i < 5; i++) {                                         // through the failed attempts
    expect(await page.evaluate(() => window.__lock.held)).toBe(1);
    await page.waitForTimeout(500);
  }
  await expect(page.locator('#connPill')).toContainText('На связи', { timeout: 10000 });
  expect(await page.evaluate(() => window.__lock.held)).toBe(1);
  await page.click('#btnDisconnect');
  await expect.poll(() => page.evaluate(() => window.__lock.held)).toBe(0);
});

test('«Живые данные» do not carry over to the next device picked in the chooser', async ({ page }) => {
  await openApp(page, { bluetooth: true, storage: { 'bal.settings': { autoReconnect: false }, ...V3_STORAGE } });
  await connect(page, realDash());
  await page.evaluate(() => window.__bal.startLive(false));
  await expect.poll(async () => (await writes(page)).length).toBe(1);
  await page.evaluate(() => window.__fakeBle.drop());
  await expect(page.locator('#connPill')).toContainText('Не подключено');
  expect(await page.evaluate(() => window.__bal.state.live.on)).toBe(false);
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
  await page.waitForTimeout(1500);
  expect((await writes(page)).length).toBe(1);
});

test('a link drop during an upload is sent right after it, not 5 minutes later', async ({ page }) => {
  const puts = [];
  let release;
  const first = new Promise(r => { release = r; });
  await page.route('https://api.github.com/**', async route => {
    const req = route.request();
    if (req.method() === 'PUT') {
      const body = JSON.parse(req.postData());
      puts.push(body);
      if (puts.length === 1) await first;                             // a slow mobile upload
      const path = pathOf({ path: new URL(req.url()).pathname });
      return route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ content: { path, sha: 'sha-' + puts.length, html_url: 'https://github.com/x/' + path } }) });
    }
    if (req.method() === 'GET' && /\/contents\//.test(req.url())) return route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ full_name: 'Purple-Palm/Bird-Air-Tweaking', permissions: { push: true } }) });
  });
  await openApp(page, { bluetooth: true, storage: { 'bal.gh.token': TOKEN, 'bal.gh': { owner: 'Purple-Palm', repo: 'Bird-Air-Tweaking', branch: 'main', folder: 'logs', auto: true }, 'bal.settings': { autoReconnect: false }, ...V3_STORAGE } });
  await connect(page, realDash());
  await page.evaluate(() => window.__bal.sendWake(false));
  await expect.poll(() => frames(page)).toBeGreaterThan(5);
  await page.evaluate(() => window.__bal.autoSend(true));               // the periodic send, now uploading
  await expect.poll(() => puts.length).toBe(1);
  await page.evaluate(() => window.__fakeBle.drop());                  // the battery died mid-upload
  await page.waitForTimeout(800);
  release();
  await expect.poll(() => puts.length, { timeout: 5000 }).toBe(2);
});
