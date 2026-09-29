#ifndef WEB_DASHBOARD_SCRIPTS_CORE_H
#define WEB_DASHBOARD_SCRIPTS_CORE_H

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

#endif // WEB_DASHBOARD_SCRIPTS_CORE_H
