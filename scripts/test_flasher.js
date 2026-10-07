// Checks the coin box flasher's helpers (website/js/flasher.js): which image for which chip, the read-back hash, and that a
// download not matching its manifest is refused before anything is written. Run: node scripts/test_flasher.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const nodeCrypto = require('crypto');

let served = null;
const win = {};
const ctx = vm.createContext({ window: win, crypto: globalThis.crypto, URL, navigator: {}, document: { baseURI: 'https://pisophone.pages.dev/flash.html' },
    fetch: async (url) => served ? { ok: true, status: 200, url, arrayBuffer: async () => served.buffer.slice(served.byteOffset, served.byteOffset + served.length) } : { ok: false, status: 404 } });
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'website', 'js', 'flasher.js'), 'utf8'), ctx);
const F = win.PisoFlasher;

let checks = 0, failures = 0;
const check = (ok, name) => { checks++; if (!ok) { failures++; console.log('FAIL:', name); } };

(async () => {
    const image = new Uint8Array(nodeCrypto.randomBytes(70000));
    const sha = nodeCrypto.createHash('sha256').update(image).digest('hex');
    const manifest = { version: '3.2.1', builds: [
        { env: 'esp32-c3-dev', chip: 'ESP32-C3', file: 'esp32-c3-dev.bin', offset: 0, size: image.length, sha256: sha },
        { env: 'esp32dev-dev', chip: 'ESP32', file: 'esp32dev-dev.bin', offset: 4096, size: image.length, sha256: sha }] };
    check(F.chooseBuild(manifest, 'ESP32-C3').env === 'esp32-c3-dev', 'an ESP32-C3 gets the C3 image');
    check(F.chooseBuild(manifest, 'ESP32').offset === 4096, 'a classic ESP32 gets its image, written at 0x1000');
    check(F.chooseBuild(manifest, 'ESP32-C3 (QFN32) (revision v0.4)').env === 'esp32-c3-dev', 'a long chip description still matches');
    check(F.chooseBuild(manifest, 'ESP32-S3') === null && F.chooseBuild(manifest, '') === null, 'a chip without an image gets none');
    for (const n of [0, 1, 55, 56, 64, 1000, 300001]) {
        const b = nodeCrypto.randomBytes(n);
        check(F.md5Hex(new Uint8Array(b)) === nodeCrypto.createHash('md5').update(b).digest('hex'), `the read-back MD5 is right for ${n} bytes`);
    }
    served = image;
    const got = await F.fetchImage(manifest.builds[0], new URL('https://pisophone.pages.dev/flash/'));
    check(got.length === image.length, 'an image matching its manifest is accepted');
    const bad = new Uint8Array(image); bad[100] ^= 1;
    served = bad;
    let refused = false;
    try { await F.fetchImage(manifest.builds[0], new URL('https://pisophone.pages.dev/flash/')); } catch (e) { refused = /checksum/.test(e.message); }
    check(refused, 'a changed image is refused before anything is written');
    served = null;
    let missing = false;
    try { await F.fetchImage(manifest.builds[0], new URL('https://pisophone.pages.dev/flash/')); } catch (e) { missing = /HTTP 404/.test(e.message); }
    check(missing, 'a missing image says so');
    check(!/https?:\/\/(?!pisophone)/.test(fs.readFileSync(path.join(__dirname, '..', 'website', 'flash.html'), 'utf8').replace(/https:\/\/pisophone\.pages\.dev\/install\.sh/, '')), 'the flasher page loads nothing from other sites');
    console.log(`${checks} checks, ${failures} failures`);
    process.exit(failures ? 1 : 0);
})();
