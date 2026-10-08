// Per-box values come from the page itself (window.PISO_CFG), so this file is the same for every box and can be cached.
const ESP32_MAC = (window.PISO_CFG && window.PISO_CFG.mac) || "";
const ESP32_SECRET = (window.PISO_CFG && window.PISO_CFG.secret) || "";
let activeSlotNum = 1;
window.unassignedDevices = [];

function showModal(id) {
    const m = document.getElementById(id);
    if (m) m.style.display = 'flex';
}
function hideModal(id) {
    const m = document.getElementById(id);
    if (m) m.style.display = 'none';
}
// Click on the dark area or press Escape to close any dialog.
document.addEventListener('click', function(e) {
    if (e.target.classList && e.target.classList.contains('modal-overlay')) e.target.style.display = 'none';
});
document.addEventListener('keydown', function(e) {
    if (e.key === 'Escape') document.querySelectorAll('.modal-overlay').forEach(m => { m.style.display = 'none'; });
});

function setupUrl(slot) {
    return 'https://pisophone.pages.dev/?mac=' + encodeURIComponent(ESP32_MAC) +
           '&slot=' + encodeURIComponent(slot) +
           '&secret=' + encodeURIComponent(ESP32_SECRET) +
           '&name=' + encodeURIComponent('PisoPhone ' + slot);
}

window.closeUnassignedPairModal = function() { hideModal('unassigned_pair_modal'); };

window.openInstallerForActiveSlot = function() {
    window.open(setupUrl(activeSlotNum || 1), '_self');
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
                notify('Phone paired to slot ' + slot + '.');
                closeUnassignedPairModal();
                if (typeof fetchDeviceStatus === 'function') fetchDeviceStatus();
                setTimeout(() => window.location.reload(), 1200);
            } else {
                notify('Could not pair: ' + (data.error || 'unknown error'), 'bad');
            }
        })
        .catch(err => notify('Could not pair: ' + (err.message || err), 'bad'));
};

function pairButtonAttrs(id, ip, name, slot) {
    return 'data-id="' + escHtml(id) + '" data-ip="' + escHtml(ip) + '" data-name="' + escHtml(name) + '" data-slot="' + slot + '" onclick="pairButton(this)"';
}
window.pairButton = function(btn) {
    const d = btn.dataset;
    closeSlotActivationModal();
    confirmConnection(d.id, d.ip, parseInt(d.slot, 10), d.name);
};

window.showSelectSlotModalForDevice = function(devId, devIp, devName) {
    const body = document.getElementById('unassigned_modal_body');
    const title = document.getElementById('unassigned_modal_title');
    if (!body) return;
    if (title) title.textContent = 'Choose a slot';

    let html = '<p class="hint">Pick a free slot for <b>' + escHtml(devName || devId) + '</b> (' + escHtml(devIp) + ').</p><div class="slot-pick">';
    const devicesList = window.latestDevicesList || [];
    const maxS = devicesList.length > 0 ? devicesList.length : 1;
    for (let s = 1; s <= maxS; s++) {
        const slotInfo = devicesList.find(d => d.slotNum === s);
        const isBound = slotInfo ? slotInfo.isBound : false;
        const isExp = slotInfo ? (!slotInfo.active) : false;
        if (isBound || isExp) {
            html += '<button type="button" class="btn" disabled>Slot ' + s + ' · ' + (isBound ? 'in use' : 'locked') + '</button>';
        } else {
            html += '<button type="button" class="btn primary" ' + pairButtonAttrs(devId, devIp, devName || '', s) + '>Slot ' + s + '</button>';
        }
    }
    body.innerHTML = html + '</div>';
    showModal('unassigned_pair_modal');
};

window.occupySlot = function(slot) {
    activeSlotNum = slot || 1;
    const devicesList = window.latestDevicesList || [];
    const slotInfo = devicesList.find(d => d.slotNum === activeSlotNum);
    if (slotInfo && !slotInfo.active) {
        notify('Slot ' + activeSlotNum + ' is not licensed yet. Add slots under Tools first.', 'bad');
        return;
    }
    const body = document.getElementById('unassigned_modal_body');
    const title = document.getElementById('unassigned_modal_title');
    if (!body) {
        openInstallerForActiveSlot();
        return;
    }
    if (title) title.textContent = 'Set up slot ' + activeSlotNum;

    const unassigned = window.unassignedDevices || [];
    if (unassigned.length > 0) {
        let html = '<p class="hint">These phones are asking to connect. Choose one for slot ' + activeSlotNum + '.</p>';
        unassigned.forEach(u => {
            const uName = u.name || 'PisoPhone';
            html += '<div class="choice"><span><span class="choice-t">' + escHtml(uName) + '</span><br><span class="choice-s mono">' + escHtml(u.ip) + ' · ' + escHtml(u.id) + '</span></span>' +
                    '<button type="button" class="btn primary sm" ' + pairButtonAttrs(u.id, u.ip, uName, activeSlotNum) + '>Pair</button></div>';
        });
        body.innerHTML = html;
    } else {
        body.innerHTML = '<div class="empty-box"><b>No phone is waiting to connect</b>' +
            'Turn the phone on and join this Wi-Fi network, or open the setup page to install PisoPhone on a new phone.</div>';
    }
    showModal('unassigned_pair_modal');
};

window.openTokenModal = function() {
    showModal('token_modal');
    document.getElementById('token_input').value = '';
    document.getElementById('token_error').classList.add('hidden');
};
window.closeTokenModal = function() { hideModal('token_modal'); };

window.submitSlotToken = function() {
    const token = document.getElementById('token_input').value.trim();
    const errDiv = document.getElementById('token_error');
    const fail = msg => { errDiv.innerHTML = ic('alert') + '<span class="grow"></span>'; errDiv.lastChild.textContent = msg; errDiv.classList.remove('hidden'); };
    if (!token) return fail('Paste the license key first.');
    errDiv.classList.add('hidden');
    fetch('/api/slots/apply_token?token=' + encodeURIComponent(token), { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                notify('The box now has ' + data.maxSlots + ' phone slots.');
                setTimeout(() => location.reload(), 1200);
            } else {
                fail(data.error || 'That key was not accepted.');
            }
        })
        .catch(err => fail('Could not reach the box: ' + err.message));
};

window.switchToOwnKey = function() {
    if (!confirm("Switch this box to its own key?\n\nEvery phone paired with this box stops working until it is set up again with the box's new key.")) return;
    fetch('/api/security/switch_key', { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                notify('Done. Set up each phone again from the Tools tab.');
                setTimeout(() => location.reload(), 1500);
            } else {
                notify('Could not switch the key.', 'bad');
            }
        })
        .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
};

window.unpairSlot = function(slot) {
    if (!confirm('Unpair slot ' + slot + '? The coin slot will not accept coins for that phone until it is paired again.')) return;
    fetch('/api/slots/unpair?slot=' + slot, { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                location.reload();
            } else {
                notify('Could not unpair: ' + (data.error || 'unknown error'), 'bad');
            }
        })
        .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
};

let targetModalSlot = 1;

window.openSlotActivationModal = function(sNum, name, ip, devId, expInfo, expStatus, isBound) {
    targetModalSlot = sNum;
    const set = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };
    set('modal_slot_title', 'Slot ' + sNum);
    set('modal_slot_sub', isBound ? (name || ('PisoPhone ' + sNum)) : 'Empty slot');
    set('modal_slot_expiry_text', isBound && ip ? ('Address ' + ip) : '');

    const badge = document.getElementById('modal_slot_status_badge');
    if (badge) badge.innerHTML = isBound ? '<span class="tag ok">Paired</span>' : '<span class="tag accent">Free</span>';

    const unpairCont = document.getElementById('modal_unpair_container');
    if (unpairCont) unpairCont.classList.toggle('hidden', !isBound);

    const pairCont = document.getElementById('modal_pair_request_container');
    if (pairCont) {
        const waiting = window.unassignedDevices || [];
        if (!isBound && waiting.length > 0) {
            let html = '<span class="lbl">Phones asking to connect</span>';
            waiting.forEach(u => {
                const uName = u.name || 'PisoPhone';
                html += '<button type="button" class="choice" ' + pairButtonAttrs(u.id, u.ip, uName, sNum) + '>' +
                        '<span><span class="choice-t">Pair ' + escHtml(uName) + '</span><br><span class="choice-s mono">' + escHtml(u.ip) + ' · ' + escHtml(u.id) + '</span></span>' + ic('plus') + '</button>';
            });
            pairCont.innerHTML = html;
            pairCont.classList.remove('hidden');
        } else {
            pairCont.classList.add('hidden');
        }
    }
    showModal('slot_activation_modal');
};

window.closeSlotActivationModal = function() { hideModal('slot_activation_modal'); };

window.submitModalFlash = function() {
    closeSlotActivationModal();
    window.open(setupUrl(targetModalSlot), '_self');
};

window.submitModalUnpair = function() {
    closeSlotActivationModal();
    unpairSlot(targetModalSlot);
};

window.checkQuickAdjustInactive = function() {
    const sel = document.getElementById('quick_adjust_target');
    const warn = document.getElementById('quick_adjust_warn');
    if (!sel || !warn) return false;
    let msg = '';
    if (sel.value === 'ALL') {
        const n = sel.querySelectorAll('option[data-inactive="true"]').length;
        if (n > 0) msg = n + ' phone' + (n > 1 ? 's are' : ' is') + ' not licensed. Time cannot be changed until the slot is licensed.';
    } else {
        const opt = sel.options[sel.selectedIndex];
        if (opt && opt.getAttribute('data-inactive') === 'true') msg = opt.text.replace(/\s*\[.*$/, '') + ' is not licensed. Time cannot be changed until the slot is licensed.';
    }
    warn.classList.toggle('hidden', !msg);
    const text = warn.querySelector('.grow');
    if (text) text.textContent = msg;
    return !!msg;
};

window.validateQuickAdjust = function(e) {
    if (window.checkQuickAdjustInactive()) {
        if (e) {
            e.preventDefault();
            e.stopPropagation();
        }
        notify('That phone is not licensed, so its time cannot be changed.', 'bad');
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
                    notify('Could not unpair: ' + data.error, 'bad');
                }
            })
            .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
    }
});
