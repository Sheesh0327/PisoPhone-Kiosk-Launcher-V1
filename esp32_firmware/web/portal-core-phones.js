// The rental phones' part of the dashboard (added to portal-core.js for the products that have phones).
window.RECEIPT_TITLE = 'PISOPHONE REVENUE COLLECTION';
window.SAVED_NOTE = 'Saved. The phones have the new settings.';

function devicesOf(data) {
    return Array.isArray(data) ? data : (data.devices || []);
}

function batteryHtml(level, charging) {
    if (!(typeof level === 'number' && level >= 0)) return charging ? ic('bolt') + 'Charging' : '–';
    const cls = level <= 15 ? 'low' : (level <= 30 ? 'mid' : '');
    return '<span class="row-batt ' + cls + '">' + (charging ? ic('bolt') : ic('battery')) + level + '%' +
           '<span class="mini-bar"><i style="width:' + level + '%"></i></span></span>';
}

window.bannerHooks.push(function(data) {
    setBanner('legacy_key_banner',
        data.legacy_key
            ? ic('alert') + '<span class="grow"><b>This box still uses the old shared key.</b> Switch it to its own key. Phones must be set up again afterwards.</span>' +
              '<button type="button" class="btn sm" onclick="switchToOwnKey()">Switch key</button>'
            : '',
        'note warn');
});
window.checklistHooks.push(function(data) {
    return [
        { done: !data.legacy_key, text: 'Switch the box to its own key (see above).' },
        { done: devicesOf(data).some(d => d.isBound), text: 'Pair at least one rental phone (Tools, Phone slots).' }
    ];
});

const ROW_HEAD = '<div class="row head"><span>Slot</span><span>Phone</span><span>Status</span><span>Time left</span><span>Battery</span><span></span></div>';

function pendingRow(u) {
    const name = escHtml(u.name || 'New phone');
    const bat = (typeof u.battery === 'number' && u.battery >= 0) ? u.battery : -1;
    return '<div class="row pending">' +
        '<span class="tag warn">New</span>' +
        '<div class="ident"><div class="row-name">' + name + '</div><div class="row-sub">' + escHtml(u.ip) + ' · ' + escHtml(u.id) + '</div></div>' +
        '<span class="pill warn">Wants to connect</span><span></span>' +
        '<span>' + batteryHtml(bat, !!u.charging) + '</span>' +
        '<div class="row-actions"><button type="button" class="btn primary sm" data-id="' + escHtml(u.id) + '" data-ip="' + escHtml(u.ip) + '" data-name="' + name + '" onclick="pairFromRow(this)">Pair…</button></div>' +
        '</div>';
}

window.pairFromRow = function(btn) {
    showSelectSlotModalForDevice(btn.dataset.id, btn.dataset.ip, btn.dataset.name);
};

function deviceRow(dev) {
    const name = escHtml((dev.name && dev.name !== dev.id && !dev.name.startsWith('Terminal') && (!dev.id || !dev.name.includes(dev.id))) ? dev.name : ('PisoPhone ' + dev.slotNum));
    const slot = '<span class="tag">Slot ' + dev.slotNum + '</span>';
    const unpair = '<div class="row-actions"><button type="button" class="btn sm" onclick="unpairSlot(' + dev.slotNum + ')">Unpair</button></div>';
    if (!dev.isBound) {
        return '<div class="row empty">' + slot +
            '<div class="ident"><div class="row-name muted">Empty slot</div></div>' +
            '<span class="muted">Ready for a phone</span><span></span><span></span>' +
            '<div class="row-actions"><button type="button" class="btn sm" onclick="occupySlot(' + dev.slotNum + ')">Set up</button></div></div>';
    }
    const restarts = (dev.restarts > 0) ? ' <span class="pill warn" title="The phone\'s app restarted after a crash ' + dev.restarts + (dev.restarts === 1 ? ' time' : ' times') + ' in the last 24 hours">↻ ' + dev.restarts + '</span>' : '';
    const ident = '<div class="ident"><div class="row-name">' + name + restarts + '</div><div class="row-sub">' + escHtml(dev.ip) + '</div></div>';
    if (!dev.online) {
        return '<div class="row">' + slot + ident + '<span class="pill bad">Offline</span><span></span><span></span>' + unpair + '</div>';
    }
    const renting = dev.time > 0;
    return '<div class="row">' + slot + ident +
        '<span class="pill ' + (renting ? 'ok' : '') + '">' + (renting ? 'Renting' : 'Locked') + '</span>' +
        '<span class="row-time">' + fmtTime(dev.time) + '</span>' +
        '<span>' + batteryHtml(typeof dev.battery === 'number' ? dev.battery : -1, !!dev.charging) + '</span>' +
        '<div class="row-actions"><button type="button" class="btn sm' + (window.locating[dev.ip] ? ' danger' : '') +
        '" data-ip="' + escHtml(dev.ip) + '" onclick="toggleLocate(this)">' + (window.locating[dev.ip] ? 'Stop' : 'Locate') +
        '</button><button type="button" class="btn sm" onclick="unpairSlot(' + dev.slotNum + ')">Unpair</button></div></div>';
}

window.locating = window.locating || {};

window.toggleLocate = function(btn) {
    const ip = btn.dataset.ip;
    const stop = !!window.locating[ip];
    btn.disabled = true;
    fetch('/api/locate?ip=' + encodeURIComponent(ip) + (stop ? '&mode=stop' : ''), { method: 'POST' })
        .then(res => res.json())
        .then(data => {
            if (!data.success) throw new Error(data.error || 'failed');
            if (stop) delete window.locating[ip];
            else window.locating[ip] = true;
            btn.textContent = stop ? 'Locate' : 'Stop';
            btn.classList.toggle('danger', !stop);
            notify(stop ? 'Alarm stopped.' : 'Phone is ringing. It stops by itself after a minute.', 'ok');
            if (!stop) setTimeout(() => { delete window.locating[ip]; }, 60000);
        })
        .catch(err => notify('Could not reach the phone: ' + err.message, 'bad'))
        .finally(() => { btn.disabled = false; });
};

const ACCT_HEAD = '<div class="row head acct"><span>Player</span><span>Time left</span><span>Status</span><span></span></div>';

function acctRow(a) {
    const who = a.name ? escHtml(a.name) : '<span class="muted">No name yet</span>';
    let status;
    if (a.slot > 0) status = '<span class="pill ok">Playing on slot ' + a.slot + '</span>';
    else if (a.idle_min < 0) status = '<span class="muted">–</span>';
    else status = '<span class="muted">Last used ' + (a.idle_min < 60 ? a.idle_min + ' min' : (a.idle_min < 2880 ? Math.round(a.idle_min / 60) + ' h' : Math.round(a.idle_min / 1440) + ' days')) + ' ago</span>';
    const busy = a.slot > 0 ? ' disabled title="Signed in on a phone"' : '';
    const id = Number(a.id);
    return '<div class="row acct">' +
        '<div class="ident"><div class="row-name">' + who + '</div><div class="row-sub">Card No. ' + String(id).padStart(5, '0') + '</div></div>' +
        '<span class="row-time">' + fmtTime(a.balance_sec) + '</span>' +
        status +
        '<div class="row-actions">' +
        '<button type="button" class="btn sm" data-id="' + id + '"' + busy + ' onclick="accountAdjust(this, 1)">+ Time</button>' +
        '<button type="button" class="btn sm" data-id="' + id + '"' + busy + ' onclick="accountAdjust(this, -1)">– Time</button>' +
        '<button type="button" class="btn sm danger" data-id="' + id + '"' + busy + ' onclick="accountDelete(this)">Delete</button>' +
        '</div></div>';
}

window.fetchAccounts = function() {
    const box = document.getElementById('accounts_container');
    if (!box) return;
    fetch('/api/accounts')
        .then(res => { if (res.status === 401) { signedOutAnswer(); throw new Error('signed out'); } signedOutStrikes = 0; if (!res.ok) throw new Error(res.status === 503 ? 'Accounts are off on this box' : 'HTTP ' + res.status); return res.json(); })
        .then(data => {
            const list = data.accounts || [];
            const badge = document.getElementById('accounts_count');
            if (badge) badge.textContent = list.length + ' of ' + data.max;
            const idEl = document.getElementById('accounts_box_id');
            if (idEl) idEl.textContent = data.box_id || '';
            let notes = '';
            if (!data.cards_ready) {
                notes += '<div class="note bad"><svg class="ic"><use href="#i-alert"/></svg><span class="grow"><b>No card key is installed, so every QR card is refused.</b> Run scripts/make_card_key.py on your computer, commit the public key it writes, then rebuild and flash this box.</span></div>';
            }
            if (data.failing) {
                notes += '<div class="note bad"><svg class="ic"><use href="#i-alert"/></svg><span class="grow"><b>Accounts are not being saved to the box\'s memory.</b> Changes since the last good save would be lost if the box lost power. See Tools, Diagnostics.</span></div>';
            }
            box.innerHTML = notes + (list.length
                ? ACCT_HEAD + list.map(acctRow).join('')
                : '<div class="row-empty-msg">No accounts yet. A player\'s account is created the first time they scan a card on a phone.</div>');
        })
        .catch(err => { box.innerHTML = '<div class="row-empty-msg">' + escHtml(err.message) + '</div>'; });
};
document.addEventListener('DOMContentLoaded', fetchAccounts);
pollTimers.push(setInterval(fetchAccounts, 15000));

function accountPost(path, id, extra, done) {
    fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'id=' + encodeURIComponent(id) + (extra || '')
    })
        .then(res => res.json())
        .then(data => {
            if (data.success) { notify(done, 'ok'); fetchAccounts(); }
            else notify('Not changed: ' + (data.error === 'ALREADY_SIGNED_IN' ? 'the player is signed in on a phone.' : (data.error || 'unknown error')), 'bad');
        })
        .catch(err => notify('Could not reach the box: ' + err.message, 'bad'));
}

window.accountAdjust = function(btn, sign) {
    const id = btn.dataset.id;
    const m = parseInt(prompt((sign > 0 ? 'Add how many minutes to card ' : 'Remove how many minutes from card ') + id + '?', '30'), 10);
    if (!m || m < 1) return;
    accountPost('/api/accounts/adjust', id, '&minutes=' + (sign * m), (sign > 0 ? 'Added ' : 'Removed ') + m + ' min ' + (sign > 0 ? 'to ' : 'from ') + 'card ' + id + '.');
};

window.accountDelete = function(btn) {
    const id = btn.dataset.id;
    if (!confirm('Delete the account for card ' + id + '? Any time left in it is lost, and the card gets no new starter time: scanning it again gives an empty account.')) return;
    accountPost('/api/accounts/delete', id, '', 'Deleted the account for card ' + id + '.');
};

window.statusHooks.push(function(data) {
    const devices = devicesOf(data);
    const container = document.getElementById('live_devices_container');
        if (!container) return;

        window.latestDevicesList = devices;
        const unassigned = data.unassigned_devices || [];
        window.unassignedDevices = unassigned;

        const paired = devices.filter(d => d.isBound);
        const online = paired.filter(d => d.online).length;
        const stat = document.getElementById('stat_phones');
        if (stat) stat.textContent = paired.length;
        const sub = document.getElementById('stat_phones_sub');
        if (sub) sub.textContent = paired.length === 0 ? 'None paired yet' : (online + ' online');

        const reqBadge = document.getElementById('pending_requests_badge');
        if (reqBadge) {
            reqBadge.classList.toggle('hidden', unassigned.length === 0);
            reqBadge.textContent = unassigned.length + ' pair request' + (unassigned.length > 1 ? 's' : '');
        }

        let html = unassigned.map(pendingRow).join('');
        devices.forEach(dev => {
            html += deviceRow(dev);
        });
        container.innerHTML = html
            ? ROW_HEAD + html
            : '<div class="row-empty-msg">No phones yet. Open Tools, Phone slots, to set one up.</div>';
});

function showMatchResult(kind, html) {
    const el = document.getElementById('match_qual_result');
    if (!el) return;
    el.className = 'note ' + kind;
    el.innerHTML = ic(kind === 'ok' ? 'check' : (kind === 'bad' ? 'alert' : 'clock')) + '<span class="grow">' + html + '</span>';
}

window.checkMatchQualification = function() {
    const p1 = document.getElementById('p1_select') ? document.getElementById('p1_select').value : '';
    const p2 = document.getElementById('p2_select') ? document.getElementById('p2_select').value : '';
    const mins = document.getElementById('match_mins_input') ? document.getElementById('match_mins_input').value : '15';
    if (!p1 || !p2) return showMatchResult('bad', 'Choose both players.');
    if (p1 === p2) return showMatchResult('bad', 'Player 1 and Player 2 must be different phones.');
    showMatchResult('', 'Checking the time on both phones…');

    fetch('/check_qualification?p1=' + encodeURIComponent(p1) + '&p2=' + encodeURIComponent(p2) + '&minutes=' + encodeURIComponent(mins))
        .then(res => res.json())
        .then(data => {
            if (data && data.success) {
                const lines = '<br>Player 1 (' + escHtml(data.p1_ip || p1) + '): <b>' + escHtml(data.p1_formatted) + '</b><br>Player 2 (' + escHtml(data.p2_ip || p2) + '): <b>' + escHtml(data.p2_formatted) + '</b>';
                if (data.qualified) {
                    showMatchResult('ok', '<b>Both players can stake ' + escHtml(data.stake_minutes || mins) + ' minutes.</b>' + lines);
                } else {
                    showMatchResult('bad', '<b>Not enough time to stake.</b>' + lines + '<br>' + escHtml(data.message || data.error || ''));
                }
            } else {
                showMatchResult('bad', escHtml(data && data.error ? data.error : 'Could not check the players.'));
            }
        }).catch(err => showMatchResult('bad', 'Could not reach the box: ' + escHtml(err)));
};

window.toggle1v1MatchBox = function() {
    const content = document.getElementById('match_collapsible_content');
    const btn = document.getElementById('match_toggle_btn');
    if (!content) return;
    const open = content.classList.toggle('hidden') === false;
    if (btn) btn.innerHTML = open ? ic('x') + 'Close' : ic('users') + 'Start a match';
};
