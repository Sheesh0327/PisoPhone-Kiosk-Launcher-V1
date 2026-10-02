#ifndef WEB_DASHBOARD_OTA_H
#define WEB_DASHBOARD_OTA_H

#include <pgmspace.h>

static const char OTA_FORM_HTML[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Firmware Upgrade</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <style>
        :root {
            --bg: #f8fafc;
            --sub-bg: #ffffff;
            --text-main: #0f172a;
            --text-muted: #64748b;
            --primary: #4f46e5;
            --primary-hover: #4338ca;
            --border: #e2e8f0;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.05), 0 2px 4px -1px rgba(0,0,0,0.03);
            --input-bg: #fafafa;
        }
        [data-theme="dark"] {
            --bg: #0b1120;
            --sub-bg: #1e293b;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --primary: #6366f1;
            --primary-hover: #4f46e5;
            --border: #334155;
            --card-shadow: 0 4px 6px -1px rgba(0,0,0,0.3);
            --input-bg: #0f172a;
        }
        * { box-sizing: border-box; }
        body { font-family: 'Inter', sans-serif; background: var(--bg); margin: 20px; color: var(--text-main); transition: background-color 0.2s, color 0.2s; }
        .container { background: var(--sub-bg); padding: 28px; border-radius: 16px; max-width: 460px; margin: 20px auto; box-shadow: var(--card-shadow); text-align: center; border: 1px solid var(--border); }
        .top-bar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
        h2 { margin: 0; color: var(--text-main); font-size: 20px; font-weight: 700; }
        .status-box { margin: 15px 0; padding: 12px; border-radius: 8px; font-size: 14px; display: none; line-height: 1.4; text-align: left; }
        .error { background: #ffebee; border: 1px solid #ffcdd2; color: #c62828; }
        .success { background: #e8f5e9; border: 1px solid #c8e6c9; color: #1b5e20; }
        .info { background: #e3f2fd; border: 1px solid #bbdefb; color: #0d47a1; }
        [data-theme="dark"] .error { background: #450a0a; border-color: #7f1d1d; color: #fca5a5; }
        [data-theme="dark"] .success { background: #052e16; border-color: #14532d; color: #86efac; }
        [data-theme="dark"] .info { background: #082f49; border-color: #075985; color: #7dd3fc; }
        .progress-container { width: 100%; background: var(--border); border-radius: 10px; height: 18px; margin: 15px 0; overflow: hidden; display: none; }
        .progress-bar { height: 100%; width: 0%; background: #10b981; transition: width 0.1s ease-in-out; }
        input[type=file] { margin: 15px 0; padding: 12px; width: 100%; box-sizing: border-box; border: 1px solid var(--border); border-radius: 8px; background: var(--input-bg); color: var(--text-main); cursor: pointer; }
        button { padding: 12px 20px; background: var(--primary); color: white; border: none; border-radius: 8px; font-weight: 600; width: 100%; cursor: pointer; font-size: 15px; transition: background 0.2s; }
        button:hover { background: var(--primary-hover); }
        button:disabled { background: #64748b; cursor: not-allowed; opacity: 0.6; }
        .theme-btn { background: transparent; border: 1px solid var(--border); color: var(--text-main); padding: 4px 10px; border-radius: 6px; font-size: 12px; cursor: pointer; width: auto; font-weight: 500; }
        .theme-btn:hover { background: var(--border); }
        a { display: inline-block; margin-top: 18px; color: var(--primary); text-decoration: none; font-size: 13px; font-weight: 600; }
        a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <div class="top-bar">
            <h2>📲 Firmware OTA Update</h2>
            <button type="button" class="theme-btn" id="theme_toggle_btn" onclick="toggleTheme()">🌙 Dark</button>
        </div>
        <p style="font-size:13px; color:var(--text-muted); margin-bottom: 16px; line-height: 1.5;">
            Update your Kiosk controller wirelessly directly from the official Cloud server to ensure firmware integrity.
        </p>

        <!-- Cloud Server One-Click OTA Upgrade Card -->
        <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: 12px; padding: 16px; margin-bottom: 20px;">
            <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;">
                <h3 style="font-size: 15px; margin: 0;">🌐 Cloud Server Update</h3>
                <span id="cloud_ver_badge" style="font-size: 11px; font-weight: 700; background: rgba(59,130,246,0.15); color: #3b82f6; padding: 3px 8px; border-radius: 12px;">Checking server...</span>
            </div>
            <p style="font-size: 12px; color: var(--text-muted); margin: 0 0 12px 0;">
                Directly download and flash official system updates from the Cloud server.
            </p>
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px;">
                <button type="button" class="theme-btn" style="width:100%; border-color: var(--primary); color: var(--primary);" onclick="checkCloudUpdate()">🔍 Check Version</button>
                <button type="button" id="cloud_update_btn" style="background: #10b981;" onclick="installCloudFirmware()">⚡ Install Cloud Update</button>
            </div>
        </div>
        
        <!-- Local Firmware Upload Card -->
        <div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: 12px; padding: 16px; margin-bottom: 20px; text-align: left;">
            <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px;">
                <h3 style="font-size: 15px; margin: 0;">📁 Manual Local File Upload</h3>
            </div>
            <p style="font-size: 12px; color: var(--text-muted); margin: 0 0 12px 0;">
                Select a local <b>firmware.bin</b> compiled binary from your machine to flash manually.
            </p>
            <input type="file" id="local_file_input" accept=".bin" style="margin: 0 0 12px 0;">
            <p style="font-size: 12px; color: var(--text-muted); margin: 0 0 8px 0;">
                Also select its signed manifest (<b>firmware.bin.manifest.json</b> from <code>sign_firmware.py</code>). Controllers with a signing key refuse an image without it.
            </p>
            <input type="file" id="local_manifest_input" accept=".json" style="margin: 0 0 12px 0;">
            <button type="button" id="local_upload_btn" style="background: var(--primary);" onclick="uploadLocalFirmware()">📤 Upload and Flash</button>
        </div>

        <div class="progress-container" id="progress_wrapper">
            <div class="progress-bar" id="progress_bar"></div>
        </div>
        
        <div class="status-box" id="status_message"></div>
        <br>
        <a href="/">&larr; Back to Kiosk Dashboard</a>
    </div>

    <script>
    (function() {
        const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
        document.documentElement.setAttribute('data-theme', savedTheme);
    })();

    window.toggleTheme = function() {
        const cur = document.documentElement.getAttribute('data-theme') || 'light';
        const next = cur === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('kiosk_theme', next);
        updateThemeButton();
    };

    function updateThemeButton() {
        const btn = document.getElementById('theme_toggle_btn');
        if (btn) {
            const cur = document.documentElement.getAttribute('data-theme') || 'light';
            btn.innerHTML = cur === 'dark' ? '☀️ Light' : '🌙 Dark';
        }
    }
    document.addEventListener('DOMContentLoaded', updateThemeButton);

    window.showStatus = function(text, type) {
        const box = document.getElementById('status_message');
        box.style.display = 'block';
        box.className = 'status-box ' + type;
        box.innerHTML = text;
    };

    // Which firmware file this board needs and which version it runs; filled in by the ESP32
    // when it serves this page.
    const CHIP_ID = '{CHIP_ID}';
    const RUNNING_VERSION = '{FW_VERSION}';

    // SHA-256 for the OTA page. crypto.subtle only exists on https/localhost pages, and this page is
    // served over plain http by the ESP32, so a small pure-JS fallback is needed.
    function sha256Bytes(bytes) {
        const K = new Uint32Array([
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2]);
        const H = new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);
        const len = bytes.length;
        const padded = new Uint8Array(((len + 9 + 63) >> 6) << 6);
        padded.set(bytes);
        padded[len] = 0x80;
        const view = new DataView(padded.buffer);
        view.setUint32(padded.length - 8, Math.floor(len / 0x20000000), false);
        view.setUint32(padded.length - 4, (len << 3) >>> 0, false);
        const W = new Uint32Array(64);
        const rotr = (x, n) => (x >>> n) | (x << (32 - n));
        for (let off = 0; off < padded.length; off += 64) {
            for (let i = 0; i < 16; i++) W[i] = view.getUint32(off + i * 4, false);
            for (let i = 16; i < 64; i++) {
                const s0 = rotr(W[i-15], 7) ^ rotr(W[i-15], 18) ^ (W[i-15] >>> 3);
                const s1 = rotr(W[i-2], 17) ^ rotr(W[i-2], 19) ^ (W[i-2] >>> 10);
                W[i] = (W[i-16] + s0 + W[i-7] + s1) >>> 0;
            }
            let a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
            for (let i = 0; i < 64; i++) {
                const S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                const ch = (e & f) ^ (~e & g);
                const t1 = (h + S1 + ch + K[i] + W[i]) >>> 0;
                const S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const t2 = (S0 + maj) >>> 0;
                h=g; g=f; f=e; e=(d + t1) >>> 0; d=c; c=b; b=a; a=(t1 + t2) >>> 0;
            }
            H[0]=(H[0]+a)>>>0; H[1]=(H[1]+b)>>>0; H[2]=(H[2]+c)>>>0; H[3]=(H[3]+d)>>>0;
            H[4]=(H[4]+e)>>>0; H[5]=(H[5]+f)>>>0; H[6]=(H[6]+g)>>>0; H[7]=(H[7]+h)>>>0;
        }
        return Array.from(H).map(x => x.toString(16).padStart(8, '0')).join('');
    }

    async function sha256Hex(buffer) {
        const bytes = new Uint8Array(buffer);
        if (window.crypto && window.crypto.subtle) {
            const digest = await window.crypto.subtle.digest('SHA-256', bytes);
            return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
        }
        return sha256Bytes(bytes);
    }


    // The controller only flashes an image that matches a manifest signed with the owner's key. Send it first.
    async function postOtaManifest(m) {
        const body = new URLSearchParams();
        ['chip', 'version', 'sha256', 'size', 'sig'].forEach(function(k) { body.append(k, String(m[k] === undefined ? '' : m[k])); });
        const res = await fetch('/api/ota/manifest', { method: 'POST', body: body });
        let msg = '';
        try { msg = (await res.json()).message || ''; } catch (e) { /* not JSON */ }
        if (!res.ok) throw new Error(msg || ('The controller refused the update manifest (HTTP ' + res.status + ').'));
    }

    async function fetchChipFirmwareInfo() {
        const res = await fetch('https://pisophone.pages.dev/update/firmware.json', { cache: 'no-store' });
        if (!res.ok) throw new Error('firmware.json returned HTTP ' + res.status);
        const data = await res.json();
        const entry = data.chips && data.chips[CHIP_ID];
        if (!entry || !entry.url) throw new Error('No firmware is published for chip "' + CHIP_ID + '" yet.');
        return { data: data, entry: entry };
    }

    window.checkCloudUpdate = async function() {
        const badge = document.getElementById('cloud_ver_badge');
        if (badge) badge.textContent = 'Checking...';
        try {
            const info = await fetchChipFirmwareInfo();
            const data = info.data;
            if (badge) badge.textContent = 'Server v' + (data.version || '3.0.0');
            if (data.version === RUNNING_VERSION) {
                showStatus('<b>✅ Up to date:</b> this controller already runs v' + RUNNING_VERSION + ' (' + CHIP_ID + ').', 'success');
            } else {
                showStatus('<b>🎉 Update available:</b> v' + (data.version || '?') + ' (this controller runs v' + RUNNING_VERSION + ', chip ' + CHIP_ID + ')<br>' + (data.changelog || 'Latest build ready to install.'), 'info');
            }
        } catch(e) {
            if (badge) badge.textContent = 'Server Ready';
            showStatus('<b>Server Update Endpoint:</b> Ready to download latest system update.', 'info');
        }
    };

    window.installCloudFirmware = async function() {
        const cloudBtn = document.getElementById('cloud_update_btn');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');
        
        let startInfo;
        try {
            startInfo = await fetchChipFirmwareInfo();
        } catch (e) {
            showStatus('<b>❌ Cannot update:</b> ' + e.message, 'error');
            return;
        }
        const sameVersion = startInfo.data.version === RUNNING_VERSION;
        const question = sameVersion
            ? 'This controller already runs v' + RUNNING_VERSION + '. Download and flash it again anyway?'
            : 'Download and flash v' + startInfo.data.version + ' (currently v' + RUNNING_VERSION + ')?';
        if (!confirm(question)) return;
        
        if (cloudBtn) cloudBtn.disabled = true;
        
        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#3b82f6';
        
        showStatus('📥 Downloading latest system update from cloud server...', 'info');
        
        try {
            const info = startInfo;
            const expectedSha = (info.entry.sha256 || '').toLowerCase();
            if (!/^[0-9a-f]{64}$/.test(expectedSha)) {
                throw new Error('No checksum is published for this firmware yet, so it cannot be verified. Nothing was flashed.');
            }
            const fwRes = await fetch(info.entry.url, { cache: 'no-store' });
            if (!fwRes.ok) {
                throw new Error('Server returned HTTP ' + fwRes.status + ' when downloading update.');
            }
            const fwBlob = await fwRes.blob();
            // Every ESP32 app image starts with magic byte 0xE9. Anything else is a corrupt file or
            // an HTML error page, and must never reach the flash.
            const magic = new Uint8Array(await fwBlob.slice(0, 1).arrayBuffer());
            if (fwBlob.size < 100000 || magic[0] !== 0xE9) {
                throw new Error('Downloaded file is not a valid ESP32 firmware image (' + fwBlob.size + ' bytes). Nothing was flashed.');
            }
            showStatus('🔐 Verifying checksum...', 'info');
            const actualSha = await sha256Hex(await fwBlob.arrayBuffer());
            if (actualSha !== expectedSha) {
                throw new Error('Checksum mismatch: the download is corrupt or not the published build. Nothing was flashed.');
            }
            
            showStatus('🔏 Checking the signed manifest...', 'info');
            await postOtaManifest({ chip: CHIP_ID, version: info.data.version, sha256: expectedSha, size: fwBlob.size, sig: info.entry.sig || '' });

            showStatus('⚡ Download complete (' + (fwBlob.size/1024).toFixed(1) + ' KB). Preparing to flash HARDWARE partition...', 'info');
            
            const formData = new FormData();
            formData.append('update', fwBlob, 'firmware.bin');
            
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/update', true);
            
            xhr.upload.addEventListener('progress', function(e) {
                if (e.lengthComputable) {
                    const percent = (e.loaded / e.total) * 100;
                    progressBar.style.width = percent + '%';
                    showStatus('Flashing: ' + Math.round(percent) + '% (' + (e.loaded/1024).toFixed(0) + ' KB / ' + (e.total/1024).toFixed(0) + ' KB)...', 'info');
                    if (percent >= 99) {
                        showStatus('Flashing binary to HARDWARE partition... Please do not power off.', 'info');
                    }
                }
            });
            
            xhr.onload = function() {
                if (xhr.status === 200) {
                    progressBar.style.width = '100%';
                    progressBar.style.background = '#10b981';
                    showStatus('<b>✅ SUCCESS: Firmware Updated via Server!</b><br>Rebooting HARDWARE Controller now... returning to dashboard in 5 seconds.', 'success');
                    setTimeout(function() { window.location.href = '/'; }, 5000);
                } else {
                    progressBar.style.background = '#ef4444';
                    showStatus('<b>❌ Flash Error:</b> ' + (xhr.responseText || 'Error flashing downloaded binary'), 'error');
                    if (cloudBtn) cloudBtn.disabled = false;
                }
            };
            
            xhr.onerror = function() {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ Connection Error during upload to ESP32 controller.</b>', 'error');
                if (cloudBtn) cloudBtn.disabled = false;
            };
            
            xhr.send(formData);
        } catch(err) {
            progressBar.style.background = '#ef4444';
            showStatus('<b>❌ Server Fetch Failed:</b> ' + err.message, 'error');
            if (cloudBtn) cloudBtn.disabled = false;
        }
    };

    window.uploadLocalFirmware = async function() {
        const fileInput = document.getElementById('local_file_input');
        const uploadBtn = document.getElementById('local_upload_btn');
        const progressWrapper = document.getElementById('progress_wrapper');
        const progressBar = document.getElementById('progress_bar');

        if (!fileInput.files || fileInput.files.length === 0) {
            alert('Please select a firmware.bin file first.');
            return;
        }

        const file = fileInput.files[0];
        if (!confirm('Flash the local firmware file "' + file.name + '"?')) return;

        if (uploadBtn) uploadBtn.disabled = true;

        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#4f46e5';

        showStatus('⚡ Preparing to flash local HARDWARE partition...', 'info');

        const manifestInput = document.getElementById('local_manifest_input');
        if (manifestInput && manifestInput.files && manifestInput.files.length > 0) {
            try {
                await postOtaManifest(JSON.parse(await manifestInput.files[0].text()));
            } catch (e) {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ Manifest refused:</b> ' + e.message, 'error');
                if (uploadBtn) uploadBtn.disabled = false;
                return;
            }
        }

        const formData = new FormData();
        formData.append('update', file, 'firmware.bin');

        const xhr = new XMLHttpRequest();
        xhr.open('POST', '/update', true);

        xhr.upload.addEventListener('progress', function(e) {
            if (e.lengthComputable) {
                const percent = (e.loaded / e.total) * 100;
                progressBar.style.width = percent + '%';
                showStatus('Flashing local file: ' + Math.round(percent) + '% (' + (e.loaded/1024).toFixed(0) + ' KB / ' + (e.total/1024).toFixed(0) + ' KB)...', 'info');
                if (percent >= 99) {
                    showStatus('Flashing binary to HARDWARE partition... Please do not power off.', 'info');
                }
            }
        });

        xhr.onload = function() {
            if (xhr.status === 200) {
                progressBar.style.width = '100%';
                progressBar.style.background = '#10b981';
                showStatus('<b>✅ SUCCESS: Firmware Updated successfully!</b><br>Rebooting HARDWARE Controller now... returning to dashboard in 5 seconds.', 'success');
                setTimeout(function() { window.location.href = '/'; }, 5000);
            } else {
                progressBar.style.background = '#ef4444';
                showStatus('<b>❌ Flash Error:</b> ' + (xhr.responseText || 'Error flashing local binary'), 'error');
                if (uploadBtn) uploadBtn.disabled = false;
            }
        };

        xhr.onerror = function() {
            progressBar.style.background = '#ef4444';
            showStatus('<b>❌ Connection Error during upload to ESP32 controller.</b>', 'error');
            if (uploadBtn) uploadBtn.disabled = false;
        };

        xhr.send(formData);
    };

    document.addEventListener('DOMContentLoaded', function() { checkCloudUpdate(); });
    </script>
</body>
</html>
)HTML";

#endif // WEB_DASHBOARD_OTA_H
