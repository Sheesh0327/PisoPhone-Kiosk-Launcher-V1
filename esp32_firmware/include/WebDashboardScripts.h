#ifndef WEB_DASHBOARD_SCRIPTS_H
#define WEB_DASHBOARD_SCRIPTS_H

#include <Arduino.h>

#include <Arduino.h>

const char PORTAL_JS_CORE[] PROGMEM = R"JS(
// Apply saved theme immediately to prevent flashing
(function() {
    const savedTheme = localStorage.getItem('kiosk_theme') || 'light';
    document.documentElement.setAttribute('data-theme', savedTheme);
})();

window.toggleTheme = function() {
    const cur = document.documentElement.getAttribute('data-theme') || 'light';
    const next = cur === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('kiosk_theme', next);
    updateThemeButtonText();
};

window.copyToClipboard = function(text, btnElement) {
    if (!text) return;
    const doFeedback = () => {
        if (btnElement) {
            const origText = btnElement.innerText;
            btnElement.innerText = '✓ Copied!';
            setTimeout(() => { btnElement.innerText = origText; }, 1800);
        }
    };
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(doFeedback).catch(() => {
            const ta = document.createElement('textarea');
            ta.value = text;
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
            doFeedback();
        });
    } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        doFeedback();
    }
};

window.toggleInstalledDevicesDropdown = function() {
    const content = document.getElementById('installed-devices-dropdown-content');
    const arrow = document.getElementById('vault-dropdown-arrow');
    if (content) {
        if (content.style.display === 'none' || content.style.display === '') {
            content.style.display = 'grid';
            if (arrow) arrow.textContent = '▲';
        } else {
            content.style.display = 'none';
            if (arrow) arrow.textContent = '▼';
        }
    }
};

function updateThemeButtonText() {
    const btn = document.getElementById('theme_toggle_btn');
    if (btn) {
        const cur = document.documentElement.getAttribute('data-theme') || 'light';
        btn.innerHTML = cur === 'dark' ? '☀️ Light Mode' : '🌙 Dark Mode';
    }
}

window.switchTab = function(tabId) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    const tabBtn = document.querySelector(`[onclick="switchTab('${tabId}')"]`);
    if (tabBtn) tabBtn.classList.add('active');
    const tabEl = document.getElementById(tabId);
    if (tabEl) tabEl.classList.add('active');
    localStorage.setItem('activeTab', tabId);
};

document.addEventListener('DOMContentLoaded', () => {
    let savedTab = localStorage.getItem('activeTab') || 'tab-dashboard';
    const path = window.location.pathname.toLowerCase();
    const search = window.location.search.toLowerCase();
    const hash = window.location.hash.toLowerCase();
    if (path.includes('install') || path.includes('provision') || search.includes('install') || search.includes('provision') || hash.includes('install') || hash.includes('provision')) {
        savedTab = 'tab-install';
    }
    if (document.getElementById(savedTab)) switchTab(savedTab);
    updateThemeButtonText();
});

window.triggerUniversalCoin = function(pulses) {
    fetch('/insert_ucoin?pulses=' + pulses, { method: 'POST', credentials: 'include' })
        .then(res => { if (res.ok) alert('✅ Universal ' + pulses + ' PHP coin drop (' + pulses + ' pulses) simulated successfully!'); else alert('❌ Auth failed or error!'); })
        .catch(err => alert('Error: ' + err));
};

window.fetchDeviceStatus = function() {
    fetch('/api/status')
        .then(res => res.json())
        .then(data => {
            const devices = Array.isArray(data) ? data : (data.devices || []);
            const wifi = data.wifi || null;

            if (wifi) {
                const rssi = typeof wifi.rssi === 'number' ? wifi.rssi : -100;
                const quality = typeof wifi.quality === 'number' ? wifi.quality : 0;
                const status = wifi.status || (quality >= 50 ? 'Good' : 'Weak');
                const isConnected = !!wifi.connected;
                
                let wifiColor = 'var(--status-good)';
                let wifiBg = 'var(--status-good-bg)';
                let wifiBorder = 'var(--status-good-border)';
                let icon = '📶';

                if (!isConnected || quality <= 0) {
                    wifiColor = 'var(--status-critical)';
                    wifiBg = 'var(--status-critical-bg)';
                    wifiBorder = 'var(--status-critical-border)';
                    icon = '❌';
                } else if (quality < 25) {
                    wifiColor = 'var(--status-critical)';
                    wifiBg = 'var(--status-critical-bg)';
                    wifiBorder = 'var(--status-critical-border)';
                    icon = '⚠️';
                } else if (quality < 50) {
                    wifiColor = 'var(--status-warning)';
                    wifiBg = 'var(--status-warning-bg)';
                    wifiBorder = 'var(--status-warning-border)';
                    icon = '📶';
                }

                const pill = document.getElementById('wifi_quality_pill');
                const sigText = document.getElementById('wifi_signal_text');
                const iconEl = document.getElementById('wifi_icon');
                if (pill && sigText) {
                    pill.style.background = wifiBg;
                    pill.style.color = wifiColor;
                    pill.style.borderColor = wifiBorder;
                    if (iconEl) iconEl.textContent = icon;
                    sigText.textContent = isConnected ? ('Wi-Fi: ' + rssi + ' dBm (' + quality + '%)') : 'Wi-Fi: Disconnected';
                }

                const rssiDisp = document.getElementById('wifi_rssi_display');
                const qualPct = document.getElementById('wifi_quality_pct');
                const fill = document.getElementById('wifi_meter_fill');
                const badge = document.getElementById('wifi_status_badge');
                const ssidDisp = document.getElementById('wifi_ssid_display');
                const ipDisp = document.getElementById('wifi_ip_display');

                if (rssiDisp) rssiDisp.textContent = isConnected ? (rssi + ' dBm') : 'Disconnected';
                if (qualPct) {
                    qualPct.textContent = quality + '% ' + status;
                    qualPct.style.color = wifiColor;
                }
                if (fill) {
                    fill.style.width = quality + '%';
                    fill.style.backgroundColor = wifiColor;
                }
                if (badge) {
                    badge.textContent = status;
                    badge.style.background = wifiBg;
                    badge.style.color = wifiColor;
                }
                if (ssidDisp && wifi.ssid) ssidDisp.textContent = wifi.ssid;
                if (ipDisp && wifi.ip) ipDisp.textContent = wifi.ip;
            }

            const container = document.getElementById('live_devices_container');
            if (!container) return;
            
            window.latestDevicesList = devices;
            const unassigned = data.unassigned_devices || [];
            window.unassignedDevices = unassigned;

            let availableSlotsCount = 0;
            let totalActiveSlotsCount = 0;

            devices.forEach(dev => {
                const isExp = (!dev.active);
                if (!isExp) {
                    totalActiveSlotsCount++;
                    if (!dev.isBound) {
                        availableSlotsCount++;
                    }
                }
            });

            const availableBadge = document.getElementById('available_slots_badge');
            if (availableBadge) {
                availableBadge.innerHTML = '<b>' + availableSlotsCount + '</b> Open / ' + totalActiveSlotsCount + ' Active Seats';
                if (availableSlotsCount > 0) {
                    availableBadge.style.background = 'rgba(16, 185, 129, 0.15)';
                    availableBadge.style.color = 'var(--primary)';
                } else {
                    availableBadge.style.background = 'rgba(239, 68, 68, 0.15)';
                    availableBadge.style.color = '#ef4444';
                }
            }

            if (devices.length === 0 && unassigned.length === 0) {
                container.innerHTML = '<div style="padding: 24px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">No PisoPhone devices registered.</div>';
                return;
            }
            let html = '';
            devices.forEach((dev) => {
                const name = (dev.name && dev.name !== dev.id && !dev.name.startsWith('Terminal') && (!dev.id || !dev.name.includes(dev.id))) ? dev.name : ('PisoPhone ' + dev.slotNum);
                const battery = (typeof dev.battery === 'number' && dev.battery >= 0) ? dev.battery : 100;
                const isCharging = !!dev.charging;
                const isExp = (!dev.active);
                
                let batteryStatusClass = 'status-good';
                if (battery <= 15) {
                    batteryStatusClass = 'status-critical';
                } else if (battery <= 30) {
                    batteryStatusClass = 'status-warning';
                }

                if (!dev.isBound && isExp) {
                    return;
                }

                if (!dev.isBound) {
                    html += '<div class="device-row empty">' +
                                '<div class="device-row-identity">' +
                                    '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                    '<div class="device-row-info">' +
                                        '<span class="device-row-name">Empty Slot #' + dev.slotNum + '</span>' +
                                        '<span class="device-row-sub">Seat open & ready for setup</span>' +
                                    '</div>' +
                                '</div>' +
                                '<div style="color: var(--text-muted); font-size: 13px; font-style: italic;">' +
                                    'No terminal bound' +
                                '</div>' +
                                '<div class="device-row-actions">' +
                                    '<button type="button" class="btn btn-primary btn-sm" onclick="occupySlot(' + dev.slotNum + ')" style="padding: 8px 16px; font-weight: 700;">' +
                                        '⚡ Occupy slot' +
                                    '</button>' +
                                '</div>' +
                            '</div>';
                } else if (dev.online) {
                    const mins = Math.floor(dev.time / 60);
                    const secs = dev.time % 60;
                    const timeStr = mins + 'm ' + secs + 's';
                    const active = dev.time > 0;
                    
                    let badgeHtml = active 
                        ? '<span class="device-badge active">ACTIVE</span>'
                        : '<span class="device-badge standby">STANDBY</span>';

                    if (isExp) {
                        badgeHtml += ' <span class="device-badge inactive" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3);">INACTIVE SLOT</span>';
                    }
                        
                    const batteryIcon = isCharging ? '⚡' : '🔋';
                    const batteryText = (isCharging ? '⚡ ' : '') + battery + '%';

                    html += '<div class="device-row" ' + (isExp ? 'style="opacity: 0.8;"' : '') + '>' +
                                '<div class="device-row-identity">' +
                                    '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                    '<div class="device-row-info">' +
                                        '<span class="device-row-name">' + name + '</span>' +
                                        '<span class="device-row-sub">' + dev.ip + (dev.deviceId ? ' • ' + dev.deviceId : '') + '</span>' +
                                    '</div>' +
                                '</div>' +
                                '<div class="device-row-metrics">' +
                                    '<div class="device-row-timer">' +
                                        '<span>⏱️ ' + timeStr + '</span>' +
                                        badgeHtml +
                                    '</div>' +
                                    '<div class="device-row-battery ' + batteryStatusClass + '">' +
                                        '<span style="font-size: 12px; font-weight: 700;">' + batteryIcon + ' ' + batteryText + '</span>' +
                                        '<div class="battery-bar-bg" style="width: 50px; height: 6px; display: inline-block; margin-left: 4px;">' +
                                            '<div class="battery-bar-fill" style="width: ' + battery + '%;"></div>' +
                                        '</div>' +
                                    '</div>' +
                                '</div>' +
                                '<div class="device-row-actions">' +
                                    '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                        '🔓 Unpair' +
                                    '</button>' +
                                '</div>' +
                            '</div>';
                } else {
                    html += '<div class="device-row offline">' +
                                '<div class="device-row-identity">' +
                                    '<span class="device-slot-badge">Slot #' + dev.slotNum + '</span>' +
                                    '<div class="device-row-info">' +
                                        '<span class="device-row-name">' + name + '</span>' +
                                        '<span class="device-row-sub">' + dev.ip + '</span>' +
                                    '</div>' +
                                '</div>' +
                                '<div class="device-row-metrics">' +
                                    '<span class="device-badge offline">OFFLINE / DISCONNECTED</span>' +
                                    (isExp ? ' <span class="device-badge inactive" style="background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239, 68, 68, 0.3);">INACTIVE</span>' : '') +
                                '</div>' +
                                '<div class="device-row-actions">' +
                                    '<button type="button" class="btn btn-outline btn-sm" style="border-color: var(--danger); color: var(--danger);" onclick="unpairSlot(' + dev.slotNum + ')">' +
                                        '🔓 Unpair' +
                                    '</button>' +
                                '</div>' +
                            '</div>';
                }
            });

            if (unassigned.length > 0) {
                html += '<div style="grid-column: 1/-1; margin-top: 18px; margin-bottom: 6px;">' +
                        '<div style="font-size: 12px; font-weight: 800; color: #f59e0b; display: flex; align-items: center; justify-content: space-between; padding: 10px 14px; background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.3); border-radius: var(--radius-md);">' +
                            '<div style="display: flex; align-items: center; gap: 8px;">' +
                                '<span>🟡 PENDING CONNECTION REQUESTS</span>' +
                                '<span style="font-size: 10px; background: #f59e0b; color: #000000; font-weight: 800; padding: 2px 8px; border-radius: 10px;">' + unassigned.length + ' Terminal(s)</span>' +
                            '</div>' +
                            '<span style="font-size: 11px; color: var(--text-muted); font-weight: 600;">Unassigned devices requesting pairing</span>' +
                        '</div>' +
                        '</div>';

                unassigned.forEach((uDev) => {
                    const uName = uDev.name || 'PisoPhone Terminal';
                    const uBat = (typeof uDev.battery === 'number' && uDev.battery >= 0) ? uDev.battery : 100;
                    const uChg = !!uDev.charging;
                    const uBatText = (uChg ? '⚡ ' : '🔋 ') + uBat + '%';

                    html += '<div class="device-row" style="border-left: 4px solid #f59e0b; background: rgba(245, 158, 11, 0.04);">' +
                                '<div class="device-row-identity">' +
                                    '<span class="device-slot-badge" style="background: rgba(245, 158, 11, 0.2); color: #f59e0b; border: 1px solid rgba(245, 158, 11, 0.4);">🟡 UNASSIGNED</span>' +
                                    '<div class="device-row-info">' +
                                        '<span class="device-row-name">' + uName + '</span>' +
                                        '<span class="device-row-sub">IP: <b>' + uDev.ip + '</b> • HW: <b>' + uDev.id + '</b></span>' +
                                    '</div>' +
                                '</div>' +
                                '<div class="device-row-metrics">' +
                                    '<div class="device-row-battery status-good">' +
                                        '<span style="font-size: 12px; font-weight: 700;">' + uBatText + '</span>' +
                                    '</div>' +
                                '</div>' +
                                '<div class="device-row-actions">' +
                                    '<button type="button" class="btn btn-primary btn-sm" onclick="showSelectSlotModalForDevice(\'' + uDev.id + '\', \'' + uDev.ip + '\', \'' + uName.replace(/'/g, "\\'") + '\')" style="padding: 8px 14px; font-weight: 700; background: linear-gradient(135deg, #f59e0b 0%, #d97706 100%); border: none;">' +
                                        '⚡ Confirm Connection' +
                                    '</button>' +
                                '</div>' +
                            '</div>';
                });
            }

            if (!html) {
                container.innerHTML = '<div style="padding: 24px; text-align: center; color: var(--text-muted); grid-column: 1/-1;">No active terminals connected. Inactive slots can be monitored under Hardware Slot Seats above.</div>';
            } else {
                container.innerHTML = html;
            }
        })
        .catch(err => console.log('Status polling error', err));
};
setInterval(fetchDeviceStatus, 3000);
document.addEventListener("DOMContentLoaded", fetchDeviceStatus);

window.checkMatchQualification = function() {
    const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
    const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
    const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
    const resDiv = document.getElementById('match_qual_result');
    if (!p1 || !p2) { alert('Select both players.'); return; }
    if (p1 === p2) { resDiv.style.display = 'block'; resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b'; resDiv.innerHTML = '❌ <b>Error:</b> Players cannot be the same device.'; return; }
    resDiv.style.display = 'block'; resDiv.style.background = '#f8fafc'; resDiv.style.border = '1px solid #e2e8f0'; resDiv.style.color = '#334155'; resDiv.innerHTML = '⏳ Verifying balances...';
    fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                if (data.qualified) {
                    resDiv.style.background = '#f0fdf4'; resDiv.style.border = '1px solid #bbf7d0'; resDiv.style.color = '#166534';
                    resDiv.innerHTML = '✅ <b>BOTH QUALIFIED FOR ' + data.stake_minutes + 'm MATCH!</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted;
                } else {
                    resDiv.style.background = '#fef2f2'; resDiv.style.border = '1px solid #fecaca'; resDiv.style.color = '#991b1b';
                    resDiv.innerHTML = '❌ <b>NOT QUALIFIED</b><br>• P1: ' + data.p1_formatted + '<br>• P2: ' + data.p2_formatted + '<br><i>' + data.message + '</i>';
                }
            } else { resDiv.innerHTML = '❌ Error checking qualification.'; }
        }).catch(err => resDiv.innerHTML = '❌ Network error.');
};
)JS";

#include <Arduino.h>

const char PORTAL_JS_MODALS[] PROGMEM = R"JS(
const ESP32_MAC = "{MAC_ADDRESS}";
const ESP32_SECRET = "{SHARED_SECRET}";
const ESP32_HOST = window.location.hostname;
let activeSlotNum = 1;
let localApkBytes = null;
window.unassignedDevices = [];

window.closeUnassignedPairModal = function() {
    const modal = document.getElementById('unassigned_pair_modal');
    if (modal) modal.style.display = 'none';
};

window.openInstallerForActiveSlot = function() {
    const s = activeSlotNum || 1;
    const targetUrl = 'https://pisophone.pages.dev/?mac=' + encodeURIComponent(ESP32_MAC) + 
                      '&ip=' + encodeURIComponent(ESP32_HOST) + 
                      '&slot=' + encodeURIComponent(s) + 
                      '&secret=' + encodeURIComponent(ESP32_SECRET) +
                      '&name=' + encodeURIComponent('PisoPhone ' + s);
    window.open(targetUrl, '_blank');
    closeUnassignedPairModal();
};

window.confirmConnection = function(devId, devIp, slotNum, devName) {
    const slot = slotNum || activeSlotNum || 1;
    const name = (devName && devName !== devId && !devName.startsWith('Terminal') && !devName.includes(devId)) ? devName : ('PisoPhone ' + slot);
    fetch('/api/slots/pair?slot=' + slot + '&id=' + encodeURIComponent(devId) + '&ip=' + encodeURIComponent(devIp) + '&name=' + encodeURIComponent(name), { method: 'POST' })
        .then(res => {
            if (!res.ok) {
                return res.text().then(text => { throw new Error('HTTP ' + res.status + ': ' + text); });
            }
            return res.json();
        })
        .then(data => {
            if (data.success) {
                alert('✅ Successfully paired terminal (' + devIp + ') to Slot #' + slot + '!');
                closeUnassignedPairModal();
                if (typeof fetchDeviceStatus === 'function') fetchDeviceStatus();
                setTimeout(() => window.location.reload(), 1000);
            } else {
                alert('❌ Failed to pair: ' + (data.error || 'Unknown error'));
            }
        })
        .catch(err => alert('❌ Error pairing device: ' + (err.message || err)));
};

window.showSelectSlotModalForDevice = function(devId, devIp, devName) {
    const modal = document.getElementById('unassigned_pair_modal');
    const title = document.getElementById('unassigned_modal_title');
    const body = document.getElementById('unassigned_modal_body');
    if (!modal || !body) return;

    if (title) title.innerHTML = '⚡ Assign Device to Slot Seat';

    let html = '<div style="font-size: 13px; color: var(--text-muted); margin-bottom: 8px;">' +
               'Select an available slot seat to pair terminal <b>' + (devName || devId) + '</b> (' + devIp + '):' +
               '</div>' +
               '<div style="display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px;">';

    const devicesList = window.latestDevicesList || [];
    const maxS = devicesList.length > 0 ? devicesList.length : 1;

    for (let s = 1; s <= maxS; s++) {
        const slotInfo = devicesList.find(d => d.slotNum === s);
        const isBound = slotInfo ? slotInfo.isBound : false;
        const isExp = slotInfo ? (!slotInfo.active) : false;

        if (isBound) {
            html += '<button disabled class="btn btn-outline" style="padding: 10px; font-size: 13px; font-weight: 700; opacity: 0.4; cursor: not-allowed; background: rgba(255,255,255,0.03); border-color: var(--border); color: var(--text-muted); pointer-events: none;">' +
                    'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: var(--text-muted);">(Occupied)</span>' +
                    '</button>';
        } else if (isExp) {
            html += '<button disabled class="btn btn-outline" style="padding: 10px; font-size: 13px; font-weight: 700; opacity: 0.45; cursor: not-allowed; background: rgba(239, 68, 68, 0.05); border-color: rgba(239, 68, 68, 0.2); color: #ef4444; pointer-events: none;">' +
                    'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: #ef4444;">(Inactive)</span>' +
                    '</button>';
        } else {
            html += '<button type="button" class="btn btn-primary" style="padding: 10px; font-size: 13px; font-weight: 700; background: linear-gradient(135deg, var(--primary) 0%, var(--primary-hover) 100%); border: none; color: #ffffff;" onclick="confirmConnection(\'' + devId + '\', \'' + devIp + '\', ' + s + ', \'' + (devName || '').replace(/'/g, "\\'") + '\')">' +
                    'Slot #' + s + '<span style="font-size: 10px; font-weight: 400; display: block; color: rgba(255,255,255,0.8);">(Available)</span>' +
                    '</button>';
        }
    }
    html += '</div>';

    body.innerHTML = html;
    modal.style.display = 'flex';
};

window.copyMacToClipboard = function(mac) {
    const val = (mac && mac !== '{MAC_ADDRESS}') ? mac : ESP32_MAC;
    if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(val).then(() => {
            alert('Copied MAC Address: ' + val);
        }).catch(() => {
            prompt('Copy MAC address:', val);
        });
    } else {
        prompt('Copy MAC address:', val);
    }
};

window.openInstaller = function() {
    const targetUrl = 'https://pisophone.pages.dev/?mac=' + encodeURIComponent(ESP32_MAC) + 
                      '&ip=' + encodeURIComponent(ESP32_HOST) + 
                      '&secret=' + encodeURIComponent(ESP32_SECRET);
    window.open(targetUrl, '_blank');
};

window.occupySlot = function(slot) {
    activeSlotNum = slot || 1;
    const devicesList = window.latestDevicesList || [];
    const slotInfo = devicesList.find(d => d.slotNum === activeSlotNum);
    if (slotInfo && (!slotInfo.active)) {
        alert('⛔ Cannot occupy Slot #' + activeSlotNum + ': This slot seat is not licensed.\n\nPlease upgrade slot capacity to activate additional slots.');
        return;
    }

    const modal = document.getElementById('unassigned_pair_modal');
    const title = document.getElementById('unassigned_modal_title');
    const body = document.getElementById('unassigned_modal_body');
    if (!modal || !body) {
        openInstallerForActiveSlot();
        return;
    }

    if (title) title.innerHTML = '⚡ Occupy Slot #' + activeSlotNum;

    const unassigned = window.unassignedDevices || [];
    if (unassigned.length > 0) {
        let html = '<div style="font-size: 13px; color: var(--text-muted); margin-bottom: 8px;">' +
                   'Unassigned terminals requesting connection found on network! Click to pair to <b>Slot #' + activeSlotNum + '</b>:' +
                   '</div>';

        unassigned.forEach(uDev => {
            const uName = uDev.name || 'PisoPhone Terminal';
            html += '<div style="background: var(--input-bg); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 12px; display: flex; align-items: center; justify-content: space-between; gap: 10px;">' +
                    '<div>' +
                        '<div style="font-size: 13px; font-weight: 700; color: var(--text-main);">' + uName + '</div>' +
                        '<div style="font-size: 11px; color: var(--text-muted); font-family: monospace;">IP: ' + uDev.ip + ' • HW: ' + uDev.id + '</div>' +
                    '</div>' +
                    '<button type="button" class="btn btn-primary btn-sm" onclick="confirmConnection(\'' + uDev.id + '\', \'' + uDev.ip + '\', ' + activeSlotNum + ', \'' + uName.replace(/'/g, "\\'") + '\')" style="padding: 6px 12px; font-weight: 700; font-size: 12px;">' +
                        '⚡ Pair to Slot #' + activeSlotNum +
                    '</button>' +
                    '</div>';
        });

        body.innerHTML = html;
    } else {
        body.innerHTML = '<div style="background: var(--input-bg); border: 1px dashed var(--border); border-radius: var(--radius-md); padding: 18px; text-align: center; color: var(--text-muted); font-size: 12px;">' +
                         '<div style="font-size: 24px; margin-bottom: 6px;">📱</div>' +
                         '<div style="font-weight: 700; color: var(--text-main);">No Unassigned Terminal Requesting Connection</div>' +
                         '<div style="margin-top: 6px; line-height: 1.4; color: var(--text-muted);">Make sure your Android terminal is powered on and connected to this ESP32 Wi-Fi network.<br>Alternatively, click below to open the Web Installer to sideload the PisoPhone Kiosk App.</div>' +
                         '</div>';
    }

    modal.style.display = 'flex';
};

window.showPairingQrModal = function(slotNum) {
    activeSlotNum = slotNum || 1;
    const modal = document.getElementById('qr_pair_modal');
    if (!modal) return;
    const slotTitle = document.getElementById('qr_slot_title');
    if (slotTitle) slotTitle.textContent = activeSlotNum;
    const ipElem = document.getElementById('qr_modal_ip');
    const hostIp = window.location.hostname || "192.168.4.1";
    if (ipElem) ipElem.textContent = hostIp;

    const payloadObj = {
        pisophone_pair: 1,
        ip: hostIp,
        port: 80,
        ws_port: 81,
        mac: ESP32_MAC,
        secret: ESP32_SECRET,
        slot: activeSlotNum,
        name: "Slot #" + activeSlotNum
    };
    const payloadStr = JSON.stringify(payloadObj);

    modal.style.display = 'flex';
    try {
        if (typeof QRious !== 'undefined') {
            new QRious({
                element: document.getElementById('qr_canvas'),
                value: payloadStr,
                size: 260,
                level: 'M'
            });
        } else {
            const fb = document.getElementById('qr_fallback_text');
            if (fb) {
                fb.textContent = payloadStr;
                fb.style.display = 'block';
            }
        }
    } catch (e) {
        console.error("QR render error:", e);
    }
};

window.closePairingQrModal = function() {
    const modal = document.getElementById('qr_pair_modal');
    if (modal) modal.style.display = 'none';
};

window.openTokenModal = function() {
    document.getElementById('token_modal').style.display = 'flex';
    document.getElementById('token_input').value = '';
    document.getElementById('token_error').style.display = 'none';
};
window.closeTokenModal = function() {
    document.getElementById('token_modal').style.display = 'none';
};

window.submitSlotToken = function() {
    const token = document.getElementById('token_input').value.trim();
    const errDiv = document.getElementById('token_error');
    if (!token) {
        errDiv.textContent = 'Please paste a token.';
        errDiv.style.display = 'block';
        return;
    }
    errDiv.style.display = 'none';
    fetch('/api/slots/apply_token?token=' + encodeURIComponent(token), { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                alert('🎉 Capacity upgraded to ' + data.maxSlots + ' seats!');
                location.reload();
            } else {
                errDiv.textContent = data.error || 'Invalid token.';
                errDiv.style.display = 'block';
            }
        })
        .catch(err => {
            errDiv.textContent = 'Network error: ' + err.message;
            errDiv.style.display = 'block';
        });
};

window.unpairSlot = function(slot) {
    if (!confirm('Unpair Slot #' + slot + '? This will free the seat slot on this ESP32. The coin slot will no longer accept coins for this device until paired again.')) return;
    fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                location.reload();
            } else {
                alert('Failed to unpair slot: ' + (data.error || 'Unknown error'));
            }
        })
        .catch(err => {
            alert('Network error unpairing slot: ' + err.message);
        });
};

let targetModalSlot = 1;

window.openSlotActivationModal = function(sNum, name, ip, devId, expInfo, expStatus, isBound) {
    targetModalSlot = sNum;
    const modal = document.getElementById('slot_activation_modal');
    if (!modal) return;

    const titleEl = document.getElementById('modal_slot_title');
    const subEl = document.getElementById('modal_slot_sub');
    const expTextEl = document.getElementById('modal_slot_expiry_text');
    const badgeEl = document.getElementById('modal_slot_status_badge');
    const unpairCont = document.getElementById('modal_unpair_container');

    if (titleEl) titleEl.textContent = 'Slot #' + sNum + ' Seat';
    if (subEl) subEl.textContent = name || (isBound ? ('PisoPhone Slot #' + sNum) : 'Unassigned Slot Seat');
    if (expTextEl) expTextEl.textContent = isBound ? (ip ? ('IP: ' + ip) : 'Permanent (Active)') : 'Available Seat (Permanent)';

    if (badgeEl) {
        if (isBound) {
            badgeEl.innerHTML = '<span style="font-size: 10px; font-weight: 800; background: rgba(16, 185, 129, 0.15); color: var(--primary); border: 1px solid rgba(16, 185, 129, 0.3); padding: 3px 8px; border-radius: 6px;">🟢 PAIRED & ACTIVE</span>';
        } else {
            badgeEl.innerHTML = '<span style="font-size: 10px; font-weight: 800; background: rgba(59, 130, 246, 0.15); color: #3b82f6; border: 1px solid rgba(59, 130, 246, 0.3); padding: 3px 8px; border-radius: 6px;">🔵 VACANT</span>';
        }
    }

    if (unpairCont) {
        unpairCont.style.display = isBound ? 'block' : 'none';
    }

    modal.style.display = 'flex';
};

window.closeSlotActivationModal = function() {
    const modal = document.getElementById('slot_activation_modal');
    if (modal) modal.style.display = 'none';
};

window.submitModalFlash = function() {
    closeSlotActivationModal();
    window.open('https://pisophone.pages.dev/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + targetModalSlot, '_blank');
};

window.submitModalUnpair = function() {
    closeSlotActivationModal();
    unpairSlot(targetModalSlot);
};

window.openProvisionModal = function(slot) {
    activeSlotNum = slot;
    document.getElementById('prov_slot_num').textContent = slot;
    document.getElementById('provision_modal').style.display = 'flex';
};
window.closeProvisionModal = function() {
    document.getElementById('provision_modal').style.display = 'none';
};
window.launchHttpsFlasher = function() {
    window.open('https://pisophone.pages.dev/?mac=' + ESP32_MAC + '&ip=' + ESP32_HOST + '&slot=' + activeSlotNum, '_blank');
    closeProvisionModal();
};

window.openDeprovisionModal = function(slot, devId) {
    activeSlotNum = slot;
    document.getElementById('deprov_slot_num').textContent = slot;
    document.getElementById('deprov_log').textContent = 'Connect phone via USB and click Confirm Deprovision.\n';
    document.getElementById('deprovision_modal').style.display = 'flex';
};
window.closeDeprovisionModal = function() {
    document.getElementById('deprovision_modal').style.display = 'none';
};

window.executeDeprovisionFlow = async function() {
    const btn = document.getElementById('start_deprov_btn');
    const log = document.getElementById('deprov_log');
    const pin = document.getElementById('deprov_pin_input').value.trim() || '1234';
    btn.disabled = true;

    const appendLog = (msg) => {
        log.textContent += msg + '\n';
        log.scrollTop = log.scrollHeight;
    };

    try {
        if (!navigator.usb) throw new Error('WebUSB not supported in this browser.');
        if (!window.webADB) throw new Error('WebADB library not loaded.');

        appendLog('[1/3] Connecting USB device...');
        await window.webADB.connect((t) => appendLog(t));

        appendLog('[2/3] Deprovisioning Device Owner and removing kiosk package...');
        await window.webADB.deprovisionDevice(pin, (t) => appendLog(t));

        appendLog('[3/3] Freeing Slot #' + activeSlotNum + ' on ESP32...');
        await fetch('/api/slots/unpair?slot=' + activeSlotNum, { method: 'POST' });

        appendLog('\n✅ DEPROVISION COMPLETE! Phone restored, seat slot is open.');
        setTimeout(() => location.reload(), 1500);
    } catch (err) {
        appendLog('\n[ERROR] ' + (err.message || err));
        btn.disabled = false;
    }
};

window.checkQuickAdjustInactive = function() {
    const sel = document.getElementById('quick_adjust_target');
    const warn = document.getElementById('quick_adjust_warn');
    if (!sel || !warn) return false;
    let hasInactive = false;
    if (sel.value === 'ALL') {
        const inactiveOpts = sel.querySelectorAll('option[data-inactive="true"]');
        hasInactive = (inactiveOpts.length > 0);
        if (hasInactive) {
            warn.innerHTML = '🚫 <b>Broadcast Notice:</b> ' + inactiveOpts.length + ' registered device(s) are INACTIVE. Adjusting time is blocked until activations are allocated.';
            warn.style.display = 'block';
        } else {
            warn.style.display = 'none';
        }
    } else {
        const opt = sel.options[sel.selectedIndex];
        hasInactive = (opt && opt.getAttribute('data-inactive') === 'true');
        if (hasInactive) {
            const label = opt ? opt.text : 'Selected Device';
            warn.innerHTML = '🚫 <b>Device Inactive:</b> ' + label + ' is INACTIVE. Manual time adjustment is blocked until activations are allocated.';
            warn.style.display = 'block';
        } else {
            warn.style.display = 'none';
        }
    }
    return hasInactive;
};

window.validateQuickAdjust = function(e) {
    if (window.checkQuickAdjustInactive()) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
        }
        const sel = document.getElementById('quick_adjust_target');
        const isAll = (sel && sel.value === 'ALL');
        const msg = isAll 
            ? "❌ Action Blocked: One or more devices in broadcast are INACTIVE or UNLICENSED!\n\nPlease upgrade slot capacity and pair devices before adjusting time."
            : "❌ Action Blocked: The selected device is INACTIVE or UNLICENSED!\n\nPlease pair an active licensed slot before adjusting time.";
        alert(msg);
        return false;
    }
    return true;
};

document.addEventListener('DOMContentLoaded', function() {
    if (window.checkQuickAdjustInactive) window.checkQuickAdjustInactive();
    
    const urlParams = new URLSearchParams(window.location.search);
    const action = urlParams.get('action');
    const slot = urlParams.get('slot');
    if (action === 'unpair' && slot) {
        fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
            .then(res => res.json())
            .then(data => {
                if (data.success) {
                    window.history.replaceState({}, document.title, window.location.pathname);
                    location.reload();
                } else {
                    alert('Unpair auto-action failed: ' + data.error);
                }
            })
            .catch(err => alert('Unpair auto-action network error: ' + err.message));
    }
});
)JS";

#endif // WEB_DASHBOARD_SCRIPTS_H
