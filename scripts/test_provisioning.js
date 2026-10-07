// Checks the setup page's provisioning helpers (validation of what is sent to the phone's shell, and the extras).
// Run: node scripts/test_provisioning.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const win = { location: { search: '' }, addEventListener() {}, navigator: {} };
win.window = win;
const ctx = vm.createContext({ window: win, console: { log() {}, warn() {}, error() {}, info() {}, debug() {} }, setTimeout, clearTimeout, navigator: {}, document: { addEventListener() {}, getElementById() { return null; } },
    fetch: async () => { throw new Error('offline'); }, URL, URLSearchParams, TextEncoder, TextDecoder, Promise });
for (const f of ['provisioning.js', 'webadb_manager.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'website', 'js', f), 'utf8'), ctx);
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
// ---- the USB fallback's messages ----
check(/remove the account again/.test(P.describeDeviceOwnerFailure('SecurityException: MANAGE_DEVICE_ADMINS')), 'Xiaomi: sign in, turn it on, remove the account');
check(/more than one user/.test(P.describeDeviceOwnerFailure('users')) && /account/.test(P.describeDeviceOwnerFailure('accounts')), 'users and accounts are named before anything is copied');
check(/Install via USB/.test(P.describeInstallFailure('Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]')), 'a blocked install names the Xiaomi switch');
const wm = fs.readFileSync(path.join(__dirname, '..', 'website', 'js', 'webadb_manager.js'), 'utf8');
check(!/pisophone\.pages\.dev/.test(wm) && !/settings put global/.test(wm), 'the USB setup uses only this site\'s APK and writes no Android settings');

check(!fs.readFileSync(path.join(__dirname, '..', 'website', 'index.html'), 'utf8').includes('cdn.tailwindcss.com'), 'no third-party script on the provisioning page');
console.log(`${checks} checks, ${failures} failures`);
process.exit(failures ? 1 : 0);
