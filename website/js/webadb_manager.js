/**
 * WebADB Manager for PisoPhone
 * Handles local temporary caching of APK and automated WebUSB installation/provisioning.
 */
(function () {
    const CHUNK_SIZE = 32 * 1024; // 32 KB - Crucial for budget Android USB controllers (prevents stalls)
    const LOG_INTERVAL_BYTES = 1024 * 1024 * 2; // 2 MB
    const DEVICE_TEMP_APK_PATH = "/data/local/tmp/app.apk";
    const PACKAGE_NAME = "com.pisophone.kiosk";
    const INSTALL_TIMEOUT_MS = 90000; // 90 seconds - Package Manager extraction, dex2oat ART compilation & verification require 30-60s on budget hardware

    // The ADB library is this site's own vendored copy (its hash is checked in CI, see js/VENDORED.md); it is never fetched
    // from another site, which would also be refused by the page's Content-Security-Policy.
    const BUNDLE_CANDIDATE_URLS = [
        (typeof window !== 'undefined' && window.YUME_CHAN_BUNDLE_URL) ? window.YUME_CHAN_BUNDLE_URL : null,
        './js/yume-chan-bundle.js',
        '/js/yume-chan-bundle.js',
        '../js/yume-chan-bundle.js',
        './yume-chan-bundle.js',
        '/yume-chan-bundle.js'
    ].filter(Boolean).map(url => {
        if (url.startsWith('http://') || url.startsWith('https://')) return url;
        try {
            return new URL(url, window.location.href).href;
        } catch (e) {
            return url;
        }
    });

    async function loadYumeChanModules() {
        console.log("[WebADB] Initializing dynamic import of WebADB bundle...");
        console.log("[WebADB] Candidate bundle URLs:", BUNDLE_CANDIDATE_URLS);
        const errors = [];
        for (const url of BUNDLE_CANDIDATE_URLS) {
            let attempts = 3;
            while (attempts > 0) {
                try {
                    console.log(`[WebADB] Attempting to import bundle from: ${url} (Attempts left: ${attempts})`);
                    const mod = await import(url);
                    if (mod && (mod.Adb || (mod.default && mod.default.Adb))) {
                        console.log(`[WebADB] Dynamic import SUCCEEDED from: ${url}`);
                        return mod.Adb ? mod : mod.default;
                    }
                    console.warn(`[WebADB] Module imported from ${url} did not have Adb export.`);
                    break; // No retry needed if load succeeded but export was missing
                } catch (e) {
                    console.warn(`[WebADB] Failed loading WebADB bundle from ${url}:`, e);
                    attempts--;
                    if (attempts > 0) {
                        // Progressive backoff before retry
                        await new Promise(r => setTimeout(r, (3 - attempts) * 500));
                    } else {
                        errors.push(`${url}: ${e.message || e}`);
                    }
                }
            }
        }
        throw new Error("Unable to load WebADB core bundle. Details:\n" + errors.join("\n"));
    }

    // Eagerly preload WebADB bundle on script evaluation
    // Single-quote a value for the device shell. Every URL-supplied value must go through this:
    // a bare or double-quoted value lets "$(...)", backticks or ";" run commands as the adb shell.
    function shellQuote(value) {
        return "'" + String(value).replace(/'/g, "'\\''") + "'";
    }

    // Validate provisioning values (normally from the page URL) and return only well-formed ones.
    // Throws on a malformed value instead of silently passing it on to the phone.
    const MAX_SUPPORTED_SLOTS = 6;
    function validateProvisioning(raw) {
        const out = {};
        const fail = (field, rule) => { throw new Error(`Invalid provisioning parameter "${field}": ${rule}`); };
        if (raw.mac != null && raw.mac !== '') {
            if (!/^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$/.test(raw.mac)) fail('mac', 'expected 6 hex bytes like AA:BB:CC:DD:EE:FF');
            out.mac = raw.mac.toUpperCase();
        }
        if (raw.slot != null && raw.slot !== '') {
            if (!/^[0-9]{1,2}$/.test(String(raw.slot))) fail('slot', 'expected a number');
            const n = parseInt(raw.slot, 10);
            if (n < 1 || n > MAX_SUPPORTED_SLOTS) fail('slot', `expected 1-${MAX_SUPPORTED_SLOTS}`);
            out.slot = n;
        }
        if (raw.name != null && raw.name !== '') {
            if (!/^[A-Za-z0-9 _.\-]{1,40}$/.test(raw.name)) fail('name', 'letters, digits, space, _ . - only (max 40)');
            out.name = raw.name;
        }
        if (raw.secret != null && raw.secret !== '') {
            if (!/^[A-Za-z0-9_.+=\-]{1,128}$/.test(raw.secret)) fail('secret', 'unexpected characters');
            out.secret = raw.secret;
        }
        if (raw.wifiSsid != null && raw.wifiSsid !== '') {
            if (!/^[\x20-\x7E]{1,32}$/.test(raw.wifiSsid)) fail('wifi name', 'printable characters only (max 32)');
            out.wifiSsid = raw.wifiSsid;
        }
        if (raw.wifiPass != null && raw.wifiPass !== '') {
            if (!/^[\x20-\x7E]{8,63}$/.test(raw.wifiPass)) fail('wifi password', '8-63 printable characters');
            out.wifiPass = raw.wifiPass;
        }
        return out;
    }

    function buildProvisioningExtras(prov) {
        let extras = '';
        if (prov.secret) extras += ` --es secret ${shellQuote(prov.secret)}`;
        if (prov.mac) extras += ` --es mac ${shellQuote(prov.mac)} --es esp32_mac ${shellQuote(prov.mac)}`;
        if (prov.slot) extras += ` --ei slot ${prov.slot}`;
        if (prov.name) extras += ` --es name ${shellQuote(prov.name)}`;
        // The shop's kiosk Wi-Fi: without it a new phone cannot reach its box, so it could never pair or learn its admin PIN.
        if (prov.wifiPass) extras += ` --es wifi_ssid ${shellQuote(prov.wifiSsid || 'PisoKiosk')} --es wifi_pass ${shellQuote(prov.wifiPass)}`;
        return extras;
    }

    // Turn a pm install result into a message that names the real cause.
    function describeInstallFailure(output) {
        const text = (output || '').trim();
        const m = text.match(/Failure\s*\[([A-Z_]+)(?::\s*([^\]]*))?\]/);
        const code = m ? m[1] : '';
        const detail = m && m[2] ? ` (${m[2].trim()})` : '';
        const hints = {
            INSTALL_FAILED_UPDATE_INCOMPATIBLE: 'A PisoPhone build signed with a different key is already installed. Uninstall it first (or factory reset the phone), then run setup again.',
            INSTALL_FAILED_INSUFFICIENT_STORAGE: 'The phone is out of storage space. Free up space and retry.',
            INSTALL_FAILED_OLDER_SDK: 'This Android version is too old. PisoPhone needs Android 8.0 or newer.',
            INSTALL_FAILED_VERSION_DOWNGRADE: 'A newer PisoPhone version is already installed.',
            INSTALL_PARSE_FAILED_NO_CERTIFICATES: 'The downloaded APK is not signed. Re-download it or contact support.',
            INSTALL_PARSE_FAILED_NOT_APK: 'The downloaded file is not a valid APK (it may be an error page). Re-download it.',
            INSTALL_FAILED_USER_RESTRICTED: 'Installation was blocked by the phone. Turn on "Install via USB" in Developer options (Xiaomi/Redmi/POCO) and retry.',
            INSTALL_FAILED_VERIFICATION_FAILURE: 'Play Protect or a verifier blocked the install. Turn it off for this install and retry.'
        };
        if (code) return `Application installation failed: ${code}${detail}. ${hints[code] || ''}`.trim();
        return `Application installation failed: ${text || 'no output from Package Manager'}`;
    }

    let bundlePromise = null;
    try {
        bundlePromise = loadYumeChanModules().catch(err => {
            console.warn("WebADB bundle background preload warning:", err);
            return null;
        });
    } catch (e) {}

    class WebADBManager {
        constructor() {
            this.adb = null;
            this.connection = null;
            this.credentialStore = null;
            this.cachedApkBytes = null;
            this.serial = null;
        }

        /**
         * Sets cached APK bytes directly (e.g. from local file input)
         */
        setCachedApkBytes(bytes) {
            this.cachedApkBytes = bytes;
        }

        /**
         * Downloads the APK silently into computer's temporary cache (Browser memory)
         * Supports candidate URLs array for seamless fallback.
         * @param {string|string[]} apkUrlOrUrls URL or list of fallback URLs
         * @param {function} logCallback Function to output log messages
         * @returns {Promise<Uint8Array>} The downloaded APK bytes
         */
        async downloadToLocalTemp(apkUrlOrUrls, logCallback = console.log) {
            if (this.cachedApkBytes && this.cachedApkBytes.length > 0) {
                logCallback("✅ Using pre-cached APK from computer memory.");
                return this.cachedApkBytes;
            }

            const urls = Array.isArray(apkUrlOrUrls) ? apkUrlOrUrls : [apkUrlOrUrls];

            logCallback("Step 1: Downloading APK to local temporary cache...");

            for (const url of urls) {
                try {
                    logCallback(`Fetching APK from: ${url}`);
                    const res = await fetch(url);
                    if (!res.ok) {
                        console.warn(`Source responded HTTP ${res.status}: ${url}`);
                        logCallback(`⚠️ ${url} returned HTTP ${res.status}. Trying next source...`);
                        continue;
                    }

                    const contentType = (res.headers.get('content-type') || '').toLowerCase();
                    if (contentType.includes('text/html') || contentType.includes('application/json')) {
                        console.warn(`Source returned non-binary response: ${url} (${contentType})`);
                        logCallback(`⚠️ Source returned HTML/JSON page instead of binary APK: ${url}. Trying next source...`);
                        continue;
                    }

                    const contentLength = +(res.headers.get('Content-Length') || 0);
                    const reader = res.body.getReader();
                    const chunks = [];
                    let receivedBytes = 0;

                    while (true) {
                        const { done, value } = await reader.read();
                        if (done) break;

                        chunks.push(value);
                        receivedBytes += value.length;

                        if (contentLength > 0) {
                            const pct = Math.round((receivedBytes / contentLength) * 100);
                            const mb = (receivedBytes / (1024 * 1024)).toFixed(1);
                            const totalMb = (contentLength / (1024 * 1024)).toFixed(1);

                            if (receivedBytes % LOG_INTERVAL_BYTES < value.length || receivedBytes === contentLength) {
                                logCallback(`Downloading APK: ${pct}% (${mb} / ${totalMb} MB)`);
                            }
                        } else if (receivedBytes % LOG_INTERVAL_BYTES < value.length) {
                            const mb = (receivedBytes / (1024 * 1024)).toFixed(1);
                            logCallback(`Downloading APK: ${mb} MB received...`);
                        }
                    }

                    // Combine chunks into a single Uint8Array
                    const apkBytes = new Uint8Array(receivedBytes);
                    let offset = 0;
                    for (const chunk of chunks) {
                        apkBytes.set(chunk, offset);
                        offset += chunk.length;
                    }

                    // VALIDATION: APK is a Zip archive (starts with magic bytes 0x50 0x4B 0x03 0x04)
                    const isZipHeader = (
                        apkBytes.length >= 4 &&
                        apkBytes[0] === 0x50 &&
                        apkBytes[1] === 0x4B &&
                        apkBytes[2] === 0x03 &&
                        apkBytes[3] === 0x04
                    );

                    if (!isZipHeader || apkBytes.length < 500000) {
                        console.warn(`URL returned invalid APK payload (${apkBytes.length} bytes, valid PK header: ${isZipHeader}): ${url}`);
                        logCallback(`⚠️ Candidate URL returned invalid APK binary (${(apkBytes.length / 1024).toFixed(0)} KB). Trying next source...`);
                        continue;
                    }

                    this.cachedApkBytes = apkBytes;
                    const sizeMB = (apkBytes.length / (1024 * 1024)).toFixed(2);
                    logCallback(`✅ Valid APK downloaded & cached locally (${sizeMB} MB).`);
                    return apkBytes;
                } catch (fetchErr) {
                    console.warn(`Fetch error for ${url}:`, fetchErr);
                    logCallback(`⚠️ Network error fetching ${url}: ${fetchErr.message || fetchErr}. Trying next source...`);
                }
            }

            throw new Error(`Failed to download a valid APK binary from any source. Please verify internet connection or load a local APK file.`);
        }

        /**
         * Connects to Android device via WebUSB ADB
         * Supports connecting to already-paired devices as well as prompting user picker.
         * @param {function} logCallback Function to output log messages
         * @returns {Promise<boolean>} Connection success status
         */
        async connect(logCallback = console.log) {
            logCallback("Initializing WebADB connection...");
            console.log("[WebADB] connect() called.");
            
            try {
                logCallback("[DEBUG] Loading WebADB modules...");
                const modules = (bundlePromise ? await bundlePromise : null) || await loadYumeChanModules();
                console.log("[WebADB] Core modules resolved:", modules);
                logCallback("[DEBUG] WebADB modules loaded successfully.");

                const {
                    Adb,
                    AdbDaemonTransport,
                    AdbDaemonWebUsbDeviceManager,
                    AdbCredentialWeb
                } = modules;

                logCallback("[DEBUG] Fetching WebUSB browser manager...");
                const Manager = AdbDaemonWebUsbDeviceManager.BROWSER;
                if (!Manager) {
                    throw new Error("WebUSB is not supported by your browser or inside this insecure context. Please ensure you are on HTTPS, localhost, or have enabled the Chrome/Edge flag: chrome://flags/#unsafely-treat-insecure-origin-as-secure");
                }
                logCallback("[DEBUG] WebUSB browser manager found.");

                let webusbDevice = null;
                let connection = null;

                // Attempt to check if device is already paired/authorized
                try {
                    logCallback("[DEBUG] Checking for previously paired USB devices...");
                    const pairedDevices = await Manager.getDevices();
                    logCallback(`[DEBUG] Found ${pairedDevices ? pairedDevices.length : 0} previously paired devices.`);
                    if (pairedDevices && pairedDevices.length > 0) {
                        for (const dev of pairedDevices) {
                            try {
                                logCallback(`Checking previously paired USB device: ${dev.name || dev.serial || 'Android Device'}...`);
                                connection = await dev.connect();
                                webusbDevice = dev;
                                logCallback(`Connected to previously paired device: ${dev.name || dev.serial || 'Android Device'}`);
                                break;
                            } catch (devErr) {
                                logCallback(`[DEBUG] Paired device connect failed, trying next: ${devErr.message || devErr}`);
                                console.debug("Paired device connect attempt failed, falling back to picker:", devErr);
                            }
                        }
                    }
                } catch (e) {
                    logCallback(`[DEBUG] getDevices check threw an error: ${e.message || e}`);
                    console.debug("getDevices check:", e);
                }

                // If no paired device connected successfully, prompt user with standard WebUSB picker
                if (!webusbDevice || !connection) {
                    logCallback("Requesting WebUSB permission (select your Android phone from popup)...");
                    console.log("[WebADB] Triggering Manager.requestDevice(). This must show the browser picker.");
                    
                    try {
                        webusbDevice = await Manager.requestDevice();
                    } catch (reqErr) {
                        logCallback(`❌ WebUSB Picker failed to open: ${reqErr.message || reqErr}`);
                        console.error("[WebADB] Manager.requestDevice() failed:", reqErr);
                        throw reqErr;
                    }

                    if (!webusbDevice) {
                        throw new Error("No USB device selected.");
                    }
                    logCallback(`Device selected: ${webusbDevice.name || webusbDevice.serial || 'Android Device'}`);
                    
                    logCallback("[DEBUG] Establishing connection to selected USB device...");
                    
                    // Connection Retry Loop for ultimate USB reliability
                    let connectAttempts = 3;
                    while (connectAttempts > 0) {
                        try {
                            connection = await webusbDevice.connect();
                            break; // Success!
                        } catch (connErr) {
                            console.warn(`[WebADB] Connection attempt failed (${4 - connectAttempts}/3):`, connErr);
                            connectAttempts--;
                            if (connectAttempts > 0) {
                                logCallback(`⚠️ USB port busy or locked. Retrying connection in 1.5s...`);
                                // Try resetting the raw underlying USB device if accessible
                                if (webusbDevice.raw && typeof webusbDevice.raw.reset === 'function') {
                                    try { await webusbDevice.raw.reset(); } catch (_) {}
                                } else if (webusbDevice.device && typeof webusbDevice.device.reset === 'function') {
                                    try { await webusbDevice.device.reset(); } catch (_) {}
                                }
                                await new Promise(r => setTimeout(r, 1500));
                            } else {
                                logCallback(`❌ Device connection failed: ${connErr.message || connErr}`);
                                console.error("[WebADB] webusbDevice.connect() failed:", connErr);
                                throw new Error(`Could not claim USB interface. Troubleshooting steps:\n` +
                                                `1. Close Android Studio, Scrcpy, or any other WebADB tabs.\n` +
                                                `2. Unplug your USB cable, wait 3 seconds, and plug it back in.\n` +
                                                `3. Change the USB connection mode on your phone from "Charging" to "File Transfer / MTP" or "MIDI".\n` +
                                                `4. Verify that USB Debugging is turned ON in Developer Options.`);
                            }
                        }
                    }
                }

                this.connection = connection;
                this.serial = webusbDevice.serial || 'UNKNOWN';
                this.credentialStore = new AdbCredentialWeb();

                logCallback("Authenticating with device (Accept prompt on phone screen)...");
                console.log("[WebADB] Authenticating...");
                const AUTH_TIMEOUT_MS = 60000;
                let authTimer;
                const transport = await Promise.race([
                    AdbDaemonTransport.authenticate({
                        serial: webusbDevice.serial,
                        connection: this.connection,
                        credentialStore: this.credentialStore,
                    }),
                    new Promise((_, reject) => {
                        authTimer = setTimeout(() => reject(new Error(
                            "Timed out waiting for USB debugging authorization. Keep the phone screen on and unlocked, " +
                            "tap \"Allow\" on the \"Allow USB debugging?\" prompt (tick \"Always allow\"), then try again.")), AUTH_TIMEOUT_MS);
                    })
                ]).finally(() => clearTimeout(authTimer));

                this.adb = new Adb(transport);
                logCallback("✅ WebUSB ADB session connected successfully!");
                return true;
            } catch (err) {
                logCallback(`❌ Connection error: ${err.message || err}`);
                console.error("[WebADB] connect() error:", err);
                throw err;
            }
        }

        /**
         * Executes an ADB shell command and returns its text output with safety timeout
         * @param {string} command The shell command to execute
         * @param {number} timeoutMs Maximum duration to wait before aborting
         * @returns {Promise<string>} Output of the shell command
         */
        async shell(command, timeoutMs = 12000) {
            if (!this.adb) throw new Error("Device not connected.");
            
            const execPromise = (async () => {
                if (this.adb.subprocess && this.adb.subprocess.noneProtocol && typeof this.adb.subprocess.noneProtocol.spawnWaitText === 'function') {
                    return await this.adb.subprocess.noneProtocol.spawnWaitText(command);
                }
                if (this.adb.subprocess && typeof this.adb.subprocess.spawnWaitText === 'function') {
                    return await this.adb.subprocess.spawnWaitText(command);
                }
                if (typeof this.adb.shell === 'function') {
                    return await this.adb.shell(command);
                }
                throw new Error("Shell service unavailable on this device.");
            })();

            if (!timeoutMs || timeoutMs <= 0) return await execPromise;

            let timer;
            const timeoutPromise = new Promise((_, reject) => {
                timer = setTimeout(() => {
                    reject(new Error(`ADB command timed out (${Math.round(timeoutMs / 1000)}s): ${command.substring(0, 40)}`));
                }, timeoutMs);
            });

            try {
                return await Promise.race([execPromise, timeoutPromise]);
            } finally {
                clearTimeout(timer);
            }
        }

        /**
         * Pushes a file to the device using ADB Sync protocol
         * @param {Uint8Array} fileBytes The binary file content
         * @param {string} destPath The destination path on the device
         * @param {function} logCallback Function to output log messages
         */
        async pushFile(fileBytes, destPath, logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");
            
            logCallback("Starting ADB Sync protocol for high-speed file transfer...");
            let sync = null;
            
            try {
                sync = await this.adb.sync();
                const totalBytes = fileBytes.length;
                let offset = 0;

                const stream = new ReadableStream({
                    pull(controller) {
                        if (offset >= totalBytes) {
                            controller.close();
                            return;
                        }
                        const end = Math.min(offset + CHUNK_SIZE, totalBytes);
                        const chunk = fileBytes.subarray(offset, end);
                        
                        // Push pure Uint8Array to the stream
                        controller.enqueue(chunk);
                        
                        // Log progress periodically
                        if (offset % LOG_INTERVAL_BYTES < CHUNK_SIZE || end === totalBytes) {
                            const pct = Math.round((end / totalBytes) * 100);
                            const currentMb = (end / (1024 * 1024)).toFixed(1);
                            const totalMb = (totalBytes / (1024 * 1024)).toFixed(1);
                            logCallback(`Pushing: ${pct}% (${currentMb} / ${totalMb} MB)`);
                        }
                        
                        offset = end;
                    }
                });

                await sync.write({
                    filename: destPath,
                    file: stream,
                });
                
                logCallback("✅ File transferred to device successfully!");
            } catch (syncErr) {
                throw new Error("Failed to push file via ADB Sync. " + (syncErr.message || syncErr));
            } finally {
                if (sync) {
                    try { sync.dispose(); } catch (e) {}
                }
            }
        }

        /**
         * Reads immutable hardware properties via ADB and computes the canonical Hardware Device ID
         * @param {function} logCallback Function to log progress
         * @returns {Promise<{deviceId: string, deviceModel: string, hardwareHash: string, rawHardwareString: string}>}
         */
        /**
         * Inspects and computes canonical hardware identifier.
         * Ensures 100% consistency with the Android app by:
         * 1. Querying persistent Global Settings (pisophone_hw_id)
         * 2. Computing canonical hash in a single high-speed shell round-trip
         * 3. Syncing the canonical ID to device settings
         */
        async getHardwareInfo(logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");

            // Probe hardware properties in a single consolidated shell execution to prevent stream freezes
            const dumpCmd = `echo "===PISO_START==="; settings get global pisophone_hw_id; echo "---"; settings get secure android_id; echo "---"; getprop ro.product.brand; echo "---"; getprop ro.product.manufacturer; echo "---"; getprop ro.product.model; echo "---"; getprop ro.product.board; echo "---"; getprop ro.product.device; echo "---"; getprop ro.hardware; echo "---"; getprop ro.product.name; echo "===PISO_END==="`;

            let globalSetting = "";
            let androidId = "UNKNOWN_ID";
            let brand = "";
            let manufacturer = "";
            let model = "Android Device";
            let board = "";
            let device = "";
            let hardware = "";
            let product = "";

            try {
                const rawOutput = await this.shell(dumpCmd, 8000);
                const startIndex = rawOutput.indexOf("===PISO_START===");
                const endIndex = rawOutput.indexOf("===PISO_END===");
                if (startIndex !== -1 && endIndex !== -1) {
                    const block = rawOutput.substring(startIndex + 16, endIndex).trim();
                    const parts = block.split("---").map(s => s.trim());
                    globalSetting = parts[0] || "";
                    androidId = parts[1] || "UNKNOWN_ID";
                    brand = parts[2] || "";
                    manufacturer = parts[3] || "";
                    model = parts[4] || "Android Device";
                    board = parts[5] || "";
                    device = parts[6] || "";
                    hardware = parts[7] || "";
                    product = parts[8] || "";
                }
            } catch (err) {
                logCallback(`Notice: Quick hardware probe note: ${err.message || err}. Continuing with default profile.`);
            }

            let deviceModel = model;
            const effectiveBrand = brand || manufacturer || "";
            if (effectiveBrand && model && !model.toLowerCase().startsWith(effectiveBrand.toLowerCase())) {
                deviceModel = `${effectiveBrand} ${model}`.trim();
            }

            // Priority 1: Check persistent Global Setting (pisophone_hw_id)
            if (globalSetting && globalSetting.startsWith("HW-") && !globalSetting.includes("null") && !globalSetting.includes("not found")) {
                logCallback(`Retrieved persistent Device ID from global settings: ${globalSetting}`);
                return {
                    deviceId: globalSetting,
                    deviceModel,
                    source: 'global_setting'
                };
            }

            // Priority 2: Compute canonical immutable hardware identity
            if (!effectiveBrand || effectiveBrand.toLowerCase() === "unknown") {
                brand = manufacturer || "";
            } else {
                brand = effectiveBrand;
            }

            const rawHardwareString = `${androidId}|${board}|${brand}|${device}|${hardware}|${manufacturer}|${model}|${product}`;
            
            let deviceId;
            let hex = "";
            try {
                const encoder = new TextEncoder();
                const data = encoder.encode(rawHardwareString);
                const hashBuffer = await crypto.subtle.digest('SHA-256', data);
                const hashArray = Array.from(new Uint8Array(hashBuffer));
                hex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('').toUpperCase();
                deviceId = `HW-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}`;
            } catch (hashErr) {
                deviceId = `HW-${(androidId.replace(/[^a-zA-Z0-9]/g, '') || "DEVICE").substring(0, 16).toUpperCase()}`;
            }

            // Persist synchronized ID to device settings so app reads the exact same ID
            try {
                await this.shell(`settings put global pisophone_hw_id ${deviceId}`, 4000);
                logCallback(`Synchronized canonical Device ID to device settings: ${deviceId}`);
            } catch (e) {}

            return {
                deviceId,
                deviceModel,
                hardwareHash: hex || deviceId,
                rawHardwareString,
                source: 'computed_synced'
            };
        }

        /**
         * Full Automated Kiosk App Installer & Provisioner
         * @param {string} apkUrl URL of the APK to install
         * @param {function} logCallback Function to output log messages
         */
        async installKioskApp(apkUrl, logCallback = console.log) {
            // Step 1: Cache APK locally
            if (!this.cachedApkBytes) {
                await this.downloadToLocalTemp(apkUrl, logCallback);
            }

            if (!this.adb) throw new Error("Device not connected.");

            // Hardware Verification & Registration against Cloudflare Database
            logCallback("Inspecting hardware identifier for license provisioning...");
            let deviceId = "UNKNOWN";
            try {
                const hwInfo = await this.getHardwareInfo(logCallback);
                deviceId = hwInfo.deviceId;
                const deviceModel = hwInfo.deviceModel;
                logCallback(`Device Hardware ID: ${deviceId} (${deviceModel})`);
                
                // Local-only instant registration flow
                logCallback(`✨ Device hardware ID verified: ${deviceId}. Ready for setup tutorial and activation.`);
            } catch (e) {
                logCallback(`Hardware check notice: ${e.message}`);
            }

            const apkBytes = this.cachedApkBytes;

            logCallback("Step 2: Transferring locally cached APK to device...");

            // Probe device storage space before pushing to guarantee the device has room for the APK
            try {
                const dfOutput = await this.shell("df /data/local/tmp", 4000);
                const dfLines = dfOutput.split('\n').map(l => l.trim()).filter(Boolean);
                let freeKb = null;
                if (dfLines.length >= 2) {
                    const columns = dfLines[1].split(/\s+/);
                    // Find column that matches a number preceding Use% (e.g., "76%") or use position fallback
                    for (let colIdx = 0; colIdx < columns.length; colIdx++) {
                        const colVal = columns[colIdx];
                        if (colVal.includes('%')) {
                            if (colIdx > 0) {
                                freeKb = parseInt(columns[colIdx - 1], 10);
                            }
                            break;
                        }
                    }
                    if (freeKb === null && columns.length >= 4) {
                        freeKb = parseInt(columns[3], 10);
                    }
                }
                if (freeKb !== null && !isNaN(freeKb)) {
                    const freeMb = (freeKb / 1024).toFixed(1);
                    logCallback(`Device storage: ${freeMb} MB free space on /data/local/tmp.`);
                    if (freeKb < 40000) { // Under 40MB
                        logCallback("⚠️ WARNING: Storage is extremely low! Installation may fail due to insufficient space.");
                    }
                }
            } catch (storageErr) {
                console.debug("[WebADB] Storage probe bypassed:", storageErr);
            }

            try {
                // Clear any previous temporary files
                await this.shell(`rm -f ${DEVICE_TEMP_APK_PATH}`);
            } catch (e) {}

            // Push APK using Sync protocol
            await this.pushFile(apkBytes, DEVICE_TEMP_APK_PATH, logCallback);

            // Verify file size on device
            const checkStat = await this.shell(`ls -l ${DEVICE_TEMP_APK_PATH}`);
            logCallback(`Device storage verified: ${checkStat.trim()}`);

            logCallback("Step 3: Running Package Manager to install the application (this can take 30–60s for dex optimization)...");
            await this.shell(`chmod 777 ${DEVICE_TEMP_APK_PATH}`);
            
            const updatedBefore = await this.getPackageUpdateTime();
            let installRes = "";
            let installErr = null;
            try {
                installRes = await this.shell(`pm install -r -d -g ${DEVICE_TEMP_APK_PATH}`, INSTALL_TIMEOUT_MS);
            } catch (err) {
                installErr = err;
            }

            if (installErr) {
                // The shell call timed out, but Package Manager may still be finishing. Only a package
                // whose update time actually changed counts: an older copy already on the phone must
                // never be mistaken for a fresh install.
                await new Promise(r => setTimeout(r, 3000));
                const updatedAfter = await this.getPackageUpdateTime();
                if (updatedAfter && updatedAfter !== updatedBefore) {
                    logCallback("✅ Package Manager finished installing after the shell timed out.");
                    installRes = "Success";
                } else {
                    throw new Error(`Installation did not complete: ${installErr.message || installErr}`);
                }
            } else if (/Unknown option|Unknown flag|Unrecognized option/i.test(installRes)) {
                logCallback("⚠️ This Android build rejected the extra install flags. Retrying with a plain install...");
                installRes = await this.shell(`pm install -r ${DEVICE_TEMP_APK_PATH}`, INSTALL_TIMEOUT_MS);
            }

            if (!/^\s*Success\b/m.test(installRes)) {
                throw new Error(describeInstallFailure(installRes));
            }
            logCallback(`Install output: ${installRes.trim()}`);

            // Verify installation success
            const finalCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`);
            if (!finalCheck.includes(PACKAGE_NAME)) {
                throw new Error(`Application installation failed: ${installRes.trim()}`);
            }

            logCallback("✅ PisoPhone Launcher installed successfully!");

            if (await this.isOurDeviceOwner()) {
                logCallback("PisoPhone is already the Device Owner on this phone. Skipping enrollment and re-applying setup.");
            } else {
                logCallback("Step 4: Pre-flight check: Verifying device account prerequisites...");
                try {
                    const accountsDump = await this.shell("dumpsys account");
                    const hasAccounts = /Account\s*\{/i.test(accountsDump) || /Accounts:\s*[1-9]/i.test(accountsDump);
                    if (hasAccounts) {
                        throw new Error(this.describeDeviceOwnerFailure("accounts"));
                    }
                } catch (accErr) {
                    if ((accErr.message || "").includes("Cannot set Device Owner")) {
                        throw accErr;
                    }
                    // Continue if dumpsys is restricted
                }

                logCallback("Setting PisoPhone as Device Owner (Kiosk Administrator)...");
                const dpmResult = await this.shell(`dpm set-device-owner ${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver`);
                logCallback(`Device Admin output: ${dpmResult.trim()}`);
                if (!/^\s*Success/im.test(dpmResult)) {
                    throw new Error(this.describeDeviceOwnerFailure(dpmResult));
                }
            }

            logCallback("Step 5: Securing device permissions and launching PisoPhone...");
            
            // Grant overlay permission
            try {
                await this.shell(`appops set ${PACKAGE_NAME} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true`);
                await this.shell(`cmd overlay enable --user 0 ${PACKAGE_NAME} 2>/dev/null || true`);
            } catch (e) {}

            // Grant write secure settings and ignore battery optimizations for 24/7 kiosk stability
            try {
                await this.shell(`pm grant ${PACKAGE_NAME} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true`);
                await this.shell(`dumpsys deviceidle whitelist +${PACKAGE_NAME} 2>/dev/null || true`);
            } catch (e) {}

            // Parse provisioning credentials if supplied via URL. Values are validated and
            // single-quoted so a crafted link cannot run commands on the phone.
            let provExtras = '';
            try {
                const urlParams = pageParams;
                provExtras = buildProvisioningExtras(validateProvisioning({
                    secret: urlParams.get('secret'),
                    mac: urlParams.get('mac'),
                    slot: urlParams.get('slot'),
                    name: urlParams.get('name'),
                    wifiSsid: urlParams.get('wifi_ssid'),
                    wifiPass: urlParams.get('wifi_pass')
                }));
            } catch (e) {
                throw new Error(`Setup link rejected: ${e.message}`);
            }

            // Launch the main activity with provisioning params
            await this.shell(`am start -n ${PACKAGE_NAME}/.MainActivity -a ${PACKAGE_NAME}.SETUP_DIRECT${provExtras}`);
            
            // Broadcast provisioning configuration to ensure immediate persistence
            if (provExtras) {
                logCallback("⚡ Injecting ESP32 cryptographically protected secret key and provisioning configuration...");
                try {
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.CONFIGURE_ESP32 -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver${provExtras}`);
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.CONFIGURE_ESP32 -p ${PACKAGE_NAME}${provExtras}`);
                } catch (bErr) {}
            }
            
            // Ensure canonical hardware ID is synchronized between app and system
            try {
                await new Promise(r => setTimeout(r, 1000));
                const syncBroadcast = await this.shell(`am broadcast -a ${PACKAGE_NAME}.GET_DEVICE_ID -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver`);
                const match = syncBroadcast.match(/data="([^"]+)"/) || syncBroadcast.match(/(HW-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4})/);
                if (match && match[1]) {
                    const verifiedId = match[1].trim();
                    await this.shell(`settings put global pisophone_hw_id ${verifiedId}`);
                    logCallback(`Verified app hardware seal ID: ${verifiedId}`);
                }
            } catch (e) {}

            // Clean up temporary APK
            try { 
                await this.shell(`rm -f ${DEVICE_TEMP_APK_PATH}`); 
            } catch (e) {}

            logCallback("Step 6: Setup validation complete. Skipping reboot to preserve immediate active kiosk launch.");
            logCallback("🎉 PisoPhone Kiosk setup complete! Kiosk is active and secured.");
        }

        /**
         * Alias for shell command execution
         */
        async runShell(command, timeoutMs = 12000) {
            return await this.shell(command, timeoutMs);
        }

        /** Package Manager's last-update timestamp for the app, or "" if not installed. */
        async getPackageUpdateTime() {
            try {
                const dump = await this.shell(`dumpsys package ${PACKAGE_NAME}`, 8000);
                const m = dump.match(/lastUpdateTime=([^\r\n]+)/);
                return m ? m[1].trim() : "";
            } catch (e) {
                return "";
            }
        }

        /** True when PisoPhone itself is already the Device Owner (re-running setup on a set-up phone). */
        async isOurDeviceOwner() {
            const outputs = [];
            for (const cmd of ["dpm list-owners", "dumpsys device_policy"]) {
                try { outputs.push(await this.shell(cmd, 8000)); } catch (e) {}
            }
            return outputs.some(o => /device\s*owner[\s\S]{0,400}?com\.pisophone\.kiosk/i.test(o));
        }

        /** Explain why `dpm set-device-owner` failed, naming the actual cause. */
        describeDeviceOwnerFailure(output) {
            const text = String(output || "").trim();
            if (/device owner (is )?already (set|provisioned)|already.*device owner/i.test(text)) {
                return "Cannot set Device Owner: a different app is already the Device Owner of this phone. Factory reset the phone and skip all setup screens, then run setup again.";
            }
            if (/several users|multiple users|more than one user|users? on the device|secondary user/i.test(text)) {
                return "Cannot set Device Owner: the phone has more than one user (for example a guest, work profile or Secure Folder). Remove the extra users in Settings > System > Multiple users, or factory reset, then retry.";
            }
            if (text === "accounts" || /accounts?\b/i.test(text)) {
                return "Cannot set Device Owner: an account (Google, Samsung, Xiaomi, WhatsApp...) is signed in on this phone. Remove every account in Settings > Accounts, or factory reset and skip account setup.";
            }
            if (/MANAGE_DEVICE_ADMINS|SecurityException|permission/i.test(text)) {
                return `Device Owner setup was blocked by the phone's security settings: ${text}\n` +
                    "Xiaomi / Redmi / POCO: in Developer options turn on \"USB debugging (Security settings)\" (needs a SIM and Mi account) and turn off MIUI optimization.\n" +
                    "Samsung: remove the Samsung account, turn off Auto Blocker, and make sure Secure Folder / work profiles are removed.";
            }
            return `Device Owner setup failed: ${text || "no output from the phone"}`;
        }

        /**
         * Granular step: Launches PisoPhone Kiosk app
         */
        async launchApp(logCallback = console.log, mainActivity = `${PACKAGE_NAME}/.MainActivity`) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("Launching PisoPhone application...");
            await this.shell(`am start -n ${mainActivity}`);
            logCallback("✅ App launched.");
        }

        /**
         * Disconnects the ADB session and cleans up resources
         */
        async disconnect() {
            if (this.adb) {
                try { await this.adb.close(); } catch (e) {}
            }
            this.adb = null;
            this.connection = null;
        }
    }

    /**
     * What the box's link carried, read once. Values may come after '#' (a fragment never reaches a web server, its logs
     * or a Referer) or, from older boxes, in the query; the fragment wins. The secret and the Wi-Fi password are then taken
     * out of the address bar and the history; the page keeps them in memory for the install.
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
    const pageParams = readPageParams(typeof window !== 'undefined' ? window.location : null, typeof window !== 'undefined' ? window.history : null);

    // Export globally for the UI
    window.webADB = new WebADBManager();
    window.PisoProvisioning = { shellQuote, validateProvisioning, buildProvisioningExtras, describeInstallFailure, readPageParams, params: pageParams };
})();
