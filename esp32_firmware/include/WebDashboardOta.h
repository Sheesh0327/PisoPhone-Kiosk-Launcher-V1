#ifndef WEB_DASHBOARD_OTA_H
#define WEB_DASHBOARD_OTA_H

#include <pgmspace.h>

static const char OTA_FORM_HTML[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="light dark">
    <title>PisoPhone Firmware Update</title>
    <link rel="stylesheet" href="/assets/portal.css?v={ASSET_V_PORTAL_CSS}">
</head>
<body>
{PORTAL_ICONS}
    <div class="center-page">
        <div class="card-narrow" style="max-width: 520px;">
            <div class="brand">
                <span class="brand-mark"><svg class="ic ic-lg"><use href="#i-logo"/></svg></span>
                <span class="brand-name">PisoPhone</span>
                <span class="brand-sub">Firmware update</span>
            </div>

            <div class="panel">
                <div class="panel-head">
                    <h2><svg class="ic"><use href="#i-cloud"/></svg>From the website</h2>
                    <span id="cloud_ver_badge" class="tag accent">Checking…</span>
                </div>
                <div class="panel-body">
                    <p class="hint">Downloads the latest firmware from the PisoPhone website. It is checked against its signature before the box installs it.</p>
                    <div class="split">
                        <button type="button" class="btn" onclick="checkCloudUpdate()"><svg class="ic"><use href="#i-search"/></svg>Check version</button>
                        <button type="button" id="cloud_update_btn" class="btn primary" onclick="installCloudFirmware()"><svg class="ic"><use href="#i-download"/></svg>Install update</button>
                    </div>
                </div>
            </div>

            <div class="panel">
                <div class="panel-head"><h2><svg class="ic"><use href="#i-upload"/></svg>From a file</h2></div>
                <div class="panel-body">
                    <div class="field">
                        <label for="local_file_input">Firmware file (firmware.bin)</label>
                        <input type="file" id="local_file_input" accept=".bin">
                    </div>
                    <div class="field">
                        <label for="local_manifest_input">Its signed manifest (firmware.bin.manifest.json)</label>
                        <input type="file" id="local_manifest_input" accept=".json">
                        <span class="hint">Made by <code>sign_firmware.py</code>. A box with a signing key refuses an image without it.</span>
                    </div>
                    <button type="button" id="local_upload_btn" class="btn block" onclick="uploadLocalFirmware()"><svg class="ic"><use href="#i-upload"/></svg>Install from file</button>
                </div>
            </div>

            <div class="progress" id="progress_wrapper"><i id="progress_bar"></i></div>
            <div class="note hidden" id="status_message"></div>
            <a href="/">&larr; Back to the dashboard</a>
        </div>
    </div>

    <script src="/assets/portal-core.js?v={ASSET_V_PORTAL_CORE}"></script>
    <script>window.PISO_OTA = { chip: "{CHIP_ID}", version: "{FW_VERSION}" };</script>
    <script src="/assets/ota.js?v={ASSET_V_OTA}"></script>
</body>
</html>
)HTML";

#endif // WEB_DASHBOARD_OTA_H
