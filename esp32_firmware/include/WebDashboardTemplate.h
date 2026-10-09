#ifndef WEB_DASHBOARD_TEMPLATE_H
#define WEB_DASHBOARD_TEMPLATE_H

#include <Arduino.h>

const char PORTAL_HTML_TEMPLATE[] PROGMEM = R"HTML(
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="light dark">
    <title>PisoPhone Coin Box</title>
    <link rel="stylesheet" href="/assets/portal.css?v={ASSET_V_PORTAL_CSS}">
    <script src="/assets/portal-core.js?v={ASSET_V_PORTAL_CORE}"></script>
</head>
<body>
{PORTAL_ICONS}
    <header class="topbar">
        <div class="topbar-in">
            <div class="brand">
                <span class="brand-mark"><svg class="ic ic-lg"><use href="#i-logo"/></svg></span>
                <span class="brand-name">PisoPhone</span>
                <span class="brand-sub">Coin box</span>
            </div>
            <nav class="tabs">
                <button type="button" class="tab active" onclick="switchTab('tab-dashboard')"><svg class="ic"><use href="#i-overview"/></svg>Overview</button>
                <button type="button" class="tab" onclick="switchTab('tab-settings')"><svg class="ic"><use href="#i-settings"/></svg>Settings</button>
                <button type="button" class="tab" onclick="switchTab('tab-tools')"><svg class="ic"><use href="#i-tools"/></svg>Tools</button>
                <button type="button" class="tab" onclick="switchTab('tab-superadmin')"><svg class="ic"><use href="#i-shield"/></svg>Vendor</button>
            </nav>
            <div class="topbar-end">
                <span id="conn_pill" class="pill ok">Online</span>
                <a href="/logout" onclick="return confirm('Sign out?');" class="linkbtn"><svg class="ic"><use href="#i-logout"/></svg>Sign out</a>
            </div>
        </div>
    </header>

    <main class="shell">
        <div id="banners" class="stack"></div>

        <!-- OVERVIEW -->
        <section id="tab-dashboard" class="tab-content active">
            <div class="stats">
                <div class="stat">
                    <span class="stat-label">Revenue</span>
                    <span class="stat-value">₱{TOTAL_COINS}</span>
                    <span class="stat-sub">₱{SESSION_COINS} this session</span>
                </div>
                <div class="stat">
                    <span class="stat-label">Phones</span>
                    <span class="stat-value"><span id="stat_phones">–</span> <small>of {MAX_SLOTS}</small></span>
                    <span id="stat_phones_sub" class="stat-sub">Checking…</span>
                </div>
                <div id="wifi_stat" class="stat">
                    <span class="stat-label">Wi-Fi signal</span>
                    <span id="wifi_quality_pct" class="stat-value">{WIFI_QUALITY}%</span>
                    <span class="stat-sub"><span id="wifi_rssi_display">{WIFI_RSSI} dBm</span> · <span id="wifi_ssid_display">{WIFI_SSID}</span></span>
                    <div class="meter"><i id="wifi_meter_fill" style="width: {WIFI_QUALITY}%"></i></div>
                </div>
            </div>

            <div class="panel">
                <div class="panel-head">
                    <h2>Phones
                        <span id="pending_requests_badge" class="tag warn hidden"></span>
                    </h2>
                    <div class="actions">
                        <button type="button" class="btn sm" onclick="fetchDeviceStatus()"><svg class="ic"><use href="#i-refresh"/></svg>Refresh</button>
                    </div>
                </div>
                <div id="live_devices_container" class="rows">
                    <div class="row-empty-msg">Loading phones…</div>
                </div>
            </div>

            <form id="quick_adjust_form" action="/add_time" method="POST" class="panel" onsubmit="return validateQuickAdjust(event)">
                <div class="panel-head"><h2>Add or remove time</h2></div>
                <div class="panel-body">
                    {QUICK_TIME_ALERT}
                    <div class="grid">
                        <div class="field">
                            <label for="quick_adjust_target">Phone</label>
                            <select id="quick_adjust_target" name="target_ip" onchange="checkQuickAdjustInactive()">
                                <option value="ALL">All phones</option>
                                {DEVICE_OPTIONS}
                            </select>
                        </div>
                        <div class="field">
                            <label for="quick_adjust_minutes">Minutes</label>
                            <input id="quick_adjust_minutes" type="number" name="add_minutes" value="60" min="1">
                        </div>
                    </div>
                    <div id="quick_adjust_warn" class="note bad hidden"><svg class="ic"><use href="#i-alert"/></svg><span class="grow"></span></div>
                </div>
                <div class="panel-foot">
                    <button type="submit" name="adjust_action" value="subtract" class="btn danger" onclick="return validateQuickAdjust(event, 'subtract')"><svg class="ic"><use href="#i-minus"/></svg>Remove time</button>
                    <button type="submit" name="adjust_action" value="add" class="btn primary" onclick="return validateQuickAdjust(event, 'add')"><svg class="ic"><use href="#i-plus"/></svg>Add time</button>
                </div>
            </form>

            <div class="panel">
                <div class="panel-head">
                    <h2><svg class="ic"><use href="#i-users"/></svg>Player accounts <span id="accounts_count" class="tag"></span></h2>
                    <div class="actions">
                        <button type="button" class="btn sm" onclick="fetchAccounts()"><svg class="ic"><use href="#i-refresh"/></svg>Refresh</button>
                    </div>
                </div>
                <div id="accounts_container" class="rows">
                    <div class="row-empty-msg">Loading accounts…</div>
                </div>
                <div class="panel-body">
                    <p class="hint">Players keep unused time in an account. An account with no time that nobody has used for 30 days is deleted automatically. Time cannot be changed, and an account cannot be deleted, while its player is signed in on a phone.</p>
                </div>
            </div>
        </section>

        <!-- SETTINGS -->
        <section id="tab-settings" class="tab-content">
            <form action="/save" method="POST" onsubmit="return saveSettings(this, event);" class="stack">
                <div class="grid">
                    <div class="panel">
                        <div class="panel-head"><h2><svg class="ic"><use href="#i-wifi"/></svg>Wi-Fi and network</h2></div>
                        <div class="panel-body">
                            <div class="field">
                                <label for="wifi_ssid">Wi-Fi name (SSID)</label>
                                <input id="wifi_ssid" type="text" name="wifi_ssid" value="{WIFI_SSID}" autocomplete="off">
                            </div>
                            <div class="field">
                                <label for="wifi_pass">Wi-Fi password</label>
                                <input id="wifi_pass" type="password" name="wifi_pass" value="{WIFI_PASS}" autocomplete="off">
                            </div>
                            <div class="field">
                                <label for="port">Phone app port</label>
                                <input id="port" type="number" name="port" value="{PORT}">
                                <span class="hint">Default is 8080.</span>
                            </div>
                            <div class="kv"><span class="muted">Box address</span><span id="wifi_ip_display" class="mono">{IP_ADDRESS}</span></div>
                        </div>
                    </div>

                    <div class="panel">
                        <div class="panel-head"><h2><svg class="ic"><use href="#i-lock"/></svg>Admin password</h2></div>
                        <div class="panel-body">
                            <div class="field">
                                <label for="admin_pw">Password for this page and the phones' admin screen</label>
                                <input id="admin_pw" type="password" name="admin_pw" value="{ADMIN_PASSWORD}" autocomplete="new-password">
                                <span class="hint">Changing it also changes the admin password on every paired phone. They pick it up automatically.</span>
                            </div>
                        </div>
                    </div>

                    <div class="panel">
                        <div class="panel-head"><h2><svg class="ic"><use href="#i-coin"/></svg>Coin rate</h2></div>
                        <div class="panel-body">
                            <div class="field">
                                <label for="minutes_per_coin">Minutes per ₱1</label>
                                <input id="minutes_per_coin" type="number" name="minutes_per_coin" value="{MINUTES_PER_COIN}" min="1" max="1440">
                                <span class="hint">Larger coins multiply it (₱5 = 5×, ₱10 = 10×, ₱20 = 20×). Phones get the new rate right away.</span>
                            </div>
                        </div>
                    </div>

                    <div class="panel">
                        <div class="panel-head"><h2><svg class="ic"><use href="#i-chip"/></svg>Hardware pins</h2></div>
                        <div class="panel-body">
                            <div class="split">
                                <div class="field">
                                    <label for="u_coin_pin">Coin slot pin</label>
                                    <input id="u_coin_pin" type="number" name="u_coin_pin" value="{U_COIN_PIN}">
                                </div>
                                <div class="field">
                                    <label for="relay_pin">Relay pin</label>
                                    <input id="relay_pin" type="number" name="relay_pin" value="{RELAY_PIN}">
                                </div>
                            </div>
                            <div class="field">
                                <label for="relay_active_low">Relay type</label>
                                <select id="relay_active_low" name="relay_active_low">
                                    <option value="0" {RELAY_HIGH_SELECTED}>On with a high signal</option>
                                    <option value="1" {RELAY_LOW_SELECTED}>On with a low signal (most relay boards)</option>
                                </select>
                                <span class="hint">Change it if the relay is on when it should be off.</span>
                            </div>
                            <div class="field">
                                <label for="led_pin">Indicator LED pin</label>
                                <input id="led_pin" type="number" name="led_pin" value="{LED_PIN}">
                            </div>
                            <div class="field">
                                <label for="led_active_low">LED type</label>
                                <select id="led_active_low" name="led_active_low">
                                    <option value="1" {LED_ACTIVE_LOW_SELECTED}>On-board LED (on with a low signal)</option>
                                    <option value="0" {LED_ACTIVE_HIGH_SELECTED}>External LED (on with a high signal)</option>
                                </select>
                            </div>
                        </div>
                    </div>
                </div>
                <div class="actions">
                    <button type="submit" class="btn primary"><svg class="ic"><use href="#i-save"/></svg>Save changes</button>
                    <span class="hint">Changes are saved on the box and sent to the phones right away.</span>
                </div>
            </form>
        </section>

        <!-- TOOLS -->
        <section id="tab-tools" class="tab-content">
            {DEVICE_SLOTS_MANAGER}

            <div class="panel grid-full {MATCH_CARD_CLASS}" id="match_card_box">
                <div class="panel-head">
                    <h2><svg class="ic"><use href="#i-users"/></svg>1v1 match mode {MATCH_STATUS_BADGE}</h2>
                    {MATCH_HEADER_ACTION}
                </div>
                <div id="match_collapsible_content" class="panel-body {MATCH_CONTENT_CLASS}">
                    {MATCH_ALERT}
                    <form action="/one_vs_one" method="POST" class="stack">
                        <div class="field" style="max-width: 200px;">
                            <label for="match_mins_input">Stake (minutes)</label>
                            <input type="number" id="match_mins_input" name="match_minutes" value="{MATCH_MINUTES}" min="1">
                        </div>
                        <div class="duel">
                            <div>
                                <label class="lbl" for="p1_select">Player 1</label>
                                <select id="p1_select" name="p1_ip">{P1_OPTIONS}</select>
                                <button type="submit" name="winner" value="p1" class="btn block"><svg class="ic"><use href="#i-trophy"/></svg>Player 1 wins</button>
                            </div>
                            <div>
                                <label class="lbl" for="p2_select">Player 2</label>
                                <select id="p2_select" name="p2_ip">{P2_OPTIONS}</select>
                                <button type="submit" name="winner" value="p2" class="btn block"><svg class="ic"><use href="#i-trophy"/></svg>Player 2 wins</button>
                            </div>
                        </div>
                        {MATCH_CONTROLS}
                    </form>
                    <div id="match_qual_result" class="note hidden"></div>
                </div>
            </div>

            <div class="grid">
                <div class="panel">
                    <div class="panel-head"><h2><svg class="ic"><use href="#i-cloud"/></svg>Firmware update</h2></div>
                    <div class="panel-body">
                        <p class="hint">Update the coin box over Wi-Fi. The update is downloaded from the PisoPhone website and checked before it is installed.</p>
                        <a href="/update" class="btn primary block"><svg class="ic"><use href="#i-download"/></svg>Check for an update</a>
                    </div>
                </div>

                <div class="panel">
                    <div class="panel-head"><h2><svg class="ic"><use href="#i-tools"/></svg>Maintenance</h2></div>
                    <div class="panel-body">
                        <div class="field">
                            <span class="lbl">Test the relay (pin {RELAY_PIN})</span>
                            <div class="split">
                                <button type="button" class="btn" onclick="relayTest(1)"><svg class="ic"><use href="#i-bolt"/></svg>Relay on</button>
                                <button type="button" class="btn" onclick="relayTest(0)">Relay off</button>
                            </div>
                        </div>
                        <hr class="sep">
                        <form action="/reset_vault" method="POST" onsubmit="return confirm('Reset the lifetime coin counters? This needs the Vendor password.');" class="field">
                            <label for="reset_pw">Reset revenue counters (vendor only)</label>
                            <div class="inline">
                                <input id="reset_pw" type="password" name="reset_pw" placeholder="Vendor password" autocomplete="off">
                                <button type="submit" class="btn danger">Reset</button>
                            </div>
                        </form>
                        <hr class="sep">
                        <div class="split">
                            <button type="button" class="btn" onclick="rebootBox()"><svg class="ic"><use href="#i-power"/></svg>Restart box</button>
                            <button type="button" class="btn danger" onclick="factoryResetBox()">Factory reset</button>
                        </div>
                    </div>
                </div>
            </div>
        </section>

{SUPER_ADMIN_TAB}
    </main>

{PORTAL_MODALS}

    <script>window.PISO_CFG = { mac: "{MAC_ADDRESS}", secret: "{SHARED_SECRET}" };</script>
    <script src="/assets/portal-modals.js?v={ASSET_V_PORTAL_MODALS}"></script>
    <script src="/assets/superadmin.js?v={ASSET_V_SUPERADMIN}"></script>
</body>
</html>
)HTML";

#endif // WEB_DASHBOARD_TEMPLATE_H
