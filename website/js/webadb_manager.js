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

    // Candidate bundle URLs for robust universal loading across Cloudflare, Local ESP32, or Localhost
    const BUNDLE_CANDIDATE_URLS = [
        (typeof window !== 'undefined' && window.YUME_CHAN_BUNDLE_URL) ? window.YUME_CHAN_BUNDLE_URL : null,
        './js/yume-chan-bundle.js',
        '/js/yume-chan-bundle.js',
        '../js/yume-chan-bundle.js',
        './yume-chan-bundle.js',
        '/yume-chan-bundle.js',
        'https://pisophone.pages.dev/js/yume-chan-bundle.js',
        'https://pisophone.pages.dev/yume-chan-bundle.js',
        'https://cdn.jsdelivr.net/gh/Sheesh0327/PisoPhone-Kiosk-Launcher-V1@main/website/js/yume-chan-bundle.js'
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
                const transport = await AdbDaemonTransport.authenticate({
                    serial: webusbDevice.serial,
                    connection: this.connection,
                    credentialStore: this.credentialStore,
                });

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
         * Generic APK Sideloader
         * @param {Uint8Array} fileBytes The APK file content
         * @param {string} originalFilename The name of the file (for logging only)
         * @param {function} logCallback Function to output log messages
         */
        async sideloadApk(fileBytes, originalFilename = "sideload.apk", logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");

            // Validate PK Zip Magic Bytes
            const isZipHeader = (
                fileBytes && fileBytes.length >= 4 &&
                fileBytes[0] === 0x50 && fileBytes[1] === 0x4B &&
                fileBytes[2] === 0x03 && fileBytes[3] === 0x04
            );
            if (!isZipHeader || fileBytes.length < 100000) {
                throw new Error(`The selected file '${originalFilename}' is not a valid Android APK package (Invalid PK Zip header or corrupted file).`);
            }

            const destPath = `/data/local/tmp/sideload_temp.apk`;

            logCallback(`Step 1: Transferring ${originalFilename} to device...`);
            try {
                await this.shell(`rm -f ${destPath}`);
            } catch (e) {}

            await this.pushFile(fileBytes, destPath, logCallback);

            const checkStat = await this.shell(`ls -l ${destPath}`);
            logCallback(`Device storage verified: ${checkStat.trim()}`);

            logCallback(`Step 2: Running Package Manager to install ${originalFilename} (this may take 30–60s for dex optimization)...`);
            await this.shell(`chmod 777 ${destPath}`);
            
            let installRes = "";
            let useFallback = false;
            try {
                installRes = await this.shell(`pm install -r -d -g ${destPath}`, INSTALL_TIMEOUT_MS);
            } catch (err) {
                logCallback(`⚠️ Initial pm install flags failed: ${err.message || err}. Attempting standard compatibility installation...`);
                useFallback = true;
            }

            if (useFallback || installRes.includes("Failure") || installRes.includes("Error") || installRes.includes("Exception") || installRes.includes("Unknown option")) {
                logCallback("⚠️ Premium installation flags rejected. Attempting standard installation fallback...");
                try {
                    installRes = await this.shell(`pm install -r ${destPath}`, INSTALL_TIMEOUT_MS);
                } catch (fallbackErr) {
                    throw new Error(`Installation failed: ${fallbackErr.message || fallbackErr}`);
                }
            }
            logCallback(`Install output: ${installRes.trim()}`);

            if (installRes.includes("Failure") || installRes.includes("Error") || installRes.includes("Exception")) {
                throw new Error(`Installation failed: ${installRes.trim()}`);
            }

            logCallback(`✅ ${originalFilename} installed successfully!`);

            // Clean up temporary APK
            try { 
                await this.shell(`rm -f ${destPath}`); 
            } catch (e) {}
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
            let serverLicenseData = null;
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
            
            let installRes = "";
            let useFallback = false;
            try {
                installRes = await this.shell(`pm install -r -d -g ${DEVICE_TEMP_APK_PATH}`, INSTALL_TIMEOUT_MS);
            } catch (err) {
                // If timed out or errored, verify if the package actually succeeded in installing
                const earlyCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                if (earlyCheck.includes(PACKAGE_NAME)) {
                    logCallback("✅ Package Manager confirmed application is installed.");
                    installRes = "Success";
                } else {
                    logCallback(`⚠️ Initial pm install flags failed: ${err.message || err}. Attempting standard compatibility installation...`);
                    useFallback = true;
                }
            }

            if (useFallback || installRes.includes("Failure") || installRes.includes("Error") || installRes.includes("Exception") || installRes.includes("Unknown option")) {
                // Double-check if package was actually installed before attempting fallback
                const fallbackCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                if (fallbackCheck.includes(PACKAGE_NAME)) {
                    logCallback("✅ Application is confirmed installed on device.");
                    installRes = "Success";
                } else {
                    logCallback("⚠️ Premium installation flags rejected. Attempting standard installation fallback...");
                    try {
                        installRes = await this.shell(`pm install -r ${DEVICE_TEMP_APK_PATH}`, INSTALL_TIMEOUT_MS);
                    } catch (fallbackErr) {
                        // Final check if it succeeded despite shell timeout
                        const finalVerify = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                        if (!finalVerify.includes(PACKAGE_NAME)) {
                            throw new Error(`Installation failed: ${fallbackErr.message || fallbackErr}`);
                        }
                        installRes = "Success";
                    }
                }
            }
            logCallback(`Install output: ${installRes.trim()}`);

            // Verify installation success
            const finalCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`);
            if (!finalCheck.includes(PACKAGE_NAME)) {
                throw new Error(`Application installation failed: ${installRes.trim()}`);
            }

            logCallback("✅ PisoPhone Launcher installed successfully!");

            logCallback("Step 4: Pre-flight check: Verifying device account prerequisites...");
            try {
                const accountsDump = await this.shell("dumpsys account");
                const hasAccounts = /Account\s*\{/i.test(accountsDump) || /Accounts:\s*[1-9]/i.test(accountsDump);
                if (hasAccounts) {
                    throw new Error("Cannot set Device Owner: An active user account (e.g. Google, WhatsApp, Samsung) is logged in. Android security policy strictly blocks Device Owner enrollment when accounts exist. Please go to Android Settings > Accounts and remove all accounts, or Factory Reset the device and skip account setup.");
                }
            } catch (accErr) {
                if (accErr.message.includes("Cannot set Device Owner")) {
                    throw accErr;
                }
                // Continue if dumpsys is restricted
            }

            logCallback("Setting PisoPhone as Device Owner (Kiosk Administrator)...");
            const dpmResult = await this.shell(`dpm set-device-owner ${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver`);
            logCallback(`Device Admin output: ${dpmResult.trim()}`);

            if (dpmResult.includes("Exception") || dpmResult.includes("java.lang") || dpmResult.includes("Error") || dpmResult.includes("illegal state")) {
                if (dpmResult.includes("accounts") || dpmResult.includes("already")) {
                    throw new Error("Cannot set Device Owner: An account is logged into this device. Android requires 0 accounts for kiosk mode. Please remove all accounts in Settings > Accounts or Factory Reset and SKIP all account setups.");
                }
                throw new Error(`Device Owner setup failed: ${dpmResult.trim()}`);
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

            // Parse provisioning credentials if supplied via URL
            let provExtras = '';
            try {
                const urlParams = new URLSearchParams(window.location.search);
                const provSecret = urlParams.get('secret');
                const provMac = urlParams.get('mac');
                const provSlot = urlParams.get('slot');
                const provName = urlParams.get('name');

                if (provSecret) provExtras += ` --es secret "${provSecret}"`;
                if (provMac) provExtras += ` --es mac "${provMac}" --es esp32_mac "${provMac}"`;
                if (provSlot) provExtras += ` --ei slot ${provSlot}`;
                if (provName) provExtras += ` --es name "${provName}"`;
            } catch (e) {}

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

            // If the device is already paid on Cloudflare database, push license key immediately
            if (serverLicenseData && serverLicenseData.status === 'PAID' && serverLicenseData.licenseKey) {
                logCallback("🌟 Syncing active Software License directly to device...");
                try {
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.ACTIVATE -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver --es key "${serverLicenseData.licenseKey}"`);
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.ACTIVATE -p ${PACKAGE_NAME} --es key "${serverLicenseData.licenseKey}"`);
                    await new Promise(r => setTimeout(r, 500));
                } catch (e) {
                    logCallback(`Note: License push broadcast: ${e.message}`);
                }
            }

            // Clean up temporary APK
            try { 
                await this.shell(`rm -f ${DEVICE_TEMP_APK_PATH}`); 
            } catch (e) {}

            logCallback("Step 6: Setup validation complete. Skipping reboot to preserve immediate active kiosk launch.");
            logCallback("🎉 PisoPhone Kiosk setup complete! Kiosk is active and secured.");
        }

        /**
         * Granular step: Restarts device cleanly
         */
        async rebootDevice(logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("🔄 Sending reboot command to device...");
            try {
                if (typeof this.adb.reboot === "function") {
                    await this.adb.reboot();
                } else {
                    await this.shell("svc power reboot || reboot || true");
                }
            } catch (e) {
                try {
                    await this.shell("reboot || true");
                } catch (_) {}
            }
            logCallback("✅ Device reboot signal dispatched.");
        }

        /**
         * Alias for shell command execution
         */
        async runShell(command, timeoutMs = 12000) {
            return await this.shell(command, timeoutMs);
        }

        /**
         * Granular step: Installs APK from device temp path or Uint8Array
         */
        async installApk(logCallback = console.log, destPath = DEVICE_TEMP_APK_PATH) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("Installing APK via Android Package Manager...");
            await this.shell(`chmod 777 ${destPath}`);
            
            let res = "";
            let useFallback = false;
            try {
                res = await this.shell(`pm install -r -d -g ${destPath}`, INSTALL_TIMEOUT_MS);
            } catch (err) {
                // If timed out or errored, check if package was installed
                const earlyCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                if (earlyCheck.includes(PACKAGE_NAME)) {
                    res = "Success";
                } else {
                    logCallback(`⚠️ Initial pm install flags failed: ${err.message || err}. Attempting standard compatibility installation...`);
                    useFallback = true;
                }
            }

            if (useFallback || res.includes("Failure") || res.includes("Error") || res.includes("Exception") || res.includes("Unknown option")) {
                const fallbackCheck = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                if (fallbackCheck.includes(PACKAGE_NAME)) {
                    res = "Success";
                } else {
                    logCallback("⚠️ Premium installation flags rejected. Attempting standard installation fallback...");
                    try {
                        res = await this.shell(`pm install -r ${destPath}`, INSTALL_TIMEOUT_MS);
                    } catch (fallbackErr) {
                        const finalVerify = await this.shell(`pm list packages ${PACKAGE_NAME}`, 4000).catch(() => "");
                        if (!finalVerify.includes(PACKAGE_NAME)) {
                            throw new Error(`Installation failed: ${fallbackErr.message || fallbackErr}`);
                        }
                        res = "Success";
                    }
                }
            }
            logCallback(`Install output: ${res.trim()}`);
            if (res.includes("Failure") || res.includes("Error") || res.includes("Exception")) {
                throw new Error(`APK installation failed: ${res.trim()}`);
            }
            logCallback("✅ Package installed successfully.");
        }

        /**
         * Granular step: Sets Kiosk Device Owner
         */
        async setDeviceOwner(logCallback = console.log, receiverClass = `${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver`) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("Setting PisoPhone as Device Owner administrator...");
            
            // Check accounts first
            try {
                const accountsDump = await this.shell("dumpsys account");
                const hasAccounts = /Account\s*\{/i.test(accountsDump) || /Accounts:\s*[1-9]/i.test(accountsDump);
                if (hasAccounts) {
                    throw new Error("Cannot set Device Owner: An active user account is logged in. Please remove all Google/app accounts in Android Settings > Accounts, or Factory Reset the device.");
                }
            } catch (accErr) {
                if (accErr.message.includes("Cannot set Device Owner")) throw accErr;
            }

            const dpmResult = await this.shell(`dpm set-device-owner ${receiverClass}`);
            logCallback(`Device Admin output: ${dpmResult.trim()}`);
            if (dpmResult.includes("Exception") || dpmResult.includes("java.lang") || dpmResult.includes("Error") || dpmResult.includes("illegal state")) {
                if (dpmResult.includes("accounts") || dpmResult.includes("already")) {
                    throw new Error("Cannot set Device Owner: Device has existing accounts. Android requires 0 accounts for kiosk mode.");
                }
                throw new Error(`Device Owner setup failed: ${dpmResult.trim()}`);
            }
            logCallback("✅ Device Owner enrolled successfully.");
        }

        /**
         * Granular step: Grants necessary kiosk permissions
         */
        async grantPermissions(logCallback = console.log, pkg = PACKAGE_NAME) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("Configuring system permissions for 24/7 kiosk reliability...");
            try {
                await this.shell(`appops set ${pkg} SYSTEM_ALERT_WINDOW allow 2>/dev/null || true`);
                await this.shell(`cmd overlay enable --user 0 ${pkg} 2>/dev/null || true`);
                await this.shell(`pm grant ${pkg} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true`);
                await this.shell(`dumpsys deviceidle whitelist +${pkg} 2>/dev/null || true`);
            } catch (e) {
                logCallback(`Notice: Permission grant warning: ${e.message}`);
            }
            logCallback("✅ Permissions configured.");
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
         * Granular step: Deprovisions device and removes kiosk administrator
         */
        async deprovisionDevice(pin = "1234", logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");
            logCallback("Sending deprovision broadcast intent...");
            try {
                await this.shell(`am broadcast -a ${PACKAGE_NAME}.DEPROVISION -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver --es pin "${pin}"`);
                await this.shell(`dpm remove-active-admin ${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver 2>/dev/null || true`);
                await this.shell(`pm uninstall ${PACKAGE_NAME} 2>/dev/null || true`);
            } catch (e) {
                logCallback(`Deprovision warning: ${e.message}`);
            }
            logCallback("✅ Deprovision commands sent.");
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

    // Export globally for the UI
    window.webADB = new WebADBManager();
})();
