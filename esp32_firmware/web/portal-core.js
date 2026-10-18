// Light or dark, and the embedded view (this page inside the router console's Rental phones tab). A theme asked for in the address (?theme=) or by the
// console's message is used without being saved; the button saves the owner's own choice.
(function () {
    var root = document.documentElement;
    var framed = window.parent !== window;
    function apply(t) { if (t === 'dark' || t === 'light') root.setAttribute('data-theme', t); }
    var q = new URLSearchParams(location.search);
    try { if (framed && q.get('embed') === '1') sessionStorage.setItem('piso_embed', '1'); } catch (e) { /* private mode */ }
    try { if (framed && sessionStorage.getItem('piso_embed') === '1') root.classList.add('embed'); } catch (e) { /* private mode */ }
    var t = q.get('theme');
    if (!t) { try { t = localStorage.getItem('piso_theme'); } catch (e) { /* private mode */ } }
    apply(t);
    window.toggleTheme = function () {
        var now = root.getAttribute('data-theme') || (window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
        var next = now === 'dark' ? 'light' : 'dark';
        apply(next);
        try { localStorage.setItem('piso_theme', next); } catch (e) { /* private mode */ }
    };
    window.addEventListener('message', function (e) {
        if (!framed || e.source !== window.parent) return;
        try { if (new URL(e.origin).hostname !== location.hostname) return; } catch (x) { return; }
        if (e.data && e.data.piso === 'theme') apply(e.data.theme);
    });
})();

var pollTimers = []; // the background polls (see sessionLost)
var signedOutStrikes = 0; // consecutive "not signed in" answers: one stray answer does not end the page
function escHtml(v) {
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// Line icon from the sprite every page carries (see WebDashboardIcons.h).
window.ic = function(name) {
    return '<svg class="ic" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
};

// A short message at the bottom of the page. kind is 'ok' (default) or 'bad'.
window.notify = function(msg, kind) {
    const old = document.getElementById('toast');
    if (old) old.remove();
    const t = document.createElement('div');
    t.id = 'toast';
    t.className = 'toast' + (kind === 'bad' ? ' bad' : '');
    t.setAttribute('role', 'status');
    t.innerHTML = ic(kind === 'bad' ? 'alert' : 'check') + '<span></span>';
    t.lastChild.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function() { if (t.parentNode) t.remove(); }, 4000);
};

window.copyToClipboard = function(text, btnElement) {
    if (!text) return;
    const doFeedback = () => {
        if (btnElement) {
            const orig = btnElement.innerHTML;
            btnElement.innerHTML = ic('check') + 'Copied';
            setTimeout(() => { btnElement.innerHTML = orig; }, 1800);
        }
    };
    const fallback = () => {
        const ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        doFeedback();
    };
    if (navigator.clipboard && window.isSecureContext) {
        navigator.clipboard.writeText(text).then(doFeedback).catch(fallback);
    } else {
        fallback();
    }
};

window.switchTab = function(tabId) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    const tabBtn = document.querySelector(`[onclick="switchTab('${tabId}')"]`);
    if (tabBtn) tabBtn.classList.add('active');
    const tabEl = document.getElementById(tabId);
    if (tabEl) tabEl.classList.add('active');
    try { localStorage.setItem('activeTab', tabId); } catch (e) { /* private mode */ }
};

document.addEventListener('DOMContentLoaded', () => {
    let savedTab = 'tab-dashboard';
    try { savedTab = localStorage.getItem('activeTab') || savedTab; } catch (e) { /* private mode */ }
    if (document.getElementById(savedTab)) switchTab(savedTab);
});

window.saveSettings = function(form, ev) {
    if (ev) ev.preventDefault();
    fetch('/save', { method: 'POST', body: new URLSearchParams(new FormData(form)) })
        .then(async res => {
            if (res.ok) return notify('Saved. The phones have the new settings.', 'ok');
            let why = '';
            try { why = (await res.json()).message || ''; } catch (e) { /* not JSON */ }
            notify('Not saved, nothing was changed. ' + (why || 'Could not save the settings.'), 'bad');
        })
        .catch(err => notify('Could not reach the box: ' + err, 'bad'));
    return false;
};

window.relayTest = function(state) {
    fetch('/api/relay?state=' + state, { method: 'POST' })
        .then(r => r.json())
        .then(d => notify('Relay on pin ' + d.relay_pin + ' is now ' + (state ? 'on' : 'off') + '.'))
        .catch(err => notify('Could not reach the box: ' + err, 'bad'));
};

window.rebootBox = function() {
    if (!confirm('Restart the coin box?')) return;
    fetch('/reboot', { method: 'POST' }).then(() => {
        notify('Restarting. This page reloads in a few seconds.');
        setTimeout(() => window.location.reload(), 5000);
    });
};

window.factoryResetBox = function() {
    if (!confirm('Factory reset? All settings on the box will be erased.')) return;
    fetch('/factory_reset', { method: 'POST' }).then(() => {
        notify('Resetting. This page reloads in a few seconds.');
        setTimeout(() => window.location.reload(), 6000);
    });
};

// Banners across the top of the page: shown while they apply, removed by themselves.
function setBanner(id, html, cls) {
    const host = document.getElementById('banners');
    if (!host) return;
    let el = document.getElementById(id);
    if (!html) {
        if (el) el.remove();
        return;
    }
    if (!el) {
        el = document.createElement('div');
        el.id = id;
        host.appendChild(el);
    }
    if (el.dataset.html !== html) {
        el.dataset.html = html;
        el.className = cls;
        el.innerHTML = html;
    }
}

function fmtTime(sec) {
    if (!(sec > 0)) return '–';
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    const two = n => (n < 10 ? '0' : '') + n;
    return h > 0 ? (h + ':' + two(m) + ':' + two(s)) : (m + ':' + two(s));
}

function batteryHtml(level, charging) {
    if (!(typeof level === 'number' && level >= 0)) return charging ? ic('bolt') + 'Charging' : '–';
    const cls = level <= 15 ? 'low' : (level <= 30 ? 'mid' : '');
    return '<span class="row-batt ' + cls + '">' + (charging ? ic('bolt') : ic('battery')) + level + '%' +
           '<span class="mini-bar"><i style="width:' + level + '%"></i></span></span>';
}

function updateWifi(wifi) {
    const rssi = typeof wifi.rssi === 'number' ? wifi.rssi : -100;
    const quality = typeof wifi.quality === 'number' ? wifi.quality : 0;
    const connected = !!wifi.connected && quality > 0;
    const card = document.getElementById('wifi_stat');
    if (card) card.className = 'stat' + (!connected || quality < 25 ? ' bad' : (quality < 50 ? ' warn' : ''));
    const set = (id, text) => { const el = document.getElementById(id); if (el) el.textContent = text; };
    set('wifi_quality_pct', connected ? quality + '%' : 'Offline');
    set('wifi_rssi_display', connected ? rssi + ' dBm' : 'not connected');
    if (wifi.ssid) set('wifi_ssid_display', wifi.ssid);
    if (wifi.ip) set('wifi_ip_display', wifi.ip);
    const fill = document.getElementById('wifi_meter_fill');
    if (fill) fill.style.width = (connected ? quality : 0) + '%';
}

function updateBanners(data, devices) {
    setBanner('default_cred_banner',
        data.default_credentials
            ? ic('alert') + '<span class="grow"><b>The default admin password is still in use.</b> Coins are blocked until you choose your own in Settings.</span>'
            : '',
        'note bad');
    // Payments the box had to give up on: a customer paid and the phone never got the time. They stay here until handled.
    const orphans = data.orphans || {};
    setBanner('orphan_banner',
        orphans.unhandled > 0
            ? ic('alert') + '<span class="grow"><b>' + orphans.unhandled + (orphans.unhandled === 1 ? ' payment' : ' payments') +
              ' (₱' + orphans.pulses + ') could not be delivered.</b> A customer paid but did not get the time. Refund or credit them, then mark them handled.</span>' +
              '<button type="button" class="btn sm" onclick="showOrphans()">Review</button>'
            : '',
        'note bad');
    setBanner('legacy_key_banner',
        data.legacy_key
            ? ic('alert') + '<span class="grow"><b>This box still uses the old shared key.</b> Switch it to its own key. Phones must be set up again afterwards.</span>' +
              '<button type="button" class="btn sm" onclick="switchToOwnKey()">Switch key</button>'
            : '',
        'note warn');

    // First-run checklist: shown until the box is safe and in use, then it disappears by itself.
    const steps = [
        { done: !data.default_credentials, text: 'Choose your own admin password (Settings).' },
        { done: !!(data.wifi && data.wifi.connected), text: 'Connect the box to your Wi-Fi (Settings).' },
        { done: !data.legacy_key, text: 'Switch the box to its own key (see above).' },
        { done: devices.some(d => d.isBound), text: 'Pair at least one rental phone (Tools, Phone slots).' }
    ];
    setBanner('setup_checklist',
        steps.every(st => st.done) ? '' :
            '<div class="panel-head"><h2>Getting started</h2></div><div class="panel-body"><ul class="checklist">' +
            steps.map(st => '<li class="' + (st.done ? 'done' : '') + '">' + ic(st.done ? 'check' : 'circle') + '<span>' + st.text + '</span></li>').join('') +
            '</ul></div>',
        'panel');
}

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

function setConn(ok) {
    const pill = document.getElementById('conn_pill');
    if (!pill) return;
    pill.className = 'pill ' + (ok ? 'ok' : 'bad');
    pill.textContent = ok ? 'Online' : 'Connection lost';
}

// The sign-in ended (a restart of the box, another password, a lock-out): stop polling and say so once, with a way back in. The
// browser's own password box is not used for these background requests, so it cannot pop up every few seconds.
function signedOutAnswer() {
    signedOutStrikes++;
    if (signedOutStrikes >= 2) sessionLost();
}

function sessionLost() {
    pollTimers.forEach(clearInterval);
    pollTimers = [];
    if (document.getElementById('session_lost')) return;
    const bar = document.createElement('div');
    bar.id = 'session_lost';
    bar.className = 'note bad';
    bar.style.cssText = 'position:fixed;left:12px;right:12px;bottom:12px;z-index:50;margin:0';
    bar.innerHTML = '<span class="grow"><b>You are signed out.</b> <a href="/signin?next=/">Sign in again</a></span>';
    document.body.appendChild(bar);
}

window.fetchDeviceStatus = function() {
    fetch('/api/status')
        .then(res => { if (res.status === 401) { signedOutAnswer(); throw new Error('signed out'); } signedOutStrikes = 0; return res.json(); })
        .then(data => {
            setConn(true);
            const devices = Array.isArray(data) ? data : (data.devices || []);
            if (data.wifi) updateWifi(data.wifi);
            updateBanners(data, devices);

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
        })
        .catch(err => { setConn(false); console.log('Status polling error', err); });
};
pollTimers.push(setInterval(fetchDeviceStatus, 3000));
document.addEventListener("DOMContentLoaded", fetchDeviceStatus);

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
