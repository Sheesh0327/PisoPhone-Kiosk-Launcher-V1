/**
 * The coin box flasher (flash.html): writes the PisoPhone firmware to an ESP32 over USB from the browser (Web Serial,
 * Chrome or Edge), with Espressif's esptool-js (js/esptool-bundle.js, vendored). The images and their sha256 come from
 * flash/manifest.json, built by CI from this branch's firmware (one merged image per chip). Nothing to install.
 */
(function () {
    const BAUD = 460800;   // fast, and still reliable on the cheap USB-serial chips of most boards

    /** The image for the chip esptool-js detected ("ESP32-C3", "ESP32"...), or null. */
    function chooseBuild(manifest, chipName) {
        const name = String(chipName || '').toUpperCase().split(/\s|\(/)[0];
        return ((manifest && manifest.builds) || []).find(b => String(b.chip).toUpperCase() === name) || null;
    }

    /** MD5 (hex) of bytes: esptool-js uses it to read back and verify what was written to the flash. */
    function md5Hex(bytes) {
        const K = new Uint32Array(64), S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
        for (let i = 0; i < 64; i++) K[i] = Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296);
        const n = bytes.length, total = (((n + 8) >>> 6) + 1) << 6, m = new Uint8Array(total);
        m.set(bytes);
        m[n] = 0x80;
        const dv = new DataView(m.buffer);
        dv.setUint32(total - 8, (n * 8) >>> 0, true);
        dv.setUint32(total - 4, Math.floor(n / 536870912), true);
        let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
        for (let off = 0; off < total; off += 64) {
            let a = a0, b = b0, c = c0, d = d0;
            for (let i = 0; i < 64; i++) {
                let f, g;
                if (i < 16) { f = (b & c) | (~b & d); g = i; }
                else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) & 15; }
                else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) & 15; }
                else { f = c ^ (b | ~d); g = (7 * i) & 15; }
                const t = d;
                d = c; c = b;
                const x = (a + f + K[i] + dv.getUint32(off + 4 * g, true)) >>> 0, r = S[(i >> 4) * 4 + (i & 3)];
                b = (b + ((x << r) | (x >>> (32 - r)))) >>> 0;
                a = t;
            }
            a0 = (a0 + a) >>> 0; b0 = (b0 + b) >>> 0; c0 = (c0 + c) >>> 0; d0 = (d0 + d) >>> 0;
        }
        const out = new DataView(new ArrayBuffer(16));
        [a0, b0, c0, d0].forEach((v, i) => out.setUint32(4 * i, v, true));
        return Array.from(new Uint8Array(out.buffer), x => x.toString(16).padStart(2, '0')).join('');
    }

    async function sha256Hex(bytes) {
        const d = await crypto.subtle.digest('SHA-256', bytes);
        return Array.from(new Uint8Array(d), b => b.toString(16).padStart(2, '0')).join('');
    }

    /** The image file, checked against the manifest (size and sha256) before anything is written. */
    async function fetchImage(build, base) {
        const res = await fetch(new URL(build.file, base).href, { cache: 'no-store' });
        if (!res.ok) throw new Error(`The firmware image could not be downloaded (HTTP ${res.status}).`);
        const bytes = new Uint8Array(await res.arrayBuffer());
        if (bytes.length !== build.size || (await sha256Hex(bytes)) !== build.sha256) {
            throw new Error("The downloaded firmware does not match its checksum. Reload the page and try again.");
        }
        return bytes;
    }

    /**
     * Connect (the browser asks which USB port), detect the chip, write its image at its offset, restart the box.
     * `erase`: wipe the whole flash first (a new box, or a used one that must forget its old Wi-Fi and settings).
     */
    async function flash({ manifest, base, erase, log, progress }) {
        if (!navigator.serial) throw new Error("This browser cannot use USB serial. Use Google Chrome or Microsoft Edge on a computer.");
        const { ESPLoader, Transport } = await import(new URL('js/esptool-bundle.js', document.baseURI).href);
        log("Choose the coin box's USB port in the browser's window...");
        const port = await navigator.serial.requestPort();
        const transport = new Transport(port, false);
        const terminal = { clean() {}, writeLine(t) { if (/Chip is|Detecting|Connected|Erasing|Hash of data/i.test(t)) log(t); }, write() {} };
        try {
            const loader = new ESPLoader({ transport, baudrate: BAUD, terminal });
            log("Connecting... (if it hangs: hold the board's BOOT button, tap RESET, release BOOT)");
            await loader.main();
            const chip = loader.chip.CHIP_NAME;
            const build = chooseBuild(manifest, chip);
            if (!build) throw new Error(`This board is an ${chip}; PisoPhone firmware is built for ${manifest.builds.map(b => b.chip).join(' and ')}.`);
            log(`${chip} found. Installing PisoPhone firmware ${manifest.version} (${(build.size / 1048576).toFixed(2)} MB).`);
            const data = await fetchImage(build, base);
            if (erase) {
                log("Erasing the whole flash (about 15-30 s)...");
                await loader.eraseFlash();
            }
            await loader.writeFlash({
                fileArray: [{ data, address: build.offset }],
                flashMode: 'keep', flashFreq: 'keep', flashSize: 'keep',
                eraseAll: false, compress: true,
                calculateMD5Hash: md5Hex,   // the box's flash is read back and compared
                reportProgress: (_i, written, total) => progress(Math.round(100 * written / total))
            });
            log("Written and verified. Restarting the box...");
            await loader.after('hard_reset');
            return { chip, version: manifest.version };
        } finally {
            try { await transport.disconnect(); } catch (e) {}
        }
    }

    window.PisoFlasher = { chooseBuild, md5Hex, sha256Hex, fetchImage, flash };
})();
