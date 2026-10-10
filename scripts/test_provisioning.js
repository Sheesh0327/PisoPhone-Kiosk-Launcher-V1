// Checks the setup page's provisioning helpers (validation of what is sent to the phone's shell, and the extras).
// Run: node scripts/test_provisioning.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const win = { location: { search: '' }, addEventListener() {}, navigator: {} };
win.window = win;
const ctx = vm.createContext({ window: win, console: { log() {}, warn() {}, error() {}, info() {}, debug() {} }, setTimeout, clearTimeout, navigator: {}, document: { addEventListener() {}, getElementById() { return null; } },
    fetch: async () => { throw new Error('offline'); }, URL, URLSearchParams, TextEncoder, TextDecoder, Promise });
for (const f of ['qrcode.js', 'provisioning.js', 'webadb_manager.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'website', 'js', f), 'utf8'), ctx);
const P = win.PisoProvisioning;

let checks = 0, failures = 0;
const check = (ok, name) => { checks++; if (!ok) { failures++; console.log('FAIL:', name); } };
const throws = (fn) => { try { fn(); return false; } catch (e) { return true; } };

const v = P.validateProvisioning({ mac: 'aa:bb:cc:dd:ee:ff', slot: '2', name: 'Phone 2', secret: 'abcdefghijklmnop1234', wifiSsid: 'PisoKiosk', wifiPass: '3hC4RATnpQMJ' });
check(v.wifiSsid === 'PisoKiosk' && v.wifiPass === '3hC4RATnpQMJ', 'kiosk Wi-Fi passes validation');
const x = P.buildProvisioningExtras(v);
check(x.includes("--es wifi_ssid 'PisoKiosk'") && x.includes("--es wifi_pass '3hC4RATnpQMJ'"), 'extras carry the Wi-Fi: ' + x);
check(!P.buildProvisioningExtras(P.validateProvisioning({ mac: 'aa:bb:cc:dd:ee:ff' })).includes('wifi'), 'no Wi-Fi extras without a password');
check(P.buildProvisioningExtras({ wifiPass: 'password123' }).includes("wifi_ssid 'PisoKiosk'"), 'the name defaults to PisoKiosk');
check(throws(() => P.validateProvisioning({ wifiPass: 'short' })), 'a password under 8 characters is rejected');
check(throws(() => P.validateProvisioning({ wifiPass: 'x'.repeat(64) })), 'a password over 63 characters is rejected');
check(throws(() => P.validateProvisioning({ wifiSsid: 'x'.repeat(33), wifiPass: 'password123' })), 'a name over 32 characters is rejected');
check(throws(() => P.validateProvisioning({ wifiSsid: 'café', wifiPass: 'password123' })), 'non-ASCII names are rejected');
const q = P.buildProvisioningExtras(P.validateProvisioning({ wifiPass: "pa'ss$(id)word" }));
check(q.includes("'pa'\\''ss$(id)word'"), 'shell metacharacters stay inside quotes: ' + q);
check(!/wifi_pass [^']/.test(q), 'the password is always quoted');
// the box's link: the fragment wins over the query, and the secret leaves the address bar (kept in memory)
const replaced = [];
const hist = { replaceState(_s, _t, url) { replaced.push(url); } };
const pp = P.readPageParams({ pathname: '/', search: '?mac=aa%3Abb&slot=2&secret=old', hash: '#secret=abc123&wifi_pass=pw12345678' }, hist);
check(pp.get('secret') === 'abc123' && pp.get('wifi_pass') === 'pw12345678' && pp.get('mac') === 'aa:bb' && pp.get('slot') === '2', 'values from the fragment win over the query');
check(replaced.length === 1 && replaced[0] === '/?mac=aa%3Abb&slot=2', 'the secret is taken out of the address bar: ' + replaced[0]);
const plain = P.readPageParams({ pathname: '/', search: '?mac=aa', hash: '' }, { replaceState() { throw new Error('should not be called'); } });
check(plain.get('mac') === 'aa', 'a link without secrets is left as it is');
check(P.readPageParams(null, null).toString() === '', 'no location: no values');
check(!fs.readFileSync(path.join(__dirname, '..', 'website', 'js', 'webadb_manager.js'), 'utf8').includes('cdn.jsdelivr.net'), 'the ADB library is never loaded from another site');
// ---- the QR setup code ----
const app = { signatureChecksum: 'A'.repeat(43), versionCode: 340 };
const prov = P.validateProvisioning({ mac: 'aa:bb:cc:dd:ee:ff', slot: '2', name: 'Phone 2', secret: 'abcdefghijklmnop1234', wifiSsid: 'PisoKiosk', wifiPass: '3hC4RATnpQMJ' });
const qp = P.buildQrPayload(prov, app, 'https://pisophone.pages.dev/index.html?mac=aa');
const E = 'android.app.extra.';
check(qp[E + 'PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME'] === 'com.pisophone.kiosk/com.pisophone.kiosk.receiver.KioskDeviceAdminReceiver', 'the QR code names the app\'s device admin');
check(qp[E + 'PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION'] === 'https://pisophone.pages.dev/update/app-release.apk', 'it downloads the APK this site published: ' + qp[E + 'PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION']);
check(P.buildQrPayload(prov, app, 'https://beta.pisophone.pages.dev/')[E + 'PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION'] === 'https://beta.pisophone.pages.dev/update/app-release.apk', 'the beta site gives the beta APK');
check(P.buildQrPayload(prov, { ...app, url: 'https://github.com/x/releases/download/a/b.apk' }, 'https://pisophone.pages.dev/')[E + 'PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION'].startsWith('https://github.com/'), 'a release URL in app.json is used when there is one');
check(qp[E + 'PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM'] === app.signatureChecksum, 'it carries the signing certificate hash');
check(qp[E + 'PROVISIONING_WIFI_SSID'] === 'PisoKiosk' && qp[E + 'PROVISIONING_WIFI_PASSWORD'] === '3hC4RATnpQMJ' && qp[E + 'PROVISIONING_WIFI_HIDDEN'] === true && qp[E + 'PROVISIONING_WIFI_SECURITY_TYPE'] === 'WPA', 'the phone joins the hidden kiosk Wi-Fi');
const ex = qp[E + 'PROVISIONING_ADMIN_EXTRAS_BUNDLE'];
check(ex.secret === 'abcdefghijklmnop1234' && ex.mac === 'AA:BB:CC:DD:EE:FF' && ex.slot === 2 && ex.wifi_ssid === 'PisoKiosk' && ex.wifi_pass === '3hC4RATnpQMJ', 'the box\'s details go in the admin extras (names the app reads): ' + JSON.stringify(ex));
check(throws(() => P.buildQrPayload(prov, {}, 'https://pisophone.pages.dev/')), 'no signing hash published: no code (the USB cable is offered)');
check(throws(() => P.buildQrPayload({ ...prov, wifiPass: undefined }, app, 'https://pisophone.pages.dev/')), 'no Wi-Fi password: no code (the phone needs it to download the app)');
check(throws(() => P.buildQrPayload(prov, app, 'http://example.com/')), 'the app is never downloaded over plain http');
check(JSON.stringify(qp).length < 1200, 'the code stays small enough to scan: ' + JSON.stringify(qp).length + ' characters');
// ---- the page and the app must agree on what is valid (a value the app would refuse silently ends in "cannot find its box") ----
const kotlin = (f) => fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'java', 'com', 'pisophone', 'kiosk', f), 'utf8');
const secretRegex = new RegExp(kotlin('security/KioskSecurity.kt').match(/BOX_SECRET_REGEX = Regex\("([^"]+)"\)/)[1]);
for (const sample of ['a'.repeat(15), 'a'.repeat(16), 'a'.repeat(128), 'a'.repeat(129), 'abc def ghijklmnop', 'ab/cdefghijklmnopq', 'ABCdef123_.+=-ABCdef', 'abc\u00e9defghijklmnopq']) {
    const pageAccepts = !throws(() => P.validateProvisioning({ secret: sample }));
    check(pageAccepts === secretRegex.test(sample), `the page and the app agree on the secret ${JSON.stringify(sample.slice(0, 20))} (${sample.length} characters): page ${pageAccepts}, app ${secretRegex.test(sample)}`);
}
const wifiRule = kotlin('network/KioskWifi.kt').match(/password\.length in (\d+)\.\.(\d+)/);
for (const len of [7, 8, 63, 64]) {
    const pageAccepts = !throws(() => P.validateProvisioning({ wifiPass: 'x'.repeat(len) }));
    check(pageAccepts === (len >= +wifiRule[1] && len <= +wifiRule[2]), `the page and the app agree on a ${len}-character Wi-Fi password`);
}
// the admin extras the page sends are exactly names the app reads
const appReads = new Set([...kotlin('provisioning/QrProvisioning.kt').matchAll(/(?:getString|getInt|str)\("([a-z_]+)"/g)].map(m => m[1]));
for (const k of Object.keys(ex)) check(appReads.has(k), `the app reads the extra "${k}"`);
const withName = P.buildQrPayload(P.validateProvisioning({ mac: 'aa:bb:cc:dd:ee:ff', slot: '2', name: 'Phone 2', secret: 'abcdefghijklmnop1234', wifiPass: '3hC4RATnpQMJ' }), app, 'https://pisophone.pages.dev/');
check(!('name' in withName[E + 'PROVISIONING_ADMIN_EXTRAS_BUNDLE']), 'a phone with a slot is named by its slot, not by an extra name');
check('name' in P.buildQrPayload(P.validateProvisioning({ mac: 'aa:bb:cc:dd:ee:ff', name: 'Front desk', wifiPass: '3hC4RATnpQMJ' }), app, 'https://pisophone.pages.dev/')[E + 'PROVISIONING_ADMIN_EXTRAS_BUNDLE'], 'without a slot the name is kept');
// how dense the setup code is: a factory-reset phone's camera is the weakest link, so the code must stay small
const realApp = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'website', 'update', 'app.json'), 'utf8'));
const worst = P.buildQrPayload(P.validateProvisioning({ mac: 'AA:BB:CC:DD:EE:FF', slot: '10', name: 'PisoPhone 10', secret: 'A'.repeat(128), wifiSsid: 'PisoKiosk', wifiPass: 'x'.repeat(63) }), realApp, 'https://pisophone.pages.dev/');
const typical = P.buildQrPayload(P.validateProvisioning({ mac: 'AA:BB:CC:DD:EE:FF', slot: '1', name: 'PisoPhone 1', secret: 'A'.repeat(32), wifiSsid: 'PisoKiosk', wifiPass: '3hC4RATnpQMJ' }), realApp, 'https://pisophone.pages.dev/');
const qrVersion = (obj) => { const qr = vm.runInContext('qrcode', ctx)(0, 'L'); qr.addData(JSON.stringify(obj), 'Byte'); qr.make(); return (qr.getModuleCount() - 17) / 4; };
check(qrVersion(typical) <= 21, `a typical setup code is QR version ${qrVersion(typical)} (at most 21)`);
check(qrVersion(worst) <= 30, `even the longest allowed values stay at QR version ${qrVersion(worst)} (at most 30)`);
// ---- the USB fallback's messages ----
check(/remove the account again/.test(P.describeDeviceOwnerFailure('SecurityException: MANAGE_DEVICE_ADMINS')), 'Xiaomi: sign in, turn it on, remove the account');
check(/more than one user/.test(P.describeDeviceOwnerFailure('users')) && /account/.test(P.describeDeviceOwnerFailure('accounts')), 'users and accounts are named before anything is copied');
check(/Install via USB/.test(P.describeInstallFailure('Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]')), 'a blocked install names the Xiaomi switch');
const wm = fs.readFileSync(path.join(__dirname, '..', 'website', 'js', 'webadb_manager.js'), 'utf8');
check(!/pisophone\.pages\.dev/.test(wm) && !/settings put global/.test(wm), 'the USB setup uses only this site\'s APK and writes no Android settings');

check(!fs.readFileSync(path.join(__dirname, '..', 'website', 'index.html'), 'utf8').includes('cdn.tailwindcss.com'), 'no third-party script on the provisioning page');
console.log(`${checks} checks, ${failures} failures`);
process.exit(failures ? 1 : 0);
