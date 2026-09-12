#ifndef WEB_DASHBOARD_TEMPLATE_H
#define WEB_DASHBOARD_TEMPLATE_H

#include <Arduino.h>

const char PORTAL_HTML_TEMPLATE[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>HARDWARE Admin Console</title>
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap" rel="stylesheet">
    <script src="https://cdnjs.cloudflare.com/ajax/libs/qrious/4.0.2/qrious.min.js"></script>
    <style>
{PORTAL_STYLES}
    </style>
    <script>
{PORTAL_SCRIPTS_CORE}
    </script>
</head>
<body>
    <div class="app-container">
        <!-- Brand Header Bar -->
        <div class="header-bar">
            <div class="logo-container">
                <svg class="logo-icon" width="28" height="28" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                    <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2.5" />
                    <path d="M13 7L8 13H12L11 17L16 11H12L13 7Z" fill="currentColor" />
                </svg>
                <h1 class="logo-text">
                    <span class="logo-piso">Piso</span><span class="logo-phone">Phone</span>
                </h1>
                <span class="badge-pill">Kiosk Admin</span>
                <div id="wifi_quality_pill" class="badge-pill" style="background: rgba(16, 185, 129, 0.15); color: #10B981; border: 1px solid rgba(16, 185, 129, 0.3); display: inline-flex; align-items: center; gap: 6px; font-weight: 700; padding: 4px 10px; border-radius: 20px;" title="ESP32 Real-Time Wi-Fi RSSI Signal Quality">
                    <span id="wifi_icon">📶</span>
                    <span id="wifi_signal_text">Wi-Fi: {WIFI_RSSI} dBm ({WIFI_QUALITY}%)</span>
                </div>
            </div>
            <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                <button type="button" id="theme_toggle_btn" onclick="toggleTheme()" class="btn btn-outline btn-sm">🌙 Dark Mode</button>
                <span class="status-badge">🟢 ONLINE</span>
                <a href="/logout" onclick="return confirm('Log out?');" class="btn btn-outline btn-sm">🚪 Logout</a>
            </div>
        </div>

        <!-- Navigation Tabs -->
        <div class="tabs">
            <div class="tab active" onclick="switchTab('tab-dashboard')">📊 Dashboard</div>
            <div class="tab" onclick="switchTab('tab-settings')">🛠️ Settings</div>
            <div class="tab" onclick="switchTab('tab-tools')">⚡ Advanced Tools</div>
        </div>

        <!-- TAB 1: DASHBOARD -->
        <div id="tab-dashboard" class="tab-content active">
            <div class="grid">
                <!-- Master Activation Vault & Seat Slots Manager -->
                <div class="grid-full">
                    {DEVICE_SLOTS_MANAGER}
                </div>

                <!-- Live Devices List (Horizontal) -->
                <div class="card grid-full">
                    <div class="card-header">
                        <div style="display: flex; align-items: center; gap: 10px;">
                            <h3 class="card-title">📡 Live Device Status</h3>
                            <span id="available_slots_badge" class="badge" style="background: rgba(16, 185, 129, 0.15); color: var(--primary); font-weight: 700; padding: 3px 10px; border-radius: 12px; font-size: 11px;">
                                {MAX_SLOTS} Seats
                            </span>
                        </div>
                        <div style="display: flex; align-items: center; gap: 8px;">
                            <span class="status-badge" style="background: var(--status-good-bg); color: var(--status-good); border-color: var(--status-good-border);">LIVE SYNC</span>
                            <button type="button" class="btn btn-outline btn-sm" onclick="fetchDeviceStatus()">🔄 Refresh</button>
                        </div>
                    </div>
                    <div id="live_devices_container" class="device-list">
                        <div style="padding: 24px; text-align: center; color: var(--text-muted);">Loading devices...</div>
                    </div>
                </div>

                <!-- Vault Stats -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">💰 Revenue Vault</h3>
                    </div>
                    <div style="background: var(--input-bg); padding: 24px; border-radius: var(--radius-lg); text-align: center; border: 1px solid var(--border); margin-bottom: 4px;">
                        <div style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.75px; margin-bottom: 6px;">Total Coins (PHP)</div>
                        <div style="font-size: 36px; font-weight: 800; color: var(--primary);">₱{TOTAL_COINS}</div>
                    </div>
                    <div style="font-size: 13px; text-align: center; color: var(--text-muted); background: var(--bg); padding: 12px; border-radius: var(--radius-md); border: 1px solid var(--border); font-weight: 500;">
                        Session Coins: <b style="color: var(--text-main); font-weight: 700;">₱{SESSION_COINS}</b>
                    </div>
                    <div style="margin-top: 8px; display: flex; flex-direction: column; gap: 8px;">
                        <div style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 2px;">Simulate Coin Drops (PHP)</div>
                        <div style="display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px;">
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(1)">₱1</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(5)">₱5</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(10)">₱10</button>
                            <button type="button" class="btn btn-outline btn-sm" onclick="triggerUniversalCoin(20)">₱20</button>
                        </div>
                    </div>
                </div>

                <!-- Quick Add Time -->
                <form id="quick_adjust_form" action="/add_time" method="POST" class="card" onsubmit="return validateQuickAdjust(event)">
                    <div class="card-header">
                        <h3 class="card-title">⏱️ Quick Adjust Time</h3>
                    </div>
                    {QUICK_TIME_ALERT}
                    <div class="form-group">
                        <label>Target Device</label>
                        <select id="quick_adjust_target" name="target_ip" onchange="checkQuickAdjustInactive()">
                            <option value="ALL">All Devices (Broadcast)</option>
                            {DEVICE_OPTIONS}
                        </select>
                        <div id="quick_adjust_warn" style="display: none; margin-top: 6px; padding: 6px 10px; background: rgba(239, 68, 68, 0.15); color: var(--danger); border: 1px solid rgba(239, 68, 68, 0.3); border-radius: 6px; font-size: 11px; font-weight: 700;">
                            🚫 Selected device is INACTIVE. Manual time adjustment is blocked until activations are allocated.
                        </div>
                    </div>
                    <div class="form-group">
                        <label>Minutes</label>
                        <input type="number" name="add_minutes" value="60" min="1">
                    </div>
                    <div style="display: flex; gap: 12px; margin-top: 12px;">
                        <button type="submit" name="adjust_action" value="add" class="btn btn-warning" style="flex: 1;" onclick="return validateQuickAdjust(event, 'add')">+ Add</button>
                        <button type="submit" name="adjust_action" value="subtract" class="btn btn-danger" style="flex: 1;" onclick="return validateQuickAdjust(event, 'subtract')">- Subtract</button>
                    </div>
                </form>
            </div>
        </div>

        <!-- TAB 2: SETTINGS -->
        <div id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="
                event.preventDefault();
                const formData = new FormData(this);
                fetch('/save', { method: 'POST', body: new URLSearchParams(formData) })
                    .then(res => { if (res.ok) alert('✅ Configuration saved & pushed live!'); else alert('❌ Failed to save configuration.'); })
                    .catch(err => alert('Error: ' + err));
            ">
                <div class="grid">
                    <!-- Network -->
                    <div class="card">
                        <h3 class="card-title">📡 Wi-Fi & Network</h3>
                        <div class="form-group" style="background: var(--input-bg); padding: 14px; border-radius: var(--radius-md); border: 1px solid var(--border); margin-bottom: 14px;">
                            <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                                <span style="font-size: 11px; font-weight: 700; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.5px;">Real-Time Wi-Fi Quality (RSSI)</span>
                                <span id="wifi_status_badge" class="badge" style="background: var(--status-good-bg); color: var(--status-good); font-weight: 700; padding: 2px 8px; border-radius: 10px; font-size: 11px;">Live</span>
                            </div>
                            <div style="display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px;">
                                <span id="wifi_rssi_display" style="font-size: 20px; font-weight: 800; color: var(--text-main); font-family: monospace;">{WIFI_RSSI} dBm</span>
                                <span id="wifi_quality_pct" style="font-size: 13px; font-weight: 700; color: var(--primary);">{WIFI_QUALITY}% Quality</span>
                            </div>
                            <div style="background: var(--bg); border: 1px solid var(--border); border-radius: 10px; height: 8px; overflow: hidden; margin-bottom: 6px;">
                                <div id="wifi_meter_fill" style="background: var(--primary); height: 100%; width: {WIFI_QUALITY}%; transition: width 0.4s ease, background-color 0.4s ease;"></div>
                            </div>
                            <div style="display: flex; justify-content: space-between; font-size: 11px; color: var(--text-muted); margin-top: 4px;">
                                <span>SSID: <b id="wifi_ssid_display" style="color: var(--text-main);">{WIFI_SSID}</b></span>
                                <span>IP: <b id="wifi_ip_display" style="color: var(--text-main);">{IP_ADDRESS}</b></span>
                            </div>
                        </div>
                        <div class="form-group">
                            <label>SSID</label>
                            <input type="text" name="wifi_ssid" value="{WIFI_SSID}">
                        </div>
                        <div class="form-group">
                            <label>Password</label>
                            <input type="password" name="wifi_pass" value="{WIFI_PASS}">
                        </div>
                        <div class="form-group">
                            <label>Android App Port</label>
                            <input type="number" name="port" value="{PORT}">
                            <div class="hint">Default is 8080.</div>
                        </div>
                    </div>

                    <!-- Pricing & Rules -->
                    <div class="card">
                        <div class="card-header" style="margin-bottom: 0;">
                            <h3 class="card-title">🪙 Simple Beam Pricing</h3>
                            <span class="status-badge" style="background: var(--status-warning-bg); color: var(--status-warning); border-color: var(--status-warning-border);">GPIO 4</span>
                        </div>
                        <div class="hint" style="background: var(--bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; font-size: 12px; line-height: 1.4;">
                            ⚠️ These pricing settings apply <b>exclusively to the Simple optical sensor</b> (GPIO 4). The multi-coin acceptor dynamically calculates rate from coin pulses.
                        </div>
                        <div class="form-group">
                            <label>Coin Price (PHP)</label>
                            <input type="number" step="0.01" name="price" value="{PRICE}">
                        </div>
                        <div class="form-group">
                            <label>Minutes granted per Coin Drop</label>
                            <input type="number" name="minutes" value="{MINUTES}">
                        </div>
                        <div class="form-group">
                            <label>Debounce lock (ms)</label>
                            <input type="number" name="debounce" value="{DEBOUNCE}">
                        </div>
                    </div>

                    <!-- Advanced Security & Pins -->
                    <div class="card">
                        <h3 class="card-title">🔒 Security Vault & Admin Console Password</h3>
                        <div class="form-group">
                            <label>Admin Web & Kiosk App Password</label>
                            <input type="password" name="admin_pw" value="{ADMIN_PASSWORD}">
                            <div class="hint" style="margin-top: 8px; color: var(--text-muted); line-height: 1.4;">
                                💡 Changing this password updates access to both this ESP32 Admin Web Portal and the 
                                <strong>Security Vault & Admin Console</strong> on all paired PisoPhone Android kiosk terminals. 
                                Changes are pushed and synchronized automatically to all active paired devices.
                            </div>
                        </div>
                    </div>

                    <div class="card">
                        <h3 class="card-title">🔌 Hardware GPIO Pins</h3>
                        <div class="form-group">
                            <label>Coin Acceptor Signal GPIO</label>
                            <input type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                            <div class="hint">Signal line from universal multi-coin pulse acceptor (Allan 124A/616A, default: GPIO 3).</div>
                        </div>
                        <div class="form-group">
                            <label>Indicator LED GPIO</label>
                            <input type="number" name="led_pin" value="{LED_PIN}">
                        </div>
                        <div class="form-group">
                            <label>LED Polarity Logic</label>
                            <select name="led_active_low">
                                <option value="1" {LED_ACTIVE_LOW_SELECTED}>Active LOW (Onboard blue LED)</option>
                                <option value="0" {LED_ACTIVE_HIGH_SELECTED}>Active HIGH (Standard External LED)</option>
                            </select>
                        </div>
                        <div class="form-group">
                            <label>Relay Power GPIO</label>
                            <input type="number" name="relay_pin" value="{RELAY_PIN}">
                            <div class="hint">Coin slot enable / power relay (energized only during Insert Coin, default: GPIO 5).</div>
                        </div>
                        <div class="form-group">
                            <label>Relay Polarity</label>
                            <select name="relay_active_low">
                                <option value="0" {RELAY_HIGH_SELECTED}>Active HIGH (Direct 3.3V/5V drive)</option>
                                <option value="1" {RELAY_LOW_SELECTED}>Active LOW (Optocoupler relay boards)</option>
                            </select>
                            <div class="hint">Invert if relay is ON when it should be OFF.</div>
                        </div>
                    </div>

                    <div class="grid-full">
                        <button type="submit" class="btn" style="width: 100%; font-size: 16px;">💾 Save & Push Configuration Live</button>
                    </div>
                </div>
            </form>
        </div>

        <!-- TAB 3: TOOLS -->
        <div id="tab-tools" class="tab-content">
            <div class="grid">
                <!-- 1v1 Match -->
                <div class="card grid-full">
                    <div class="card-header">
                        <h3 class="card-title">⚔️ 1v1 Match Mode</h3>
                        <span class="status-badge accent">ESPORTS</span>
                    </div>
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" style="display: flex; flex-direction: column; gap: 16px;">
                        <div class="form-group" style="max-width: 200px;">
                            <label>Stake Minutes</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        
                        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 24px;">
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--primary);">🎮 Player 1</label>
                                <select id="p1_select" name="p1_ip" style="margin-bottom: 12px;">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P1</button>
                            </div>
                            <div style="background: var(--bg); padding: 16px; border-radius: var(--radius-md); border: 1px solid var(--border);">
                                <label style="color: var(--danger);">🎮 Player 2</label>
                                <select id="p2_select" name="p2_ip" style="margin-bottom: 12px;">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn btn-outline" style="width: 100%;">🏆 Award Win to P2</button>
                            </div>
                        </div>
                        
                        <button type="button" onclick="checkMatchQualification()" class="btn btn-outline" style="align-self: flex-start; border-color: var(--primary); color: var(--primary);">🔍 Verify Both Players' Balances</button>
                    </form>
                    <div id="match_qual_result" class="alert-box"></div>
                </div>

                <!-- OTA Update -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">🚀 Firmware Upgrade</h3>
                    </div>
                    <p style="font-size: 13px; color: var(--text-muted); margin-bottom: 12px;">Upgrade your Kiosk controller wirelessly directly from the official Cloud update server.</p>
                    <a href="/update" class="btn" style="width: 100%; text-align: center; justify-content: center; background: #10b981; color: #ffffff; font-weight: 700; border: none;">⚡ One-Click Cloud Firmware Update &rarr;</a>
                </div>

                <!-- System Recovery -->
                <div class="card">
                    <div class="card-header">
                        <h3 class="card-title">⚠️ System Recovery</h3>
                    </div>
                    
                    <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset lifetime coin counts?');" style="margin-bottom: 12px;">
                        <label>Reset Vault Counters</label>
                        <div style="display: flex; gap: 8px; margin-top: 6px;">
                            <input type="password" name="reset_pw" placeholder="Admin password">
                            <button type="submit" class="btn btn-danger btn-sm" style="min-height:48px;">Reset</button>
                        </div>
                    </form>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <label>⚡ Relay Pin 5 Hardware Test</label>
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 6px; margin-bottom: 12px;">
                        <button type="button" class="btn" style="background: #10b981;" onclick="fetch('/api/relay?state=1').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned ON! State: ' + d.state))">⚡ Turn Relay ON</button>
                        <button type="button" class="btn btn-outline" onclick="fetch('/api/relay?state=0').then(r=>r.json()).then(d=>alert('Relay Pin ' + d.relay_pin + ' turned OFF! State: ' + d.state))">Turn Relay OFF</button>
                    </div>
                    <hr style="border: none; border-top: 1px solid var(--border); margin: 12px 0;">
                    <div style="display: flex; flex-direction: column; gap: 10px;">
                        <button type="button" class="btn" style="background: #0284c7; box-shadow: 0 4px 12px rgba(2, 132, 199, 0.2);" onclick="if(confirm('🔄 Reboot controller?')) { fetch('/reboot', {method: 'POST'}).then(() => { alert('Rebooting... returning in 5 seconds.'); setTimeout(() => window.location.reload(), 5000); }); }">
                            🔄 Reboot Controller
                        </button>
                        <button type="button" class="btn btn-danger" onclick="if(confirm('⚠️ Factory Reset? All settings will be wiped.')) { fetch('/factory_reset', {method: 'POST'}).then(() => { alert('Resetting...'); setTimeout(() => window.location.reload(), 6000); }); }">
                            Restore Factory Defaults
                        </button>
                    </div>
                </div>
            </div>
        </div>
    </div>

{PORTAL_MODALS}

    <script>
{PORTAL_SCRIPTS_MODALS}
    </script>
</body>
</html>
)HTML";

#endif // WEB_DASHBOARD_TEMPLATE_H
