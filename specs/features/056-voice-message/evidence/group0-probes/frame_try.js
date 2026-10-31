// Feature 056, group 0 (a): the router console frames the box page from ANOTHER port (console port + 1) with allow="".
// Does the microphone work in that cross-origin frame? (boxOrigin() in console-ui.js builds the frame address.)
const http = require('http'); const fs = require('fs'); const os = require('os'); const path = require('path');
const { chromium } = require('playwright');
const HOST = '192.0.2.2';
const framePage = '<!doctype html><title>box page</title>box page';
const frameSrv = http.createServer((q, r) => { r.setHeader('content-type', 'text/html'); r.end(framePage); });
let framePort;
const parentSrv = http.createServer((q, r) => {
  r.setHeader('content-type', 'text/html');
  const allow = q.url.includes('allow=mic') ? 'microphone' : '';
  r.end(`<!doctype html><title>console</title><iframe id=f allow="${allow}" src="http://${HOST}:${framePort}/"></iframe>`);
});
frameSrv.listen(0, '0.0.0.0', () => { framePort = frameSrv.address().port; parentSrv.listen(0, '0.0.0.0', async () => {
  const pp = parentSrv.address().port;
  const flag = `--unsafely-treat-insecure-origin-as-secure=http://${HOST}:${pp},http://${HOST}:${framePort}`;
  for (const [label, q] of [['console frame as it is today: allow=""', '/'], ['frame with allow="microphone"', '/?allow=mic']]) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pw-udd-'));
    const ctx = await chromium.launchPersistentContext(dir, { executablePath: '/opt/pw-browsers/chromium', headless: false,
      args: ['--headless=new', '--no-proxy-server', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', flag] });
    const page = await ctx.newPage();
    await page.goto(`http://${HOST}:${pp}${q}`);
    await page.waitForSelector('iframe');
    const fr = page.frames().find(f => f.url().includes(':' + framePort));
    const res = await fr.evaluate(async () => ({ secure: isSecureContext, gum: await navigator.mediaDevices.getUserMedia({ audio: true }).then(s => { s.getTracks().forEach(t => t.stop()); return 'ok'; }, e => e.name + ': ' + e.message) }));
    console.log(label, '->', JSON.stringify(res));
    await ctx.close();
  }
  frameSrv.close(); parentSrv.close();
}); });
