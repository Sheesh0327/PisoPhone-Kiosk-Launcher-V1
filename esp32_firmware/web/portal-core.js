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
// What a product adds to the shared page: functions run on every status answer, extra banners and extra first-run steps.
window.statusHooks = [];
window.bannerHooks = [];
window.checklistHooks = [];
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
            if (res.ok) return notify(window.SAVED_NOTE || 'Saved.', 'ok');
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

function updateBanners(data) {
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
    window.bannerHooks.forEach(f => f(data));

    // First-run checklist: shown until the box is safe and in use, then it disappears by itself.
    const steps = [
        { done: !data.default_credentials, text: 'Choose your own admin password (Settings).' },
        { done: !!(data.wifi && data.wifi.connected), text: 'Connect the box to your Wi-Fi (Settings).' }
    ];
    window.checklistHooks.forEach(f => f(data).forEach(st => steps.push(st)));
    setBanner('setup_checklist',
        steps.every(st => st.done) ? '' :
            '<div class="panel-head"><h2>Getting started</h2></div><div class="panel-body"><ul class="checklist">' +
            steps.map(st => '<li class="' + (st.done ? 'done' : '') + '">' + ic(st.done ? 'check' : 'circle') + '<span>' + st.text + '</span></li>').join('') +
            '</ul></div>',
        'panel');
}

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
            if (data.wifi) updateWifi(data.wifi);
            updateBanners(data);
            window.statusHooks.forEach(f => f(data));
        })
        .catch(err => { setConn(false); console.log('Status polling error', err); });
};
