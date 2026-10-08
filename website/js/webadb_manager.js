/**
 * USB setup (the fallback to the QR code): installs PisoPhone on a phone over WebUSB ADB, makes it the device owner and
 * hands it the box's details in one step. Needs Chrome/Edge, a USB data cable and USB debugging on the phone.
 * The ADB library (js/yume-chan-bundle.js, vendored, its hash checked in CI) is loaded only when the USB method is used.
 */
(function () {
    const P = window.PisoProvisioning;
    const PACKAGE_NAME = P.PACKAGE_NAME;
    const TEMP_APK = "/data/local/tmp/pisophone.apk";
    const INSTALL_TIMEOUT_MS = 120000; // Package Manager verifies and compiles the app: 30-90 s on a budget phone
    const MIN_APK_BYTES = 500000;
    const PUSH_CHUNK = 32 * 1024;      // small chunks: some budget phones' USB stalls on bigger ones

    let modules = null;
    /** The ADB library, loaded once (also used to warm it up when the USB method is chosen). */
    function loadAdb() {
        if (!modules) {
            modules = import(new URL('js/yume-chan-bundle.js', document.baseURI).href).then(m => {
                const mod = m.Adb ? m : m.default;
                if (!mod || !mod.Adb) throw new Error("The ADB library did not load (js/yume-chan-bundle.js).");
                return mod;
            }).catch(e => { modules = null; throw e; });
        }
        return modules;
    }

    const sleep = (ms) => new Promise(r => setTimeout(r, ms));
    const isZip = (b) => b && b.length >= MIN_APK_BYTES && b[0] === 0x50 && b[1] === 0x4B && b[2] === 0x03 && b[3] === 0x04;

    // A pm install result as a message that names the real cause.
    function describeInstallFailure(output) {
        const text = (output || '').trim();
        const m = text.match(/Failure\s*\[([A-Z_]+)(?::\s*([^\]]*))?\]/);
        const code = m ? m[1] : '';
        const detail = m && m[2] ? ` (${m[2].trim()})` : '';
        const hints = {
            INSTALL_FAILED_UPDATE_INCOMPATIBLE: 'A PisoPhone build signed with a different key is installed. Uninstall it (or factory reset the phone), then run setup again.',
            INSTALL_FAILED_INSUFFICIENT_STORAGE: 'The phone is out of storage space. Free up space and retry.',
            INSTALL_FAILED_OLDER_SDK: 'This Android version is too old. PisoPhone needs Android 8.0 or newer.',
            INSTALL_FAILED_VERSION_DOWNGRADE: 'A newer PisoPhone version is already installed.',
            INSTALL_PARSE_FAILED_NO_CERTIFICATES: 'The downloaded APK is not signed. Download it again.',
            INSTALL_PARSE_FAILED_NOT_APK: 'The downloaded file is not an APK (it may be an error page). Download it again.',
            INSTALL_FAILED_USER_RESTRICTED: 'The phone blocked the install. Xiaomi/Redmi/POCO: turn on "Install via USB" in Developer options and retry.',
            INSTALL_FAILED_VERIFICATION_FAILURE: 'Play Protect or a verifier blocked the install. Turn it off for this install and retry.'
        };
        if (code) return `Installation failed: ${code}${detail}. ${hints[code] || ''}`.trim();
        return `Installation failed: ${text || 'no output from Package Manager'}`;
    }

    // Why the phone cannot take a device owner, named from what it reports.
    function describeDeviceOwnerFailure(output) {
        const text = String(output || "").trim();
        if (/device owner (is )?already (set|provisioned)|already.*device owner/i.test(text)) {
            return "Another app is already the device owner of this phone. Factory reset it, skip every setup screen, and run setup again.";
        }
        if (text === "users" || /several users|multiple users|more than one user|users? on the device|secondary user/i.test(text)) {
            return "The phone has more than one user (a guest, a work profile or Secure Folder). Remove them in Settings > System > Multiple users, or factory reset, then retry.";
        }
        if (text === "accounts" || /accounts?\b/i.test(text)) {
            return "An account (Google, Samsung, Xiaomi...) is signed in on this phone. Remove every account in Settings > Accounts, or factory reset and skip account setup.";
        }
        if (/MANAGE_DEVICE_ADMINS|SecurityException|permission/i.test(text)) {
            return `The phone's security settings blocked the setup: ${text}\n` +
                "Xiaomi / Redmi / POCO: turn on \"USB debugging (Security settings)\" (it asks you to sign in to a Mi account: sign in, turn it on, then remove the account again before setup).\n" +
                "Samsung: remove the Samsung account and turn off Auto Blocker.";
        }
        return `Device owner setup failed: ${text || "no answer from the phone"}`;
    }

    // what only ADB can grant (the USB setup and the QR + USB setup's last step): "display over other apps" (the lock
    // screen and the time bubble are overlays), secure settings, and no battery optimisation for the kiosk
    const GRANTS = `appops set ${PACKAGE_NAME} SYSTEM_ALERT_WINDOW allow; pm grant ${PACKAGE_NAME} android.permission.WRITE_SECURE_SETTINGS; ` +
        `dumpsys deviceidle whitelist +${PACKAGE_NAME}`;

    class WebADBManager {
        constructor() {
            this.adb = null;
            this.apk = null;      // APK bytes (downloaded once per page, or chosen from a file)
            this.serial = null;
        }

        warmUp() { loadAdb().catch(() => {}); }

        /** An APK the person picked on this computer (instead of the site's). */
        setApkBytes(bytes) {
            if (!isZip(bytes)) throw new Error("That file is not an Android app (APK).");
            this.apk = bytes;
        }

        /** The site's own APK (the one this page's branch published), downloaded once. */
        async getApk(log) {
            if (this.apk) return this.apk;
            const url = new URL('update/app-release.apk', document.baseURI).href;
            log(`Downloading PisoPhone from ${url}...`);
            const res = await fetch(url, { cache: 'no-store' });
            if (!res.ok) throw new Error(`The app could not be downloaded (HTTP ${res.status}). Check the internet connection, or choose an APK file.`);
            const bytes = new Uint8Array(await res.arrayBuffer());
            if (!isZip(bytes)) throw new Error("The download is not an Android app (an error page?). Try again, or choose an APK file.");
            log(`Downloaded ${(bytes.length / 1048576).toFixed(1)} MB.`);
            return (this.apk = bytes);
        }

        /** Connects to the phone (a phone allowed before is used without asking), then waits for USB debugging approval. */
        async connect(log) {
            const { Adb, AdbDaemonTransport, AdbDaemonWebUsbDeviceManager, AdbCredentialWeb } = await loadAdb();
            const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
            if (!manager) throw new Error("This browser cannot use USB. Use Google Chrome or Microsoft Edge on a computer.");
            let device = null, connection = null;
            for (const d of (await manager.getDevices().catch(() => [])) || []) {
                try { connection = await d.connect(); device = d; break; } catch (e) {}
            }
            if (!device) {
                log("Choose your phone in the browser's USB window...");
                device = await manager.requestDevice();
                if (!device) throw new Error("No phone was chosen.");
                for (let attempt = 1; !connection; attempt++) {
                    try {
                        connection = await device.connect();
                    } catch (e) {
                        if (attempt >= 3) {
                            throw new Error("The phone's USB is in use by another program. Close Android Studio, scrcpy or other ADB tabs, " +
                                "unplug and replug the cable, and set the phone's USB mode to File transfer.");
                        }
                        await sleep(1500);
                    }
                }
            }
            this.serial = device.serial || 'phone';
            log("Allow USB debugging on the phone (tick \"Always allow\")...");
            let timer;
            const transport = await Promise.race([
                AdbDaemonTransport.authenticate({ serial: device.serial, connection, credentialStore: new AdbCredentialWeb() }),
                new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(
                    "USB debugging was not allowed within a minute. Unlock the phone, tap Allow on \"Allow USB debugging?\", and retry.")), 60000); })
            ]).finally(() => clearTimeout(timer));
            this.adb = new Adb(transport);
            log(`Connected to ${device.name || this.serial}.`);
        }

        /** Runs a shell command on the phone and returns its output. */
        async shell(command, timeoutMs = 15000) {
            if (!this.adb) throw new Error("The phone is not connected.");
            const sp = this.adb.subprocess;
            const run = sp && sp.noneProtocol ? sp.noneProtocol.spawnWaitText(command) : sp.spawnWaitText(command);
            let timer;
            try {
                return await Promise.race([run, new Promise((_, reject) => {
                    timer = setTimeout(() => reject(new Error(`The phone did not answer in ${Math.round(timeoutMs / 1000)} s: ${command.slice(0, 40)}`)), timeoutMs);
                })]);
            } finally {
                clearTimeout(timer);
            }
        }

        async push(bytes, path, log) {
            const sync = await this.adb.sync();
            let offset = 0, nextLog = 0;
            try {
                await sync.write({
                    filename: path,
                    file: new ReadableStream({
                        pull(controller) {
                            if (offset >= bytes.length) { controller.close(); return; }
                            const end = Math.min(offset + PUSH_CHUNK, bytes.length);
                            controller.enqueue(bytes.subarray(offset, end));
                            offset = end;
                            const pct = Math.floor(100 * end / bytes.length);
                            if (pct >= nextLog) { log(`Copying to the phone: ${pct}%`); nextLog = pct + 25; }
                        }
                    })
                });
            } finally {
                try { sync.dispose(); } catch (e) {}
            }
        }

        async isOurDeviceOwner() {
            const out = await this.shell("dpm list-owners 2>/dev/null; dumpsys device_policy 2>/dev/null | grep -i -A2 'device owner'").catch(() => "");
            return /com\.pisophone\.kiosk/.test(out);
        }

        /** The app's answer to GET_DEVICE_ID: its hardware id, and whether it took the setup (result code -1). */
        async askApp() {
            const out = await this.shell(`am broadcast -a ${PACKAGE_NAME}.GET_DEVICE_ID -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver`).catch(() => "");
            const id = (out.match(/data="([^"]+)"/) || [])[1] || "";
            // the app answers RESULT_OK (-1) once it has taken its setup
            return { id, paired: /result=-1\b/.test(out) };
        }

        /**
         * The whole USB setup: the phone is checked first (so a phone that cannot be set up fails in seconds, not after the
         * copy), then the app is installed, made the device owner and given the box's details in one start, which the app
         * confirms. Returns the phone's hardware id (the box's page uses it to pair the slot).
         */
        async installKioskApp(prov, log) {
            if (!this.adb) throw new Error("The phone is not connected.");
            const apk = await this.getApk(log);
            const owner = await this.isOurDeviceOwner();
            if (!owner) {
                log("Checking that this phone can be set up...");
                const users = await this.shell("pm list users").catch(() => "");
                if ((users.match(/UserInfo\{/g) || []).length > 1) throw new Error(describeDeviceOwnerFailure("users"));
                const accounts = await this.shell("dumpsys account | grep -c 'Account {'").catch(() => "0");
                if (parseInt(accounts, 10) > 0) throw new Error(describeDeviceOwnerFailure("accounts"));
            }

            log("Copying PisoPhone to the phone...");
            await this.push(apk, TEMP_APK, log);
            log("Installing (30-90 s on a budget phone)...");
            let res = await this.shell(`pm install -r -d -g ${TEMP_APK}`, INSTALL_TIMEOUT_MS);
            if (/Unknown option|Unknown flag|Unrecognized option/i.test(res)) res = await this.shell(`pm install -r ${TEMP_APK}`, INSTALL_TIMEOUT_MS);
            await this.shell(`rm -f ${TEMP_APK}`).catch(() => {});
            if (!/^\s*Success\b/m.test(res)) throw new Error(describeInstallFailure(res));
            log("Installed.");

            if (owner) {
                log("PisoPhone is already the device owner: the setup is applied again.");
            } else {
                const dpm = await this.shell(`dpm set-device-owner ${P.ADMIN_COMPONENT}`);
                if (!/^\s*Success/im.test(dpm)) throw new Error(describeDeviceOwnerFailure(dpm));
                log("PisoPhone is the device owner (kiosk lock).");
            }
            await this.shell(GRANTS).catch(() => {});

            // one start carries everything; the app checks it is in its first-setup window, stores it and starts the kiosk
            log("Giving the app the box's details...");
            const extras = P.buildProvisioningExtras(prov);
            await this.shell(`am start -n ${PACKAGE_NAME}/.MainActivity -a ${PACKAGE_NAME}.SETUP_DIRECT --ez activate true${extras}`);
            let answer = { id: "", paired: false };
            for (let i = 0; i < 8 && !answer.paired; i++) {
                await sleep(1000);
                answer = await this.askApp();
            }
            if (!answer.paired) {
                // an app that was already running answers to the broadcast form
                await this.shell(`am broadcast -a ${PACKAGE_NAME}.ACTIVATE -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver${extras}`).catch(() => {});
                await sleep(1500);
                answer = await this.askApp();
            }
            if (!answer.paired) throw new Error("The app did not take the box's details. Open PisoPhone on the phone once, then run setup again.");
            log(`The app is set up (${answer.id}).`);
            return answer.id;
        }

        /**
         * QR + USB setup: the phone was set up by its QR code (PisoPhone is the device owner and has turned USB debugging on
         * by itself), so only what Android lets nothing but ADB grant is left: "display over other apps", secure settings and
         * the battery exemption. The app then starts the kiosk and turns USB debugging off again. Returns the phone's
         * hardware id (the box's page pairs the slot with it).
         */
        async grantPermissions(log) {
            if (!this.adb) throw new Error("The phone is not connected.");
            if (!(await this.isOurDeviceOwner())) {
                throw new Error("PisoPhone is not set up on this phone yet. Scan the QR code with it first, wait until it shows " +
                    "PisoPhone (\"One last step\"), then click Finish over USB again.");
            }
            log("Granting the permissions...");
            await this.shell(GRANTS).catch(() => {});
            const ops = await this.shell(`appops get ${PACKAGE_NAME} SYSTEM_ALERT_WINDOW`).catch(() => "");
            if (!/allow/i.test(ops)) {
                throw new Error("Android did not grant \"display over other apps\". On the phone, use \"Open the setting\" on its " +
                    "One last step screen instead.");
            }
            log("Granted: display over other apps, secure settings, battery exemption.");
            const { id } = await this.askApp();
            // the app starts the kiosk and turns USB debugging off a few seconds after this (the connection then ends)
            await this.shell(`am broadcast -a ${PACKAGE_NAME}.SETUP_GRANTS_DONE -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver`).catch(() => {});
            log("The kiosk is starting; USB debugging turns off by itself. You can unplug the phone.");
            return id;
        }

        /** Removes the kiosk from a phone connected by USB. No PIN: the phone accepts it while USB debugging is on. */
        async deprovision(log) {
            const out = await this.shell(`am broadcast -a ${PACKAGE_NAME}.DEPROVISION -n ${PACKAGE_NAME}/.receiver.KioskAdminActionReceiver`);
            log(out.trim());
            // The phone restores its apps and gives up its device-owner role first; that can take a while, so the removal
            // is tried again until the phone lets go of the app.
            let un = "";
            for (let attempt = 0; attempt < 12; attempt++) {
                await sleep(3000);
                await this.shell(`dpm remove-active-admin ${P.ADMIN_COMPONENT}`).catch(() => {});
                un = await this.shell(`pm uninstall ${PACKAGE_NAME}`);
                if (!/Failure/.test(un)) break;
                log("Waiting for the phone to release the app...");
            }
            log(un.trim());
            if (/Failure/.test(un)) throw new Error(`The app could not be removed: ${un.trim()}. Is USB debugging still on, and is this the phone you meant?`);
        }

        async disconnect() {
            if (this.adb) { try { await this.adb.close(); } catch (e) {} }
            this.adb = null;
        }
    }

    window.webADB = new WebADBManager();
    Object.assign(window.PisoProvisioning, { describeInstallFailure, describeDeviceOwnerFailure });
})();
