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
        .then(res => notify(res.ok ? 'Saved. The phones have the new settings.' : 'Could not save the settings.', res.ok ? 'ok' : 'bad'))
        .catch(err => notify('Could not reach the box: ' + err, 'bad'));
    return false;
};

window.relayTest = function(state) {
    fetch('/api/relay?state=' + state)
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
    const ident = '<div class="ident"><div class="row-name">' + name + '</div><div class="row-sub">' + escHtml(dev.ip) + '</div></div>';
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

function setConn(ok) {
    const pill = document.getElementById('conn_pill');
    if (!pill) return;
    pill.className = 'pill ' + (ok ? 'ok' : 'bad');
    pill.textContent = ok ? 'Online' : 'Connection lost';
}

window.fetchDeviceStatus = function() {
    fetch('/api/status')
        .then(res => res.json())
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
setInterval(fetchDeviceStatus, 3000);
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
