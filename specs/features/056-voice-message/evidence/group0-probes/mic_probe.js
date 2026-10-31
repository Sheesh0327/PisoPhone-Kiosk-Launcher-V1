// Feature 056, group 0 (a): what a browser gives the box's admin page (plain http:// on a LAN address) for a microphone.
// Run: node mic_probe.js <lan-ip>      (the LAN address matters: 127.0.0.1 and localhost count as secure, a 192.168.x.x does not)
const http = require('http'); const fs = require('fs'); const os = require('os'); const path = require('path');
const { chromium } = require('playwright');

const HOST = process.argv[2] || '192.0.2.2';
const PAGE = `<!doctype html><meta charset=utf-8><title>probe</title><body>probe</body>`;
const server = http.createServer((req, res) => { res.setHeader('content-type', 'text/html'); res.end(PAGE); });

// Runs in the page. Reports what the admin page's recorder code would see.
async function probe(opts) {
  const out = { origin: location.origin, isSecureContext: window.isSecureContext,
    mediaDevices: typeof navigator.mediaDevices, getUserMedia: !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia),
    MediaRecorder: typeof MediaRecorder, AudioContext: typeof (window.AudioContext || window.webkitAudioContext),
    AudioWorklet: typeof AudioWorkletNode };
  if (!out.getUserMedia) return out;
  let stream;
  try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }); out.gum = 'ok'; }
  catch (e) { out.gum = e.name + ': ' + e.message; return out; }
  if (!opts.record) { stream.getTracks().forEach(t => t.stop()); return out; }
  const track = stream.getAudioTracks()[0];
  out.trackSettings = track.getSettings();
  // 1. MediaRecorder: which container does the browser give us?
  out.mediaRecorderTypes = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/mp4'].filter(t => MediaRecorder.isTypeSupported(t));
  const rec = new MediaRecorder(stream);
  const chunks = [];
  rec.ondataavailable = e => chunks.push(e.data);
  const stopped = new Promise(r => rec.onstop = r);
  rec.start(); await new Promise(r => setTimeout(r, 3000)); rec.stop(); await stopped;
  const blob = new Blob(chunks, { type: rec.mimeType });
  out.mediaRecorder = { mimeType: rec.mimeType, bytesFor3s: blob.size };
  // 2. Raw samples with an AudioContext (what the ADPCM encoder needs), 3 s, then resampled to 8 kHz.
  const ctx = new (window.AudioContext || window.webkitAudioContext)();
  out.audioContextRate = ctx.sampleRate;
  const src = ctx.createMediaStreamSource(stream);
  const blocks = [];
  let how;
  if (typeof AudioWorkletNode !== 'undefined') {
    const code = 'class P extends AudioWorkletProcessor{process(i){const c=i[0][0];if(c)this.port.postMessage(c.slice());return true}}registerProcessor("pcm",P)';
    await ctx.audioWorklet.addModule(URL.createObjectURL(new Blob([code], { type: 'application/javascript' })));
    const node = new AudioWorkletNode(ctx, 'pcm');
    node.port.onmessage = e => blocks.push(e.data);
    src.connect(node); how = 'AudioWorklet';
  } else {
    const node = ctx.createScriptProcessor(4096, 1, 1);
    node.onaudioprocess = e => blocks.push(e.inputBuffer.getChannelData(0).slice());
    src.connect(node); node.connect(ctx.destination); how = 'ScriptProcessor';
  }
  await new Promise(r => setTimeout(r, 3000));
  stream.getTracks().forEach(t => t.stop());
  const n = blocks.reduce((a, b) => a + b.length, 0);
  const pcm = new Float32Array(n); let o = 0; for (const b of blocks) { pcm.set(b, o); o += b.length; }
  let peak = 0; for (const v of pcm) peak = Math.max(peak, Math.abs(v));
  const t0 = performance.now();
  const off = new OfflineAudioContext(1, Math.ceil(n * 8000 / ctx.sampleRate), 8000);
  const buf = off.createBuffer(1, n, ctx.sampleRate); buf.copyToChannel(pcm, 0);
  const s = off.createBufferSource(); s.buffer = buf; s.connect(off.destination); s.start();
  const r8 = await off.startRendering();
  out.capture = { how, samplesAtDeviceRate: n, secondsCaptured: +(n / ctx.sampleRate).toFixed(2), peak: +peak.toFixed(3),
    samplesAt8k: r8.length, adpcmBytesFor3s: Math.ceil(r8.length / 2), resampleMs: Math.round(performance.now() - t0) };
  // 3. The same request from inside a frame, the way the router console frames the box page.
  out.frames = {};
  for (const allow of ['', 'microphone']) {
    const f = document.createElement('iframe'); f.setAttribute('allow', allow); f.src = '/frame';
    document.body.appendChild(f); await new Promise(r => f.onload = r);
    out.frames['allow="' + allow + '"'] = await f.contentWindow.navigator.mediaDevices.getUserMedia({ audio: true })
      .then(st => { st.getTracks().forEach(t => t.stop()); return 'ok'; }, e => e.name + ': ' + e.message);
  }
  ctx.close();
  return out;
}

async function run(label, url, args, opts = {}) {
  // The full Chrome in its new headless mode, with its own user-data-dir: the only launch that honours the insecure-origin setting
  // (the default headless shell ignores it; found in flag_try.js).
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pw-udd-'));
  const ctx = await chromium.launchPersistentContext(dir, { executablePath: '/opt/pw-browsers/chromium', headless: false, args: ['--headless=new', '--no-proxy-server', ...args] });
  const page = await ctx.newPage();
  await page.goto(url);
  const res = await page.evaluate(`(${probe.toString()})(${JSON.stringify(opts)})`).catch(e => ({ error: String(e) }));
  console.log('\n== ' + label + '\n' + JSON.stringify(res, null, 1));
  await ctx.close();
}

server.listen(0, '0.0.0.0', async () => {
  const port = server.address().port;
  const lan = `http://${HOST}:${port}/`;
  const fake = ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'];
  
  await run('T1 plain http on a LAN address, no setting (what an owner gets today)', lan, fake);
  await run('T2 same address after the one-time Chrome setting (unsafely-treat-insecure-origin-as-secure)', lan,
    [...fake, `--unsafely-treat-insecure-origin-as-secure=http://${HOST}:${port}`], { record: true });
  await run('T3 setting made, but the owner says "Block" to the prompt', lan,
    ['--use-fake-device-for-media-stream', `--unsafely-treat-insecure-origin-as-secure=http://${HOST}:${port}`, '--deny-permission-prompts']);
  await run('T4 setting made, accepted, but the device has no microphone', lan,
    ['--use-fake-ui-for-media-stream', `--unsafely-treat-insecure-origin-as-secure=http://${HOST}:${port}`]);
  await run('T5 for reference: localhost counts as secure with no setting', `http://127.0.0.1:${port}/`, fake);
  server.close();
});
