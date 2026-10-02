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

    <script>window.PISO_OTA = { chip: "{CHIP_ID}", version: "{FW_VERSION}" };</script>
    <script src="/assets/ota.js?v={ASSET_V_OTA}"></script>
</body>
</html>
)HTML";

#endif // WEB_DASHBOARD_OTA_H
