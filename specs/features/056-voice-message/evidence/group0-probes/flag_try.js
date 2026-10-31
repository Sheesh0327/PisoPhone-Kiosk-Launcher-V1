const http = require('http'); const fs = require('fs'); const os = require('os'); const path = require('path');
const { chromium } = require('playwright');
const HOST = '192.0.2.2';
const server = http.createServer((q, r) => { r.setHeader('content-type', 'text/html'); r.end('<!doctype html><title>p</title>x'); });
async function check(label, launch) {
  try {
    const { page, close } = await launch();
    await page.goto(`http://${HOST}:${server.address().port}/`);
    console.log(label, '->', JSON.stringify(await page.evaluate(() => ({ secure: window.isSecureContext, md: typeof navigator.mediaDevices }))));
    await close();
  } catch (e) { console.log(label, 'ERROR', String(e).split('\n')[0]); }
}
server.listen(0, '0.0.0.0', async () => {
  const o = `http://${HOST}:${server.address().port}`;
  const flag = `--unsafely-treat-insecure-origin-as-secure=${o}`;
  const fake = ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--no-proxy-server'];
  const udd = () => fs.mkdtempSync(path.join(os.tmpdir(), 'pw-udd-'));
  await check('V1 persistent context + flag (own user-data-dir)', async () => { const c = await chromium.launchPersistentContext(udd(), { args: [flag, ...fake] }); return { page: await c.newPage(), close: () => c.close() }; });
  await check('V2 persistent context + flag + headless=new via full chrome', async () => { const c = await chromium.launchPersistentContext(udd(), { executablePath: '/opt/pw-browsers/chromium', headless: false, args: ['--headless=new', flag, ...fake] }); return { page: await c.newPage(), close: () => c.close() }; });
  await check('V3 launch() + flag + explicit --user-data-dir arg', async () => { const b = await chromium.launch({ args: [flag, `--user-data-dir=${udd()}`, ...fake] }); const p = await b.newPage(); return { page: p, close: () => b.close() }; });
  server.close();
});
