#ifndef WEB_DASHBOARD_SCRIPTS_MODALS_H
#define WEB_DASHBOARD_SCRIPTS_MODALS_H

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
                      '&slot=' + encodeURIComponent(s) + 
                      '&secret=' + encodeURIComponent(ESP32_SECRET) +
                      '&name=' + encodeURIComponent('PisoPhone ' + s);
    window.open(targetUrl, '_self');
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
                      '&secret=' + encodeURIComponent(ESP32_SECRET);
    window.open(targetUrl, '_self');
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
    const hostIp = window.location.hostname || "kioskmanager.local";
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
    const url = '/api/slots/unpair?slot=' + slot;
    fetch(url, { method: 'POST' })
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
    window.open('https://pisophone.pages.dev/?mac=' + ESP32_MAC + '&slot=' + targetModalSlot, '_self');
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
    window.open('https://pisophone.pages.dev/?mac=' + ESP32_MAC + '&slot=' + activeSlotNum, '_self');
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
    if (sel.value === 'ALL') {
        const allOpts = sel.querySelectorAll('option:not([value="ALL"])');
        const activeOpts = sel.querySelectorAll('option:not([value="ALL"]):not([data-inactive="true"])');
        const inactiveOpts = sel.querySelectorAll('option[data-inactive="true"]');
        if (allOpts.length === 0 || activeOpts.length === 0) {
            warn.innerHTML = '🚫 <b>No Eligible Devices:</b> No active devices registered to adjust.';
            warn.style.display = 'block';
            return true;
        } else if (inactiveOpts.length > 0) {
            warn.innerHTML = '⚠️ <b>Notice:</b> ' + inactiveOpts.length + ' inactive device(s) will be skipped during broadcast.';
            warn.style.display = 'block';
            return false;
        } else {
            warn.style.display = 'none';
            return false;
        }
    } else {
        const opt = sel.options[sel.selectedIndex];
        const isInactive = (opt && opt.getAttribute('data-inactive') === 'true');
        if (isInactive) {
            const label = opt ? opt.text : 'Selected Device';
            warn.innerHTML = '🚫 <b>Device Inactive:</b> ' + label + ' is INACTIVE. Manual time adjustment is blocked until activations are allocated.';
            warn.style.display = 'block';
            return true;
        } else {
            warn.style.display = 'none';
            return false;
        }
    }
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
            ? "❌ Action Blocked: No active devices available for broadcast adjustment."
            : "❌ Action Blocked: The selected device is INACTIVE or UNLICENSED!\n\nPlease pair an active licensed slot before adjusting time.";
        alert(msg);
        return false;
    }
    const minInput = document.querySelector('#quick_adjust_form input[name="add_minutes"]');
    const val = minInput ? parseInt(minInput.value, 10) : 0;
    if (!val || val <= 0) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
        }
        alert("❌ Please enter a valid positive number of minutes.");
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

#endif // WEB_DASHBOARD_SCRIPTS_MODALS_H
