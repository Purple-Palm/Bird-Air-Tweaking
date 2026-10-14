// Regression tests for defects found in review.
const { test, expect, openApp, entries, mockGitHub, pathOf, TOKEN, uuid16 } = require('./helpers');

async function connect(page) {
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}
const writes = page => page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'writeData'));

test.describe('write console', () => {
  test.beforeEach(async ({ page }) => {
    await openApp(page, { bluetooth: true, storage: { 'bal.settings': { allowWrite: true } } });
    await connect(page);
    await page.click('#t-data');
  });

  test('a refused repeat leaves no timer behind', async ({ page }) => {
    await page.click('#wrRepeat');                       // hex field still empty: refused, so it unticks itself
    await expect(page.locator('#wrMsg')).toContainText('Введи байты');
    await expect(page.locator('#wrRepeat')).not.toBeChecked();
    await page.fill('#wrHex', '01 02');                  // typing afterwards must not start writing
    await page.waitForTimeout(1500);
    expect(await writes(page)).toEqual([]);
  });

  test('a repeat sends exactly the command it started with and locks the fields', async ({ page }) => {
    await page.fill('#wrEvery', '200');
    await page.fill('#wrHex', '5A A5 01');
    await page.check('#wrRepeat');
    await expect(page.locator('#wrHex')).toBeDisabled();
    await page.waitForTimeout(700);
    await page.uncheck('#wrRepeat');
    const sent = await writes(page);
    expect(sent.length).toBeGreaterThanOrEqual(3);
    expect(new Set(sent.map(w => w[2]))).toEqual(new Set(['5A A5 01']));
    expect(sent.every(w => w[1] === uuid16('ffe1'))).toBe(true);
    await expect(page.locator('#wrHex')).toBeEnabled();
    const n = sent.length;
    await page.waitForTimeout(600);
    expect((await writes(page)).length).toBe(n);         // stopped means stopped
  });
});

test('«Отключить» while connecting cancels the connection', async ({ page }) => {
  await openApp(page, { bluetooth: true });
  await page.evaluate(() => { window.__fakeBle.connectDelay = 800; });
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('Подключаюсь');
  await page.click('#btnDisconnect');
  await expect(page.locator('#connPill')).toContainText('Не подключено');
  await page.waitForTimeout(1500);
  await expect(page.locator('#connPill')).toContainText('Не подключено');
  expect(await page.evaluate(() => window.__fakeBle.connected)).toBe(false);
  const sys = (await entries(page, { k: 'sys' })).map(e => e.n);
  expect(sys).not.toContain('Подключено');
  expect(await page.evaluate(() => window.__fakeBle.calls.filter(c => c[0] === 'startNotifications').length)).toBe(0);
  // connecting again works and delivers each packet once
  await page.evaluate(() => { window.__fakeBle.connectDelay = 0; });
  await connect(page);
  await page.evaluate(() => { for (let i = 0; i < 4; i++) window.__fakeBle.notify('ffe1', [0xA0, i]); });
  await expect.poll(async () => (await entries(page, { k: 'rx' })).length).toBe(4);
});

test.describe('re-sending', () => {
  test('after «Очистить» a new recording gets its own file', async ({ page }) => {
    const puts = await mockGitHub(page);
    await openApp(page, { bluetooth: true, tab: 'data', storage: { 'bal.gh.token': TOKEN } });
    await connect(page);
    await page.evaluate(() => window.__fakeBle.notify('ffe1', [1]));
    await page.click('#btnGhSend');
    await expect(page.locator('#ghMsg')).toContainText('Отправлено');
    await page.waitForTimeout(1100);                     // the file name has one-second resolution
    await page.click('#btnClear'); await page.click('#btnClear');
    await page.evaluate(() => window.__fakeBle.notify('ffe1', [2]));
    await page.click('#btnGhSend');
    await expect.poll(() => puts.length).toBe(2);
    expect(pathOf(puts[1])).not.toBe(pathOf(puts[0]));
    expect(puts[1].body.sha).toBeUndefined();
  });

  test('a full (capped) log with new packets is sent again', async ({ page }) => {
    const puts = await mockGitHub(page);
    await openApp(page, { tab: 'data', storage: { 'bal.gh.token': TOKEN } });
    await page.evaluate(() => window.__bal.stopDemo(false));
    await page.click('#btnGhSend');
    await expect(page.locator('#ghMsg')).toContainText('Отправлено');
    // same length, new content: what a log at MAX_ENTRIES looks like
    await page.evaluate(() => { const S = window.__bal.state.session; S.entries.shift(); window.__bal.pushEntry({ k: 'mk', n: 'новая метка' }); });
    await page.click('#btnGhSend');
    await expect.poll(() => puts.length).toBe(2);
    expect(puts[1].body.sha).toBe('sha-1');
  });
});

test.describe('GitHub errors', () => {
  test.use({ allowedErrors: [/Failed to load resource/] });
  test('a protected branch is not called a race', async ({ page }) => {
    await mockGitHub(page, () => ({ status: 409, body: { message: 'Repository rule violations found\n\nChanges must be made through a pull request.' } }));
    await openApp(page, { tab: 'data', storage: { 'bal.gh.token': TOKEN } });
    await page.evaluate(() => window.__bal.stopDemo(false));
    await page.click('#btnGhSend');
    await expect(page.locator('#ghMsg')).toContainText('защищена правилами репозитория');
  });
});

test('a token pasted with invisible characters is cleaned or rejected clearly', async ({ page }) => {
  const puts = await mockGitHub(page);
  await openApp(page, { tab: 'data' });
  await page.fill('#ghToken', ' ' + TOKEN + '​ ');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('bal.gh.token')))).toBe(TOKEN);
  await page.fill('#ghToken', TOKEN.replace('o', 'о'));   // Cyrillic о
  await expect(page.locator('#ghSetMsg')).toContainText('не из латиницы');
  await page.evaluate(() => window.__bal.stopDemo(false));
  await page.click('#btnGhSend');
  await expect(page.locator('#ghMsg')).toContainText('лишние символы');
  expect(puts).toHaveLength(0);
});

test.describe('decoder slots', () => {
  const V2 = { version: 2, updatedAt: '2026-10-15T10:00:00Z', fields: [{ key: 'speed_kmh', offset: 9, type: 'u16le', scale: 0.1 }] };
  const ls = (page, k) => page.evaluate(key => JSON.parse(localStorage.getItem(key)), k);

  test('an edit made just before switching sources is kept', async ({ page }) => {
    await page.route('**/decoders/current.json', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(V2) }));
    await openApp(page, { tab: 'decoder', storage: { 'bal.decoder': { json: false, fields: [{ key: 'custom', name: 'мой', offset: 3, type: 'u8', scale: 1 }] } } });
    await page.evaluate(() => window.__bal.stopDemo(false));
    await page.click('#t-decoder');
    await page.locator('#decFields [data-k="scale"]').first().fill('5');
    await page.click('#decRepoApply');                    // within 250 ms of the keystroke
    expect((await ls(page, 'bal.decoder')).fields[0].scale).toBe(5);
    expect(await ls(page, 'bal.decoder.active')).toBe('repo');
    await page.waitForTimeout(400);
    expect(await ls(page, 'bal.decoder.active')).toBe('repo');   // the late timer did not fork the repo decoder
  });

  test('several forks keep several backups, a JSON-only decoder counts', async ({ page }) => {
    await page.route('**/decoders/current.json', r => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(V2) }));
    await openApp(page, { tab: 'decoder', storage: { 'bal.decoder': { json: true, fields: [] } } });
    await page.evaluate(() => window.__bal.stopDemo(false));
    await page.click('#t-decoder');
    await page.click('#decRepoApply');
    await page.locator('#decFields [data-k="scale"]').first().fill('0.2');      // fork 1: JSON-only «мой» → backup
    await expect.poll(() => ls(page, 'bal.decoder.active')).toBe('mine');
    await page.click('#decSrcSeg button[data-src="repo"]');
    await expect(page.locator('#decSrcSeg button[data-src="repo"]')).toHaveText('Из репозитория v2');
    await page.locator('#decFields [data-k="scale"]').first().fill('0.3');      // fork 2: the 0.2 fork → backup
    await expect.poll(async () => (await ls(page, 'bal.decoder.backups') || []).length).toBe(2);
    const backups = await ls(page, 'bal.decoder.backups');
    expect(backups[0].decoder.fields[0].scale).toBe(0.2);
    expect(backups[1].decoder.json).toBe(true);
    await page.selectOption('#decBackupSel', '1');
    await page.click('#decBackup');
    expect(await page.evaluate(() => window.__bal.decoder.json)).toBe(true);
    expect((await ls(page, 'bal.decoder.backups')).map(b => b.decoder.fields.length ? b.decoder.fields[0].scale : 'json')).toEqual([0.3, 0.2]);
  });

  test('«Взять демо-конфиг» keeps the old «мой» as a backup', async ({ page }) => {
    await openApp(page, { tab: 'decoder', storage: { 'bal.decoder': { json: false, fields: [{ key: 'custom', name: 'мой', offset: 3, type: 'u8', scale: 1 }] } } });
    await page.evaluate(() => window.__bal.stopDemo(false));
    await page.click('#t-decoder');
    await page.click('#decLoadDemo');
    await expect.poll(async () => (await ls(page, 'bal.decoder.backups') || []).length).toBe(1);
    expect((await ls(page, 'bal.decoder.backups'))[0].decoder.fields[0].name).toBe('мой');
  });
});
