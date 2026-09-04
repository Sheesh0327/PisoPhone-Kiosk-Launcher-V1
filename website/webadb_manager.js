/**
 * WebADB Manager for PisoPhone
 * Handles local temporary caching of APK and automated WebUSB installation/provisioning.
 */
(function () {
    const CHUNK_SIZE = 64 * 1024; // 64 KB
    const LOG_INTERVAL_BYTES = 1024 * 1024 * 2; // 2 MB
    const DEVICE_TEMP_APK_PATH = "/data/local/tmp/app.apk";
    const PACKAGE_NAME = "com.pisophone.kiosk";

    // Eagerly preload WebADB bundle on script evaluation to prevent microtask delays during user click
    let bundlePromise = null;
    try {
        bundlePromise = import('./yume-chan-bundle.js').catch(err => {
            console.warn("WebADB bundle background preload:", err);
            return null;
        });
    } catch (e) {}

    class WebADBManager {
        constructor() {
            this.adb = null;
            this.connection = null;
            this.credentialStore = null;
            this.cachedApkBytes = null;
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
            let response = null;
            let chosenUrl = null;

            logCallback("Step 1: Downloading APK to local computer temporary cache...");

            for (const url of urls) {
                try {
                    logCallback(`Fetching APK from: ${url}`);
                    const res = await fetch(url);
                    if (res.ok) {
                        response = res;
                        chosenUrl = url;
                        break;
                    } else {
                        console.warn(`Source responded HTTP ${res.status}: ${url}`);
                    }
                } catch (fetchErr) {
                    console.warn(`Fetch error for ${url}:`, fetchErr);
                }
            }

            if (!response || !response.ok) {
                throw new Error(`Failed to download APK from any source. Please verify internet connection or load a local APK file.`);
            }

            const contentLength = +(response.headers.get('Content-Length') || 0);
            const reader = response.body.getReader();
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
                        logCallback(`Downloading: ${pct}% (${mb} / ${totalMb} MB)`);
                    }
                } else if (receivedBytes % LOG_INTERVAL_BYTES < value.length) {
                    const mb = (receivedBytes / (1024 * 1024)).toFixed(1);
                    logCallback(`Downloading: ${mb} MB received...`);
                }
            }

            // Combine chunks into a single Uint8Array
            const apkBytes = new Uint8Array(receivedBytes);
            let offset = 0;
            for (const chunk of chunks) {
                apkBytes.set(chunk, offset);
                offset += chunk.length;
            }

            this.cachedApkBytes = apkBytes;
            const sizeMB = (apkBytes.length / (1024 * 1024)).toFixed(2);
            logCallback(`✅ APK cached locally on computer (${sizeMB} MB). Ready to transfer.`);
            return apkBytes;
        }

        /**
         * Connects to Android device via WebUSB ADB
         * @param {function} logCallback Function to output log messages
         * @returns {Promise<boolean>} Connection success status
         */
        async connect(logCallback = console.log) {
            logCallback("Initializing WebADB connection...");
            
            try {
                // Dynamically import local bundled yume-chan WebADB modules
                const modules = (bundlePromise ? await bundlePromise : null) || await import('./yume-chan-bundle.js');
                const {
                    Adb,
                    AdbDaemonTransport,
                    AdbDaemonWebUsbDeviceManager,
                    AdbCredentialWeb
                } = modules;

                const Manager = AdbDaemonWebUsbDeviceManager.BROWSER;
                if (!Manager) {
                    throw new Error("WebUSB is not supported by your browser. Please use Google Chrome, Microsoft Edge, or Brave.");
                }

                logCallback("Requesting WebUSB permission (select your Android phone from popup)...");
                const webusbDevice = await Manager.requestDevice();
                if (!webusbDevice) {
                    throw new Error("No USB device selected.");
                }

                logCallback(`Device selected: ${webusbDevice.name || webusbDevice.serial || 'Android Device'}`);
                this.connection = await webusbDevice.connect();
                this.credentialStore = new AdbCredentialWeb();

                logCallback("Authenticating with device (Accept prompt on phone screen)...");
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
                throw err;
            }
        }

        /**
         * Executes an ADB shell command and returns its text output
         * @param {string} command The shell command to execute
         * @returns {Promise<string>} Output of the shell command
         */
        async shell(command) {
            if (!this.adb) throw new Error("Device not connected.");
            
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
            const destPath = `/data/local/tmp/sideload_temp.apk`;

            logCallback(`Step 1: Transferring ${originalFilename} to device...`);
            try {
                await this.shell(`rm -f ${destPath}`);
            } catch (e) {}

            await this.pushFile(fileBytes, destPath, logCallback);

            const checkStat = await this.shell(`ls -l ${destPath}`);
            logCallback(`Device storage verified: ${checkStat.trim()}`);

            logCallback(`Step 2: Running Package Manager to install ${originalFilename}...`);
            await this.shell(`chmod 777 ${destPath}`);
            const installRes = await this.shell(`pm install -r -d -g ${destPath}`);
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
         * 1. Directly querying the app via GET_DEVICE_ID broadcast if installed
         * 2. Querying persistent Global Settings (pisophone_hw_id)
         * 3. Computing canonical hash and syncing it to device settings
         */
        async getHardwareInfo(logCallback = console.log) {
            if (!this.adb) throw new Error("Device not connected.");

            const getProp = async (prop) => {
                try {
                    const res = await this.shell(`getprop ${prop}`);
                    return res ? res.trim() : "";
                } catch (e) { return ""; }
            };

            const brandProp = (await getProp("ro.product.brand")) || (await getProp("ro.product.manufacturer")) || "";
            const modelProp = (await getProp("ro.product.model")) || "Android Device";
            let deviceModel = modelProp;
            if (brandProp && modelProp && !modelProp.toLowerCase().startsWith(brandProp.toLowerCase())) {
                deviceModel = `${brandProp} ${modelProp}`.trim();
            }

            // Priority 1: Direct app query via administrative broadcast
            try {
                const broadcastRes = await this.shell("am broadcast -a com.pisophone.kiosk.GET_DEVICE_ID -n com.pisophone.kiosk/.receiver.KioskAdminActionReceiver");
                const match = broadcastRes.match(/data="([^"]+)"/) || broadcastRes.match(/(HW-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4})/);
                if (match && match[1] && match[1].startsWith("HW-")) {
                    const directId = match[1].trim();
                    logCallback(`Directly retrieved synchronized Device ID from app: ${directId}`);
                    try {
                        await this.shell(`settings put global pisophone_hw_id ${directId}`);
                    } catch (e) {}
                    return {
                        deviceId: directId,
                        deviceModel,
                        source: 'app_direct'
                    };
                }
            } catch (e) {
                // App not installed or not active yet, proceed to next priority
            }

            // Priority 2: Check persistent Global Setting (pisophone_hw_id)
            try {
                const globalSetting = (await this.shell("settings get global pisophone_hw_id")).trim();
                if (globalSetting && globalSetting.startsWith("HW-") && !globalSetting.includes("null") && !globalSetting.includes("not found")) {
                    logCallback(`Retrieved persistent Device ID from global settings: ${globalSetting}`);
                    return {
                        deviceId: globalSetting,
                        deviceModel,
                        source: 'global_setting'
                    };
                }
            } catch (e) {
                // Fall through
            }

            // Priority 3: Compute canonical immutable hardware identity
            const androidId = (await this.shell("settings get secure android_id")).trim() || "UNKNOWN_ID";
            const board = await getProp("ro.product.board");
            let brand = await getProp("ro.product.brand");
            const manufacturer = await getProp("ro.product.manufacturer");
            if (!brand || brand.toLowerCase() === "unknown") {
                brand = manufacturer || "";
            }
            const device = await getProp("ro.product.device");
            const hardware = await getProp("ro.hardware");
            const model = await getProp("ro.product.model");
            const product = await getProp("ro.product.name");

            const rawHardwareString = `${androidId}|${board}|${brand}|${device}|${hardware}|${manufacturer}|${model}|${product}`;
            
            const encoder = new TextEncoder();
            const data = encoder.encode(rawHardwareString);
            const hashBuffer = await crypto.subtle.digest('SHA-256', data);
            const hashArray = Array.from(new Uint8Array(hashBuffer));
            const hex = hashArray.map(b => b.toString(16).padStart(2, '0')).join('').toUpperCase();
            
            const deviceId = `HW-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}`;

            // Persist synchronized ID to device settings so app reads the exact same ID
            try {
                await this.shell(`settings put global pisophone_hw_id ${deviceId}`);
                logCallback(`Synchronized canonical Device ID to device settings: ${deviceId}`);
            } catch (e) {}

            return {
                deviceId,
                deviceModel,
                hardwareHash: hex,
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
                
                // Query Cloudflare KV / Worker endpoint for license status
                try {
                    let ownerToken = null;
                    try {
                        const userStr = localStorage.getItem('piso_google_user');
                        if (userStr) {
                            ownerToken = JSON.parse(userStr).token;
                        }
                    } catch(e) {}

                    const workerApiBase = 'https://pisophone-licensing-api.evankhell897.workers.dev';
                    const checkResp = await fetch(`${workerApiBase}/api/device/register`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            deviceId: deviceId,
                            hardwareHash: deviceId,
                            deviceModel: deviceModel,
                            ownerToken: ownerToken
                        })
                    });
                    if (checkResp.ok) {
                        serverLicenseData = await checkResp.json();
                        if (serverLicenseData.status === 'PAID') {
                            logCallback(`🌟 Verified: Commercial License Active (${serverLicenseData.daysRemaining} days remaining).`);
                        } else {
                            logCallback(`✨ Device registered with hardware ID: ${deviceId}. Ready for setup tutorial and activation.`);
                        }
                    }
                } catch (apiErr) {
                    logCallback(`Note: Cloudflare backend offline/unreachable; proceeding with hardware local seal.`);
                }
            } catch (e) {
                logCallback(`Hardware check warning: ${e.message}`);
            }

            const apkBytes = this.cachedApkBytes;

            logCallback("Step 2: Transferring locally cached APK to device...");

            try {
                // Clear any previous temporary files
                await this.shell(`rm -f ${DEVICE_TEMP_APK_PATH}`);
            } catch (e) {}

            // Push APK using Sync protocol
            await this.pushFile(apkBytes, DEVICE_TEMP_APK_PATH, logCallback);

            // Verify file size on device
            const checkStat = await this.shell(`ls -l ${DEVICE_TEMP_APK_PATH}`);
            logCallback(`Device storage verified: ${checkStat.trim()}`);

            logCallback("Step 3: Running Package Manager to install the application...");
            await this.shell(`chmod 777 ${DEVICE_TEMP_APK_PATH}`);
            const installRes = await this.shell(`pm install -r -d -g ${DEVICE_TEMP_APK_PATH}`);
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

            // Launch the main activity
            await this.shell(`am start -n ${PACKAGE_NAME}/.MainActivity`);
            
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
                logCallback("🌟 Syncing active 1-Year Commercial License directly to device...");
                try {
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.ACTIVATE -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver --es key "${serverLicenseData.licenseKey}"`);
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.ACTIVATE -p ${PACKAGE_NAME} --es key "${serverLicenseData.licenseKey}"`);
                    await new Promise(r => setTimeout(r, 800));
                    logCallback("🔄 Restarting PisoPhone to apply 1-Year Commercial License...");
                    await this.shell(`am broadcast -a ${PACKAGE_NAME}.RESTART -p ${PACKAGE_NAME}`);
                    await this.shell(`am force-stop ${PACKAGE_NAME}`);
                    await new Promise(r => setTimeout(r, 600));
                    await this.shell(`am start -n ${PACKAGE_NAME}/.MainActivity`);
                } catch (e) {
                    logCallback(`Note: License push broadcast: ${e.message}`);
                }
            }

            // Clean up temporary APK
            try { 
                await this.shell(`rm -f ${DEVICE_TEMP_APK_PATH}`); 
            } catch (e) {}

            logCallback("🎉 PisoPhone Kiosk setup complete! Device is now secured.");
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
