/**
 * PisoPhone setup page: what the box's link carries, checked once, and the QR setup code.
 *
 * Two ways to set a phone up, both fed from here:
 *   1. QR code (main): on a factory-reset phone, six taps on the welcome screen open Android's QR reader. The code tells
 *      Android which Wi-Fi to join, where to download the app and the hash of its signing certificate, and carries the
 *      box's details for the app (admin extras). Android installs the app, makes it the device owner, and the app stores
 *      the details (app: provisioning/QrProvisioning.kt). No computer, cable or developer options.
 *   2. USB cable (fallback, js/webadb_manager.js): the computer installs the app over ADB.
 */
(function () {
    const PACKAGE_NAME = "com.pisophone.kiosk";
    const ADMIN_COMPONENT = `${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver`;
    const DEFAULT_SSID = "PisoKiosk";
    const MAX_SUPPORTED_SLOTS = 6;

    // Single-quote a value for the phone's shell (USB setup). Every value from the link goes through this.
    function shellQuote(value) {
        return "'" + String(value).replace(/'/g, "'\\''") + "'";
    }

    // Only well-formed values are ever passed on (to the phone's shell or into the QR code); a malformed one throws.
    function validateProvisioning(raw) {
        const out = {};
        const fail = (field, rule) => { throw new Error(`Invalid provisioning parameter "${field}": ${rule}`); };
        const has = (v) => v != null && v !== '';
        if (has(raw.mac)) {
            if (!/^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$/.test(raw.mac)) fail('mac', 'expected 6 hex bytes like AA:BB:CC:DD:EE:FF');
            out.mac = raw.mac.toUpperCase();
        }
        if (has(raw.slot)) {
            if (!/^[0-9]{1,2}$/.test(String(raw.slot))) fail('slot', 'expected a number');
            const n = parseInt(raw.slot, 10);
            if (n < 1 || n > MAX_SUPPORTED_SLOTS) fail('slot', `expected 1-${MAX_SUPPORTED_SLOTS}`);
            out.slot = n;
        }
        if (has(raw.name)) {
            if (!/^[A-Za-z0-9 _.\-]{1,40}$/.test(raw.name)) fail('name', 'letters, digits, space, _ . - only (max 40)');
            out.name = raw.name;
        }
        if (has(raw.secret)) {
            if (!/^[A-Za-z0-9_.+=\-]{1,128}$/.test(raw.secret)) fail('secret', 'unexpected characters');
            out.secret = raw.secret;
        }
        if (has(raw.wifiSsid)) {
            if (!/^[\x20-\x7E]{1,32}$/.test(raw.wifiSsid)) fail('wifi name', 'printable characters only (max 32)');
            out.wifiSsid = raw.wifiSsid;
        }
        if (has(raw.wifiPass)) {
            if (!/^[\x20-\x7E]{8,63}$/.test(raw.wifiPass)) fail('wifi password', '8-63 printable characters');
            out.wifiPass = raw.wifiPass;
        }
        return out;
    }

    // The same values as the app's intent extras (USB setup), quoted for the shell.
    function buildProvisioningExtras(prov) {
        let extras = '';
        if (prov.secret) extras += ` --es secret ${shellQuote(prov.secret)}`;
        if (prov.mac) extras += ` --es mac ${shellQuote(prov.mac)}`;
        if (prov.slot) extras += ` --ei slot ${prov.slot}`;
        if (prov.name) extras += ` --es name ${shellQuote(prov.name)}`;
        // without the kiosk Wi-Fi a new phone cannot reach its box, so it could never pair or learn its admin PIN
        if (prov.wifiPass) extras += ` --es wifi_ssid ${shellQuote(prov.wifiSsid || DEFAULT_SSID)} --es wifi_pass ${shellQuote(prov.wifiPass)}`;
        return extras;
    }

    /**
     * The QR setup code's content (Android's provisioning JSON). `app` is the site's update/app.json: the APK's address
     * and the SHA-256 of its signing certificate (signatureChecksum, published by CI). Throws when something is missing.
     */
    function buildQrPayload(prov, app, pageUrl) {
        if (!app || !/^[A-Za-z0-9_-]{43}$/.test(app.signatureChecksum || '')) {
            throw new Error("The published app has no signing fingerprint yet (update/app.json): use the USB cable for now.");
        }
        if (!prov.wifiPass) throw new Error("Enter the PisoKiosk Wi-Fi password: the phone joins it to download PisoPhone.");
        const apkUrl = app.url || new URL('update/app-release.apk', pageUrl).href;
        if (!/^https:\/\//.test(apkUrl) && !/^http:\/\/(127\.0\.0\.1|localhost)[:/]/.test(apkUrl)) {
            throw new Error("The app must be downloaded over https.");
        }
        const extras = {};
        if (prov.secret) extras.secret = prov.secret;
        if (prov.mac) extras.mac = prov.mac;
        if (prov.slot) extras.slot = prov.slot;
        if (prov.name) extras.name = prov.name;
        extras.wifi_ssid = prov.wifiSsid || DEFAULT_SSID;
        extras.wifi_pass = prov.wifiPass;
        return {
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": ADMIN_COMPONENT,
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": apkUrl,
            "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": app.signatureChecksum,
            "android.app.extra.PROVISIONING_WIFI_SSID": prov.wifiSsid || DEFAULT_SSID,
            "android.app.extra.PROVISIONING_WIFI_PASSWORD": prov.wifiPass,
            "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
            "android.app.extra.PROVISIONING_WIFI_HIDDEN": true,
            "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
            "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
            "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": extras
        };
    }

    /** The QR code as an SVG tag (js/qrcode.js, error correction M). */
    function qrSvg(text, cellSize) {
        const qr = window.qrcode(0, 'M');
        qr.addData(text, 'Byte');
        qr.make();
        return qr.createSvgTag({ cellSize: cellSize || 4, margin: 4, scalable: true });
    }

    /**
     * What the box's link carried, read once. Values may come after '#' (a fragment never reaches a web server, its logs
     * or a Referer) or, from older boxes, in the query; the fragment wins. The secret and the Wi-Fi password are then taken
     * out of the address bar and the history; the page keeps them in memory.
     */
    function readPageParams(loc, hist) {
        const p = new URLSearchParams((loc && loc.search) || '');
        new URLSearchParams(((loc && loc.hash) || '').replace(/^#/, '')).forEach((v, k) => p.set(k, v));
        if (hist && loc && (p.has('secret') || p.has('wifi_pass'))) {
            const shown = new URLSearchParams(loc.search || '');
            shown.delete('secret');
            shown.delete('wifi_pass');
            const q = shown.toString();
            try { hist.replaceState(null, '', (loc.pathname || '/') + (q ? '?' + q : '')); } catch (e) {}
        }
        return p;
    }

    const params = readPageParams(typeof window !== 'undefined' ? window.location : null, typeof window !== 'undefined' ? window.history : null);
    window.PisoProvisioning = {
        PACKAGE_NAME, ADMIN_COMPONENT, DEFAULT_SSID,
        shellQuote, validateProvisioning, buildProvisioningExtras, buildQrPayload, qrSvg, readPageParams, params
    };
})();
