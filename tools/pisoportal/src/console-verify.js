// TechNet PisoHub: checks an ECDSA P-256 / SHA-256 signature in the browser (plain HTTP pages have no crypto.subtle). The curve and
// SHA-256 code is the same as esp32_firmware/web/adminqr.js (which signs); this file only adds verification.
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
    const fromB64 = (t) => Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
    const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

    /** The point of a DER SubjectPublicKeyInfo (base64): its last 65 bytes are 04 || x || y. */
    function publicPoint(spkiB64) {
        const d = fromB64(spkiB64);
        if (d.length < 65) throw new Error('bad key');
        const q = d.slice(d.length - 65);
        if (q[0] !== 4) throw new Error('bad key');
        return [toBig(q.slice(1, 33)), toBig(q.slice(33)), 1n];
    }
    /** r and s of a DER ECDSA signature (base64). */
    function parseSig(sigB64) {
        const d = fromB64(sigB64);
        if (d.length < 8 || d[0] !== 0x30 || d[1] !== d.length - 2 || d[2] !== 0x02) throw new Error('bad signature');
        const rl = d[3];
        if (d[4 + rl] !== 0x02 || d[5 + rl] !== d.length - 6 - rl) throw new Error('bad signature');
        return [toBig(d.slice(4, 4 + rl)), toBig(d.slice(6 + rl))];
    }
    function verify(spkiB64, message, sigB64) {
        try {
            const Q = publicPoint(spkiB64);
            const [r, s] = parseSig(sigB64);
            if (r <= 0n || r >= N || s <= 0n || s >= N) return false;
            const z = toBig(sha256(new TextEncoder().encode(message))) % N;
            const w = inv(s, N);
            const R = affine(add(mul((z * w) % N, G), mul((r * w) % N, Q)));
            return !!R && mod(R[0], N) === r;
        } catch (e) {
            return false;
        }
    }
    window.PisoVerify = { verify: verify, sha256hex: (bytes) => hex(sha256(bytes)) };
})();
