// The rental phones' part of the dialogs (added to portal-modals.js for the products that have phones).
const ESP32_MAC = (window.PISO_CFG && window.PISO_CFG.mac) || "";
const ESP32_SECRET = (window.PISO_CFG && window.PISO_CFG.secret) || "";
let activeSlotNum = 1;
window.unassignedDevices = [];

// (the wording of the shared list of given-up payments, for a phone)
Object.assign(ORPHAN_REASONS, {
    'expired': 'The phone did not confirm it for 24 hours.',
    'expired-queue-full': 'The phone did not confirm it for 30 minutes and the queue was almost full.',
    'not-queued': 'The coin was accepted but the box could not record it. It may still have reached the phone: check before refunding.'
});
window.ORPHAN_UNKNOWN = 'unknown phone';

function setupUrl(slot) {
    // the secret travels after the "#": the browser never sends it to the website's server (the setup page then removes it)
    return 'https://pisophone.pages.dev/?mac=' + encodeURIComponent(ESP32_MAC) +
           '&slot=' + encodeURIComponent(slot) +
           '&name=' + encodeURIComponent('PisoPhone ' + slot) +
           '#secret=' + encodeURIComponent(ESP32_SECRET);
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
        if (isBound) {
            html += '<button type="button" class="btn" disabled>Slot ' + s + ' · in use</button>';
        } else {
            html += '<button type="button" class="btn primary" ' + pairButtonAttrs(devId, devIp, devName || '', s) + '>Slot ' + s + '</button>';
        }
    }
    body.innerHTML = html + '</div>';
    showModal('unassigned_pair_modal');
};

window.occupySlot = function(slot) {
    activeSlotNum = slot || 1;
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

window.openSlotActivationModal = function(sNum, name, ip, devId, isBound) {
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
        if (n > 0) msg = n + ' phone' + (n > 1 ? 's are' : ' is') + ' not paired to a slot. Time cannot be changed until it is paired.';
    } else {
        const opt = sel.options[sel.selectedIndex];
        if (opt && opt.getAttribute('data-inactive') === 'true') msg = opt.text.replace(/\s*\[.*$/, '') + ' is not paired to a slot. Time cannot be changed until it is paired.';
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
        notify('That phone is not paired to a slot, so its time cannot be changed.', 'bad');
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


// ---- Phone profiles (Settings): a name and minutes per P1 for each kind of rental device, and a profile for each slot -----------
let profileState = { standard: 6, max: 8, profiles: [], slots: [] };

function profilesFail(data) {
    const why = {
        BAD_NAME: 'A profile name must be 1 to 20 characters, without | ; " < > & or \\.',
        BAD_MINUTES: 'Minutes per \u20b11 must be a whole number from 1 to 1440.',
        NAME_TAKEN: 'Two profiles have the same name.',
        TOO_MANY_PROFILES: 'There is room for ' + profileState.max + ' profiles.',
        CHANGED_ELSEWHERE: 'The profiles were changed somewhere else. The page is reloading them.',
        UNKNOWN_PROFILE: 'That profile no longer exists.'
    };
    notify((data && why[data.error]) || 'Could not save the profiles.', 'bad');
}

function renderProfiles() {
    const list = document.getElementById('profiles_list');
    const slots = document.getElementById('profile_slots');
    if (!list || !slots) return;
    const rows = ['<div class="kv"><span>Standard</span><span class="mono">' + profileState.standard + ' min per \u20b11 (the Coin rate above)</span></div>'];
    profileState.profiles.forEach((p, i) => {
        rows.push('<div class="split profile-row" data-id="' + p.id + '">' +
            '<div class="field"><label>Name</label><input type="text" maxlength="20" class="pf-name" value="' + escHtml(p.name) + '" autocomplete="off"></div>' +
            '<div class="field"><label>Minutes per \u20b11</label><input type="number" min="1" max="1440" class="pf-min" value="' + p.minutes + '"></div>' +
            '<div class="field"><label>&nbsp;</label><button type="button" class="btn sm danger" onclick="removeProfileRow(' + i + ')">Remove</button></div>' +
            '</div>');
    });
    list.innerHTML = rows.join('');
    const add = document.getElementById('profile_add');
    if (add) add.disabled = profileState.profiles.length >= profileState.max;
    const opts = (cur) => '<option value="0"' + (cur === 0 ? ' selected' : '') + '>Standard (' + profileState.standard + ' min)</option>' +
        profileState.profiles.map(p => '<option value="' + p.id + '"' + (cur === p.id ? ' selected' : '') + '>' + escHtml(p.name) + ' (' + p.minutes + ' min)</option>').join('');
    let html = '';
    for (let s = 1; s <= profileState.slots.length; s++) {
        const dev = (window.latestDevicesList || []).find(d => d.slotNum === s);
        const label = (dev && dev.isBound) ? 'Slot ' + s + ' \u00b7 ' + escHtml(dev.name || ('PisoPhone ' + s)) : 'Slot ' + s + ' \u00b7 empty';
        html += '<div class="field"><label for="pslot' + s + '">' + label + '</label>' +
            '<select id="pslot' + s + '" onchange="setSlotProfile(' + s + ', this.value)">' + opts(profileState.slots[s - 1] || 0) + '</select></div>';
    }
    slots.innerHTML = html;
}

// The profile list as it is on the page now (unsaved edits included).
function readProfileRows() {
    return Array.from(document.querySelectorAll('#profiles_list .profile-row')).map(row => ({
        id: row.dataset.id || '0',
        name: row.querySelector('.pf-name').value.trim(),
        minutes: row.querySelector('.pf-min').value.trim()
    }));
}

window.loadProfiles = function() {
    fetch('/api/profiles')
        .then(res => res.json())
        .then(data => {
            if (!data.success) throw new Error(data.error || 'failed');
            profileState = data;
            renderProfiles();
        })
        .catch(() => {});
};

window.addProfileRow = function() {
    const edited = readProfileRows();
    if (edited.length >= profileState.max) return;
    profileState.profiles = edited.map(r => ({ id: parseInt(r.id, 10) || 0, name: r.name, minutes: parseInt(r.minutes, 10) || 0 }));
    profileState.profiles.push({ id: 0, name: '', minutes: profileState.standard });
    renderProfiles();
};

// A preset is a new profile with the preset's name and the standard rate (change the rate and the name freely, then Save). The name
// is also the title on the lock and home screens of the phones in that profile (feature 024).
window.addPresetProfile = function(name) {
    const edited = readProfileRows();
    if (edited.length >= profileState.max) { notify('There is room for ' + profileState.max + ' profiles.', 'bad'); return; }
    if (edited.some(r => r.name.toLowerCase() === name.toLowerCase())) { notify('There is already a profile named ' + name + '.', 'bad'); return; }
    profileState.profiles = edited.map(r => ({ id: parseInt(r.id, 10) || 0, name: r.name, minutes: parseInt(r.minutes, 10) || 0 }));
    profileState.profiles.push({ id: 0, name: name, minutes: profileState.standard });
    renderProfiles();
};

window.removeProfileRow = function(i) {
    const edited = readProfileRows();
    const gone = edited[i];
    if (gone && !confirm('Remove the profile ' + (gone.name || '(unnamed)') + '? Phones that use it go back to the Standard rate.')) return;
    edited.splice(i, 1);
    profileState.profiles = edited.map(r => ({ id: parseInt(r.id, 10) || 0, name: r.name, minutes: parseInt(r.minutes, 10) || 0 }));
    renderProfiles();
};

window.saveProfiles = function() {
    const rows = readProfileRows();
    const list = rows.map(r => (r.id === '0' ? '' : r.id) + '|' + r.name + '|' + r.minutes).join(';');
    const btn = document.getElementById('profile_save');
    if (btn) btn.disabled = true;
    fetch('/api/profiles', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'list=' + encodeURIComponent(list) })
        .then(res => res.json())
        .then(data => {
            if (!data.success) { profilesFail(data); if (data.error === 'CHANGED_ELSEWHERE') window.loadProfiles(); return; }
            profileState = data;
            renderProfiles();
            notify('Profiles saved. Phones were told their rate.', 'ok');
        })
        .catch(() => notify('Could not reach the box.', 'bad'))
        .finally(() => { if (btn) btn.disabled = false; });
};

window.setSlotProfile = function(slot, id) {
    fetch('/api/slots/profile?slot=' + slot + '&profile=' + encodeURIComponent(id), { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (!data.success) { profilesFail(data); window.loadProfiles(); return; }
            profileState = data;
            renderProfiles();
            notify('Slot ' + slot + ' profile saved.', 'ok');
        })
        .catch(() => notify('Could not reach the box.', 'bad'));
};

window.addEventListener('load', function() {
    window.loadProfiles();
    setTimeout(renderProfiles, 3500); // (the phone names arrive with the first status poll)
});


// ---- Shop branding (Settings): the palette, the shop name, the title and the animation, handed to every paired phone ---------------
function brandingFail(data) {
    const why = {
        BAD_PALETTE: 'That colour palette is not one of the list.',
        BAD_DENSITY: 'That animation setting is not one of the list.',
        BAD_NAME: 'The shop name must be up to 32 characters, with no | ; " \\ < > & and no line breaks, and no space at either end.',
        BAD_TITLE: 'The title must be up to 20 characters, with no | ; " \\ < > & and no line breaks, and no space at either end.',
        TOO_LONG_FOR_PUSH: 'The name and title are too long to send to the phones. Use fewer accented characters or shorter words.',
        NO_BRANDING: 'The form was incomplete. Reload the page and try again.'
    };
    notify((data && why[data.error]) || 'Could not save the branding.', 'bad');
}

function showBranding(data) {
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.value = v || ''; };
    set('brand_palette', data.palette);
    set('brand_density', data.density);
    set('brand_name', data.name);
    set('brand_title', data.title);
}

window.loadBranding = function() {
    fetch('/api/branding')
        .then(res => res.json())
        .then(data => { if (data.success) showBranding(data); })
        .catch(() => {});
};

window.saveBranding = function() {
    const val = id => { const el = document.getElementById(id); return el ? el.value.trim() : ''; };
    const body = 'palette=' + encodeURIComponent(val('brand_palette')) + '&density=' + encodeURIComponent(val('brand_density')) +
        '&name=' + encodeURIComponent(val('brand_name')) + '&title=' + encodeURIComponent(val('brand_title'));
    const btn = document.getElementById('brand_save');
    if (btn) btn.disabled = true;
    fetch('/api/branding', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
        .then(res => res.json())
        .then(data => {
            if (!data.success) { brandingFail(data); return; }
            showBranding(data);
            notify('Branding saved. Phones were told.', 'ok');
        })
        .catch(() => notify('Could not reach the box.', 'bad'))
        .finally(() => { if (btn) btn.disabled = false; });
};

window.addEventListener('load', function() { window.loadBranding(); });


// ---- Slot licenses (Phone slots): a key the owner made for this box opens one more slot -----------------------------------------
window.focusSlotLicense = function() {
    const box = document.getElementById('licensebox');
    if (box) box.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const input = document.getElementById('lic_token');
    if (input) input.focus();
};

window.redeemSlotLicense = function() {
    const input = document.getElementById('lic_token');
    const token = input ? input.value.trim() : '';
    if (!token) { notify('Paste the license key first.', 'bad'); return; }
    fetch('/api/license', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'token=' + encodeURIComponent(token) })
        .then(res => res.json())
        .then(data => {
            if (data.success) {
                notify('Slot added. This box can now pair ' + data.cap + ' phones.');
                setTimeout(() => location.reload(), 900);
            } else {
                notify(data.message || data.error || 'The box did not accept that key.', 'bad');
            }
        })
        .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
};
