const { test, expect, openApp, entries, decodeContent, mockGitHub, pathOf, TOKEN } = require('./helpers');

/* A finished demo recording with a token in place. */
async function ready(page, opts = {}) {
  await openApp(page, { tab: 'data', storage: { 'bal.gh.token': TOKEN }, ...opts });
  await page.waitForTimeout(1200);
  await page.evaluate(() => window.__bal.stopDemo(false));
}
const send = async page => {
  await page.click('#btnGhSend');
  await expect(page.locator('#ghMsg')).not.toHaveText(/Готовлю|Отправляю/, { timeout: 15000 });
};

test('uploads an anonymized log with the token only in the header', async ({ page }) => {
  const puts = await mockGitHub(page);
  await ready(page);
  await page.fill('#ghLabel', 'Газ и тормоз');
  await expect(page.locator('#ghName')).toHaveText(/logs\/\d{8}-\d{6}-gaz-i-tormoz\.json/);
  await send(page);
  await expect(page.locator('#ghMsg')).toContainText('Отправлено');
  await expect(page.locator('#ghMsg a')).toHaveAttribute('href', /github\.com\/.+gaz-i-tormoz\.json$/);
  expect(puts).toHaveLength(1);
  const put = puts[0];
  expect(pathOf(put)).toMatch(/^logs\/\d{8}-\d{6}-gaz-i-tormoz\.json$/);
  expect(put.headers.authorization).toBe('Bearer ' + TOKEN);
  expect(put.headers['x-github-api-version']).toBe('2022-11-28');
  expect(put.body.branch).toBe('main');
  expect(JSON.stringify(put.body)).not.toContain(TOKEN);
  const log = decodeContent(put.body);
  expect(log.format).toBe('birdair-lab-log');
  expect(log.device).toEqual({ name: 'scooter', id: 'scooter' });
  expect(JSON.stringify(log)).not.toContain('Демо: Bird Air');
  expect(log.entries.length).toBe((await entries(page)).length);

  // nothing new recorded: a second press does not upload again
  await page.click('#btnGhSend');
  await expect(page.locator('#ghMsg')).toContainText('уже отправлен');
  expect(puts).toHaveLength(1);
});

test('keeps the device name when anonymizing is switched off', async ({ page }) => {
  const puts = await mockGitHub(page);
  await ready(page);
  await page.uncheck('#ghAnon');
  await send(page);
  expect(decodeContent(puts[0].body).device.name).toBe('Демо: Bird Air');
});

test('big logs go as .json.gz', async ({ page }) => {
  const puts = await mockGitHub(page);
  await ready(page);
  await page.evaluate(() => { window.__bal.ghLimits.fileMax = 1200; });
  await send(page);
  expect(puts).toHaveLength(1);
  expect(pathOf(puts[0])).toMatch(/\.json\.gz$/);
  const log = decodeContent(puts[0].body);
  expect(log.part).toBeUndefined();
  expect(log.entries.length).toBe((await entries(page)).length);
});

test('without CompressionStream a big log is cut into complete parts', async ({ page }) => {
  const puts = await mockGitHub(page);
  await ready(page, { noCompression: true });
  await page.evaluate(() => { window.__bal.ghLimits.fileMax = 1500; });
  await send(page);
  await expect(page.locator('#ghMsg')).toContainText('Отправлено');
  expect(puts.length).toBeGreaterThan(1);
  const parts = puts.map(p => decodeContent(p.body));
  const id = parts[0].part.id;
  puts.forEach((p, i) => expect(pathOf(p)).toBe(`logs/${id}-part${i + 1}of${puts.length}.json`));
  parts.forEach((p, i) => {
    expect(p.format).toBe('birdair-lab-log');
    expect(p.part).toEqual({ id, index: i + 1, count: puts.length });
    expect(Buffer.byteLength(JSON.stringify(p))).toBeLessThanOrEqual(1500);
  });
  expect(parts.flatMap(p => p.entries).length).toBe((await entries(page)).length);
  expect(puts.map(p => p.body.message)).toEqual(puts.map((_, i) => expect.stringContaining(`часть ${i + 1} из ${puts.length}`)));
});

test.describe('name taken', () => {
  test.use({ allowedErrors: [/Failed to load resource.*422/] });
  test('a taken name gets -2', async ({ page }) => {
    const puts = await mockGitHub(page, (req, n) => (n === 1 ? { status: 422, body: { message: 'Invalid request.\n\n"sha" wasn\'t supplied.' } } : null));
    await ready(page);
    await send(page);
    await expect(page.locator('#ghMsg')).toContainText('Отправлено');
    expect(puts.map(pathOf)).toEqual([expect.stringMatching(/\d-demo\.json$/), expect.stringMatching(/\d-demo-2\.json$/)]);
  });
});

test.describe('errors', () => {
  test.use({ allowedErrors: [/Failed to load resource/] });
  const cases = [
    [401, 'Bad credentials', 'Токен не принят (401)'],
    [403, 'Resource not accessible by personal access token', 'Contents: Read and write'],
    [404, 'Not Found', 'Репозиторий Purple-Palm/Bird-Air-Tweaking не найден (404)'],
    [404, 'Branch main not found', 'Ветка «main» не найдена'],
    [409, 'is at 1234 but expected 5678', 'Конфликт (409)'],
    [422, 'path contains a malformed path component', 'GitHub отклонил запись (422)'],
  ];
  for (const [status, message, text] of cases) {
    test(`${status} ${message}`, async ({ page }) => {
      await mockGitHub(page, () => ({ status, body: { message } }));
      await ready(page);
      await send(page);
      await expect(page.locator('#ghMsg')).toHaveClass(/err/);
      await expect(page.locator('#ghMsg')).toContainText(text);
      await expect(page.locator('#btnGhSend')).toBeEnabled();
    });
  }

  test('no network', async ({ page }) => {
    await page.route('https://api.github.com/**', r => r.abort('internetdisconnected'));
    await ready(page);
    await send(page);
    await expect(page.locator('#ghMsg')).toContainText('Нет связи с api.github.com');
  });

  test('access check names the missing branch', async ({ page }) => {
    await mockGitHub(page, req => (req.path.includes('/branches/') ? { status: 404, body: { message: 'Branch not found' } } : null));
    await ready(page);
    await page.fill('#ghBranch', 'nope');
    await page.click('#ghCheck');
    await expect(page.locator('#ghSetMsg')).toContainText('Ветка «nope» не найдена');
  });
});

test('missing token sends you to the settings', async ({ page }) => {
  const puts = await mockGitHub(page);
  await openApp(page, { tab: 'data' });
  await page.click('#btnGhSend');
  await expect(page.locator('#ghMsg')).toContainText('Нет токена');
  await expect(page.locator('#ghToken')).toBeFocused();
  expect(puts).toHaveLength(0);
});

test('access check and settings; the token stays out of settings and exports', async ({ page }) => {
  await mockGitHub(page);
  await openApp(page, { tab: 'data' });
  await expect(page.locator('#ghOwner')).toHaveValue('Purple-Palm');
  await expect(page.locator('#ghRepo')).toHaveValue('Bird-Air-Tweaking');
  await expect(page.locator('#ghBranch')).toHaveValue('main');
  await expect(page.locator('#ghFolder')).toHaveValue('logs');
  await page.fill('#ghToken', TOKEN);
  await page.fill('#ghFolder', 'logs/phone');
  await page.click('#ghCheck');
  await expect(page.locator('#ghSetMsg')).toContainText('Доступ есть: Purple-Palm/Bird-Air-Tweaking, ветка main');
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('bal.gh.token')))).toBe(TOKEN);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('bal.gh')).folder)).toBe('logs/phone');
  const others = await page.evaluate(() => Object.keys(localStorage).filter(k => k !== 'bal.gh.token').map(k => localStorage.getItem(k)).join('\n'));
  expect(others).not.toContain(TOKEN);
  const [dl] = await Promise.all([page.waitForEvent('download'), page.click('#btnExport')]);
  const text = require('fs').readFileSync(await dl.path(), 'utf8');
  expect(text).toContain('birdair-lab-log');
  expect(text).not.toContain(TOKEN);
  await page.click('#ghForget');
  await expect(page.locator('#ghToken')).toHaveValue('');
  expect(await page.evaluate(() => localStorage.getItem('bal.gh.token'))).toBeNull();
});
