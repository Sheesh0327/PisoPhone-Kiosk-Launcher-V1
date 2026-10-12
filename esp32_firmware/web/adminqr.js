/*
 * The owner's QR login, in the browser. The box's pages are plain HTTP, where the browser's own crypto (crypto.subtle) is
 * not available, so this file carries what it needs: SHA-256 and ECDSA P-256 signing (with BigInt), and it reads the QR code
 * from a photo with jsQR (jsqr.js).
 *
 * Flow: PisoAdminQr.login(photoFile) asks the box for a challenge, reads the QR code in the photo (PISOADMIN1.<d>.<x>.<y>: a
 * private key and its public point), signs "pisophone-admin-v1|<nonce>|<box id>" and posts only the signature. The private key
 * never leaves this page. The box answers with a session cookie.
 */
(function () {
    'use strict';
    const P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffffn;
    const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;
    const GX = 0x6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296n;
    const GY = 0x4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5n;

    const mod = (a, m) => { const r = a % m; return r >= 0n ? r : r + m; };
    function powmod(b, e, m) {
        let r = 1n;
        b = mod(b, m);
        while (e > 0n) {
            if (e & 1n) r = (r * b) % m;
            b = (b * b) % m;
            e >>= 1n;
        }
        return r;
    }
    const inv = (a, m) => powmod(a, m - 2n, m);   // (both moduli are prime)

    // Points are Jacobian [X, Y, Z]; Z = 0 is the point at infinity.
    function dbl(pt) {
        const [X, Y, Z] = pt;
        if (Z === 0n || Y === 0n) return [0n, 1n, 0n];
        const zz = (Z * Z) % P;
        const m = mod(3n * (X - zz) * (X + zz), P);   // (the curve has a = -3)
        const yy = (Y * Y) % P;
        const s = mod(4n * X * yy, P);
        const x3 = mod(m * m - 2n * s, P);
        const y3 = mod(m * (s - x3) - 8n * yy * yy, P);
        return [x3, y3, mod(2n * Y * Z, P)];
    }
    function add(p1, p2) {
        if (p1[2] === 0n) return p2;
        if (p2[2] === 0n) return p1;
        const [X1, Y1, Z1] = p1, [X2, Y2, Z2] = p2;
        const z1z1 = (Z1 * Z1) % P, z2z2 = (Z2 * Z2) % P;
        const u1 = (X1 * z2z2) % P, u2 = (X2 * z1z1) % P;
        const s1 = (Y1 * Z2 * z2z2) % P, s2 = (Y2 * Z1 * z1z1) % P;
        if (u1 === u2) return s1 === s2 ? dbl(p1) : [0n, 1n, 0n];
        const h = mod(u2 - u1, P), r = mod(s2 - s1, P);
        const hh = (h * h) % P, hhh = (h * hh) % P, v = (u1 * hh) % P;
        const x3 = mod(r * r - hhh - 2n * v, P);
        const y3 = mod(r * (v - x3) - s1 * hhh, P);
        return [x3, y3, mod(h * Z1 * Z2, P)];
    }
    function mul(k, pt) {
        let r = [0n, 1n, 0n];
        for (let i = k.toString(2).length - 1; i >= 0; i--) {
            r = dbl(r);
            if ((k >> BigInt(i)) & 1n) r = add(r, pt);
        }
        return r;
    }
    function affine(pt) {
        if (pt[2] === 0n) return null;
        const zi = inv(pt[2], P), zi2 = (zi * zi) % P;
        return [(pt[0] * zi2) % P, (pt[1] * zi2 * zi) % P];
    }
    const G = [GX, GY, 1n];

    // SHA-256 of a Uint8Array.
    const K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2]);
    function sha256(bytes) {
        const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
        const len = bytes.length, total = (((len + 8) >> 6) + 1) << 6;
        const m = new Uint8Array(total);
        m.set(bytes);
        m[len] = 0x80;
        const dv = new DataView(m.buffer);
        dv.setUint32(total - 8, Math.floor(len / 0x20000000), false);
        dv.setUint32(total - 4, (len << 3) >>> 0, false);
        const w = new Uint32Array(64);
        const rotr = (x, n) => (x >>> n) | (x << (32 - n));
        for (let off = 0; off < total; off += 64) {
            for (let i = 0; i < 16; i++) w[i] = dv.getUint32(off + 4 * i, false);
            for (let i = 16; i < 64; i++) {
                const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
            }
            let [a, b, c, d, e, f, g, hh] = h;
            for (let i = 0; i < 64; i++) {
                const t1 = (hh + (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) + ((e & f) ^ (~e & g)) + K[i] + w[i]) | 0;
                const t2 = ((rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
                hh = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh;
        }
        const out = new Uint8Array(32), o = new DataView(out.buffer);
        for (let i = 0; i < 8; i++) o.setUint32(4 * i, h[i], false);
        return out;
    }

    const toBig = (bytes) => BigInt('0x' + Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('') || '0');
    function unb64u(text) {
        const s = atob(text.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((text.length + 3) % 4));
        return Uint8Array.from(s, (c) => c.charCodeAt(0));
    }
    function derInt(n) {
        let hex = n.toString(16);
        if (hex.length % 2) hex = '0' + hex;
        if (parseInt(hex.slice(0, 2), 16) >= 0x80) hex = '00' + hex;
        const bytes = hex.match(/../g).map((h) => parseInt(h, 16));
        return [0x02, bytes.length, ...bytes];
    }
    function randomScalar() {
        for (;;) {
            const b = new Uint8Array(32);
            crypto.getRandomValues(b);
            const k = toBig(b);
            if (k > 0n && k < N) return k;
        }
    }

    /** "PISOADMIN1.<d>.<x>.<y>" -> {d, x, y} (BigInt), checking that the public point belongs to the private number. */
    function parsePayload(text) {
        const parts = String(text || '').trim().split('.');
        if (parts.length !== 4 || parts[0] !== 'PISOADMIN1') throw new Error('That is not a PisoPhone admin code.');
        let d, x, y;
        try {
            [d, x, y] = parts.slice(1).map((p) => toBig(unb64u(p)));
        } catch (e) {
            throw new Error('The admin code could not be read.');
        }
        if (d <= 0n || d >= N) throw new Error('The admin code is not valid.');
        const pub = affine(mul(d, G));
        if (!pub || pub[0] !== x || pub[1] !== y) throw new Error('The photo was misread (the code is damaged). Try again.');
        return { d, x, y };
    }

    /** The ECDSA P-256 / SHA-256 signature of a message (a string), DER-encoded, base64. */
    function sign(key, message) {
        const z = toBig(sha256(new TextEncoder().encode(message))) % N;
        for (;;) {
            const k = randomScalar();
            const R = affine(mul(k, G));
            const r = mod(R[0], N);
            if (r === 0n) continue;
            const s = mod(inv(k, N) * (z + r * key.d), N);
            if (s === 0n) continue;
            const body = [...derInt(r), ...derInt(s)];
            return btoa(String.fromCharCode(0x30, body.length, ...body));
        }
    }

    function decodeImage(img) {
        // jsQR works on pixels. Photos are big: try a few sizes (a smaller copy often reads better than the full picture).
        for (const max of [1000, 1600, 600]) {
            const scale = Math.min(1, max / Math.max(img.naturalWidth, img.naturalHeight));
            const canvas = document.createElement('canvas');
            canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
            canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
            const ctx = canvas.getContext('2d', { willReadFrequently: true });
            ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
            const data = ctx.getImageData(0, 0, canvas.width, canvas.height);
            const found = window.jsQR(data.data, data.width, data.height, { inversionAttempts: 'attemptBoth' });
            if (found && found.data) return found.data;
        }
        return null;
    }

    function readPhoto(file) {
        return new Promise((resolve, reject) => {
            const url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => {
                try {
                    resolve(decodeImage(img));
                } catch (e) {
                    reject(e);
                } finally {
                    URL.revokeObjectURL(url);
                }
            };
            img.onerror = () => {
                URL.revokeObjectURL(url);
                reject(new Error('That file is not a picture.'));
            };
            img.src = url;
        });
    }

    async function json(path, options) {
        const res = await fetch(path, Object.assign({ credentials: 'same-origin' }, options || {}));
        let data = {};
        try { data = await res.json(); } catch (e) { /* not JSON */ }
        return { ok: res.ok, status: res.status, data };
    }

    /** Photo of the admin QR code -> signed in (the box set a session cookie). Rejects with a message for the person. */
    async function login(file) {
        if (!window.jsQR) throw new Error('The QR reader is not loaded.');
        const text = await readPhoto(file);
        if (!text) throw new Error('No QR code found in the photo. Fill the frame with the code and keep it sharp.');
        const key = parsePayload(text);
        const challenge = await json('/api/superadmin/challenge');
        if (!challenge.ok || !challenge.data.nonce) throw new Error('The box did not give a challenge. Try again.');
        const signature = sign(key, 'pisophone-admin-v1|' + challenge.data.nonce + '|' + challenge.data.box);
        const done = await json('/api/superadmin/qr_login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: 'nonce=' + encodeURIComponent(challenge.data.nonce) + '&sig=' + encodeURIComponent(signature)
        });
        if (done.status === 429) throw new Error('Too many tries. Wait a minute.');
        if (!done.ok) throw new Error((done.data && done.data.message) || 'This code is not accepted by this box.');
        return true;
    }

    /** The sign-in page: a photo from `input` signs in and leads on to the page that was asked for (data-next on the body). */
    function bindLoginPage(input, message) {
        input.addEventListener('change', (e) => {
            const file = e.target.files && e.target.files[0];
            if (!file) return;
            message.textContent = 'Reading the code...';
            login(file).then(() => {
                let next = document.body.dataset.next || '/';
                if (next.charAt(0) !== '/' || next.charAt(1) === '/') next = '/';
                location.href = next;
            }).catch((err) => {
                message.textContent = err.message || 'Could not sign in.';
            });
            e.target.value = '';
        });
    }

    window.PisoAdminQr = { login, parsePayload, sign, sha256, readPhoto, bindLoginPage };
})();
