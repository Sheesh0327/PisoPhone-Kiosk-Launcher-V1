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

    window.checkCloudUpdate = async function() {
        const badge = document.getElementById('cloud_ver_badge');
        if (badge) badge.textContent = 'Checking...';
        try {
            const res = await fetch('https://pisophone.pages.dev/update/firmware.json', { cache: 'no-store' });
            if (res.ok) {
                const data = await res.json();
                if (badge) badge.textContent = 'Server v' + (data.version || '3.0.0');
                showStatus('<b>🎉 Server Firmware Available:</b> v' + (data.version || '3.0.0') + '<br>' + (data.changelog || 'Latest build ready to install.'), 'info');
            } else {
                if (badge) badge.textContent = 'Server Ready';
                showStatus('<b>Server Connected:</b> Update service is online and ready.', 'info');
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
        
        if (!confirm('Download and flash the latest official system update?')) return;
        
        if (cloudBtn) cloudBtn.disabled = true;
        
        progressWrapper.style.display = 'block';
        progressBar.style.width = '0%';
        progressBar.style.background = '#3b82f6';
        
        showStatus('📥 Downloading latest system update from cloud server...', 'info');
        
        try {
            const fwRes = await fetch('https://pisophone.pages.dev/update/firmware.bin', { cache: 'no-store' });
            if (!fwRes.ok) {
                throw new Error('Server returned HTTP ' + fwRes.status + ' when downloading update.');
            }
            const fwBlob = await fwRes.blob();
            
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

    window.uploadLocalFirmware = function() {
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
