// Checks the setup page's provisioning helpers (validation of what is sent to the phone's shell, and the extras).
// Run: node scripts/test_provisioning.js
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const win = { location: { search: '' }, addEventListener() {}, navigator: {} };
win.window = win;
const ctx = vm.createContext({ window: win, console: { log() {}, warn() {}, error() {}, info() {}, debug() {} }, setTimeout, clearTimeout, navigator: {}, document: { addEventListener() {}, getElementById() { return null; } },
    fetch: async () => { throw new Error('offline'); }, URL, URLSearchParams, TextEncoder, TextDecoder, Promise });
vm.runInContext(fs.readFileSync(path.join(__dirname, '..', 'website', 'js', 'webadb_manager.js'), 'utf8'), ctx);
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
console.log(`${checks} checks, ${failures} failures`);
process.exit(failures ? 1 : 0);
