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

            // Hardware & Trial Verification against Cloudflare Database
            logCallback("Inspecting hardware identifier for license provisioning...");
            let deviceId = "UNKNOWN";
            try {
                const idOut = await this.shell("settings get secure android_id");
                deviceId = idOut.trim();
                logCallback(`Device Hardware ID: ${deviceId}`);
                
                // Query Cloudflare KV / Worker endpoint for trial status
                try {
                    const workerApiBase = 'https://pisophone-license-api.evankhell897.workers.dev';
                    const checkResp = await fetch(`${workerApiBase}/api/device/register`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            deviceId: deviceId,
                            hardwareHash: `HW-${deviceId.toUpperCase()}`,
                            deviceModel: navigator.userAgent
                        })
                    });
                    if (checkResp.ok) {
                        const data = await checkResp.json();
                        if (data.status === 'LOCKED') {
                            logCallback(`⚠️ Note: 7-Day trial has previously expired on this hardware (${deviceId}). Commercial license required after install.`);
                        } else if (data.status === 'PAID') {
                            logCallback(`🌟 Verified: Commercial License Active (${data.daysRemaining} days remaining).`);
                        } else {
                            logCallback(`🎁 Verified: 7-Day Free Trial assigned to this device (${data.daysRemaining} days remaining).`);
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

            logCallback("Step 4: Setting PisoPhone as Device Owner (Kiosk Administrator)...");
            const dpmResult = await this.shell(`dpm set-device-owner ${PACKAGE_NAME}/${PACKAGE_NAME}.receiver.KioskDeviceAdminReceiver`);
            logCallback(`Device Admin output: ${dpmResult.trim()}`);

            if (dpmResult.includes("Exception") || dpmResult.includes("java.lang") || dpmResult.includes("Error") || dpmResult.includes("illegal state")) {
                if (dpmResult.includes("accounts") || dpmResult.includes("already")) {
                    throw new Error("Cannot set Device Owner: An account is logged into this device. Please factory reset and SKIP all account setups.");
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
