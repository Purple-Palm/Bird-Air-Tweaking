const { test, expect, openApp, entries, uuid16 } = require('./helpers');

const FFE1 = uuid16('ffe1');
const OTA_CHR = '00010203-0405-0607-0809-0a0b0c0d2b12';
const str = s => Array.from(s, c => c.charCodeAt(0)).map(x => x.toString(16).padStart(2, '0')).join(' ').toUpperCase();
const writesTo = (page, u) => page.evaluate(uu => window.__fakeBle.calls.filter(c => c[0] === 'writeData' && c[1] === uu).map(c => c[2]), u);

async function connect(page) {
  await page.click('#btnConnect');
  await expect(page.locator('#connPill')).toContainText('На связи');
}

test('control tab is gated by the write switch and a connection', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'control' });
  await expect(page.locator('#ctlGate')).toBeVisible();
  await expect(page.locator('#ctlGateText')).toContainText('включи отправку');
  await expect(page.locator('#ctlCard')).toBeVisible();          // buttons are on screen…
  await expect(page.locator('#ctlModes .bigbtn').first()).toBeDisabled();   // …but locked
  await expect(page.locator('#ctlLightOn')).toBeDisabled();
  await page.click('#ctlGateBtns button');                 // enable writes
  await expect(page.locator('#ctlModes .bigbtn').first()).toBeDisabled();   // still no connection
  await expect(page.locator('#ctlGateText')).toContainText('управлять');
  await page.click('#ctlGateBtns button');                 // connect (stops the demo)
  await expect(page.locator('#connPill')).toContainText('На связи');
  await expect(page.locator('#ctlCard')).toBeVisible();
  await expect(page.locator('#ctlModes .bigbtn').first()).toBeEnabled();
});

test('mode and light buttons send the documented commands, never OTA', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'control', storage: { 'bal.settings': { allowWrite: true } } });
  await connect(page);
  await page.click('#ctlModes .bigbtn[data-mode="3"]');
  await page.click('#ctlLightOn');
  await page.click('#ctlVer');
  await expect.poll(() => writesTo(page, FFE1)).toEqual([str('+MODE=3'), str('+HLGT=1'), str('+SVGT=?')]);
  await expect(page.locator('#ctlMsg')).toContainText('отправлено');
  expect(await writesTo(page, OTA_CHR)).toEqual([]);
  // tx entries are logged
  const tx = (await entries(page, { k: 'tx' })).map(e => e.h);
  expect(tx).toContain(str('+MODE=3'));
});

test('KEY? / passcode: no UI, and the bytes are refused when sent', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'control', storage: { 'bal.settings': { allowWrite: true } } });
  await connect(page);
  await expect(page.locator('#ctlHandshake')).toBeHidden();
  await expect(page.locator('#ctlProbeCard details')).toBeHidden();
  const sent = await page.evaluate(() => window.__bal.sendBleCommand(window.__bal.CMD.challenge, 'test', document.querySelector('#ctlProbeMsg')));
  expect(sent).toBe(false);
  await expect(page.locator('#ctlProbeMsg')).toContainText('ошибка 37');
  expect(await writesTo(page, FFE1)).toEqual([]);
});

test('with the write switch off, a command is refused', async ({ page }) => {
  await openApp(page, { bluetooth: true, tab: 'control' });
  await connect(page);
  const refused = await page.evaluate(() => window.__bal.sendBleCommand(window.__bal.CMD.lightOn, 'test'));
  expect(refused).toBe(false);
  expect(await writesTo(page, FFE1)).toEqual([]);
});
